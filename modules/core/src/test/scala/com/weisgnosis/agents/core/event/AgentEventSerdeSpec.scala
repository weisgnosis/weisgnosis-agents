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

import com.weisgnosis.agents.core.model.{ContentBlock, Msg, ToolResultState}
import zio.Chunk
import zio.json.*
import zio.json.ast.Json
import zio.test.*

/**
 * Complete round-trip serialization and discriminator verification for all 39 AgentEvent types.
 */
object AgentEventSerdeSpec extends ZIOSpecDefault:

  final private case class Example(
      value: AgentEvent,
      encoded: String,
      decode: String => Either[String, AgentEvent]
  )

  private def example[A <: AgentEvent: JsonCodec](value: A): Example =
    Example(value, value.toJson, json => json.fromJson[A])

  private val examples: Chunk[Example] = Chunk(
    example(AgentEvent.AgentResultEvent(Msg.assistant("final"))),
    example(AgentEvent.DataBlockStartEvent("reply", "block")),
    example(AgentEvent.DataBlockDeltaEvent("reply", "block", "fragment")),
    example(AgentEvent.DataBlockEndEvent("reply", "block")),
    example(
      AgentEvent.ToolResultDataDeltaEvent(
        "reply",
        "call",
        "tool",
        ContentBlock.Data.url("https://example.com/data"),
        Map("trace" -> Json.Str("kept"))
      )
    ),
    example(AgentEvent.ExceedMaxItersEvent("reply", 3, 3)),
    example(AgentEvent.RequestStopEvent("checkpoint")),
    example(
      AgentEvent.AllToolsDeniedEvent(Chunk(ContentBlock.ToolUse("call", "tool", Json.Obj())))
    ),
    example(AgentEvent.AgentStartEvent("sess-1", "rep-1", "ag-1")),
    example(AgentEvent.AgentEndEvent("rep-1")),
    example(AgentEvent.AgentProgressEvent("Processing step 1", 1, Some(5))),
    example(AgentEvent.TextBlockStartEvent(0)),
    example(AgentEvent.TextBlockDeltaEvent("Hello", 0)),
    example(AgentEvent.TextBlockEndEvent(0, "Hello world")),
    example(AgentEvent.ThinkingBlockStartEvent(0)),
    example(AgentEvent.ThinkingBlockDeltaEvent("Let me plan", 0)),
    example(AgentEvent.ThinkingBlockEndEvent(0, "Complete thought")),
    example(AgentEvent.ToolCallStartEvent("call-1", "calculator", 0, Some(0))),
    example(AgentEvent.ToolCallDeltaEvent("call-1", "{\"x\": 1}", 0, Some(0))),
    example(AgentEvent.ToolCallEndEvent("call-1", "calculator", Json.Obj("x" -> Json.Num(1)), 0)),
    example(AgentEvent.ToolResultStartEvent("call-1", "calculator")),
    example(AgentEvent.ToolResultDeltaEvent("call-1", "partial")),
    example(AgentEvent.ToolResultEndEvent("call-1", "calculator", "42", ToolResultState.Success)),
    example(AgentEvent.ModelRequestStartEvent("reasoning-model", 150)),
    example(AgentEvent.ModelResponseChunkEvent("chunk-data")),
    example(AgentEvent.ModelResponseEndEvent("reasoning-model", 45, 195)),
    example(AgentEvent.SubagentSpawnEvent("sess-1", "sub-sess-2", "sub-agent-1", Some("Worker"))),
    example(AgentEvent.SubagentStartEvent("sub-sess-2", "sub-agent-1")),
    example(AgentEvent.SubagentEndEvent("sub-sess-2", "sub-agent-1", "completed")),
    example(AgentEvent.SubagentExposedEvent("sub-1", "ag-1", "sess-1", Some("Label"))),
    example(
      AgentEvent.PermissionAskEvent("req-1", "shell_tool", Json.Obj("cmd" -> Json.Str("ls")))
    ),
    example(AgentEvent.PermissionDecisionEvent("req-1", true, Some("Allowed by user"))),
    example(
      AgentEvent.ExternalExecutionAskEvent(
        "ext-1",
        "browser",
        Json.Obj("url" -> Json.Str("https://example.com"))
      )
    ),
    example(AgentEvent.ExternalExecutionResultEvent("ext-1", "html payload", false)),
    example(AgentEvent.CompactionStartEvent(100000, 42)),
    example(AgentEvent.CompactionEndEvent(45000, 20)),
    example(AgentEvent.HintBlockEvent("Consider edge cases")),
    example(AgentEvent.ErrorEvent("Failed to execute tool", Some("ERR_TOOL_FAILURE"))),
    example(AgentEvent.CustomEvent("telemetry", Json.Obj("metric" -> Json.Str("latency_ms"))))
  )

  def spec: Spec[TestEnvironment, Any] = suite("AgentEventSerdeSpec")(
    test("Round-trip serialization for all 39 AgentEvent variants") {
      val events = examples.map(_.value)

      assertTrue(events.size == 39) &&
      assertTrue(
        events.forall { ev =>
          val json    = ev.toJson
          val decoded = json.fromJson[AgentEvent]
          decoded.contains(ev) && json.contains(""""event":""")
        }
      )
    },
    test("concrete and parent codecs share one canonical discriminator for every variant") {
      assertTrue(examples.forall { sample =>
        sample.encoded.fromJson[AgentEvent].contains(sample.value) &&
        sample.decode(sample.value.toJson).contains(sample.value) &&
        sample.encoded.fromJson[Json] == sample.value.toJson.fromJson[Json]
      })
    },
    test("leaf decoding reads legacy untagged payloads and rejects conflicting discriminators") {
      val legacy = """{"delta":"hello","blockIndex":0}"""
      val invalid = Chunk(
        """{"event":"text_block_start","delta":"hello","blockIndex":0}""",
        """{"event":null,"delta":"hello","blockIndex":0}""",
        """{"event":7,"delta":"hello","blockIndex":0}""",
        """{"event":"text_block_delta","event":"text_block_delta","delta":"hello","blockIndex":0}"""
      )
      assertTrue(
        legacy
          .fromJson[AgentEvent.TextBlockDeltaEvent]
          .contains(AgentEvent.TextBlockDeltaEvent("hello", 0)),
        legacy.fromJson[AgentEvent].isLeft,
        invalid.forall(_.fromJson[AgentEvent.TextBlockDeltaEvent].isLeft)
      )
    },
    test("every leaf still decodes its legacy untagged payload") {
      assertTrue(examples.forall { sample =>
        sample.encoded.fromJson[Json].flatMap(_.asObject.toRight("Expected object")).exists { obj =>
          sample
            .decode(Json.Obj(obj.fields.filterNot(_._1 == "event")).toJson)
            .contains(sample.value)
        }
      })
    },
    test("parent decoding rejects invalid discriminators and malformed required fields") {
      val invalid = Chunk(
        "null",
        "[]",
        "{}",
        """{"event":null}""",
        """{"event":7}""",
        """{"event":"agent_end","event":"agent_end","replyId":"reply"}""",
        """{"event":"text_block_delta","blockIndex":0}""",
        """{"event":"text_block_delta","delta":"x","blockIndex":"invalid"}"""
      )
      assertTrue(invalid.forall(_.fromJson[AgentEvent].isLeft))
    },
    test("toolResultEndEventMetadataRoundTrip") {
      val json =
        """{"event":"tool_result_end","callId":"call","name":"search","output":"done","state":"success","source":"parent","metadata":{"source":"meta_tool","cost":1}}"""
      val expected = Json.Obj("source" -> Json.Str("meta_tool"), "cost" -> Json.Num(1))
      val decoded  = json.fromJson[AgentEvent]
      val tagged   = decoded.map(_.withSource("next")).flatMap(_.toJsonAST)
      assertTrue(
        decoded.contains(
          AgentEvent.ToolResultEndEvent(
            "call",
            "search",
            "done",
            ToolResultState.Success,
            source = Some("parent"),
            metadata = Map("source" -> Json.Str("meta_tool"), "cost" -> Json.Num(1))
          )
        ),
        tagged.exists(
          _.asObject.exists(obj =>
            obj.get("metadata").contains(expected) && obj.get("source").contains(Json.Str("next"))
          )
        )
      )
    },
    test("toolResultTextDeltaEventMetadataRoundTrip") {
      val json =
        """{"event":"tool_result_delta","callId":"call","delta":"partial","metadata":{"chunk":true}}"""
      val decoded = json.fromJson[AgentEvent].flatMap(_.toJsonAST)
      assertTrue(
        json
          .fromJson[AgentEvent]
          .contains(
            AgentEvent
              .ToolResultDeltaEvent("call", "partial", metadata = Map("chunk" -> Json.Bool(true)))
          ),
        decoded.exists(
          _.asObject.exists(_.get("metadata").contains(Json.Obj("chunk" -> Json.Bool(true))))
        )
      )
    },
    test("toolResultStartEventEmptyMetadata") {
      val event   = AgentEvent.ToolResultStartEvent("call", "search")
      val encoded = event.toJson.fromJson[AgentEvent].flatMap(_.toJsonAST)
      assertTrue(
        event.toJson.fromJson[AgentEvent].contains(event),
        encoded.exists(_.asObject.exists(obj => obj.get("metadata").forall(_ == Json.Obj())))
      )
    },
    test("tool result metadata survives repeated tagging and rejects malformed supplied values") {
      val metadata =
        Map("source" -> Json.Str("tool"), "nested" -> Json.Obj("flag" -> Json.Bool(true)))
      val events: Chunk[AgentEvent] = Chunk(
        AgentEvent.ToolResultStartEvent("call", "search", metadata = metadata),
        AgentEvent.ToolResultDeltaEvent("call", "chunk", metadata = metadata),
        AgentEvent.ToolResultEndEvent(
          "call",
          "search",
          "done",
          ToolResultState.Success,
          metadata = metadata
        )
      )
      assertTrue(events.forall { event =>
        event.toJsonAST.flatMap(_.asObject.toRight("Expected object")).exists { obj =>
          val invalid =
            Json.Obj(obj.fields.filterNot(_._1 == "metadata") :+ ("metadata" -> Json.Num(7)))
          val tagged    = event.withSource("first").withSource("second")
          val preserved = tagged.toJsonAST.flatMap(_.asObject.toRight("Expected object"))
          invalid.as[AgentEvent].isLeft && tagged.toJson.fromJson[AgentEvent].contains(tagged) &&
          preserved.exists(_.get("metadata") == obj.get("metadata"))
        }
      })
    },
    test("Hierarchical source tagging with withSource") {
      assertTrue(examples.forall { sample =>
        val tagged = sample.value.withSource("first").withSource("parentSession/childAgent")
        tagged.source.contains("parentSession/childAgent") && sample.value.source.isEmpty &&
        sample.decode(tagged.toJson).contains(tagged) &&
        tagged.toJson.fromJson[AgentEvent].contains(tagged)
      })
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
        decoded.left.exists(_.nonEmpty)
      )
    }
  )
