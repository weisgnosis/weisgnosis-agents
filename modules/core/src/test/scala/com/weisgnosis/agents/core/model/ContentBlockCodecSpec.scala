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

import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

/** Concrete and polymorphic codecs share one lossless, strictly discriminated representation. */
object ContentBlockCodecSpec extends ZIOSpecDefault:
  private def contract[A <: ContentBlock: JsonCodec](value: A, tag: String): TestResult =
    val root: ContentBlock = value
    val concrete           = value.toJson.fromJson[Json]
    val broad              = root.toJson.fromJson[Json]
    val legacy = concrete
      .flatMap(_.asObject.toRight("Expected object"))
      .map(obj => Json.Obj(obj.fields.filterNot(_._1 == "type")))
    assertTrue(
      concrete == broad,
      value.toJson.fromJson[ContentBlock].contains(value),
      root.toJson.fromJson[A].contains(value),
      legacy.flatMap(_.as[A]).contains(value),
      legacy.flatMap(_.as[ContentBlock]).isLeft,
      concrete.exists(_.asObject.exists(_.fields.count(_._1 == "type") == 1)),
      concrete.exists(_.asObject.flatMap(_.get("type")).contains(Json.Str(tag))),
      concrete.flatMap(_.asObject.toRight("Expected object")).forall { obj =>
        Chunk(Json.Null, Json.Num(1), Json.Str("unrelated"))
          .forall(bad => obj.add("type", bad).as[A].isLeft)
      }
    )

  def spec: Spec[TestEnvironment, Any] = suite("ContentBlockCodecSpec")(
    test("text concrete and polymorphic codecs")(contract(ContentBlock.Text("visible"), "text")),
    test("thinking concrete and polymorphic codecs") {
      contract(ContentBlock.Thinking("thought", Map("trace" -> Json.Str("kept"))), "thinking")
    },
    test("tool use concrete and polymorphic codecs") {
      contract(
        ContentBlock.ToolUse(
          "id",
          "tool",
          Json.Obj("x" -> Json.Num(2)),
          Some("raw"),
          Map("trace" -> Json.Bool(true)),
          ToolCallState.Submitted
        ),
        "tool_use"
      )
    },
    test("tool result concrete and polymorphic codecs") {
      contract(
        ContentBlock.ToolResult(
          "id",
          Some("tool"),
          Chunk(ContentBlock.Text("output")),
          ToolResultState.Error,
          Map("trace" -> Json.Bool(true))
        ),
        "tool_result"
      )
    },
    test("image concrete and polymorphic codecs") {
      contract(ContentBlock.Image.url("https://example.com/image"), "image")
    },
    test("audio concrete and polymorphic codecs") {
      contract(ContentBlock.Audio.url("https://example.com/audio"), "audio")
    },
    test("video concrete and polymorphic codecs") {
      contract(ContentBlock.Video.url("https://example.com/video"), "video")
    },
    test("data concrete and polymorphic codecs") {
      contract(ContentBlock.Data.base64("application/pdf", "encoded", Some("doc")), "data")
    },
    test("hint concrete and polymorphic codecs") {
      contract(ContentBlock.Hint(hint = "hint", source = Some("runtime")), "hint")
    },
    test("custom concrete and polymorphic codecs") {
      contract(ContentBlock.Custom("extension", Json.Obj("value" -> Json.Bool(true))), "custom")
    },
    test("duplicate and invalid root discriminators fail for concrete and polymorphic decoding") {
      val invalid = Chunk(
        """{"type":"text","type":"text","text":"x"}""",
        """{"type":null,"text":"x"}""",
        """{"type":1,"text":"x"}""",
        "[]",
        "null"
      )
      assertTrue(
        invalid.forall(json =>
          json.fromJson[ContentBlock].isLeft && json.fromJson[ContentBlock.Text].isLeft
        )
      )
    },
    test("nested results preserve payloads and validate every nested discriminator") {
      val nested = ContentBlock.ToolResult(
        "outer",
        output = Chunk(ContentBlock.ToolResult("inner", output = Chunk(ContentBlock.Text("kept"))))
      )
      val invalid = Chunk(
        """{"id":"outer","output":[{"text":"missing tag"}]}""",
        """{"id":"outer","output":[{"type":"text","type":"text","text":"duplicate"}]}"""
      )
      assertTrue(
        nested.toJson.fromJson[ContentBlock.ToolResult].contains(nested),
        nested.toJson.fromJson[ContentBlock].contains(nested),
        invalid.forall(_.fromJson[ContentBlock.ToolResult].isLeft)
      )
    },
    test("compatibility defaults and legacy result text retain exact meaning") {
      val result  = """{"id":"id","output":"legacy"}""".fromJson[ContentBlock.ToolResult]
      val missing = """{"id":"id"}""".fromJson[ContentBlock.ToolResult]
      val empty   = """{"id":"id","output":null}""".fromJson[ContentBlock.ToolResult]
      assertTrue(
        result.exists(_.output == Chunk(ContentBlock.Text("legacy"))),
        missing == empty,
        missing.exists(_.output.isEmpty),
        "{}".fromJson[ContentBlock.Thinking].contains(ContentBlock.Thinking("")),
        """{"thinking":null,"metadata":null}"""
          .fromJson[ContentBlock.Thinking]
          .contains(ContentBlock.Thinking("")),
        """{"thinking":1}""".fromJson[ContentBlock.Thinking].isLeft,
        ContentBlock
          .ToolResult("id")
          .toJson
          .fromJson[Json]
          .exists(_.asObject.exists(_.get("metadata").isEmpty))
      )
    }
  )
