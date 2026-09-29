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

object PlanContextSpec extends ZIOSpecDefault:
  def spec: Spec[TestEnvironment & Scope, Any] = suite("PlanContextSpec")(
    test(
      "complete Unicode code points reject hidden formatting and malformed surrogate sequences"
    ) {
      val invalid = Chunk("a\uDB40\uDC01b", "a\uDB40\uDC61b", "a\uD800b", "a\uDC00b", "\uD800")
      val visible = "plans/\uD83D\uDE00/PLAN.md"
      assertTrue(
        invalid.forall(PlanLocation.fromString(_).isLeft),
        invalid.forall(_.toJson.fromJson[PlanLocation].isLeft),
        PlanLocation
          .fromString(visible)
          .exists(location =>
            location.text == visible &&
              location.toJson.fromJson[PlanLocation] == Right(location)
          )
      )
    },
    test("locations preserve exact descriptive text and derive no filesystem authority") {
      val text = "remote:plans/quoted \"draft\"/PLAN.md"
      for location <- ZIO.fromEither(PlanLocation.fromString(text))
      yield assertTrue(
        location.text == text,
        location.toJson.fromJson[PlanLocation] == Right(location),
        PlanLocation.fromString("../description-only").isRight
      )
    },
    test("location validation rejects blank control formatting and line-separator input") {
      val invalid =
        Chunk("", " ", "a\nb", "a\tb", "a\u0000b", "a\u007fb", "a\u202eb", "a\u2028b", "a\u2029b")
      assertTrue(
        invalid.forall(PlanLocation.fromString(_).isLeft),
        invalid.forall(_.toJson.fromJson[PlanLocation].isLeft),
        "42".fromJson[PlanLocation].isLeft
      )
    },
    test("context codecs preserve optional location and snapshots without restoring authority") {
      for
        location <- ZIO.fromEither(PlanLocation.fromString("plans/PLAN.md"))
        draft    <- ZIO.fromEither(PlanDraft.fromString("draft"))
        revision <- ZIO.fromEither("7".fromJson[PlanRevision])
        states = Chunk(
          PlanSessionSnapshot.Build(None),
          PlanSessionSnapshot.Planning(None),
          PlanSessionSnapshot.Planning(Some(draft)),
          PlanSessionSnapshot.AwaitingReview(draft, revision),
          PlanSessionSnapshot.Build(Some(ApprovedPlan(draft, revision)))
        )
        values = states.flatMap(state =>
          Chunk(PlanContext(state), PlanContext(state, Some(location)))
        )
      yield assertTrue(values.forall(value => value.toJson.fromJson[PlanContext] == Right(value)))
    },
    test("configured and observed location text is JSON-quoted and fresh Build remains unchanged") {
      for
        location <- ZIO.fromEither(PlanLocation.fromString("plans/\"quoted\"/PLAN.md"))
        draft    <- ZIO.fromEither(PlanDraft.fromString("draft"))
        revision <- ZIO.fromEither("8".fromJson[PlanRevision])
        configured = PlanPrompt.fromContext(
          PlanContext(PlanSessionSnapshot.Planning(None), Some(location))
        )
        observed = PlanPrompt.fromContext(
          PlanContext(PlanSessionSnapshot.Planning(Some(draft)), Some(location))
        )
        approved = PlanPrompt.fromContext(
          PlanContext(
            PlanSessionSnapshot.Build(Some(ApprovedPlan(draft, revision))),
            Some(location)
          )
        )
      yield assertTrue(
        configured.exists(_.contains("Configured draft destination")),
        configured.forall(text => !text.contains("last successful session read")),
        Chunk(configured, observed, approved).forall(_.exists(_.contains(location.text.toJson))),
        observed.exists(_.contains("last successful session read or publication")),
        approved.exists(_.contains("Approval applies to the captured draft and revision")),
        PlanPrompt
          .fromContext(PlanContext(PlanSessionSnapshot.Build(None), Some(location)))
          .isEmpty,
        PlanPrompt.fromContext(PlanContext(PlanSessionSnapshot.Planning(Some(draft)))) ==
          PlanPrompt.render(PlanSessionSnapshot.Planning(Some(draft)))
      )
    }
  )
