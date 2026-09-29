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

import com.weisgnosis.agents.core.error.AgentError
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

/** Immutable factory adaptations of all 26 upstream MsgSubclassesTest methods. */
object MsgFactorySpec extends ZIOSpecDefault:
  private val image: ContentBlock.Image = ContentBlock.Image.url("https://example.com/image")
  private val first: ContentBlock.ToolResult =
    ContentBlock.ToolResult.text("call-1", "search", "result-a")
  private val second: ContentBlock.ToolResult =
    ContentBlock.ToolResult.text("call-2", "search", "result-b")

  private def roundTrip(message: Msg): Either[String, Msg] = message.toJson.fromJson[Msg]

  def spec: Spec[TestEnvironment, Any] = suite("MsgFactorySpec")(
    test("userMessage_text") {
      val message = Msg.user("hello")
      assertTrue(
        message.role == Role.User,
        message.text == "hello",
        message.content == Chunk(ContentBlock.Text("hello")),
        message.name.contains("user"),
        message.id.isEmpty,
        message.timestamp.isEmpty
      )
    },
    test("userMessage_nameAndText") {
      val message = Msg.user("hello").withName("alice")
      assertTrue(message.role == Role.User, message.name.contains("alice"), message.text == "hello")
    },
    test("userMessage_varargsBlocks") {
      val message = Msg.user(Chunk(ContentBlock.Text("a"), ContentBlock.Text("b")))
      assertTrue(message.role == Role.User, message.content.size == 2, message.text == "a\nb")
    },
    test("userMessage_nameAndVarargsBlocks") {
      val message = Msg.user(Chunk(ContentBlock.Text("hello")), name = Some("alice"))
      assertTrue(
        message.name.contains("alice"),
        message.role == Role.User,
        message.content.size == 1
      )
    },
    test("userMessage_listOfBlocks") {
      val blocks  = List(ContentBlock.Text("x"))
      val message = Msg.user(Chunk.fromIterable(blocks))
      assertTrue(message.role == Role.User, message.text == "x")
    },
    test("assistantMessage_text") {
      val message = Msg.assistant("sure")
      assertTrue(message.role == Role.Assistant, message.text == "sure")
    },
    test("assistantMessage_acceptsToolUseBlock") {
      val call    = ContentBlock.ToolUse("call-1", "search", Json.Obj())
      val message = Msg.assistant(Chunk(call))
      assertTrue(message.role == Role.Assistant, message.content == Chunk(call))
    },
    test("systemMessage_text") {
      val message = Msg.system("you are helpful")
      assertTrue(message.role == Role.System, message.text == "you are helpful")
    },
    test("systemMessage_rejectsNonTextBlock") {
      assertTrue(
        Msg.create(Role.System, Chunk(image)).left.exists(_.isInstanceOf[AgentError.InvalidMessage])
      )
    },
    test("toolResultMessage_idNameText") {
      val message = Msg.tool("call-1", "search", "result")
      assertTrue(
        message.role == Role.Tool,
        message.content == Chunk(ContentBlock.ToolResult.text("call-1", "search", "result"))
      )
    },
    test("toolResultMessage_varargs") {
      val message = Msg.tool(Chunk(first, second))
      assertTrue(message.role == Role.Tool, message.toolResults == Chunk(first, second))
    },
    test("toolResultMessage_list") {
      val message = Msg.tool(Chunk.fromIterable(List(first)))
      assertTrue(message.role == Role.Tool, message.toolResults == Chunk(first))
    },
    test("jsonRoundTrip_userMessage") {
      val message = Msg.user("hi")
      assertTrue(
        roundTrip(message).contains(message),
        roundTrip(message).exists(_.role == Role.User)
      )
    },
    test("jsonRoundTrip_assistantMessage") {
      val message = Msg.assistant("ok")
      assertTrue(
        roundTrip(message).contains(message),
        roundTrip(message).exists(_.role == Role.Assistant)
      )
    },
    test("jsonRoundTrip_systemMessage") {
      val message = Msg.system("be terse")
      assertTrue(
        roundTrip(message).contains(message),
        roundTrip(message).exists(_.role == Role.System)
      )
    },
    test("jsonRoundTrip_toolResultMessage") {
      val message = Msg.tool("call-1", "search", "result")
      assertTrue(
        roundTrip(message).contains(message),
        roundTrip(message).exists(_.role == Role.Tool)
      )
    },
    test("legacyBuilderJson_deserializesToSubclass") {
      val decoded = """{"role":"ASSISTANT","content":[{"type":"text","text":"x"}]}""".fromJson[Msg]
      assertTrue(decoded.exists(message => message.role == Role.Assistant && message.text == "x"))
    },
    test("userMessageBuilder_textContent") {
      val message = Msg.user("hi").withName("alice")
      assertTrue(
        message.role == Role.User,
        message.name.contains("alice"),
        message.text == "hi",
        message.id.isEmpty,
        message.timestamp.isEmpty
      )
    },
    test("userMessageBuilder_idAndExplicitContent") {
      val message = Msg.user(Chunk(ContentBlock.Text("x")), id = Some("fixed-id"))
      assertTrue(message.id.contains("fixed-id"), message.content == Chunk(ContentBlock.Text("x")))
    },
    test("assistantMessageBuilder_returnsConcreteType") {
      val message: Msg = Msg.assistant("ok")
      assertTrue(message.role == Role.Assistant, message.text == "ok")
    },
    test("assistantMessageBuilder_generateReasonGoesIntoMetadata") {
      val message = Msg.assistant("ok").withGenerateReason(GenerateReason.ModelStop)
      assertTrue(
        message.generationReason == GenerateReason.ModelStop,
        message.metadata.get(Msg.MetadataGenerateReason).contains(Json.Str("model_stop"))
      )
    },
    test("systemMessageBuilder_text") {
      val message = Msg.system("be terse")
      assertTrue(message.role == Role.System, message.text == "be terse")
    },
    test("systemMessageBuilder_rejectsNonTextAtBuild") {
      val original = Msg.system("valid")
      assertTrue(
        original.withContent(Chunk(image)).left.exists(_.isInstanceOf[AgentError.InvalidMessage]),
        original.text == "valid"
      )
    },
    test("toolResultMessageBuilder_resultShorthandAccumulates") {
      val original = Msg.tool(Chunk(first))
      val updated  = original.withContent(original.content :+ second)
      assertTrue(
        updated.exists(message =>
          message.role == Role.Tool && message.toolResults == Chunk(first, second)
        ),
        original.toolResults == Chunk(first)
      )
    },
    test("toolResultMessageBuilder_resultsReplacesAccumulator") {
      val original = Msg.tool(Chunk(first, second))
      val fresh    = ContentBlock.ToolResult.text("c-final", "search", "fresh")
      val updated  = original.withContent(Chunk(fresh))
      assertTrue(
        updated.exists(message => message.role == Role.Tool && message.toolResults == Chunk(fresh)),
        original.toolResults == Chunk(first, second)
      )
    },
    test("subclassBuildersProduceJsonRoundTripCompatibleMessages") {
      val message = Msg.user("hey").withName("bob")
      assertTrue(
        roundTrip(message).contains(message),
        roundTrip(message).exists(value =>
          value.name.contains("bob") && value.role == Role.User && value.text == "hey"
        )
      )
    }
  )
