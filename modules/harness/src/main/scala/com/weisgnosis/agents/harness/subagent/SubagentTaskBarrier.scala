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

import com.weisgnosis.agents.core.error.AgentError
import zio.*

/** A checked local membership selection; it conveys no task cancellation authority. */
sealed trait SubagentTaskSelection

object SubagentTaskSelection:
  val MaximumTasks: Int = 128
  case object CurrentRunning extends SubagentTaskSelection
  final private[subagent] class Selected private[SubagentTaskSelection] (
      val ids: Chunk[SubagentTaskId]
  ) extends SubagentTaskSelection

  def selected(
      ids: Chunk[SubagentTaskId]
  ): Either[AgentError.InvalidConfiguration, SubagentTaskSelection] =
    if ids.isEmpty || ids.distinct.size != ids.size || ids.size > MaximumTasks then
      Left(
        AgentError.InvalidConfiguration(
          "Selected task IDs must be nonempty, distinct and contain at most 128 entries"
        )
      )
    else Right(new Selected(ids))

/** One shared wait budget. Nonpositive wire values use the default; larger values are clamped. */
final class SubagentTaskWaitTimeout private (val seconds: Int):
  def duration: Duration = Duration.fromSeconds(seconds.toLong)

object SubagentTaskWaitTimeout:
  def fromSeconds(seconds: Option[Int] = None): SubagentTaskWaitTimeout =
    new SubagentTaskWaitTimeout(seconds.filter(_ > 0).fold(60)(_.min(120)))

enum SubagentTaskBarrierResult:
  case Completed(tasks: Chunk[SubagentTerminalRecord])
  case TimedOut(initialIds: Chunk[SubagentTaskId], remainingIds: Chunk[SubagentTaskId])
  case Empty, AdmissionTimedOut

private[subagent] enum SubagentCapturedReadiness:
  case Running, PublicationPending
  case Ready(record: SubagentTerminalRecord)

/** Stable captured references, not a reconstructible handle or a cancellation capability. */
private[subagent] trait SubagentCapturedWait:
  def checkedMetadata: IO[AgentError, Option[SubagentTaskMetadata]]
  def id: SubagentTaskId
  def await: IO[AgentError, SubagentTerminalRecord]
  def readiness: IO[AgentError, SubagentCapturedReadiness]
  final def poll: IO[AgentError, Option[SubagentTerminalRecord]] = readiness.map:
    case SubagentCapturedReadiness.Ready(record) => Some(record)
    case _                                       => None

object SubagentTaskBarrier:
  /** Timeout and caller interruption cancel only these waiters, never their captured tasks. */
  def await(
      registry: SubagentTaskRegistry,
      selection: SubagentTaskSelection,
      timeout: SubagentTaskWaitTimeout = SubagentTaskWaitTimeout.fromSeconds()
  ): IO[AgentError, SubagentTaskBarrierResult] =
    Ref
      .make(Option.empty[(Chunk[SubagentTaskId], Set[SubagentTaskId])])
      .flatMap: progress =>
        val waiting = registry
          .captureWait(selection)
          .flatMap: captured =>
            if captured.isEmpty then ZIO.succeed(SubagentTaskBarrierResult.Empty)
            else
              progress.set(Some(captured.map(_.id) -> Set.empty)) *>
                awaitAll(captured)(entry =>
                  entry.await
                    .tap(_ => progress.update(_.map((ids, done) => ids -> (done + entry.id))))
                )
                  .map(SubagentTaskBarrierResult.Completed(_))
        raceWithSignal(waiting, ZIO.sleep(timeout.duration).as(Exit.unit))
          .flatMap:
            case Some(result) => ZIO.succeed(result)
            case None =>
              progress.get.map:
                case None => SubagentTaskBarrierResult.AdmissionTimedOut
                case Some((ids, done)) =>
                  SubagentTaskBarrierResult.TimedOut(ids, ids.filterNot(done.contains))

  /** Record the first original waiter failure before foreachPar cancels its siblings. */
  private[subagent] def awaitAll[R, A, B](
      values: Chunk[A]
  )(await: A => ZIO[R, AgentError, B]): ZIO[R, AgentError, Chunk[B]] =
    ZIO.uninterruptibleMask: restore =>
      for
        first <- Ref.make(Option.empty[Cause[AgentError]])
        exit <- restore(
          ZIO.foreachPar(values)(value =>
            await(value).onExit:
              case Exit.Failure(cause) => first.update(_.orElse(Some(cause)))
              case Exit.Success(_)     => ZIO.unit
          )
        ).exit
        result <- exit match
          case Exit.Success(completed) => ZIO.succeed(completed)
          case Exit.Failure(aggregate) =>
            first.get.flatMap:
              case None => ZIO.refailCause(aggregate)
              case Some(original) =>
                ZIO.refailCause(retainInterrupts(aggregate, original.interruptors))
      yield result

  /** Retain the caller's services, including its permit-owning Scope, while joining our worker. */
  private[subagent] def raceWithSignal[R, A](
      effect: ZIO[R, AgentError, A],
      signal: UIO[Exit[AgentError, Unit]]
  ): ZIO[R, AgentError, Option[A]] =
    ZIO
      .environment[R]
      .flatMap: environment =>
        ZIO.scoped(ZIO.uninterruptibleMask: restore =>
          for
            worker  <- restore(effect.provideEnvironment(environment)).forkScoped
            outcome <- restore(worker.await.raceEither(signal)).exit
            result <- outcome match
              case Exit.Success(Left(exit)) => worker.inheritAll *> exit.map(Some(_))
              case Exit.Success(Right(signalExit)) =>
                worker.interrupt
                  .flatMap: exit =>
                    val cleanup = loserCleanup(exit)
                    signalExit match
                      case Exit.Success(_) =>
                        if cleanup.isEmpty then ZIO.none else ZIO.refailCause(cleanup)
                      case Exit.Failure(signalCause) =>
                        ZIO.refailCause(
                          if cleanup.isEmpty then signalCause else signalCause ++ cleanup
                        )
              case Exit.Failure(waitCause) =>
                worker.interrupt
                  .flatMap: exit =>
                    val cleanup = loserCleanup(exit)
                    ZIO.refailCause(
                      if cleanup.isEmpty || waitCause.contains(cleanup) then waitCause
                      else waitCause ++ cleanup
                    )
          yield result)

  /**
   * A stop winner owns cancellation of the losing observation tree. Retain every cleanup failure
   * and defect with its provenance; worker-winning and original caller causes remain unfiltered.
   */
  private def loserCleanup[A](exit: Exit[AgentError, A]): Cause[AgentError] = exit match
    case Exit.Success(_)     => Cause.empty
    case Exit.Failure(cause) => retainInterrupts(cause, Set.empty)

  /** Exact leaf identities include composite IDs; preserve every aggregate failure and defect. */
  private def retainInterrupts(
      cause: Cause[AgentError],
      retained: Set[FiberId]
  ): Cause[AgentError] =
    cause.foldLog[Cause[AgentError]](
      Cause.empty,
      (error, trace, spans, annotations) =>
        Cause.fail(error, trace).spanned(spans).annotated(annotations),
      (defect, trace, spans, annotations) =>
        Cause.die(defect, trace).spanned(spans).annotated(annotations),
      (id, trace, spans, annotations) =>
        if retained.contains(id) then
          Cause.interrupt(id, trace).spanned(spans).annotated(annotations)
        else Cause.empty
    )(_ ++ _, _ && _, (value, stackless) => Cause.Stackless(value, stackless))
