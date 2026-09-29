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

object PlanReviewPublisherSpec extends ZIOSpecDefault:
  def spec: Spec[TestEnvironment & Scope, Any] = suite("PlanReviewPublisherSpec")(
    test("publication is suspended and preserves the exact runtime ticket and display text") {
      for
        session   <- PlanSession.make
        draft     <- ZIO.fromEither(PlanDraft.fromString("exact draft"))
        _         <- session.control.enter *> session.control.replaceDraft(draft)
        review    <- session.control.requestReview
        delivered <- Ref.make(Chunk.empty[PlanReviewRequest])
        publisher = PlanReviewPublisher(request => delivered.update(_ :+ request))
        request   = PlanReviewRequest(review, Some("untrusted summary"))
        effect    = publisher.publish(request)
        before   <- delivered.get
        _        <- effect
        after    <- delivered.get
        snapshot <- session.control.snapshot
      yield assertTrue(
        before.isEmpty,
        after == Chunk(request),
        snapshot == PlanSessionSnapshot.AwaitingReview(draft, review.revision)
      )
    },
    test("typed publication failure retains scoped cleanup and does not resolve the review") {
      val error = AgentError.PlanStorageError("delivery failed")
      for
        session <- PlanSession.make
        draft   <- ZIO.fromEither(PlanDraft.fromString("draft"))
        _       <- session.control.enter *> session.control.replaceDraft(draft)
        review  <- session.control.requestReview
        cleaned <- Ref.make(false)
        publisher = PlanReviewPublisher(_ =>
          ZIO.acquireRelease(ZIO.unit)(_ => cleaned.set(true)) *> ZIO.fail(error)
        )
        exit     <- ZIO.scoped(publisher.publish(PlanReviewRequest(review, None))).exit
        cleanup  <- cleaned.get
        snapshot <- session.control.snapshot
      yield assertTrue(
        exit == Exit.fail(error),
        cleanup,
        snapshot == PlanSessionSnapshot.AwaitingReview(draft, review.revision)
      )
    }
  )
