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

import com.weisgnosis.agents.core.agent.{AgentResult, AgentRunState, ReActAgent}
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.model.Msg
import zio.*

/** One host-held child runtime; the returned TaskResource owns its lifetime and descendants. */
final class SubagentSession private (val executions: SubagentTaskRegistry):
  /** Each accepted send receives a distinct retained execution ID; waiting sends count toward capacity. */
  def send(input: Msg): ZIO[Scope, AgentError, SubagentTaskSubmission] = executions.submit(input)

object SubagentSession:
  private enum Continuation:
    case Ready, Suspended

  def open(
      parent: SubagentSpawner[AgentResult],
      factory: SubagentFactory,
      maxTracked: Int = 256
  ): ZIO[Scope, AgentError, TaskResourceAdmission[SubagentSession]] =
    ZIO
      .fail(AgentError.InvalidConfiguration("Subagent task capacity must be positive"))
      .when(maxTracked <= 0) *>
      parent.ownChild: (child, supervisor) =>
        for
          runtime      <- factory.build(child)
          turn         <- Semaphore.make(1)
          continuation <- Ref.make(Continuation.Ready)
          executions <- SubagentTaskRegistry.openWith(
            input => supervisor.attach(turn.withPermit(runTurn(runtime, continuation, input))),
            maxTracked
          )
        yield new SubagentSession(executions)

  private def runTurn(
      runtime: ReActAgent,
      continuation: Ref[Continuation],
      input: Msg
  ): ZIO[Scope, AgentError, AgentResult] =
    ZIO.uninterruptibleMask: restore =>
      continuation.get.flatMap:
        case Continuation.Suspended =>
          ZIO.fail(
            AgentError.InvalidConfiguration(
              "The child session is suspended; ordinary sends cannot resume it"
            )
          )
        case Continuation.Ready =>
          restore(runtime.run(input)).tap(result =>
            continuation.set(
              if result.state == AgentRunState.Suspended then Continuation.Suspended
              else Continuation.Ready
            )
          )
