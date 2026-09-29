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
import zio.json.*
import zio.test.*

object PlanSessionStateSpec extends ZIOSpecDefault:
  def spec: Spec[TestEnvironment & Scope, Any] = suite("PlanSessionStateSpec")(
    test("drafts reject blank input and preserve exact reviewable text") {
      val content = "  # Plan\n- Inspect\n- Verify\n"
      assertTrue(
        List("", " ", "\n\t").forall(PlanDraft.fromString(_).isLeft),
        PlanDraft
          .fromString(content)
          .exists(draft =>
            draft.content == content && draft.toJson.fromJson[PlanDraft] == Right(draft)
          ),
        "\" \"".fromJson[PlanDraft].isLeft,
        "1".fromJson[PlanDraft].isLeft
      )
    },
    test(
      "revisions reject coerced negative fractional values and advance beyond machine integer limits"
    ) {
      for revision <- ZIO.fromEither("18446744073709551616".fromJson[PlanRevision])
      yield assertTrue(
        revision.next.value == BigInt("18446744073709551617"),
        revision.toJson.fromJson[PlanRevision] == Right(revision),
        List("-1", "0.5", "\"1\"", "true", "{}").forall(_.fromJson[PlanRevision].isLeft)
      )
    },
    test("snapshots distinguish drafts pending review and actual approved content") {
      for
        draft    <- ZIO.fromEither(PlanDraft.fromString("plan"))
        revision <- ZIO.fromEither("1".fromJson[PlanRevision])
        approved = ApprovedPlan(draft, revision)
        snapshots = Chunk(
          PlanSessionSnapshot.Build(None),
          PlanSessionSnapshot.Build(Some(approved)),
          PlanSessionSnapshot.Planning(None),
          PlanSessionSnapshot.Planning(Some(draft)),
          PlanSessionSnapshot.AwaitingReview(draft, revision)
        )
      yield assertTrue(
        snapshots.forall(snapshot =>
          snapshot.toJson.fromJson[PlanSessionSnapshot] == Right(snapshot)
        ),
        snapshots.map(_.mode) == Chunk(
          PlanMode.Build,
          PlanMode.Build,
          PlanMode.Plan,
          PlanMode.Plan,
          PlanMode.Plan
        )
      )
    },
    test("review decisions resolutions and terminal outcomes retain typed codecs") {
      for
        draft    <- ZIO.fromEither(PlanDraft.fromString("plan"))
        revision <- ZIO.fromEither("1".fromJson[PlanRevision])
        outcomes = Chunk(
          PlanReviewOutcome.Approved(ApprovedPlan(draft, revision)),
          PlanReviewOutcome.Declined,
          PlanReviewOutcome.Superseded,
          PlanReviewOutcome.Cancelled
        )
      yield assertTrue(
        outcomes.forall(outcome => outcome.toJson.fromJson[PlanReviewOutcome] == Right(outcome)),
        PlanReviewDecision.values.forall(value =>
          value.toJson.fromJson[PlanReviewDecision] == Right(value)
        ),
        PlanReviewResolution.values.forall(value =>
          value.toJson.fromJson[PlanReviewResolution] == Right(value)
        )
      )
    }
  )
