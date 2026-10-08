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
package com.weisgnosis.agents.core.tool

import com.weisgnosis.agents.core.event.ModelStreamContext
import com.weisgnosis.agents.core.model.ToolResultState
import zio.*

/** Correlation without executable capabilities or model arguments. */
enum ToolDispatchContext:
  case Detached
  case Active(context: ModelStreamContext, callId: String, callOrdinal: Int)

object ToolDispatchContext:
  private[tool] def from(invocation: ToolInvocation): ToolDispatchContext = invocation match
    case ToolInvocation.Detached => ToolDispatchContext.Detached
    case ToolInvocation.Active(context, call, ordinal, _) =>
      ToolDispatchContext.Active(context, call.id, ordinal)

/** Read-only completion after local dispatch normalization and owned tool cleanup. */
trait ToolDispatchCompletion:
  def finish(outcome: Exit[Nothing, ToolResultState]): UIO[Unit]

object ToolDispatchCompletion:
  val ignore: ToolDispatchCompletion = ToolDispatchCompletion(_ => ZIO.unit)

  def apply(callback: Exit[Nothing, ToolResultState] => UIO[Unit]): ToolDispatchCompletion =
    new ToolDispatchCompletion:
      def finish(outcome: Exit[Nothing, ToolResultState]): UIO[Unit] =
        ZIO.suspendSucceed(callback(outcome))

/** Observes actual local dispatch only; callbacks receive no arguments or result payload. */
final class ToolDispatchObserver private (
    private val callbacks: Chunk[
      (String, ToolDispatchContext) => ZIO[Scope, Nothing, ToolDispatchCompletion]
    ]
):
  def andThen(next: ToolDispatchObserver): ToolDispatchObserver =
    new ToolDispatchObserver(callbacks ++ next.callbacks)

  private[tool] def around[A](name: String, invocation: ToolInvocation)(
      effect: UIO[A]
  )(state: A => ToolResultState): UIO[A] =
    callbacks.foldRight(effect)((callback, work) =>
      ZIO.scoped:
        ZIO.uninterruptibleMask: restore =>
          for
            completion <- restore(
              ZIO.suspendSucceed(callback(name, ToolDispatchContext.from(invocation)))
            )
            result <- restore(work).onExit(outcome =>
              completion.finish(outcome match
                case Exit.Success(value) => Exit.succeed(state(value))
                case Exit.Failure(cause) => Exit.failCause(cause))
            )
          yield result
    )

object ToolDispatchObserver:
  val ignore: ToolDispatchObserver = new ToolDispatchObserver(Chunk.empty)

  def apply(
      callback: (String, ToolDispatchContext) => ZIO[Scope, Nothing, ToolDispatchCompletion]
  ): ToolDispatchObserver = new ToolDispatchObserver(Chunk(callback))
