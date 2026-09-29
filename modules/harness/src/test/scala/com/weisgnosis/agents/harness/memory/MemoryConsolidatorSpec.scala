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

import com.weisgnosis.agents.core.client.{LlmClient, LlmConfig, TurnOutput}
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.model.{Msg, Role}
import java.time.{Instant, LocalDate}
import zio.*
import zio.json.*
import zio.test.*

object MemoryConsolidatorSpec extends ZIOSpecDefault:
  private val day: LocalDate = LocalDate.of(2026, 9, 29)
  private val counter: TokenCounter = new TokenCounter:
    def count(message: Msg): Long = message.text.length.toLong
  private def repository: IO[AgentError, MemoryRepository] =
    for
      repository <- MemoryRepository.inMemory(Some("existing"))
      _          <- repository.daily.appendDaily(day, "new fact")
    yield repository

  def spec: Spec[TestEnvironment & Scope, Any] = suite("MemoryConsolidatorSpec")(
    test("empty and blank pending snapshots skip extraction without changing progress") {
      for outcomes <- ZIO.foreach(Chunk("", " \n")): daily =>
          for
            repository <- MemoryRepository.inMemory(Some("existing"))
            _          <- repository.daily.appendDaily(day, daily)
            before     <- repository.consolidation.snapshot
            calls      <- Ref.make(0)
            consolidator <- MemoryConsolidator.make(
              repository.consolidation,
              MemoryExtractor(_ => calls.update(_ + 1).as("wrong")),
              counter
            )
            result <- consolidator.consolidate
            after  <- repository.consolidation.snapshot
            count  <- calls.get
          yield result == ConsolidationResult.NoPendingMemories && after == before && count == 0
      yield assertTrue(outcomes.forall(identity))
    },
    test("sends two messages with curated context and deterministically ordered daily contents") {
      for
        repository <- MemoryRepository.inMemory(Some("existing"))
        _          <- repository.daily.appendDaily(day, " second-day ")
        _          <- repository.daily.appendDaily(day.minusDays(1), "first-day")
        inputs     <- Ref.make(Chunk.empty[Chunk[Msg]])
        consolidator <- MemoryConsolidator.make(
          repository.consolidation,
          MemoryExtractor(input => inputs.update(_ :+ input).as("  merged memory\n")),
          counter
        )
        result   <- consolidator.consolidate
        after    <- repository.consolidation.snapshot
        daily    <- repository.daily.readDaily(day)
        observed <- inputs.get
      yield assertTrue(
        result == ConsolidationResult.Committed,
        after.curated == "merged memory",
        after.pending.isEmpty,
        daily.contains(" second-day "),
        observed.size == 1,
        observed.headOption.exists(_.map(_.role) == Chunk(Role.System, Role.User)),
        observed.headOption.flatMap(_.headOption).exists(_.text.contains("4000 tokens")),
        observed.headOption
          .flatMap(_.lastOption)
          .exists(
            _.text ==
              "Current MEMORY.md:\nexisting\n\nNew daily ledger entries to merge:\n### 2026-09-28.md\nfirst-day\n\n### 2026-09-29.md\nsecond-day"
          )
      )
    },
    test("blank output preserves both curated content and unacknowledged inputs") {
      for
        repository <- repository
        before     <- repository.consolidation.snapshot
        consolidator <- MemoryConsolidator.make(
          repository.consolidation,
          MemoryExtractor(_ => ZIO.succeed(" \n")),
          counter
        )
        result <- consolidator.consolidate
        after  <- repository.consolidation.snapshot
      yield assertTrue(result == ConsolidationResult.EmptyResponse, after == before)
    },
    test("exact budget commits and one token over leaves content and progress unchanged") {
      for results <- ZIO.foreach(Chunk(4000, 4001)): size =>
          for
            repository <- repository
            before     <- repository.consolidation.snapshot
            consolidator <- MemoryConsolidator.make(
              repository.consolidation,
              MemoryExtractor(_ => ZIO.succeed("x" * size)),
              counter
            )
            result <- consolidator.consolidate.either
            after  <- repository.consolidation.snapshot
          yield
            if size == 4000 then
              result == Right(
                ConsolidationResult.Committed
              ) && after.curated.length == 4000 && after.pending.isEmpty
            else result == Left(AgentError.ContextLengthExceeded(4001L, 4000L)) && after == before
      yield assertTrue(results.forall(identity))
    },
    test("invalid budgets and negative counter results fail explicitly") {
      val negative = new TokenCounter:
        def count(message: Msg): Long = -1L
      for
        repository <- repository
        invalid <- ZIO.foreach(Chunk(0, -1, 4001))(budget =>
          MemoryConsolidator
            .make(
              repository.consolidation,
              MemoryExtractor(_ => ZIO.succeed("memory")),
              counter,
              budget
            )
            .either
        )
        before <- repository.consolidation.snapshot
        consolidator <- MemoryConsolidator.make(
          repository.consolidation,
          MemoryExtractor(_ => ZIO.succeed("memory")),
          negative
        )
        result <- consolidator.consolidate.either
        after  <- repository.consolidation.snapshot
      yield assertTrue(
        invalid.forall(_.isLeft),
        result.left.exists(_.isInstanceOf[AgentError.InvalidConfiguration]),
        after == before
      )
    },
    test("default estimation is enforced after extraction") {
      val output = "x" * 16001
      for
        repository <- repository
        before     <- repository.consolidation.snapshot
        consolidator <- MemoryConsolidator.make(
          repository.consolidation,
          MemoryExtractor(_ => ZIO.succeed(output))
        )
        result <- consolidator.consolidate.either
        after  <- repository.consolidation.snapshot
      yield assertTrue(
        result.left.exists(_.isInstanceOf[AgentError.ContextLengthExceeded]),
        after == before
      )
    },
    test("extraction scope closes before conditional commit") {
      for
        closed  <- Ref.make(false)
        commits <- Ref.make(0)
        store = new ConsolidationStore:
          type Revision = Unit
          def snapshot: IO[AgentError, ConsolidationSnapshot[Revision]] =
            ZIO.succeed(ConsolidationSnapshot((), "existing", Chunk(DailyMemory(day, "fact"))))
          def commit(expected: Revision, content: String): IO[AgentError, ConsolidationCommit] =
            closed.get.flatMap(done =>
              if done then commits.update(_ + 1).as(ConsolidationCommit.Applied)
              else ZIO.fail(AgentError.InvalidMessage("extraction scope open"))
            )
        consolidator <- MemoryConsolidator.make(
          store,
          MemoryExtractor(_ => ZIO.addFinalizer(closed.set(true)).as("merged")),
          counter
        )
        result <- consolidator.consolidate
        count  <- commits.get
      yield assertTrue(result == ConsolidationResult.Committed, count == 1)
    },
    test("extraction error and cleanup defect preserve content and progress") {
      val error  = AgentError.ModelError(503, "unavailable")
      val defect = new IllegalStateException("cleanup failed")
      for
        repository <- repository
        before     <- repository.consolidation.snapshot
        failed <- MemoryConsolidator.make(
          repository.consolidation,
          MemoryExtractor(_ => ZIO.fail(error)),
          counter
        )
        failure <- failed.consolidate.either
        defective <- MemoryConsolidator.make(
          repository.consolidation,
          MemoryExtractor(_ => ZIO.addFinalizer(ZIO.die(defect)).as("merged")),
          counter
        )
        exit  <- defective.consolidate.exit
        after <- repository.consolidation.snapshot
      yield assertTrue(
        failure == Left(error),
        exit.causeOption.exists(_.defects.contains(defect)),
        after == before
      )
    },
    test("cancelling extraction closes resources without committing and permits a later run") {
      for
        repository <- repository
        before     <- repository.consolidation.snapshot
        entered    <- Promise.make[Nothing, Unit]
        closed     <- Ref.make(false)
        calls      <- Ref.make(0)
        consolidator <- MemoryConsolidator.make(
          repository.consolidation,
          MemoryExtractor(_ =>
            calls
              .getAndUpdate(_ + 1)
              .flatMap(index =>
                if index == 0 then
                  ZIO.addFinalizer(closed.set(true)) *> entered.succeed(()) *> ZIO.never
                else ZIO.succeed("merged")
              )
          ),
          counter
        )
        fiber     <- consolidator.consolidate.forkScoped
        _         <- entered.await
        exit      <- fiber.interrupt
        unchanged <- repository.consolidation.snapshot
        released  <- closed.get
        next      <- consolidator.consolidate
      yield assertTrue(
        exit.isInterrupted,
        released,
        unchanged == before,
        next == ConsolidationResult.Committed
      )
    },
    test("a daily append during extraction causes conflict without replaying stale output") {
      for
        repository <- repository
        entered    <- Promise.make[Nothing, Unit]
        release    <- Promise.make[Nothing, Unit]
        calls      <- Ref.make(0)
        consolidator <- MemoryConsolidator.make(
          repository.consolidation,
          MemoryExtractor(_ =>
            calls.update(_ + 1) *> entered.succeed(()) *> release.await.as("stale")
          ),
          counter
        )
        fiber  <- consolidator.consolidate.forkScoped
        _      <- entered.await
        _      <- repository.daily.appendDaily(day, " later fact")
        _      <- release.succeed(())
        result <- fiber.join
        after  <- repository.consolidation.snapshot
        count  <- calls.get
      yield assertTrue(
        result == ConsolidationResult.Conflict,
        count == 1,
        after.curated == "existing",
        after.pending == Chunk(DailyMemory(day, "new fact later fact"))
      )
    },
    test("competing consolidators cannot both publish against the same revision") {
      for
        repository    <- repository
        firstEntered  <- Promise.make[Nothing, Unit]
        secondEntered <- Promise.make[Nothing, Unit]
        releaseFirst  <- Promise.make[Nothing, Unit]
        releaseSecond <- Promise.make[Nothing, Unit]
        first <- MemoryConsolidator.make(
          repository.consolidation,
          MemoryExtractor(_ => firstEntered.succeed(()) *> releaseFirst.await.as("first winner")),
          counter
        )
        second <- MemoryConsolidator.make(
          repository.consolidation,
          MemoryExtractor(_ => secondEntered.succeed(()) *> releaseSecond.await.as("stale second")),
          counter
        )
        firstFiber   <- first.consolidate.forkScoped
        secondFiber  <- second.consolidate.forkScoped
        _            <- firstEntered.await *> secondEntered.await
        _            <- releaseFirst.succeed(())
        firstResult  <- firstFiber.join
        _            <- releaseSecond.succeed(())
        secondResult <- secondFiber.join
        after        <- repository.consolidation.snapshot
      yield assertTrue(
        firstResult == ConsolidationResult.Committed,
        secondResult == ConsolidationResult.Conflict,
        after.curated == "first winner",
        after.pending.isEmpty
      )
    },
    test("malformed duplicate-day snapshots fail before extraction or commit") {
      for
        calls <- Ref.make(Chunk.empty[String])
        store = new ConsolidationStore:
          type Revision = Unit
          def snapshot: IO[AgentError, ConsolidationSnapshot[Revision]] =
            ZIO.succeed(
              ConsolidationSnapshot(
                (),
                "existing",
                Chunk(DailyMemory(day, "a"), DailyMemory(day, "b"))
              )
            )
          def commit(expected: Revision, content: String): IO[AgentError, ConsolidationCommit] =
            calls.update(_ :+ "commit").as(ConsolidationCommit.Applied)
        consolidator <- MemoryConsolidator.make(
          store,
          MemoryExtractor(_ => calls.update(_ :+ "extract").as("wrong")),
          counter
        )
        result   <- consolidator.consolidate.either
        observed <- calls.get
      yield assertTrue(
        result.left.exists(_.isInstanceOf[AgentError.InvalidMessage]),
        observed.isEmpty
      )
    },
    test("snapshot and commit errors propagate without fallback or retry") {
      val error = AgentError.MemoryStorageError("storage unavailable")
      for
        operations <- Ref.make(Chunk.empty[String])
        readFailure = new ConsolidationStore:
          type Revision = Unit
          def snapshot: IO[AgentError, ConsolidationSnapshot[Revision]] =
            operations.update(_ :+ "read") *> ZIO.fail(error)
          def commit(expected: Revision, content: String): IO[AgentError, ConsolidationCommit] =
            operations.update(_ :+ "wrong").as(ConsolidationCommit.Applied)
        commitFailure = new ConsolidationStore:
          type Revision = Unit
          def snapshot: IO[AgentError, ConsolidationSnapshot[Revision]] =
            ZIO.succeed(ConsolidationSnapshot((), "existing", Chunk(DailyMemory(day, "fact"))))
          def commit(expected: Revision, content: String): IO[AgentError, ConsolidationCommit] =
            operations.update(_ :+ "commit") *> ZIO.fail(error)
        extractor = MemoryExtractor(_ => operations.update(_ :+ "extract").as("merged"))
        first    <- MemoryConsolidator.make(readFailure, extractor, counter)
        read     <- first.consolidate.either
        second   <- MemoryConsolidator.make(commitFailure, extractor, counter)
        commit   <- second.consolidate.either
        observed <- operations.get
      yield assertTrue(
        read == Left(error),
        commit == Left(error),
        observed == Chunk("read", "extract", "commit")
      )
    },
    test("daily extraction and client-backed consolidation share distinct write capabilities") {
      for
        _          <- TestClock.setTime(Instant.parse("2026-09-29T10:00:00Z"))
        repository <- MemoryRepository.inMemory(Some("existing"))
        daily <- MemoryFlushManager.make(
          repository.daily,
          MemoryExtractor(_ => ZIO.succeed("- new fact"))
        )
        _        <- daily.flush(Chunk(Msg.user("remember this")))
        original <- repository.daily.readDaily(day)
        client   <- LlmClient.mock(Chunk(TurnOutput.FinalText(Msg.assistant("merged memory"))))
        consolidator <- MemoryConsolidator.make(
          repository.consolidation,
          MemoryExtractor.fromClient(client, LlmConfig("memory")),
          counter
        )
        result   <- consolidator.consolidate
        curated  <- repository.daily.readCurated
        retained <- repository.daily.readDaily(day)
        snapshot <- repository.consolidation.snapshot
        again    <- consolidator.consolidate
      yield assertTrue(
        result == ConsolidationResult.Committed,
        curated.contains("merged memory"),
        retained == original,
        snapshot.pending.isEmpty,
        again == ConsolidationResult.NoPendingMemories,
        result.toJson.fromJson[ConsolidationResult] == Right(result)
      )
    },
    test("one consolidator serializes snapshots and skips a second already acknowledged run") {
      for
        repository <- repository
        entered    <- Promise.make[Nothing, Unit]
        release    <- Promise.make[Nothing, Unit]
        calls      <- Ref.make(0)
        consolidator <- MemoryConsolidator.make(
          repository.consolidation,
          MemoryExtractor(_ =>
            calls
              .getAndUpdate(_ + 1)
              .flatMap(index =>
                if index == 0 then entered.succeed(()) *> release.await.as("merged")
                else ZIO.succeed("unexpected second extraction")
              )
          ),
          counter
        )
        first        <- consolidator.consolidate.forkScoped
        _            <- entered.await
        second       <- consolidator.consolidate.forkScoped
        _            <- TestClock.adjust(0.seconds)
        blockedCalls <- calls.get
        _            <- release.succeed(())
        firstResult  <- first.join
        secondResult <- second.join
        count        <- calls.get
      yield assertTrue(
        blockedCalls == 1,
        count == 1,
        firstResult == ConsolidationResult.Committed,
        secondResult == ConsolidationResult.NoPendingMemories
      )
    }
  )
