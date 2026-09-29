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

import zio.*
import zio.test.*

object PlanSessionSpec extends ZIOSpecDefault:
  def spec: Spec[TestEnvironment & Scope, Any] = suite("PlanSessionSpec")(
    test("initial Build and draft-free Plan cannot request approval or fabricate a written plan") {
      for
        session     <- PlanSession.make
        draft       <- ZIO.fromEither(PlanDraft.fromString("draft"))
        initial     <- session.control.snapshot
        write       <- session.control.replaceDraft(draft).exit
        buildReview <- session.control.requestReview.exit
        _           <- session.control.enter
        planning    <- session.control.snapshot
        emptyReview <- session.control.requestReview.exit
      yield assertTrue(
        initial == PlanSessionSnapshot.Build(None),
        write.isFailure,
        buildReview.isFailure,
        planning == PlanSessionSnapshot.Planning(None),
        emptyReview.isFailure
      )
    },
    test("approval consumes the exact review and records exact draft and revision") {
      for
        session <- PlanSession.make
        draft <- ZIO.fromEither(PlanDraft.fromString("  # Review me\n- Execute this exact draft\n"))
        _     <- session.control.enter
        _     <- session.control.replaceDraft(draft)
        review     <- session.control.requestReview
        before     <- session.control.snapshot
        resolution <- session.reviewer.resolve(review, PlanReviewDecision.Approve)
        outcome    <- review.await
        after      <- session.control.snapshot
        replay     <- session.reviewer.resolve(review, PlanReviewDecision.Approve)
      yield assertTrue(
        before == PlanSessionSnapshot.AwaitingReview(draft, review.revision),
        resolution == PlanReviewResolution.Applied,
        replay == PlanReviewResolution.NotCurrent,
        outcome == PlanReviewOutcome.Approved(ApprovedPlan(draft, review.revision)),
        after == PlanSessionSnapshot.Build(Some(ApprovedPlan(draft, review.revision)))
      )
    },
    test("decline consumes only the pending review and preserves a revisable draft in Plan") {
      for
        session <- PlanSession.make
        draft   <- ZIO.fromEither(PlanDraft.fromString("draft"))
        _       <- session.control.enter
        _       <- session.control.replaceDraft(draft)
        review  <- session.control.requestReview
        result  <- session.reviewer.resolve(review, PlanReviewDecision.Decline)
        outcome <- review.await
        after   <- session.control.snapshot
        next    <- session.control.requestReview
      yield assertTrue(
        result == PlanReviewResolution.Applied,
        outcome == PlanReviewOutcome.Declined,
        after == PlanSessionSnapshot.Planning(Some(draft)),
        next.revision.value > review.revision.value
      )
    },
    test("draft replacement settles the old waiter and makes stale approval inert") {
      for
        session <- PlanSession.make
        first   <- ZIO.fromEither(PlanDraft.fromString("first"))
        second  <- ZIO.fromEither(PlanDraft.fromString("second"))
        _       <- session.control.enter
        _       <- session.control.replaceDraft(first)
        review  <- session.control.requestReview
        _       <- session.control.replaceDraft(second)
        outcome <- review.await
        stale   <- session.reviewer.resolve(review, PlanReviewDecision.Approve)
        after   <- session.control.snapshot
      yield assertTrue(
        outcome == PlanReviewOutcome.Superseded,
        stale == PlanReviewResolution.NotCurrent,
        after == PlanSessionSnapshot.Planning(Some(second))
      )
    },
    test("re-entry invalidates review and prior approval while preserving draft content") {
      for
        session    <- PlanSession.make
        draft      <- ZIO.fromEither(PlanDraft.fromString("draft"))
        _          <- session.control.enter
        _          <- session.control.replaceDraft(draft)
        old        <- session.control.requestReview
        _          <- session.control.enter
        superseded <- old.await
        stale      <- session.reviewer.resolve(old, PlanReviewDecision.Approve)
        current    <- session.control.requestReview
        _          <- session.reviewer.resolve(current, PlanReviewDecision.Approve)
        _          <- session.control.enter
        after      <- session.control.snapshot
        replay     <- session.reviewer.resolve(current, PlanReviewDecision.Approve)
      yield assertTrue(
        superseded == PlanReviewOutcome.Superseded,
        stale == PlanReviewResolution.NotCurrent,
        current.revision.value > old.revision.value,
        after == PlanSessionSnapshot.Planning(Some(draft)),
        replay == PlanReviewResolution.NotCurrent
      )
    },
    test("invalid draft decoding leaves current approval request unchanged") {
      for
        session <- PlanSession.make
        draft   <- ZIO.fromEither(PlanDraft.fromString("draft"))
        _       <- session.control.enter
        _       <- session.control.replaceDraft(draft)
        review  <- session.control.requestReview
        before  <- session.control.snapshot
        invalid <- ZIO
          .fromEither(PlanDraft.fromString(" "))
          .flatMap(session.control.replaceDraft)
          .exit
        after    <- session.control.snapshot
        resolved <- session.reviewer.resolve(review, PlanReviewDecision.Approve)
      yield assertTrue(invalid.isFailure, before == after, resolved == PlanReviewResolution.Applied)
    },
    test("the session mode feeds the existing permission gate without a mirrored flag") {
      for
        session <- PlanSession.make
        gate = PlanPermissionGate.make(
          session.control.mode,
          Map("write" -> PlanToolAccess.Mutation)
        )
        initial    <- gate.evaluate("write", zio.json.ast.Json.Obj())
        _          <- session.control.enter
        restricted <- gate.evaluate("write", zio.json.ast.Json.Obj())
        draft      <- ZIO.fromEither(PlanDraft.fromString("approved draft"))
        _          <- session.control.replaceDraft(draft)
        review     <- session.control.requestReview
        _          <- session.reviewer.resolve(review, PlanReviewDecision.Approve)
        approved   <- gate.evaluate("write", zio.json.ast.Json.Obj())
      yield assertTrue(
        initial.isEmpty,
        restricted.contains(com.weisgnosis.agents.harness.permission.PermissionBehavior.Deny),
        approved.isEmpty
      )
    }
  )
