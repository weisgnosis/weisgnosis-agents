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
package com.weisgnosis.agents.harness.storage

import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.harness.memory.MemoryTestSupport.{children, temporaryDirectory}
import java.nio.file.Files
import zio.*
import zio.test.*

object WorkspaceLeaseSpec extends ZIOSpecDefault:
  def spec: Spec[TestEnvironment & Scope, Any] = suite("WorkspaceLeaseSpec")(
    test("typed owners retain independent fixed ownership filenames") {
      for
        root <- temporaryDirectory
        active <- ZIO.scoped:
          for
            _     <- WorkspaceLease.open(root, WorkspaceOwner.Memory)
            _     <- WorkspaceLease.open(root, WorkspaceOwner.Transcript)
            names <- children(root)
          yield names
        after <- children(root)
      yield assertTrue(
        active == Set(".daily-writer.lease", ".transcript-writer.lease"),
        after.isEmpty
      )
    },
    test("transcript ownership excludes competitors and rejects handles after release") {
      for
        root <- temporaryDirectory
        closed <- ZIO.scoped:
          for
            owner      <- WorkspaceLease.open(root, WorkspaceOwner.Transcript)
            competitor <- ZIO.scoped(WorkspaceLease.open(root, WorkspaceOwner.Transcript)).either
            _ <- ZIO
              .fail(AgentError.MemoryStorageError("Competing owner admitted"))
              .unless(competitor.isLeft)
          yield owner
        rejected <- closed.protect(ZIO.unit).either
        next <- ZIO.scoped(
          WorkspaceLease
            .open(root, WorkspaceOwner.Transcript)
            .flatMap(_.protect(ZIO.succeed("reopened")))
        )
      yield assertTrue(rejected.isLeft, next == "reopened")
    },
    test("an abandoned transcript token is never cleared by another owner") {
      for
        root <- temporaryDirectory
        _ <- ZIO.attemptBlocking(
          Files.writeString(root.resolve(".transcript-writer.lease"), "stale")
        )
        failed   <- ZIO.scoped(WorkspaceLease.open(root, WorkspaceOwner.Transcript)).either
        retained <- ZIO.attemptBlocking(Files.readString(root.resolve(".transcript-writer.lease")))
      yield assertTrue(failed.isLeft, retained == "stale")
    }
  )
