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

/** Host-owned execution scope; attach supplies a separate parent cancellation lease. */
trait TaskSupervisor[A]:
  def attach(task: => ZIO[Scope, AgentError, A]): ZIO[Scope, Nothing, TaskAdmission[A]]

object TaskSupervisor:
  private enum Admission:
    case Open, Closed

  def open[A]: ZIO[Scope, Nothing, TaskSupervisor[A]] =
    ZIO.uninterruptible:
      for
        owner     <- Scope.parallel
        gate      <- Semaphore.make(1)
        admission <- Ref.make(Admission.Open)
        live = new Live[A](owner, gate, admission)
        _ <- ZIO.addFinalizer(live.close)
      yield live

  final private class Live[A](
      owner: Scope.Closeable,
      gate: Semaphore,
      admission: Ref[Admission]
  ) extends TaskSupervisor[A]:
    def attach(task: => ZIO[Scope, AgentError, A]): ZIO[Scope, Nothing, TaskAdmission[A]] =
      ZIO.acquireRelease(launch(ZIO.suspendSucceed(task))):
        case TaskAdmission.Accepted(handle) => handle.release
        case TaskAdmission.Closed           => ZIO.unit

    private def launch(task: ZIO[Scope, AgentError, A]): UIO[TaskAdmission[A]] =
      gate.withPermit:
        admission.get.flatMap:
          case Admission.Closed => ZIO.succeed(TaskAdmission.Closed)
          case Admission.Open =>
            for
              ownership   <- Ref.make(SupervisedTask.Ownership.Attached)
              workerOwner <- owner.forkWith(ExecutionStrategy.Sequential)
              // Admission is masked; the child must still respond to cancellation.
              // forkIn closes its child scope from the worker: keep that cleanup on the same fiber.
              fiber <- ZIO
                .scoped(task)
                .onExit(workerOwner.close(_))
                .interruptible
                .forkIn(workerOwner)
            yield TaskAdmission.Accepted(new SupervisedTask(fiber, ownership))

    def close: UIO[Unit] =
      ZIO.uninterruptible(
        gate.withPermit(admission.set(Admission.Closed)) *> owner.close(Exit.unit)
      )
