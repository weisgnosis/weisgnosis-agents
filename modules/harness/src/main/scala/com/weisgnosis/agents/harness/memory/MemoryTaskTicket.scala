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
package com.weisgnosis.agents.harness.memory

import com.weisgnosis.agents.core.error.AgentError
import java.util.UUID
import zio.*
import zio.json.JsonCodec
import zio.stm.TPromise

/** Runtime identity for one conversation; reuse it across that conversation's submissions. */
opaque type MemoryConversationToken = UUID

object MemoryConversationToken:
  def make: UIO[MemoryConversationToken] = Random.nextUUID

/** Runtime outcomes preserve complete task causes, including interruption and cleanup defects. */
enum MemoryTaskOutcome:
  case Completed(exit: Exit[AgentError, Unit])
  case Superseded, CancelledBeforeStart

enum MemoryTaskRejection derives JsonCodec:
  case Closed, Capacity

enum MemoryTaskSubmission:
  case Accepted(ticket: MemoryTaskTicket)
  case Rejected(reason: MemoryTaskRejection)

final class MemoryTaskTicket private[memory] (completion: TPromise[Nothing, MemoryTaskOutcome]):
  /** Interrupting this wait does not cancel the separately owned task. */
  def await: UIO[MemoryTaskOutcome] = completion.await.commit
