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
package com.weisgnosis.agents.harness.subagent

import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.harness.eviction.WorkspaceTrust
import com.weisgnosis.agents.harness.memory.MemoryTestSupport.{children, temporaryDirectory}
import com.weisgnosis.agents.harness.storage.*
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.time.Instant
import java.util.UUID
import zio.*
import zio.test.*

object LocalSubagentTaskWorkspaceSpec extends ZIOSpecDefault:
  private val trusted: WorkspaceTrust = WorkspaceTrust.HostControlled
  private val one: SubagentTaskId     = SubagentTaskId(new UUID(0L, 801L))
  private val two: SubagentTaskId     = SubagentTaskId(new UUID(0L, 802L))
  private val three: SubagentTaskId   = SubagentTaskId(new UUID(0L, 803L))

  private def name(value: String): IO[AgentError, LocalSubagentTaskWorkspace.Name] =
    ZIO.fromEither(LocalSubagentTaskWorkspace.Name.from(value))

  private def admissionIdentity(
      admission: SubagentTaskAdmissionResult
  ): IO[AgentError, SubagentTaskAdmissionIdentity] = admission match
    case SubagentTaskAdmissionResult.Created(value) => ZIO.succeed(value)
    case other => ZIO.fail(AgentError.StorageError(s"Expected fresh fixture admission: $other"))

  def spec: Spec[TestEnvironment & Scope, Any] = suite("LocalSubagentTaskWorkspaceSpec")(
    test(
      "physical sessions remain separately discoverable with live writers; inclusive observed mtime excludes old payloads without reading them"
    ):
      for
        root     <- temporaryDirectory
        agent    <- name("test-agent")
        first    <- name("session-a")
        second   <- name("session-b")
        old      <- name("session-old")
        unknown  <- name("unknown-agent")
        window   <- ZIO.fromEither(LocalSubagentTaskWorkspace.RecentWindow.make(1.day))
        _        <- TestClock.setTime(Instant.parse("2026-10-08T00:00:00Z"))
        a        <- LocalSubagentTaskWorkspace.openSession(root, agent, first, trusted)
        b        <- LocalSubagentTaskWorkspace.openSession(root, agent, second, trusted)
        oldStore <- LocalSubagentTaskWorkspace.openSession(root, agent, old, trusted)
        _ <- a.publish(SubagentTerminalRecord(one, SubagentTerminalOutcome.Completed("exact λ 😀")))
        admitted        <- a.admit(two)
        runningIdentity <- admissionIdentity(admitted)
        _               <- a.markRunning(runningIdentity)
        failure = SubagentTerminalOutcome.Failed(
          Chunk(SubagentTaskFailure("failed", Some("direct cause"))),
          SubagentTaskDefects.Absent
        )
        _         <- b.publish(SubagentTerminalRecord(three, failure))
        _         <- b.admit(one)
        _         <- oldStore.admit(three)
        expectedA <- a.taskRecords
        expectedB <- b.taskRecords
        directory    = root.resolve("agents/test-agent/tasks")
        pathA        = directory.resolve("session-a.json")
        pathB        = directory.resolve("session-b.json")
        pathOld      = directory.resolve("session-old.json")
        wantedCutoff = Instant.parse("2026-10-07T00:00:00Z")
        _      <- ZIO.attemptBlocking(Files.setLastModifiedTime(pathA, FileTime.from(wantedCutoff)))
        cutoff <- ZIO.attemptBlocking(Files.getLastModifiedTime(pathA).toInstant)
        _ <- ZIO.attemptBlocking(
          Files.setLastModifiedTime(pathB, FileTime.from(cutoff.plusMillis(1L)))
        )
        _ <- ZIO.attemptBlocking(
          Files.writeString(pathOld, "old corrupt bytes", StandardCharsets.UTF_8)
        )
        _ <- ZIO.attemptBlocking(
          Files.setLastModifiedTime(pathOld, FileTime.from(cutoff.minusMillis(1L)))
        )
        observedB   <- ZIO.attemptBlocking(Files.getLastModifiedTime(pathB).toInstant)
        observedOld <- ZIO.attemptBlocking(Files.getLastModifiedTime(pathOld).toInstant)
        _           <- TestClock.setTime(cutoff.plusSeconds(86400L))
        namesBefore <- children(directory)
        bytesBefore <- ZIO.foreach(Chunk(pathA, pathB, pathOld))(path =>
          ZIO.attemptBlocking(Files.readString(path, StandardCharsets.UTF_8))
        )
        timesBefore <- ZIO.foreach(Chunk(pathA, pathB, pathOld))(path =>
          ZIO.attemptBlocking(Files.getLastModifiedTime(path))
        )
        discovered <- LocalSubagentTaskWorkspace.listRecent(root, agent, window, trusted)
        namesAfter <- children(directory)
        bytesAfter <- ZIO.foreach(Chunk(pathA, pathB, pathOld))(path =>
          ZIO.attemptBlocking(Files.readString(path, StandardCharsets.UTF_8))
        )
        timesAfter <- ZIO.foreach(Chunk(pathA, pathB, pathOld))(path =>
          ZIO.attemptBlocking(Files.getLastModifiedTime(path))
        )
        rootBefore <- children(root.resolve("agents"))
        absent     <- LocalSubagentTaskWorkspace.listRecent(root, unknown, window, trusted)
        rootAfter  <- children(root.resolve("agents"))
        addressA   <- ZIO.fromEither(StorageAddress.from(Chunk("test-agent", "session-a"), "tasks"))
        addressB   <- ZIO.fromEither(StorageAddress.from(Chunk("test-agent", "session-b"), "tasks"))
      yield assertTrue(
        cutoff == wantedCutoff,
        observedB.isAfter(cutoff),
        observedOld.isBefore(cutoff),
        discovered.map(_.session) == Chunk("session-a", "session-b"),
        discovered.map(_.address) == Chunk(addressA, addressB),
        discovered.map(_.records) == Chunk(expectedA, expectedB),
        discovered.flatMap(_.records).size == 4,
        discovered.flatMap(_.records).count(_.id == one) == 2,
        discovered.exists(snapshot =>
          snapshot.session == "session-a" && snapshot.observedModifiedAt == cutoff
        ),
        discovered.forall(_.agent == "test-agent"),
        expectedA.exists(record =>
          record.id == two && record.state == SubagentTaskRecordState.Running
        ),
        expectedB.exists(record =>
          record.id == one && record.state == SubagentTaskRecordState.Pending
        ),
        namesBefore == namesAfter,
        namesBefore.count(_.endsWith(".versioned-writer.lease")) == 3,
        bytesBefore == bytesAfter,
        timesBefore == timesAfter,
        absent.isEmpty,
        rootBefore == rootAfter
      )
    ,
    test(
      "checked layout and bounded read-only discovery fail closed on selected corruption, wrong partitions and aggregate overflow"
    ):
      for
        root   <- temporaryDirectory
        agent  <- name("bounded-agent")
        a      <- name("a")
        b      <- name("b")
        window <- ZIO.fromEither(LocalSubagentTaskWorkspace.RecentWindow.make(1.day))
        _      <- TestClock.setTime(Instant.parse("2026-10-08T00:00:00Z"))
        first  <- LocalSubagentTaskWorkspace.openSession(root, agent, a, trusted)
        second <- LocalSubagentTaskWorkspace.openSession(root, agent, b, trusted)
        _ <- first.publish(
          SubagentTerminalRecord(one, SubagentTerminalOutcome.Completed("full Unicode λ 😀" * 1000))
        )
        _ <- first.admit(two)
        _ <- second.publish(
          SubagentTerminalRecord(three, SubagentTerminalOutcome.Completed("second"))
        )
        directory = root.resolve("agents/bounded-agent/tasks")
        path      = directory.resolve("a.json")
        original    <- ZIO.attemptBlocking(Files.readString(path, StandardCharsets.UTF_8))
        beforeNames <- children(directory)
        valid       <- LocalSubagentTaskWorkspace.listRecent(root, agent, window, trusted)
        tinyFile    <- ZIO.fromEither(LocalVersionedJsonStore.Limits.make(1))
        secondBytes <- ZIO.attemptBlocking(Files.size(directory.resolve("b.json")))
        totalBytes = original.getBytes(StandardCharsets.UTF_8).length.toLong + secondBytes
        exactLimits <- ZIO.fromEither(
          LocalSubagentTaskWorkspace.Limits.make(100, 10, totalBytes, 3)
        )
        exact <- LocalSubagentTaskWorkspace.listRecent(root, agent, window, trusted, exactLimits)
        bounds <- ZIO.foreach(
          Chunk(
            (3, 3, 1000000L, 10, LocalVersionedJsonStore.Limits.default),
            (100, 1, 1000000L, 10, LocalVersionedJsonStore.Limits.default),
            (100, 10, totalBytes - 1L, 10, LocalVersionedJsonStore.Limits.default),
            (100, 10, 1000000L, 1, LocalVersionedJsonStore.Limits.default),
            (100, 10, 1000000L, 10, tinyFile)
          )
        ): (entries, files, bytes, records, file) =>
          ZIO
            .fromEither(
              LocalSubagentTaskWorkspace.Limits.make(entries, files, bytes, records, file)
            )
            .flatMap: limits =>
              LocalSubagentTaskWorkspace
                .listRecent(root, agent, window, trusted, limits)
                .either
                .map(_.isLeft)
        untrusted <- LocalSubagentTaskWorkspace
          .listRecent(root, agent, window, WorkspaceTrust.Untrusted)
          .either
        traversal <- LocalSubagentTaskWorkspace
          .listRecent(root.resolve("..").resolve(root.getFileName), agent, window, trusted)
          .either
        corruptions <- ZIO.foreach(
          Chunk("not JSON", original.replace("\"key\":\"tasks\"", "\"key\":\"other\""))
        ): text =>
          for
            _      <- ZIO.attemptBlocking(Files.writeString(path, text, StandardCharsets.UTF_8))
            result <- LocalSubagentTaskWorkspace.listRecent(root, agent, window, trusted).either
          yield text != original && result.isLeft
        _ <- ZIO.attemptBlocking(Files.writeString(path, original, StandardCharsets.UTF_8))
        malformedRoot <- temporaryDirectory
        _ <- ZIO.attemptBlocking(
          Files.writeString(
            malformedRoot.resolve("agents"),
            "not a directory",
            StandardCharsets.UTF_8
          )
        )
        malformed <- LocalSubagentTaskWorkspace
          .listRecent(malformedRoot, agent, window, trusted)
          .either
        namesAfter  <- children(directory)
        restored    <- ZIO.attemptBlocking(Files.readString(path, StandardCharsets.UTF_8))
        emptyRoot   <- temporaryDirectory
        emptyBefore <- children(emptyRoot)
        unknown     <- LocalSubagentTaskWorkspace.listRecent(emptyRoot, agent, window, trusted)
        emptyAfter  <- children(emptyRoot)
      yield assertTrue(
        valid.flatMap(_.records).size == 3,
        exact == valid,
        bounds.forall(identity),
        corruptions.forall(identity),
        untrusted.isLeft,
        traversal.isLeft,
        malformed.isLeft,
        beforeNames == namesAfter,
        restored == original,
        unknown.isEmpty,
        emptyBefore.isEmpty,
        emptyAfter.isEmpty,
        Chunk("..", "UPPER", "con", "a/b", "~alias", "worker λ").forall(value =>
          LocalSubagentTaskWorkspace.Name.from(value).isLeft
        ),
        LocalSubagentTaskWorkspace.RecentWindow.make(Duration.Infinity).isLeft,
        LocalSubagentTaskWorkspace.RecentWindow.make((-1).seconds).isLeft
      )
  )
