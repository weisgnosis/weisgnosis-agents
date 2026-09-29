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

object PlanWorkspaceStoreSpec extends ZIOSpecDefault:
  def spec: Spec[TestEnvironment & Scope, Any] = suite("PlanWorkspaceStoreSpec")(
    test(
      "custom logical directory resolves inside the selected workspace and publishes exact text"
    ) {
      for
        root      <- temporaryDirectory
        docs      <- ZIO.attemptBlocking(Files.createDirectory(root.resolve("docs")))
        plans     <- ZIO.attemptBlocking(Files.createDirectory(docs.resolve("plans")))
        directory <- ZIO.fromEither(PlanDirectory.fromString("/docs/plans/"))
        draft     <- ZIO.fromEither(PlanDraft.fromString("# Plan\n- step 1\n"))
        context <- ZIO.scoped:
          for
            store <- LocalPlanDraftStore.inWorkspace(root, WorkspaceTrust.HostControlled, directory)
            session <- PlanSession.stored(store)
            _       <- session.control.replaceDraft(draft)
            context <- session.control.context
          yield context
        expected  <- ZIO.attemptBlocking(plans.toRealPath().resolve("PLAN.md").toString)
        text      <- ZIO.attemptBlocking(Files.readString(plans.resolve("PLAN.md")))
        names     <- children(plans)
        rootNames <- children(root)
      yield assertTrue(
        directory.value == "docs/plans",
        context.location.exists(_.text == expected),
        context.snapshot == PlanSessionSnapshot.Planning(Some(draft)),
        text == draft.content,
        names == Set("PLAN.md"),
        rootNames == Set("docs")
      )
    },
    test("default plans selection shares ownership with native opening and releases on closure") {
      for
        root  <- temporaryDirectory
        plans <- ZIO.attemptBlocking(Files.createDirectory(root.resolve("plans")))
        draft <- ZIO.fromEither(PlanDraft.fromString("default draft"))
        blocked <- ZIO.scoped:
          for
            store <- LocalPlanDraftStore.inWorkspace(root, WorkspaceTrust.HostControlled)
            _     <- store.write(draft)
            competitor <- ZIO
              .scoped(LocalPlanDraftStore.open(plans, WorkspaceTrust.HostControlled))
              .either
          yield competitor
        reopened <- ZIO.scoped:
          for
            native <- LocalPlanDraftStore.open(plans, WorkspaceTrust.HostControlled)
            competitor <- ZIO
              .scoped(LocalPlanDraftStore.inWorkspace(root, WorkspaceTrust.HostControlled))
              .either
            read <- native.read
          yield competitor.isLeft && read.contains(draft)
        names <- children(plans)
      yield assertTrue(
        blocked.left.exists(_.isInstanceOf[AgentError.PlanStorageError]),
        reopened,
        names == Set("PLAN.md")
      )
    },
    test("missing and non-directory workspace or destination creates no directories or ownership") {
      for
        root <- temporaryDirectory
        missingWorkspace <- ZIO
          .scoped(
            LocalPlanDraftStore.inWorkspace(root.resolve("missing"), WorkspaceTrust.HostControlled)
          )
          .either
        missingTarget <- ZIO
          .scoped(LocalPlanDraftStore.inWorkspace(root, WorkspaceTrust.HostControlled))
          .either
        file <- ZIO.attemptBlocking(Files.writeString(root.resolve("file"), "keep"))
        nonDirectoryWorkspace <- ZIO
          .scoped(LocalPlanDraftStore.inWorkspace(file, WorkspaceTrust.HostControlled))
          .either
        _ <- ZIO.attemptBlocking(Files.writeString(root.resolve("plans"), "keep target"))
        nonDirectoryTarget <- ZIO
          .scoped(LocalPlanDraftStore.inWorkspace(root, WorkspaceTrust.HostControlled))
          .either
        names  <- children(root)
        target <- ZIO.attemptBlocking(Files.readString(root.resolve("plans")))
      yield assertTrue(
        Chunk(missingWorkspace, missingTarget, nonDirectoryWorkspace, nonDirectoryTarget).forall(
          _.isLeft
        ),
        names == Set("file", "plans"),
        target == "keep target"
      )
    },
    test("untrusted and sensitive selections are refused before any ownership or payload write") {
      for
        root      <- temporaryDirectory
        plans     <- ZIO.attemptBlocking(Files.createDirectory(root.resolve("plans")))
        sensitive <- ZIO.attemptBlocking(Files.createDirectory(root.resolve(".env.private")))
        untrusted <- ZIO
          .scoped(LocalPlanDraftStore.inWorkspace(root, WorkspaceTrust.Untrusted))
          .either
        directory <- ZIO.fromEither(PlanDirectory.fromString(".env.private"))
        denied <- ZIO
          .scoped(LocalPlanDraftStore.inWorkspace(root, WorkspaceTrust.HostControlled, directory))
          .either
        planNames      <- children(plans)
        sensitiveNames <- children(sensitive)
        rootNames      <- children(root)
      yield assertTrue(
        untrusted.isLeft,
        denied.isLeft,
        planNames.isEmpty,
        sensitiveNames.isEmpty,
        rootNames == Set("plans", ".env.private")
      )
    },
    test("an escaped workspace-selected store cannot publish after its scope closes") {
      for
        root   <- temporaryDirectory
        plans  <- ZIO.attemptBlocking(Files.createDirectory(root.resolve("plans")))
        closed <- ZIO.scoped(LocalPlanDraftStore.inWorkspace(root, WorkspaceTrust.HostControlled))
        draft  <- ZIO.fromEither(PlanDraft.fromString("must not write"))
        failed <- closed.write(draft).either
        names  <- children(plans)
      yield assertTrue(
        failed.left.exists(_.isInstanceOf[AgentError.PlanStorageError]),
        names.isEmpty
      )
    }
  )
