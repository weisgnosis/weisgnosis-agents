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

import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.event.{
  AgentEvent,
  EventAdmission,
  EventPublisher,
  ModelStreamContext
}
import com.weisgnosis.agents.core.model.ContentBlock
import zio.*

/** Explicit host capability; model arguments cannot grant event publication authority. */
enum ToolInvocation:
  case Detached

  /** The ordinal identifies the call occurrence within the assistant message; model IDs may repeat. */
  case Active(
      context: ModelStreamContext,
      call: ContentBlock.ToolUse,
      callOrdinal: Int,
      events: ToolEventPort
  )

enum ToolEventDelivery:
  case Published, Closed
  case Rejected(reason: AgentError.EventDeliveryError)

/** A revocable publication lease, not an execution owner or a guarantee of consumer delivery. */
final class ToolEventPort private (
    publisher: EventPublisher,
    gate: Semaphore,
    availability: Ref[ToolEventPort.Availability]
):
  /** Publication and closure are ordered; acceptance is diagnostic admission, never consumer acknowledgement. */
  def publish(event: AgentEvent): UIO[ToolEventDelivery] =
    gate.withPermit:
      ZIO.uninterruptible:
        availability.get.flatMap:
          case ToolEventPort.Availability.Open =>
            publisher
              .publish(event)
              .map:
                case EventAdmission.Accepted         => ToolEventDelivery.Published
                case EventAdmission.Rejected(reason) => ToolEventDelivery.Rejected(reason)
                case EventAdmission.Closed           => ToolEventDelivery.Closed
          case ToolEventPort.Availability.Closed => ZIO.succeed(ToolEventDelivery.Closed)

  private def close: UIO[Unit] =
    ZIO.uninterruptible(gate.withPermit(availability.set(ToolEventPort.Availability.Closed)))

object ToolEventPort:
  private enum Availability:
    case Open, Closed

  /** Internal runtime boundary: the nonblocking publisher must outlive this lease. */
  private[core] def open(publisher: EventPublisher): ZIO[Scope, Nothing, ToolEventPort] =
    ZIO.acquireRelease(
      for
        gate         <- Semaphore.make(1)
        availability <- Ref.make(Availability.Open)
      yield new ToolEventPort(publisher, gate, availability)
    )(_.close)
