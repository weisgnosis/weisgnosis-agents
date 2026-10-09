/* Copyright 2024-2026 WeisGnosis. Licensed under the Apache License, Version 2.0. */
package com.weisgnosis.agents.core.client

import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.json.JsonBoundary
import com.weisgnosis.agents.core.model.{ChatUsage, ContentBlock, Msg, ToolResultState}
import zio.Chunk
import zio.json.*
import zio.json.ast.Json

/** One candidate, ordered native parts and provider-owned code/result pairs. */
final private[client] case class GeminiMessageStream private (
    correlation: String,
    execution: GeminiCodeExecution,
    content: Chunk[ContentBlock],
    nativeParts: Chunk[Json],
    pending: Option[(String, Option[String])],
    finishReason: Option[String],
    usage: ChatUsage
):
  import GeminiMessageStream.*

  def finished: Boolean = finishReason.nonEmpty

  def accept(frame: SseEvent): Either[AgentError, (GeminiMessageStream, Chunk[LlmChunk])] =
    for
      json <- JsonBoundary.decode(frame.data).left.map(AgentError.DeserializationError(_))
      obj  <- json.asObject.toRight(invalid("Gemini response must be an object"))
      _    <- ensure(JsonBoundary.validUnicode(obj), "Malformed Unicode in Gemini response")
      _    <- ensure(!finished, "Gemini data arrived after completion")
      _    <- ensure(obj.get("error").isEmpty, "Gemini stream reported an error")
      candidates <- obj
        .get("candidates")
        .flatMap(_.asArray)
        .toRight(invalid("Missing Gemini candidates"))
      _ <- ensure(candidates.size == 1, "Gemini profile requires exactly one candidate")
      candidate <- candidates.headOption
        .flatMap(_.asObject)
        .toRight(invalid("Invalid Gemini candidate"))
      _ <- ensure(
        candidate.get("index").forall(_ == Json.Num(0)),
        "Unsupported Gemini candidate index"
      )
      parts <- candidate.get("content") match
        case None => Right(Chunk.empty)
        case Some(value: Json.Obj) =>
          for
            _ <- ensure(
              value.get("role").forall(_ == Json.Str("model")),
              "Gemini candidate role is not model"
            )
            parts <- value.get("parts").flatMap(_.asArray).toRight(invalid("Missing Gemini parts"))
          yield parts
        case _ => Left(invalid("Invalid Gemini candidate content"))
      accepted <- append(parts)
      (next, deltas) = accepted
      counts <- obj.get("usageMetadata") match
        case None                  => Right(next.usage)
        case Some(value: Json.Obj) => readUsage(value, next.usage)
        case _                     => Left(invalid("Invalid Gemini usage"))
      finalState <- candidate.get("finishReason") match
        case None => Right(next.copy(usage = counts))
        case Some(Json.Str("STOP")) =>
          ensure(
            next.pending.isEmpty && next.content.nonEmpty,
            "Gemini completed with unresolved code or no content"
          )
            .map(_ => next.copy(finishReason = Some("STOP"), usage = counts))
        case Some(Json.Str("MAX_TOKENS")) =>
          Right(next.copy(finishReason = Some("MAX_TOKENS"), usage = counts))
        case _ => Left(invalid("Incomplete or unsupported Gemini finish reason"))
    yield (finalState, deltas :+ LlmChunk.UsageChunk(counts))

  private def append(
      parts: Chunk[Json]
  ): Either[AgentError, (GeminiMessageStream, Chunk[LlmChunk])] =
    parts.foldLeft[Either[AgentError, (GeminiMessageStream, Chunk[LlmChunk])]](
      Right(this -> Chunk.empty)
    )((previous, part) =>
      previous.flatMap((state, deltas) =>
        state.appendPart(part).map((next, fresh) => next -> (deltas ++ fresh))
      )
    )

  private def appendPart(raw: Json): Either[AgentError, (GeminiMessageStream, Chunk[LlmChunk])] =
    for
      part <- raw.asObject.toRight(invalid("Gemini part must be an object"))
      _ <- ensure(
        part.fields.forall((key, _) =>
          Set("text", "executableCode", "codeExecutionResult", "thoughtSignature", "thought")
            .contains(key)
        ),
        "Unsupported Gemini part fields"
      )
      _ <- ensure(
        part.get("thought").forall(_ == Json.Bool(false)),
        "Gemini thought content is outside this profile"
      )
      _ <- ensure(
        part.get("thoughtSignature").forall(_.asString.exists(_.nonEmpty)),
        "Invalid Gemini thought signature"
      )
      data = Chunk("text", "executableCode", "codeExecutionResult").flatMap(key =>
        part.get(key).map(key -> _)
      )
      _ <- ensure(data.size == 1, "Gemini part requires exactly one supported payload")
      accepted <- data.headOption match
        case Some(("text", Json.Str(value))) =>
          val index = content.size
          Right(
            copy(content = content :+ ContentBlock.Text(value)) -> Chunk(
              LlmChunk.TextDelta(value, index)
            )
          )
        case Some(("executableCode", value: Json.Obj)) =>
          for
            _ <- ensure(
              execution == GeminiCodeExecution.Enabled && pending.isEmpty,
              "Unexpected or overlapping Gemini code execution"
            )
            _ <- ensure(
              value.fields.forall((key, _) => Set("id", "language", "code").contains(key)),
              "Unsupported executableCode fields"
            )
            language   <- string(value, "language")
            _          <- ensure(language == "PYTHON", "Unsupported Gemini execution language")
            _          <- string(value, "code")
            providerId <- optionalId(value)
            _ <- ensure(
              providerId.forall(id =>
                !content.exists {
                  case call: ContentBlock.ToolUse =>
                    call.metadata.get("gemini_code_id").contains(Json.Str(id))
                  case _ => false
                }
              ),
              "Duplicate Gemini code identity"
            )
            id = s"gemini-code:$correlation:${content.size}"
            call = ContentBlock
              .ToolUse(
                id,
                "code_execution",
                value,
                metadata = providerId.fold(Map.empty[String, Json])(id =>
                  Map("gemini_code_id" -> Json.Str(id))
                )
              )
              .withServerTool(true)
          yield copy(content = content :+ call, pending = Some(id -> providerId)) ->
            Chunk(
              LlmChunk.ToolCallChunk(
                content.size,
                Some(id),
                Some("code_execution"),
                JsonBoundary.render(value)
              )
            )
        case Some(("codeExecutionResult", value: Json.Obj)) =>
          for
            waiting <- pending.toRight(invalid("Gemini execution result has no pending code"))
            _ <- ensure(
              value.fields.forall((key, _) => Set("id", "outcome", "output").contains(key)),
              "Unsupported codeExecutionResult fields"
            )
            providerId <- optionalId(value)
            _ <- ensure(
              providerId == waiting._2,
              "Gemini execution result identity differs from its code"
            )
            status <- string(value, "outcome")
            state <- status match
              case "OUTCOME_OK"                                   => Right(ToolResultState.Success)
              case "OUTCOME_FAILED" | "OUTCOME_DEADLINE_EXCEEDED" => Right(ToolResultState.Error)
              case _ => Left(invalid("Unsupported Gemini code execution outcome"))
            output <- value.get("output") match
              case None                  => Right("")
              case Some(Json.Str(value)) => Right(value)
              case _                     => Left(invalid("Invalid Gemini execution output"))
            result = ContentBlock.ToolResult.text(
              waiting._1,
              "code_execution",
              output,
              state,
              Map("agentscope_server_tool" -> Json.Bool(true), "gemini_outcome" -> Json.Str(status))
            )
          yield copy(content = content :+ result, pending = None) -> Chunk.empty
        case _ => Left(invalid("Invalid Gemini part payload"))
    yield (accepted._1.copy(nativeParts = nativeParts :+ raw), accepted._2)

  def partial: TurnOutput.Interrupted = TurnOutput.Interrupted(message)

  private def message: Msg = Msg.assistant(
    content,
    metadata = Map(
      "gemini_parts"       -> Json.Arr(nativeParts),
      "gemini_correlation" -> Json.Str(correlation)
    ),
    usage = Some(usage)
  )

  def output: Either[AgentError, TurnOutput] =
    ensure(finished, "Gemini stream ended before a successful finish reason").map(_ =>
      if finishReason.contains("MAX_TOKENS") then TurnOutput.tokenExhausted(message)
      else if content.exists(_.isInstanceOf[ContentBlock.ToolUse]) then
        TurnOutput.ToolCalls(message)
      else TurnOutput.FinalText(message)
    )

private[client] object GeminiMessageStream:
  def empty(correlation: String, execution: GeminiCodeExecution): GeminiMessageStream =
    GeminiMessageStream(correlation, execution, Chunk.empty, Chunk.empty, None, None, ChatUsage())

  def replay(message: Msg): Either[AgentError, Chunk[Json]] =
    for
      parts <- message.metadata
        .get("gemini_parts")
        .flatMap(_.asArray)
        .toRight(invalid("Missing native Gemini replay parts"))
      correlation <- message.metadata
        .get("gemini_correlation")
        .flatMap(_.asString)
        .toRight(invalid("Missing Gemini replay correlation"))
      restored <- empty(correlation, GeminiCodeExecution.Enabled).append(parts).map(_._1)
      _ <- ensure(
        restored.pending.isEmpty && restored.content == message.content,
        "Gemini replay parts differ from canonical content"
      )
    yield parts

  private def invalid(message: String): AgentError = AgentError.DeserializationError(message)
  private def ensure(value: Boolean, message: String): Either[AgentError, Unit] =
    Either.cond(value, (), invalid(message))
  private def string(value: Json.Obj, key: String): Either[AgentError, String] =
    value.get(key).flatMap(_.asString).toRight(invalid(s"Missing Gemini string: $key"))
  private def optionalId(value: Json.Obj): Either[AgentError, Option[String]] =
    value.get("id") match
      case None                              => Right(None)
      case Some(Json.Str(id)) if !id.isBlank => Right(Some(id))
      case _                                 => Left(invalid("Invalid Gemini code identity"))

  private def readUsage(value: Json.Obj, previous: ChatUsage): Either[AgentError, ChatUsage] =
    def count(key: String, fallback: Int): Either[AgentError, Int] = value.get(key) match
      case None      => Right(fallback)
      case Some(raw) => raw.as[Int].left.map(AgentError.DeserializationError(_))
    for
      input    <- count("promptTokenCount", previous.inputTokens)
      output   <- count("candidatesTokenCount", previous.outputTokens)
      cached   <- count("cachedContentTokenCount", previous.cachedTokens)
      thoughts <- count("thoughtsTokenCount", previous.reasoningTokens)
      usage <- previous
        .copy(
          inputTokens = input,
          outputTokens = output,
          cachedTokens = cached,
          reasoningTokens = thoughts
        )
        .validated
    yield usage
