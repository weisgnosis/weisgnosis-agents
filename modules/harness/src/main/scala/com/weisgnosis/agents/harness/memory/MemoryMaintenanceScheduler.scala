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

/** Reuse one scheduler across conversations sharing this memory owner. */
final class MemoryMaintenanceScheduler private (task: MemoryMaintenanceTask):
  def submit: UIO[MemoryTaskSubmission] = task.submit

  def completionObserver(
      registerTicket: MemoryTaskTicket => IO[AgentError.CompletionObservationError, Unit]
  ): TurnCompletionObserver = task.completionObserver(registerTicket)

object MemoryMaintenanceScheduler:
  /** Reporting follows work and cannot roll back committed memory or archival. */
  def make(
      queue: MemoryTaskQueue,
      gate: MemoryTriggerGate,
      scope: MemoryScope,
      maintenance: MemoryMaintenance,
      report: MemoryMaintenanceResult => IO[AgentError, Unit]
  ): UIO[MemoryMaintenanceScheduler] =
    MemoryMaintenanceTask
      .make(queue, gate, scope, MemoryMaintenanceTask.Kind.Maintenance)(
        maintenance.run.flatMap(result => ZIO.suspendSucceed(report(result)))
      )
      .map(new MemoryMaintenanceScheduler(_))
