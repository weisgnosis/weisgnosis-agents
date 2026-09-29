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
package com.weisgnosis.agents.harness.eviction

import zio.*
import zio.test.*
import java.nio.file.{Files, Path}
import EvictionTestSupport.temporaryDirectory

object ToolResultStoreSpec extends ZIOSpecDefault:
  def spec: Spec[TestEnvironment & Scope, Any] = suite("ToolResultStoreSpec")(
    test("writes UTF-8 content below the canonical workspace and reuses identical files") {
      for
        directory <- temporaryDirectory
        store     <- ToolResultStore.local(directory, WorkspaceTrust.HostControlled)
        first     <- store.persist("safe-key", "full output")
        second    <- store.persist("safe-key", "full output")
        text      <- ZIO.attemptBlocking(Files.readString(Path.of(first)))
        collision <- store.persist("safe-key", "different output").either
      yield assertTrue(
        text == "full output",
        first == second,
        Path.of(first).startsWith(directory),
        collision.isLeft
      )
    },
    test("untrusted workspaces fail closed without creating storage") {
      for
        directory <- temporaryDirectory
        result    <- ToolResultStore.local(directory, WorkspaceTrust.Untrusted).either
        created   <- ZIO.attemptBlocking(Files.exists(directory.resolve("large_tool_results")))
      yield assertTrue(result.isLeft, !created)
    },
    test("independent stores publish concurrent first writes and identical keys completely") {
      val content = "concurrent output" * 10000
      for
        directory <- temporaryDirectory
        stores <- ZIO.foreach(1 to 12)(_ =>
          ToolResultStore.local(directory, WorkspaceTrust.HostControlled)
        )
        paths <- ZIO.foreachPar(stores.zipWithIndex) { case (store, index) =>
          store.persist(s"result-$index", content)
        }
        shared <- ZIO.foreachPar(stores)(_.persist("shared", content))
        saved <- ZIO.foreach(paths ++ shared)(path =>
          ZIO.attemptBlocking(Files.readString(Path.of(path)))
        )
      yield assertTrue(
        paths.distinct.size == 12,
        shared.distinct.size == 1,
        saved.forall(_ == content)
      )
    },
    test("concurrent content collisions preserve exactly one complete winner") {
      for
        directory <- temporaryDirectory
        first     <- ToolResultStore.local(directory, WorkspaceTrust.HostControlled)
        second    <- ToolResultStore.local(directory, WorkspaceTrust.HostControlled)
        outcomes <- first
          .persist("shared", "first")
          .either
          .zipPar(second.persist("shared", "second").either)
        saved <- ZIO.attemptBlocking(
          Files.readString(directory.resolve("large_tool_results/shared.txt"))
        )
      yield assertTrue(
        outcomes._1.isRight != outcomes._2.isRight,
        Set("first", "second").contains(saved)
      )
    },
    test("temporary cleanup failure does not replace a successful scoped result with a defect") {
      for
        directory <- temporaryDirectory
        child <- ZIO.attemptBlocking(
          Files.writeString(directory.resolve("retained.txt"), "content")
        )
        result <- ZIO
          .scoped(
            ZIO
              .acquireRelease(ZIO.succeed(directory))(ToolResultStore.cleanupTemporary)
              .as("published")
          )
          .exit
        retained <- ZIO.attemptBlocking(Files.readString(child))
      yield assertTrue(result == Exit.succeed("published"), retained == "content")
    },
    test("rejects traversal keys and sensitive workspace directories") {
      for
        directory <- temporaryDirectory
        store     <- ToolResultStore.local(directory, WorkspaceTrust.HostControlled)
        traversal <- store.persist("../escape", "secret").either
        sensitive <- ZIO.attemptBlocking(Files.createDirectory(directory.resolve(".ssh")))
        denied    <- ToolResultStore.local(sensitive, WorkspaceTrust.HostControlled).either
      yield assertTrue(traversal.isLeft, denied.isLeft)
    }
  )
