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
import com.weisgnosis.agents.core.model.{ContentBlock, Msg}
import zio.Chunk
import zio.json.*
import zio.json.ast.Json
import zio.test.*

object EncryptedReasoningDetailSpec extends ZIOSpecDefault:
  private val first: Json.Obj = Json.Obj(
    "type"   -> Json.Str("reasoning.encrypted"),
    "data"   -> Json.Str(" opaque-first "),
    "id"     -> Json.Null,
    "format" -> Json.Str("future-format"),
    "index"  -> Json.Num(0)
  )
  private val second: Json.Obj = Json.Obj(
    "type"  -> Json.Str("reasoning.encrypted"),
    "data"  -> Json.Str("[REDACTED]"),
    "id"    -> Json.Str("second"),
    "index" -> Json.Num(0)
  )

  private def frame(details: Option[Json]): String =
    Json
      .Obj(
        "choices" -> Json.Arr(
          Json.Obj(
            "index" -> Json.Num(0),
            "delta" -> Json.Obj(Chunk.fromIterable(details.map("reasoning_details" -> _)))
          )
        )
      )
      .toJson

  private def parse(value: String, policy: ReasoningReplay): Either[AgentError, Chunk[LlmChunk]] =
    ReasoningModelAdapter.parseChunk(value, ToolSignatureFormat.Unsupported, policy)

  private def accumulate(frames: Chunk[String]): Either[AgentError, StreamAccumulator] =
    frames.foldLeft[Either[AgentError, StreamAccumulator]](Right(StreamAccumulator.empty)):
      (previous, input) =>
        for
          state  <- previous
          chunks <- parse(input, ReasoningReplay.OpenRouterEncryptedDetails)
          updated <- chunks.foldLeft[Either[AgentError, StreamAccumulator]](Right(state))(
            (acc, chunk) => acc.flatMap(_.feed(chunk))
          )
        yield updated

  private def messages(body: Json.Obj): Option[Chunk[Json]] =
    body.get("messages").flatMap(_.asArray)

  private def details(message: Json): Option[Json] = message match
    case obj: Json.Obj => obj.get("reasoning_details")
    case _             => None

  def spec: Spec[TestEnvironment, Any] = suite("EncryptedReasoningDetailSpec")(
    test("checked values preserve exact JSON and reject malformed fields through every boundary") {
      val minimal =
        Json.Obj("type" -> Json.Str("reasoning.encrypted"), "data" -> Json.Str("opaque"))
      val valid = Chunk(
        first,
        second,
        minimal,
        Json.Obj(minimal.fields :+ ("index" -> Json.Num(Int.MaxValue)))
      )
      val invalid = Chunk(
        Json.Obj(),
        Json.Obj("type"      -> Json.Str("reasoning.text"), "text"       -> Json.Str("text")),
        Json.Obj("type"      -> Json.Str("reasoning.summary"), "summary" -> Json.Str("summary")),
        Json.Obj(minimal.fields :+ ("data"      -> Json.Str("duplicate"))),
        Json.Obj(minimal.fields :+ ("extension" -> Json.Bool(true)))
      ) ++ Chunk("", " ").map(value =>
        Json.Obj("type" -> Json.Str("reasoning.encrypted"), "data" -> Json.Str(value))
      ) ++ Chunk(
        "data"   -> Json.Null,
        "data"   -> Json.Num(1),
        "id"     -> Json.Bool(true),
        "id"     -> Json.Str(" "),
        "format" -> Json.Null,
        "format" -> Json.Num(1),
        "format" -> Json.Str(""),
        "index"  -> Json.Null,
        "index"  -> Json.Str("0"),
        "index"  -> Json.Num(-1),
        "index"  -> Json.Num(BigDecimal("0.5")),
        "index"  -> Json.Num(BigDecimal("2147483648")),
        "index"  -> Json.Num(BigDecimal("1e1000"))
      ).map((key, value) => Json.Obj(minimal.fields.filterNot(_._1 == key) :+ (key -> value)))
      val validResults = valid.map(value =>
        val checked = EncryptedReasoningDetail.make(value)
        assertTrue(
          checked.exists(_.value == value),
          checked.exists(detail =>
            detail.toJson.fromJson[EncryptedReasoningDetail].contains(detail)
          ),
          checked.flatMap(_.toJsonAST.left.map(AgentError.InvalidMessage(_))) == Right(value)
        )
      )
      val invalidResults = invalid.map(value =>
        assertTrue(
          EncryptedReasoningDetail.make(value).isLeft,
          value.toJson.fromJson[EncryptedReasoningDetail].isLeft,
          parse(frame(Some(Json.Arr(value))), ReasoningReplay.OpenRouterEncryptedDetails).isLeft
        )
      )
      val profiles = Chunk(ReasoningReplay.Omit, ReasoningReplay.LatestAssistant)
      val policyResults = profiles.map(policy =>
        assertTrue(
          parse(frame(None), policy) == Right(Chunk.empty),
          parse(frame(Some(Json.Null)), policy) == Right(Chunk.empty),
          parse(frame(Some(Json.Arr())), policy).isLeft,
          parse(frame(Some(Json.Arr(first))), policy).isLeft
        )
      )
      (validResults ++ invalidResults ++ policyResults).foldLeft(assertTrue(true))(_ && _)
    },
    test(
      "ordered opaque deltas survive interruption and all-history replay without invented thinking"
    ) {
      val ordered = Json.Arr(first, second, first)
      val state =
        accumulate(Chunk(frame(Some(Json.Arr(first))), frame(Some(Json.Arr(second, first)))))
      val output   = state.flatMap(_.feed(LlmChunk.FinishReason("stop"))).flatMap(_.toTurnOutput)
      val expected = ContentBlock.Thinking("", Map("reasoningDetails" -> ordered))
      val empty = accumulate(Chunk(frame(Some(Json.Arr()))))
        .flatMap(_.feed(LlmChunk.FinishReason("stop")))
        .flatMap(_.toTurnOutput)
      val history = Chunk(
        Msg.assistant(Chunk(expected)),
        Msg.user("next"),
        Msg.assistant(Chunk(ContentBlock.Thinking("", Map("reasoningDetails" -> Json.Arr()))))
      )
      val request = ChatCompletionsRequest.encode(
        history,
        Chunk.empty,
        LlmConfig("model"),
        ReasoningReplay.OpenRouterEncryptedDetails
      )
      val invalidHistory = Chunk[Json](
        Json.Null,
        Json.Str("opaque"),
        Json.Arr(Json.Str("opaque")),
        Json.Arr(Json.Obj("type" -> Json.Str("reasoning.text"), "text" -> Json.Str("text")))
      )
        .map(value =>
          ChatCompletionsRequest.encode(
            Chunk(
              Msg.assistant(Chunk(ContentBlock.Thinking("", Map("reasoningDetails" -> value))))
            ),
            Chunk.empty,
            LlmConfig("model"),
            ReasoningReplay.OpenRouterEncryptedDetails
          )
        )
      assertTrue(
        output.exists:
          case TurnOutput.ThinkingOnly(message) =>
            message.content == Chunk(expected) && message.thinking.isEmpty
          case _ => false,
        state.exists(_.toInterrupted.message.content == Chunk(expected)),
        empty.exists:
          case TurnOutput.FinalText(message) =>
            !message.hasReasoning && message.preservesToolArguments &&
            message.content == Chunk(
              ContentBlock.Thinking("", Map("reasoningDetails" -> Json.Arr()))
            )
          case _ => false,
        request.toOption
          .flatMap(messages)
          .exists(values =>
            values.lift(0).flatMap(details).contains(ordered) &&
              values.lift(2).flatMap(details).contains(Json.Arr()) &&
              values.forall:
                case obj: Json.Obj => obj.get("reasoning_content").isEmpty
                case _             => false
          ),
        Chunk(ReasoningReplay.Omit, ReasoningReplay.LatestAssistant).forall(policy =>
          ChatCompletionsRequest.encode(history, Chunk.empty, LlmConfig("model"), policy).isLeft
        ),
        invalidHistory.forall(_.isLeft),
        ReasoningReplay.OpenRouterEncryptedDetails.toJson
          .fromJson[ReasoningReplay]
          .contains(ReasoningReplay.OpenRouterEncryptedDetails)
      )
    }
  )
