/*
 * Copyright 2024-2026 WeisGnosis (https://weisgnosis.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.weisgnosis.agents.core.client

import com.weisgnosis.agents.core.json.JsonBoundary
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.model.{ChatUsage, ContentBlock, Msg, Role}
import zio.Chunk
import zio.json.*
import zio.json.ast.Json

/** Strict single-choice completion deltas and pure reasoning-history transformations. */
object ReasoningModelAdapter:

  final private case class StreamIndex(value: Int)

  private object StreamIndex:
    given JsonDecoder[StreamIndex] = nonnegativeInteger.map(StreamIndex(_))

  final private case class TokenCount(value: Int)

  private object TokenCount:
    given JsonDecoder[TokenCount] = nonnegativeInteger.map(TokenCount(_))

  private def nonnegativeInteger: JsonDecoder[Int] = JsonDecoder[Json].mapOrFail:
    case number: Json.Num =>
      number
        .as[Int]
        .flatMap(value => Either.cond(value >= 0, value, "Expected a nonnegative integer"))
    case _ => Left("Expected a JSON integer")

  final private case class FunctionDelta(
      name: Option[String] = None,
      arguments: Option[String] = None
  ) derives JsonDecoder

  final private case class ToolDelta(
      index: StreamIndex,
      id: Option[String] = None,
      function: Option[FunctionDelta] = None,
      @jsonField("type") kind: Option[String] = None,
      @jsonField("extra_content") extraContent: Option[Json] = None
  ) derives JsonDecoder

  final private case class Delta(
      content: Option[String] = None,
      @jsonField("reasoning_content") reasoningContent: Option[String] = None,
      @jsonField("reasoning_details") reasoningDetails: Option[Json] = None,
      thinking: Option[String] = None,
      reasoning: Option[String] = None,
      @jsonField("tool_calls") toolCalls: Option[Chunk[ToolDelta]] = None,
      @jsonField("function_call") legacyFunctionCall: Option[Json] = None,
      role: Option[String] = None,
      refusal: Option[String] = None
  ) derives JsonDecoder

  final private case class Choice(
      delta: Delta,
      index: StreamIndex,
      @jsonField("finish_reason") finishReason: Option[String] = None
  ) derives JsonDecoder

  final private case class PromptDetails(
      @jsonField("cached_tokens") cachedTokens: Option[TokenCount] = None,
      @jsonField("cache_write_tokens") cacheWriteTokens: Option[TokenCount] = None
  ) derives JsonDecoder

  final private case class CompletionDetails(
      @jsonField("reasoning_tokens") reasoningTokens: Option[TokenCount] = None
  ) derives JsonDecoder

  final private case class Usage(
      @jsonField("prompt_tokens") promptTokens: Option[TokenCount] = None,
      @jsonField("completion_tokens") completionTokens: Option[TokenCount] = None,
      @jsonField("prompt_cache_hit_tokens") promptCacheHitTokens: Option[TokenCount] = None,
      @jsonField("prompt_tokens_details") promptDetails: Option[PromptDetails] = None,
      @jsonField("completion_tokens_details") completionDetails: Option[CompletionDetails] = None
  ) derives JsonDecoder

  final private case class Completion(
      choices: Chunk[Choice],
      usage: Option[Usage] = None
  ) derives JsonDecoder

  final private case class ProviderError(message: Option[String] = None) derives JsonDecoder

  /**
   * Decodes an OpenAI-compatible SSE data payload without silently discarding malformed fields.
   * SSE framing and the [DONE] marker belong to SseParser. Only choice zero is supported.
   */
  def parseChunk(jsonStr: String): Either[AgentError, Chunk[LlmChunk]] =
    parseChunk(jsonStr, ToolSignatureFormat.Unsupported)

  def parseChunk(
      jsonStr: String,
      toolSignatureFormat: ToolSignatureFormat
  ): Either[AgentError, Chunk[LlmChunk]] =
    parseChunk(jsonStr, toolSignatureFormat, ReasoningReplay.Omit)

  def parseChunk(
      jsonStr: String,
      toolSignatureFormat: ToolSignatureFormat,
      reasoningReplay: ReasoningReplay
  ): Either[AgentError, Chunk[LlmChunk]] =
    for
      json <- JsonBoundary.decode(jsonStr).left.map(AgentError.DeserializationError(_))
      chunks <- json match
        case obj: Json.Obj =>
          obj.get("error") match
            case Some(error) if error != Json.Null =>
              error
                .as[ProviderError]
                .left
                .map(AgentError.DeserializationError(_))
                .flatMap(value =>
                  Left(
                    AgentError.ModelError(
                      200,
                      value.message
                        .filter(_.nonEmpty)
                        .getOrElse("Completion stream reported an error")
                    )
                  )
                )
            case _ =>
              (for
                _ <- Either.cond(
                  obj.get("choices").exists(_.isInstanceOf[Json.Arr]),
                  (),
                  "Completion choices must be an explicit array"
                )
                value   <- obj.as[Completion]
                decoded <- decodeCompletion(value, toolSignatureFormat, reasoningReplay)
              yield decoded).left.map(AgentError.DeserializationError(_))
        case _ => Left(AgentError.DeserializationError("Completion chunk must be an object"))
    yield chunks

  private def decodeCompletion(
      value: Completion,
      toolSignatureFormat: ToolSignatureFormat,
      reasoningReplay: ReasoningReplay
  ): Either[String, Chunk[LlmChunk]] =
    for choices <- value.choices.toList match
        case Nil if value.usage.nonEmpty => Right(Chunk.empty)
        case choice :: Nil if choice.index.value == 0 =>
          decodeChoice(choice, toolSignatureFormat, reasoningReplay)
        case Nil => Left("An empty choices array requires a usage payload")
        case _   => Left("Exactly one completion choice with index zero is supported")
    yield choices ++ Chunk.fromIterable(value.usage.map(decodeUsage))

  private def decodeChoice(
      choice: Choice,
      toolSignatureFormat: ToolSignatureFormat,
      reasoningReplay: ReasoningReplay
  ): Either[String, Chunk[LlmChunk]] =
    val delta = choice.delta
    val calls = delta.toolCalls.getOrElse(Chunk.empty)
    for
      _ <- Either.cond(delta.role.forall(_ == "assistant"), (), "Unexpected completion delta role")
      _ <- Either.cond(delta.refusal.forall(_.isEmpty), (), "Refusal deltas are not supported")
      _ <- Either.cond(
        delta.legacyFunctionCall.isEmpty,
        (),
        "Legacy unindexed function_call deltas are not supported"
      )
      _ <- Either.cond(
        calls.map(_.index).distinct.size == calls.size,
        (),
        "Duplicate tool indexes in one delta"
      )
      tools <- calls.foldLeft[Either[String, Chunk[LlmChunk]]](Right(Chunk.empty))((acc, tool) =>
        for
          previous <- acc
          decoded  <- decodeTool(tool, toolSignatureFormat)
        yield previous :+ decoded
      )
      details <- delta.reasoningDetails match
        case None => Right(Chunk.empty)
        case Some(value) =>
          reasoningReplay match
            case ReasoningReplay.OpenRouterEncryptedDetails =>
              EncryptedReasoningDetail
                .decodeArray(value)
                .map(entries => Chunk(LlmChunk.ReasoningDetailsDelta(entries.map(_.toDetail))))
            case ReasoningReplay.OpenRouterDetails =>
              ReasoningDetail
                .decodeArray(value)
                .map(entries => Chunk(LlmChunk.ReasoningDetailsDelta(entries)))
            case ReasoningReplay.OpaqueSignatureDetails =>
              ReasoningDetail
                .decodeSignatureArray(value)
                .map(entries => Chunk(LlmChunk.ReasoningDetailsDelta(entries)))
            case _ => Left("Reasoning details require an explicit replay profile")
      // This selected profile uses detailed text exclusively, without cross-frame alias deduplication.
      _ <- Either.cond(
        reasoningReplay != ReasoningReplay.OpenRouterDetails || delta.reasoningDetails.nonEmpty ||
          !(delta.reasoningContent.exists(_.nonEmpty) || delta.thinking.exists(_.nonEmpty) ||
            delta.reasoning.exists(_.nonEmpty)),
        (),
        "OpenRouterDetails requires a reasoning_details array when scalar reasoning is supplied"
      )
      scalar <- reasoningReplay match
        case ReasoningReplay.OpenRouterDetails => Right(Chunk.empty)
        case _ =>
          val values = Chunk.fromIterable(delta.reasoningContent) ++
            Chunk.fromIterable(delta.reasoning) ++ Chunk.fromIterable(delta.thinking)
          Either.cond(
            values.distinct.size <= 1,
            Chunk.fromIterable(values.headOption.filter(_.nonEmpty).map(LlmChunk.ThinkingDelta(_))),
            "Conflicting scalar reasoning aliases"
          )
    yield details ++ scalar ++
      Chunk.fromIterable(delta.content.filter(_.nonEmpty).map(LlmChunk.TextDelta(_))) ++
      tools ++ Chunk.fromIterable(choice.finishReason.map(LlmChunk.FinishReason(_)))

  private def decodeTool(
      tool: ToolDelta,
      toolSignatureFormat: ToolSignatureFormat
  ): Either[String, LlmChunk] =
    for
      _ <- Either.cond(tool.kind.forall(_ == "function"), (), "Unsupported streamed tool type")
      signature <- decodeSignature(tool.extraContent, toolSignatureFormat)
    yield LlmChunk.ToolCallChunk(
      tool.index.value,
      tool.id,
      tool.function.flatMap(_.name),
      tool.function.flatMap(_.arguments).getOrElse(""),
      signature
    )

  private def decodeSignature(
      extraContent: Option[Json],
      format: ToolSignatureFormat
  ): Either[String, Option[String]] =
    // Absent/null extra_content is optional; present google and signature values are checked below.
    val invalid = "Invalid tool thought signature or container"
    for
      google <- extraContent match
        case None                  => Right(None)
        case Some(value: Json.Obj) => Right(value.get("google"))
        case _                     => Left(invalid)
      signature <- google match
        case None                  => Right(None)
        case Some(value: Json.Obj) => Right(value.get("thought_signature"))
        case _                     => Left(invalid)
      result <- signature match
        case None => Right(None)
        case Some(Json.Str(value)) if !value.isBlank =>
          format match
            case ToolSignatureFormat.Unsupported =>
              Left("Tool thought signatures require an explicit wire format")
            case ToolSignatureFormat.GoogleExtraContent => Right(Some(value))
        case _ => Left(invalid)
    yield result

  private def decodeUsage(value: Usage): LlmChunk =
    LlmChunk.UsageChunk(
      ChatUsage(
        inputTokens = value.promptTokens.map(_.value).getOrElse(0),
        outputTokens = value.completionTokens.map(_.value).getOrElse(0),
        cachedTokens = value.promptCacheHitTokens
          .orElse(value.promptDetails.flatMap(_.cachedTokens))
          .map(_.value)
          .getOrElse(0),
        cacheCreationTokens =
          value.promptDetails.flatMap(_.cacheWriteTokens).map(_.value).getOrElse(0),
        reasoningTokens =
          value.completionDetails.flatMap(_.reasoningTokens).map(_.value).getOrElse(0)
      )
    )

  /**
   * Context Hygiene: Prunes obsolete reasoning tokens from older dialogue rounds.
   * Unsigned plaintext is retained only on the most recent assistant message. Protected protocol
   * reasoning remains on its original message for exact replay.
   */
  def pruneObsoleteReasoning(history: Chunk[Msg]): Chunk[Msg] =
    if history.isEmpty then history
    else
      val lastAssistantIdx = history.lastIndexWhere(_.role == Role.Assistant)
      history.zipWithIndex.map { case (msg, idx) =>
        if msg.role == Role.Assistant && idx < lastAssistantIdx then
          msg.filterContent:
            case thought: ContentBlock.Thinking =>
              thought.metadata.contains("thoughtSignature") || thought.metadata.contains(
                "reasoningDetails"
              ) || thought.metadata.contains(Msg.MetadataResponsesReasoning)
            case _ => true
        else msg
      }
