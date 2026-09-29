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
import com.weisgnosis.agents.harness.memory.MemoryTestSupport.{children, temporaryDirectory}
import java.nio.file.{Files, Path}
import java.nio.file.attribute.FileTime
import java.time.Instant
import zio.*
import zio.json.*
import zio.test.*

object LocalTranscriptRetentionSpec extends ZIOSpecDefault:
  private val old: Instant    = Instant.parse("2025-01-01T00:00:00Z")
  private val cutoff: Instant = Instant.parse("2026-01-01T00:00:00Z")
  private val recent: Instant = Instant.parse("2026-02-01T00:00:00Z")

  private def draft(id: String): TranscriptDraft = TranscriptDraft(
    Some(TranscriptKey.Message(id)),
    TranscriptPayload.Message(Role.User, "recorded fact", Chunk(TranscriptBlockType.Text))
  )

  private def modified(root: Path, at: Instant): Task[Unit] =
    ZIO
      .attemptBlocking(
        Files.setLastModifiedTime(root.resolve("transcript.jsonl"), FileTime.from(at))
      )
      .unit

  private def write(root: Path, at: Instant): ZIO[Any, Throwable, Chunk[TranscriptEntry]] =
    TestClock.setTime(at) *> ZIO.scoped:
      LocalTranscriptStore
        .open(root, WorkspaceTrust.HostControlled)
        .flatMap(_.append(Chunk(draft("stable"))))

  private def prune(root: Path): ZIO[Any, Throwable, TranscriptPruneResult] =
    LocalTranscriptRetention.prune(root, WorkspaceTrust.HostControlled, cutoff)

  def spec: Spec[TestEnvironment & Scope, Any] = suite("LocalTranscriptRetentionSpec")(
    test("missing payload is distinguished from a valid expired empty file") {
      for
        root    <- temporaryDirectory
        missing <- prune(root)
        before  <- children(root)
        _       <- ZIO.attemptBlocking(Files.writeString(root.resolve("transcript.jsonl"), ""))
        _       <- modified(root, old)
        empty   <- prune(root)
        after   <- children(root)
      yield assertTrue(
        missing == TranscriptPruneResult.Missing,
        before.isEmpty,
        empty == TranscriptPruneResult.Pruned(old, 0),
        after.isEmpty
      )
    },
    test("strict cutoff equality retains the transcript and round trips the typed outcome") {
      for
        root   <- temporaryDirectory
        _      <- write(root, old)
        _      <- modified(root, cutoff)
        result <- prune(root)
        names  <- children(root)
      yield assertTrue(
        result == TranscriptPruneResult.Retained(cutoff),
        names == Set("transcript.jsonl"),
        result.toJson.fromJson[TranscriptPruneResult] == Right(result)
      )
    },
    test("recent modification time preserves a file whose entries are old") {
      for
        root   <- temporaryDirectory
        _      <- write(root, old)
        _      <- modified(root, recent)
        result <- prune(root)
      yield assertTrue(result == TranscriptPruneResult.Retained(recent))
    },
    test("recent recorded activity preserves a file whose modification time is old") {
      for
        root   <- temporaryDirectory
        _      <- write(root, recent)
        _      <- modified(root, old)
        result <- prune(root)
      yield assertTrue(result == TranscriptPruneResult.Retained(recent))
    },
    test("all entry timestamps participate even when the recording clock moved backwards") {
      for
        root <- temporaryDirectory
        _ <- ZIO.scoped:
          for
            store <- LocalTranscriptStore.open(root, WorkspaceTrust.HostControlled)
            _     <- TestClock.setTime(recent)
            _     <- store.append(Chunk(draft("recent first")))
            _     <- TestClock.setTime(old)
            _     <- store.append(Chunk(draft("older last")))
          yield ()
        _      <- modified(root, old)
        result <- prune(root)
      yield assertTrue(result == TranscriptPruneResult.Retained(recent))
    },
    test("future recorded timestamps conservatively prevent expiration") {
      for
        root   <- temporaryDirectory
        _      <- write(root, Instant.MAX)
        _      <- modified(root, old)
        result <- prune(root)
      yield assertTrue(result == TranscriptPruneResult.Retained(Instant.MAX))
    },
    test("malformed and broken-chain logs remain intact even when old") {
      for
        malformedRoot <- temporaryDirectory
        _ <- ZIO.attemptBlocking(
          Files.writeString(malformedRoot.resolve("transcript.jsonl"), "not JSON")
        )
        _         <- modified(malformedRoot, old)
        malformed <- prune(malformedRoot).exit
        badText <- ZIO.attemptBlocking(Files.readString(malformedRoot.resolve("transcript.jsonl")))
        brokenRoot <- temporaryDirectory
        _          <- TestClock.setTime(old)
        _ <- ZIO.scoped(
          LocalTranscriptStore
            .open(brokenRoot, WorkspaceTrust.HostControlled)
            .flatMap(_.append(Chunk(draft("first"), draft("second"))))
        )
        original <- ZIO.attemptBlocking(Files.readString(brokenRoot.resolve("transcript.jsonl")))
        brokenText = original.linesIterator.drop(1).mkString("", "\n", "\n")
        _ <- ZIO.attemptBlocking(
          Files.writeString(brokenRoot.resolve("transcript.jsonl"), brokenText)
        )
        _        <- modified(brokenRoot, old)
        broken   <- prune(brokenRoot).exit
        retained <- ZIO.attemptBlocking(Files.readString(brokenRoot.resolve("transcript.jsonl")))
        malformedNames <- children(malformedRoot)
        brokenNames    <- children(brokenRoot)
      yield assertTrue(
        malformed.isFailure,
        broken.isFailure,
        badText == "not JSON",
        retained == brokenText,
        malformedNames == Set("transcript.jsonl"),
        brokenNames == Set("transcript.jsonl")
      )
    },
    test("an active writer blocks pruning and remains able to append until its scope closes") {
      for
        root <- temporaryDirectory
        _    <- TestClock.setTime(old)
        refused <- ZIO.scoped:
          for
            store   <- LocalTranscriptStore.open(root, WorkspaceTrust.HostControlled)
            _       <- store.append(Chunk(draft("first")))
            _       <- modified(root, old)
            exit    <- prune(root).exit
            _       <- store.append(Chunk(draft("second")))
            entries <- store.entries
          yield exit.isFailure && entries.size == 2
        _         <- modified(root, old)
        result    <- prune(root)
        remaining <- children(root)
      yield assertTrue(refused, result == TranscriptPruneResult.Pruned(old, 2), remaining.isEmpty)
    },
    test("a stale lease is never reclaimed or removed by pruning") {
      for
        root <- temporaryDirectory
        _    <- write(root, old)
        _    <- modified(root, old)
        _ <- ZIO.attemptBlocking(
          Files.writeString(root.resolve(".transcript-writer.lease"), "stale owner")
        )
        exit  <- prune(root).exit
        lease <- ZIO.attemptBlocking(Files.readString(root.resolve(".transcript-writer.lease")))
        names <- children(root)
      yield assertTrue(
        exit.isFailure,
        lease == "stale owner",
        names == Set("transcript.jsonl", ".transcript-writer.lease")
      )
    },
    test("pruning deletes only the selected payload and preserves unrelated files and directories") {
      for
        root <- temporaryDirectory
        _    <- write(root, old)
        _    <- modified(root, old)
        _    <- ZIO.attemptBlocking(Files.writeString(root.resolve("notes.md"), "keep me"))
        _    <- ZIO.attemptBlocking(Files.createDirectory(root.resolve("other-session")))
        _ <- ZIO.attemptBlocking(
          Files.writeString(root.resolve("other-session/transcript.jsonl"), "foreign session")
        )
        result <- prune(root)
        names  <- children(root)
        other <- ZIO.attemptBlocking(
          Files.readString(root.resolve("other-session/transcript.jsonl"))
        )
      yield assertTrue(
        result == TranscriptPruneResult.Pruned(old, 1),
        names == Set("notes.md", "other-session"),
        other == "foreign session"
      )
    },
    test("pruning retires deduplication keys and reopening starts a fresh parent chain") {
      for
        root   <- temporaryDirectory
        first  <- write(root, old)
        _      <- modified(root, old)
        result <- prune(root)
        second <- write(root, recent)
      yield assertTrue(
        result == TranscriptPruneResult.Pruned(old, 1),
        second.size == 1,
        second.forall(_.parentId.isEmpty),
        second.map(_.id) != first.map(_.id),
        second.flatMap(_.key) == first.flatMap(_.key)
      )
    },
    test("nonregular payloads and unsafe or missing directories are rejected without deletion") {
      for
        root       <- temporaryDirectory
        _          <- ZIO.attemptBlocking(Files.createDirectory(root.resolve("transcript.jsonl")))
        nonregular <- prune(root).exit
        untrusted  <- LocalTranscriptRetention.prune(root, WorkspaceTrust.Untrusted, cutoff).exit
        traversal  <- prune(root.resolve("..").resolve(root.getFileName)).exit
        missing    <- prune(root.resolve("missing")).exit
        sensitive  <- ZIO.attemptBlocking(Files.createDirectory(root.resolve(".ssh")))
        denied     <- prune(sensitive).exit
        remains    <- ZIO.attemptBlocking(Files.isDirectory(root.resolve("transcript.jsonl")))
        names      <- children(root)
      yield assertTrue(
        nonregular.isFailure,
        untrusted.isFailure,
        traversal.isFailure,
        missing.isFailure,
        denied.isFailure,
        remains,
        !names.contains(".transcript-writer.lease")
      )
    },
    test("competing pruning attempts delete at most once and leave no ownership file") {
      for
        root    <- temporaryDirectory
        _       <- write(root, old)
        _       <- modified(root, old)
        results <- ZIO.foreachPar(1 to 20)(_ => prune(root).exit)
        names   <- children(root)
      yield assertTrue(
        results.count(_ == Exit.succeed(TranscriptPruneResult.Pruned(old, 1))) == 1,
        results.forall {
          case Exit.Success(TranscriptPruneResult.Pruned(_, 1) | TranscriptPruneResult.Missing) =>
            true
          case Exit.Failure(_) => true
          case _               => false
        },
        names.isEmpty
      )
    }
  )
