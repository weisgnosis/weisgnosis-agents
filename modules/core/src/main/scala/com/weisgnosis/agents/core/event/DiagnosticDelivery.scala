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
import com.weisgnosis.agents.core.error.AgentError.{EventDeliveryError, EventDeliveryFailure}
import com.weisgnosis.agents.core.json.JsonBoundary
import java.nio.charset.StandardCharsets
import zio.*
import zio.json.*
import zio.stm.*

/** One FIFO backlog. Dequeue transfers ownership to the consumer and releases its reservation. */
final class DiagnosticDelivery private (
    budget: EventDeliveryBudget,
    queue: TQueue[(AgentEvent, Long)],
    state: TRef[DiagnosticDelivery.State]
):
  import DiagnosticDelivery.*

  val publisher: EventPublisher = EventPublisher.fromFunction(admit)

  def take: UIO[Option[AgentEvent]] =
    poll.commit.flatMap:
      case Read.Event(event) => ZIO.some(event)
      case Read.Closed       => ZIO.none
      case Read.Await        => awaitReady.commit *> take

  // A suspended transaction only observes readiness; a fresh synchronous poll owns the dequeue.
  private def poll: USTM[Read] =
    for
      current <- state.get
      entry   <- queue.poll
      event <- entry match
        case Some((event, bytes)) =>
          state
            .set(current.copy(count = current.count - 1, bytes = current.bytes - bytes))
            .as(Read.Event(event))
        case None =>
          current.lifecycle match
            case Lifecycle.Finished(_) | Lifecycle.Abandoned(_) => STM.succeed(Read.Closed)
            case _                                              => STM.succeed(Read.Await)
    yield event

  private def awaitReady: USTM[Unit] =
    state.get.flatMap: current =>
      current.lifecycle match
        case Lifecycle.Finished(_) | Lifecycle.Abandoned(_) => STM.unit
        case _                                              => STM.check(current.count > 0)

  def finish: UIO[Unit] = state
    .update(current =>
      current.lifecycle match
        case Lifecycle.Abandoned(_) => current
        case _ => current.copy(lifecycle = Lifecycle.Finished(current.lifecycle.failure))
    )
    .commit
    .uninterruptible

  def abandon: UIO[Unit] =
    (queue.takeAll *> state.update(current =>
      State(Lifecycle.Abandoned(current.lifecycle.failure), 0, 0L)
    )).commit.uninterruptible

  def failure: UIO[Option[AgentError.EventDeliveryError]] =
    state.get.map(_.lifecycle.failure).commit

  private def admit(event: AgentEvent): UIO[EventAdmission] =
    state.get
      .map(_.lifecycle == Lifecycle.Accepting)
      .commit
      .flatMap:
        case false => ZIO.succeed(EventAdmission.Closed)
        case true  => encodedBytes(event).flatMap(cost => reserve(event, cost).commit)

  private def encodedBytes(event: AgentEvent): UIO[Either[EventDeliveryError, Long]] =
    ZIO
      .succeed(
        event.toJsonAST
          .flatMap(JsonBoundary.validate)
          .flatMap(json =>
            Either.cond(JsonBoundary.validUnicode(json), json, "Invalid diagnostic event Unicode")
          )
          .map(JsonBoundary.render)
          .left
          .map(_ =>
            EventDeliveryError(
              EventDeliveryFailure.EncodingFailure,
              "Diagnostic event encoding failed"
            )
          )
          .map(_.getBytes(StandardCharsets.UTF_8).length.toLong)
      )
      .catchAllCause(cause =>
        if cause.isInterrupted then ZIO.refailCause(cause)
        else
          ZIO.succeed(
            Left(
              EventDeliveryError(
                EventDeliveryFailure.EncodingFailure,
                "Diagnostic event encoding failed"
              )
            )
          )
      )

  private def reserve(
      event: AgentEvent,
      cost: Either[EventDeliveryError, Long]
  ): USTM[EventAdmission] =
    state.get.flatMap: current =>
      current.lifecycle match
        case Lifecycle.Accepting =>
          val checked = cost.flatMap(bytes =>
            Either.cond(
              bytes <= budget.maxEventBytes && current.count < budget.maxEvents &&
                bytes <= budget.maxQueuedBytes - current.bytes,
              bytes,
              EventDeliveryError(
                EventDeliveryFailure.CapacityExhausted,
                "Diagnostic event delivery budget exhausted"
              )
            )
          )
          checked match
            case Right(bytes) =>
              (queue.offer((event, bytes)) *> state.set(
                current.copy(count = current.count + 1, bytes = current.bytes + bytes)
              ))
                .as(EventAdmission.Accepted)
            case Left(error) =>
              state
                .set(current.copy(lifecycle = Lifecycle.Rejected(error)))
                .as(EventAdmission.Rejected(error))
        case _ => STM.succeed(EventAdmission.Closed)

object DiagnosticDelivery:
  private enum Read:
    case Event(value: AgentEvent)
    case Closed, Await

  private enum Lifecycle:
    case Accepting
    case Rejected(error: EventDeliveryError)
    case Finished(error: Option[EventDeliveryError])
    case Abandoned(error: Option[EventDeliveryError])

    def failure: Option[EventDeliveryError] = this match
      case Accepting        => None
      case Rejected(error)  => Some(error)
      case Finished(error)  => error
      case Abandoned(error) => error

  final private case class State(lifecycle: Lifecycle, count: Int, bytes: Long)

  def make(budget: EventDeliveryBudget): UIO[DiagnosticDelivery] =
    (for
      queue <- TQueue.unbounded[(AgentEvent, Long)]
      state <- TRef.make(State(Lifecycle.Accepting, 0, 0L))
    yield new DiagnosticDelivery(budget, queue, state)).commit
