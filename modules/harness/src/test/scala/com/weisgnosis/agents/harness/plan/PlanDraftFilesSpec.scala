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
package com.weisgnosis.agents.harness.plan

import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.harness.eviction.WorkspaceTrust
import com.weisgnosis.agents.harness.memory.MemoryTestSupport.{children, temporaryDirectory}
import java.nio.file.Files
import zio.*
import zio.test.*

object PlanDraftFilesSpec extends ZIOSpecDefault:
  def spec: Spec[TestEnvironment & Scope, Any] = suite("PlanDraftFilesSpec")(
    test("canonical containment rejects the workspace itself and sibling string-prefix collisions") {
      for
        root      <- temporaryDirectory
        workspace <- ZIO.attemptBlocking(Files.createDirectory(root.resolve("plan")).toRealPath())
        child <- ZIO.attemptBlocking(
          Files.createDirectory(workspace.resolve("nested")).toRealPath()
        )
        sibling <- ZIO.attemptBlocking(
          Files.createDirectory(root.resolve("plan-extra")).toRealPath()
        )
        ancestor <- ZIO.attemptBlocking(root.toRealPath())
        accepted <- PlanDraftFiles.validateDescendant(workspace, child).either
        rejected <- ZIO.foreach(Chunk(workspace, sibling, ancestor))(candidate =>
          PlanDraftFiles.validateDescendant(workspace, candidate).either
        )
      yield assertTrue(
        accepted.isRight,
        rejected.forall(_.left.exists(_.isInstanceOf[AgentError.PermissionDenied]))
      )
    },
    test("atomic replacement preserves the other name of a hard-linked destination") {
      for
        root      <- temporaryDirectory
        original  <- ZIO.attemptBlocking(Files.writeString(root.resolve("original"), "original"))
        _         <- ZIO.attemptBlocking(Files.createLink(root.resolve("PLAN.md"), original))
        files     <- PlanDraftFiles.open(root, WorkspaceTrust.HostControlled)
        next      <- ZIO.fromEither(PlanDraft.fromString("replacement"))
        _         <- files.write(next)
        saved     <- files.read
        unchanged <- ZIO.attemptBlocking(Files.readString(original))
        names     <- children(root)
      yield assertTrue(
        saved.contains(next),
        unchanged == "original",
        names == Set("original", "PLAN.md")
      )
    },
    test("malformed UTF-16 fails without replacement bytes or damage to the previous draft") {
      for
        root      <- temporaryDirectory
        files     <- PlanDraftFiles.open(root, WorkspaceTrust.HostControlled)
        original  <- ZIO.fromEither(PlanDraft.fromString("original"))
        malformed <- ZIO.fromEither(PlanDraft.fromString("unpaired \ud800"))
        _         <- files.write(original)
        failed    <- files.write(malformed).either
        saved     <- files.read
        names     <- children(root)
      yield assertTrue(
        failed.left.exists(_.isInstanceOf[AgentError.PlanStorageError]),
        saved.contains(original),
        names == Set("PLAN.md")
      )
    },
    test("nonregular destination rejects reads and writes and cleans pending files") {
      for
        root     <- temporaryDirectory
        files    <- PlanDraftFiles.open(root, WorkspaceTrust.HostControlled)
        _        <- ZIO.attemptBlocking(Files.createDirectory(root.resolve("PLAN.md")))
        next     <- ZIO.fromEither(PlanDraft.fromString("replacement"))
        read     <- files.read.either
        write    <- files.write(next).either
        names    <- children(root)
        retained <- ZIO.attemptBlocking(Files.isDirectory(root.resolve("PLAN.md")))
      yield assertTrue(read.isLeft, write.isLeft, names == Set("PLAN.md"), retained)
    },
    test("an existing blank file differs from a missing draft on every read") {
      for
        root         <- temporaryDirectory
        files        <- PlanDraftFiles.open(root, WorkspaceTrust.HostControlled)
        absent       <- files.read
        _            <- ZIO.attemptBlocking(Files.writeString(root.resolve("PLAN.md"), ""))
        blank        <- files.read.either
        _            <- ZIO.attemptBlocking(Files.delete(root.resolve("PLAN.md")))
        missingAgain <- files.read
      yield assertTrue(
        absent.isEmpty,
        blank.left.exists(_.isInstanceOf[AgentError.PlanStorageError]),
        missingAgain.isEmpty
      )
    }
  )
