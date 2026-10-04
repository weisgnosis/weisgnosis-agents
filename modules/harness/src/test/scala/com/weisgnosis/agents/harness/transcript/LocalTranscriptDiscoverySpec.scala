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
package com.weisgnosis.agents.harness.transcript

import com.weisgnosis.agents.core.model.Role
import com.weisgnosis.agents.harness.eviction.WorkspaceTrust
import com.weisgnosis.agents.harness.memory.*
import com.weisgnosis.agents.harness.memory.MemoryTestSupport.{children, temporaryDirectory}
import java.nio.file.{Files, Path}
import java.nio.file.attribute.FileTime
import java.time.{Instant, LocalDate}
import zio.*
import zio.test.*

object LocalTranscriptDiscoverySpec extends ZIOSpecDefault:
  private enum LaterFailure:
    case Writer, Malformed
  private val old: Instant    = Instant.parse("2025-01-01T00:00:00Z")
  private val now: Instant    = Instant.parse("2026-04-01T00:00:00Z")
  private val recent: Instant = Instant.parse("2026-03-01T00:00:00Z")
  private val day: LocalDate  = LocalDate.of(2025, 1, 1)
  private val draft: TranscriptDraft = TranscriptDraft(
    Some(TranscriptKey.Message("entry")),
    TranscriptPayload.Message(Role.User, "fact", Chunk(TranscriptBlockType.Text))
  )

  private def directory(root: Path, name: String): Task[Path] =
    ZIO.attemptBlocking(Files.createDirectory(root.resolve(name)))
  private def modified(path: Path, at: Instant): Task[Unit] =
    ZIO
      .attemptBlocking(
        Files.setLastModifiedTime(path.resolve("transcript.jsonl"), FileTime.from(at))
      )
      .unit
  private def write(path: Path, at: Instant): ZIO[Any, Throwable, Unit] =
    TestClock.setTime(at) *> ZIO
      .scoped(
        LocalTranscriptStore
          .open(path, WorkspaceTrust.HostControlled)
          .flatMap(_.append(Chunk(draft)))
      )
      .unit *> modified(path, old)
  private def ids(result: TranscriptBatchResult): Chunk[String] = result match
    case TranscriptBatchResult.Disabled              => Chunk.empty
    case TranscriptBatchResult.Evaluated(_, targets) => targets.map(_.id.value)

  def spec: Spec[TestEnvironment & Scope, Any] = suite("LocalTranscriptDiscovery")(
    test(
      "cold sorted discovery refreshes each maintenance run and preserves recent recorded activity"
    ):
      for
        root <- temporaryDirectory
        discovered = LocalTranscriptDiscovery.discover(root, WorkspaceTrust.HostControlled)
        batch      <- TranscriptRetentionBatch.fromDiscovery(discovered)
        b          <- directory(root, "b-recent")
        a          <- directory(root, "a-old")
        empty      <- directory(root, "empty")
        _          <- ZIO.attemptBlocking(Files.writeString(root.resolve("notes.txt"), "unrelated"))
        _          <- write(a, old) *> write(b, recent)
        selected   <- discovered
        repository <- MemoryRepository.inMemory()
        _          <- repository.daily.appendDaily(day, "first")
        extractions <- Ref.make(0)
        memory <- MemoryMaintenance.make(
          repository,
          MemoryExtractor(_ => extractions.update(_ + 1).as("summary")),
          policy = MemoryRetentionPolicy.KeepAll
        )
        coordinator = new MemoryMaintenanceCoordinator(memory, batch)
        _      <- TestClock.setTime(now)
        first  <- coordinator.run
        aFiles <- children(a)
        bFiles <- children(b)
        c      <- directory(root, "c-newly-discovered")
        _ <- write(c, old) *> repository.daily.appendDaily(day, "second") *> TestClock.setTime(now)
        second     <- coordinator.run
        cFiles     <- children(c)
        emptyFiles <- children(empty)
        rootFiles  <- children(root)
        count      <- extractions.get
      yield assertTrue(
        selected.map(_.id.value) == Chunk("a-old", "b-recent"),
        ids(first.transcripts) == Chunk("a-old", "b-recent"),
        ids(second.transcripts) == Chunk("b-recent", "c-newly-discovered"),
        aFiles.isEmpty,
        cFiles.isEmpty,
        emptyFiles.isEmpty,
        bFiles == Set("transcript.jsonl"),
        rootFiles == Set("a-old", "b-recent", "c-newly-discovered", "empty", "notes.txt"),
        count == 2,
        first.transcripts match
          case TranscriptBatchResult.Evaluated(_, values) =>
            values.map(_.result) == Chunk(
              TranscriptPruneResult.Pruned(old, 1),
              TranscriptPruneResult.Retained(recent)
            )
          case _ => false
      )
    ,
    test(
      "limits trust traversal and unsafe payloads fail discovery before maintenance mutates any target"
    ):
      for
        root <- temporaryDirectory
        a    <- directory(root, "a")
        b    <- directory(root, "b")
        _    <- write(a, old) *> write(b, old) *> TestClock.setTime(now)
        _    <- ZIO.attemptBlocking(Files.writeString(root.resolve("notes.txt"), "also counted"))
        repository  <- MemoryRepository.inMemory()
        _           <- repository.daily.appendDaily(day, "pending")
        extractions <- Ref.make(0)
        memory <- MemoryMaintenance.make(
          repository,
          MemoryExtractor(_ => extractions.update(_ + 1).as("unexpected")),
          policy = MemoryRetentionPolicy.KeepAll
        )
        failures <- ZIO.foreach(
          Chunk(
            LocalTranscriptDiscoveryLimits(0, 2),
            LocalTranscriptDiscoveryLimits(2, 0),
            LocalTranscriptDiscoveryLimits(1, 2),
            LocalTranscriptDiscoveryLimits(2, 2),
            LocalTranscriptDiscoveryLimits(3, 1)
          )
        )(limits =>
          TranscriptRetentionBatch
            .fromDiscovery(
              LocalTranscriptDiscovery.discover(root, WorkspaceTrust.HostControlled, limits)
            )
            .flatMap(batch => new MemoryMaintenanceCoordinator(memory, batch).run)
            .exit
        )
        missing   <- ZIO.attempt(root.resolve("missing"))
        untrusted <- LocalTranscriptDiscovery.discover(missing, WorkspaceTrust.Untrusted).exit
        traversal <- ZIO.attempt(root.resolve("a").resolve("..").resolve("b"))
        traversed <- LocalTranscriptDiscovery
          .discover(traversal, WorkspaceTrust.HostControlled)
          .exit
        candidate <- directory(root, "invalid-payload")
        _ <- ZIO.attemptBlocking(Files.createDirectory(candidate.resolve("transcript.jsonl")))
        nonregular <- TranscriptRetentionBatch
          .fromDiscovery(LocalTranscriptDiscovery.discover(root, WorkspaceTrust.HostControlled))
          .flatMap(batch => new MemoryMaintenanceCoordinator(memory, batch).run)
          .exit
        sensitiveRoot <- temporaryDirectory
        _             <- directory(sensitiveRoot, ".ssh")
        sensitive <- LocalTranscriptDiscovery
          .discover(sensitiveRoot, WorkspaceTrust.HostControlled)
          .exit
        aFiles <- children(a)
        bFiles <- children(b)
        count  <- extractions.get
      yield assertTrue(
        failures.forall(_.isFailure),
        untrusted.isFailure,
        traversed.isFailure,
        nonregular.isFailure,
        sensitive.isFailure,
        count == 0,
        aFiles == Set("transcript.jsonl"),
        bFiles == Set("transcript.jsonl")
      )
    ,
    test(
      "discovered pruning retains writer protection and exposes partial progress on a later malformed body"
    ):
      ZIO
        .foreach(Chunk.fromArray(LaterFailure.values)): failure =>
          ZIO.scoped:
            for
              root <- temporaryDirectory
              a    <- directory(root, "a")
              b    <- directory(root, "b")
              c    <- directory(root, "c")
              _    <- write(a, old) *> write(b, old) *> write(c, old) *> TestClock.setTime(now)
              held <- failure match
                case LaterFailure.Writer =>
                  LocalTranscriptStore.open(b, WorkspaceTrust.HostControlled).map(Some(_))
                case LaterFailure.Malformed =>
                  ZIO
                    .attemptBlocking(Files.writeString(b.resolve("transcript.jsonl"), "malformed"))
                    .as(None)
              selected <- LocalTranscriptDiscovery.discover(root, WorkspaceTrust.HostControlled)
              batch    <- TranscriptRetentionBatch.make(selected)
              exit     <- batch.run.exit
              aFiles   <- children(a)
              bFiles   <- children(b)
              cFiles   <- children(c)
              retained <- held match
                case Some(store) =>
                  store
                    .append(Chunk(draft.copy(key = Some(TranscriptKey.Message("second")))))
                    .flatMap(_ => store.entries)
                    .map(_.size == 2)
                case None =>
                  ZIO
                    .attemptBlocking(Files.readString(b.resolve("transcript.jsonl")))
                    .map(_ == "malformed")
            yield assertTrue(
              selected.map(_.id.value) == Chunk("a", "b", "c"),
              exit.isFailure,
              aFiles.isEmpty,
              bFiles.contains("transcript.jsonl"),
              cFiles == Set("transcript.jsonl"),
              retained
            )
        .map(_.foldLeft(assertTrue(true))(_ && _))
  ) @@ TestAspect.timeout(20.seconds)
