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
import scala.compiletime.testing.typeCheckErrors
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

/** Construction routes must not bypass role/content validation. */
object MsgConstructionSpec extends ZIOSpecDefault:
  def spec: Spec[TestEnvironment, Any] = suite("MsgConstructionSpec")(
    test(
      "unchecked construction, copying, product reconstruction, and forbidden user content do not compile"
    ) {
      assertTrue(
        typeCheckErrors(
          """new com.weisgnosis.agents.core.model.Msg(None, None, com.weisgnosis.agents.core.model.Role.System, zio.Chunk(com.weisgnosis.agents.core.model.ContentBlock.Thinking("hidden")), Map.empty, None, None)"""
        ).nonEmpty,
        typeCheckErrors(
          """com.weisgnosis.agents.core.model.Msg(role = com.weisgnosis.agents.core.model.Role.System, content = zio.Chunk(com.weisgnosis.agents.core.model.ContentBlock.Thinking("hidden")))"""
        ).nonEmpty,
        typeCheckErrors(
          """com.weisgnosis.agents.core.model.Msg.assistant("text").copy(role = com.weisgnosis.agents.core.model.Role.System, content = zio.Chunk(com.weisgnosis.agents.core.model.ContentBlock.Thinking("hidden")))"""
        ).nonEmpty,
        typeCheckErrors(
          """summon[scala.deriving.Mirror.ProductOf[com.weisgnosis.agents.core.model.Msg]]"""
        ).nonEmpty,
        typeCheckErrors(
          """com.weisgnosis.agents.core.model.Msg.user(zio.Chunk(com.weisgnosis.agents.core.model.ContentBlock.Thinking("hidden")))"""
        ).nonEmpty,
        typeCheckErrors(
          """com.weisgnosis.agents.core.model.Msg.system(zio.Chunk(com.weisgnosis.agents.core.model.ContentBlock.Image.url("https://example.com/image")))"""
        ).nonEmpty
      )
    },
    test("mixed user content and empty messages have safe typed factories") {
      val blocks: Chunk[Msg.UserBlock] = Chunk(
        ContentBlock.Text("caption"),
        ContentBlock.Data.url("https://example.com/data"),
        ContentBlock.Image.url("https://example.com/image"),
        ContentBlock.Audio.url("https://example.com/audio"),
        ContentBlock.Video.url("https://example.com/video")
      )
      val message                      = Msg.user(blocks)
      val widened: Chunk[ContentBlock] = blocks
      assertTrue(
        message.role == Role.User,
        message.content == widened,
        message.toJson.fromJson[Msg].contains(message),
        Msg.create(Role.User, widened, name = Some("user")).contains(message),
        Chunk(Msg.user(), Msg.assistant(), Msg.system(), Msg.tool()).forall(_.content.isEmpty)
      )
    },
    test("dynamic content and role changes validate before returning a message") {
      val user = Msg.user("text").withId(Some("id")).withMetadata("trace", Json.Str("kept"))
      val invalidContent = user.withContent(Chunk(ContentBlock.Thinking("forbidden")))
      val invalidRole = Msg.assistant(Chunk(ContentBlock.Thinking("thought"))).withRole(Role.System)
      val valid       = user.withContent(Chunk(ContentBlock.Text("updated")))
      assertTrue(
        invalidContent.left.exists(_.isInstanceOf[AgentError.InvalidMessage]),
        invalidRole.left.exists(_.isInstanceOf[AgentError.InvalidMessage]),
        valid.exists(value =>
          value.id == user.id && value.name == user.name && value.metadata == user.metadata && value.role == Role.User && value.text == "updated"
        ),
        user.text == "text",
        user.withRole(Role.System).exists(_.content == user.content)
      )
    },
    test("InvalidMessage remains a typed serializable domain failure") {
      val failure = AgentError.InvalidMessage("invalid role content")
      assertTrue(
        failure.toJson.fromJson[AgentError.InvalidMessage].contains(failure),
        failure.getMessage == failure.message
      )
    },
    test("structural equality covers all stored fields and does not use derived usage") {
      val usage    = ChatUsage(inputTokens = 3, outputTokens = 5)
      val original = Msg.assistant("value").withUsage(usage)
      val equal    = Msg.assistant("value").withUsage(usage)
      val legacy = original
        .withUsage(None)
        .withMetadata(
          Msg.MetadataChatUsage,
          Json.Obj("inputTokens" -> Json.Num(3), "outputTokens" -> Json.Num(5))
        )
      val changed = Chunk(
        original.withId(Some("id")),
        original.withName(None),
        original.withTextContent("other"),
        original.withMetadata("trace", Json.Str("x")),
        original.withTimestamp(Some("time")),
        original.withUsage(None)
      )
      assertTrue(
        original == equal,
        original.hashCode == equal.hashCode,
        Set(original, equal).size == 1,
        changed.forall(_ != original),
        original.withRole(Role.Tool).exists(_ != original),
        legacy.chatUsage == original.chatUsage,
        legacy != original,
        !original.equals("unrelated")
      )
    },
    test("filtering and tool-specific transformations preserve surrounding content and metadata") {
      val call   = ContentBlock.ToolUse("call", "search", Json.Obj())
      val result = ContentBlock.ToolResult.text("call", "search", "before")
      val text   = ContentBlock.Text("kept")
      val original =
        Msg.assistant(Chunk(text, call, result)).withMetadata("trace", Json.Str("kept"))
      val changed = original
        .mapToolUses(_.copy(input = Json.Obj("query" -> Json.Str("new"))))
        .mapToolResults(_.copy(output = Chunk(ContentBlock.Text("after"))))
      val filtered = changed.filterContent(block => !block.isInstanceOf[ContentBlock.ToolUse])
      assertTrue(
        original.content == Chunk(text, call, result),
        changed.content.headOption.contains(text),
        changed.toolUses.headOption.exists(_.input == Json.Obj("query" -> Json.Str("new"))),
        changed.toolResults.headOption.exists(_.text == "after"),
        changed.metadata == original.metadata,
        filtered.toolUses.isEmpty,
        filtered.toolResults == changed.toolResults,
        filtered.toJson.fromJson[Msg].contains(filtered)
      )
    },
    test(
      "effectful tool mapping is lazy, ordered, and preserves its environment and typed failures"
    ) {
      val first   = ContentBlock.ToolResult.text("first", "tool", "a")
      val second  = ContentBlock.ToolResult.text("second", "tool", "b")
      val message = Msg.tool(Chunk(first, ContentBlock.Text("middle"), second))
      val failure = AgentError.ToolExecutionError("tool", "mapping failed")
      def record(
          result: ContentBlock.ToolResult
      ): ZIO[Ref[Chunk[String]], AgentError, ContentBlock.ToolResult] =
        ZIO
          .serviceWithZIO[Ref[Chunk[String]]](_.update(_ :+ result.id))
          .as(result.copy(output = Chunk(ContentBlock.Text("mapped"))))
      for
        seen <- Ref.make(Chunk.empty[String])
        effect = message.mapToolResultsZIO(record)
        before  <- seen.get
        updated <- effect.provideEnvironment(ZEnvironment(seen))
        after   <- seen.get
        untouched <- Msg
          .user("text")
          .mapToolResultsZIO(record)
          .provideEnvironment(ZEnvironment(seen))
        afterEmpty <- seen.get
        failed <- message
          .mapToolResultsZIO(result =>
            if result.id == "second" then ZIO.fail(failure) else ZIO.succeed(result)
          )
          .either
      yield assertTrue(
        before.isEmpty,
        after == Chunk("first", "second"),
        afterEmpty == after,
        updated.toolResults.map(_.text) == Chunk("mapped", "mapped"),
        updated.content.lift(1).contains(ContentBlock.Text("middle")),
        untouched == Msg.user("text"),
        failed == Left(failure),
        message.toolResults == Chunk(first, second)
      )
    },
    test("interrupting effectful mapping releases the active callback and skips later results") {
      val first   = ContentBlock.ToolResult.text("first", "tool", "a")
      val second  = ContentBlock.ToolResult.text("second", "tool", "b")
      val message = Msg.tool(Chunk(first, second))
      ZIO.scoped:
        for
          entered  <- Promise.make[Nothing, Unit]
          released <- Promise.make[Nothing, Unit]
          seen     <- Ref.make(Chunk.empty[String])
          fiber <- message
            .mapToolResultsZIO(result =>
              seen.update(_ :+ result.id) *> ZIO.acquireReleaseWith(entered.succeed(()))(_ =>
                released.succeed(())
              )(_ => ZIO.never)
            )
            .forkScoped
          _       <- entered.await
          exit    <- fiber.interrupt
          cleaned <- released.isDone
          visited <- seen.get
        yield assertTrue(
          exit.isInterrupted,
          cleaned,
          visited == Chunk("first"),
          message.toolResults == Chunk(first, second)
        )
    },
    test("derived wire encoding preserves empty-content and optional-field representation") {
      val empty =
        Msg.user(name = None).toJson.fromJson[Json].flatMap(_.asObject.toRight("Expected object"))
      val full = Msg
        .assistant("text")
        .withId(Some("id"))
        .withName(Some("agent"))
        .withTimestamp(Some("time"))
        .withMetadata("key", Json.Bool(true))
      val encoded = full.toJson.fromJson[Json].flatMap(_.asObject.toRight("Expected object"))
      assertTrue(
        empty.exists(_.fields.toMap == Map("role" -> Json.Str("user"), "content" -> Json.Arr())),
        encoded.exists(obj =>
          obj.get("id").contains(Json.Str("id")) && obj.get("name").contains(Json.Str("agent")) &&
            obj.get("timestamp").contains(Json.Str("time")) && obj
              .get("metadata")
              .contains(Json.Obj("key" -> Json.Bool(true)))
        ),
        full.toJson.fromJson[Msg].contains(full)
      )
    }
  )
