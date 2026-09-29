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

import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.model.Role
import com.weisgnosis.agents.harness.eviction.WorkspaceTrust
import com.weisgnosis.agents.harness.transcript.*
import java.nio.file.{Files, Path}
import java.nio.file.attribute.FileTime
import java.time.{Instant, LocalDate}
import zio.*
import zio.json.*
import zio.test.*

object MemoryMaintenanceCoordinatorSpec extends ZIOSpecDefault:
  private val now: Instant    = Instant.parse("2026-04-01T00:00:00Z")
  private val day: LocalDate  = LocalDate.of(2025, 1, 1)
  private val old: Instant    = Instant.parse("2025-01-01T00:00:00Z")
  private val cutoff: Instant = Instant.parse("2025-10-03T00:00:00Z")

  private def batch(
      run: Instant => IO[AgentError, TranscriptPruneResult]
  ): IO[AgentError, TranscriptRetentionBatch] =
    for
      id    <- ZIO.fromEither(TranscriptTargetId.fromString("selected"))
      batch <- TranscriptRetentionBatch.make(Chunk(TranscriptRetentionTarget(id)(run)))
    yield batch

  private def log(root: Path): ZIO[Any, Throwable, Unit] =
    val draft = TranscriptDraft(
      Some(TranscriptKey.Message("old")),
      TranscriptPayload.Message(Role.User, "old record", Chunk(TranscriptBlockType.Text))
    )
    TestClock.setTime(old) *> ZIO
      .scoped(
        LocalTranscriptStore
          .open(root, WorkspaceTrust.HostControlled)
          .flatMap(_.append(Chunk(draft)))
      )
      .unit *>
      ZIO
        .attemptBlocking(
          Files.setLastModifiedTime(root.resolve("transcript.jsonl"), FileTime.from(old))
        )
        .unit

  def spec: Spec[TestEnvironment & Scope, Any] = suite("MemoryMaintenanceCoordinatorSpec")(
    suite("independent transcript phase")(
      List(
        ConsolidationResult.Committed,
        ConsolidationResult.NoPendingMemories,
        ConsolidationResult.EmptyResponse,
        ConsolidationResult.Conflict
      ).map(outcome =>
        test(s"$outcome still permits independently eligible transcript pruning") {
          for
            _          <- TestClock.setTime(now)
            repository <- MemoryRepository.inMemory()
            _ <- repository.daily
              .appendDaily(day, "fact")
              .unless(outcome == ConsolidationResult.NoPendingMemories)
            extractor = MemoryExtractor(_ =>
              outcome match
                case ConsolidationResult.Conflict =>
                  repository.daily.appendDaily(day, " changed").as("candidate")
                case ConsolidationResult.EmptyResponse => ZIO.succeed(" ")
                case _                                 => ZIO.succeed("curated")
            )
            memory <- MemoryMaintenance.make(
              repository,
              extractor,
              policy = MemoryRetentionPolicy.KeepAll
            )
            calls       <- Ref.make(0)
            transcripts <- batch(_ => calls.update(_ + 1).as(TranscriptPruneResult.Missing))
            result      <- new MemoryMaintenanceCoordinator(memory, transcripts).run
            count       <- calls.get
            expected = outcome match
              case ConsolidationResult.Committed =>
                MemoryMaintenanceResult.Committed(DailyArchivalResult.Kept)
              case ConsolidationResult.NoPendingMemories =>
                MemoryMaintenanceResult.NoPendingMemories(DailyArchivalResult.Kept)
              case ConsolidationResult.EmptyResponse => MemoryMaintenanceResult.EmptyResponse
              case ConsolidationResult.Conflict      => MemoryMaintenanceResult.Conflict
          yield assertTrue(
            count == 1,
            result.memory == expected,
            result.transcripts match
              case TranscriptBatchResult.Evaluated(actual, targets) =>
                actual == cutoff && targets.size == 1
              case _ => false
          )
        }
      )*
    ),
    test("invalid transcript cutoff prevents memory extraction and target execution") {
      for
        _          <- TestClock.setTime(Instant.MIN)
        repository <- MemoryRepository.inMemory()
        _          <- repository.daily.appendDaily(day, "fact")
        extracted  <- Ref.make(false)
        pruned     <- Ref.make(false)
        memory <- MemoryMaintenance.make(
          repository,
          MemoryExtractor(_ => extracted.set(true).as("candidate")),
          policy = MemoryRetentionPolicy.KeepAll
        )
        transcripts <- batch(_ => pruned.set(true).as(TranscriptPruneResult.Missing))
        exit        <- new MemoryMaintenanceCoordinator(memory, transcripts).run.exit
        extraction  <- extracted.get
        target      <- pruned.get
        curated     <- repository.daily.readCurated
      yield assertTrue(
        exit.causeOption.exists(_.failures.exists(_.isInstanceOf[AgentError.InvalidConfiguration])),
        !extraction,
        !target,
        curated.isEmpty
      )
    },
    test("typed memory failure and cancellation both prevent every transcript target") {
      val failure = AgentError.MemoryStorageError("memory unavailable")
      for
        _           <- TestClock.setTime(now)
        repository  <- MemoryRepository.inMemory()
        _           <- repository.daily.appendDaily(day, "fact")
        calls       <- Ref.make(0)
        transcripts <- batch(_ => calls.update(_ + 1).as(TranscriptPruneResult.Missing))
        failedMemory <- MemoryMaintenance.make(
          repository,
          MemoryExtractor(_ => ZIO.fail(failure)),
          policy = MemoryRetentionPolicy.KeepAll
        )
        failed  <- new MemoryMaintenanceCoordinator(failedMemory, transcripts).run.exit
        entered <- Promise.make[Nothing, Unit]
        cleaned <- Ref.make(false)
        waitingMemory <- MemoryMaintenance.make(
          repository,
          MemoryExtractor(_ =>
            ZIO.addFinalizer(cleaned.set(true)) *> entered.succeed(()) *> ZIO.never
          ),
          policy = MemoryRetentionPolicy.KeepAll
        )
        fiber       <- new MemoryMaintenanceCoordinator(waitingMemory, transcripts).run.forkScoped
        _           <- entered.await
        interrupted <- fiber.interrupt
        count       <- calls.get
        closed      <- cleaned.get
      yield assertTrue(failed == Exit.fail(failure), interrupted.isInterrupted, closed, count == 0)
    },
    test(
      "transcript cutoff prepared before memory work survives its clock advancement and codecs preserve the aggregate"
    ) {
      for
        _          <- TestClock.setTime(now)
        repository <- MemoryRepository.inMemory()
        _          <- repository.daily.appendDaily(day, "fact")
        memory <- MemoryMaintenance.make(
          repository,
          MemoryExtractor(_ => TestClock.adjust(48.hours).as("curated")),
          policy = MemoryRetentionPolicy.KeepAll
        )
        observed    <- Ref.make(Option.empty[Instant])
        transcripts <- batch(before => observed.set(Some(before)).as(TranscriptPruneResult.Missing))
        result      <- new MemoryMaintenanceCoordinator(memory, transcripts).run
        actual      <- observed.get
      yield assertTrue(
        actual.contains(cutoff),
        result.toJson.fromJson[MaintenanceResult] == Right(result)
      )
    },
    test(
      "a second active local transcript stops the batch after the first deletion without undoing persisted memory"
    ) {
      for
        memoryRoot <- MemoryTestSupport.temporaryDirectory
        firstRoot  <- MemoryTestSupport.temporaryDirectory
        secondRoot <- MemoryTestSupport.temporaryDirectory
        _          <- log(firstRoot)
        _          <- log(secondRoot)
        _          <- TestClock.setTime(now)
        firstId    <- ZIO.fromEither(TranscriptTargetId.fromString("first"))
        secondId   <- ZIO.fromEither(TranscriptTargetId.fromString("second"))
        outcome <- ZIO.scoped:
          for
            repository <- LocalMemoryRepository.open(memoryRoot, WorkspaceTrust.HostControlled)
            _          <- repository.daily.appendDaily(day, "durable fact")
            active     <- LocalTranscriptStore.open(secondRoot, WorkspaceTrust.HostControlled)
            memory <- MemoryMaintenance.make(
              repository,
              MemoryExtractor(_ => ZIO.succeed("durable curated")),
              policy = MemoryRetentionPolicy.KeepAll
            )
            transcripts <- TranscriptRetentionBatch.make(
              Chunk(
                TranscriptRetentionTarget.local(firstId, firstRoot, WorkspaceTrust.HostControlled),
                TranscriptRetentionTarget.local(secondId, secondRoot, WorkspaceTrust.HostControlled)
              )
            )
            exit   <- new MemoryMaintenanceCoordinator(memory, transcripts).run.exit
            intact <- active.entries
          yield exit.isFailure && intact.size == 1
        curated <- ZIO.scoped(
          LocalMemoryRepository
            .open(memoryRoot, WorkspaceTrust.HostControlled)
            .flatMap(_.daily.readCurated)
        )
        first <- ZIO.scoped(
          LocalTranscriptStore.open(firstRoot, WorkspaceTrust.HostControlled).flatMap(_.entries)
        )
        second <- ZIO.scoped(
          LocalTranscriptStore.open(secondRoot, WorkspaceTrust.HostControlled).flatMap(_.entries)
        )
      yield assertTrue(
        outcome,
        curated.contains("durable curated"),
        first.isEmpty,
        second.size == 1
      )
    }
  )
