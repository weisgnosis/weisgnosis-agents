/*
 * Copyright 2024-2026 WeisGnosis (https://weisgnosis.com)
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 */
package com.weisgnosis.agents.core.agent

import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.event.InvocationContext
import zio.*
import zio.stream.ZStream

/** A lazy one-shot execution. Observing status or termination never claims or starts it. */
final class PreparedInvocation[A] private (
    val context: InvocationContext,
    execution: ZIO[Scope, AgentError, A],
    events: ZStream[Scope, AgentError, AgentRunUpdate[A]],
    control: PreparedInvocation.Control[A]
):
  def status: UIO[PreparedInvocation.Status] = control.status

  /** Replay the joined outcome. Execution callbacks must not await their own termination. */
  def termination: UIO[Exit[AgentError, A]] = control.termination

  /** Cancel only this invocation and join its execution. None means no new cancellation was admitted. */
  def cancel: IO[AgentError, Option[Exit[AgentError, A]]] = control.cancel

  /** Queued invocations cancel; running invocations receive their own cooperative signal. */
  def interrupt: IO[AgentError, Boolean] = control.interrupt

  def run: ZIO[Scope, AgentError, A] = control.execute(execution)

  /** Events and ordinary execution compete for the same single-use claim and reserved context. */
  def streamWithResult: ZStream[Scope, AgentError, AgentRunUpdate[A]] =
    events

object PreparedInvocation:
  /** Returned means invocation return, including a suspended or cooperatively interrupted AgentResult. */
  enum Status:
    case Created, Queued, Running, Returned, Failed, Cancelled

  private enum Use:
    case Available, Consumed

  private enum Cancellation:
    case BeforeStart, Owned, Ignore

  private enum Phase[+A]:
    case Created, Queued, Running
    case CancellingQueued(by: FiberId)
    case CancellingRunning(by: FiberId)
    case Terminal(outcome: Exit[AgentError, A], status: Status)

  final private[agent] class Control[A] private[PreparedInvocation] (
      phase: Ref.Synchronized[Phase[A]],
      cancellation: Promise[Nothing, FiberId],
      completed: Promise[Nothing, Exit[AgentError, A]],
      val cooperative: Promise[Nothing, Unit],
      use: Ref[Use],
      executionGuard: Ref[Option[FiberRef[Boolean]]]
  ):
    def status: UIO[Status] = phase.get.map:
      case Phase.Created              => Status.Created
      case Phase.Queued               => Status.Queued
      case Phase.Running              => Status.Running
      case Phase.CancellingQueued(_)  => Status.Queued
      case Phase.CancellingRunning(_) => Status.Running
      case Phase.Terminal(_, status)  => status

    def termination: UIO[Exit[AgentError, A]] = completed.await

    private def claim: IO[AgentError, Unit] =
      use
        .getAndSet(Use.Consumed)
        .flatMap:
          case Use.Available => queue
          case Use.Consumed =>
            ZIO.fail(
              AgentError.InvalidConfiguration("Prepared invocation permits one execution attempt")
            )

    private def queue: IO[AgentError, Unit] = phase.modifyZIO:
      case Phase.Created                         => ZIO.succeed(((), Phase.Queued))
      case terminal @ Phase.Terminal(outcome, _) => outcome.as(((), terminal))
      case current                               => ZIO.succeed(((), current))

    /** Called only while the agent's run permit is owned, before lifecycle/model effects. */
    def admitted: IO[AgentError, Unit] = phase.modifyZIO:
      case Phase.Queued                          => ZIO.succeed(((), Phase.Running))
      case current @ Phase.CancellingQueued(by)  => ZIO.interruptAs(by).as(((), current))
      case current @ Phase.CancellingRunning(by) => ZIO.interruptAs(by).as(((), current))
      case terminal @ Phase.Terminal(outcome, _) => outcome.as(((), terminal))
      case current                               => ZIO.succeed(((), current))

    /** Inherited by execution descendants even when the host supplies a different owning Scope. */
    private def requireExternalController: IO[AgentError, Unit] =
      executionGuard.get
        .flatMap(_.fold[UIO[Boolean]](ZIO.succeed(false))(_.get))
        .flatMap(inside =>
          ZIO
            .fail(
              AgentError
                .InvalidConfiguration("Execution callbacks cannot cancel their own invocation")
            )
            .when(inside)
            .unit
        )

    def cancel: IO[AgentError, Option[Exit[AgentError, A]]] = ZIO.uninterruptible:
      for
        _  <- requireExternalController
        by <- ZIO.fiberId
        decision <- phase.modify:
          case Phase.Created =>
            (
              Cancellation.BeforeStart,
              Phase.Terminal(Exit.failCause(Cause.interrupt(by)), Status.Cancelled)
            )
          case Phase.Queued  => (Cancellation.Owned, Phase.CancellingQueued(by))
          case Phase.Running => (Cancellation.Owned, Phase.CancellingRunning(by))
          case current       => (Cancellation.Ignore, current)
        result <- decision match
          case Cancellation.BeforeStart =>
            val outcome: Exit[AgentError, A] = Exit.failCause(Cause.interrupt(by))
            cancellation.succeed(by) *> completed.succeed(outcome).as(Some(outcome))
          case Cancellation.Owned  => cancellation.succeed(by) *> completed.await.map(Some(_))
          case Cancellation.Ignore => ZIO.none
      yield result

    def interrupt: IO[AgentError, Boolean] = phase.get.flatMap:
      case Phase.Running                => cooperative.succeed(())
      case Phase.Created | Phase.Queued => cancel.map(_.isDefined)
      case _                            => ZIO.succeed(false)

    /** The caller owns this controller; only its worker is interrupted, never another run. */
    def execute(effect: ZIO[Scope, AgentError, A]): ZIO[Scope, AgentError, A] =
      ZIO.checkInterruptible: incoming =>
        ZIO.scoped:
          ZIO.uninterruptibleMask: restore =>
            for
              _      <- claim
              guard  <- FiberRef.make(false)
              _      <- executionGuard.set(Some(guard))
              worker <- guard.locally(true)(ZIO.scoped(effect)).interruptible.forkScoped
              observed <- restore(
                worker.await.interruptible.raceEither(cancellation.await.interruptible)
              ).exit
              outcome <- observed match
                case Exit.Success(Left(exit)) => worker.inheritAll.as(exit)
                case Exit.Success(Right(by)) =>
                  worker
                    .interruptAs(by)
                    .map(joined =>
                      Exit
                        .failCause(Cause.interrupt(by))
                        .zipWith(joined)((_, value) => value, _ ++ _)
                    )
                case Exit.Failure(cause) =>
                  worker.interrupt.map(joined =>
                    Exit.failCause(cause).zipWith(joined)((_, value) => value, _ ++ _)
                  )
              pending <-
                if incoming.isInterruptible then
                  ZIO.descriptor.map(descriptor =>
                    descriptor.interrupters.foldLeft[Cause[AgentError]](Cause.empty)((cause, id) =>
                      cause ++ Cause.interrupt(id)
                    )
                  )
                else ZIO.succeed(Cause.empty)
              joined =
                if pending.isEmpty then outcome
                else outcome.zipWith(Exit.failCause(pending))((value, _) => value, _ ++ _)
              status =
                if !pending.isEmpty then Status.Cancelled
                else
                  observed match
                    case Exit.Success(Right(_))                     => Status.Cancelled
                    case Exit.Failure(cause) if cause.isInterrupted => Status.Cancelled
                    case _ =>
                      outcome match
                        case Exit.Success(_) => Status.Returned
                        case Exit.Failure(cause)
                            if cause.failures.isEmpty && cause.defects.isEmpty && cause.isInterrupted =>
                          Status.Cancelled
                        case Exit.Failure(_) => Status.Failed
              _      <- phase.set(Phase.Terminal(joined, status))
              _      <- completed.succeed(joined)
              result <- joined
            yield result

  private[agent] def make[A](
      execute: (InvocationContext, Control[A]) => ZIO[Scope, AgentError, A],
      events: (InvocationContext, Control[A]) => ZStream[Scope, AgentError, AgentRunUpdate[A]]
  ): UIO[PreparedInvocation[A]] =
    for
      context      <- InvocationContext.fresh
      phase        <- Ref.Synchronized.make[Phase[A]](Phase.Created)
      cancellation <- Promise.make[Nothing, FiberId]
      completed    <- Promise.make[Nothing, Exit[AgentError, A]]
      cooperative  <- Promise.make[Nothing, Unit]
      use          <- Ref.make(Use.Available)
      guard        <- Ref.make(Option.empty[FiberRef[Boolean]])
      control = new Control(phase, cancellation, completed, cooperative, use, guard)
    yield new PreparedInvocation(
      context,
      ZIO.suspendSucceed(execute(context, control)),
      ZStream.suspend(events(context, control)),
      control
    )
