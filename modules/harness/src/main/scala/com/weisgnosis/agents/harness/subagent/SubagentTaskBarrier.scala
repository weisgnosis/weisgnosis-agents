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

/** Stable captured references, not a reconstructible handle or a cancellation capability. */
private[subagent] trait SubagentCapturedWait:
  def id: SubagentTaskId
  def await: IO[AgentError, SubagentTerminalRecord]
  def poll: IO[AgentError, Option[SubagentTerminalRecord]]

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
                ZIO
                  .foreachPar(captured)(entry =>
                    entry.await
                      .tap(_ => progress.update(_.map((ids, done) => ids -> (done + entry.id))))
                  )
                  .map(SubagentTaskBarrierResult.Completed(_))
        waiting
          .timeout(timeout.duration)
          .flatMap:
            case Some(result) => ZIO.succeed(result)
            case None =>
              progress.get.map:
                case None => SubagentTaskBarrierResult.AdmissionTimedOut
                case Some((ids, done)) =>
                  SubagentTaskBarrierResult.TimedOut(ids, ids.filterNot(done.contains))
