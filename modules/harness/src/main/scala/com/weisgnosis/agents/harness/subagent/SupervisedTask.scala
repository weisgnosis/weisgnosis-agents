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

/** Runtime authority, deliberately not serializable or reconstructible from model output. */
final class SupervisedTask[+A] private[subagent] (
    fiber: Fiber.Runtime[AgentError, A],
    ownership: Ref[SupervisedTask.Ownership]
):
  import SupervisedTask.Ownership

  def await: UIO[Exit[AgentError, A]] = fiber.await

  /** Observes completion only after the task's owned cleanup has finished. */
  def poll: UIO[Option[Exit[AgentError, A]]] = fiber.poll

  /** Cancels even promoted work and waits for all task-owned cleanup. */
  def cancel: UIO[Exit[AgentError, A]] =
    ZIO.uninterruptible(ownership.set(Ownership.Released) *> fiber.interrupt)

  /** The deadline starts with this wait; it does not restart the underlying task. */
  def awaitOrPromote: UIO[TaskWait[A]] =
    ZIO.uninterruptibleMask: restore =>
      restore(
        fiber.await.map(Some(_)).raceFirst(ZIO.sleep(30.seconds).as(None))
      ).flatMap:
        case Some(exit) => ZIO.succeed(TaskWait.Completed(exit))
        case None =>
          ownership
            .modify {
              case Ownership.Attached => (Ownership.Promoted, Ownership.Promoted)
              case current            => (current, current)
            }
            .flatMap:
              case Ownership.Promoted => ZIO.succeed(TaskWait.Promoted)
              case Ownership.Attached | Ownership.Released =>
                restore(fiber.await).map(TaskWait.Completed(_))

  private[subagent] def release: UIO[Unit] =
    ZIO.uninterruptible:
      ownership
        .modify {
          case Ownership.Attached => (Ownership.Attached, Ownership.Released)
          case current            => (current, current)
        }
        .flatMap:
          case Ownership.Attached => fiber.interrupt.unit
          case Ownership.Promoted => ZIO.unit
          case Ownership.Released => fiber.await.unit

object SupervisedTask:
  private[subagent] enum Ownership:
    case Attached, Promoted, Released
