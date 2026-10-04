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
import com.weisgnosis.agents.core.event.{AgentEvent, ModelStreamContext}
import com.weisgnosis.agents.core.model.{ContentBlock, Msg}
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

object ReasoningDetailSpec extends ZIOSpecDefault:
  private val text: Json.Obj = Json.Obj(
    "type"      -> Json.Str("reasoning.text"),
    "text"      -> Json.Str("first "),
    "signature" -> Json.Null,
    "index"     -> Json.Num(0)
  )
  private val summary: Json.Obj = Json.Obj(
    "type"    -> Json.Str("reasoning.summary"),
    "summary" -> Json.Str("summary "),
    "id"      -> Json.Null,
    "index"   -> Json.Num(0)
  )
  private val encrypted: Json.Obj = Json.Obj(
    "type"  -> Json.Str("reasoning.encrypted"),
    "data"  -> Json.Str(" opaque "),
    "index" -> Json.Num(0)
  )

  private def changed(value: Json.Obj, field: String, replacement: Json): Json.Obj =
    Json.Obj(value.fields.filterNot(_._1 == field) :+ (field -> replacement))

  private def frame(fields: (String, Json)*): String =
    Json
      .Obj(
        "choices" -> Json.Arr(
          Json.Obj(
            "index" -> Json.Num(0),
            "delta" -> Json.Obj(Chunk.fromIterable(fields))
          )
        )
      )
      .toJson

  private def parse(raw: String, profile: ReasoningReplay): Either[AgentError, Chunk[LlmChunk]] =
    ReasoningModelAdapter.parseChunk(raw, ToolSignatureFormat.Unsupported, profile)

  private def request(history: Chunk[Msg], profile: ReasoningReplay): Either[AgentError, Json.Obj] =
    ChatCompletionsRequest.encode(history, Chunk.empty, LlmConfig("model"), profile)

  private def replayed(value: Json.Obj): Option[Chunk[Json]] =
    value
      .get("messages")
      .flatMap(_.asArray)
      .map(_.collect { case obj: Json.Obj =>
        obj.get("reasoning_details").getOrElse(Json.Null)
      })

  def spec: Spec[TestEnvironment & Scope, Any] = suite("ReasoningDetailSpec")(
    test(
      "checked variants preserve literal fields and selected policies reject incompatible inputs"
    ) {
      val valid = Chunk(
        text,
        summary,
        encrypted,
        changed(text, "signature", Json.Str(" signature ")),
        Json.Obj(text.fields.filterNot(_._1 == "signature")),
        changed(text, "text", Json.Str("")),
        changed(summary, "summary", Json.Str(" "))
      )
      val invalid = Chunk(
        changed(text, "type", Json.Str("reasoning.other")),
        changed(text, "text", Json.Null),
        changed(text, "signature", Json.Str(" ")),
        changed(text, "signature", Json.Num(1)),
        changed(summary, "summary", Json.Bool(true)),
        Json.Obj(summary.fields.filterNot(_._1 == "summary") :+ ("text" -> Json.Str("legacy"))),
        changed(summary, "signature", Json.Str("misplaced")),
        changed(text, "data", Json.Str("misplaced")),
        Json.Obj(text.fields :+ ("text" -> Json.Str("duplicate"))),
        changed(text, "id", Json.Bool(true)),
        changed(text, "format", Json.Null),
        changed(text, "index", Json.Num(-1)),
        changed(text, "index", Json.Num(BigDecimal("0.5"))),
        changed(text, "index", Json.Num(BigDecimal("1e1000")))
      )
      val checked = valid.map(value =>
        val result = ReasoningDetail.make(value)
        assertTrue(
          result.exists(_.value == value),
          result.exists(detail => detail.toJson.fromJson[ReasoningDetail] == Right(detail)),
          parse(
            frame("reasoning_details" -> Json.Arr(value)),
            ReasoningReplay.OpenRouterDetails
          ).isRight
        )
      ) ++ invalid.map(value =>
        assertTrue(
          ReasoningDetail.make(value).isLeft,
          value.toJson.fromJson[ReasoningDetail].isLeft,
          parse(
            frame("reasoning_details" -> Json.Arr(value), "reasoning_content" -> Json.Str("alias")),
            ReasoningReplay.OpenRouterDetails
          ).isLeft,
          request(
            Chunk(
              Msg.assistant(
                Chunk(ContentBlock.Thinking("", Map("reasoningDetails" -> Json.Arr(value))))
              )
            ),
            ReasoningReplay.OpenRouterDetails
          ).isLeft
        )
      )
      val scalarCases = Chunk("reasoning_content", "thinking").flatMap(field =>
        Chunk("", " ", "scalar").map(value =>
          val alone = parse(frame(field -> Json.Str(value)), ReasoningReplay.OpenRouterDetails)
          val explicitEmpty = parse(
            frame(field -> Json.Str(value), "reasoning_details" -> Json.Arr()),
            ReasoningReplay.OpenRouterDetails
          )
          assertTrue(
            alone.isRight == value.isEmpty,
            explicitEmpty == Right(Chunk(LlmChunk.ReasoningDetailsDelta(Chunk.empty))),
            parse(frame(field -> Json.Str(value)), ReasoningReplay.LatestAssistant).isRight
          )
        )
      )
      val oldProfiles = Chunk(
        ReasoningReplay.Omit,
        ReasoningReplay.LatestAssistant,
        ReasoningReplay.OpenRouterEncryptedDetails
      ).map(profile =>
        assertTrue(
          parse(frame("reasoning_details" -> Json.Arr(text)), profile).isLeft,
          request(
            Chunk(
              Msg.assistant(
                Chunk(ContentBlock.Thinking("first ", Map("reasoningDetails" -> Json.Arr(text))))
              )
            ),
            profile
          ).isLeft
        )
      )
      val directMixing = ReasoningDetail
        .make(text)
        .map: detail =>
          val detailed = LlmChunk.ReasoningDetailsDelta(Chunk(detail))
          val scalar   = LlmChunk.ThinkingDelta("scalar")
          assertTrue(
            StreamAccumulator.empty.feed(scalar).flatMap(_.feed(detailed)).isLeft,
            StreamAccumulator.empty.feed(detailed).flatMap(_.feed(scalar)).isLeft
          )
      val opaqueNearMiss = ReasoningDetail
        .make(encrypted)
        .map: detail =>
          val detailed = LlmChunk.ReasoningDetailsDelta(Chunk(detail))
          val scalar   = LlmChunk.ThinkingDelta("scalar")
          assertTrue(
            StreamAccumulator.empty.feed(scalar).flatMap(_.feed(detailed)).isRight,
            StreamAccumulator.empty.feed(detailed).flatMap(_.feed(scalar)).isRight,
            StreamAccumulator.empty
              .feed(scalar)
              .flatMap(_.feed(LlmChunk.ReasoningDetailsDelta(Chunk.empty)))
              .isRight
          )
      (checked ++ scalarCases ++ oldProfiles).foldLeft(assertTrue(true))(_ && _) && assertTrue(
        EncryptedReasoningDetail
          .make(encrypted)
          .exists(value => value.toDetail.value == encrypted && value.toDetail.plaintext.isEmpty),
        EncryptedReasoningDetail.make(text).isLeft,
        parse(frame(), ReasoningReplay.OpenRouterDetails) == Right(Chunk.empty),
        parse(frame("reasoning_details" -> Json.Null), ReasoningReplay.OpenRouterDetails) == Right(
          Chunk.empty
        )
      ) && directMixing.getOrElse(assertTrue(false)) && opaqueNearMiss.getOrElse(assertTrue(false))
    },
    test(
      "ordered details yield identical canonical and streamed plaintext and replay every original object"
    ) {
      val frames = Chunk(
        frame(
          "reasoning_details" -> Json.Arr(encrypted, text),
          "reasoning_content" -> Json.Str("first ")
        ),
        frame(
          "reasoning_details" -> Json.Arr(summary, encrypted, text),
          "thinking"          -> Json.Str("summary first ")
        )
      )
      val all = Json.Arr(encrypted, text, summary, encrypted, text)
      for
        chunks <- ZIO.fromEither(
          frames.foldLeft[Either[AgentError, Chunk[Chunk[LlmChunk]]]](Right(Chunk.empty))(
            (previous, raw) =>
              for
                accumulated <- previous
                decoded     <- parse(raw, ReasoningReplay.OpenRouterDetails)
              yield accumulated :+ decoded
          )
        )
        state <- ZIO.fromEither(
          chunks.flatten.foldLeft[Either[AgentError, StreamAccumulator]](
            Right(StreamAccumulator.empty)
          )((previous, chunk) => previous.flatMap(_.feed(chunk)))
        )
        output <- ZIO.fromEither(state.feed(LlmChunk.FinishReason("stop")).flatMap(_.toTurnOutput))
        expected = ContentBlock.Thinking("first summary first ", Map("reasoningDetails" -> all))
        empty <- ZIO.fromEither(
          StreamAccumulator.empty
            .feed(LlmChunk.ReasoningDetailsDelta(Chunk.empty))
            .flatMap(_.feed(LlmChunk.FinishReason("stop")))
            .flatMap(_.toTurnOutput)
        )
        body <- ZIO.fromEither(
          request(
            Chunk(output.message, Msg.user("next"), empty.message),
            ReasoningReplay.OpenRouterDetails
          )
        )
        hub       <- ZIO.acquireRelease(Hub.unbounded[AgentEvent])(_.shutdown)
        queue     <- hub.subscribe
        publisher <- ModelStreamPublisher.make(hub, ModelStreamContext("reply", "request"))
        _ <- ZIO.foreachDiscard(frames.zip(chunks))((raw, values) => publisher.frame(raw, values))
        _ <- publisher.finish(output)
        events <- queue.takeAll
      yield assertTrue(
        output == TurnOutput.ThinkingOnly(Msg.assistant(Chunk(expected), name = Some("assistant"))),
        state.toInterrupted.message.content == Chunk(expected),
        !empty.message.hasReasoning,
        replayed(body).contains(Chunk(all, Json.Null, Json.Arr())),
        body
          .get("messages")
          .flatMap(_.asArray)
          .exists(_.forall {
            case obj: Json.Obj => obj.get("reasoning_content").isEmpty
            case _             => false
          }),
        events.collect { case event: AgentEvent.ThinkingBlockDeltaEvent =>
          event.delta
        }.mkString == output.message.thinking,
        events.count(_.isInstanceOf[AgentEvent.ThinkingBlockStartEvent]) == 1,
        events.collect { case event: AgentEvent.ThinkingBlockEndEvent =>
          event.fullThought
        } == Chunk(output.message.thinking)
      )
    }
  )
