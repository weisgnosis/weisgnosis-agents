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
package com.weisgnosis.agents.core.agent

import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.event.InvocationContext
import zio.*

/** A lazy one-shot execution; cancellation before agent admission still consumes the handle. */
final class PreparedInvocation[A] private (
    val context: InvocationContext,
    execution: ZIO[Scope, AgentError, A],
    use: Ref[PreparedInvocation.Use]
):
  /** The caller owns execution and cancellation through the returned scoped effect. */
  def run: ZIO[Scope, AgentError, A] = ZIO.suspendSucceed:
    use
      .getAndSet(PreparedInvocation.Use.Consumed)
      .flatMap:
        case PreparedInvocation.Use.Available => execution
        case PreparedInvocation.Use.Consumed =>
          ZIO.fail(
            AgentError.InvalidConfiguration("Prepared invocation permits one execution attempt")
          )

object PreparedInvocation:
  private enum Use:
    case Available, Consumed

  private[agent] def make[A](
      execute: InvocationContext => ZIO[Scope, AgentError, A]
  ): UIO[PreparedInvocation[A]] =
    for
      context <- InvocationContext.fresh
      use     <- Ref.make(Use.Available)
    yield new PreparedInvocation(context, ZIO.suspendSucceed(execute(context)), use)
