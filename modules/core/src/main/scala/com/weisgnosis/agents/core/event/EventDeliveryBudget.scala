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
package com.weisgnosis.agents.core.event

import com.weisgnosis.agents.core.error.AgentError
import zio.json.*

/** Checked host limits for queued encoded diagnostics, not JVM heap or execution results. */
opaque type EventDeliveryBudget = (Int, Long, Long)

object EventDeliveryBudget:
  final private case class Limits(maxEvents: Int, maxQueuedBytes: Long, maxEventBytes: Long)
      derives JsonCodec

  /** Conservative host policy; these limits are not a measured performance optimum. */
  val default: EventDeliveryBudget = (256, 4L * 1024 * 1024, 1024L * 1024)

  extension (budget: EventDeliveryBudget)
    def maxEvents: Int       = budget._1
    def maxQueuedBytes: Long = budget._2
    def maxEventBytes: Long  = budget._3

  def fromLimits(
      maxEvents: Int,
      maxQueuedBytes: Long,
      maxEventBytes: Long
  ): Either[AgentError.InvalidConfiguration, EventDeliveryBudget] =
    if maxEvents <= 0 || maxQueuedBytes <= 0 || maxEventBytes <= 0 then
      Left(AgentError.InvalidConfiguration("Event delivery limits must be positive"))
    else if maxEventBytes > maxQueuedBytes then
      Left(
        AgentError.InvalidConfiguration("Individual event bytes cannot exceed queued event bytes")
      )
    else Right((maxEvents, maxQueuedBytes, maxEventBytes))

  /** Decode validation preserves the positive and aggregate-limit boundaries. */
  given JsonCodec[EventDeliveryBudget] = JsonCodec[Limits].transformOrFail(
    limits =>
      fromLimits(limits.maxEvents, limits.maxQueuedBytes, limits.maxEventBytes).left.map(_.message),
    budget => Limits(budget.maxEvents, budget.maxQueuedBytes, budget.maxEventBytes)
  )
