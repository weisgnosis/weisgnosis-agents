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
import zio.json.ast.Json
import zio.test.*

/**
 * Selected upstream scenario suite for MsgTest.java and RoleContentValidationTest.java from AgentScope Java.
 */
object MsgSpec extends ZIOSpecDefault:

  def spec: Spec[TestEnvironment, Any] = suite("MsgSpec (Selected upstream scenarios)")(
    test("message construction and decoding are pure, identity allocation is explicit") {
      val first  = Msg.user("same")
      val second = Msg.user("same")
      for
        identified <- first.identified
        preserved  <- identified.identified
      yield assertTrue(
        first == second,
        first.id.isEmpty,
        identified.id.exists(_.nonEmpty),
        preserved == identified
      )
    },
    test(
      "MsgTests.testConstructor preserves supplied timestamp and metadata with optional content"
    ) {
      val metadata  = Map("key" -> Json.Str("value"))
      val timestamp = Some("2026-09-29T00:00:00Z")
      val full = Msg(
        role = Role.System,
        name = Some("test"),
        content = Chunk(ContentBlock.Text("hello")),
        timestamp = timestamp,
        metadata = metadata
      )
      val empty = Msg(role = Role.System, name = Some("test"), timestamp = timestamp)
      assertTrue(
        full.timestamp == timestamp,
        full.metadata == metadata,
        full.text == "hello",
        empty.content.isEmpty,
        empty.timestamp == timestamp
      )
    },
    suite("Basic Creation & Content Extraction")(
      test("testBasicMsgCreation") {
        val textBlock = ContentBlock.Text("Hello World")
        val msg       = Msg(name = Some("user"), role = Role.User, content = Chunk(textBlock))

        assertTrue(
          msg.name.contains("user"),
          msg.role == Role.User,
          msg.getFirstContentBlock.contains(textBlock)
        )
      },
      test("testBuilderWithTextBlock") {
        val textBlock = ContentBlock.Text("Hello World")
        val msg       = Msg(name = Some("user"), role = Role.User, content = Chunk(textBlock))

        assertTrue(
          msg.getFirstContentBlock.contains(textBlock),
          msg.text == "Hello World"
        )
      },
      test("testBuilderWithImageBlock") {
        val imageBlock = ContentBlock.Image.url("https://example.com/image.jpg")
        val msg        = Msg(name = Some("user"), role = Role.User, content = Chunk(imageBlock))

        assertTrue(msg.getFirstContentBlock.contains(imageBlock))
      },
      test("testBuilderWithAudioBlock") {
        val audioBlock = ContentBlock.Audio.base64("audio/mp3", "base64audiodata")
        val msg        = Msg(name = Some("user"), role = Role.User, content = Chunk(audioBlock))

        assertTrue(msg.getFirstContentBlock.contains(audioBlock))
      },
      test("testBuilderWithVideoBlock") {
        val videoBlock = ContentBlock.Video.url("https://example.com/video.mp4")
        val msg        = Msg(name = Some("user"), role = Role.User, content = Chunk(videoBlock))

        assertTrue(msg.getFirstContentBlock.contains(videoBlock))
      },
      test("testBuilderWithThinkingBlock") {
        val thinkingBlock = ContentBlock.Thinking("Let me think about this...")
        val msg =
          Msg(name = Some("assistant"), role = Role.Assistant, content = Chunk(thinkingBlock))

        assertTrue(
          msg.getFirstContentBlock.contains(thinkingBlock),
          msg.thinking == "Let me think about this..."
        )
      },
      test("testBuilderPattern") {
        val textBlock = ContentBlock.Text("System message")
        val msg       = Msg(name = Some("test"), role = Role.System, content = Chunk(textBlock))

        assertTrue(
          msg.name.contains("test"),
          msg.role == Role.System,
          msg.text == "System message"
        )
      },
      test("testGetTextContentWithSingleTextBlock") {
        val msg = Msg.user("Hello, world!")
        assertTrue(msg.getTextContent == "Hello, world!")
      },
      test("testGetTextContentWithMultipleTextBlocks") {
        val b1  = ContentBlock.Text("First line")
        val b2  = ContentBlock.Text("Second line")
        val b3  = ContentBlock.Text("Third line")
        val msg = Msg(role = Role.Assistant, content = Chunk(b1, b2, b3), name = Some("assistant"))

        assertTrue(msg.getTextContent == "First line\nSecond line\nThird line")
      },
      test("testGetTextContentWithMixedContentTypes") {
        val img = ContentBlock.Image.url("https://example.com/image.jpg")
        val t1  = ContentBlock.Text("Here's an image for you:")
        val t2  = ContentBlock.Text("I hope you like it!")
        val msg = Msg(
          role = Role.Assistant,
          content = Chunk(t1, img, t2),
          name = Some("multimodal-assistant")
        )

        assertTrue(msg.getTextContent == "Here's an image for you:\nI hope you like it!")
      },
      test("testGetTextContentWithNoTextBlocks") {
        val img = ContentBlock.Image.url("https://example.com/image.jpg")
        val msg = Msg(role = Role.Assistant, content = Chunk(img), name = Some("image-bot"))

        assertTrue(msg.getTextContent == "")
      },
      test("testGetTextContentWithEmptyMessage") {
        val msg = Msg(role = Role.Assistant, name = Some("empty-bot"))
        assertTrue(msg.getTextContent == "")
      },
      test("testGetTextContentWithToolBlocks") {
        val toolUse = ContentBlock.ToolUse(
          "tool-123",
          "calculator",
          Json.Obj("x" -> Json.Num(5), "y" -> Json.Num(3))
        )
        val textBlock = ContentBlock.Text("I'll calculate that for you.")
        val msg =
          Msg(role = Role.Assistant, content = Chunk(textBlock, toolUse), name = Some("assistant"))

        assertTrue(
          msg.getTextContent == "I'll calculate that for you.",
          msg.toolUses.size == 1,
          msg.toolUses.headOption.exists(_.id == "tool-123")
        )
      },
      test("testGetTextContentWithEmptyTextBlock") {
        val emptyText  = ContentBlock.Text("")
        val normalText = ContentBlock.Text("Normal text")
        val msg = Msg(
          role = Role.Assistant,
          content = Chunk(emptyText, normalText),
          name = Some("assistant")
        )

        assertTrue(msg.getTextContent == "\nNormal text")
      },
      test("testTextContentConvenienceMethod") {
        val msg = Msg.user("Hello World")

        assertTrue(
          msg.name.contains("user"),
          msg.role == Role.User,
          msg.getTextContent == "Hello World",
          msg.getFirstContentBlock.contains(ContentBlock.Text("Hello World"))
        )
      }
    ),
    suite("Role Content Validation (RoleContentValidationTest.java Parity)")(
      test("USER role accepts text, data, image, audio, video") {
        val text  = ContentBlock.Text("hello")
        val data  = ContentBlock.Data.url("https://example.com/x.png", Some("x.png"))
        val img   = ContentBlock.Image.url("https://example.com/x.png")
        val audio = ContentBlock.Audio.url("https://example.com/x.mp3")
        val video = ContentBlock.Video.url("https://example.com/x.mp4")

        assertTrue(
          Msg.validateRoleContent(Role.User, Chunk(text)).isRight,
          Msg.validateRoleContent(Role.User, Chunk(data)).isRight,
          Msg.validateRoleContent(Role.User, Chunk(img)).isRight,
          Msg.validateRoleContent(Role.User, Chunk(audio)).isRight,
          Msg.validateRoleContent(Role.User, Chunk(video)).isRight
        )
      },
      test("USER role rejects hint, thinking, tool_use, tool_result") {
        val hint       = ContentBlock.Hint(Some("h1"), "consider X")
        val thinking   = ContentBlock.Thinking("reasoning...")
        val toolUse    = ContentBlock.ToolUse("call-1", "search", Json.Obj("q" -> Json.Str("test")))
        val toolResult = ContentBlock.ToolResult.text("call-1", "search", "result")

        assertTrue(
          Msg.validateRoleContent(Role.User, Chunk(hint)).isLeft,
          Msg.validateRoleContent(Role.User, Chunk(thinking)).isLeft,
          Msg.validateRoleContent(Role.User, Chunk(toolUse)).isLeft,
          Msg.validateRoleContent(Role.User, Chunk(toolResult)).isLeft
        )
      },
      test("SYSTEM role accepts text only") {
        val text     = ContentBlock.Text("system prompt")
        val img      = ContentBlock.Image.url("https://example.com/x.png")
        val hint     = ContentBlock.Hint(Some("h1"), "hint")
        val thinking = ContentBlock.Thinking("thought")

        assertTrue(
          Msg.validateRoleContent(Role.System, Chunk(text)).isRight,
          Msg.validateRoleContent(Role.System, Chunk(img)).isLeft,
          Msg.validateRoleContent(Role.System, Chunk(hint)).isLeft,
          Msg.validateRoleContent(Role.System, Chunk(thinking)).isLeft
        )
      },
      test("ASSISTANT role is unrestricted") {
        val text       = ContentBlock.Text("response")
        val hint       = ContentBlock.Hint(Some("h1"), "hint")
        val thinking   = ContentBlock.Thinking("thought")
        val toolUse    = ContentBlock.ToolUse("c1", "search", Json.Obj())
        val toolResult = ContentBlock.ToolResult.text("c1", "search", "output")

        assertTrue(
          Msg
            .validateRoleContent(Role.Assistant, Chunk(text, hint, thinking, toolUse, toolResult))
            .isRight
        )
      },
      test("TOOL role is unrestricted (Java back-compat)") {
        val toolResult = ContentBlock.ToolResult.text("c1", "search", "output")
        val text       = ContentBlock.Text("plain tool result")

        assertTrue(
          Msg.validateRoleContent(Role.Tool, Chunk(toolResult)).isRight,
          Msg.validateRoleContent(Role.Tool, Chunk(text)).isRight
        )
      },
      test("Empty content list is valid for all roles") {
        assertTrue(
          Msg.validateRoleContent(Role.User, Chunk.empty).isRight,
          Msg.validateRoleContent(Role.System, Chunk.empty).isRight,
          Msg.validateRoleContent(Role.Assistant, Chunk.empty).isRight,
          Msg.validateRoleContent(Role.Tool, Chunk.empty).isRight
        )
      },
      test("Single invalid block rejects the entire message via Msg.create") {
        val text = ContentBlock.Text("hello")
        val hint = ContentBlock.Hint(Some("h1"), "hint")
        val res  = Msg.create(role = Role.User, content = Chunk(text, hint))

        assertTrue(res.isLeft)
      }
    )
  )
