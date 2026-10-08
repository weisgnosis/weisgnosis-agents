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
import com.weisgnosis.agents.harness.storage.WorkspaceLease
import java.nio.file.Path
import java.time.{Instant, LocalDate}
import zio.*
import zio.json.*

enum WatermarkMigration derives JsonCodec:
  case Missing, AlreadyCurrent
  case Migrated(previous: Instant)

/**
 * Cooperative, scoped ownership with roll-forward recovery of consolidation commits.
 * Publishing the journal commits the candidate to recovery. A subsequent I/O failure can therefore
 * have an uncertain acknowledgement; callers must inspect storage before invoking the model again.
 */
object LocalMemoryRepository:
  final private case class Version(
      curated: Option[String],
      checkpoint: Option[MemoryCheckpoint],
      daily: Map[String, String]
  )

  final private case class State(version: Version, daily: Map[LocalDate, String])

  /** The workspace and all ancestors must be protected against uncoordinated mutation. */
  def open(workspace: Path, trust: WorkspaceTrust): ZIO[Scope, AgentError, MemoryRepository] =
    WorkspaceLease.initialize:
      for
        files <- MemoryFiles.open(workspace, trust)
        lease <- DailyMemoryLease.open(files.directory)
        backend = new Backend(files, lease)
        _ <- backend.owned(ZIO.unit)
      yield backend.repository

  /**
   * Explicitly replaces a legacy timestamp with empty content acknowledgements. Curated and daily
   * data remain untouched; every daily input must be reconsidered. A pending journal blocks this
   * operation instead of being recovered. Failure or interruption can follow checkpoint publication.
   */
  def migrateLegacyWatermark(
      workspace: Path,
      trust: WorkspaceTrust
  ): IO[AgentError, WatermarkMigration] =
    ZIO.scoped:
      for
        files <- MemoryFiles.open(workspace, trust)
        lease <- DailyMemoryLease.open(files.directory)
        result <- lease.protect:
          for
            _       <- files.validateLocation
            journal <- files.read(MemoryFile.Journal)
            _ <- ZIO
              .fail(
                AgentError.MemoryStorageError("A consolidation journal blocks watermark migration")
              )
              .when(journal.nonEmpty)
            stored <- files.read(MemoryFile.Checkpoint)
            outcome <- stored match
              case None => ZIO.succeed(WatermarkMigration.Missing)
              case Some(text) =>
                MemoryJournal
                  .checkpoint(text)
                  .as(WatermarkMigration.AlreadyCurrent)
                  .catchAll(_ => migrateTimestamp(files, text))
          yield outcome
      yield result

  private def migrateTimestamp(
      files: MemoryFiles,
      text: String
  ): IO[AgentError, WatermarkMigration] =
    val invalid = AgentError.MemoryStorageError("Unrecognized legacy consolidation watermark")
    for
      previous    <- ZIO.attempt(Instant.parse(text.strip)).mapError(_ => invalid)
      _           <- ZIO.fail(invalid).unless(previous.toString == text.strip)
      transaction <- Random.nextUUID.map(_.toString)
      checkpoint = MemoryCheckpoint(1, BigInt(1), transaction, Map.empty)
      _ <- files.write(MemoryFile.Checkpoint, checkpoint.toJson)
      stored <- files
        .read(MemoryFile.Checkpoint)
        .someOrFail(
          AgentError.MemoryStorageError("Migrated consolidation checkpoint is missing")
        )
      verified <- MemoryJournal.checkpoint(stored)
      _ <- ZIO
        .fail(
          AgentError.MemoryStorageError("Watermark migration verification failed")
        )
        .unless(verified == checkpoint)
    yield WatermarkMigration.Migrated(previous)

  final private class Backend(files: MemoryFiles, lease: DailyMemoryLease):
    def owned[A](operation: IO[AgentError, A]): IO[AgentError, A] =
      lease.protect(files.validateLocation *> recover *> operation)

    private val daily: DailyMemoryStore = new DailyMemoryStore:
      def readCurated: IO[AgentError, Option[String]] = owned(files.read(MemoryFile.Curated))
      def readDaily(day: LocalDate): IO[AgentError, Option[String]] = owned(
        files.read(MemoryFile.Daily(day))
      )
      def appendDaily(day: LocalDate, entry: String): IO[AgentError, Unit] = owned:
        if entry.isEmpty then ZIO.unit
        else
          files
            .read(MemoryFile.Daily(day))
            .flatMap(previous => files.write(MemoryFile.Daily(day), previous.getOrElse("") + entry))

    private val consolidation: ConsolidationStore = new ConsolidationStore:
      type Revision = Version
      def snapshot: IO[AgentError, ConsolidationSnapshot[Revision]] = owned:
        current.map: state =>
          val acknowledged =
            state.version.checkpoint.fold(Map.empty[String, String])(_.acknowledged)
          val pending = state.daily.toList.collect:
            case (day, content)
                if acknowledged.get(day.toString) != state.version.daily.get(day.toString) =>
              DailyMemory(day, content)
          ConsolidationSnapshot(
            state.version,
            state.version.curated.getOrElse(""),
            Chunk.fromIterable(pending.sortBy(_.day.toEpochDay))
          )

      def commit(expected: Revision, content: String): IO[AgentError, ConsolidationCommit] =
        owned:
          current.flatMap: state =>
            if state.version != expected then ZIO.succeed(ConsolidationCommit.Conflict)
            else
              for
                transaction <- Random.nextUUID.map(_.toString)
                checkpoint = MemoryCheckpoint(
                  1,
                  expected.checkpoint.fold(BigInt(0))(_.generation) + 1,
                  transaction,
                  expected.daily
                )
                journal = MemoryJournal(
                  1,
                  expected.curated,
                  expected.checkpoint,
                  expected.daily,
                  content,
                  checkpoint
                )
                _ <- files.write(MemoryFile.Journal, journal.toJson)
                _ <- finish(journal)
              yield ConsolidationCommit.Applied
    private val retention: DailyMemoryRetention = new DailyMemoryRetention:
      def archiveBefore(cutoff: LocalDate): IO[AgentError, Chunk[DailyMemoryArchive]] = owned:
        for
          state <- current
          candidates <- ZIO.foreach(state.version.checkpoint): checkpoint =>
            val eligible = state.daily.toList
              .filter((day, _) =>
                day.isBefore(cutoff) && checkpoint.acknowledged
                  .get(day.toString)
                  .exists(digest => state.version.daily.get(day.toString).contains(digest))
              )
              .sortBy(_._1.toEpochDay)
            for
              entries <- ZIO.foreach(eligible): (day, content) =>
                MemoryFingerprint
                  .of(content)
                  .map(fingerprint => DailyMemoryArchive(day, fingerprint) -> content)
              _ <- ZIO.foreachDiscard(entries)((archive, content) =>
                files.validateArchive(archive, content)
              )
              _ <- ZIO.foreachDiscard(entries)((archive, content) =>
                files.publishArchive(archive, content)
              )
              _ <- ZIO.when(entries.nonEmpty):
                for
                  transaction <- Random.nextUUID.map(_.toString)
                  next = checkpoint.copy(
                    generation = checkpoint.generation + 1,
                    transaction = transaction,
                    acknowledged = checkpoint.acknowledged -- entries.map(_._1.day.toString)
                  )
                  _ <- files.write(MemoryFile.Checkpoint, next.toJson)
                  _ <- ZIO.foreachDiscard(entries)((archive, content) =>
                    files.removeArchivedDaily(archive, content)
                  )
                yield ()
            yield Chunk.fromIterable(entries.map(_._1))
        yield candidates.getOrElse(Chunk.empty)

      def archives: IO[AgentError, Chunk[DailyMemoryArchive]] = owned(files.archives)
      def readArchive(archive: DailyMemoryArchive): IO[AgentError, Option[String]] =
        owned(files.readArchive(archive))

    val repository: MemoryRepository = new MemoryRepository(daily, consolidation, retention)

    private def current: IO[AgentError, State] =
      for
        curated      <- files.read(MemoryFile.Curated)
        checkpoint   <- readCheckpoint
        daily        <- files.dailyContents
        fingerprints <- MemoryJournal.fingerprints(daily)
      yield State(Version(curated, checkpoint, fingerprints), daily)

    private def readCheckpoint: IO[AgentError, Option[MemoryCheckpoint]] =
      files
        .read(MemoryFile.Checkpoint)
        .flatMap(value => ZIO.foreach(value)(MemoryJournal.checkpoint))

    private def recover: IO[AgentError, Unit] =
      files
        .read(MemoryFile.Journal)
        .flatMap(value =>
          ZIO.foreachDiscard(value)(text => MemoryJournal.decode(text).flatMap(finish))
        )

    private def finish(journal: MemoryJournal): IO[AgentError, Unit] =
      for
        state <- current
        _ <- ZIO
          .fail(
            AgentError.MemoryStorageError("Consolidation recovery inputs changed; journal retained")
          )
          .unless(
            (state.version.curated == journal.beforeCurated || state.version.curated
              .contains(journal.afterCurated)) &&
              (state.version.checkpoint == journal.beforeCheckpoint || state.version.checkpoint
                .contains(journal.afterCheckpoint)) &&
              state.version.daily == journal.daily
          )
        _ <- files
          .write(MemoryFile.Curated, journal.afterCurated)
          .unless(state.version.curated.contains(journal.afterCurated))
        _ <- files
          .write(MemoryFile.Checkpoint, journal.afterCheckpoint.toJson)
          .unless(state.version.checkpoint.contains(journal.afterCheckpoint))
        curated    <- files.read(MemoryFile.Curated)
        checkpoint <- readCheckpoint
        _ <- ZIO
          .fail(
            AgentError.MemoryStorageError(
              "Consolidation recovery verification failed; journal retained"
            )
          )
          .unless(
            curated.contains(journal.afterCurated) && checkpoint.contains(journal.afterCheckpoint)
          )
        _ <- files.removeJournal
      yield ()
