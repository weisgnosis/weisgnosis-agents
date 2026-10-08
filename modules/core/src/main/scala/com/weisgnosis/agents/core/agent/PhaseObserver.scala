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
import com.weisgnosis.agents.core.event.{AgentEvent, InvocationContext, ModelStreamContext}
import com.weisgnosis.agents.core.tool.{ToolEventDelivery, ToolEventPort}
import zio.*

/** Immutable correlation only; phase observation grants no execution or confirmation authority. */
enum PhaseContext:
  case Invocation(invocation: InvocationContext)
  case Reasoning(invocation: InvocationContext, iteration: Int)
  case ModelCall(context: ModelStreamContext, model: String)

/** A revocable Hint-only capability. Publication enqueues an event, not a delivery acknowledgement. */
final class PhaseHints private (events: ToolEventPort):
  def publish(hint: String, source: Option[String] = None): UIO[ToolEventDelivery] =
    events.publish(AgentEvent.HintBlockEvent(hint, source))

object PhaseHints:
  private[agent] def open(hub: Hub[AgentEvent]): ZIO[Scope, Nothing, PhaseHints] =
    ToolEventPort.open(hub).map(new PhaseHints(_))

/** Runs once after the observed work, before this observer's scope closes. */
trait PhaseCompletion:
  def finish(
      outcome: Exit[AgentError, Unit]
  ): ZIO[Scope, AgentError.CompletionObservationError, Unit]

object PhaseCompletion:
  val ignore: PhaseCompletion = PhaseCompletion(_ => ZIO.unit)

  def apply(
      callback: Exit[AgentError, Unit] => ZIO[Scope, AgentError.CompletionObservationError, Unit]
  ): PhaseCompletion = new PhaseCompletion:
    def finish(
        outcome: Exit[AgentError, Unit]
    ): ZIO[Scope, AgentError.CompletionObservationError, Unit] =
      ZIO.suspendSucceed(callback(outcome))

/**
 * Scoped phase participation without an execution continuation. Registration order is outermost
 * first: each observer enters before the next and finishes/closes after the next. Invocation has
 * no Hint capability because its event hub does not yet exist. Callbacks must not await the run lock.
 */
final class PhaseObserver private (private val callbacks: Chunk[PhaseObserver.Callback]):
  def andThen(next: PhaseObserver): PhaseObserver = new PhaseObserver(callbacks ++ next.callbacks)

  private[agent] def around[R, E >: AgentError, A](
      context: PhaseContext,
      hints: Option[PhaseHints]
  )(effect: ZIO[R, E, A])(observedError: E => AgentError): ZIO[R, E, A] =
    if callbacks.isEmpty then effect
    else observe(context, hints)(effect)(observedError)

  private def observe[R, E >: AgentError, A](
      context: PhaseContext,
      hints: Option[PhaseHints]
  )(effect: ZIO[R, E, A])(observedError: E => AgentError): ZIO[R, E, A] =
    ZIO.checkInterruptible: incoming =>
      ZIO.uninterruptibleMask: restore =>
        for
          environment <- ZIO.environment[R]
          outcome <- restore {
            def loop(remaining: List[PhaseObserver.Callback]): ZIO[Any, E, A] = remaining match
              case Nil => effect.provideEnvironment(environment)
              case callback :: rest =>
                ZIO.scoped(ZIO.uninterruptibleMask: restoreStage =>
                  for
                    completion <- restoreStage(ZIO.suspendSucceed(callback(context, hints)))
                    executed   <- restoreStage(loop(rest)).exit
                    observed = executed match
                      case Exit.Success(_)     => Exit.unit
                      case Exit.Failure(cause) => Exit.failCause(cause.map(observedError))
                    finished <- completion.finish(observed).exit
                    value <- (executed, finished) match
                      case (Exit.Success(value), Exit.Success(_)) => ZIO.succeed(value)
                      case (Exit.Failure(cause), Exit.Success(_)) => ZIO.refailCause(cause)
                      case (Exit.Success(_), Exit.Failure(cause)) => ZIO.refailCause(cause)
                      case (Exit.Failure(original), Exit.Failure(failure)) =>
                        ZIO.refailCause(original ++ failure)
                  yield value)
            loop(callbacks.toList)
          }.exit
          pending <-
            if incoming.isInterruptible then
              ZIO.descriptor.map(descriptor =>
                descriptor.interrupters.foldLeft(Cause.empty: Cause[E])((cause, id) =>
                  if outcome.causeOption.exists(_.interruptors.contains(id)) then cause
                  else cause ++ Cause.interrupt(id)
                )
              )
            else ZIO.succeed(Cause.empty)
          value <- outcome match
            case Exit.Success(value) if pending.isEmpty => ZIO.succeed(value)
            case Exit.Success(_)                        => ZIO.refailCause(pending)
            case Exit.Failure(cause)                    => ZIO.refailCause(cause ++ pending)
        yield value

object PhaseObserver:
  private[agent] type Callback = (PhaseContext, Option[PhaseHints]) => ZIO[
    Scope,
    AgentError.CompletionObservationError,
    PhaseCompletion
  ]

  val ignore: PhaseObserver = new PhaseObserver(Chunk.empty)

  def apply(
      callback: (PhaseContext, Option[PhaseHints]) => ZIO[
        Scope,
        AgentError.CompletionObservationError,
        PhaseCompletion
      ]
  ): PhaseObserver = new PhaseObserver(Chunk(callback))
