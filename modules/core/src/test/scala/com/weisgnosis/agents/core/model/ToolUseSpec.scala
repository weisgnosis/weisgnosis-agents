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

import scala.compiletime.testing.typeCheckErrors
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

/** Remaining ToolUseBlockTest contracts, adapted to immutable values and explicit absence. */
object ToolUseSpec extends ZIOSpecDefault:
  private val input: Json                 = Json.Obj("param" -> Json.Str("value"))
  private val metadata: Map[String, Json] = Map("meta" -> Json.Str("kept"))

  def spec: Spec[TestEnvironment, Any] = suite("ToolUseSpec")(
    test("testJsonDeserializationWithoutMetadata") {
      val decoded =
        """{"type":"tool_use","id":"tool-789","name":"validator","input":{"value":100}}"""
          .fromJson[ContentBlock.ToolUse]
      assertTrue(
        decoded.contains(
          ContentBlock.ToolUse("tool-789", "validator", Json.Obj("value" -> Json.Num(100)))
        )
      )
    },
    test("testJsonDeserializationWithNullContent") {
      val decoded =
        """{"type":"tool_use","id":"tool-111","name":"test-tool","input":{"param":"value"},"content":null}"""
          .fromJson[ContentBlock.ToolUse]
      assertTrue(decoded.contains(ContentBlock.ToolUse("tool-111", "test-tool", input)))
    },
    test("testRoundTripSerialization") {
      val original =
        ContentBlock.ToolUse("tool-222", "processor", input, Some("streaming"), metadata)
      assertTrue(
        original.toJson.fromJson[ContentBlock.ToolUse].contains(original),
        original.toJson.fromJson[ContentBlock].contains(original)
      )
    },
    test("testInputMapIsUnmodifiable") {
      val original = ContentBlock.ToolUse("tool-333", "test", input)
      val changed  = original.copy(input = Json.Obj("other" -> Json.Str("new")))
      assertTrue(
        original.input == input,
        changed.input != original.input,
        typeCheckErrors(
          """com.weisgnosis.agents.core.model.ContentBlock.ToolUse("id", "tool", zio.json.ast.Json.Obj()).input.put("key", "value")"""
        ).nonEmpty
      )
    },
    test("testMetadataMapIsUnmodifiable") {
      val original = ContentBlock.ToolUse("tool-444", "test", input, metadata = metadata)
      val changed  = original.copy(metadata = original.metadata.updated("other", Json.Bool(true)))
      assertTrue(
        original.metadata == metadata,
        !original.metadata.contains("other"),
        changed.metadata.contains("other"),
        typeCheckErrors(
          """com.weisgnosis.agents.core.model.ContentBlock.ToolUse("id", "tool", zio.json.ast.Json.Obj()).metadata.put("key", zio.json.ast.Json.Str("value"))"""
        ).nonEmpty
      )
    },
    test("testConstructorWithThreeParameters") {
      val value = ContentBlock.ToolUse("tool-555", "simple-tool", input)
      assertTrue(
        value.id == "tool-555",
        value.name == "simple-tool",
        value.input == input,
        value.content.isEmpty,
        value.metadata.isEmpty,
        value.state == ToolCallState.Pending
      )
    },
    test("testConstructorWithFourParameters") {
      val value = ContentBlock.ToolUse("tool-666", "metadata-tool", input, metadata = metadata)
      assertTrue(
        value.id == "tool-666",
        value.name == "metadata-tool",
        value.input == input,
        value.metadata == metadata,
        value.content.isEmpty
      )
    },
    test("testConstructorWithAllFiveParameters") {
      val value =
        ContentBlock.ToolUse("tool-777", "full-tool", input, Some("content value"), metadata)
      assertTrue(
        value.id == "tool-777",
        value.name == "full-tool",
        value.input == input,
        value.content.contains("content value"),
        value.metadata == metadata
      )
    },
    test("testBuilderPattern") {
      val value = ContentBlock.ToolUse(
        id = "tool-888",
        name = "builder-test",
        input = input,
        content = Some("builder content"),
        metadata = metadata
      )
      assertTrue(
        value.id == "tool-888",
        value.name == "builder-test",
        value.input == input,
        value.content.contains("builder content"),
        value.metadata == metadata
      )
    },
    test("serverToolMarkerDistinguishesServerAndLocalCalls") {
      val server = ContentBlock
        .ToolUse("tool-1001", "web_search", input, metadata = Map("serverTool" -> Json.Bool(true)))
      val local = ContentBlock.ToolUse("tool-1002", "local-tool", input)
      assertTrue(
        server.id == "tool-1001",
        server.name == "web_search",
        server.input == input,
        server.isServerTool,
        !local.isServerTool
      )
    },
    test("serverToolMarkerSurvivesJsonRoundTrip") {
      val original = ContentBlock
        .ToolUse("tool-1009", "web_search", input, metadata = Map("serverTool" -> Json.Bool(true)))
      assertTrue(
        original.toJson.fromJson[ContentBlock.ToolUse].contains(original),
        original.toJson.fromJson[ContentBlock.ToolUse].exists(_.isServerTool)
      )
    },
    test("withStatePreservesServerToolMarker") {
      val original = ContentBlock.ToolUse(
        "tool-1010",
        "web_search",
        input,
        Some("raw"),
        metadata.updated("serverTool", Json.Bool(true))
      )
      val updated = original.withState(ToolCallState.Allowed)
      assertTrue(
        updated.isServerTool,
        updated.state == ToolCallState.Allowed,
        updated.copy(state = original.state) == original,
        original.state == ToolCallState.Pending
      )
    },
    test("testEmptyMapsForNullInputAndMetadata") {
      val decoded =
        """{"type":"tool_use","id":"tool-999","name":"absence-test","input":null,"content":null,"metadata":null}"""
          .fromJson[ContentBlock.ToolUse]
      val missing  = """{"id":"tool-999","name":"absence-test"}""".fromJson[ContentBlock.ToolUse]
      val expected = ContentBlock.ToolUse("tool-999", "absence-test", Json.Obj())
      assertTrue(
        decoded.contains(expected),
        missing.contains(expected),
        expected.copy(input = Json.Null).toJson.fromJson[ContentBlock.ToolUse].contains(expected)
      )
    },
    test("state codec covers all protocol values and rejects unknown or malformed values") {
      val states = Chunk(
        ToolCallState.Pending   -> "pending",
        ToolCallState.Asking    -> "asking",
        ToolCallState.Allowed   -> "allowed",
        ToolCallState.Submitted -> "submitted",
        ToolCallState.Finished  -> "finished"
      )
      assertTrue(
        states.size == ToolCallState.values.length,
        states.forall { case (state, wire) =>
          state.toJson == Json
            .Str(wire)
            .toJson && Json.Str(wire).as[ToolCallState].contains(state) &&
          ContentBlock
            .ToolUse("id", "tool", input, state = state)
            .toJson
            .fromJson[ContentBlock.ToolUse]
            .exists(_.state == state)
        },
        Chunk(Json.Str("unknown"), Json.Str("ALLOWED"), Json.Num(1), Json.Null)
          .forall(_.as[ToolCallState].isLeft)
      )
    },
    test("malformed optional fields fail and JSON-null state defaults only at the block boundary") {
      val base = Json.Obj("id" -> Json.Str("id"), "name" -> Json.Str("tool"), "input" -> input)
      val invalid = Chunk(
        "content"  -> Json.Num(1),
        "metadata" -> Json.Bool(true),
        "state"    -> Json.Str("unknown"),
        "state"    -> Json.Num(1)
      )
      assertTrue(
        invalid.forall { case (key, value) =>
          base.add(key, value).as[ContentBlock.ToolUse].isLeft
        },
        base
          .add("state", Json.Null)
          .as[ContentBlock.ToolUse]
          .exists(_.state == ToolCallState.Pending),
        ContentBlock
          .ToolUse("id", "tool", Json.Str("pruned arguments"))
          .toJson
          .fromJson[ContentBlock.ToolUse]
          .exists(_.input == Json.Str("pruned arguments"))
      )
    },
    test(
      "canonical server marker takes precedence and setters keep both existing aliases coherent"
    ) {
      val conflict =
        Map("serverTool" -> Json.Bool(false), "agentscope_server_tool" -> Json.Bool(true))
      val call    = ContentBlock.ToolUse("id", "tool", input, metadata = conflict)
      val result  = ContentBlock.ToolResult("id", metadata = conflict)
      val invalid = conflict.updated("serverTool", Json.Str("true"))
      assertTrue(
        !call.isServerTool,
        !result.isServerTool,
        call.withServerTool(true).isServerTool,
        !call.withServerTool(true).withServerTool(false).isServerTool,
        call.withServerTool(true).metadata.values.forall(_ == Json.Bool(true)),
        !call.copy(metadata = invalid).isServerTool,
        !result.copy(metadata = invalid).isServerTool,
        call.copy(metadata = Map("agentscope_server_tool" -> Json.Bool(true))).isServerTool,
        result.copy(metadata = Map("serverTool" -> Json.Bool(true))).isServerTool
      )
    }
  )
