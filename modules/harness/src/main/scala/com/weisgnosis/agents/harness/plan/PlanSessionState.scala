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
import zio.json.*
import zio.json.ast.Json

opaque type PlanDraft = String

object PlanDraft:
  def fromString(content: String): Either[AgentError.InvalidMessage, PlanDraft] =
    if content.isBlank then
      Left(AgentError.InvalidMessage("A reviewable plan draft must not be blank"))
    else Right(content)

  given JsonCodec[PlanDraft] = JsonCodec.string.transformOrFail(
    content => fromString(content).left.map(_.message),
    content => content
  )

  extension (draft: PlanDraft) def content: String = draft

opaque type PlanRevision = BigInt

object PlanRevision:
  private[plan] val Initial: PlanRevision = BigInt(0)

  given JsonCodec[PlanRevision] = summon[JsonCodec[Json]].transformOrFail(
    {
      case Json.Num(value) =>
        BigDecimal(value).toBigIntExact
          .filter(_ >= 0)
          .toRight("Plan revision must be a nonnegative integer")
      case _ => Left("Plan revision must be a JSON number")
    },
    revision => Json.Num(BigDecimal(revision))
  )

  extension (revision: PlanRevision)
    def value: BigInt                    = revision
    private[plan] def next: PlanRevision = revision + 1

final case class ApprovedPlan(draft: PlanDraft, revision: PlanRevision) derives JsonCodec

enum PlanSessionSnapshot derives JsonCodec:
  case Build(approved: Option[ApprovedPlan])
  case Planning(draft: Option[PlanDraft])
  case AwaitingReview(draft: PlanDraft, revision: PlanRevision)

  def mode: PlanMode = this match
    case Build(_)                           => PlanMode.Build
    case Planning(_) | AwaitingReview(_, _) => PlanMode.Plan

enum PlanReviewDecision derives JsonCodec:
  case Approve, Decline

enum PlanReviewOutcome derives JsonCodec:
  case Approved(plan: ApprovedPlan)
  case Declined, Superseded, Cancelled

enum PlanReviewResolution derives JsonCodec:
  case Applied, NotCurrent
