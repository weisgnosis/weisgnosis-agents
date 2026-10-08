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

import java.time.Instant
import zio.json.*

/** Observed admission times, never reconstructed from a terminal publication or legacy record. */
final case class SubagentTaskMetadata(
    createdAt: Instant,
    lastCheckedAt: Instant,
    lastUpdatedAt: Instant
) derives JsonCodec

/** The exact confirmed admission identity; data alone does not authorize takeover or restoration. */
final case class SubagentTaskAdmissionIdentity(
    id: SubagentTaskId,
    workerId: Option[SubagentWorkerId],
    createdAt: Instant,
    origin: SubagentExecutionOrigin = SubagentExecutionOrigin.Local
)

/** First durable cancellation request; this is not a completion timestamp or execution lease. */
enum SubagentCancellationIntent derives JsonCodec:
  case NotRequested
  case Requested(at: Instant)

enum SubagentTaskStartResult:
  case Started, CancellationRequested

enum SubagentCancellationRequestResult:
  case Missing
  case Requested(record: SubagentTaskRecord)
  case AlreadyRequested(record: SubagentTaskRecord)
  case AlreadyTerminal(record: SubagentTaskRecord)

enum SubagentTaskRecordState derives JsonCodec:
  case Pending, Running
  case Terminal(outcome: SubagentTerminalOutcome, acknowledgement: SubagentTerminalAcknowledgement)

/** Persistent observation; Pending can remain unresolved after an uncertain admission receipt. */
final case class SubagentTaskRecord(
    id: SubagentTaskId,
    workerId: Option[SubagentWorkerId],
    state: SubagentTaskRecordState,
    metadata: Option[SubagentTaskMetadata],
    cancellationIntent: SubagentCancellationIntent = SubagentCancellationIntent.NotRequested,
    origin: SubagentExecutionOrigin = SubagentExecutionOrigin.Local,
    remoteApproval: SubagentRemoteApproval = SubagentRemoteApproval.NotAwaiting
):
  def terminal: Option[SubagentTerminalRecord] = state match
    case SubagentTaskRecordState.Terminal(outcome, _) =>
      Some(SubagentTerminalRecord(id, outcome, workerId, origin))
    case _ => None

enum SubagentTaskAdmissionResult:
  case Created(identity: SubagentTaskAdmissionIdentity)
  case Existing(record: SubagentTaskRecord)

/** Explicit owner refresh disposition, never an execution lease or takeover authority. */
enum SubagentTaskHeartbeatResult:
  case Refreshed(record: SubagentTaskRecord)
  case NotRunning(record: SubagentTaskRecord)
  case AlreadyTerminal(record: SubagentTaskRecord)
  case CancellationRequested(record: SubagentTaskRecord)
