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

/** RoleContentValidationTest contracts at the smart-constructor and JSON boundaries. */
object RoleContentValidationSpec extends ZIOSpecDefault:
  private def decode(role: Role, content: Chunk[ContentBlock]): Either[String, Msg] =
    for
      roleJson    <- role.toJsonAST
      contentJson <- content.toJsonAST
      message     <- Json.Obj("role" -> roleJson, "content" -> contentJson).as[Msg]
    yield message

  private val textBlock: ContentBlock = ContentBlock.Text("text")
  private val data: ContentBlock  = ContentBlock.Data(MediaSource.url("https://example.com/data"))
  private val image: ContentBlock = ContentBlock.Image.url("https://example.com/image")
  private val audio: ContentBlock = ContentBlock.Audio.url("https://example.com/audio")
  private val video: ContentBlock = ContentBlock.Video.url("https://example.com/video")
  private val hint: ContentBlock  = ContentBlock.Hint(hint = "hint")
  private val thinking: ContentBlock = ContentBlock.Thinking("reasoning")
  private val call: ContentBlock     = ContentBlock.ToolUse("call", "tool", Json.Obj())
  private val result: ContentBlock   = ContentBlock.ToolResult.text("call", "tool", "result")

  private val accepted: Chunk[(String, Role, Chunk[ContentBlock])] = Chunk(
    ("userAcceptsText", Role.User, Chunk(textBlock)),
    ("userAcceptsData", Role.User, Chunk(data)),
    ("userAcceptsImage", Role.User, Chunk(image)),
    ("userAcceptsAudio", Role.User, Chunk(audio)),
    ("userAcceptsVideo", Role.User, Chunk(video)),
    ("assistantAcceptsText", Role.Assistant, Chunk(textBlock)),
    ("assistantAcceptsHint", Role.Assistant, Chunk(hint)),
    ("assistantAcceptsThinking", Role.Assistant, Chunk(thinking)),
    ("assistantAcceptsData", Role.Assistant, Chunk(data)),
    ("assistantAcceptsToolUse", Role.Assistant, Chunk(call)),
    ("assistantAcceptsMixedReasoningTurn", Role.Assistant, Chunk(thinking, textBlock, call)),
    ("assistantAcceptsToolResult", Role.Assistant, Chunk(result)),
    ("systemAcceptsText", Role.System, Chunk(textBlock)),
    ("toolAcceptsToolResult", Role.Tool, Chunk(result)),
    ("toolAcceptsText", Role.Tool, Chunk(textBlock)),
    ("toolAcceptsToolUse", Role.Tool, Chunk(call)),
    ("toolAcceptsData", Role.Tool, Chunk(data))
  )

  private val rejected: Chunk[(String, Role, Chunk[ContentBlock])] = Chunk(
    ("userRejectsHint", Role.User, Chunk(hint)),
    ("userRejectsThinking", Role.User, Chunk(thinking)),
    ("userRejectsToolUse", Role.User, Chunk(call)),
    ("userRejectsToolResult", Role.User, Chunk(result)),
    ("systemRejectsData", Role.System, Chunk(data)),
    ("systemRejectsImage", Role.System, Chunk(image)),
    ("systemRejectsHint", Role.System, Chunk(hint)),
    ("systemRejectsThinking", Role.System, Chunk(thinking)),
    ("systemRejectsToolUse", Role.System, Chunk(call))
  )

  def spec: Spec[TestEnvironment, Any] = suite("RoleContentValidationSpec")(
    (accepted.map { case (name, role, content) =>
      test(name) {
        val created = Msg.create(role, content)
        val decoded = decode(role, content)
        assertTrue(created.isRight, decoded == created.left.map(_.message))
      }
    } ++ rejected.map { case (name, role, content) =>
      test(name) {
        assertTrue(
          Msg.create(role, content).isLeft,
          decode(role, content).isLeft
        )
      }
    } ++ Chunk(
      test("emptyContentAlwaysValid") {
        assertTrue(Chunk.fromArray(Role.values).forall(role => Msg.create(role).isRight))
      },
      test("nullContentNormalised") {
        assertTrue(Chunk.fromArray(Role.values).forall { role =>
          Json
            .Obj("role" -> Json.Str(role.toString), "content" -> Json.Null)
            .as[Msg]
            .exists(message => Msg.create(role).contains(message))
        })
      },
      test("singleInvalidBlockRejectsMessage") {
        val content = Chunk(textBlock, thinking)
        assertTrue(
          Msg.create(Role.User, content).isLeft,
          decode(Role.User, content).isLeft
        )
      },
      test("deserializationRunsValidator") {
        val invalid =
          """{"role":"system","content":[{"type":"tool_use","id":"c","name":"tool","input":{}}]}"""
        assertTrue(invalid.fromJson[Msg].isLeft)
      },
      test("user rejects custom blocks outside the permitted content types") {
        val content = Chunk(ContentBlock.Custom("extension", Json.Obj()))
        assertTrue(
          Msg.create(Role.User, content).isLeft,
          decode(Role.User, content).isLeft
        )
      }
    ))*
  )
