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
import com.weisgnosis.agents.core.model.Msg
import com.weisgnosis.agents.harness.transcript.*
import java.time.{Instant, LocalDate}
import zio.*
import zio.test.*

object MemoryMaintenanceCoordinatorSchedulerSpec extends ZIOSpecDefault:
  private val now: Instant   = Instant.parse("2026-04-01T00:00:00Z")
  private val day: LocalDate = LocalDate.of(2025, 1, 1)

  private def accepted(submission: UIO[MemoryTaskSubmission]): IO[AgentError, MemoryTaskTicket] =
    submission.flatMap:
      case MemoryTaskSubmission.Accepted(ticket) => ZIO.succeed(ticket)
      case MemoryTaskSubmission.Rejected(reason) =>
        ZIO.fail(AgentError.InvalidConfiguration(s"Unexpected rejection: $reason"))

  private def coordinator(
      repository: MemoryRepository,
      operations: Chunk[IO[AgentError, TranscriptPruneResult]]
  ): IO[AgentError, MemoryMaintenanceCoordinator] =
    for
      memory <- MemoryMaintenance.make(
        repository,
        MemoryExtractor(_ => ZIO.succeed("curated")),
        policy = MemoryRetentionPolicy.KeepAll
      )
      targets <- ZIO.foreach(operations.zipWithIndex): (operation, index) =>
        ZIO
          .fromEither(TranscriptTargetId.fromString(s"target-$index"))
          .map(id => TranscriptRetentionTarget(id)(_ => operation))
      batch <- TranscriptRetentionBatch.make(targets)
    yield new MemoryMaintenanceCoordinator(memory, batch)

  def spec: Spec[TestEnvironment & Scope, Any] = suite("MemoryMaintenanceCoordinatorSchedulerSpec")(
    test(
      "agent completion coalesces pending maintenance under one gate claim and reports the complete pipeline"
    ) {
      for
        _          <- TestClock.setTime(now)
        repository <- MemoryRepository.inMemory()
        _          <- repository.daily.appendDaily(day, "fact")
        queue      <- MemoryTaskQueue.open()
        blocker    <- MemoryConversationToken.make
        entered    <- Promise.make[Nothing, Unit]
        release    <- Promise.make[Nothing, Unit]
        _          <- queue.submit(blocker)(entered.succeed(()) *> release.await)
        _          <- entered.await
        claims     <- Ref.make(Chunk.empty[MemoryOperation])
        gate = new MemoryTriggerGate:
          def claim(
              operation: MemoryOperation,
              scope: MemoryScope
          ): IO[AgentError, MemoryTriggerDecision] =
            claims.update(_ :+ operation).as(MemoryTriggerDecision.Admitted)
          def cleanup(retention: MemoryInterval): IO[AgentError, Int] = ZIO.succeed(0)
        calls <- Ref.make(0)
        service <- coordinator(
          repository,
          Chunk(calls.update(_ + 1).as(TranscriptPruneResult.Missing))
        )
        reports <- Ref.make(Chunk.empty[MaintenanceResult])
        tickets <- Ref.make(Chunk.empty[MemoryTaskTicket])
        scheduler <- MemoryMaintenanceScheduler.make(
          queue,
          gate,
          MemoryScope.Global,
          service,
          result => reports.update(_ :+ result)
        )
        agent <- ReActAgent.make(
          client = LlmClient.mockFunction((_, _) => TurnOutput.FinalText(Msg.assistant("done"))),
          completionObserver = scheduler.completionObserver(ticket => tickets.update(_ :+ ticket))
        )
        first      <- ZIO.scoped(agent.run(Msg.user("first")))
        second     <- ZIO.scoped(agent.run(Msg.user("second")))
        before     <- calls.get
        _          <- release.succeed(())
        receipts   <- tickets.get.flatMap(ZIO.foreach(_)(_.await))
        count      <- calls.get
        operations <- claims.get
        observed   <- reports.get
      yield assertTrue(
        first.reply.text == "done",
        second.reply.text == "done",
        before == 0,
        count == 1,
        operations == Chunk(MemoryOperation.Maintenance),
        receipts == Chunk(MemoryTaskOutcome.Superseded, MemoryTaskOutcome.Completed(Exit.unit)),
        observed.size == 1,
        observed.forall(result =>
          result.memory == MemoryMaintenanceResult.Committed(
            DailyArchivalResult.Kept
          ) && (result.transcripts match
            case TranscriptBatchResult.Evaluated(_, targets) =>
              targets.map(_.result) == Chunk(TranscriptPruneResult.Missing)
            case _ => false)
        )
      )
    },
    test(
      "a later target failure preserves memory and earlier target progress without reporting aggregate success"
    ) {
      val failure = AgentError.MemoryStorageError("target unavailable")
      for
        _          <- TestClock.setTime(now)
        repository <- MemoryRepository.inMemory()
        _          <- repository.daily.appendDaily(day, "fact")
        queue      <- MemoryTaskQueue.open()
        gate       <- MemoryTriggerGate.inMemory()
        progress   <- Ref.make(Chunk.empty[Int])
        service <- coordinator(
          repository,
          Chunk(
            progress.update(_ :+ 1).as(TranscriptPruneResult.Missing),
            ZIO.fail(failure),
            progress.update(_ :+ 3).as(TranscriptPruneResult.Missing)
          )
        )
        reports <- Ref.make(0)
        scheduler <- MemoryMaintenanceScheduler.make(
          queue,
          gate,
          MemoryScope.Global,
          service,
          _ => reports.update(_ + 1)
        )
        receipt  <- accepted(scheduler.submit).flatMap(_.await)
        actual   <- progress.get
        reported <- reports.get
        curated  <- repository.daily.readCurated
      yield assertTrue(
        receipt == MemoryTaskOutcome.Completed(Exit.fail(failure)),
        actual == Chunk(1),
        reported == 0,
        curated.contains("curated")
      )
    },
    test("aggregate report failure leaves all completed pipeline effects visible") {
      val failure = AgentError.MemoryStorageError("report unavailable")
      for
        _          <- TestClock.setTime(now)
        repository <- MemoryRepository.inMemory()
        _          <- repository.daily.appendDaily(day, "fact")
        queue      <- MemoryTaskQueue.open()
        gate       <- MemoryTriggerGate.inMemory()
        pruned     <- Ref.make(false)
        service <- coordinator(
          repository,
          Chunk(pruned.set(true).as(TranscriptPruneResult.Missing))
        )
        scheduler <- MemoryMaintenanceScheduler.make(
          queue,
          gate,
          MemoryScope.Global,
          service,
          _ => ZIO.fail(failure)
        )
        receipt  <- accepted(scheduler.submit).flatMap(_.await)
        complete <- pruned.get
        curated  <- repository.daily.readCurated
      yield assertTrue(
        receipt == MemoryTaskOutcome.Completed(Exit.fail(failure)),
        complete,
        curated.contains("curated")
      )
    },
    test(
      "owner closure waits for current target cleanup and preserves prior progress without starting later targets"
    ) {
      for
        _          <- TestClock.setTime(now)
        repository <- MemoryRepository.inMemory()
        _          <- repository.daily.appendDaily(day, "fact")
        owner      <- ZIO.acquireRelease(Scope.make)(_.close(Exit.unit))
        queue      <- owner.extend(MemoryTaskQueue.open())
        gate       <- MemoryTriggerGate.inMemory()
        entered    <- Promise.make[Nothing, Unit]
        closed     <- Ref.make(false)
        progress   <- Ref.make(Chunk.empty[Int])
        service <- coordinator(
          repository,
          Chunk(
            progress.update(_ :+ 1).as(TranscriptPruneResult.Missing),
            (entered.succeed(()) *> ZIO.never).ensuring(closed.set(true)),
            progress.update(_ :+ 3).as(TranscriptPruneResult.Missing)
          )
        )
        reports <- Ref.make(0)
        scheduler <- MemoryMaintenanceScheduler.make(
          queue,
          gate,
          MemoryScope.Global,
          service,
          _ => reports.update(_ + 1)
        )
        ticket   <- accepted(scheduler.submit)
        _        <- entered.await
        _        <- owner.close(Exit.unit)
        receipt  <- ticket.await
        cleaned  <- closed.get
        actual   <- progress.get
        reported <- reports.get
        curated  <- repository.daily.readCurated
      yield assertTrue(
        cleaned,
        actual == Chunk(1),
        reported == 0,
        curated.contains("curated"),
        receipt match
          case MemoryTaskOutcome.Completed(exit) => exit.isInterrupted
          case _                                 => false
      )
    }
  )
