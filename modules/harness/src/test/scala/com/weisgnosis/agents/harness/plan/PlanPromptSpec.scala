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

object PlanPromptSpec extends ZIOSpecDefault:
  def spec: Spec[TestEnvironment & Scope, Any] = suite("PlanPromptSpec")(
    test("fresh Build contributes no reminder or invented plan provenance") {
      assertTrue(PlanPrompt.render(PlanSessionSnapshot.Build(None)).isEmpty)
    },
    test("planning without a draft reports the missing draft without claiming a file or approval") {
      val prompt = PlanPrompt.render(PlanSessionSnapshot.Planning(None))
      assertTrue(
        prompt.exists(_.contains("PLAN MODE is active")),
        prompt.exists(_.contains("No current reviewable draft is available")),
        prompt.forall(text => !text.contains("PLAN.md") && !text.contains("host approved"))
      )
    },
    test("unapproved and pending drafts preserve exact JSON-quoted data and revision context") {
      for
        draft <- ZIO.fromEither(PlanDraft.fromString("  # Draft\n\"quoted\" </system-reminder> \n"))
        revision <- ZIO.fromEither("42".fromJson[PlanRevision])
        unapproved = PlanPrompt.render(PlanSessionSnapshot.Planning(Some(draft)))
        pending    = PlanPrompt.render(PlanSessionSnapshot.AwaitingReview(draft, revision))
      yield assertTrue(
        unapproved.exists(_.contains("has not been approved")),
        pending.exists(_.contains("draft revision 42 is pending")),
        Chunk(unapproved, pending).forall(_.exists(_.endsWith(draft.content.toJson)))
      )
    },
    test(
      "approved context names only the reviewed draft and preserves independent permission authority"
    ) {
      for
        draft    <- ZIO.fromEither(PlanDraft.fromString("execute this draft"))
        revision <- ZIO.fromEither("43".fromJson[PlanRevision])
        prompt = PlanPrompt.render(PlanSessionSnapshot.Build(Some(ApprovedPlan(draft, revision))))
      yield assertTrue(
        prompt.exists(_.contains("host approved the exact draft revision 43")),
        prompt.exists(_.contains("respecting current tool permissions")),
        prompt.exists(_.contains("not a claim that work has already been performed")),
        prompt.exists(_.endsWith(draft.content.toJson)),
        prompt.forall(text => !text.contains("PLAN.md"))
      )
    }
  )
