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
import scala.jdk.CollectionConverters.*

object ToolResultStoreSpec extends ZIOSpecDefault:
  private def temporaryDirectory: ZIO[Scope, Throwable, Path] =
    ZIO.acquireRelease(ZIO.attemptBlocking(Files.createTempDirectory("agent-results-"))) {
      directory =>
        ZIO.scoped {
          ZIO
            .acquireRelease(ZIO.attemptBlocking(Files.walk(directory)))(stream =>
              ZIO.attemptBlocking(stream.close()).orDie
            )
            .flatMap(stream =>
              ZIO.attemptBlocking(stream.iterator().asScala.toList.sortBy(_.getNameCount).reverse)
            )
            .flatMap(paths =>
              ZIO.foreachDiscard(paths)(path => ZIO.attemptBlocking(Files.deleteIfExists(path)))
            )
        }.orDie
    }

  def spec: Spec[TestEnvironment & Scope, Any] = suite("ToolResultStoreSpec")(
    test("writes UTF-8 content below the canonical workspace and reuses identical files") {
      for
        directory <- temporaryDirectory
        store     <- ToolResultStore.local(directory)
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
    test("rejects traversal keys and sensitive workspace directories") {
      for
        directory <- temporaryDirectory
        store     <- ToolResultStore.local(directory)
        traversal <- store.persist("../escape", "secret").either
        sensitive <- ZIO.attemptBlocking(Files.createDirectory(directory.resolve(".ssh")))
        denied    <- ToolResultStore.local(sensitive).either
      yield assertTrue(traversal.isLeft, denied.isLeft)
    }
  )
