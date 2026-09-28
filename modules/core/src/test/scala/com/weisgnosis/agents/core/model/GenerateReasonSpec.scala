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
package com.weisgnosis.agents.core.model

import zio.Chunk
import zio.json.*
import zio.json.ast.Json
import zio.test.*

/** GenerateReasonTest behavior adapted to immutable messages and lowercase metadata output. */
object GenerateReasonSpec extends ZIOSpecDefault:
  private val cases: Chunk[(String, String, GenerateReason)] = Chunk(
    ("MODEL_STOP", "model_stop", GenerateReason.ModelStop),
    ("TOOL_CALLS", "tool_calls", GenerateReason.ToolCalls),
    ("STRUCTURED_OUTPUT", "structured_output", GenerateReason.StructuredOutput),
    ("TOOL_SUSPENDED", "tool_suspended", GenerateReason.ToolSuspended),
    ("REASONING_STOP_REQUESTED", "reasoning_stop_requested", GenerateReason.ReasoningStopRequested),
    ("ACTING_STOP_REQUESTED", "acting_stop_requested", GenerateReason.ActingStopRequested),
    ("PERMISSION_ASKING", "permission_asking", GenerateReason.PermissionAsking),
    (
      "MIDDLEWARE_STOP_REQUESTED",
      "middleware_stop_requested",
      GenerateReason.MiddlewareStopRequested
    ),
    ("ALL_TOOLS_DENIED", "all_tools_denied", GenerateReason.AllToolsDenied),
    ("INTERRUPTED", "interrupted", GenerateReason.Interrupted),
    ("MAX_ITERATIONS", "max_iterations", GenerateReason.MaxIterations),
    ("TOOL_RETURN_DIRECT", "tool_return_direct", GenerateReason.ToolReturnDirect)
  )

  def spec: Spec[TestEnvironment, Any] = suite("GenerateReasonSpec")(
    test("testEnumValues") {
      assertTrue(
        cases.size == 12,
        cases.map(_._3).toSet == Chunk.fromArray(GenerateReason.values).toSet
      )
    },
    test("testDefaultGenerateReason") {
      val message = Msg.assistant("Hello")
      assertTrue(
        message.generationReason == GenerateReason.ModelStop,
        message.generateReason.isEmpty,
        message.metadata.isEmpty
      )
    },
    test("testGenerateReasonFromBuilder") {
      val message =
        Msg.assistant("External tool needed").withGenerateReason(GenerateReason.ToolSuspended)
      assertTrue(
        message.generationReason == GenerateReason.ToolSuspended,
        message.metadata.get(Msg.MetadataGenerateReason).contains(Json.Str("tool_suspended"))
      )
    },
    test("testWithGenerateReason") {
      val original = Msg.assistant("Hello")
      val updated  = original.withGenerateReason(GenerateReason.MaxIterations)
      assertTrue(
        original.generationReason == GenerateReason.ModelStop,
        original.metadata.isEmpty,
        updated.generationReason == GenerateReason.MaxIterations
      )
    },
    test("testGenerateReasonFromStringMetadata") {
      val message = Msg
        .assistant("Interrupted")
        .withMetadata(Msg.MetadataGenerateReason, Json.Str("INTERRUPTED"))
      assertTrue(
        message.generationReason == GenerateReason.Interrupted,
        message.generateReason.contains("INTERRUPTED")
      )
    },
    test("testInvalidGenerateReasonString") {
      val message = Msg.assistant("Test").withGenerateReason("INVALID_REASON")
      assertTrue(
        message.generationReason == GenerateReason.ModelStop,
        message.generateReason.contains("INVALID_REASON")
      )
    },
    test("testPreserveMetadataWithGenerateReason") {
      val original = Msg.assistant("Test").withMetadata("custom_key", Json.Str("custom_value"))
      val updated  = original.withGenerateReason(GenerateReason.ToolSuspended)
      assertTrue(
        updated.generationReason == GenerateReason.ToolSuspended,
        updated.metadata.get("custom_key").contains(Json.Str("custom_value")),
        original.metadata == Map("custom_key" -> Json.Str("custom_value"))
      )
    },
    test("testAllGenerateReasonValues") {
      assertTrue(cases.forall { case (_, wireName, reason) =>
        val message = Msg.assistant("Test").withGenerateReason(reason)
        message.generationReason == reason && message.generateReason.contains(
          wireName
        ) && message.toJson.fromJson[Msg].contains(message)
      })
    },
    test("testAllGenerateReasonValuesViaWith") {
      val original = Msg.assistant("Test")
      assertTrue(
        cases.forall { case (_, _, reason) =>
          original.withGenerateReason(reason).generationReason == reason
        },
        original.generationReason == GenerateReason.ModelStop,
        original.metadata.isEmpty
      )
    },
    test("all lowercase and uppercase labels decode with canonical lowercase output") {
      assertTrue(cases.forall { case (upstreamName, wireName, reason) =>
        upstreamName.toJson
          .fromJson[GenerateReason]
          .contains(reason) && wireName.toJson.fromJson[GenerateReason].contains(reason) &&
        reason.toJson == wireName.toJson && Msg
          .assistant("Test")
          .withGenerateReason(upstreamName)
          .generationReason == reason
      })
    },
    test("malformed metadata and raw extensions remain intact under defaulting projection") {
      val inputs = Chunk(
        Json.Num(7),
        Json.Null,
        Json.Obj(),
        Json.Str("empty_response"),
        Json.Str("Interrupted"),
        Json.Str(" interrupted")
      )
      assertTrue(
        inputs.forall { value =>
          val message = Msg.assistant("Test").withMetadata(Msg.MetadataGenerateReason, value)
          message.generationReason == GenerateReason.ModelStop && message.metadata
            .get(Msg.MetadataGenerateReason)
            .contains(value) && value.as[GenerateReason].isLeft
        },
        Msg
          .assistant("Test")
          .withGenerateReason("empty_response")
          .generateReason
          .contains("empty_response")
      )
    },
    test("typed reason updates preserve every unrelated message field") {
      val original = Msg(
        role = Role.Assistant,
        id = Some("message-id"),
        name = Some("agent"),
        content = Chunk(ContentBlock.Thinking("thought"), ContentBlock.Text("reply")),
        metadata = Map("trace" -> Json.Str("kept")),
        timestamp = Some("2026-09-29T00:00:00Z"),
        usage = Some(ChatUsage(inputTokens = 3, outputTokens = 5))
      )
      val updated = original.withGenerateReason(GenerateReason.Interrupted)
      assertTrue(
        updated == original.copy(metadata =
          original.metadata.updated(Msg.MetadataGenerateReason, Json.Str("interrupted"))
        ),
        updated.toJson.fromJson[Msg].contains(updated),
        original.generateReason.isEmpty
      )
    }
  )
