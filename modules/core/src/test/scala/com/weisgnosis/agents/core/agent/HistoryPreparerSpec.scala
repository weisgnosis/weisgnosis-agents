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
package com.weisgnosis.agents.core.agent

import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.model.*
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

object HistoryPreparerSpec extends ZIOSpecDefault:
  private val call: ContentBlock.ToolUse = ContentBlock.ToolUse("same", "read", Json.Obj())
  def spec: Spec[TestEnvironment & Scope, Any] = suite("HistoryPreparerSpec")(
    test("identity preserves permissive supplied history and callbacks remain lazy") {
      val history = Chunk(Msg.assistant(Chunk(call)))
      for
        invoked <- Ref.make(0)
        preparer = HistoryPreparer(value => invoked.update(_ + 1).as(value))
        effect   = preparer.prepare(history)
        before <- invoked.get
        result <- ZIO.scoped(effect)
        after  <- invoked.get
      yield assertTrue(
        before == 0,
        after == 1,
        HistoryPreparer.validate(history, result) == Right(history)
      )
    },
    test("composition passes committed stage values in order and stays lazy") {
      val original = Chunk(Msg.user("original"))
      for
        calls <- Ref.make(Chunk.empty[String])
        first = HistoryPreparer(history =>
          calls.update(_ :+ "first").as(history :+ Msg.assistant("prepared"))
        )
        second = HistoryPreparer(history =>
          calls
            .update(_ :+ history.lastOption.fold("missing")(_.text))
            .as(history :+ Msg.user("next"))
        )
        effect = first.andThen(second).prepare(original)
        before <- calls.get
        result <- ZIO.scoped(effect)
        after  <- calls.get
      yield assertTrue(
        before.isEmpty,
        after == Chunk("first", "prepared"),
        result.map(_.text) == Chunk("original", "prepared", "next")
      )
    },
    test("composition retains resources until the request scope closes") {
      for
        closed   <- Ref.make(false)
        observed <- Ref.make(true)
        first  = HistoryPreparer(history => ZIO.addFinalizer(closed.set(true)).as(history))
        second = HistoryPreparer(history => closed.get.flatMap(observed.set).as(history))
        _      <- ZIO.scoped(first.andThen(second).prepare(Chunk.empty))
        during <- observed.get
        after  <- closed.get
      yield assertTrue(!during, after)
    },
    test("composition preserves typed failure skips later work and releases prior resources") {
      val error = AgentError.HistoryPreparationError("second stage failed")
      for
        closed <- Ref.make(false)
        later  <- Ref.make(false)
        first  = HistoryPreparer(history => ZIO.addFinalizer(closed.set(true)).as(history))
        second = HistoryPreparer(_ => ZIO.fail(error))
        third  = HistoryPreparer(history => later.set(true).as(history))
        exit     <- ZIO.scoped(first.andThen(second).andThen(third).prepare(Chunk.empty)).either
        released <- closed.get
        invoked  <- later.get
      yield assertTrue(exit == Left(error), released, !invoked)
    },
    test("composition cancellation closes earlier resources without invoking subsequent stages") {
      for
        entered <- Promise.make[Nothing, Unit]
        closed  <- Ref.make(false)
        later   <- Ref.make(false)
        first  = HistoryPreparer(history => ZIO.addFinalizer(closed.set(true)).as(history))
        second = HistoryPreparer(_ => entered.succeed(()) *> ZIO.never)
        third  = HistoryPreparer(history => later.set(true).as(history))
        fiber    <- ZIO.scoped(first.andThen(second).andThen(third).prepare(Chunk.empty)).forkScoped
        _        <- entered.await
        exit     <- fiber.interrupt
        released <- closed.get
        invoked  <- later.get
      yield assertTrue(exit.isInterrupted, released, !invoked)
    },
    test("composition rejects invalid intermediate rewrites before invoking the next stage") {
      for
        invoked <- Ref.make(false)
        first  = HistoryPreparer(_ => ZIO.succeed(Chunk(Msg.assistant(Chunk(call)))))
        second = HistoryPreparer(_ => invoked.set(true).as(Chunk.empty))
        result <- ZIO.scoped(first.andThen(second).prepare(Chunk(Msg.user("original")))).either
        after  <- invoked.get
      yield assertTrue(result.isLeft, !after)
    },
    test("composition validates the final stage and preserves ordered system messages") {
      val original = Chunk(Msg.system("system"), Msg.user("original"))
      val second   = HistoryPreparer(_ => ZIO.succeed(Chunk(Msg.user("replacement"))))
      for result <- ZIO.scoped(HistoryPreparer.identity.andThen(second).prepare(original)).either
      yield assertTrue(result.isLeft)
    },
    test("changed histories reject unfinished calls and orphan inline or local results") {
      val original = Chunk(Msg.user("original"))
      val result   = ContentBlock.ToolResult.text("same", "read", "result")
      val candidates = Chunk(
        Chunk(Msg.assistant(Chunk(call))),
        Chunk(Msg.assistant(Chunk(result))),
        Chunk(Msg.tool(Chunk(result)))
      )
      assertTrue(candidates.forall(HistoryPreparer.validate(original, _).isLeft))
    },
    test("rewrites preserve complete repeated occurrences and provider inline pairs") {
      val server = call.withServerTool(true)
      val result = ContentBlock.ToolResult.text("same", "read", "inline")
      val candidate = Chunk(
        Msg.user("summary"),
        Msg.assistant(Chunk(call, call, server, result)),
        Msg.tool("same", "read", "one"),
        Msg.tool("same", "read", "two")
      )
      assertTrue(HistoryPreparer.validate(Chunk(Msg.user("old")), candidate) == Right(candidate))
    },
    test("system message order and complete metadata cannot change during rewriting") {
      val first    = Msg.system("first").withMetadata("audit", Json.Str("original"))
      val second   = Msg.system("second")
      val original = Chunk(first, Msg.user("old"), second)
      val valid    = Chunk(first, second, Msg.user("summary"))
      assertTrue(
        HistoryPreparer.validate(original, valid) == Right(valid),
        HistoryPreparer.validate(original, Chunk(second, first)).isLeft,
        HistoryPreparer
          .validate(original, Chunk(first.withMetadata("audit", Json.Str("changed")), second))
          .isLeft,
        HistoryPreparer.validate(original, Chunk(first)).isLeft
      )
    },
    test(
      "preparation errors round-trip and provider rejection cannot inhabit the callback channel"
    ) {
      val error = AgentError.HistoryPreparationError("failed")
      for checked <- typeCheck("""
        import com.weisgnosis.agents.core.agent.HistoryPreparer
        import com.weisgnosis.agents.core.error.AgentError
        import zio.*
        HistoryPreparer(_ => ZIO.fail(AgentError.ModelError(400, "bad")))
      """)
      yield assertTrue(
        error.toJson.fromJson[AgentError.HistoryPreparationError] == Right(error),
        checked.isLeft
      )
    }
  )
