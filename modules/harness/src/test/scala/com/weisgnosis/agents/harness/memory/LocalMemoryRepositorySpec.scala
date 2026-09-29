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
import com.weisgnosis.agents.harness.eviction.WorkspaceTrust
import java.nio.file.{Files, Path}
import java.time.LocalDate
import zio.*
import zio.json.*
import zio.test.*
import MemoryTestSupport.{children, temporaryDirectory}

object LocalMemoryRepositorySpec extends ZIOSpecDefault:
  private val day: LocalDate = LocalDate.of(2026, 9, 29)
  private val oldCheckpoint: MemoryCheckpoint =
    MemoryCheckpoint(1, BigInt(4), "11111111-1111-1111-1111-111111111111", Map.empty)

  private enum Publication:
    case JournalOnly, Curated, Checkpoint, Both

  private enum EntryPoint:
    case Repository, DailyOnly

  private enum Operation:
    case ReadCurated, ReadDaily, AppendDaily, Snapshot, Commit

  private def fixture(root: Path, publication: Publication): IO[AgentError, MemoryJournal] =
    for
      files        <- MemoryFiles.open(root, WorkspaceTrust.HostControlled)
      _            <- files.write(MemoryFile.Curated, "old curated")
      _            <- files.write(MemoryFile.Checkpoint, oldCheckpoint.toJson)
      _            <- files.write(MemoryFile.Daily(day), "daily fact")
      fingerprints <- MemoryJournal.fingerprints(Map(day -> "daily fact"))
      after = MemoryCheckpoint(1, BigInt(5), "22222222-2222-2222-2222-222222222222", fingerprints)
      journal = MemoryJournal(
        1,
        Some("old curated"),
        Some(oldCheckpoint),
        fingerprints,
        "new curated",
        after
      )
      _ <- files.write(MemoryFile.Journal, journal.toJson)
      _ <- files
        .write(MemoryFile.Curated, journal.afterCurated)
        .when(publication == Publication.Curated || publication == Publication.Both)
      _ <- files
        .write(MemoryFile.Checkpoint, after.toJson)
        .when(publication == Publication.Checkpoint || publication == Publication.Both)
    yield journal

  def spec: Spec[TestEnvironment & Scope, Any] = suite("LocalMemoryRepositorySpec")(
    test(
      "missing checkpoint leaves all daily inputs pending and creates no curated or checkpoint payload"
    ) {
      for
        root <- temporaryDirectory
        result <- ZIO.scoped:
          for
            repository <- LocalMemoryRepository.open(root, WorkspaceTrust.HostControlled)
            _          <- repository.daily.appendDaily(day, "first")
            snapshot   <- repository.consolidation.snapshot
          yield snapshot.curated -> snapshot.pending
        entries <- children(root.resolve("memory"))
        curated <- ZIO.attemptBlocking(Files.exists(root.resolve("MEMORY.md")))
      yield assertTrue(
        result == ("" -> Chunk(DailyMemory(day, "first"))),
        entries == Set(s"$day.md"),
        !curated
      )
    },
    test(
      "commits curated content and checkpoint at the exact state path and retains progress after reopening"
    ) {
      for
        root <- temporaryDirectory
        outcome <- ZIO.scoped:
          for
            repository <- LocalMemoryRepository.open(root, WorkspaceTrust.HostControlled)
            store = repository.consolidation
            _        <- repository.daily.appendDaily(day, "first")
            snapshot <- store.snapshot
            applied  <- store.commit(snapshot.revision, "curated")
          yield applied
        state <- ZIO.attemptBlocking(Files.readString(root.resolve("memory/.consolidation_state")))
        checkpoint <- MemoryJournal.checkpoint(state)
        reopened <- ZIO.scoped:
          for
            repository <- LocalMemoryRepository.open(root, WorkspaceTrust.HostControlled)
            before     <- repository.consolidation.snapshot
            _          <- repository.daily.appendDaily(day, " second")
            after      <- repository.consolidation.snapshot
          yield (before.curated, before.pending, after.pending)
        entries <- children(root.resolve("memory"))
      yield assertTrue(
        outcome == ConsolidationCommit.Applied,
        checkpoint.generation == 1,
        reopened == ("curated", Chunk.empty, Chunk(DailyMemory(day, "first second"))),
        entries == Set(s"$day.md", ".consolidation_state")
      )
    },
    test(
      "stale daily and same-content commit revisions conflict without changing accepted progress"
    ) {
      for
        root <- temporaryDirectory
        result <- ZIO.scoped:
          for
            repository <- LocalMemoryRepository.open(root, WorkspaceTrust.HostControlled)
            store = repository.consolidation
            initial    <- store.snapshot
            _          <- repository.daily.appendDaily(day, "first")
            stale      <- store.commit(initial.revision, "wrong")
            fresh      <- store.snapshot
            first      <- store.commit(fresh.revision, "same")
            second     <- store.snapshot
            applied    <- store.commit(second.revision, "same")
            conflict   <- store.commit(second.revision, "wrong")
            finalState <- store.snapshot
          yield (
            stale,
            first,
            applied,
            conflict,
            second.revision != finalState.revision,
            finalState.curated,
            finalState.pending
          )
      yield assertTrue(
        result == (
          ConsolidationCommit.Conflict,
          ConsolidationCommit.Applied,
          ConsolidationCommit.Applied,
          ConsolidationCommit.Conflict,
          true,
          "same",
          Chunk.empty
        )
      )
    },
    test("generation advances after reopening even when replacement bytes are identical") {
      for
        root <- temporaryDirectory
        generations <- ZIO.foreach(1 to 3): _ =>
          ZIO.scoped:
            for
              repository <- LocalMemoryRepository.open(root, WorkspaceTrust.HostControlled)
              store = repository.consolidation
              snapshot <- store.snapshot
              _        <- store.commit(snapshot.revision, "same")
              text <- ZIO.attemptBlocking(
                Files.readString(root.resolve("memory/.consolidation_state"))
              )
              checkpoint <- MemoryJournal.checkpoint(text)
            yield checkpoint.generation
      yield assertTrue(generations == List(BigInt(1), BigInt(2), BigInt(3)))
    },
    suite("recovery publication matrix")(
      (for
        publication <- Publication.values.toList
        entry       <- EntryPoint.values.toList
      yield test(s"$entry recovers $publication before exposing a capability") {
        for
          root    <- temporaryDirectory
          journal <- fixture(root, publication)
          curated <- ZIO.scoped:
            entry match
              case EntryPoint.Repository =>
                LocalMemoryRepository
                  .open(root, WorkspaceTrust.HostControlled)
                  .flatMap(_.daily.readCurated)
              case EntryPoint.DailyOnly =>
                LocalDailyMemoryStore
                  .open(root, WorkspaceTrust.HostControlled)
                  .flatMap(_.readCurated)
          state <- ZIO.attemptBlocking(
            Files.readString(root.resolve("memory/.consolidation_state"))
          )
          checkpoint <- MemoryJournal.checkpoint(state)
          remaining  <- children(root.resolve("memory"))
          pending <- ZIO.scoped(
            LocalMemoryRepository
              .open(root, WorkspaceTrust.HostControlled)
              .flatMap(_.consolidation.snapshot.map(_.pending))
          )
        yield assertTrue(
          curated.contains(journal.afterCurated),
          checkpoint == journal.afterCheckpoint,
          !remaining.contains(".consolidation_journal"),
          pending.isEmpty
        )
      })*
    ),
    suite("every admitted operation recovers first")(
      Operation.values.toList.map(operation =>
        test(operation.toString) {
          for
            root <- temporaryDirectory
            result <- ZIO.scoped:
              for
                repository <- LocalMemoryRepository.open(root, WorkspaceTrust.HostControlled)
                store = repository.consolidation
                initial <- store.snapshot
                _       <- fixture(root, Publication.JournalOnly)
                _ <- operation match
                  case Operation.ReadCurated => repository.daily.readCurated.unit
                  case Operation.ReadDaily   => repository.daily.readDaily(day).unit
                  case Operation.AppendDaily => repository.daily.appendDaily(day, " appended")
                  case Operation.Snapshot    => store.snapshot.unit
                  case Operation.Commit      => store.commit(initial.revision, "wrong").unit
                curated <- ZIO.attemptBlocking(Files.readString(root.resolve("MEMORY.md")))
                exists <- ZIO.attemptBlocking(
                  Files.exists(root.resolve("memory/.consolidation_journal"))
                )
                snapshot <- store.snapshot
              yield (curated, exists, snapshot.pending)
          yield assertTrue(
            result._1 == "new curated",
            !result._2,
            result._3 == (if operation == Operation.AppendDaily then
                            Chunk(DailyMemory(day, "daily fact appended"))
                          else Chunk.empty)
          )
        }
      )*
    ),
    test("unexpected curated, checkpoint or daily inputs fail before any recovery write") {
      ZIO
        .foreach(Chunk(MemoryFile.Curated, MemoryFile.Checkpoint, MemoryFile.Daily(day))):
          changed =>
            for
              root  <- temporaryDirectory
              _     <- fixture(root, Publication.JournalOnly)
              files <- MemoryFiles.open(root, WorkspaceTrust.HostControlled)
              replacement =
                if changed == MemoryFile.Checkpoint then
                  oldCheckpoint.copy(generation = BigInt(99)).toJson
                else "external change"
              _ <- files.write(changed, replacement)
              before <- ZIO.foreach(
                Chunk(
                  MemoryFile.Curated,
                  MemoryFile.Checkpoint,
                  MemoryFile.Journal,
                  MemoryFile.Daily(day)
                )
              )(files.read)
              failed <- ZIO
                .scoped(LocalMemoryRepository.open(root, WorkspaceTrust.HostControlled))
                .either
              after <- ZIO.foreach(
                Chunk(
                  MemoryFile.Curated,
                  MemoryFile.Checkpoint,
                  MemoryFile.Journal,
                  MemoryFile.Daily(day)
                )
              )(files.read)
            yield failed.isLeft && before == after
        .map(results => assertTrue(results.forall(identity)))
    },
    test("an additional daily file prevents recovery without publishing curated content") {
      for
        root <- temporaryDirectory
        _    <- fixture(root, Publication.JournalOnly)
        _ <- ZIO.attemptBlocking(
          Files.writeString(root.resolve(s"memory/${day.plusDays(1)}.md"), "new")
        )
        failed <- ZIO.scoped(LocalMemoryRepository.open(root, WorkspaceTrust.HostControlled)).either
        curated <- ZIO.attemptBlocking(Files.readString(root.resolve("MEMORY.md")))
        journal <- ZIO.attemptBlocking(Files.exists(root.resolve("memory/.consolidation_journal")))
      yield assertTrue(failed.isLeft, curated == "old curated", journal)
    },
    test("missing and present-empty curated files remain distinct recovery inputs") {
      for
        root  <- temporaryDirectory
        files <- MemoryFiles.open(root, WorkspaceTrust.HostControlled)
        journal = MemoryJournal(
          1,
          None,
          None,
          Map.empty,
          "new",
          oldCheckpoint.copy(generation = BigInt(1))
        )
        _      <- files.write(MemoryFile.Journal, journal.toJson)
        _      <- files.write(MemoryFile.Curated, "")
        failed <- ZIO.scoped(LocalMemoryRepository.open(root, WorkspaceTrust.HostControlled)).either
        curated <- files.read(MemoryFile.Curated)
        state   <- files.read(MemoryFile.Checkpoint)
        pending <- files.read(MemoryFile.Journal)
      yield assertTrue(
        failed.isLeft,
        curated.contains(""),
        state.isEmpty,
        pending.contains(journal.toJson)
      )
    },
    test("malformed journal is retained and neither public entry point overwrites memory") {
      for
        root  <- temporaryDirectory
        _     <- fixture(root, Publication.JournalOnly)
        files <- MemoryFiles.open(root, WorkspaceTrust.HostControlled)
        _     <- files.write(MemoryFile.Journal, "{broken")
        full  <- ZIO.scoped(LocalMemoryRepository.open(root, WorkspaceTrust.HostControlled)).either
        daily <- ZIO.scoped(LocalDailyMemoryStore.open(root, WorkspaceTrust.HostControlled)).either
        curated <- files.read(MemoryFile.Curated)
        journal <- files.read(MemoryFile.Journal)
      yield assertTrue(
        full.isLeft,
        daily.isLeft,
        curated.contains("old curated"),
        journal.contains("{broken")
      )
    },
    test("legacy timestamp checkpoints fail closed instead of being reset or overwritten") {
      for
        root <- temporaryDirectory
        result <- ZIO.scoped:
          for
            repository <- LocalMemoryRepository.open(root, WorkspaceTrust.HostControlled)
            _ <- ZIO.attemptBlocking(
              Files.writeString(root.resolve("memory/.consolidation_state"), "2026-09-29T10:00:00Z")
            )
            failed <- repository.consolidation.snapshot.either
            state <- ZIO.attemptBlocking(
              Files.readString(root.resolve("memory/.consolidation_state"))
            )
          yield failed.isLeft && state == "2026-09-29T10:00:00Z"
      yield assertTrue(result)
    },
    test("both capabilities share one exclusive lease and reject operations after scope release") {
      for
        root <- temporaryDirectory
        repository <- ZIO.scoped:
          for
            repository <- LocalMemoryRepository.open(root, WorkspaceTrust.HostControlled)
            competitor <- ZIO
              .scoped(LocalDailyMemoryStore.open(root, WorkspaceTrust.HostControlled))
              .either
            _ <- ZIO
              .fail(AgentError.MemoryStorageError("Competing owner was admitted"))
              .unless(competitor.isLeft)
          yield repository
        daily    <- repository.daily.readCurated.either
        snapshot <- repository.consolidation.snapshot.either
        reopened <- ZIO.scoped(
          LocalDailyMemoryStore.open(root, WorkspaceTrust.HostControlled).flatMap(_.readCurated)
        )
      yield assertTrue(daily.isLeft, snapshot.isLeft, reopened.isEmpty)
    },
    test("stale lease blocks recovery and remains available for operator investigation") {
      for
        root <- temporaryDirectory
        _    <- fixture(root, Publication.JournalOnly)
        _ <- ZIO.attemptBlocking(
          Files.writeString(root.resolve("memory/.daily-writer.lease"), "former-owner")
        )
        failed <- ZIO.scoped(LocalMemoryRepository.open(root, WorkspaceTrust.HostControlled)).either
        curated <- ZIO.attemptBlocking(Files.readString(root.resolve("MEMORY.md")))
        lease   <- ZIO.attemptBlocking(Files.readString(root.resolve("memory/.daily-writer.lease")))
        journal <- ZIO.attemptBlocking(Files.exists(root.resolve("memory/.consolidation_journal")))
      yield assertTrue(failed.isLeft, curated == "old curated", lease == "former-owner", journal)
    },
    test(
      "consolidator integrates daily persistence, budget validation and persistent acknowledgement"
    ) {
      for
        root <- temporaryDirectory
        outcome <- ZIO.scoped:
          for
            repository <- LocalMemoryRepository.open(root, WorkspaceTrust.HostControlled)
            _          <- repository.daily.appendDaily(day, "remember me")
            consolidator <- MemoryConsolidator.make(
              repository.consolidation,
              MemoryExtractor(_ => ZIO.succeed("- remembered"))
            )
            result <- consolidator.consolidate
          yield result
        reopened <- ZIO.scoped(
          LocalMemoryRepository
            .open(root, WorkspaceTrust.HostControlled)
            .flatMap(_.consolidation.snapshot.map(value => value.curated -> value.pending))
        )
      yield assertTrue(
        outcome == ConsolidationResult.Committed,
        reopened == ("- remembered" -> Chunk.empty)
      )
    }
  )
