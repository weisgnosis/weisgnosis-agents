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

import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.model.Role
import com.weisgnosis.agents.harness.eviction.WorkspaceTrust
import com.weisgnosis.agents.harness.memory.MemoryTestSupport.{children, temporaryDirectory}
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.time.Instant
import zio.*
import zio.test.*

object LocalTranscriptRetentionPolicySpec extends ZIOSpecDefault:
  private val now: Instant    = Instant.parse("2026-04-01T00:00:00Z")
  private val cutoff: Instant = Instant.parse("2025-10-03T00:00:00Z")
  private val old: Instant    = Instant.parse("2025-01-01T00:00:00Z")

  private def clock(underlying: Clock, read: UIO[Instant]): Clock = new Clock:
    export underlying.{
      currentTime,
      currentDateTime,
      javaClock,
      localDateTime,
      nanoTime,
      scheduler,
      sleep
    }
    def instant(using Trace): UIO[Instant] = read

  def spec: Spec[TestEnvironment & Scope, Any] = suite("LocalTranscriptRetentionPolicySpec")(
    test("KeepAll bypasses both clock access and untrusted missing-target validation") {
      for
        root       <- temporaryDirectory
        underlying <- ZIO.clock
        disabledClock = clock(
          underlying,
          ZIO.die(new IllegalStateException("clock must not be read"))
        )
        result <- LocalTranscriptRetention
          .pruneWithPolicy(
            root.resolve("missing"),
            WorkspaceTrust.Untrusted,
            TranscriptRetentionPolicy.KeepAll
          )
          .withClock(disabledClock)
        files <- children(root)
      yield assertTrue(result == TranscriptRetentionResult.Disabled, files.isEmpty)
    },
    test("default policy samples time once and prunes only after acquiring exclusive ownership") {
      val draft = TranscriptDraft(
        Some(TranscriptKey.Message("old")),
        TranscriptPayload.Message(Role.User, "old fact", Chunk(TranscriptBlockType.Text))
      )
      for
        root <- temporaryDirectory
        _    <- TestClock.setTime(old)
        _ <- ZIO.scoped(
          LocalTranscriptStore
            .open(root, WorkspaceTrust.HostControlled)
            .flatMap(_.append(Chunk(draft)))
        )
        _ <- ZIO.attemptBlocking(
          Files.setLastModifiedTime(root.resolve("transcript.jsonl"), FileTime.from(old))
        )
        calls      <- Ref.make(0)
        underlying <- ZIO.clock
        selectedClock = clock(
          underlying,
          calls.updateAndGet(_ + 1).map(count => now.plusSeconds((count - 1).toLong * 86400))
        )
        result <- LocalTranscriptRetention
          .pruneWithPolicy(root, WorkspaceTrust.HostControlled)
          .withClock(selectedClock)
        count <- calls.get
        files <- children(root)
      yield assertTrue(
        result == TranscriptRetentionResult.Evaluated(cutoff, TranscriptPruneResult.Pruned(old, 1)),
        count == 1,
        files.isEmpty
      )
    },
    test("invalid policy cutoff fails before untrusted-target validation or ownership creation") {
      for
        root       <- temporaryDirectory
        underlying <- ZIO.clock
        selectedClock = clock(underlying, ZIO.succeed(Instant.MIN))
        exit <- LocalTranscriptRetention
          .pruneWithPolicy(root, WorkspaceTrust.Untrusted)
          .withClock(selectedClock)
          .exit
        files <- children(root)
      yield assertTrue(
        exit.causeOption.exists(_.failures.exists(_.isInstanceOf[AgentError.InvalidConfiguration])),
        files.isEmpty
      )
    },
    test("enabled policy keeps exact-boundary activity and distinguishes missing payloads") {
      for
        root    <- temporaryDirectory
        _       <- TestClock.setTime(now)
        missing <- LocalTranscriptRetention.pruneWithPolicy(root, WorkspaceTrust.HostControlled)
        _       <- ZIO.attemptBlocking(Files.writeString(root.resolve("transcript.jsonl"), ""))
        _ <- ZIO.attemptBlocking(
          Files.setLastModifiedTime(root.resolve("transcript.jsonl"), FileTime.from(cutoff))
        )
        retained <- LocalTranscriptRetention.pruneWithPolicy(root, WorkspaceTrust.HostControlled)
        files    <- children(root)
      yield assertTrue(
        missing == TranscriptRetentionResult.Evaluated(cutoff, TranscriptPruneResult.Missing),
        retained == TranscriptRetentionResult
          .Evaluated(cutoff, TranscriptPruneResult.Retained(cutoff)),
        files == Set("transcript.jsonl")
      )
    }
  )
