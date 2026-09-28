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

import com.weisgnosis.agents.core.model.ToolResultState
import zio.Chunk
import zio.json.*
import zio.json.ast.Json
import zio.test.*

/**
 * Complete round-trip serialization and discriminator verification for all 31 AgentEvent types.
 */
object AgentEventSerdeSpec extends ZIOSpecDefault:

  def spec: Spec[TestEnvironment, Any] = suite("AgentEventSerdeSpec")(
    test("Round-trip serialization for all 31 AgentEvent variants") {
      val events: Chunk[AgentEvent] = Chunk(
        AgentEvent.AgentStartEvent("sess-1", "rep-1", "ag-1"),
        AgentEvent.AgentEndEvent("rep-1"),
        AgentEvent.AgentProgressEvent("Processing step 1", 1, Some(5)),
        AgentEvent.TextBlockStartEvent(0),
        AgentEvent.TextBlockDeltaEvent("Hello", 0),
        AgentEvent.TextBlockEndEvent(0, "Hello world"),
        AgentEvent.ThinkingBlockStartEvent(0),
        AgentEvent.ThinkingBlockDeltaEvent("Let me plan", 0),
        AgentEvent.ThinkingBlockEndEvent(0, "Complete thought"),
        AgentEvent.ToolCallStartEvent("call-1", "calculator", 0, Some(0)),
        AgentEvent.ToolCallDeltaEvent("call-1", "{\"x\": 1}", 0, Some(0)),
        AgentEvent.ToolCallEndEvent("call-1", "calculator", Json.Obj("x" -> Json.Num(1)), 0),
        AgentEvent.ToolResultStartEvent("call-1", "calculator"),
        AgentEvent.ToolResultDeltaEvent("call-1", "partial"),
        AgentEvent.ToolResultEndEvent("call-1", "calculator", "42", ToolResultState.Success),
        AgentEvent.ModelRequestStartEvent("reasoning-model", 150),
        AgentEvent.ModelResponseChunkEvent("chunk-data"),
        AgentEvent.ModelResponseEndEvent("reasoning-model", 45, 195),
        AgentEvent.SubagentSpawnEvent("sess-1", "sub-sess-2", "sub-agent-1", Some("Worker")),
        AgentEvent.SubagentStartEvent("sub-sess-2", "sub-agent-1"),
        AgentEvent.SubagentEndEvent("sub-sess-2", "sub-agent-1", "completed"),
        AgentEvent.SubagentExposedEvent("sub-1", "ag-1", "sess-1", Some("Label")),
        AgentEvent.PermissionAskEvent("req-1", "shell_tool", Json.Obj("cmd" -> Json.Str("ls"))),
        AgentEvent.PermissionDecisionEvent("req-1", true, Some("Allowed by user")),
        AgentEvent.ExternalExecutionAskEvent(
          "ext-1",
          "browser",
          Json.Obj("url" -> Json.Str("https://example.com"))
        ),
        AgentEvent.ExternalExecutionResultEvent("ext-1", "html payload", false),
        AgentEvent.CompactionStartEvent(100000, 42),
        AgentEvent.CompactionEndEvent(45000, 20),
        AgentEvent.HintBlockEvent("Consider edge cases"),
        AgentEvent.ErrorEvent("Failed to execute tool", Some("ERR_TOOL_FAILURE")),
        AgentEvent.CustomEvent("telemetry", Json.Obj("metric" -> Json.Str("latency_ms")))
      )

      assertTrue(events.size == 31) &&
      assertTrue(
        events.forall { ev =>
          val json    = ev.toJson
          val decoded = json.fromJson[AgentEvent]
          decoded.contains(ev) && json.contains(""""event":""")
        }
      )
    },
    test("Hierarchical source tagging with withSource") {
      val ev = AgentEvent.TextBlockDeltaEvent("hello", 0).withSource("parentSession/childAgent")

      assertTrue(
        ev.source.contains("parentSession/childAgent")
      )

      val json    = ev.toJson
      val decoded = json.fromJson[AgentEvent]

      assertTrue(
        decoded.isRight,
        decoded.toOption.get.source.contains("parentSession/childAgent")
      )
    },
    test("Rejects unknown event discriminators gracefully") {
      val invalidJson =
        """{
          |  "event": "unknown_unsupported_event",
          |  "data": "xyz"
          |}""".stripMargin

      val decoded = invalidJson.fromJson[AgentEvent]
      assertTrue(
        decoded.isLeft,
        decoded.swap.toOption.get.contains("Unknown agent event discriminator")
      )
    }
  )
