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
import java.time.{Instant, LocalDate, ZoneOffset}
import zio.*
import zio.json.*
import zio.test.*

object MemoryMaintenanceSpec extends ZIOSpecDefault:
  private val old: LocalDate = LocalDate.of(2025, 12, 31)
  private val today: Instant = Instant.parse("2026-04-01T00:00:00Z")

  private def tracking(
      repository: MemoryRepository,
      before: IO[AgentError, Unit]
  ): MemoryRepository =
    new MemoryRepository(
      repository.daily,
      repository.consolidation,
      new DailyMemoryRetention:
        def archiveBefore(cutoff: LocalDate): IO[AgentError, Chunk[DailyMemoryArchive]] =
          before *> repository.retention.archiveBefore(cutoff)
        def archives: IO[AgentError, Chunk[DailyMemoryArchive]] = repository.retention.archives
        def readArchive(archive: DailyMemoryArchive): IO[AgentError, Option[String]] =
          repository.retention.readArchive(archive)
    )

  def spec: Spec[TestEnvironment & Scope, Any] = suite("MemoryMaintenanceSpec")(
    test("consolidates and closes extraction before archiving strictly older acknowledged days") {
      for
        _          <- TestClock.setTime(today)
        repository <- MemoryRepository.inMemory()
        _          <- repository.daily.appendDaily(old, "old fact")
        _          <- repository.daily.appendDaily(LocalDate.of(2026, 1, 1), "boundary fact")
        closed     <- Ref.make(false)
        observed   <- Ref.make(false)
        wrapped = tracking(repository, closed.get.flatMap(observed.set))
        service <- MemoryMaintenance.make(
          wrapped,
          MemoryExtractor(_ => ZIO.addFinalizer(closed.set(true)).as("curated facts"))
        )
        result        <- service.run
        archive       <- repository.retention.archives
        boundary      <- repository.daily.readDaily(LocalDate.of(2026, 1, 1))
        beforeArchive <- observed.get
      yield assertTrue(
        beforeArchive,
        boundary.contains("boundary fact"),
        archive.map(_.day) == Chunk(old),
        result == MemoryMaintenanceResult.Committed(
          DailyArchivalResult.Archived(LocalDate.of(2026, 1, 1), archive)
        ),
        result.toJson.fromJson[MemoryMaintenanceResult] == Right(result)
      )
    },
    test("NoPendingMemories archives acknowledged days but never blank unacknowledged input") {
      for
        _          <- TestClock.setTime(today)
        repository <- MemoryRepository.inMemory()
        _          <- repository.daily.appendDaily(old, "confirmed")
        store = repository.consolidation
        snapshot  <- store.snapshot
        _         <- store.commit(snapshot.revision, "curated")
        _         <- repository.daily.appendDaily(old.minusDays(1), " ")
        extracted <- Ref.make(false)
        service <- MemoryMaintenance.make(
          repository,
          MemoryExtractor(_ => extracted.set(true).as("unused"))
        )
        result   <- service.run
        archives <- repository.retention.archives
        blank    <- repository.daily.readDaily(old.minusDays(1))
        called   <- extracted.get
      yield assertTrue(
        !called,
        blank.contains(" "),
        archives.map(_.day) == Chunk(old),
        result == MemoryMaintenanceResult.NoPendingMemories(
          DailyArchivalResult.Archived(LocalDate.of(2026, 1, 1), archives)
        )
      )
    },
    test("Conflict and EmptyResponse defer archival even when older acknowledged content exists") {
      ZIO
        .foreach(List(ConsolidationResult.Conflict, ConsolidationResult.EmptyResponse)): outcome =>
          for
            _          <- TestClock.setTime(today)
            repository <- MemoryRepository.inMemory()
            _          <- repository.daily.appendDaily(old, "confirmed")
            store = repository.consolidation
            snapshot <- store.snapshot
            _        <- store.commit(snapshot.revision, "curated")
            _        <- repository.daily.appendDaily(old.plusDays(1), "pending")
            called   <- Ref.make(false)
            wrapped = tracking(repository, called.set(true))
            extractor = outcome match
              case ConsolidationResult.Conflict =>
                MemoryExtractor(_ =>
                  repository.daily.appendDaily(old.plusDays(2), "concurrent fact").as("candidate")
                )
              case _ => MemoryExtractor(_ => ZIO.succeed(" "))
            service  <- MemoryMaintenance.make(wrapped, extractor)
            result   <- service.run
            archived <- called.get
            retained <- repository.daily.readDaily(old)
          yield !archived && retained.contains("confirmed") && result == (outcome match
            case ConsolidationResult.Conflict => MemoryMaintenanceResult.Conflict
            case _                            => MemoryMaintenanceResult.EmptyResponse)
        .map(results => assertTrue(results.forall(identity)))
    },
    test("typed extraction errors and cleanup defects stop before archival") {
      val failure = AgentError.MemoryStorageError("extractor failed")
      val defect  = new IllegalStateException("cleanup failed")
      ZIO
        .foreach(
          List(
            MemoryExtractor(_ => ZIO.fail(failure)),
            MemoryExtractor(_ => ZIO.addFinalizer(ZIO.die(defect)).as("candidate"))
          )
        ): extractor =>
          for
            _          <- TestClock.setTime(today)
            repository <- MemoryRepository.inMemory()
            _          <- repository.daily.appendDaily(old, "fact")
            called     <- Ref.make(false)
            service    <- MemoryMaintenance.make(tracking(repository, called.set(true)), extractor)
            exit       <- service.run.exit
            archived   <- called.get
            curated    <- repository.daily.readCurated
          yield !archived && curated.isEmpty && exit.causeOption.exists(cause =>
            cause.failures.contains(failure) || cause.defects.contains(defect)
          )
        .map(results => assertTrue(results.forall(identity)))
    },
    test("cancellation closes extraction and cannot advance to archival") {
      for
        _          <- TestClock.setTime(today)
        repository <- MemoryRepository.inMemory()
        _          <- repository.daily.appendDaily(old, "fact")
        entered    <- Promise.make[Nothing, Unit]
        closed     <- Ref.make(false)
        called     <- Ref.make(false)
        service <- MemoryMaintenance.make(
          tracking(repository, called.set(true)),
          MemoryExtractor(_ =>
            ZIO.addFinalizer(closed.set(true)) *> entered.succeed(()) *> ZIO.never
          )
        )
        fiber    <- service.run.forkScoped
        _        <- entered.await
        exit     <- fiber.interrupt
        archived <- called.get
        cleaned  <- closed.get
      yield assertTrue(exit.isInterrupted, !archived, cleaned)
    },
    test("archival failure preserves an already committed consolidation") {
      val failure = AgentError.MemoryStorageError("archive unavailable")
      for
        _          <- TestClock.setTime(today)
        repository <- MemoryRepository.inMemory()
        _          <- repository.daily.appendDaily(old, "fact")
        service <- MemoryMaintenance.make(
          tracking(repository, ZIO.fail(failure)),
          MemoryExtractor(_ => ZIO.succeed("committed before archive"))
        )
        exit    <- service.run.exit
        curated <- repository.daily.readCurated
        daily   <- repository.daily.readDaily(old)
      yield assertTrue(
        exit == Exit.fail(failure),
        curated.contains("committed before archive"),
        daily.contains("fact")
      )
    },
    test("KeepAll bypasses invalid clock dates and leaves acknowledged ledgers active") {
      for
        _          <- TestClock.setTime(Instant.MAX)
        repository <- MemoryRepository.inMemory()
        _          <- repository.daily.appendDaily(old, "fact")
        service <- MemoryMaintenance.make(
          repository,
          MemoryExtractor(_ => ZIO.succeed("curated")),
          policy = MemoryRetentionPolicy.KeepAll
        )
        result <- service.run
        daily  <- repository.daily.readDaily(old)
      yield assertTrue(
        result == MemoryMaintenanceResult.Committed(DailyArchivalResult.Kept),
        daily.contains("fact")
      )
    },
    test("date overflow and cutoff underflow fail before extraction or mutation") {
      val minimum = LocalDate.MIN.atStartOfDay().toInstant(ZoneOffset.UTC)
      ZIO
        .foreach(List(Instant.MAX, minimum)): instant =>
          for
            _          <- TestClock.setTime(instant)
            repository <- MemoryRepository.inMemory()
            _          <- repository.daily.appendDaily(old, "fact")
            called     <- Ref.make(false)
            service <- MemoryMaintenance
              .make(repository, MemoryExtractor(_ => called.set(true).as("candidate")))
            exit      <- service.run.exit
            extracted <- called.get
            curated   <- repository.daily.readCurated
          yield !extracted && curated.isEmpty && exit.causeOption.exists(
            _.failures.exists(_.isInstanceOf[AgentError.InvalidConfiguration])
          )
        .map(results => assertTrue(results.forall(identity)))
    },
    test(
      "the explicit zone selects the calendar date and a single cutoff survives extraction crossing midnight"
    ) {
      for
        _          <- TestClock.setTime(Instant.parse("2026-04-01T23:30:00Z"))
        repository <- MemoryRepository.inMemory()
        _          <- repository.daily.appendDaily(old, "fact")
        service <- MemoryMaintenance.make(
          repository,
          MemoryExtractor(_ => TestClock.adjust(24.hours).as("curated")),
          zone = ZoneOffset.ofHours(2)
        )
        result   <- service.run
        archives <- repository.retention.archives
      yield assertTrue(
        result == MemoryMaintenanceResult.Committed(
          DailyArchivalResult.Archived(LocalDate.of(2026, 1, 2), archives)
        )
      )
    }
  )
