/* Copyright 2024-2026 WeisGnosis. Licensed under the Apache License, Version 2.0. */
package com.weisgnosis.agents.core.client

import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.json.JsonBoundary
import com.weisgnosis.agents.core.model.{ContentBlock, Msg, Role}
import com.weisgnosis.agents.core.tool.ToolDef
import zio.Chunk
import zio.json.ast.Json

/** Explicit host selection; model output cannot enable provider execution. */
enum GeminiCodeExecution:
  case Disabled, Enabled

private[client] object GeminiMessagesRequest:
  def encode(
      history: Chunk[Msg],
      tools: Chunk[ToolDef],
      config: LlmConfig,
      execution: GeminiCodeExecution
  ): Either[AgentError, Json] =
    for
      _ <- Either.cond(
        tools.isEmpty && !config.model.isBlank && config.maxTokens.forall(_ > 0) &&
          config.temperature.isFinite && config.temperature >= 0 && config.temperature <= 2 &&
          config.topP.forall(value => value.isFinite && value >= 0 && value <= 1) &&
          config.responseFormat.isEmpty && config.toolChoice.isEmpty && config.parallelToolCalls.isEmpty &&
          config.frequencyPenalty.isEmpty && config.presencePenalty.isEmpty && config.seed.isEmpty &&
          config.reasoningEffort.isEmpty && config.thinkingBudget.isEmpty,
        (),
        AgentError.InvalidConfiguration(
          "Gemini text/code profile does not support local tools, native response formats, tool choice, penalties, seed or reasoning settings"
        )
      )
      system <- traverse(history.filter(_.role == Role.System))(message =>
        textParts(message.content)
      )
      contents <- traverse(history.filterNot(_.role == Role.System))(message =>
        for
          parts <-
            if message.role == Role.Assistant && message.metadata.contains("gemini_parts") then
              GeminiMessageStream.replay(message)
            else if message.role == Role.User || message.role == Role.Assistant then
              textParts(message.content)
            else
              Left(
                AgentError.InvalidMessage("Gemini code profile cannot replay local tool messages")
              )
          _ <- Either.cond(parts.nonEmpty, (), AgentError.InvalidMessage("Gemini content is empty"))
        yield Json.Obj(
          "role"  -> Json.Str(if message.role == Role.Assistant then "model" else "user"),
          "parts" -> Json.Arr(parts)
        )
      )
      _ <- Either.cond(
        contents.nonEmpty,
        (),
        AgentError.InvalidMessage("Gemini contents are empty")
      )
      generation = Json.Obj(
        Chunk(
          "temperature"     -> Json.Num(config.temperature),
          "maxOutputTokens" -> Json.Num(config.maxTokens.getOrElse(1024))
        ) ++ Chunk.fromIterable(config.topP.map(value => "topP" -> Json.Num(value))) ++
          Chunk.fromIterable(
            config.stopSequences.map(value =>
              "stopSequences" -> Json.Arr(value.values.map(Json.Str(_)))
            )
          )
      )
      body = Json.Obj(
        Chunk("contents" -> Json.Arr(contents), "generationConfig" -> generation) ++
          (if system.flatten.isEmpty then Chunk.empty
           else Chunk("systemInstruction" -> Json.Obj("parts" -> Json.Arr(system.flatten)))) ++
          (if execution == GeminiCodeExecution.Enabled then
             Chunk("tools" -> Json.Arr(Json.Obj("codeExecution" -> Json.Obj())))
           else Chunk.empty)
      )
      checked <- JsonBoundary.validate(body).left.map(AgentError.InvalidMessage(_))
      _ <- Either.cond(
        JsonBoundary.validUnicode(checked),
        (),
        AgentError.InvalidMessage("Malformed Unicode in Gemini request")
      )
    yield checked

  private def textParts(blocks: Chunk[ContentBlock]): Either[AgentError, Chunk[Json]] =
    traverse(blocks):
      case ContentBlock.Text(value) => Right(Json.Obj("text" -> Json.Str(value)))
      case _ =>
        Left(
          AgentError.InvalidMessage(
            "Gemini profile requires text or exact native code replay; media and thinking are not supported"
          )
        )

  private def traverse[A, B](
      values: Chunk[A]
  )(f: A => Either[AgentError, B]): Either[AgentError, Chunk[B]] =
    values.foldLeft[Either[AgentError, Chunk[B]]](Right(Chunk.empty))((previous, value) =>
      for accumulated <- previous; next <- f(value) yield accumulated :+ next
    )
