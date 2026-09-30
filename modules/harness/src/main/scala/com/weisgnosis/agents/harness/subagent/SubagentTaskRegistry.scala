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

import com.weisgnosis.agents.core.agent.AgentResult
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.model.Msg
import com.weisgnosis.agents.core.tool.ToolEventPort
import java.util.UUID
import scala.annotation.unused
import zio.*

/** Identifies one execution within a registry; the value does not confer execution authority. */
final case class SubagentTaskId(value: UUID)

enum SubagentTaskState:
  case Running
  case Completed(exit: Exit[AgentError, AgentResult])

final case class SubagentTaskSnapshot(id: SubagentTaskId, state: SubagentTaskState)

enum SubagentTaskSubmission:
  case Accepted(id: SubagentTaskId)
  case Capacity, Closed, IdentityCollision

enum SubagentTaskRemoval:
  case Removed, Missing, Running

/**
 * Host-owned task lookup for one parent and configured factory. Every retained result counts toward
 * capacity. Scope closure cancels and joins registered work, including promoted work. Caller scopes
 * retain their normal cancellation leases. This bounds record count, not retained result bytes.
 */
trait SubagentTaskRegistry:
  def submit(input: Msg): ZIO[Scope, AgentError, SubagentTaskSubmission]

  /** Existing custom registries may retain detached execution by using this default. */
  def submit(
      input: Msg,
      @unused events: Option[ToolEventPort]
  ): ZIO[Scope, AgentError, SubagentTaskSubmission] =
    submit(input)
  def inspect(id: SubagentTaskId): UIO[Option[SubagentTaskSnapshot]]
  def list: UIO[Chunk[SubagentTaskSnapshot]]
  def await(id: SubagentTaskId): UIO[Option[Exit[AgentError, AgentResult]]]
  def awaitOrPromote(id: SubagentTaskId): UIO[Option[TaskWait[AgentResult]]]
  def cancel(id: SubagentTaskId): UIO[Option[Exit[AgentError, AgentResult]]]
  def forgetCompleted(id: SubagentTaskId): UIO[SubagentTaskRemoval]

object SubagentTaskRegistry:
  final private case class Entry(id: SubagentTaskId, task: SupervisedTask[AgentResult])

  private enum State:
    case Open(values: Chunk[Entry])
    case Closed(values: Chunk[Entry])

    def entries: Chunk[Entry] = this match
      case Open(values)   => values
      case Closed(values) => values

    def remove(id: SubagentTaskId): State = this match
      case Open(values)   => Open(values.filterNot(_.id == id))
      case Closed(values) => Closed(values.filterNot(_.id == id))

  def open(
      parent: SubagentSpawner[AgentResult],
      factory: SubagentFactory,
      maxTracked: Int = 256
  ): ZIO[Scope, AgentError, SubagentTaskRegistry] =
    openWith((input, id, events) => factory.spawn(parent, input, id, events), maxTracked)

  private[subagent] def openWith(
      launchTask: (Msg, SubagentTaskId, Option[ToolEventPort]) => ZIO[
        Scope,
        AgentError,
        TaskAdmission[AgentResult]
      ],
      maxTracked: Int
  ): ZIO[Scope, AgentError, SubagentTaskRegistry] =
    ZIO.uninterruptible:
      for
        _ <- ZIO
          .fail(AgentError.InvalidConfiguration("Subagent task capacity must be positive"))
          .when(maxTracked <= 0)
        gate  <- Semaphore.make(1)
        state <- Ref.make[State](State.Open(Chunk.empty))
        registry = new Live(launchTask, maxTracked, gate, state)
        _ <- ZIO.addFinalizer(registry.close)
      yield registry

  final private class Live(
      launchTask: (Msg, SubagentTaskId, Option[ToolEventPort]) => ZIO[
        Scope,
        AgentError,
        TaskAdmission[AgentResult]
      ],
      maxTracked: Int,
      gate: Semaphore,
      state: Ref[State]
  ) extends SubagentTaskRegistry:
    def submit(input: Msg): ZIO[Scope, AgentError, SubagentTaskSubmission] =
      submit(input, None)

    override def submit(
        input: Msg,
        events: Option[ToolEventPort]
    ): ZIO[Scope, AgentError, SubagentTaskSubmission] =
      ZIO.uninterruptibleMask: restore =>
        Scope.make.flatMap: lease =>
          // Register the caller lease outside admission: a closed caller may synchronously join cleanup.
          restore(gate.withPermit(ZIO.uninterruptible(lease.extend(launch(input, events)))))
            .onExit:
              case Exit.Success(SubagentTaskSubmission.Accepted(_)) =>
                ZIO.addFinalizer(lease.close(Exit.unit))
              case Exit.Success(_)          => lease.close(Exit.unit)
              case failed @ Exit.Failure(_) => lease.close(failed)

    private def launch(
        input: Msg,
        events: Option[ToolEventPort]
    ): ZIO[Scope, AgentError, SubagentTaskSubmission] =
      state.get.flatMap:
        case State.Closed(_) => ZIO.succeed(SubagentTaskSubmission.Closed)
        case State.Open(entries) if entries.size >= maxTracked =>
          ZIO.succeed(SubagentTaskSubmission.Capacity)
        case State.Open(entries) =>
          Random.nextUUID
            .map(SubagentTaskId(_))
            .flatMap: id =>
              if entries.exists(_.id == id) then
                ZIO.succeed(SubagentTaskSubmission.IdentityCollision)
              else
                ZIO
                  .suspendSucceed(launchTask(input, id, events))
                  .flatMap:
                    case TaskAdmission.Closed => ZIO.succeed(SubagentTaskSubmission.Closed)
                    case TaskAdmission.Accepted(task) =>
                      state
                        .set(State.Open(entries :+ Entry(id, task)))
                        .as(SubagentTaskSubmission.Accepted(id))

    def inspect(id: SubagentTaskId): UIO[Option[SubagentTaskSnapshot]] =
      lookup(id).flatMap(ZIO.foreach(_)(snapshot))

    /** Membership is captured once; each task's completion is then observed independently. */
    def list: UIO[Chunk[SubagentTaskSnapshot]] =
      state.get.flatMap(current => ZIO.foreach(current.entries)(snapshot))

    def await(id: SubagentTaskId): UIO[Option[Exit[AgentError, AgentResult]]] =
      lookup(id).flatMap(ZIO.foreach(_)(_.task.await))

    def awaitOrPromote(id: SubagentTaskId): UIO[Option[TaskWait[AgentResult]]] =
      lookup(id).flatMap(ZIO.foreach(_)(_.task.awaitOrPromote))

    def cancel(id: SubagentTaskId): UIO[Option[Exit[AgentError, AgentResult]]] =
      lookup(id).flatMap(ZIO.foreach(_)(_.task.cancel))

    def forgetCompleted(id: SubagentTaskId): UIO[SubagentTaskRemoval] =
      gate.withPermit:
        lookup(id).flatMap:
          case None => ZIO.succeed(SubagentTaskRemoval.Missing)
          case Some(entry) =>
            entry.task.poll.flatMap:
              case None    => ZIO.succeed(SubagentTaskRemoval.Running)
              case Some(_) => state.update(_.remove(id)).as(SubagentTaskRemoval.Removed)

    private def lookup(id: SubagentTaskId): UIO[Option[Entry]] =
      state.get.map(_.entries.find(_.id == id))

    private def snapshot(entry: Entry): UIO[SubagentTaskSnapshot] =
      entry.task.poll.map: result =>
        SubagentTaskSnapshot(
          entry.id,
          result.fold[SubagentTaskState](SubagentTaskState.Running)(SubagentTaskState.Completed(_))
        )

    def close: UIO[Unit] =
      ZIO.uninterruptible:
        gate
          .withPermit(state.modify(current => (current.entries, State.Closed(current.entries))))
          .flatMap(entries => ZIO.foreachParDiscard(entries)(_.task.cancel))
