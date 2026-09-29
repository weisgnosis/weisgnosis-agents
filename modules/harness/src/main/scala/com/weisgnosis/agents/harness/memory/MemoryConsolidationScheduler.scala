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
package com.weisgnosis.agents.harness.memory

import com.weisgnosis.agents.core.agent.TurnCompletionObserver
import com.weisgnosis.agents.core.error.AgentError
import zio.*

/** One memory owner's consolidation work; this does not archive ledgers or delete session files. */
final class MemoryConsolidationScheduler private (
    queue: MemoryTaskQueue,
    gate: MemoryTriggerGate,
    scope: MemoryScope,
    consolidator: MemoryConsolidator,
    report: ConsolidationResult => IO[AgentError, Unit],
    owner: MemoryConversationToken
):
  /** Gate admission and the latest store snapshot are resolved only in the owned worker. */
  def submit: UIO[MemoryTaskSubmission] =
    queue.submit(owner):
      gate
        .claim(MemoryOperation.Maintenance, scope)
        .flatMap:
          case MemoryTriggerDecision.Admitted =>
            consolidator.consolidate.flatMap(result => ZIO.suspendSucceed(report(result)))
          case MemoryTriggerDecision.Disabled | MemoryTriggerDecision.Throttled => ZIO.unit

  /** The host retains accepted tickets; reporting failures cannot roll back admitted work. */
  def completionObserver(
      registerTicket: MemoryTaskTicket => IO[AgentError.CompletionObservationError, Unit]
  ): TurnCompletionObserver =
    TurnCompletionObserver: completion =>
      completion.outcome match
        case Exit.Failure(_) => ZIO.unit
        case Exit.Success(_) =>
          submit.flatMap:
            case MemoryTaskSubmission.Accepted(ticket) => ZIO.suspendSucceed(registerTicket(ticket))
            case MemoryTaskSubmission.Rejected(reason) =>
              ZIO.fail(
                AgentError.CompletionObservationError(
                  s"Memory consolidation scheduling rejected: $reason"
                )
              )

object MemoryConsolidationScheduler:
  /** Reuse this scheduler across conversations sharing its owner to coalesce pending maintenance. */
  def make(
      queue: MemoryTaskQueue,
      gate: MemoryTriggerGate,
      scope: MemoryScope,
      consolidator: MemoryConsolidator,
      report: ConsolidationResult => IO[AgentError, Unit]
  ): UIO[MemoryConsolidationScheduler] =
    MemoryConversationToken.make.map(
      new MemoryConsolidationScheduler(queue, gate, scope, consolidator, report, _)
    )
