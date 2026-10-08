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

import com.weisgnosis.agents.core.agent.{AgentResult, AgentRunState}
import com.weisgnosis.agents.core.error.AgentError
import zio.*
import zio.json.*

enum SubagentTaskDefects derives JsonCodec:
  case Absent, Present

/** Serializable observations only; neither an executable result nor a resumable checkpoint. */
enum SubagentTerminalOutcome derives JsonCodec:
  case Completed(output: String)
  case Suspended(output: String)
  case Stopped(reason: String)
  case Interrupted(failures: Chunk[SubagentTaskFailure], defects: SubagentTaskDefects)
  case Failed(failures: Chunk[SubagentTaskFailure], defects: SubagentTaskDefects)

object SubagentTerminalOutcome:
  /**
   * Preserve exact successful text, typed messages and supplied direct cause strings; runtime Cause
   * and Throwable structures are not serialized.
   */
  def fromExit(exit: Exit[AgentError, AgentResult]): SubagentTerminalOutcome = exit match
    case Exit.Success(result) =>
      result.state match
        case AgentRunState.Completed       => SubagentTerminalOutcome.Completed(result.reply.text)
        case AgentRunState.Suspended(_)    => SubagentTerminalOutcome.Suspended(result.reply.text)
        case AgentRunState.Stopped(reason) => SubagentTerminalOutcome.Stopped(reason)
        case AgentRunState.Interrupted =>
          SubagentTerminalOutcome.Interrupted(Chunk.empty, SubagentTaskDefects.Absent)
    case Exit.Failure(cause) =>
      val failures = Chunk.fromIterable(cause.failures.map(SubagentTaskFailure.fromError))
      val defects =
        if cause.defects.isEmpty then SubagentTaskDefects.Absent else SubagentTaskDefects.Present
      if cause.isInterrupted then SubagentTerminalOutcome.Interrupted(failures, defects)
      else SubagentTerminalOutcome.Failed(failures, defects)

final case class SubagentTerminalRecord(
    id: SubagentTaskId,
    outcome: SubagentTerminalOutcome,
    workerId: Option[SubagentWorkerId] = None,
    origin: SubagentExecutionOrigin = SubagentExecutionOrigin.Local
)
