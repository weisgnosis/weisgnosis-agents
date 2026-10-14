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
import com.weisgnosis.agents.core.event.{
  EventPublisher,
  AgentEvent,
  EventAdmission,
  ModelStreamContext
}
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
              Msg.assistant(content = Chunk(call("a", "fast"), call("b", "slow")), name = None)
            ),
            TurnOutput.FinalText(Msg.assistant("resumed"))
          )
        )
        agent <- ReActAgent.make(
          AgentConfig(toolExecution = ToolExecution.Sequential),
          Toolkit.single(completed).updated(hanging),
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
                Msg.assistant(content = Chunk(call("a", "missing")), name = None)
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
    test("each interrupted run appends its own recovery after the new user input") {
      for
        requested <- ZIO.acquireRelease(Queue.unbounded[Unit])(_.shutdown)
        model = LlmClient.mockEffectful((_, _, _) => requested.offer(()) *> ZIO.never)
        agent <- ReActAgent.make(client = model)
        results <- ZIO.foreach(Chunk("first request", "second request")) { input =>
          for
            fiber  <- agent.run(Msg.user(input)).forkScoped
            _      <- requested.take
            _      <- agent.interrupt
            result <- fiber.join
          yield result
        }
        history <- agent.history
      yield assertTrue(
        results.forall(_.isInterrupted),
        history.map(_.role) == Chunk(Role.User, Role.Assistant, Role.User, Role.Assistant),
        history.count(_.generateReason.contains("interrupted")) == 2,
        history.lastOption.exists(_.generateReason.contains("interrupted"))
      )
    },
    test("empty-input resumption after interruption appends a recovery for the new run") {
      for
        requested <- ZIO.acquireRelease(Queue.unbounded[Unit])(_.shutdown)
        model = LlmClient.mockEffectful((_, _, _) => requested.offer(()) *> ZIO.never)
        agent <- ReActAgent.make(client = model)
        results <- ZIO.foreach(Chunk(Chunk(Msg.user("first request")), Chunk.empty[Msg])) { input =>
          for
            fiber  <- agent.run(input).forkScoped
            _      <- requested.take
            _      <- agent.interrupt
            result <- fiber.join
          yield result
        }
        history <- agent.history
      yield assertTrue(
        results.forall(_.isInterrupted),
        history.map(_.role) == Chunk(Role.User, Role.Assistant, Role.Assistant),
        history.count(_.generateReason.contains("interrupted")) == 2
      )
    },
    test("each model request releases resources and readers before the next request starts") {
      for
        activeReaders     <- Ref.make(0)
        stoppedReaders    <- Ref.make(0)
        resourceCount     <- Ref.make(0)
        resourcesAtStart  <- Ref.make(Chunk.empty[Int])
        observedPublisher <- Ref.make[Option[EventPublisher]](None)
        model = LlmClient.mockEffectful { (history, _, hub) =>
          for
            previous <- resourceCount.get
            _        <- resourcesAtStart.update(_ :+ previous)
            _        <- observedPublisher.set(Some(hub))
            _ <- ZIO.acquireRelease(resourceCount.update(_ + 1))(_ => resourceCount.update(_ - 1))
            readerStarted <- Promise.make[Nothing, Unit]
            _ <- (activeReaders.update(_ + 1) *> readerStarted.succeed(()) *> ZIO.never)
              .ensuring(activeReaders.update(_ - 1) *> stoppedReaders.update(_ + 1))
              .forkScoped
            _ <- readerStarted.await
          yield
            if history.exists(_.role == Role.Tool) then TurnOutput.FinalText(Msg.assistant("done"))
            else TurnOutput.ToolCalls(Msg.assistant(Chunk(call("missing", "missing"))))
        }
        agent     <- ReActAgent.make(client = model)
        result    <- agent.run(Msg.user("work"))
        active    <- activeReaders.get
        stopped   <- stoppedReaders.get
        resources <- resourceCount.get
        previous  <- resourcesAtStart.get
        publisher <- observedPublisher.get
        late      <- ZIO.foreach(publisher)(_.publish(AgentEvent.AgentEndEvent("probe")))
      yield assertTrue(
        result.reply.text == "done",
        active == 0,
        stopped == 2,
        resources == 0,
        previous == Chunk(0, 0),
        late.contains(EventAdmission.Closed)
      )
    },
    test("fiber cancellation releases model resources and scoped readers before returning") {
      for
        requested        <- Promise.make[Nothing, Unit]
        readerStarted    <- Promise.make[Nothing, Unit]
        readerStopped    <- Ref.make(false)
        resourceReleased <- Ref.make(false)
        model = LlmClient.mockEffectful { (_, _, _) =>
          for
            _ <- ZIO.acquireRelease(ZIO.unit)(_ => resourceReleased.set(true))
            _ <- (readerStarted.succeed(()) *> ZIO.never)
              .ensuring(readerStopped.set(true))
              .forkScoped
            _      <- readerStarted.await
            _      <- requested.succeed(())
            result <- ZIO.never
          yield result
        }
        agent    <- ReActAgent.make(client = model)
        fiber    <- agent.run(Msg.user("work")).forkScoped
        _        <- requested.await
        _        <- fiber.interrupt
        stopped  <- readerStopped.get
        released <- resourceReleased.get
      yield assertTrue(stopped, released)
    },
    test("timeout cancels active work using the test clock") {
      for
        started  <- Promise.make[Nothing, Unit]
        released <- Ref.make(false)
        model = LlmClient.mockEffectful((_, _, _) =>
          ZIO.acquireRelease(ZIO.unit)(_ => released.set(true)) *> started.succeed(()) *> ZIO.never
        )
        agent            <- ReActAgent.make(AgentConfig(timeout = 5.seconds), client = model)
        fiber            <- agent.run(Msg.user("wait")).either.forkScoped
        _                <- started.await
        _                <- TestClock.adjust(5.seconds)
        result           <- fiber.join
        history          <- agent.history
        resourceReleased <- released.get
      yield assertTrue(
        result.left.exists(_.isInstanceOf[AgentError.TimeoutError]),
        history.count(_.generateReason.contains("interrupted")) == 1,
        resourceReleased
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
            eventHub: EventPublisher,
            context: ModelStreamContext
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
            eventHub: EventPublisher,
            context: ModelStreamContext
        ): ZIO[Scope, AgentError, TurnOutput] =
          if config.responseFormat.isDefined then
            ZIO.fail(AgentError.ModelError(400, "unsupported response format"))
          else if history.exists(_.role == Role.Tool) then
            ZIO.succeed(TurnOutput.FinalText(Msg.assistant("done")))
          else
            ZIO.succeed(
              TurnOutput.ToolCalls(
                Msg.assistant(
                  content = Chunk(
                    ContentBlock.ToolUse(
                      "response",
                      StructuredOutput.ToolName,
                      Json.Obj("response" -> Json.Num(42))
                    )
                  ),
                  name = None
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
