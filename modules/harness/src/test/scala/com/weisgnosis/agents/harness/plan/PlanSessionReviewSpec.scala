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
import zio.*
import zio.test.*

object PlanSessionReviewSpec extends ZIOSpecDefault:
  private def prepared: IO[AgentError.InvalidMessage, PlanSession] =
    for
      session <- PlanSession.make
      draft   <- ZIO.fromEither(PlanDraft.fromString("reviewed draft"))
      _       <- session.control.enter
      _       <- session.control.replaceDraft(draft)
    yield session

  def spec: Spec[TestEnvironment & Scope, Any] = suite("PlanSessionReviewSpec")(
    test("closing a review scope cancels its waiter and preserves the unapproved draft") {
      for
        session  <- prepared
        owner    <- ZIO.acquireRelease(Scope.make)(_.close(Exit.unit))
        review   <- owner.extend(session.control.requestReview)
        _        <- owner.close(Exit.unit)
        outcome  <- review.await
        stale    <- session.reviewer.resolve(review, PlanReviewDecision.Approve)
        snapshot <- session.control.snapshot
      yield assertTrue(
        outcome == PlanReviewOutcome.Cancelled,
        stale == PlanReviewResolution.NotCurrent,
        snapshot == PlanSessionSnapshot.Planning(Some(review.draft))
      )
    },
    test("closing an old scope cannot cancel its newer replacement request") {
      for
        session    <- prepared
        firstOwner <- ZIO.acquireRelease(Scope.make)(_.close(Exit.unit))
        first      <- firstOwner.extend(session.control.requestReview)
        second     <- session.control.requestReview
        superseded <- first.await
        _          <- firstOwner.close(Exit.unit)
        before     <- session.control.snapshot
        resolved   <- session.reviewer.resolve(second, PlanReviewDecision.Approve)
        outcome    <- second.await
      yield assertTrue(
        superseded == PlanReviewOutcome.Superseded,
        before == PlanSessionSnapshot.AwaitingReview(second.draft, second.revision),
        resolved == PlanReviewResolution.Applied,
        outcome == PlanReviewOutcome.Approved(ApprovedPlan(second.draft, second.revision))
      )
    },
    test("foreign session authority cannot consume another session's identity capability") {
      for
        first        <- prepared
        second       <- prepared
        firstReview  <- first.control.requestReview
        secondReview <- second.control.requestReview
        before       <- first.control.snapshot.zip(second.control.snapshot)
        foreign      <- first.reviewer.resolve(secondReview, PlanReviewDecision.Approve)
        reverse      <- second.reviewer.resolve(firstReview, PlanReviewDecision.Decline)
        after        <- first.control.snapshot.zip(second.control.snapshot)
        own          <- first.reviewer.resolve(firstReview, PlanReviewDecision.Approve)
        other        <- second.reviewer.resolve(secondReview, PlanReviewDecision.Decline)
      yield assertTrue(
        firstReview.revision == secondReview.revision,
        foreign == PlanReviewResolution.NotCurrent,
        reverse == PlanReviewResolution.NotCurrent,
        before == after,
        own == PlanReviewResolution.Applied,
        other == PlanReviewResolution.Applied
      )
    },
    test("concurrent approval consumes a review exactly once and completes every observer") {
      for
        session   <- prepared
        review    <- session.control.requestReview
        observers <- ZIO.foreach(1 to 8)(_ => review.await.forkScoped)
        decisions <- ZIO.foreachPar(1 to 16)(_ =>
          session.reviewer.resolve(review, PlanReviewDecision.Approve)
        )
        results <- ZIO.foreach(observers)(_.join)
      yield assertTrue(
        decisions.count(_ == PlanReviewResolution.Applied) == 1,
        decisions.count(_ == PlanReviewResolution.NotCurrent) == 15,
        results.forall(_ == PlanReviewOutcome.Approved(ApprovedPlan(review.draft, review.revision)))
      )
    },
    test("draft replacement racing approval has only the two valid atomic outcomes") {
      for
        next <- ZIO.fromEither(PlanDraft.fromString("replacement"))
        results <- ZIO.foreach(1 to 30): _ =>
          ZIO.scoped:
            for
              session <- prepared
              review  <- session.control.requestReview
              raced <- session.control
                .replaceDraft(next)
                .exit
                .zipPar(session.reviewer.resolve(review, PlanReviewDecision.Approve))
              snapshot <- session.control.snapshot
              outcome  <- review.await
            yield raced match
              case (Exit.Success(_), PlanReviewResolution.NotCurrent) =>
                snapshot == PlanSessionSnapshot.Planning(
                  Some(next)
                ) && outcome == PlanReviewOutcome.Superseded
              case (Exit.Failure(_), PlanReviewResolution.Applied) =>
                snapshot == PlanSessionSnapshot.Build(
                  Some(ApprovedPlan(review.draft, review.revision))
                ) &&
                outcome == PlanReviewOutcome.Approved(ApprovedPlan(review.draft, review.revision))
              case _ => false
      yield assertTrue(results.forall(identity))
    },
    test(
      "interrupting the review-owning fiber invalidates its request before cancellation returns"
    ) {
      for
        session   <- prepared
        published <- Promise.make[Nothing, PlanReview]
        fiber <- ZIO
          .scoped(
            session.control.requestReview
              .flatMap(review => published.succeed(review) *> review.await)
          )
          .forkScoped
        review      <- published.await
        interrupted <- fiber.interrupt
        outcome     <- review.await
        snapshot    <- session.control.snapshot
        resolution  <- session.reviewer.resolve(review, PlanReviewDecision.Approve)
      yield assertTrue(
        interrupted.isInterrupted,
        outcome == PlanReviewOutcome.Cancelled,
        snapshot == PlanSessionSnapshot.Planning(Some(review.draft)),
        resolution == PlanReviewResolution.NotCurrent
      )
    },
    test(
      "closing an approved request scope cannot undo approval or cancel a later planning generation"
    ) {
      for
        session    <- prepared
        owner      <- ZIO.acquireRelease(Scope.make)(_.close(Exit.unit))
        first      <- owner.extend(session.control.requestReview)
        _          <- session.reviewer.resolve(first, PlanReviewDecision.Approve)
        _          <- session.control.enter
        second     <- session.control.requestReview
        _          <- owner.close(Exit.unit)
        snapshot   <- session.control.snapshot
        original   <- first.await
        resolution <- session.reviewer.resolve(second, PlanReviewDecision.Approve)
      yield assertTrue(
        snapshot == PlanSessionSnapshot.AwaitingReview(second.draft, second.revision),
        original == PlanReviewOutcome.Approved(ApprovedPlan(first.draft, first.revision)),
        resolution == PlanReviewResolution.Applied
      )
    }
  )
