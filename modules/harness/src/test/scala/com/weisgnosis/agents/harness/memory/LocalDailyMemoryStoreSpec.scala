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
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.time.{Instant, LocalDate}
import zio.*
import zio.test.*
import MemoryTestSupport.{children, temporaryDirectory}

object LocalDailyMemoryStoreSpec extends ZIOSpecDefault:
  private val day: LocalDate = LocalDate.of(2026, 9, 29)

  def spec: Spec[TestEnvironment & Scope, Any] = suite("LocalDailyMemoryStoreSpec")(
    test("reads missing context without creating payload files") {
      for
        root   <- temporaryDirectory
        before <- children(root)
        observations <- ZIO.scoped:
          for
            store   <- LocalDailyMemoryStore.open(root, WorkspaceTrust.HostControlled)
            curated <- store.readCurated
            daily   <- store.readDaily(day)
          yield curated -> daily
        files         <- children(root.resolve("memory"))
        curatedExists <- ZIO.attemptBlocking(Files.exists(root.resolve("MEMORY.md")))
      yield assertTrue(
        before.isEmpty,
        observations == (None -> None),
        files.isEmpty,
        !curatedExists
      )
    },
    test("appends UTF-8 daily entries and reads curated memory without changing it") {
      val curated = "- curated fact"
      val first   = "- price: \u20ac10\n"
      val second  = "- next fact\n"
      for
        root <- temporaryDirectory
        _ <- ZIO.attemptBlocking(
          Files.writeString(root.resolve("MEMORY.md"), curated, StandardCharsets.UTF_8)
        )
        observed <- ZIO.scoped:
          for
            store   <- LocalDailyMemoryStore.open(root, WorkspaceTrust.HostControlled)
            context <- store.readCurated
            _       <- store.appendDaily(day, first)
            _       <- store.appendDaily(day, second)
            content <- store.readDaily(day)
          yield context -> content
        saved <- ZIO.attemptBlocking(
          Files.readString(root.resolve("memory/2026-09-29.md"), StandardCharsets.UTF_8)
        )
        unchanged <- ZIO.attemptBlocking(
          Files.readString(root.resolve("MEMORY.md"), StandardCharsets.UTF_8)
        )
        files <- children(root.resolve("memory"))
      yield assertTrue(
        observed == (Some(curated) -> Some(first + second)),
        saved == first + second,
        unchanged == curated,
        files == Set("2026-09-29.md")
      )
    },
    test("independent daily files and empty append preserve existing bytes") {
      for
        root <- temporaryDirectory
        result <- ZIO.scoped:
          for
            store  <- LocalDailyMemoryStore.open(root, WorkspaceTrust.HostControlled)
            _      <- store.appendDaily(day, "first")
            _      <- store.appendDaily(day.plusDays(1), "second")
            _      <- store.appendDaily(day.plusDays(2), "")
            first  <- store.readDaily(day)
            second <- store.readDaily(day.plusDays(1))
            empty  <- store.readDaily(day.plusDays(2))
          yield (first, second, empty)
      yield assertTrue(result == (Some("first"), Some("second"), None))
    },
    test("parallel appends on one store retain each whole entry exactly once") {
      val entries = Chunk.fromIterable(1 to 24).map(index => s"entry-$index\n")
      for
        root <- temporaryDirectory
        saved <- ZIO.scoped:
          for
            store  <- LocalDailyMemoryStore.open(root, WorkspaceTrust.HostControlled)
            _      <- ZIO.foreachParDiscard(entries)(entry => store.appendDaily(day, entry))
            result <- store.readDaily(day)
          yield result
      yield assertTrue(
        saved.exists(text => text.linesIterator.toList.sorted == entries.map(_.strip).toList.sorted)
      )
    },
    test("a live owner rejects competing stores and can still append afterward") {
      for
        root <- temporaryDirectory
        result <- ZIO.scoped:
          for
            store <- LocalDailyMemoryStore.open(root, WorkspaceTrust.HostControlled)
            attempts <- ZIO.foreachPar(1 to 8)(_ =>
              ZIO.scoped(LocalDailyMemoryStore.open(root, WorkspaceTrust.HostControlled)).either
            )
            _       <- store.appendDaily(day, "owner content")
            content <- store.readDaily(day)
          yield attempts.forall(_.isLeft) && content.contains("owner content")
        reopened <- ZIO.scoped(
          LocalDailyMemoryStore.open(root, WorkspaceTrust.HostControlled).flatMap(_.readDaily(day))
        )
      yield assertTrue(result, reopened.contains("owner content"))
    },
    test("scope release rejects escaped store operations and permits a new owner") {
      for
        root   <- temporaryDirectory
        closed <- ZIO.scoped(LocalDailyMemoryStore.open(root, WorkspaceTrust.HostControlled))
        read   <- closed.readDaily(day).either
        write  <- closed.appendDaily(day, "wrong").either
        next <- ZIO.scoped(
          LocalDailyMemoryStore
            .open(root, WorkspaceTrust.HostControlled)
            .flatMap(store => store.appendDaily(day, "new owner") *> store.readDaily(day))
        )
      yield assertTrue(
        read.left.exists(_.isInstanceOf[AgentError.MemoryStorageError]),
        write.left.exists(_.isInstanceOf[AgentError.MemoryStorageError]),
        next.contains("new owner")
      )
    },
    test("cancelling an owning scope releases ownership before completion") {
      for
        root    <- temporaryDirectory
        entered <- Promise.make[Nothing, Unit]
        fiber <- ZIO
          .scoped(
            LocalDailyMemoryStore.open(root, WorkspaceTrust.HostControlled) *>
              entered.succeed(()) *> ZIO.never
          )
          .forkScoped
        _    <- entered.await
        exit <- fiber.interrupt
        next <- ZIO.scoped(
          LocalDailyMemoryStore.open(root, WorkspaceTrust.HostControlled).flatMap(_.readDaily(day))
        )
      yield assertTrue(exit.isInterrupted, next.isEmpty)
    },
    test("untrusted and traversing workspaces fail before creating memory files") {
      for
        root <- temporaryDirectory
        untrusted <- ZIO
          .scoped(LocalDailyMemoryStore.open(root.resolve("missing"), WorkspaceTrust.Untrusted))
          .either
        _ <- ZIO.attemptBlocking(Files.createDirectory(root.resolve("child")))
        traversal <- ZIO
          .scoped(
            LocalDailyMemoryStore.open(root.resolve("child/.."), WorkspaceTrust.HostControlled)
          )
          .either
        entries <- children(root)
      yield assertTrue(
        untrusted.left.exists(_.isInstanceOf[AgentError.PermissionDenied]),
        traversal.left.exists(_.isInstanceOf[AgentError.PermissionDenied]),
        entries == Set("child")
      )
    },
    test("sensitive roots and non-directory memory locations are rejected") {
      for
        root      <- temporaryDirectory
        sensitive <- ZIO.attemptBlocking(Files.createDirectory(root.resolve(".env.private")))
        rejected <- ZIO
          .scoped(LocalDailyMemoryStore.open(sensitive, WorkspaceTrust.HostControlled))
          .either
        _ <- ZIO.attemptBlocking(Files.writeString(root.resolve("memory"), "not a directory"))
        invalid <- ZIO
          .scoped(LocalDailyMemoryStore.open(root, WorkspaceTrust.HostControlled))
          .either
        unchanged <- ZIO.attemptBlocking(Files.readString(root.resolve("memory")))
      yield assertTrue(rejected.isLeft, invalid.isLeft, unchanged == "not a directory")
    },
    test("nonregular curated and daily targets are rejected without replacement") {
      for
        root <- temporaryDirectory
        _    <- ZIO.attemptBlocking(Files.createDirectory(root.resolve("MEMORY.md")))
        failures <- ZIO.scoped:
          for
            store <- LocalDailyMemoryStore.open(root, WorkspaceTrust.HostControlled)
            _ <- ZIO.attemptBlocking(Files.createDirectory(root.resolve("memory/2026-09-29.md")))
            curated <- store.readCurated.either
            read    <- store.readDaily(day).either
            append  <- store.appendDaily(day, "wrong").either
          yield Chunk(curated.isLeft, read.isLeft, append.isLeft)
        intact <- ZIO.attemptBlocking(Files.isDirectory(root.resolve("memory/2026-09-29.md")))
      yield assertTrue(failures.forall(identity), intact)
    },
    test("manager writes the dated filesystem ledger and retains curated context unchanged") {
      for
        _    <- TestClock.setTime(Instant.parse("2026-09-29T10:00:00Z"))
        root <- temporaryDirectory
        _    <- ZIO.attemptBlocking(Files.writeString(root.resolve("MEMORY.md"), "curated"))
        result <- ZIO.scoped:
          for
            store <- LocalDailyMemoryStore.open(root, WorkspaceTrust.HostControlled)
            manager <- MemoryFlushManager.make(
              store,
              MemoryExtractor(_ => ZIO.succeed("- new fact"))
            )
            _ <- manager.flush(Chunk(com.weisgnosis.agents.core.model.Msg.user("remember this")))
            daily   <- store.readDaily(day)
            curated <- store.readCurated
          yield daily -> curated
      yield assertTrue(
        result == (Some("\n## Memory Flush - 2026-09-29T10:00:00Z\n- new fact\n") -> Some(
          "curated"
        ))
      )
    },
    test("appending replaces a daily hard link without changing curated memory") {
      for
        root    <- temporaryDirectory
        curated <- ZIO.attemptBlocking(Files.writeString(root.resolve("MEMORY.md"), "curated fact"))
        result <- ZIO.scoped:
          for
            store <- LocalDailyMemoryStore.open(root, WorkspaceTrust.HostControlled)
            _ <- ZIO.attemptBlocking(
              Files.createLink(root.resolve("memory/2026-09-29.md"), curated)
            )
            _        <- store.appendDaily(day, "\nnew daily fact")
            daily    <- store.readDaily(day)
            original <- store.readCurated
          yield daily -> original
      yield assertTrue(result == (Some("curated fact\nnew daily fact") -> Some("curated fact")))
    }
  )
