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

/**
 * Selected upstream scenario suite for ContentBlock JSON serialization and deserialization
 * matching ToolUseBlockTest.java and ToolResultBlockTest.java.
 */
object ContentBlockSerdeSpec extends ZIOSpecDefault:

  def spec: Spec[TestEnvironment, Any] = suite("ContentBlockSerdeSpec")(
    suite("ToolUseBlockTest Parity")(
      test("testJsonSerializationWithAllFields") {
        val toolUse = ContentBlock.ToolUse(
          id = "tool-123",
          name = "calculator",
          input = Json.Obj("x" -> Json.Num(5), "y" -> Json.Num(3), "operation" -> Json.Str("add")),
          content = Some("Raw streaming content"),
          metadata = Map("thoughtSignature" -> Json.Str("signature123"))
        )

        val json = toolUse.toJson
        assertTrue(
          json.contains(""""id":"tool-123""""),
          json.contains(""""name":"calculator""""),
          json.contains(""""content":"Raw streaming content"""")
        )
      },
      test("testJsonDeserializationWithAllFields") {
        val json =
          """{
            |  "type": "tool_use",
            |  "id": "tool-123",
            |  "name": "calculator",
            |  "input": {"x": 5, "y": 3, "operation": "add"},
            |  "content": "Raw streaming content",
            |  "metadata": {"thoughtSignature": "signature123", "key": "value"}
            |}""".stripMargin

        val decoded = json.fromJson[ContentBlock]
        assertTrue(
          decoded.isRight,
          decoded match
            case Right(tu: ContentBlock.ToolUse) =>
              tu.id == "tool-123" &&
              tu.name == "calculator" &&
              tu.input == Json.Obj(
                "x"         -> Json.Num(5),
                "y"         -> Json.Num(3),
                "operation" -> Json.Str("add")
              ) &&
              tu.content.contains("Raw streaming content") &&
              tu.thoughtSignature.contains("signature123") &&
              tu.metadata == Map(
                "thoughtSignature" -> Json.Str("signature123"),
                "key"              -> Json.Str("value")
              )
            case _ => false
        )
      },
      test("testJsonDeserializationWithoutContent") {
        val json =
          """{
            |  "type": "tool_use",
            |  "id": "tool-456",
            |  "name": "search",
            |  "input": {"query": "test search"},
            |  "metadata": {"source": "web"}
            |}""".stripMargin

        val decoded = json.fromJson[ContentBlock]
        assertTrue(
          decoded.isRight,
          decoded match
            case Right(tu: ContentBlock.ToolUse) =>
              tu.id == "tool-456" &&
              tu.name == "search" &&
              tu.input == Json.Obj("query" -> Json.Str("test search")) &&
              tu.content.isEmpty &&
              tu.metadata == Map("source" -> Json.Str("web"))
            case _ => false
        )
      },
      test("testJsonDeserializationWithEmptyInput") {
        val json =
          """{
            |  "type": "tool_use",
            |  "id": "tool-999",
            |  "name": "no-input-tool",
            |  "input": {}
            |}""".stripMargin

        val decoded = json.fromJson[ContentBlock]
        assertTrue(
          decoded.isRight,
          decoded match
            case Right(tu: ContentBlock.ToolUse) =>
              tu.id == "tool-999" &&
              tu.name == "no-input-tool" && tu.input == Json.Obj()
            case _ => false
        )
      }
    ),
    suite("ToolResultBlockTest Parity")(
      test("errorFactoryCreatesStructuredErrorState") {
        val result = ContentBlock.ToolResult.error("probe failed")

        assertTrue(
          result.state == ToolResultState.Error,
          result.output.size == 1,
          result.outputText == "Error: probe failed"
        )
      },
      test("toolCallErrorFactoryPreservesToolIdAndRuntimeErrorMarker") {
        val result = ContentBlock.ToolResult.error("tool-1", "probe failed")

        assertTrue(
          result.id == "tool-1",
          result.state == ToolResultState.Error,
          result.output.size == 1,
          result.outputText == "[ERROR] probe failed"
        )
      },
      test("serverToolMarkerSurvivesJsonRoundTrip") {
        val result = ContentBlock.ToolResult(
          id = "tool-call-3",
          name = Some("GOOGLE_SEARCH_WEB"),
          output = Chunk(ContentBlock.Text("response")),
          state = ToolResultState.Success,
          metadata = Map("agentscope_server_tool" -> Json.Bool(true))
        )

        val json         = result.toJson
        val deserialized = json.fromJson[ContentBlock.ToolResult]

        assertTrue(
          json.contains("agentscope_server_tool"),
          deserialized.isRight,
          deserialized.exists(_.isServerTool)
        )
      },
      test("copyMethodsPreserveServerToolMarker") {
        val result = ContentBlock.ToolResult(
          id = "tool-call-5",
          name = Some("GOOGLE_SEARCH_WEB"),
          output = Chunk(ContentBlock.Text("response")),
          metadata = Map("agentscope_server_tool" -> Json.Bool(true))
        )

        val updated = result.withState(ToolResultState.Success)
        val renamed = updated.withIdAndName("tool-call-6", "GOOGLE_SEARCH_WEB")

        assertTrue(
          updated.isServerTool,
          updated.state == ToolResultState.Success,
          renamed.isServerTool,
          renamed.id == "tool-call-6",
          renamed.name.contains("GOOGLE_SEARCH_WEB")
        )
      },
      test("ToolResult rejects malformed state, name, and metadata") {
        val invalid = Chunk(
          "state"    -> Json.Str("failed_typo"),
          "state"    -> Json.Num(1),
          "name"     -> Json.Num(1),
          "metadata" -> Json.Str("invalid")
        )
        assertTrue(invalid.forall { case (key, value) =>
          Json
            .Obj("type" -> Json.Str("tool_result"), "id" -> Json.Str("call"), key -> value)
            .as[ContentBlock]
            .isLeft
        })
      },
      test("ToolResult defaults absent and JSON-null state but preserves every supplied state") {
        val missing = Json.Obj("id" -> Json.Str("call"))
        val empty   = Json.Obj("id" -> Json.Str("call"), "state" -> Json.Null)
        assertTrue(
          missing.as[ContentBlock.ToolResult].exists(_.state == ToolResultState.Success),
          empty.as[ContentBlock.ToolResult].exists(_.state == ToolResultState.Success),
          Chunk.fromArray(ToolResultState.values).forall { state =>
            ContentBlock
              .ToolResult("call", state = state)
              .toJson
              .fromJson[ContentBlock.ToolResult]
              .exists(_.state == state)
          }
        )
      },
      test("ToolResult decodes string output flexibly") {
        val json =
          """{
            |  "type": "tool_result",
            |  "id": "tool-call-7",
            |  "output": "plain text output",
            |  "state": "success"
            |}""".stripMargin

        val decoded = json.fromJson[ContentBlock.ToolResult]
        assertTrue(
          decoded.isRight,
          decoded.exists(_.outputText == "plain text output")
        )
      }
    ),
    suite("MediaSource & Modalities")(
      test(
        "Base64 accepts upstream media_type and reads legacy mediaType with canonical encoding"
      ) {
        val upstream = """{"type":"base64","media_type":"image/png","data":"iVBORw0KGgo="}"""
        val legacy   = """{"type":"base64","mediaType":"image/png","data":"iVBORw0KGgo="}"""
        val expected = MediaSource.Base64("image/png", "iVBORw0KGgo=")
        val encoded = (expected: MediaSource).toJson
          .fromJson[Json]
          .flatMap(_.asObject.toRight("Expected object"))
        assertTrue(
          upstream.fromJson[MediaSource].contains(expected),
          legacy.fromJson[MediaSource].contains(expected),
          upstream.fromJson[MediaSource.Base64].contains(expected),
          legacy.fromJson[MediaSource.Base64].contains(expected),
          encoded.exists(_.get("media_type").contains(Json.Str("image/png"))),
          encoded.exists(_.get("mediaType").isEmpty)
        )
      },
      test("Base64 rejects malformed and ambiguous media type fields") {
        val bad = Chunk(
          """{"type":"base64","media_type":7,"data":"x"}""",
          """{"type":"base64","media_type":"a","mediaType":"b","data":"x"}"""
        )
        assertTrue(bad.forall(_.fromJson[MediaSource].isLeft))
      },
      test("DataBlock testJsonRoundTrip retains the source, identity, name, and discriminator") {
        val block: ContentBlock = ContentBlock.Data(
          MediaSource.url("https://example.com/x.mp3"),
          Some("abc123"),
          Some("x.mp3")
        )
        val encoded = block.toJson
        assertTrue(
          encoded.fromJson[ContentBlock].contains(block),
          encoded.contains("\"type\":\"data\"")
        )
      },
      test("DataBlock testJsonDeserializationViaContentBlock accepts upstream base64 wire fields") {
        val json =
          """{"type":"data","source":{"type":"base64","media_type":"image/png","data":"aGVsbG8="},"id":"data-1","name":"image.png"}"""
        val expected = ContentBlock.Data(
          MediaSource.base64("image/png", "aGVsbG8="),
          Some("data-1"),
          Some("image.png")
        )
        assertTrue(json.fromJson[ContentBlock].contains(expected))
      },
      test("DataBlock testNameIsOptional preserves absence in JSON") {
        val block: ContentBlock = ContentBlock.Data(MediaSource.url("https://example.com/x"))
        val json                = block.toJson
        assertTrue(json.fromJson[ContentBlock].contains(block), !json.contains("\"name\""))
      },
      test("Thinking rejects malformed supplied text and metadata") {
        val bad = Chunk(
          """{"type":"thinking","thinking":7}""",
          """{"type":"thinking","thinking":"reason","metadata":"invalid"}"""
        )
        assertTrue(bad.forall(_.fromJson[ContentBlock].isLeft))
      },
      test("MediaSource URL and Base64 round-trip") {
        val urlSource: MediaSource = MediaSource.url("https://example.com/audio.mp3")
        val b64Source: MediaSource = MediaSource.base64("audio/mp3", "dGVzdA==")

        val urlJson = urlSource.toJson
        val b64Json = b64Source.toJson

        val decUrl = urlJson.fromJson[MediaSource]
        val decB64 = b64Json.fromJson[MediaSource]

        assertTrue(
          decUrl.contains(urlSource),
          decB64.contains(b64Source)
        )
      },
      test("ThinkingBlock round-trip with metadata") {
        val block = ContentBlock.Thinking(
          thinking = "Deep reasoning step...",
          metadata = Map("thoughtSignature" -> Json.Str("sig-xyz"))
        )

        val json    = (block: ContentBlock).toJson
        val decoded = json.fromJson[ContentBlock]

        assertTrue(
          decoded.isRight,
          decoded match
            case Right(t: ContentBlock.Thinking) =>
              t.thought == "Deep reasoning step..." &&
              t.signature.contains("sig-xyz")
            case _ => false
        )
      }
    )
  )
