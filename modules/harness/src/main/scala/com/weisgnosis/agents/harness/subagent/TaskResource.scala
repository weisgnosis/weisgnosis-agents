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

import zio.*

/**
 * Host-held resource lifetime, also bounded by the owning supervisor and acquiring caller Scope.
 * Owned workers and finalizers must not close or wait for their own resource owner.
 */
final class TaskResource[+A] private[subagent] (
    val value: A,
    owner: TaskResourceOwner
):
  /** Reject nested admission, join initialization and all workers, then release acquired resources. */
  def close: UIO[Unit] = ZIO.uninterruptible(owner.close(Exit.unit))

enum TaskResourceAdmission[+A]:
  case Accepted(resource: TaskResource[A])
  case Closed

/** All library-owned closure paths share one finalization and its complete outcome. */
final private[subagent] class TaskResourceOwner private (
    val scope: Scope.Closeable,
    registration: Scope.Closeable,
    phase: Ref[TaskResourceOwner.Phase],
    completion: Promise[Nothing, Exit[Nothing, Unit]]
):
  def close(exit: Exit[Any, Any]): UIO[Unit] =
    ZIO.uninterruptible:
      phase
        .getAndSet(TaskResourceOwner.Phase.Closing)
        .flatMap:
          case TaskResourceOwner.Phase.Open =>
            scope.close(exit).exit.flatMap(completion.succeed).unit *>
              // Publish first: detaching replays completion without reentering the closer. Its
              // defect must not be appended to the authoritative cause twice.
              registration.close(exit).exit.unit *>
              completion.await.flatMap(result => result)
          case TaskResourceOwner.Phase.Closing => completion.await.flatMap(result => result)

private[subagent] object TaskResourceOwner:
  private enum Phase:
    case Open, Closing

  def make(parent: Scope): UIO[TaskResourceOwner] =
    ZIO.uninterruptible:
      for
        scope        <- Scope.make
        registration <- parent.forkWith(ExecutionStrategy.Sequential)
        phase        <- Ref.make(Phase.Open)
        completion   <- Promise.make[Nothing, Exit[Nothing, Unit]]
        owner = new TaskResourceOwner(scope, registration, phase, completion)
        // The parent owns this registration, never the raw resource scope.
        _ <- registration.addFinalizerExit(exit =>
          completion.poll.flatMap:
            case Some(result) => result.flatMap(outcome => outcome)
            case None         => owner.close(exit)
        )
      yield owner
