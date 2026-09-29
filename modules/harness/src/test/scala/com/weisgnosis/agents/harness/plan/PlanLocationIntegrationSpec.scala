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
import java.nio.file.{Files, Path}
import zio.*
import zio.json.*
import zio.test.*

object PlanLocationIntegrationSpec extends ZIOSpecDefault:
  def spec: Spec[TestEnvironment & Scope, Any] = suite("PlanLocationIntegrationSpec")(
    test(
      "missing local content exposes only a canonical configured destination without creating a draft"
    ) {
      for
        root      <- temporaryDirectory
        store     <- LocalPlanDraftStore.open(root, WorkspaceTrust.HostControlled)
        session   <- PlanSession.stored(store)
        context   <- session.control.context
        canonical <- ZIO.attemptBlocking(root.toRealPath().resolve("PLAN.md").toString)
        names     <- children(root)
        prompt = PlanPrompt.fromContext(context)
      yield assertTrue(
        context.snapshot == PlanSessionSnapshot.Planning(None),
        context.location == store.location,
        context.location.exists(_.text == canonical),
        names == Set(".plan-writer.lease"),
        prompt.exists(_.contains(canonical.toJson)),
        prompt.exists(_.contains("Configured draft destination")),
        prompt.forall(text => !text.contains("last successful session read or publication"))
      )
    },
    test("publication and approved exit retain exact location through re-entry") {
      for
        root      <- temporaryDirectory
        store     <- LocalPlanDraftStore.open(root, WorkspaceTrust.HostControlled)
        session   <- PlanSession.stored(store)
        before    <- session.control.context
        _         <- session.control.enter
        draft     <- ZIO.fromEither(PlanDraft.fromString("# Plan\n- step 1\n"))
        _         <- session.control.replaceDraft(draft)
        written   <- session.control.context
        bytes     <- ZIO.attemptBlocking(Files.readString(root.resolve("PLAN.md")))
        review    <- session.control.requestReview
        _         <- session.reviewer.resolve(review, PlanReviewDecision.Approve)
        approved  <- session.control.context
        _         <- session.control.enter
        reentered <- session.control.context
      yield assertTrue(
        bytes == draft.content,
        written.snapshot == PlanSessionSnapshot.Planning(Some(draft)),
        Chunk(written, approved, reentered).forall(_.location == before.location),
        approved.snapshot == PlanSessionSnapshot.Build(Some(ApprovedPlan(draft, review.revision))),
        PlanPrompt
          .fromContext(approved)
          .exists(_.contains("last successful session read or publication")),
        reentered.snapshot == written.snapshot
      )
    },
    test(
      "custom existing directories canonicalize dot components and trailing separators without rebasing"
    ) {
      for
        root       <- temporaryDirectory
        docs       <- ZIO.attemptBlocking(Files.createDirectory(root.resolve("docs")))
        plans      <- ZIO.attemptBlocking(Files.createDirectory(docs.resolve("plans")))
        configured <- ZIO.attemptBlocking(Path.of(plans.resolve(".").toString + "/"))
        expected   <- ZIO.attemptBlocking(plans.toRealPath().resolve("PLAN.md").toString)
        store      <- LocalPlanDraftStore.open(configured, WorkspaceTrust.HostControlled)
        session    <- PlanSession.stored(store)
        context    <- session.control.context
        traversal <- ZIO
          .scoped(LocalPlanDraftStore.open(plans.resolve(".."), WorkspaceTrust.HostControlled))
          .either
      yield assertTrue(context.location.exists(_.text == expected), traversal.isLeft)
    },
    test("reopening exact stored text retains descriptive location but starts unapproved") {
      for
        root  <- temporaryDirectory
        draft <- ZIO.fromEither(PlanDraft.fromString("stored draft"))
        previous <- ZIO.scoped:
          for
            store   <- LocalPlanDraftStore.open(root, WorkspaceTrust.HostControlled)
            session <- PlanSession.stored(store)
            _       <- session.control.replaceDraft(draft)
            review  <- session.control.requestReview
            _       <- session.reviewer.resolve(review, PlanReviewDecision.Approve)
            context <- session.control.context
          yield context
        reopened <- ZIO.scoped:
          LocalPlanDraftStore
            .open(root, WorkspaceTrust.HostControlled)
            .flatMap(store => PlanSession.stored(store).flatMap(_.control.context))
      yield assertTrue(
        reopened.location == previous.location,
        reopened.snapshot == PlanSessionSnapshot.Planning(Some(draft)),
        reopened.snapshot.mode == PlanMode.Plan
      )
    },
    test(
      "blocked and failed publication exposes configured destination rather than saved-draft provenance"
    ) {
      val error = AgentError.PlanStorageError("ambiguous write")
      for
        old                <- ZIO.fromEither(PlanDraft.fromString("old"))
        next               <- ZIO.fromEither(PlanDraft.fromString("candidate"))
        configuredLocation <- ZIO.fromEither(PlanLocation.fromString("remote:explicit/PLAN.md"))
        entered            <- Promise.make[Nothing, Unit]
        release            <- Promise.make[Nothing, Unit]
        backend = new PlanDraftStore:
          override val location: Option[PlanLocation] = Some(configuredLocation)
          def read: IO[AgentError, Option[PlanDraft]] = ZIO.some(old)
          def write(draft: PlanDraft): IO[AgentError, Unit] =
            entered.succeed(()) *> release.await *> ZIO.fail(error)
        session <- PlanSession.stored(backend)
        before  <- session.control.context
        fiber   <- session.control.replaceDraft(next).forkScoped
        _       <- entered.await
        during  <- session.control.context
        _       <- release.succeed(())
        exit    <- fiber.await
        after   <- session.control.context
      yield assertTrue(
        exit == Exit.fail(error),
        before.location == during.location,
        during == after,
        after.snapshot == PlanSessionSnapshot.Planning(None),
        PlanPrompt
          .fromContext(before)
          .exists(_.contains("last successful session read or publication")),
        PlanPrompt.fromContext(after).exists(_.contains("Configured draft destination")),
        PlanPrompt
          .fromContext(after)
          .forall(text => !text.contains("last successful session read or publication"))
      )
    },
    test(
      "legacy in-memory and location-free stores keep location absent and read stored content once"
    ) {
      for
        reads <- Ref.make(0)
        backend = new PlanDraftStore:
          def read: IO[AgentError, Option[PlanDraft]]       = reads.update(_ + 1).as(None)
          def write(draft: PlanDraft): IO[AgentError, Unit] = ZIO.unit
        stored   <- PlanSession.stored(backend)
        memory   <- PlanSession.make
        contexts <- ZIO.foreach(1 to 3)(_ => stored.control.context)
        initial  <- memory.control.context
        count    <- reads.get
      yield assertTrue(contexts.forall(_.location.isEmpty), initial.location.isEmpty, count == 1)
    }
  )
