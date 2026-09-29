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

object LocalDailyMemoryRetentionSpec extends ZIOSpecDefault:
  private val day: LocalDate    = LocalDate.of(2026, 1, 1)
  private val cutoff: LocalDate = LocalDate.of(2026, 4, 1)

  private def acknowledge(repository: MemoryRepository): IO[AgentError, Unit] =
    val store = repository.consolidation
    store.snapshot.flatMap(snapshot => store.commit(snapshot.revision, "curated")).unit

  private def archivePath(root: Path, archive: DailyMemoryArchive): Path =
    root
      .resolve("memory")
      .resolve("archive")
      .resolve(s"${archive.day}_${archive.fingerprint.value}.md")

  def spec: Spec[TestEnvironment & Scope, Any] = suite("LocalDailyMemoryRetentionSpec")(
    test("archived versions and curated memory survive owner close and reopen") {
      for
        root <- MemoryTestSupport.temporaryDirectory
        archived <- ZIO.scoped:
          for
            repository <- LocalMemoryRepository.open(root, WorkspaceTrust.HostControlled)
            _          <- repository.daily.appendDaily(day, "old confirmed")
            _          <- acknowledge(repository)
            _          <- repository.daily.appendDaily(day.plusDays(1), "old pending")
            archived   <- repository.retention.archiveBefore(cutoff)
          yield archived
        reopened <- ZIO.scoped:
          for
            repository <- LocalMemoryRepository.open(root, WorkspaceTrust.HostControlled)
            entries    <- repository.retention.archives
            contents   <- ZIO.foreach(entries)(repository.retention.readArchive)
            snapshot   <- repository.consolidation.snapshot
          yield (entries, contents, snapshot.curated, snapshot.pending)
      yield assertTrue(
        reopened._1 == archived,
        reopened._2 == Chunk(Some("old confirmed")),
        reopened._3 == "curated",
        reopened._4 == Chunk(DailyMemory(day.plusDays(1), "old pending"))
      )
    },
    test(
      "preflight rejects a conflicting later target before changing any daily file or checkpoint"
    ) {
      for
        root        <- MemoryTestSupport.temporaryDirectory
        repository  <- LocalMemoryRepository.open(root, WorkspaceTrust.HostControlled)
        _           <- repository.daily.appendDaily(day, "first")
        _           <- repository.daily.appendDaily(day.plusDays(1), "second")
        _           <- acknowledge(repository)
        fingerprint <- MemoryFingerprint.of("second")
        conflict = DailyMemoryArchive(day.plusDays(1), fingerprint)
        _ <- ZIO.attemptBlocking(Files.createDirectory(root.resolve("memory/archive")))
        _ <- ZIO.attemptBlocking(Files.writeString(archivePath(root, conflict), "foreign content"))
        store = repository.consolidation
        before    <- store.snapshot
        failed    <- repository.retention.archiveBefore(cutoff).exit
        after     <- store.snapshot
        first     <- repository.daily.readDaily(day)
        second    <- repository.daily.readDaily(day.plusDays(1))
        untouched <- ZIO.attemptBlocking(Files.readString(archivePath(root, conflict)))
      yield assertTrue(
        failed.isFailure,
        before.revision == after.revision,
        first.contains("first"),
        second.contains("second"),
        untouched == "foreign content"
      )
    },
    test("a non-directory archive location fails before acknowledging or deleting sources") {
      for
        root       <- MemoryTestSupport.temporaryDirectory
        repository <- LocalMemoryRepository.open(root, WorkspaceTrust.HostControlled)
        _          <- repository.daily.appendDaily(day, "confirmed")
        _          <- acknowledge(repository)
        _ <- ZIO.attemptBlocking(Files.writeString(root.resolve("memory/archive"), "obstruction"))
        store = repository.consolidation
        before <- store.snapshot
        failed <- repository.retention.archiveBefore(cutoff).exit
        after  <- store.snapshot
        daily  <- repository.daily.readDaily(day)
      yield assertTrue(
        failed.isFailure,
        before.revision == after.revision,
        daily.contains("confirmed")
      )
    },
    test("an existing nonregular archive entry is never replaced") {
      for
        root        <- MemoryTestSupport.temporaryDirectory
        repository  <- LocalMemoryRepository.open(root, WorkspaceTrust.HostControlled)
        _           <- repository.daily.appendDaily(day, "confirmed")
        _           <- acknowledge(repository)
        fingerprint <- MemoryFingerprint.of("confirmed")
        entry = DailyMemoryArchive(day, fingerprint)
        _      <- ZIO.attemptBlocking(Files.createDirectories(archivePath(root, entry)))
        failed <- repository.retention.archiveBefore(cutoff).exit
        intact <- ZIO.attemptBlocking(Files.isDirectory(archivePath(root, entry)))
        daily  <- repository.daily.readDaily(day)
      yield assertTrue(failed.isFailure, intact, daily.contains("confirmed"))
    },
    test("an archive copy published before checkpoint advancement is safely reused") {
      for
        root        <- MemoryTestSupport.temporaryDirectory
        repository  <- LocalMemoryRepository.open(root, WorkspaceTrust.HostControlled)
        _           <- repository.daily.appendDaily(day, "confirmed")
        _           <- acknowledge(repository)
        fingerprint <- MemoryFingerprint.of("confirmed")
        entry = DailyMemoryArchive(day, fingerprint)
        files    <- MemoryFiles.open(root, WorkspaceTrust.HostControlled)
        _        <- files.publishArchive(entry, "confirmed")
        before   <- repository.consolidation.snapshot
        archived <- repository.retention.archiveBefore(cutoff)
        all      <- repository.retention.archives
        daily    <- repository.daily.readDaily(day)
      yield assertTrue(
        before.pending.isEmpty,
        archived == Chunk(entry),
        all == archived,
        daily.isEmpty
      )
    },
    test("checkpoint advancement before source removal leaves surviving daily content pending") {
      for
        root        <- MemoryTestSupport.temporaryDirectory
        repository  <- LocalMemoryRepository.open(root, WorkspaceTrust.HostControlled)
        _           <- repository.daily.appendDaily(day, "confirmed")
        _           <- acknowledge(repository)
        fingerprint <- MemoryFingerprint.of("confirmed")
        entry = DailyMemoryArchive(day, fingerprint)
        files <- MemoryFiles.open(root, WorkspaceTrust.HostControlled)
        _     <- files.publishArchive(entry, "confirmed")
        store = repository.consolidation
        before <- store.snapshot
        encoded <- files
          .read(MemoryFile.Checkpoint)
          .someOrFail(AgentError.MemoryStorageError("Missing fixture checkpoint"))
        checkpoint  <- MemoryJournal.checkpoint(encoded)
        transaction <- Random.nextUUID.map(_.toString)
        next = checkpoint.copy(
          generation = checkpoint.generation + 1,
          transaction = transaction,
          acknowledged = checkpoint.acknowledged - day.toString
        )
        _        <- files.write(MemoryFile.Checkpoint, next.toJson)
        pending  <- store.snapshot
        stale    <- store.commit(before.revision, "stale")
        refused  <- repository.retention.archiveBefore(cutoff)
        _        <- acknowledge(repository)
        archived <- repository.retention.archiveBefore(cutoff)
        content  <- repository.retention.readArchive(entry)
      yield assertTrue(
        pending.pending == Chunk(DailyMemory(day, "confirmed")),
        stale == ConsolidationCommit.Conflict,
        refused.isEmpty,
        archived == Chunk(entry),
        content.contains("confirmed")
      )
    },
    test("source removal requires an intact verified archive and unchanged source") {
      for
        root        <- MemoryTestSupport.temporaryDirectory
        files       <- MemoryFiles.open(root, WorkspaceTrust.HostControlled)
        _           <- files.write(MemoryFile.Daily(day), "confirmed")
        fingerprint <- MemoryFingerprint.of("confirmed")
        entry = DailyMemoryArchive(day, fingerprint)
        absent  <- files.removeArchivedDaily(entry, "confirmed").exit
        _       <- files.publishArchive(entry, "confirmed")
        _       <- files.write(MemoryFile.Daily(day), "new facts")
        changed <- files.removeArchivedDaily(entry, "confirmed").exit
        daily   <- files.read(MemoryFile.Daily(day))
        archive <- files.readArchive(entry)
      yield assertTrue(
        absent.isFailure,
        changed.isFailure,
        daily.contains("new facts"),
        archive.contains("confirmed")
      )
    },
    test("archive enumeration ignores foreign names but rejects corrupted recognized payloads") {
      for
        root       <- MemoryTestSupport.temporaryDirectory
        repository <- LocalMemoryRepository.open(root, WorkspaceTrust.HostControlled)
        _          <- repository.daily.appendDaily(day, "confirmed")
        _          <- acknowledge(repository)
        entries    <- repository.retention.archiveBefore(cutoff)
        _ <- ZIO.attemptBlocking(
          Files.writeString(root.resolve("memory/archive/notes.md"), "foreign")
        )
        listed <- repository.retention.archives
        _ <- ZIO.foreachDiscard(entries)(entry =>
          ZIO.attemptBlocking(Files.writeString(archivePath(root, entry), "corrupt"))
        )
        failed <- repository.retention.archives.exit
      yield assertTrue(listed == entries, failed.isFailure)
    },
    test(
      "retention recovers an already published consolidation journal before selecting eligible files"
    ) {
      for
        root         <- MemoryTestSupport.temporaryDirectory
        repository   <- LocalMemoryRepository.open(root, WorkspaceTrust.HostControlled)
        _            <- repository.daily.appendDaily(day, "confirmed by pending commit")
        files        <- MemoryFiles.open(root, WorkspaceTrust.HostControlled)
        fingerprints <- MemoryJournal.fingerprints(Map(day -> "confirmed by pending commit"))
        transaction  <- Random.nextUUID.map(_.toString)
        checkpoint = MemoryCheckpoint(1, BigInt(1), transaction, fingerprints)
        journal    = MemoryJournal(1, None, None, fingerprints, "recovered curated", checkpoint)
        _                <- files.write(MemoryFile.Journal, journal.toJson)
        archived         <- repository.retention.archiveBefore(cutoff)
        curated          <- repository.daily.readCurated
        remainingJournal <- files.read(MemoryFile.Journal)
      yield assertTrue(
        archived.size == 1,
        curated.contains("recovered curated"),
        remainingJournal.isEmpty
      )
    },
    test("retention capabilities reject operations after owner closure") {
      for
        root        <- MemoryTestSupport.temporaryDirectory
        repository  <- ZIO.scoped(LocalMemoryRepository.open(root, WorkspaceTrust.HostControlled))
        fingerprint <- MemoryFingerprint.of("missing")
        archive     <- repository.retention.archiveBefore(cutoff).exit
        listed      <- repository.retention.archives.exit
        read        <- repository.retention.readArchive(DailyMemoryArchive(day, fingerprint)).exit
      yield assertTrue(archive.isFailure, listed.isFailure, read.isFailure)
    }
  )
