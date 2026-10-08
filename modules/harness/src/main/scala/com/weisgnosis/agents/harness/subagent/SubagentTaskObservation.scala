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

import zio.json.*

/** Stored status does not establish a local executor or a live remote owner. */
enum SubagentStoredTaskState derives JsonCodec:
  case Pending, Running

/** Read-only task truth; terminal observations never reconstruct execution capabilities. */
enum SubagentTaskObservationState:
  case Running
  case Stored(state: SubagentStoredTaskState)
  case Terminal(outcome: SubagentTerminalOutcome)

final case class SubagentTaskObservation(id: SubagentTaskId, state: SubagentTaskObservationState)

final case class SubagentTaskPublication(
    id: SubagentTaskId,
    publication: SubagentTerminalPublication
)

object SubagentTaskObservation:
  private[subagent] def fromSnapshot(snapshot: SubagentTaskSnapshot): SubagentTaskObservation =
    val state = snapshot.state match
      case SubagentTaskState.Running => SubagentTaskObservationState.Running
      case SubagentTaskState.Completed(exit) =>
        SubagentTaskObservationState.Terminal(SubagentTerminalOutcome.fromExit(exit))
    SubagentTaskObservation(snapshot.id, state)
