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
package com.weisgnosis.agents.harness.plan

import com.weisgnosis.agents.core.event.ModelStreamContext
import com.weisgnosis.agents.core.model.*
import zio.*
import zio.json.ast.Json
import zio.test.*

object PlanInputTransformSpec extends ZIOSpecDefault:
  private val context: ModelStreamContext = ModelStreamContext("reply", "request")

  def spec: Spec[TestEnvironment & Scope, Any] = suite("PlanInputTransformSpec")(
    test(
      "construction and effect assembly do not read state and fresh Build returns exact history"
    ) {
      val history = Chunk(Msg.system("system"), Msg.user("question"))
      for
        calls <- Ref.make(0)
        transform = PlanInputTransform.make(calls.update(_ + 1).as(PlanSessionSnapshot.Build(None)))
        effect    = transform(context, history)
        before <- calls.get
        view   <- effect
        after  <- calls.get
      yield assertTrue(before == 0, after == 1, view == history)
    },
    test(
      "coalescing into a final User preserves all original fields and does not mark original content synthetic"
    ) {
      val user = Msg
        .user("question")
        .withId(Some("user-id"))
        .withName("human")
        .withTimestamp(Some("timestamp"))
        .withMetadata("keep", Json.Str("original"))
      val history = Chunk(Msg.system("system"), user)
      for view <- PlanInputTransform
          .make(ZIO.succeed(PlanSessionSnapshot.Planning(None)))(context, history)
      yield assertTrue(
        view.size == 2,
        view.dropRight(1) == history.dropRight(1),
        view.lastOption.exists(message =>
          message.withContent(user.content) == Right(user) && message.content.take(
            user.content.size
          ) == user.content &&
            message.text.contains("PLAN MODE is active") && !message.metadata.contains(
              "agentscope_synthetic"
            )
        )
      )
    },
    test("complete tool pairs remain unchanged when a standalone synthetic reminder follows them") {
      val call = ContentBlock.ToolUse("call", "read", Json.Obj())
      val history = Chunk(
        Msg.system("system"),
        Msg.user("q"),
        Msg.assistant(Chunk(call)),
        Msg.tool("call", "read", "result")
      )
      for view <- PlanInputTransform
          .make(ZIO.succeed(PlanSessionSnapshot.Planning(None)))(context, history)
      yield assertTrue(
        view.take(history.size) == history,
        view.size == history.size + 1,
        view.lastOption.exists(message =>
          message.role == Role.User && message.name.contains("system") &&
            message.metadata.get("agentscope_synthetic").contains(Json.Bool(true)) &&
            message.metadata.get("agentscope_reminder_kind").contains(Json.Str("plan_context"))
        )
      )
    },
    test(
      "empty history gets one reminder and repeated independent requests do not accumulate projections"
    ) {
      val transform = PlanInputTransform.make(ZIO.succeed(PlanSessionSnapshot.Planning(None)))
      for
        first  <- transform(context, Chunk.empty)
        second <- transform(context, Chunk.empty)
      yield assertTrue(first == second, first.size == 1, first.forall(_.role == Role.User))
    },
    test(
      "cancelling the snapshot read waits for cleanup without returning a stale or empty fallback"
    ) {
      for
        entered <- Promise.make[Nothing, Unit]
        closed  <- Ref.make(false)
        transform = PlanInputTransform.make(
          (entered.succeed(()) *> ZIO.never).ensuring(closed.set(true))
        )
        fiber   <- ZIO.scoped(transform(context, Chunk(Msg.user("q")))).forkScoped
        _       <- entered.await
        exit    <- fiber.interrupt
        cleaned <- closed.get
      yield assertTrue(exit.isInterrupted, cleaned)
    },
    test("a snapshot defect remains a defect rather than a permissive missing-plan projection") {
      val defect = new IllegalStateException("snapshot unavailable")
      for exit <- PlanInputTransform.make(ZIO.die(defect))(context, Chunk.empty).exit
      yield assertTrue(exit.causeOption.exists(_.defects.contains(defect)))
    }
  )
