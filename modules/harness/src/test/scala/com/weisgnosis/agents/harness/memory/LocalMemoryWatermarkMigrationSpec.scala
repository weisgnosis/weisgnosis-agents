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
import java.time.{Instant, LocalDate}
import zio.*
import zio.json.*
import zio.test.*
import MemoryTestSupport.temporaryDirectory

object LocalMemoryWatermarkMigrationSpec extends ZIOSpecDefault:
  private val trust: WorkspaceTrust = WorkspaceTrust.HostControlled
  private val previous: Instant     = Instant.parse("2025-06-15T12:00:00Z")
  private val day: LocalDate        = LocalDate.of(2025, 6, 14)
  private val current: MemoryCheckpoint =
    MemoryCheckpoint(1, BigInt(7), "11111111-1111-1111-1111-111111111111", Map.empty)

  def spec: Spec[TestEnvironment & Scope, Any] = suite("LocalMemoryWatermarkMigrationSpec")(
    test(
      "explicit migration preserves content and requires exact acknowledgement of every daily input"
    ):
      for
        root  <- temporaryDirectory
        files <- MemoryFiles.open(root, trust)
        _     <- files.write(MemoryFile.Curated, "  existing curated\n")
        _     <- files.write(MemoryFile.Daily(day), "already mentioned\n")
        _     <- files.write(MemoryFile.Daily(day.plusDays(2)), "new fact\n")
        legacy = s" \t$previous\r\n"
        _ <- files.write(MemoryFile.Checkpoint, legacy)
        strict <- ZIO
          .scoped(
            LocalMemoryRepository.open(root, trust).flatMap(_.consolidation.snapshot.unit)
          )
          .exit
        before <- files.read(MemoryFile.Checkpoint)
        migration = LocalMemoryRepository.migrateLegacyWatermark(root, trust)
        cold   <- files.read(MemoryFile.Checkpoint)
        result <- migration
        published <- files
          .read(MemoryFile.Checkpoint)
          .someOrFail(AgentError.MemoryStorageError("Expected migrated checkpoint"))
        checkpoint <- MemoryJournal.checkpoint(published)
        curated    <- files.read(MemoryFile.Curated)
        daily      <- files.dailyContents
        repeated   <- migration
        unchanged  <- files.read(MemoryFile.Checkpoint)
        inputs     <- Ref.make(Chunk.empty[String])
        observed <- ZIO.scoped:
          for
            repository <- LocalMemoryRepository.open(root, trust)
            snapshot   <- repository.consolidation.snapshot
            archived   <- repository.retention.archiveBefore(day.plusDays(10))
            consolidator <- MemoryConsolidator.make(
              repository.consolidation,
              MemoryExtractor(messages =>
                inputs.set(messages.map(_.text)) *> ZIO.succeed("- reconsidered facts")
              )
            )
            outcome <- consolidator.consolidate
            after   <- repository.consolidation.snapshot
          yield (snapshot.curated, snapshot.pending, archived, outcome, after.pending)
        extraction <- inputs.get
        reopened <- ZIO.scoped(
          LocalMemoryRepository.open(root, trust).flatMap(_.consolidation.snapshot.map(_.pending))
        )
      yield assertTrue(
        strict.causeOption.exists(_.failures.exists(_.isInstanceOf[AgentError.MemoryStorageError])),
        before.contains(legacy),
        cold.contains(legacy),
        result == WatermarkMigration.Migrated(previous),
        result.toJson.fromJson[WatermarkMigration] == Right(result),
        checkpoint.generation == 1,
        checkpoint.acknowledged.isEmpty,
        curated.contains("  existing curated\n"),
        daily == Map(day -> "already mentioned\n", day.plusDays(2) -> "new fact\n"),
        repeated == WatermarkMigration.AlreadyCurrent,
        unchanged.contains(published),
        observed._1 == "  existing curated\n",
        observed._2 == Chunk(
          DailyMemory(day, "already mentioned\n"),
          DailyMemory(day.plusDays(2), "new fact\n")
        ),
        observed._3.isEmpty,
        observed._4 == ConsolidationResult.Committed,
        observed._5.isEmpty,
        extraction.size == 2,
        extraction.exists(text =>
          text.contains("existing curated") && text.contains("already mentioned") && text.contains(
            "new fact"
          )
        ),
        reopened.isEmpty
      )
    ,
    test("missing and current checkpoints remain unchanged while unknown formats fail closed"):
      val invalid = AgentError.MemoryStorageError("Unrecognized legacy consolidation watermark")
      val cases: Chunk[(Option[String], Either[AgentError, WatermarkMigration])] = Chunk(
        None                                   -> Right(WatermarkMigration.Missing),
        Some(s" \n${current.toJson}\n")        -> Right(WatermarkMigration.AlreadyCurrent),
        Some("")                               -> Left(invalid),
        Some(" \n")                            -> Left(invalid),
        Some("{broken")                        -> Left(invalid),
        Some(current.copy(version = 2).toJson) -> Left(invalid),
        Some(current.copy(generation = BigInt(0)).toJson) -> Left(invalid),
        Some(s"\"$previous\"")                            -> Left(invalid),
        Some("2025-06-15T14:00:00+02:00")                 -> Left(invalid)
      )
      ZIO
        .foreach(cases): (stored, expected) =>
          for
            root   <- temporaryDirectory
            files  <- MemoryFiles.open(root, trust)
            _      <- ZIO.foreachDiscard(stored)(files.write(MemoryFile.Checkpoint, _))
            result <- LocalMemoryRepository.migrateLegacyWatermark(root, trust).either
            after  <- files.read(MemoryFile.Checkpoint)
            _      <- ZIO.scoped(DailyMemoryLease.open(files.directory)).unit
          yield assertTrue(result == expected, after == stored)
        .map(_.foldLeft(assertTrue(true))(_ && _))
    ,
    test("any journal blocks migration without recovery and an existing owner excludes migration"):
      for
        fingerprints <- MemoryJournal.fingerprints(Map(day -> "daily"))
        journal = MemoryJournal(
          1,
          Some("curated"),
          None,
          fingerprints,
          "must not publish",
          current.copy(generation = BigInt(1), acknowledged = fingerprints)
        )
        cases: Chunk[(String, Option[String])] = Chunk(
          journal.toJson -> None,
          "{broken"      -> Some(current.toJson),
          ""             -> Some(previous.toString)
        )
        blocked <- ZIO.foreach(cases): (text, stored) =>
          for
            root       <- temporaryDirectory
            files      <- MemoryFiles.open(root, trust)
            _          <- files.write(MemoryFile.Curated, "curated")
            _          <- files.write(MemoryFile.Daily(day), "daily")
            _          <- ZIO.foreachDiscard(stored)(files.write(MemoryFile.Checkpoint, _))
            _          <- files.write(MemoryFile.Journal, text)
            result     <- LocalMemoryRepository.migrateLegacyWatermark(root, trust).either
            retained   <- files.read(MemoryFile.Journal)
            checkpoint <- files.read(MemoryFile.Checkpoint)
            curated    <- files.read(MemoryFile.Curated)
            daily      <- files.read(MemoryFile.Daily(day))
            _          <- ZIO.scoped(DailyMemoryLease.open(files.directory)).unit
          yield assertTrue(
            result == Left(
              AgentError.MemoryStorageError("A consolidation journal blocks watermark migration")
            ),
            retained.contains(text),
            checkpoint == stored,
            curated.contains("curated"),
            daily.contains("daily")
          )
        root  <- temporaryDirectory
        files <- MemoryFiles.open(root, trust)
        held <- ZIO.scoped:
          for
            _          <- LocalMemoryRepository.open(root, trust)
            _          <- files.write(MemoryFile.Checkpoint, previous.toString)
            result     <- LocalMemoryRepository.migrateLegacyWatermark(root, trust).exit
            checkpoint <- files.read(MemoryFile.Checkpoint)
          yield (result, checkpoint)
        migrated <- LocalMemoryRepository.migrateLegacyWatermark(root, trust)
        untrusted <- LocalMemoryRepository
          .migrateLegacyWatermark(root, WorkspaceTrust.Untrusted)
          .exit
      yield blocked.foldLeft(assertTrue(true))(_ && _) && assertTrue(
        held._1.causeOption
          .exists(_.failures.exists(_.isInstanceOf[AgentError.MemoryStorageError])),
        held._2.contains(previous.toString),
        migrated == WatermarkMigration.Migrated(previous),
        untrusted.causeOption.exists(_.failures.exists(_.isInstanceOf[AgentError.PermissionDenied]))
      )
  )
