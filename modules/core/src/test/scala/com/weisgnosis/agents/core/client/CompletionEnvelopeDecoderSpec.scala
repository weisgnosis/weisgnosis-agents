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
import com.weisgnosis.agents.core.model.ChatUsage
import zio.Chunk
import zio.json.ast.Json
import zio.test.*

object CompletionEnvelopeDecoderSpec extends ZIOSpecDefault:
  private def decoder(
      reasoning: ReasoningReplay = ReasoningReplay.Omit,
      profile: CompletionEnvelopeProfile = CompletionEnvelopeProfile.DeltasWithFullMessageSummary
  ): CompletionEnvelopeDecoder =
    CompletionEnvelopeDecoder.initial(profile, ToolSignatureFormat.Unsupported, reasoning)

  private def changed(value: Json.Obj, key: String, replacement: Json): Json.Obj =
    Json.Obj(value.fields.filterNot(_._1 == key) :+ (key -> replacement))

  private def message(fields: (String, Json)*): Json.Obj =
    Json.Obj(Chunk("role" -> Json.Str("assistant")) ++ Chunk.fromIterable(fields))

  private def call(id: String, arguments: String): Json.Obj = Json.Obj(
    "id"       -> Json.Str(id),
    "type"     -> Json.Str("function"),
    "function" -> Json.Obj("name" -> Json.Str("lookup"), "arguments" -> Json.Str(arguments))
  )

  private def full(body: Json, finish: Json = Json.Str("stop")): Json.Obj = Json.Obj(
    "object" -> Json.Str("chat.completion"),
    "choices" -> Json.Arr(
      Json.Obj("index" -> Json.Num(0), "message" -> body, "finish_reason" -> finish)
    ),
    "usage" -> Json.Obj("prompt_tokens" -> Json.Num(2), "completion_tokens" -> Json.Num(3))
  )

  private def delta(body: Json.Obj): String = JsonBoundary.render(
    Json.Obj(
      "object"  -> Json.Str("chat.completion.chunk"),
      "choices" -> Json.Arr(Json.Obj("index" -> Json.Num(0), "delta" -> body))
    )
  )

  private val usageOnly: String = JsonBoundary.render(
    Json.Obj(
      "choices" -> Json.Arr(),
      "usage"   -> Json.Obj("prompt_tokens" -> Json.Num(2), "completion_tokens" -> Json.Num(3))
    )
  )
  private val usageChunk: LlmChunk =
    LlmChunk.UsageChunk(ChatUsage(inputTokens = 2, outputTokens = 3))
  private val details: Json.Arr = Json.Arr(
    Json.Obj(
      "type"  -> Json.Str("reasoning.text"),
      "text"  -> Json.Str("thinking"),
      "index" -> Json.Num(0)
    )
  )

  private def materialize(chunks: Chunk[LlmChunk]): Either[AgentError, TurnOutput] =
    chunks
      .foldLeft[Either[AgentError, StreamAccumulator]](Right(StreamAccumulator.empty)):
        (acc, chunk) => acc.flatMap(_.feed(chunk))
      .flatMap(_.toTurnOutput)

  def spec: Spec[TestEnvironment, Any] = suite("CompletionEnvelopeDecoderSpec")(
    test(
      "full-only envelopes materialize exact text, reasoning and complete indexed tools only when selected"
    ):
      val cases = Chunk(
        (
          message("content" -> Json.Str("answer")),
          "stop",
          ReasoningReplay.Omit,
          "answer",
          "",
          Chunk.empty[String]
        ),
        (
          message(
            "content"           -> Json.Null,
            "reasoning_content" -> Json.Str("thinking"),
            "tool_calls"        -> Json.Arr(call("a", "{}"))
          ),
          "tool_calls",
          ReasoningReplay.Omit,
          "",
          "thinking",
          Chunk("a")
        ),
        (
          message(
            "content"           -> Json.Str("answer"),
            "reasoning_content" -> Json.Str("thinking"),
            "reasoning_details" -> details,
            "tool_calls" -> Json
              .Arr(call("a", "{}"), changed(call("b", "{\"value\":2}"), "index", Json.Num(1)))
          ),
          "tool_calls",
          ReasoningReplay.OpenRouterDetails,
          "answer",
          "thinking",
          Chunk("a", "b")
        )
      )
      assertTrue(cases.forall { case (body, finish, reasoning, text, thinking, ids) =>
        val raw = JsonBoundary.render(full(body, Json.Str(finish)))
        decoder(reasoning).decode(raw, None).exists { case (next, chunks) =>
          materialize(chunks).exists(output =>
            output.message.text == text && output.message.thinking == thinking &&
              output.message.toolUses.map(_.id) == ids &&
              output.message.toolUses.map(_.name) == ids.map(_ => "lookup") &&
              output.message.usage.contains(ChatUsage(inputTokens = 2, outputTokens = 3))
          ) && next.decode(usageOnly, Some(finish)).exists(_._2 == Chunk(usageChunk)) &&
          next.decode(raw, Some(finish)).isLeft &&
          next.decode(delta(message()), Some(finish)).isLeft
        } && decoder(reasoning, CompletionEnvelopeProfile.IndexedDeltas).decode(raw, None).isLeft
      })
    ,
    test(
      "delta summaries suppress semantic replay while actual terminal state and usage remain authoritative"
    ):
      val summary = full(
        message(
          "content"           -> Json.Str("different summary"),
          "reasoning_content" -> Json.Str("thinking"),
          "reasoning_details" -> details,
          "tool_calls"        -> Json.Arr(call("a", "{\"different\":true}"))
        ),
        Json.Str("tool_calls")
      )
      val roleOnly = delta(message())
      val payload = delta(
        message(
          "content"           -> Json.Str("original"),
          "reasoning_content" -> Json.Str("thinking"),
          "reasoning_details" -> details,
          "tool_calls"        -> Json.Arr(changed(call("a", "{}"), "index", Json.Num(0)))
        )
      )
      assertTrue(
        Chunk(roleOnly, payload).forall(first =>
          decoder(ReasoningReplay.OpenRouterDetails).decode(first, None).exists {
            case (streaming, firstChunks) =>
              Chunk(None, Some("tool_calls")).forall(terminal =>
                streaming.decode(JsonBoundary.render(summary), terminal).exists {
                  case (_, accepted) =>
                    accepted == (if terminal.isEmpty then
                                   Chunk(LlmChunk.FinishReason("tool_calls"), usageChunk)
                                 else Chunk(usageChunk)) &&
                    (first == roleOnly || materialize(
                      firstChunks ++ Chunk
                        .fromIterable(terminal.map(LlmChunk.FinishReason(_))) ++ accepted
                    ).exists(output =>
                      output.message.text == "original" && output.message.thinking == "thinking" &&
                        output.message.toolUses.map(_.input) == Chunk(Json.Obj())
                    ))
                }
              ) && streaming.decode(JsonBoundary.render(summary), Some("stop")).isLeft
          }
        ),
        decoder().decode(usageOnly, None).exists { case (awaiting, _) =>
          awaiting
            .decode(JsonBoundary.render(full(message("content" -> Json.Str("answer")))), None)
            .exists { case (_, chunks) => materialize(chunks).exists(_.message.text == "answer") }
        },
        decoder(ReasoningReplay.OpenRouterDetails).decode(roleOnly, None).exists {
          case (streaming, _) =>
            streaming.decode("""{"error":{"message":"overloaded"}}""", None).left.exists {
              case AgentError.ModelError(200, "overloaded", _) => true
              case _                                           => false
            }
        }
      )
    ,
    test(
      "full envelopes reject malformed known fields even when their semantic content would be suppressed"
    ):
      val valid = full(message("tool_calls" -> Json.Arr(call("a", "{}"))), Json.Str("tool_calls"))
      val invalidBodies = Chunk(
        Json.Null,
        changed(message(), "role", Json.Str("user")),
        message("content"    -> Json.Arr()),
        message("refusal"    -> Json.Str("refused")),
        message("tool_calls" -> Json.Arr(Json.Null)),
        message("tool_calls" -> Json.Arr(changed(call("a", "{}"), "id", Json.Str(" ")))),
        message("tool_calls" -> Json.Arr(changed(call("a", "{}"), "index", Json.Num(1)))),
        message("tool_calls" -> Json.Arr(call("a", "{"))),
        message("tool_calls" -> Json.Arr(call("a", "[]"))),
        message("tool_calls" -> Json.Arr(call("a", "{}"), call("a", "{}"))),
        message(
          "tool_calls" -> Json.Arr(
            Json.Obj(
              "id"       -> Json.Str("a"),
              "type"     -> Json.Str("function"),
              "function" -> Json.Obj("name" -> Json.Str("lookup"), "arguments" -> Json.Null)
            )
          )
        )
      )
      val invalidEnvelopes = invalidBodies.map(body => full(body, Json.Str("tool_calls"))) ++ Chunk(
        full(message(), Json.Null),
        full(message(), Json.Str("content_filter")),
        changed(
          valid,
          "choices",
          Json.Arr(
            Json.Obj(
              "index"         -> Json.Num(0),
              "message"       -> message(),
              "delta"         -> Json.Null,
              "finish_reason" -> Json.Str("stop")
            )
          )
        ),
        changed(
          valid,
          "choices",
          Json.Arr(
            Json.Obj(
              "index"         -> Json.Num(1),
              "message"       -> message(),
              "finish_reason" -> Json.Str("stop")
            )
          )
        ),
        changed(valid, "usage", Json.Obj("prompt_tokens" -> Json.Num(-1))),
        changed(valid, "object", Json.Str("other"))
      )
      assertTrue(
        decoder().decode(delta(message()), None).exists { case (streaming, _) =>
          invalidEnvelopes.forall(envelope =>
            val raw = JsonBoundary.render(envelope)
            decoder().decode(raw, None).isLeft && streaming.decode(raw, None).isLeft
          )
        },
        decoder().decode("""{"choices":[{"index":0,"delta":null}]}""", None).isLeft,
        decoder().decode("""{"choices":[],"usage":null}""", None).isLeft
      )
  )
