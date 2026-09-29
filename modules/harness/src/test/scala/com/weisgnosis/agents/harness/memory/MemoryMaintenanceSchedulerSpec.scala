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
import com.weisgnosis.agents.harness.eviction.WorkspaceTrust
import java.nio.file.Files
import java.time.{Instant, LocalDate}
import zio.*
import zio.test.*

object MemoryMaintenanceSchedulerSpec extends ZIOSpecDefault:
  private val day: LocalDate = LocalDate.of(2025, 12, 31)
  private val now: Instant   = Instant.parse("2026-04-01T00:00:00Z")

  private def accepted(submission: UIO[MemoryTaskSubmission]): IO[AgentError, MemoryTaskTicket] =
    submission.flatMap:
      case MemoryTaskSubmission.Accepted(ticket) => ZIO.succeed(ticket)
      case MemoryTaskSubmission.Rejected(reason) =>
        ZIO.fail(AgentError.InvalidConfiguration(s"Unexpected rejection: $reason"))

  private def service(repository: MemoryRepository): IO[AgentError, MemoryMaintenance] =
    MemoryMaintenance.make(repository, MemoryExtractor(_ => ZIO.succeed("curated fact")))

  def spec: Spec[TestEnvironment & Scope, Any] = suite("MemoryMaintenanceSchedulerSpec")(
    test(
      "one background gate claim covers consolidation and archival without delaying agent completion"
    ) {
      for
        _          <- TestClock.setTime(now)
        repository <- MemoryRepository.inMemory()
        _          <- repository.daily.appendDaily(day, "fact")
        queue      <- MemoryTaskQueue.open()
        entered    <- Promise.make[Nothing, Unit]
        release    <- Promise.make[Nothing, Unit]
        operations <- Ref.make(Chunk.empty[MemoryOperation])
        gate = new MemoryTriggerGate:
          def claim(
              operation: MemoryOperation,
              scope: MemoryScope
          ): IO[AgentError, MemoryTriggerDecision] =
            operations.update(_ :+ operation) *> entered.succeed(()) *> release.await.as(
              MemoryTriggerDecision.Admitted
            )
          def cleanup(retention: MemoryInterval): IO[AgentError, Int] = ZIO.succeed(0)
        maintenance <- service(repository)
        reports     <- Ref.make(Chunk.empty[MemoryMaintenanceResult])
        tickets     <- Ref.make(Chunk.empty[MemoryTaskTicket])
        scheduler <- MemoryMaintenanceScheduler.make(
          queue,
          gate,
          MemoryScope.Global,
          maintenance,
          result => reports.update(_ :+ result)
        )
        agent <- ReActAgent.make(
          client = LlmClient.mockFunction((_, _) => TurnOutput.FinalText(Msg.assistant("done"))),
          completionObserver = scheduler.completionObserver(ticket => tickets.update(_ :+ ticket))
        )
        result   <- ZIO.scoped(agent.run(Msg.user("turn")))
        _        <- entered.await
        before   <- repository.retention.archives
        _        <- release.succeed(())
        receipts <- tickets.get.flatMap(ZIO.foreach(_)(_.await))
        claims   <- operations.get
        outcomes <- reports.get
        archived <- repository.retention.archives
      yield assertTrue(
        result.reply.text == "done",
        before.isEmpty,
        archived.size == 1,
        claims == Chunk(MemoryOperation.Maintenance),
        receipts == Chunk(MemoryTaskOutcome.Completed(Exit.unit)),
        outcomes == Chunk(
          MemoryMaintenanceResult.Committed(
            DailyArchivalResult.Archived(LocalDate.of(2026, 1, 1), archived)
          )
        )
      )
    },
    test(
      "pending owner work coalesces and samples the execution date rather than its admission date"
    ) {
      for
        _           <- TestClock.setTime(now.minusSeconds(24 * 60 * 60))
        repository  <- MemoryRepository.inMemory()
        _           <- repository.daily.appendDaily(day, "fact")
        queue       <- MemoryTaskQueue.open()
        gate        <- MemoryTriggerGate.inMemory()
        blocker     <- MemoryConversationToken.make
        entered     <- Promise.make[Nothing, Unit]
        release     <- Promise.make[Nothing, Unit]
        _           <- queue.submit(blocker)(entered.succeed(()) *> release.await)
        _           <- entered.await
        maintenance <- service(repository)
        reports     <- Ref.make(Chunk.empty[MemoryMaintenanceResult])
        scheduler <- MemoryMaintenanceScheduler.make(
          queue,
          gate,
          MemoryScope.Global,
          maintenance,
          result => reports.update(_ :+ result)
        )
        first      <- accepted(scheduler.submit)
        second     <- accepted(scheduler.submit)
        superseded <- first.await
        _          <- TestClock.setTime(now)
        _          <- release.succeed(())
        completed  <- second.await
        observed   <- reports.get
        archived   <- repository.retention.archives
      yield assertTrue(
        superseded == MemoryTaskOutcome.Superseded,
        completed == MemoryTaskOutcome.Completed(Exit.unit),
        archived.size == 1,
        observed == Chunk(
          MemoryMaintenanceResult.Committed(
            DailyArchivalResult.Archived(LocalDate.of(2026, 1, 1), archived)
          )
        )
      )
    },
    test("disabled admission bypasses date validation extraction archival and reporting") {
      for
        _          <- TestClock.setTime(Instant.MAX)
        repository <- MemoryRepository.inMemory()
        _          <- repository.daily.appendDaily(day, "fact")
        queue      <- MemoryTaskQueue.open()
        gate  <- MemoryTriggerGate.inMemory(MemoryTriggerPolicy(maintenance = MemoryTrigger.Never))
        calls <- Ref.make(0)
        maintenance <- MemoryMaintenance.make(
          repository,
          MemoryExtractor(_ => calls.update(_ + 1).as("candidate"))
        )
        reports <- Ref.make(Chunk.empty[MemoryMaintenanceResult])
        scheduler <- MemoryMaintenanceScheduler.make(
          queue,
          gate,
          MemoryScope.Global,
          maintenance,
          result => reports.update(_ :+ result)
        )
        outcome  <- accepted(scheduler.submit).flatMap(_.await)
        count    <- calls.get
        reported <- reports.get
        curated  <- repository.daily.readCurated
      yield assertTrue(
        outcome == MemoryTaskOutcome.Completed(Exit.unit),
        count == 0,
        reported.isEmpty,
        curated.isEmpty
      )
    },
    test("one maintenance reservation suppresses later consolidation and archival together") {
      for
        _           <- TestClock.setTime(now)
        repository  <- MemoryRepository.inMemory()
        _           <- repository.daily.appendDaily(day, "first")
        queue       <- MemoryTaskQueue.open()
        gate        <- MemoryTriggerGate.inMemory()
        maintenance <- service(repository)
        reports     <- Ref.make(Chunk.empty[MemoryMaintenanceResult])
        scheduler <- MemoryMaintenanceScheduler.make(
          queue,
          gate,
          MemoryScope.Global,
          maintenance,
          result => reports.update(_ :+ result)
        )
        first    <- accepted(scheduler.submit).flatMap(_.await)
        _        <- repository.daily.appendDaily(day, "second")
        second   <- accepted(scheduler.submit).flatMap(_.await)
        observed <- reports.get
        active   <- repository.daily.readDaily(day)
        snapshot <- repository.consolidation.snapshot
      yield assertTrue(
        List(first, second).forall(_ == MemoryTaskOutcome.Completed(Exit.unit)),
        observed.size == 1,
        active.contains("second"),
        snapshot.pending == Chunk(DailyMemory(day, "second"))
      )
    },
    test("a failed aggregate report cannot roll back completed archival") {
      val failure = AgentError.MemoryStorageError("result sink unavailable")
      for
        _           <- TestClock.setTime(now)
        repository  <- MemoryRepository.inMemory()
        _           <- repository.daily.appendDaily(day, "fact")
        queue       <- MemoryTaskQueue.open()
        gate        <- MemoryTriggerGate.inMemory()
        maintenance <- service(repository)
        scheduler <- MemoryMaintenanceScheduler.make(
          queue,
          gate,
          MemoryScope.Global,
          maintenance,
          _ => ZIO.fail(failure)
        )
        outcome  <- accepted(scheduler.submit).flatMap(_.await)
        archived <- repository.retention.archives
        active   <- repository.daily.readDaily(day)
        curated  <- repository.daily.readCurated
      yield assertTrue(
        outcome == MemoryTaskOutcome.Completed(Exit.fail(failure)),
        archived.size == 1,
        active.isEmpty,
        curated.contains("curated fact")
      )
    },
    test("closing the queue during aggregate reporting waits for cleanup after archival") {
      for
        _           <- TestClock.setTime(now)
        repository  <- MemoryRepository.inMemory()
        _           <- repository.daily.appendDaily(day, "fact")
        owner       <- ZIO.acquireRelease(Scope.make)(_.close(Exit.unit))
        queue       <- owner.extend(MemoryTaskQueue.open())
        gate        <- MemoryTriggerGate.inMemory()
        maintenance <- service(repository)
        entered     <- Promise.make[Nothing, Unit]
        cleaned     <- Ref.make(false)
        scheduler <- MemoryMaintenanceScheduler.make(
          queue,
          gate,
          MemoryScope.Global,
          maintenance,
          _ => (entered.succeed(()) *> ZIO.never).ensuring(cleaned.set(true))
        )
        ticket   <- accepted(scheduler.submit)
        _        <- entered.await
        _        <- owner.close(Exit.unit)
        outcome  <- ticket.await
        closed   <- cleaned.get
        archived <- repository.retention.archives
      yield assertTrue(
        closed,
        archived.size == 1,
        outcome match
          case MemoryTaskOutcome.Completed(exit) => exit.isInterrupted
          case _                                 => false
      )
    },
    test(
      "local archival failure suppresses aggregate reporting but preserves committed memory across reopen"
    ) {
      for
        _        <- TestClock.setTime(now)
        root     <- MemoryTestSupport.temporaryDirectory
        reported <- Ref.make(false)
        outcome <- ZIO.scoped:
          for
            repository <- LocalMemoryRepository.open(root, WorkspaceTrust.HostControlled)
            _          <- repository.daily.appendDaily(day, "fact")
            _ <- ZIO.attemptBlocking(
              Files.writeString(root.resolve("memory/archive"), "obstruction")
            )
            queue       <- MemoryTaskQueue.open()
            gate        <- MemoryTriggerGate.inMemory()
            maintenance <- service(repository)
            scheduler <- MemoryMaintenanceScheduler.make(
              queue,
              gate,
              MemoryScope.Global,
              maintenance,
              _ => reported.set(true)
            )
            result <- accepted(scheduler.submit).flatMap(_.await)
          yield result
        persisted <- ZIO.scoped:
          LocalMemoryRepository
            .open(root, WorkspaceTrust.HostControlled)
            .flatMap: repository =>
              repository.daily.readCurated.zip(repository.daily.readDaily(day))
        observed <- reported.get
      yield assertTrue(
        !observed,
        persisted == (Some("curated fact"), Some("fact")),
        outcome match
          case MemoryTaskOutcome.Completed(exit) => exit.isFailure
          case _                                 => false
      )
    },
    test(
      "consolidation-only and combined schedulers intentionally share the same maintenance window"
    ) {
      for
        _          <- TestClock.setTime(now)
        repository <- MemoryRepository.inMemory()
        _          <- repository.daily.appendDaily(day, "fact")
        queue      <- MemoryTaskQueue.open()
        gate       <- MemoryTriggerGate.inMemory()
        consolidator <- MemoryConsolidator.make(
          repository.consolidation,
          MemoryExtractor(_ => ZIO.succeed("curated"))
        )
        only <- MemoryConsolidationScheduler.make(
          queue,
          gate,
          MemoryScope.Global,
          consolidator,
          _ => ZIO.unit
        )
        maintenance <- service(repository)
        reported    <- Ref.make(false)
        combined <- MemoryMaintenanceScheduler.make(
          queue,
          gate,
          MemoryScope.Global,
          maintenance,
          _ => reported.set(true)
        )
        _        <- accepted(only.submit).flatMap(_.await)
        outcome  <- accepted(combined.submit).flatMap(_.await)
        observed <- reported.get
        active   <- repository.daily.readDaily(day)
        archived <- repository.retention.archives
      yield assertTrue(
        outcome == MemoryTaskOutcome.Completed(Exit.unit),
        !observed,
        active.contains("fact"),
        archived.isEmpty
      )
    }
  )
