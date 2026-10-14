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
package com.weisgnosis.agents.core.event

import com.weisgnosis.agents.core.error.AgentError
import zio.*

/** Admission is neither consumer receipt nor acknowledgement. */
enum EventAdmission:
  case Accepted
  case Rejected(reason: AgentError.EventDeliveryError)
  case Closed

/** Publication authority without subscription or delivery ownership. */
final class EventPublisher private (publishEvent: AgentEvent => UIO[EventAdmission]):
  def publish(event: AgentEvent): UIO[EventAdmission] = ZIO.suspendSucceed(publishEvent(event))

  def publishAll(events: Iterable[AgentEvent]): UIO[Unit] = ZIO.foreachDiscard(events)(publish)

  def contramap(transform: AgentEvent => AgentEvent): EventPublisher =
    EventPublisher.fromFunction(event => ZIO.suspendSucceed(publish(transform(event))))

object EventPublisher:
  val discard: EventPublisher = fromFunction(_ => ZIO.succeed(EventAdmission.Closed))

  def fromFunction(publish: AgentEvent => UIO[EventAdmission]): EventPublisher =
    new EventPublisher(publish)

  /** Standalone compatibility adapter; the borrowed hub is outside runtime budget accounting. */
  def borrowedHub(hub: Hub[AgentEvent]): EventPublisher =
    fromFunction(event =>
      hub
        .publish(event)
        .map(accepted => if accepted then EventAdmission.Accepted else EventAdmission.Closed)
    )
