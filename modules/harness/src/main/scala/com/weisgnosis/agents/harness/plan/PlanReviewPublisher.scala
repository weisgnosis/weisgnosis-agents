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

/** Runtime ticket and optional untrusted display text; neither is a serialized approval credential. */
final case class PlanReviewRequest(review: PlanReview, summary: Option[String])

/** Announce the exact ticket to an authenticated host; only its separate reviewer can resolve it. */
trait PlanReviewPublisher:
  def publish(request: PlanReviewRequest): ZIO[Scope, AgentError, Unit]

object PlanReviewPublisher:
  def apply(deliver: PlanReviewRequest => ZIO[Scope, AgentError, Unit]): PlanReviewPublisher =
    new PlanReviewPublisher:
      def publish(request: PlanReviewRequest): ZIO[Scope, AgentError, Unit] =
        ZIO.suspendSucceed(deliver(request))
