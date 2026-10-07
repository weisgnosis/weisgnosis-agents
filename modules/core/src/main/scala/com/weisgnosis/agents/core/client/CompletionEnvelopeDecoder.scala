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

import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.json.JsonBoundary
import zio.Chunk
import zio.json.*
import zio.json.ast.Json

/** Explicit host selection of supported SSE completion envelopes, independent of model identity. */
enum CompletionEnvelopeProfile derives JsonCodec:
  case IndexedDeltas, DeltasWithFullMessageSummary

/** Pure request-local envelope progression; the caller retains completion authority. */
final private[client] class CompletionEnvelopeDecoder private (
    profile: CompletionEnvelopeProfile,
    toolSignatureFormat: ToolSignatureFormat,
    reasoningReplay: ReasoningReplay,
    phase: CompletionEnvelopeDecoder.Phase
):
  import CompletionEnvelopeDecoder.*

  def decode(
      data: String,
      finishReason: Option[String]
  ): Either[AgentError, (CompletionEnvelopeDecoder, Chunk[LlmChunk])] =
    profile match
      case CompletionEnvelopeProfile.IndexedDeltas => parse(data).map(this -> _)
      case CompletionEnvelopeProfile.DeltasWithFullMessageSummary =>
        for
          json <- JsonBoundary.decode(data).left.map(AgentError.DeserializationError(_))
          result <- json match
            case root: Json.Obj if root.get("error").exists(_ != Json.Null) =>
              parse(data).map(this -> _)
            case root: Json.Obj => decodeEnvelope(root, data, finishReason)
            case _              => invalid("Completion envelope must be an object")
        yield result

  private def parse(data: String): Either[AgentError, Chunk[LlmChunk]] =
    ReasoningModelAdapter.parseChunk(data, toolSignatureFormat, reasoningReplay)

  private def advance(next: Phase): CompletionEnvelopeDecoder =
    new CompletionEnvelopeDecoder(profile, toolSignatureFormat, reasoningReplay, next)

  private def decodeEnvelope(
      root: Json.Obj,
      data: String,
      finishReason: Option[String]
  ): Either[AgentError, (CompletionEnvelopeDecoder, Chunk[LlmChunk])] =
    root.get("choices") match
      case Some(Json.Arr(choices)) if choices.isEmpty => parse(data).map(this -> _)
      case Some(Json.Arr(choices)) =>
        choices.toList match
          case (choice: Json.Obj) :: Nil =>
            phase match
              case Phase.Summarized => invalid("A completion choice arrived after the full summary")
              case _ if root.get("object").contains(Json.Str("chat.completion")) =>
                decodeFull(root, choice, finishReason)
              case _ if choice.get("message").nonEmpty =>
                invalid("Full messages require an explicit chat.completion envelope")
              case _ => parse(data).map(advance(Phase.Streaming) -> _)
          case _ => invalid("Exactly one completion choice with index zero is supported")
      case _ => invalid("Completion choices must be an explicit array")

  private def decodeFull(
      root: Json.Obj,
      choice: Json.Obj,
      finishReason: Option[String]
  ): Either[AgentError, (CompletionEnvelopeDecoder, Chunk[LlmChunk])] =
    for
      _ <- require(choice.get("delta").isEmpty, "A full message cannot also contain delta")
      message <- choice.get("message") match
        case Some(value: Json.Obj) => Right(value)
        case _                     => invalid("A full completion requires a message object")
      _ <- require(
        message.get("role").contains(Json.Str("assistant")),
        "Full message role must be assistant"
      )
      terminal <- nonblank(choice.get("finish_reason"), "Full completion requires a finish reason")
      indexed  <- indexTools(message)
      normalized = Json.Obj(
        root.fields.filterNot(field => field._1 == "object" || field._1 == "choices") ++ Chunk(
          "object" -> Json.Str("chat.completion.chunk"),
          "choices" -> Json.Arr(
            Json.Obj(
              choice.fields.filterNot(field => field._1 == "message" || field._1 == "delta") :+
                ("delta" -> indexed)
            )
          )
        )
      )
      chunks <- parse(JsonBoundary.render(normalized))
      assembled <- chunks.foldLeft[Either[AgentError, StreamAccumulator]](
        Right(StreamAccumulator.empty)
      ): (acc, chunk) =>
        acc.flatMap(_.feed(chunk))
      _ <- assembled.toTurnOutput
      accepted <- phase match
        case Phase.AwaitingDelta => Right(chunks)
        case Phase.Streaming =>
          require(
            finishReason.forall(_ == terminal),
            "Summary finish reason conflicts with the stream"
          )
            .map(_ =>
              chunks.collect:
                case usage: LlmChunk.UsageChunk                            => usage
                case finish: LlmChunk.FinishReason if finishReason.isEmpty => finish
            )
        case Phase.Summarized => invalid("A completion choice arrived after the full summary")
    yield advance(Phase.Summarized) -> accepted

  private def indexTools(message: Json.Obj): Either[AgentError, Json.Obj] =
    message.get("tool_calls") match
      case None | Some(Json.Null) => Right(message)
      case Some(Json.Arr(tools)) =>
        tools.zipWithIndex
          .foldLeft[Either[AgentError, Chunk[Json]]](Right(Chunk.empty)):
            case (acc, (tool: Json.Obj, index)) =>
              for
                previous <- acc
                _ <- require(
                  tool.get("type").contains(Json.Str("function")),
                  "Full tool type must be function"
                )
                _ <- nonblank(tool.get("id"), "Full tool call requires a nonblank ID")
                function <- tool.get("function") match
                  case Some(value: Json.Obj) => Right(value)
                  case _                     => invalid("Full tool call requires a function object")
                _ <- nonblank(function.get("name"), "Full tool call requires a nonblank name")
                _ <- function.get("arguments") match
                  case Some(Json.Str(_)) => Right(())
                  case _                 => invalid("Full tool call requires string arguments")
                _ <- require(
                  tool.get("index").forall {
                    case number: Json.Num => number.as[Int] == Right(index)
                    case _                => false
                  },
                  "Full tool index must match its array position"
                )
              yield previous :+ Json.Obj(
                tool.fields.filterNot(_._1 == "index") :+ ("index" -> Json.Num(index))
              )
            case (_, _) => invalid("Full tool calls must be objects")
          .map(indexed =>
            Json.Obj(
              message.fields.filterNot(_._1 == "tool_calls") :+ ("tool_calls" -> Json.Arr(indexed))
            )
          )
      case _ => invalid("Full tool calls must be an array")

private[client] object CompletionEnvelopeDecoder:
  private enum Phase:
    case AwaitingDelta, Streaming, Summarized

  def initial(
      profile: CompletionEnvelopeProfile,
      toolSignatureFormat: ToolSignatureFormat,
      reasoningReplay: ReasoningReplay
  ): CompletionEnvelopeDecoder =
    new CompletionEnvelopeDecoder(
      profile,
      toolSignatureFormat,
      reasoningReplay,
      Phase.AwaitingDelta
    )

  def fullMessage(
      data: String,
      toolSignatureFormat: ToolSignatureFormat,
      reasoningReplay: ReasoningReplay
  ): Either[AgentError, Chunk[LlmChunk]] =
    JsonBoundary
      .decode(data)
      .left
      .map(AgentError.DeserializationError(_))
      .flatMap:
        case root: Json.Obj if root.get("error").exists(_ != Json.Null) =>
          ReasoningModelAdapter.parseChunk(data, toolSignatureFormat, reasoningReplay)
        case root: Json.Obj if root.get("object").contains(Json.Str("chat.completion")) =>
          for
            _ <- require(
              root.get("choices").exists {
                case Json.Arr(choices) => choices.size == 1
                case _                 => false
              },
              "A JSON response requires exactly one completion choice"
            )
            decoded <- initial(
              CompletionEnvelopeProfile.DeltasWithFullMessageSummary,
              toolSignatureFormat,
              reasoningReplay
            )
              .decode(data, None)
          yield decoded._2
        case _ => invalid("A JSON response requires an explicit full completion envelope")

  private def require(valid: Boolean, message: String): Either[AgentError, Unit] =
    Either.cond(valid, (), AgentError.DeserializationError(message))

  private def invalid[A](message: String): Either[AgentError, A] =
    Left(AgentError.DeserializationError(message))

  private def nonblank(value: Option[Json], message: String): Either[AgentError, String] =
    value match
      case Some(Json.Str(text)) if !text.isBlank => Right(text)
      case _                                     => invalid(message)
