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
package com.weisgnosis.agents.harness.subagent

import com.weisgnosis.agents.core.error.AgentError
import zio.*

/**
 * Host callback after joined execution and confirmed publication. This never acknowledges delivery.
 * Callbacks must join children whose success matters and bound their own runtime. They must not
 * close the registry, supervisor or borrowed owner scope, await their own execution, or introduce
 * cross-owner callback cycles. The callback scope joins owned cleanup before recording success.
 */
trait SubagentCompletionNotifier:
  def observe(
      record: SubagentTerminalRecord,
      publication: SubagentTerminalPublication
  ): ZIO[Scope, AgentError.CompletionObservationError, Unit]

object SubagentCompletionNotifier:
  def apply(
      callback: (SubagentTerminalRecord, SubagentTerminalPublication) => ZIO[
        Scope,
        AgentError.CompletionObservationError,
        Unit
      ]
  ): SubagentCompletionNotifier = new SubagentCompletionNotifier:
    def observe(
        record: SubagentTerminalRecord,
        publication: SubagentTerminalPublication
    ): ZIO[Scope, AgentError.CompletionObservationError, Unit] =
      ZIO.suspendSucceed(callback(record, publication))

/** Trusted host policy captured per submission; model metadata cannot change this decision. */
enum SubagentCompletionNotification:
  case Suppress
  case Notify(callback: SubagentCompletionNotifier)

/**
 * Runtime receipt, not durable delivery or proof of an external wakeup. Publication failure remains
 * separately observable through the publication port and is retained here without rethrowing it.
 */
enum SubagentCompletionReceipt:
  case Suppressed, Observed
  case NotPublished(cause: Cause[AgentError])

final case class SubagentTaskCompletionReceipt(
    id: SubagentTaskId,
    receipt: SubagentCompletionReceipt
)
