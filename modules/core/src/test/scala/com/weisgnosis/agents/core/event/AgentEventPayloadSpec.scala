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
package com.weisgnosis.agents.core.event

import com.weisgnosis.agents.core.model.*
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

/** Payload contracts do not imply production event emission or control-plane authority. */
object AgentEventPayloadSpec extends ZIOSpecDefault:
  def spec: Spec[TestEnvironment, Any] = suite("AgentEventPayloadSpec")(
    test("agent result preserves the complete validated message") {
      val result = Msg
        .assistant(Chunk(ContentBlock.Thinking("reason"), ContentBlock.Text("final")))
        .withId(Some("message"))
        .withMetadata("trace", Json.Str("kept"))
        .withUsage(ChatUsage(inputTokens = 3))
      val event = AgentEvent.AgentResultEvent(result)
      assertTrue(
        event.toJson.fromJson[AgentEvent.AgentResultEvent].contains(event),
        event.toJson.fromJson[AgentEvent].contains(event),
        """{"result":{"role":"system","content":[{"type":"thinking","thinking":"invalid"}]}}"""
          .fromJson[AgentEvent.AgentResultEvent]
          .isLeft
      )
    },
    test("data block payloads preserve correlation and carry string deltas") {
      val start = AgentEvent.DataBlockStartEvent("reply", "block")
      val delta = AgentEvent.DataBlockDeltaEvent("reply", "block", "encoded fragment")
      val end   = AgentEvent.DataBlockEndEvent("reply", "block")
      assertTrue(
        start.toJson.fromJson[AgentEvent.DataBlockStartEvent].contains(start),
        delta.toJson.fromJson[AgentEvent.DataBlockDeltaEvent].contains(delta),
        end.toJson.fromJson[AgentEvent.DataBlockEndEvent].contains(end),
        """{"replyId":"reply","blockId":"block","delta":{"type":"text","text":"wrong shape"}}"""
          .fromJson[AgentEvent.DataBlockDeltaEvent]
          .isLeft
      )
    },
    test("toolResultDataDeltaEventMetadataRoundTrip") {
      val data = ContentBlock.Data(
        MediaSource.Base64("image/png", "encoded"),
        Some("data-id"),
        Some("image.png")
      )
      val metadata = Map("trace" -> Json.Str("trace-1"), "attempt" -> Json.Num(2))
      val event    = AgentEvent.ToolResultDataDeltaEvent("reply", "call", "tool", data, metadata)
      val tagged   = event.withSource("first").withSource("second")
      assertTrue(
        event.toJson.fromJson[AgentEvent.ToolResultDataDeltaEvent].contains(event),
        tagged.toJson.fromJson[AgentEvent].contains(event.copy(source = Some("second"))),
        event.metadata == metadata,
        event.source.isEmpty,
        event.toJson
          .fromJson[AgentEvent.ToolResultDataDeltaEvent]
          .exists(value =>
            value.replyId == "reply" && value.toolCallId == "call" && value.toolCallName == "tool" && value.data == data && value.metadata == metadata
          )
      )
    },
    test("tool data delta accepts arbitrary content and rejects malformed nested data") {
      val blocks: Chunk[ContentBlock] = Chunk(
        ContentBlock.Text("text"),
        ContentBlock.Thinking("thought"),
        ContentBlock.Custom("extension", Json.Num(2))
      )
      val invalid =
        """{"replyId":"reply","toolCallId":"call","toolCallName":"tool","data":{"type":"text","type":"text","text":"duplicate"}}"""
      assertTrue(
        blocks.forall(block =>
          val event = AgentEvent.ToolResultDataDeltaEvent("reply", "call", "tool", block)
          event.toJson.fromJson[AgentEvent.ToolResultDataDeltaEvent].contains(event)
        ),
        invalid.fromJson[AgentEvent.ToolResultDataDeltaEvent].isLeft
      )
    },
    test("iteration limit payload retains reply identity and both counters") {
      val event = AgentEvent.ExceedMaxItersEvent("reply", 20, 20)
      assertTrue(
        event.toJson.fromJson[AgentEvent.ExceedMaxItersEvent].contains(event),
        event.withSource("runtime") == event.copy(source = Some("runtime"))
      )
    },
    test(
      "stop requests default missing or JSON-null generation reason without hiding malformed values"
    ) {
      val expected = AgentEvent.RequestStopEvent("checkpoint")
      val absent   = """{"reason":"checkpoint"}""".fromJson[AgentEvent.RequestStopEvent]
      val empty =
        """{"reason":"checkpoint","generateReason":null}""".fromJson[AgentEvent.RequestStopEvent]
      val explicit = AgentEvent.RequestStopEvent("approval", GenerateReason.PermissionAsking)
      assertTrue(
        absent.contains(expected),
        empty.contains(expected),
        expected.generateReason == GenerateReason.MiddlewareStopRequested,
        explicit.toJson.fromJson[AgentEvent.RequestStopEvent].contains(explicit),
        """{"reason":"checkpoint","generateReason":"unknown"}"""
          .fromJson[AgentEvent.RequestStopEvent]
          .isLeft,
        """{"reason":"checkpoint","generateReason":1}"""
          .fromJson[AgentEvent.RequestStopEvent]
          .isLeft
      )
    },
    test("denied calls default to empty and preserve immutable protocol states") {
      val call    = ContentBlock.ToolUse("call", "tool", Json.Obj(), state = ToolCallState.Asking)
      val event   = AgentEvent.AllToolsDeniedEvent(Chunk(call))
      val updated = event.copy(deniedToolCalls = event.deniedToolCalls :+ call.copy(id = "second"))
      assertTrue(
        "{}".fromJson[AgentEvent.AllToolsDeniedEvent].contains(AgentEvent.AllToolsDeniedEvent()),
        """{"deniedToolCalls":null}"""
          .fromJson[AgentEvent.AllToolsDeniedEvent]
          .contains(AgentEvent.AllToolsDeniedEvent()),
        event.toJson.fromJson[AgentEvent.AllToolsDeniedEvent].contains(event),
        event.deniedToolCalls == Chunk(call),
        updated.deniedToolCalls.size == 2,
        """{"deniedToolCalls":"wrong"}""".fromJson[AgentEvent.AllToolsDeniedEvent].isLeft
      )
    }
  )
