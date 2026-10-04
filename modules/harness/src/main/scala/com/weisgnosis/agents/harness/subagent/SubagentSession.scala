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

import com.weisgnosis.agents.core.agent.{AgentResult, SuppliedToolResult}
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.model.Msg
import com.weisgnosis.agents.core.tool.ToolEventPort
import zio.*

/** One host-held child runtime; the returned TaskResource owns its lifetime and descendants. */
final class SubagentSession private (
    val executions: SubagentTaskRegistry,
    submitResume: (Chunk[SuppliedToolResult], Option[ToolEventPort]) => ZIO[
      Scope,
      AgentError,
      SubagentTaskSubmission
    ]
):
  /** Each accepted send receives a distinct retained execution ID; waiting sends count toward capacity. */
  def send(input: Msg): ZIO[Scope, AgentError, SubagentTaskSubmission] = executions.submit(input)

  def send(
      input: Msg,
      events: Option[ToolEventPort]
  ): ZIO[Scope, AgentError, SubagentTaskSubmission] =
    executions.submit(input, events)

  /**
   * Resumes the checkpoint present when this execution acquires the session permit. Host-supplied
   * observations are checked by the runtime; installed authorization remains authoritative. This
   * is not a checkpoint claim or replay guard: the host must sequence confirmation delivery.
   */
  def resume(
      results: Chunk[SuppliedToolResult] = Chunk.empty,
      events: Option[ToolEventPort] = None
  ): ZIO[Scope, AgentError, SubagentTaskSubmission] =
    submitResume(results, events)

object SubagentSession:
  private enum Command:
    case Send(input: Msg)
    case Resume(results: Chunk[SuppliedToolResult])

  def open(
      parent: SubagentSpawner[AgentResult],
      factory: SubagentFactory,
      maxTracked: Int = 256
  ): ZIO[Scope, AgentError, TaskResourceAdmission[SubagentSession]] =
    validateCapacity(maxTracked) *>
      parent.ownChild((child, supervisor) =>
        initialize(child, supervisor, factory, maxTracked, None)
      )

  /**
   * Acquires a new host-owned runtime and restores checked pending data before publishing the
   * session. The current factory and parent supply execution authority; no execution record is
   * imported or started. Authentication, exclusive checkpoint claims and idempotency belong to
   * the host. Factory acquisition still runs even though model/tool execution awaits resume.
   */
  def openRestored(
      parent: SubagentSpawner[AgentResult],
      factory: SubagentFactory,
      checkpoint: AgentResult,
      maxTracked: Int = 256
  ): ZIO[Scope, AgentError, TaskResourceAdmission[SubagentSession]] =
    validateCapacity(maxTracked) *>
      parent.ownChild((child, supervisor) =>
        initialize(child, supervisor, factory, maxTracked, Some(checkpoint))
      )

  private[subagent] def openRetained(
      parent: SubagentSpawner[AgentResult],
      factory: SubagentFactory,
      maxTracked: Int
  ): IO[AgentError, TaskResourceAdmission[SubagentSession]] =
    validateCapacity(maxTracked) *>
      parent.ownChildRetained((child, supervisor) =>
        initialize(child, supervisor, factory, maxTracked, None)
      )

  private def validateCapacity(maxTracked: Int): IO[AgentError, Unit] =
    ZIO
      .fail(AgentError.InvalidConfiguration("Subagent task capacity must be positive"))
      .when(maxTracked <= 0)
      .unit

  private def initialize(
      child: SubagentSpawner[AgentResult],
      supervisor: TaskSupervisor[AgentResult],
      factory: SubagentFactory,
      maxTracked: Int,
      checkpoint: Option[AgentResult]
  ): ZIO[Scope, AgentError, SubagentSession] =
    for
      runtime <- factory.build(child)
      _       <- ZIO.foreachDiscard(checkpoint)(runtime.restore)
      turn    <- Semaphore.make(1)
      executions <- SubagentTaskRegistry.openInput[Command](
        Command.Send(_),
        (command, id, events) =>
          supervisor.attach(turn.withPermit(command match
            case Command.Send(input)     => SubagentFactory.run(runtime, input, id, events)
            case Command.Resume(results) => SubagentFactory.resume(runtime, results, id, events))),
        maxTracked
      )
    yield new SubagentSession(
      executions,
      (results, events) => executions.submitInput(Command.Resume(results), events)
    )
