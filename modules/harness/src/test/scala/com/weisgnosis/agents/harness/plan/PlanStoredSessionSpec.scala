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
import com.weisgnosis.agents.harness.memory.MemoryTestSupport.temporaryDirectory
import java.nio.file.Files
import zio.*
import zio.test.*

object PlanStoredSessionSpec extends ZIOSpecDefault:
  private def draft(content: String): IO[AgentError.InvalidMessage, PlanDraft] =
    ZIO.fromEither(PlanDraft.fromString(content))

  private def store(
      saved: Ref[Option[PlanDraft]],
      writeDraft: PlanDraft => IO[AgentError, Unit]
  ): PlanDraftStore =
    new PlanDraftStore:
      def read: IO[AgentError, Option[PlanDraft]]       = saved.get
      def write(value: PlanDraft): IO[AgentError, Unit] = writeDraft(value)

  def spec: Spec[TestEnvironment & Scope, Any] = suite("PlanStoredSessionSpec")(
    test("missing storage starts in Plan without a reviewable draft or restored approval") {
      for
        saved    <- Ref.make(Option.empty[PlanDraft])
        session  <- PlanSession.stored(store(saved, value => saved.set(Some(value))))
        snapshot <- session.control.snapshot
        review   <- session.control.requestReview.either
      yield assertTrue(snapshot == PlanSessionSnapshot.Planning(None), review.isLeft)
    },
    test("local publication, host approval and reopening never restore execution authority") {
      for
        root  <- temporaryDirectory
        value <- draft("# Stored plan\r\n- Inspect carefully.\n")
        approved <- ZIO.scoped:
          for
            backend  <- LocalPlanDraftStore.open(root, WorkspaceTrust.HostControlled)
            session  <- PlanSession.stored(backend)
            _        <- session.control.replaceDraft(value)
            saved    <- backend.read
            review   <- session.control.requestReview
            applied  <- session.reviewer.resolve(review, PlanReviewDecision.Approve)
            snapshot <- session.control.snapshot
          yield saved.contains(value) && applied == PlanReviewResolution.Applied &&
            snapshot == PlanSessionSnapshot.Build(Some(ApprovedPlan(value, review.revision)))
        reopened <- ZIO.scoped:
          LocalPlanDraftStore
            .open(root, WorkspaceTrust.HostControlled)
            .flatMap(backend => PlanSession.stored(backend).flatMap(_.control.snapshot))
        text <- ZIO.attemptBlocking(Files.readString(root.resolve("PLAN.md")))
      yield assertTrue(
        approved,
        reopened == PlanSessionSnapshot.Planning(Some(value)),
        text == value.content
      )
    },
    test("failed and defective initial reads preserve their causes without yielding a session") {
      val error = AgentError.PlanStorageError("read unavailable")
      def backend(readDraft: IO[AgentError, Option[PlanDraft]]): PlanDraftStore =
        new PlanDraftStore:
          def read: IO[AgentError, Option[PlanDraft]]       = readDraft
          def write(value: PlanDraft): IO[AgentError, Unit] = ZIO.unit
      for
        typed  <- PlanSession.stored(backend(ZIO.fail(error))).exit
        defect <- PlanSession.stored(backend(ZIO.die(error))).exit
      yield assertTrue(
        typed == Exit.fail(error),
        defect.causeOption.exists(_.defects == List(error))
      )
    },
    test("cancelling an initial read finishes its cleanup without invoking a write") {
      for
        entered <- Promise.make[Nothing, Unit]
        cleanup <- Ref.make(false)
        writes  <- Ref.make(0)
        backend = new PlanDraftStore:
          def read: IO[AgentError, Option[PlanDraft]] =
            (entered.succeed(()) *> ZIO.never).ensuring(cleanup.set(true))
          def write(value: PlanDraft): IO[AgentError, Unit] = writes.update(_ + 1)
        fiber   <- PlanSession.stored(backend).forkScoped
        _       <- entered.await
        exit    <- fiber.interrupt
        cleaned <- cleanup.get
        count   <- writes.get
      yield assertTrue(exit.isInterrupted, cleaned, count == 0)
    },
    test("Build rejects replacement before I/O and explicit re-entry invalidates approval") {
      for
        value  <- draft("original")
        next   <- draft("replacement")
        saved  <- Ref.make(Option(value))
        writes <- Ref.make(0)
        session <- PlanSession.stored(
          store(saved, candidate => writes.update(_ + 1) *> saved.set(Some(candidate)))
        )
        review    <- session.control.requestReview
        _         <- session.reviewer.resolve(review, PlanReviewDecision.Approve)
        before    <- session.control.snapshot
        rejected  <- session.control.replaceDraft(next).either
        unchanged <- session.control.snapshot
        count     <- writes.get
        _         <- session.control.enter
        entered   <- session.control.snapshot
      yield assertTrue(
        rejected.left.exists(_.isInstanceOf[AgentError.InvalidMessage]),
        unchanged == before,
        count == 0,
        entered == PlanSessionSnapshot.Planning(Some(value))
      )
    },
    test("a typed failure after external publication leaves no reviewable or approved draft") {
      val error = AgentError.PlanStorageError("acknowledgement failed after writing")
      for
        old   <- draft("old")
        next  <- draft("written but unacknowledged")
        saved <- Ref.make(Option(old))
        session <- PlanSession.stored(
          store(saved, value => saved.set(Some(value)) *> ZIO.fail(error))
        )
        review     <- session.control.requestReview
        failed     <- session.control.replaceDraft(next).exit
        superseded <- review.await
        stale      <- session.reviewer.resolve(review, PlanReviewDecision.Approve)
        snapshot   <- session.control.snapshot
        actual     <- saved.get
        refused    <- session.control.requestReview.either
        _          <- session.control.enter
        reentered  <- session.control.snapshot
      yield assertTrue(
        failed == Exit.fail(error),
        superseded == PlanReviewOutcome.Superseded,
        stale == PlanReviewResolution.NotCurrent,
        actual.contains(next),
        refused.isLeft,
        snapshot == PlanSessionSnapshot.Planning(None),
        reentered == snapshot,
        PlanPrompt.render(snapshot).exists(_.contains("No current reviewable draft"))
      )
    },
    test("defective publication clears review authority and preserves the original defect") {
      val error = AgentError.PlanStorageError("defective backend")
      for
        old        <- draft("old")
        next       <- draft("candidate")
        saved      <- Ref.make(Option(old))
        session    <- PlanSession.stored(store(saved, _ => ZIO.die(error)))
        review     <- session.control.requestReview
        failed     <- session.control.replaceDraft(next).exit
        superseded <- review.await
        snapshot   <- session.control.snapshot
        actual     <- saved.get
      yield assertTrue(
        failed.causeOption.exists(_.defects == List(error)),
        superseded == PlanReviewOutcome.Superseded,
        snapshot == PlanSessionSnapshot.Planning(None),
        actual.contains(old)
      )
    }
  )
