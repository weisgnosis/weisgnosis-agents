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
package com.weisgnosis.agents.harness.memory

import com.weisgnosis.agents.core.agent.*
import com.weisgnosis.agents.core.client.{LlmClient, TurnOutput}
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.event.AgentEvent
import com.weisgnosis.agents.core.model.Msg
import zio.*
import zio.test.*

object MemoryFlushSchedulerLifecycleSpec extends ZIOSpecDefault:
  private val client: LlmClient =
    LlmClient.mockFunction((_, _) => TurnOutput.FinalText(Msg.assistant("done")))

  private def scheduler(
      queue: MemoryTaskQueue,
      gate: MemoryTriggerGate,
      extractor: MemoryExtractor
  ): UIO[MemoryFlushScheduler] =
    for
      repository <- MemoryRepository.inMemory()
      manager    <- MemoryFlushManager.make(repository.daily, extractor)
      instance   <- MemoryFlushScheduler.make(queue, gate, MemoryScope.Global, manager)
    yield instance

  def spec: Spec[TestEnvironment & Scope, Any] = suite("MemoryFlushSchedulerLifecycleSpec")(
    test("a blocked trigger runs in the owned worker without delaying agent completion") {
      for
        queue   <- MemoryTaskQueue.open()
        entered <- Promise.make[Nothing, Unit]
        release <- Promise.make[Nothing, Unit]
        calls   <- Ref.make(0)
        gate = new MemoryTriggerGate:
          def claim(
              operation: MemoryOperation,
              scope: MemoryScope
          ): IO[AgentError, MemoryTriggerDecision] =
            entered.succeed(()) *> release.await.as(MemoryTriggerDecision.Disabled)
          def cleanup(retention: MemoryInterval): IO[AgentError, Int] = ZIO.succeed(0)
        instance <- scheduler(queue, gate, MemoryExtractor(_ => calls.update(_ + 1).as("NO_REPLY")))
        tickets  <- Ref.make(Chunk.empty[MemoryTaskTicket])
        agent <- ReActAgent.make(
          client = client,
          completionObserver = instance.completionObserver(ticket => tickets.update(_ :+ ticket))
        )
        result     <- ZIO.scoped(agent.run(Msg.user("fact")))
        _          <- entered.await
        registered <- tickets.get
        _          <- release.succeed(())
        outcomes   <- ZIO.foreach(registered)(_.await)
        count      <- calls.get
      yield assertTrue(
        result.reply.text == "done",
        registered.size == 1,
        outcomes.forall(_ == MemoryTaskOutcome.Completed(Exit.unit)),
        count == 0
      )
    },
    test(
      "background gate failures remain in host-retained tickets after invocation scope closure"
    ) {
      val failure = AgentError.MemoryStorageError("gate offline")
      for
        queue <- MemoryTaskQueue.open()
        gate = new MemoryTriggerGate:
          def claim(
              operation: MemoryOperation,
              scope: MemoryScope
          ): IO[AgentError, MemoryTriggerDecision] = ZIO.fail(failure)
          def cleanup(retention: MemoryInterval): IO[AgentError, Int] = ZIO.succeed(0)
        calls    <- Ref.make(0)
        instance <- scheduler(queue, gate, MemoryExtractor(_ => calls.update(_ + 1).as("NO_REPLY")))
        tickets  <- Ref.make(Chunk.empty[MemoryTaskTicket])
        agent <- ReActAgent.make(
          client = client,
          completionObserver = instance.completionObserver(ticket => tickets.update(_ :+ ticket))
        )
        result     <- ZIO.scoped(agent.run(Msg.user("fact")))
        registered <- tickets.get
        outcomes   <- ZIO.foreach(registered)(_.await)
        count      <- calls.get
      yield assertTrue(
        result.reply.text == "done",
        count == 0,
        outcomes == Chunk(MemoryTaskOutcome.Completed(Exit.fail(failure)))
      )
    },
    test("failed and externally cancelled executions do not schedule memory work") {
      for
        queue    <- MemoryTaskQueue.open()
        gate     <- MemoryTriggerGate.inMemory()
        calls    <- Ref.make(0)
        instance <- scheduler(queue, gate, MemoryExtractor(_ => calls.update(_ + 1).as("NO_REPLY")))
        tickets  <- Ref.make(Chunk.empty[MemoryTaskTicket])
        entered  <- Promise.make[Nothing, Unit]
        observer = instance.completionObserver(ticket => tickets.update(_ :+ ticket))
        failing <- ReActAgent.make(
          client =
            LlmClient.mockEffectful((_, _, _) => ZIO.fail(AgentError.ModelError(503, "offline"))),
          completionObserver = observer
        )
        waiting <- ReActAgent.make(
          client = LlmClient.mockEffectful((_, _, _) => entered.succeed(()) *> ZIO.never),
          completionObserver = observer
        )
        failed     <- failing.run(Msg.user("failed turn")).exit
        fiber      <- waiting.run(Msg.user("cancelled turn")).forkScoped
        _          <- entered.await
        cancelled  <- fiber.interrupt
        _          <- queue.awaitIdle
        registered <- tickets.get
        count      <- calls.get
      yield assertTrue(failed.isFailure, cancelled.isInterrupted, registered.isEmpty, count == 0)
    },
    test("successful cooperative recovery outcomes intentionally remain scheduling candidates") {
      val result = AgentResult(
        Msg.assistant("recovered"),
        Chunk(Msg.user("fact"), Msg.assistant("recovered")),
        1,
        AgentRunState.Interrupted
      )
      for
        queue    <- MemoryTaskQueue.open()
        gate     <- MemoryTriggerGate.inMemory()
        calls    <- Ref.make(0)
        instance <- scheduler(queue, gate, MemoryExtractor(_ => calls.update(_ + 1).as("NO_REPLY")))
        tickets  <- Ref.make(Chunk.empty[MemoryTaskTicket])
        _ <- ZIO.scoped(
          instance
            .completionObserver(ticket => tickets.update(_ :+ ticket))
            .observe(TurnCompletion("agent", result.history, Exit.succeed(result)))
        )
        registered <- tickets.get
        outcomes   <- ZIO.foreach(registered)(_.await)
        count      <- calls.get
      yield assertTrue(
        count == 1,
        registered.size == 1,
        outcomes.forall(_ == MemoryTaskOutcome.Completed(Exit.unit))
      )
    },
    test("registration failure suppresses the result event but does not roll back admitted work") {
      val failure = AgentError.CompletionObservationError("host registration failed")
      for
        queue   <- MemoryTaskQueue.open()
        gate    <- MemoryTriggerGate.inMemory()
        entered <- Promise.make[Nothing, Unit]
        release <- Promise.make[Nothing, Unit]
        instance <- scheduler(
          queue,
          gate,
          MemoryExtractor(_ => entered.succeed(()) *> release.await.as("- committed fact"))
        )
        tickets <- Ref.make(Chunk.empty[MemoryTaskTicket])
        events  <- Ref.make(Chunk.empty[AgentEvent])
        agent <- ReActAgent.make(
          client = client,
          completionObserver =
            instance.completionObserver(ticket => tickets.update(_ :+ ticket) *> ZIO.fail(failure))
        )
        exit <- agent
          .streamEvents(Msg.user("fact"))
          .tap(event => events.update(_ :+ event))
          .runDrain
          .exit
        _          <- entered.await
        _          <- release.succeed(())
        registered <- tickets.get
        outcomes   <- ZIO.foreach(registered)(_.await)
        seen       <- events.get
      yield assertTrue(
        exit.causeOption.exists(_.failures.contains(failure)),
        !seen.exists(_.isInstanceOf[AgentEvent.AgentResultEvent]),
        registered.size == 1,
        outcomes.forall(_ == MemoryTaskOutcome.Completed(Exit.unit))
      )
    },
    test(
      "closed queue rejection enters the completion error channel without discarding canonical history"
    ) {
      for
        queue    <- ZIO.scoped(MemoryTaskQueue.open())
        gate     <- MemoryTriggerGate.inMemory()
        instance <- scheduler(queue, gate, MemoryExtractor(_ => ZIO.succeed("NO_REPLY")))
        tickets  <- Ref.make(Chunk.empty[MemoryTaskTicket])
        agent <- ReActAgent.make(
          client = client,
          completionObserver = instance.completionObserver(ticket => tickets.update(_ :+ ticket))
        )
        exit       <- agent.run(Msg.user("fact")).exit
        history    <- agent.history
        registered <- tickets.get
      yield assertTrue(
        exit.causeOption.exists(
          _.failures.exists(_.isInstanceOf[AgentError.CompletionObservationError])
        ),
        history.lastOption.exists(_.text == "done"),
        registered.isEmpty
      )
    },
    test("capacity rejection cannot silently drop a completed conversation") {
      for
        queue    <- MemoryTaskQueue.open(1)
        gate     <- MemoryTriggerGate.inMemory()
        instance <- scheduler(queue, gate, MemoryExtractor(_ => ZIO.succeed("NO_REPLY")))
        running  <- MemoryConversationToken.make
        pending  <- MemoryConversationToken.make
        entered  <- Promise.make[Nothing, Unit]
        release  <- Promise.make[Nothing, Unit]
        _        <- queue.submit(running)(entered.succeed(()) *> release.await)
        _        <- entered.await
        _        <- queue.submit(pending)(ZIO.unit)
        tickets  <- Ref.make(Chunk.empty[MemoryTaskTicket])
        agent <- ReActAgent.make(
          client = client,
          completionObserver = instance.completionObserver(ticket => tickets.update(_ :+ ticket))
        )
        exit       <- agent.run(Msg.user("fact")).exit
        _          <- release.succeed(())
        _          <- queue.awaitIdle
        registered <- tickets.get
      yield assertTrue(
        exit.causeOption.exists(
          _.failures.exists(error =>
            error.isInstanceOf[AgentError.CompletionObservationError] && error.message.contains(
              "Capacity"
            )
          )
        ),
        registered.isEmpty
      )
    }
  )
