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

import com.weisgnosis.agents.core.client.{LlmClient, LlmConfig, TurnOutput}
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.event.AgentEvent
import com.weisgnosis.agents.core.model.*
import com.weisgnosis.agents.core.tool.{Tool, ToolDef, Toolkit}
import zio.*
import zio.json.ast.Json
import zio.test.*

object ReActAgentLifecycleSpec extends ZIOSpecDefault:
  private def call(id: String, name: String): ContentBlock.ToolUse =
    ContentBlock.ToolUse(id, name, Json.Obj())

  def spec: Spec[TestEnvironment & Scope, Any] = suite("ReActAgent lifecycle regressions")(
    test("completed results survive cancellation and a later run resumes normally") {
      for
        started  <- Promise.make[Nothing, Unit]
        released <- Ref.make(false)
        completed = Tool.make(ToolDef("fast", "Fast", Json.Obj()))(_ => ZIO.succeed("done"))
        hanging = Tool.make(ToolDef("slow", "Slow", Json.Obj())) { _ =>
          (started.succeed(()) *> ZIO.never).ensuring(released.set(true))
        }
        model <- LlmClient.mock(
          Chunk(
            TurnOutput.ToolCalls(
              Msg(role = Role.Assistant, content = Chunk(call("a", "fast"), call("b", "slow")))
            ),
            TurnOutput.FinalText(Msg.assistant("resumed"))
          )
        )
        agent <- ReActAgent.make(
          AgentConfig(toolExecution = ToolExecution.Sequential),
          Toolkit.empty.withTool(completed).withTool(hanging),
          model
        )
        fiber       <- agent.run(Msg.user("work")).forkScoped
        _           <- started.await
        before      <- agent.history
        _           <- agent.interrupt
        interrupted <- fiber.join
        closed      <- released.get
        resumed     <- agent.run(Msg.user("continue"))
      yield assertTrue(
        before.flatMap(_.toolUses).size == 2,
        interrupted.history
          .flatMap(_.toolResults)
          .exists(r => r.id == "a" && r.state == ToolResultState.Success && r.text == "done"),
        interrupted.history
          .flatMap(_.toolResults)
          .exists(r => r.id == "b" && r.state == ToolResultState.Error),
        interrupted.history.count(_.generateReason.contains("interrupted")) == 1,
        closed,
        resumed.reply.text == "resumed",
        !resumed.isInterrupted
      )
    },
    test("cancellation during the second model turn remains interruptible") {
      for
        requested <- Promise.make[Nothing, Unit]
        model = LlmClient.mockEffectful { (history, _, _) =>
          if history.exists(_.role == Role.Tool) then requested.succeed(()) *> ZIO.never
          else
            ZIO.succeed(
              TurnOutput.ToolCalls(
                Msg(role = Role.Assistant, content = Chunk(call("a", "missing")))
              )
            )
        }
        agent   <- ReActAgent.make(client = model)
        fiber   <- agent.run(Msg.user("work")).forkScoped
        _       <- requested.await
        _       <- fiber.interrupt
        history <- agent.history
      yield assertTrue(
        history.count(_.generateReason.contains("interrupted")) == 1,
        history.flatMap(_.toolResults).size == 1
      )
    },
    test("timeout cancels active work using the test clock") {
      for
        started <- Promise.make[Nothing, Unit]
        model = LlmClient.mockEffectful((_, _, _) => started.succeed(()) *> ZIO.never)
        agent   <- ReActAgent.make(AgentConfig(timeout = 5.seconds), client = model)
        fiber   <- agent.run(Msg.user("wait")).either.forkScoped
        _       <- started.await
        _       <- TestClock.adjust(5.seconds)
        result  <- fiber.join
        history <- agent.history
      yield assertTrue(
        result.left.exists(_.isInstanceOf[AgentError.TimeoutError]),
        history.count(_.generateReason.contains("interrupted")) == 1
      )
    },
    test("event streams propagate model failures") {
      val error = AgentError.ModelError(503, "unavailable")
      for
        agent  <- ReActAgent.make(client = LlmClient.mockEffectful((_, _, _) => ZIO.fail(error)))
        result <- agent.streamEvents(Msg.user("hello")).runCollect.either
      yield assertTrue(result == Left(error))
    },
    test("native mode passes response format and decodes the model response") {
      val model = new LlmClient:
        def streamTurn(
            history: Chunk[Msg],
            tools: Chunk[ToolDef],
            config: LlmConfig,
            eventHub: Hub[AgentEvent]
        ): ZIO[Scope, AgentError, TurnOutput] =
          if config.responseFormat.isDefined && tools.isEmpty then
            ZIO.succeed(TurnOutput.FinalText(Msg.assistant("42")))
          else ZIO.fail(AgentError.ModelError(400, "missing response format"))
      for
        agent <- ReActAgent.make(client = model)
        result <- agent.runStructured[Int](
          Chunk(Msg.user("number")),
          Json.Obj("type" -> Json.Str("integer")),
          StructuredMode.Native
        )
      yield assertTrue(result._2 == 42, result._1.reply.getStructuredData[Int] == Right(42))
    },
    test("auto mode falls back on unsupported native formatting without duplicating input") {
      val model = new LlmClient:
        def streamTurn(
            history: Chunk[Msg],
            tools: Chunk[ToolDef],
            config: LlmConfig,
            eventHub: Hub[AgentEvent]
        ): ZIO[Scope, AgentError, TurnOutput] =
          if config.responseFormat.isDefined then
            ZIO.fail(AgentError.ModelError(400, "unsupported response format"))
          else if history.exists(_.role == Role.Tool) then
            ZIO.succeed(TurnOutput.FinalText(Msg.assistant("done")))
          else
            ZIO.succeed(
              TurnOutput.ToolCalls(
                Msg(
                  role = Role.Assistant,
                  content = Chunk(
                    ContentBlock.ToolUse(
                      "response",
                      StructuredOutput.ToolName,
                      Json.Obj("response" -> Json.Num(42))
                    )
                  )
                )
              )
            )
      for
        agent <- ReActAgent.make(client = model)
        result <- agent
          .runStructured[Int](Msg.user("number"), Json.Obj("type" -> Json.Str("integer")))
      yield assertTrue(result._2 == 42, result._1.history.count(_.role == Role.User) == 1)
    }
  ) @@ TestAspect.timeout(10.seconds)
