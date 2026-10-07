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

import com.weisgnosis.agents.core.error.AgentError
import zio.*

/** Share one capability per memory domain; admission is not an execution lock or completion record. */
trait MemoryTriggerGate:
  def claim(
      operation: MemoryOperation,
      scope: MemoryScope
  ): IO[AgentError, MemoryTriggerDecision]

  /** Remove old, already elapsed reservations without reopening an active window. */
  def cleanup(retention: MemoryInterval): IO[AgentError, Int]

object MemoryTriggerGate:
  final private case class Key(operation: MemoryOperation, scope: MemoryScope)
  final private case class Reservation(claimedAt: Long, gap: MemoryInterval)

  def inMemory(policy: MemoryTriggerPolicy = MemoryTriggerPolicy()): UIO[MemoryTriggerGate] =
    Ref.Synchronized
      .make(Map.empty[Key, Reservation])
      .map: reservations =>
        new MemoryTriggerGate:
          def claim(
              operation: MemoryOperation,
              scope: MemoryScope
          ): IO[AgentError, MemoryTriggerDecision] =
            val trigger = operation match
              case MemoryOperation.Flush       => policy.flush
              case MemoryOperation.Maintenance => policy.maintenance
            trigger match
              case MemoryTrigger.Always => ZIO.succeed(MemoryTriggerDecision.Admitted)
              case MemoryTrigger.Never  => ZIO.succeed(MemoryTriggerDecision.Disabled)
              case MemoryTrigger.Throttled(gap) =>
                val key = Key(operation, MemoryScope.normalized(scope))
                reservations.modifyZIO: current =>
                  Clock.nanoTime.map: now =>
                    current.get(key) match
                      case Some(previous) if now - previous.claimedAt < gap.nanos =>
                        MemoryTriggerDecision.Throttled -> current
                      case _ =>
                        MemoryTriggerDecision.Admitted -> current.updated(
                          key,
                          Reservation(now, gap)
                        )

          def cleanup(retention: MemoryInterval): IO[AgentError, Int] =
            reservations.modifyZIO: current =>
              Clock.nanoTime.map: now =>
                val retained = current.filter: (_, reservation) =>
                  val elapsed = now - reservation.claimedAt
                  elapsed < reservation.gap.nanos || elapsed <= retention.nanos
                (current.size - retained.size) -> retained
