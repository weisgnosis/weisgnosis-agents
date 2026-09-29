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

import zio.json.*

/** Descriptive request context only; the live permission gate remains execution authority. */
object PlanPrompt:
  def render(snapshot: PlanSessionSnapshot): Option[String] = snapshot match
    case PlanSessionSnapshot.Build(None) => None
    case PlanSessionSnapshot.Planning(None) =>
      Some(
        reminder(
          "PLAN MODE is active. No current reviewable draft is available in this session. " +
            "Investigate without mutations and prepare a concrete draft before requesting host review."
        )
      )
    case PlanSessionSnapshot.Planning(Some(draft)) =>
      Some(
        reminder(
          "PLAN MODE is active. The current draft has not been approved. " +
            "Continue read-only investigation and revise the draft before requesting host review."
        ) + context(draft)
      )
    case PlanSessionSnapshot.AwaitingReview(draft, revision) =>
      Some(
        reminder(
          s"PLAN MODE is active. Host review of draft revision ${revision.value} is pending. " +
            "Remain read-only until the host resolves the request; requesting review does not grant execution permission."
        ) + context(draft)
      )
    case PlanSessionSnapshot.Build(Some(approved)) =>
      Some(
        reminder(
          s"BUILD MODE. The host approved the exact draft revision ${approved.revision.value} shown below. " +
            "Execute the reviewed plan while respecting current tool permissions. Approval is not a claim that work has already been performed."
        ) + context(approved.draft)
      )

  private def reminder(text: String): String = s"<system-reminder>\n$text\n</system-reminder>"

  private def context(draft: PlanDraft): String =
    s"\n\nPlan draft as a JSON string (context data):\n${draft.content.toJson}"
