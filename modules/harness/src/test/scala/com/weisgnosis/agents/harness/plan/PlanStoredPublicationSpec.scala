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
import com.weisgnosis.agents.harness.permission.PermissionBehavior
import zio.*
import zio.json.ast.Json
import zio.test.*

object PlanStoredPublicationSpec extends ZIOSpecDefault:
  private def draft(content: String): IO[AgentError.InvalidMessage, PlanDraft] =
    ZIO.fromEither(PlanDraft.fromString(content))

  private def store(
      saved: Ref[Option[PlanDraft]],
      writeDraft: PlanDraft => IO[AgentError, Unit]
  ): PlanDraftStore =
    new PlanDraftStore:
      def read: IO[AgentError, Option[PlanDraft]]       = saved.get
      def write(value: PlanDraft): IO[AgentError, Unit] = writeDraft(value)

  def spec: Spec[TestEnvironment & Scope, Any] = suite("PlanStoredPublicationSpec")(
    test("publication invalidates review before I/O and an old scope cannot disturb the new draft") {
      for
        old     <- draft("old")
        next    <- draft("next")
        saved   <- Ref.make(Option(old))
        entered <- Promise.make[Nothing, Unit]
        release <- Promise.make[Nothing, Unit]
        session <- PlanSession.stored(
          store(saved, value => entered.succeed(()) *> release.await *> saved.set(Some(value)))
        )
        owner      <- ZIO.acquireRelease(Scope.make)(_.close(Exit.unit))
        review     <- owner.extend(session.control.requestReview)
        writer     <- session.control.replaceDraft(next).forkScoped
        _          <- entered.await
        superseded <- review.await
        during     <- session.control.snapshot
        gate = PlanPermissionGate.make(
          session.control.mode,
          Map("write" -> PlanToolAccess.Mutation)
        )
        denied       <- gate.evaluate("write", Json.Obj())
        _            <- owner.close(Exit.unit)
        afterClosing <- session.control.snapshot
        _            <- release.succeed(()) *> writer.join
        after        <- session.control.snapshot
        stale        <- session.reviewer.resolve(review, PlanReviewDecision.Approve)
        actual       <- saved.get
      yield assertTrue(
        superseded == PlanReviewOutcome.Superseded,
        during == PlanSessionSnapshot.Planning(None),
        afterClosing == during,
        denied.contains(PermissionBehavior.Deny),
        after == PlanSessionSnapshot.Planning(Some(next)),
        stale == PlanReviewResolution.NotCurrent,
        actual.contains(next)
      )
    },
    test("writers serialize publication and expose only their completed generation") {
      for
        first   <- draft("first")
        second  <- draft("second")
        saved   <- Ref.make(Option.empty[PlanDraft])
        calls   <- Ref.make(Chunk.empty[PlanDraft])
        entered <- Promise.make[Nothing, Unit]
        release <- Promise.make[Nothing, Unit]
        session <- PlanSession.stored(
          store(
            saved,
            value =>
              calls.update(_ :+ value) *>
                (entered.succeed(()) *> release.await).when(value == first) *>
                saved.set(Some(value))
          )
        )
        a        <- session.control.replaceDraft(first).forkScoped
        _        <- entered.await
        b        <- session.control.replaceDraft(second).forkScoped
        _        <- TestClock.adjust(0.seconds)
        before   <- calls.get
        queued   <- b.poll
        _        <- release.succeed(()) *> a.join *> b.join
        all      <- calls.get
        actual   <- saved.get
        snapshot <- session.control.snapshot
      yield assertTrue(
        before == Chunk(first),
        queued.isEmpty,
        all == Chunk(first, second),
        actual.contains(second),
        snapshot == PlanSessionSnapshot.Planning(Some(second))
      )
    },
    test("queued edits, entry and review registration remain cancellable without changing state") {
      for
        old     <- draft("old")
        next    <- draft("next")
        queued  <- draft("must not write")
        saved   <- Ref.make(Option(old))
        calls   <- Ref.make(Chunk.empty[PlanDraft])
        entered <- Promise.make[Nothing, Unit]
        release <- Promise.make[Nothing, Unit]
        session <- PlanSession.stored(
          store(
            saved,
            value =>
              calls.update(_ :+ value) *> entered.succeed(()) *> release.await *> saved.set(
                Some(value)
              )
          )
        )
        prior       <- session.control.requestReview
        writer      <- session.control.replaceDraft(next).forkScoped
        _           <- entered.await
        edit        <- session.control.replaceDraft(queued).forkScoped
        enter       <- session.control.enter.forkScoped
        review      <- ZIO.scoped(session.control.requestReview.flatMap(_.await)).forkScoped
        resolution  <- session.reviewer.resolve(prior, PlanReviewDecision.Approve).forkScoped
        _           <- TestClock.adjust(0.seconds)
        exits       <- ZIO.foreach(Chunk(edit, enter, review, resolution))(_.interrupt)
        during      <- session.control.snapshot
        _           <- release.succeed(()) *> writer.join
        actualCalls <- calls.get
        snapshot    <- session.control.snapshot
        current     <- session.control.requestReview
        applied     <- session.reviewer.resolve(current, PlanReviewDecision.Decline)
      yield assertTrue(
        exits.forall(_.isInterrupted),
        during == PlanSessionSnapshot.Planning(None),
        actualCalls == Chunk(next),
        snapshot == PlanSessionSnapshot.Planning(Some(next)),
        applied == PlanReviewResolution.Applied
      )
    },
    test("interruption after external writing completes cleanup and withholds review authority") {
      for
        old     <- draft("old")
        next    <- draft("written before cancellation")
        saved   <- Ref.make(Option(old))
        entered <- Promise.make[Nothing, Unit]
        cleaned <- Ref.make(false)
        session <- PlanSession.stored(
          store(
            saved,
            value =>
              (saved.set(Some(value)) *> entered.succeed(()) *> ZIO.never)
                .ensuring(cleaned.set(true))
          )
        )
        prior    <- session.control.requestReview
        writer   <- session.control.replaceDraft(next).forkScoped
        _        <- entered.await
        exit     <- writer.interrupt
        cleanup  <- cleaned.get
        actual   <- saved.get
        snapshot <- session.control.snapshot
        review   <- session.control.requestReview.either
        outcome  <- prior.await
      yield assertTrue(
        exit.isInterrupted,
        cleanup,
        actual.contains(next),
        review.isLeft,
        snapshot == PlanSessionSnapshot.Planning(None),
        outcome == PlanReviewOutcome.Superseded
      )
    },
    test(
      "late cancellation after a masked successful write can leave only an unapproved new draft"
    ) {
      for
        next    <- draft("completed publication")
        saved   <- Ref.make(Option.empty[PlanDraft])
        entered <- Promise.make[Nothing, Unit]
        release <- Promise.make[Nothing, Unit]
        session <- PlanSession.stored(
          store(
            saved,
            value =>
              (entered.succeed(()) *> release.await *> saved.set(Some(value))).uninterruptible
          )
        )
        writer     <- session.control.replaceDraft(next).forkScoped
        _          <- entered.await
        cancelling <- writer.interrupt.forkScoped
        _          <- TestClock.adjust(0.seconds)
        pending    <- cancelling.poll
        _          <- release.succeed(())
        exit       <- cancelling.join
        actual     <- saved.get
        snapshot   <- session.control.snapshot
      yield assertTrue(
        pending.isEmpty,
        exit.isInterrupted,
        actual.contains(next),
        snapshot == PlanSessionSnapshot.Planning(Some(next))
      )
    },
    test("a failed publication can be replaced successfully without reviving its old review") {
      val error = AgentError.PlanStorageError("first publication failed")
      for
        old      <- draft("old")
        next     <- draft("retry")
        saved    <- Ref.make(Option(old))
        attempts <- Ref.make(0)
        session <- PlanSession.stored(
          store(
            saved,
            value =>
              attempts.updateAndGet(_ + 1).flatMap {
                case 1 => ZIO.fail(error)
                case _ => saved.set(Some(value))
              }
          )
        )
        prior    <- session.control.requestReview
        failed   <- session.control.replaceDraft(next).either
        _        <- session.control.replaceDraft(next)
        review   <- session.control.requestReview
        stale    <- session.reviewer.resolve(prior, PlanReviewDecision.Approve)
        applied  <- session.reviewer.resolve(review, PlanReviewDecision.Approve)
        snapshot <- session.control.snapshot
      yield assertTrue(
        failed == Left(error),
        stale == PlanReviewResolution.NotCurrent,
        applied == PlanReviewResolution.Applied,
        review.revision.value > prior.revision.value,
        snapshot == PlanSessionSnapshot.Build(Some(ApprovedPlan(next, review.revision)))
      )
    },
    test("publication retains both a typed failure and a scope-cleanup defect") {
      val error   = AgentError.PlanStorageError("write failed")
      val cleanup = AgentError.PlanStorageError("cleanup failed")
      for
        next  <- draft("candidate")
        saved <- Ref.make(Option.empty[PlanDraft])
        session <- PlanSession.stored(
          store(
            saved,
            _ =>
              ZIO.scoped:
                ZIO.acquireRelease(ZIO.unit)(_ => ZIO.die(cleanup)) *> ZIO.fail(error)
          )
        )
        failed   <- session.control.replaceDraft(next).exit
        snapshot <- session.control.snapshot
      yield assertTrue(
        failed.causeOption.exists(cause =>
          cause.failures == List(error) && cause.defects == List(cleanup)
        ),
        snapshot == PlanSessionSnapshot.Planning(None)
      )
    }
  )
