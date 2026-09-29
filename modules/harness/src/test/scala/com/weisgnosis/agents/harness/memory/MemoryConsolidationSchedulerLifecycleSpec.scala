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

import com.weisgnosis.agents.core.agent.ReActAgent
import com.weisgnosis.agents.core.client.{LlmClient, TurnOutput}
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.event.AgentEvent
import com.weisgnosis.agents.core.model.Msg
import java.time.LocalDate
import zio.*
import zio.test.*

object MemoryConsolidationSchedulerLifecycleSpec extends ZIOSpecDefault:
  private val day: LocalDate = LocalDate.of(2026, 9, 29)
  private val client: LlmClient =
    LlmClient.mockFunction((_, _) => TurnOutput.FinalText(Msg.assistant("done")))

  private def accepted(submission: UIO[MemoryTaskSubmission]): IO[AgentError, MemoryTaskTicket] =
    submission.flatMap:
      case MemoryTaskSubmission.Accepted(ticket) => ZIO.succeed(ticket)
      case MemoryTaskSubmission.Rejected(reason) =>
        ZIO.fail(AgentError.InvalidConfiguration(s"Unexpected rejection: $reason"))

  private def scheduler(
      queue: MemoryTaskQueue,
      gate: MemoryTriggerGate,
      repository: MemoryRepository,
      report: ConsolidationResult => IO[AgentError, Unit]
  ): IO[AgentError, MemoryConsolidationScheduler] =
    MemoryConsolidator
      .make(repository.consolidation, MemoryExtractor(_ => ZIO.succeed("curated")))
      .flatMap(MemoryConsolidationScheduler.make(queue, gate, MemoryScope.Global, _, report))

  def spec: Spec[TestEnvironment & Scope, Any] = suite("MemoryConsolidationSchedulerLifecycleSpec")(
    test("closing the owner interrupts reporting after commit and waits for its cleanup") {
      for
        repository <- MemoryRepository.inMemory()
        _          <- repository.daily.appendDaily(day, "fact")
        owner      <- ZIO.acquireRelease(Scope.make)(_.close(Exit.unit))
        queue      <- owner.extend(MemoryTaskQueue.open())
        gate       <- MemoryTriggerGate.inMemory()
        entered    <- Promise.make[Nothing, Unit]
        cleaned    <- Ref.make(false)
        instance <- scheduler(
          queue,
          gate,
          repository,
          _ => (entered.succeed(()) *> ZIO.never).ensuring(cleaned.set(true))
        )
        ticket  <- accepted(instance.submit)
        _       <- entered.await
        _       <- owner.close(Exit.unit)
        outcome <- ticket.await
        closed  <- cleaned.get
        curated <- repository.daily.readCurated
        pending <- repository.consolidation.snapshot
      yield assertTrue(
        closed,
        curated.contains("curated"),
        pending.pending.isEmpty,
        outcome match
          case MemoryTaskOutcome.Completed(exit) => exit.isInterrupted
          case _                                 => false
      )
    },
    test("a no-pending attempt reserves maintenance admission until the interval expires") {
      for
        repository <- MemoryRepository.inMemory()
        queue      <- MemoryTaskQueue.open()
        gate       <- MemoryTriggerGate.inMemory()
        reports    <- Ref.make(Chunk.empty[ConsolidationResult])
        instance   <- scheduler(queue, gate, repository, result => reports.update(_ :+ result))
        first      <- accepted(instance.submit).flatMap(_.await)
        _          <- repository.daily.appendDaily(day, "later fact")
        suppressed <- accepted(instance.submit).flatMap(_.await)
        before     <- repository.daily.readCurated
        _          <- TestClock.adjust(30.minutes)
        admitted   <- accepted(instance.submit).flatMap(_.await)
        after      <- repository.daily.readCurated
        results    <- reports.get
      yield assertTrue(
        List(first, suppressed, admitted).forall(_ == MemoryTaskOutcome.Completed(Exit.unit)),
        before.isEmpty,
        after.contains("curated"),
        results == Chunk(ConsolidationResult.NoPendingMemories, ConsolidationResult.Committed)
      )
    },
    test(
      "gate failures retain their cause in the ticket without consolidation or automatic retry"
    ) {
      val failure = AgentError.MemoryStorageError("gate unavailable")
      for
        repository <- MemoryRepository.inMemory()
        _          <- repository.daily.appendDaily(day, "fact")
        queue      <- MemoryTaskQueue.open()
        attempts   <- Ref.make(0)
        gate = new MemoryTriggerGate:
          def claim(
              operation: MemoryOperation,
              scope: MemoryScope
          ): IO[AgentError, MemoryTriggerDecision] =
            attempts.update(_ + 1) *> ZIO.fail(failure)
          def cleanup(retention: MemoryInterval): IO[AgentError, Int] = ZIO.succeed(0)
        reports  <- Ref.make(Chunk.empty[ConsolidationResult])
        instance <- scheduler(queue, gate, repository, result => reports.update(_ :+ result))
        ticket   <- accepted(instance.submit)
        outcome  <- ticket.await
        _        <- queue.awaitIdle
        count    <- attempts.get
        reported <- reports.get
        curated  <- repository.daily.readCurated
      yield assertTrue(
        outcome == MemoryTaskOutcome.Completed(Exit.fail(failure)),
        count == 1,
        reported.isEmpty,
        curated.isEmpty
      )
    },
    test("failed and externally cancelled agent turns never schedule consolidation") {
      for
        repository <- MemoryRepository.inMemory()
        _          <- repository.daily.appendDaily(day, "fact")
        queue      <- MemoryTaskQueue.open()
        gate       <- MemoryTriggerGate.inMemory()
        instance   <- scheduler(queue, gate, repository, _ => ZIO.unit)
        tickets    <- Ref.make(Chunk.empty[MemoryTaskTicket])
        entered    <- Promise.make[Nothing, Unit]
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
        failed     <- failing.run(Msg.user("failed")).exit
        fiber      <- waiting.run(Msg.user("cancelled")).forkScoped
        _          <- entered.await
        cancelled  <- fiber.interrupt
        _          <- queue.awaitIdle
        registered <- tickets.get
        curated    <- repository.daily.readCurated
      yield assertTrue(
        failed.isFailure,
        cancelled.isInterrupted,
        registered.isEmpty,
        curated.isEmpty
      )
    },
    test(
      "registration failure suppresses successful result publication but preserves admitted consolidation"
    ) {
      val failure = AgentError.CompletionObservationError("ticket registration failed")
      for
        repository <- MemoryRepository.inMemory()
        _          <- repository.daily.appendDaily(day, "fact")
        queue      <- MemoryTaskQueue.open()
        gate       <- MemoryTriggerGate.inMemory()
        instance   <- scheduler(queue, gate, repository, _ => ZIO.unit)
        tickets    <- Ref.make(Chunk.empty[MemoryTaskTicket])
        events     <- Ref.make(Chunk.empty[AgentEvent])
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
        registered <- tickets.get
        outcomes   <- ZIO.foreach(registered)(_.await)
        published  <- events.get
        curated    <- repository.daily.readCurated
      yield assertTrue(
        exit.causeOption.exists(_.failures.contains(failure)),
        !published.exists(_.isInstanceOf[AgentEvent.AgentResultEvent]),
        outcomes == Chunk(MemoryTaskOutcome.Completed(Exit.unit)),
        curated.contains("curated")
      )
    },
    test("closed and full queues reject completion observation without dropping canonical history") {
      for
        repository <- MemoryRepository.inMemory()
        gate       <- MemoryTriggerGate.inMemory()
        closed     <- ZIO.scoped(MemoryTaskQueue.open())
        full       <- MemoryTaskQueue.open(1)
        running    <- MemoryConversationToken.make
        pending    <- MemoryConversationToken.make
        entered    <- Promise.make[Nothing, Unit]
        release    <- Promise.make[Nothing, Unit]
        _          <- full.submit(running)(entered.succeed(()) *> release.await)
        _          <- entered.await
        _          <- full.submit(pending)(ZIO.unit)
        outcomes <- ZIO.foreach(List(closed -> "Closed", full -> "Capacity")) {
          case (queue, reason) =>
            for
              instance <- scheduler(queue, gate, repository, _ => ZIO.unit)
              tickets  <- Ref.make(Chunk.empty[MemoryTaskTicket])
              agent <- ReActAgent.make(
                client = client,
                completionObserver =
                  instance.completionObserver(ticket => tickets.update(_ :+ ticket))
              )
              exit       <- agent.run(Msg.user("fact")).exit
              history    <- agent.history
              registered <- tickets.get
            yield exit.causeOption.exists(
              _.failures.exists(error =>
                error.isInstanceOf[AgentError.CompletionObservationError] && error.message.contains(
                  reason
                )
              )
            ) &&
              history.lastOption.exists(_.text == "done") && registered.isEmpty
        }
        _ <- release.succeed(())
      yield assertTrue(outcomes.forall(identity))
    }
  )
