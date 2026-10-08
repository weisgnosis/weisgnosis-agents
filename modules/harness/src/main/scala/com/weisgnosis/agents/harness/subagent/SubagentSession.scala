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

import com.weisgnosis.agents.core.agent.{AgentResult, ReActAgent, SuppliedToolResult}
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.model.Msg
import com.weisgnosis.agents.core.tool.ToolEventPort
import com.weisgnosis.agents.harness.storage.StorageRevision
import zio.*

/** One host-held child runtime; the returned TaskResource owns its lifetime and descendants. */
final class SubagentSession private (
    val executions: SubagentTaskRegistry,
    submitResume: (Chunk[SuppliedToolResult], Option[ToolEventPort]) => ZIO[
      Scope,
      AgentError,
      SubagentTaskSubmission
    ]
):
  /** Each accepted send receives a distinct retained execution ID; waiting sends count toward capacity. */
  def send(input: Msg): ZIO[Scope, AgentError, SubagentTaskSubmission] = executions.submit(input)

  def send(
      input: Msg,
      events: Option[ToolEventPort]
  ): ZIO[Scope, AgentError, SubagentTaskSubmission] =
    executions.submit(input, events)

  /**
   * Resumes the checkpoint present when this execution acquires the session permit. Host-supplied
   * observations are checked by the runtime; installed authorization remains authoritative. This
   * is not a checkpoint claim or replay guard: the host must sequence confirmation delivery.
   */
  def resume(
      results: Chunk[SuppliedToolResult] = Chunk.empty,
      events: Option[ToolEventPort] = None
  ): ZIO[Scope, AgentError, SubagentTaskSubmission] =
    submitResume(results, events)

object SubagentSession:
  private enum Command:
    case Send(input: Msg)
    case Resume(results: Chunk[SuppliedToolResult])

  def open(
      parent: SubagentSpawner[AgentResult],
      factory: SubagentFactory,
      maxTracked: Int = 256,
      terminalBinding: Option[SubagentTerminalBinding] = None
  ): ZIO[Scope, AgentError, TaskResourceAdmission[SubagentSession]] =
    validateCapacity(maxTracked) *>
      parent.ownChild((child, supervisor) =>
        initialize(child, supervisor, factory, maxTracked, None, terminalBinding)
      )

  /**
   * Acquires a new host-owned runtime and restores checked pending data before publishing the
   * session. The current factory and parent supply execution authority; no execution record is
   * imported or started. Authentication, exclusive checkpoint claims and idempotency belong to
   * the host. Factory acquisition still runs even though model/tool execution awaits resume.
   */
  def openRestored(
      parent: SubagentSpawner[AgentResult],
      factory: SubagentFactory,
      checkpoint: AgentResult,
      maxTracked: Int = 256,
      terminalBinding: Option[SubagentTerminalBinding] = None
  ): ZIO[Scope, AgentError, TaskResourceAdmission[SubagentSession]] =
    validateCapacity(maxTracked) *>
      parent.ownChild((child, supervisor) =>
        initialize(child, supervisor, factory, maxTracked, Some(checkpoint), terminalBinding)
      )

  private[subagent] def openRetained(
      parent: SubagentSpawner[AgentResult],
      factory: SubagentFactory,
      maxTracked: Int,
      terminalBinding: Option[SubagentTerminalBinding] = None
  ): IO[AgentError, TaskResourceAdmission[SubagentSession]] =
    validateCapacity(maxTracked) *>
      parent.ownChildRetained((child, supervisor) =>
        initialize(child, supervisor, factory, maxTracked, None, terminalBinding)
      )

  private[subagent] def openPersistentRetained(
      parent: SubagentSpawner[AgentResult],
      factory: SubagentFactory,
      key: SubagentNamedSessionKey,
      store: SubagentNamedSessionStore,
      authority: SubagentNamedSessions.Authority,
      maxTracked: Int,
      terminalBinding: Option[SubagentTerminalBinding]
  ): IO[AgentError, TaskResourceAdmission[SubagentSession]] =
    validateCapacity(maxTracked) *> parent.ownChildRetained((child, supervisor) =>
      initialize(
        child,
        supervisor,
        factory,
        maxTracked,
        None,
        terminalBinding,
        Some(PersistenceRequest(key, store, authority))
      )
    )

  final private case class PersistenceRequest(
      key: SubagentNamedSessionKey,
      store: SubagentNamedSessionStore,
      authority: SubagentNamedSessions.Authority
  )

  private enum SaveState:
    case Ready(revision: StorageRevision)
    case Poisoned

  private def persistedCommand(
      runtime: ReActAgent,
      request: PersistenceRequest,
      state: Ref[SaveState],
      command: ZIO[Scope, AgentError, AgentResult]
  ): ZIO[Scope, AgentError, AgentResult] =
    ZIO.uninterruptibleMask: restore =>
      state.get.flatMap:
        case SaveState.Poisoned =>
          ZIO.fail(
            AgentError.StorageError(
              "Named session persistence is uncertain; explicit reopen is required"
            )
          )
        case SaveState.Ready(revision) =>
          for
            completed <- restore(ZIO.scoped(command)).exit
            snapshot  <- runtime.snapshot
            // A cancelled run still saves its joined recovery state before releasing the turn.
            // The host must bound persistence I/O; uncertainty poisons this runtime, never retries.
            saved <- request.store.save(request.key, revision, snapshot).exit
            _ <- saved match
              case Exit.Success(next) => state.set(SaveState.Ready(next))
              case Exit.Failure(_)    => state.set(SaveState.Poisoned)
            interrupted <- restore(ZIO.unit).exit
            result <- completed
              .zipWith(saved)((value, _) => value, _ ++ _)
              .zipWith(interrupted)((value, _) => value, _ ++ _)
          yield result

  private def validateCapacity(maxTracked: Int): IO[AgentError, Unit] =
    ZIO
      .fail(AgentError.InvalidConfiguration("Subagent task capacity must be positive"))
      .when(maxTracked <= 0)
      .unit

  private def initialize(
      child: SubagentSpawner[AgentResult],
      supervisor: TaskSupervisor[AgentResult],
      factory: SubagentFactory,
      maxTracked: Int,
      checkpoint: Option[AgentResult],
      terminalBinding: Option[SubagentTerminalBinding],
      persistence: Option[PersistenceRequest] = None
  ): ZIO[Scope, AgentError, SubagentSession] =
    for
      loaded <- ZIO.foreach(persistence)(request =>
        ZIO
          .fromEither(request.store.address(request.key))
          .flatMap(address =>
            ZIO.suspendSucceed(request.authority.acquire(request.key, address))
          ) *>
          request.store.load(request.key)
      )
      runtime <- loaded match
        case Some(saved) => factory.build(child, saved.snapshot)
        case None        => factory.build(child)
      savedState <- ZIO.foreach(loaded)(saved =>
        Ref.make[SaveState](SaveState.Ready(saved.revision))
      )
      _    <- ZIO.foreachDiscard(checkpoint)(runtime.restore)
      turn <- Semaphore.make(1)
      launch = (
          command: Command,
          id: SubagentTaskId,
          events: Option[ToolEventPort],
          beforeStart: IO[AgentError, Unit]
      ) =>
        val run = command match
          case Command.Send(input)     => SubagentFactory.run(runtime, input, id, events)
          case Command.Resume(results) => SubagentFactory.resume(runtime, results, id, events)
        val execute = persistence.zip(savedState) match
          case Some((request, state)) => persistedCommand(runtime, request, state, run)
          case None                   => run
        supervisor.attach(beforeStart *> turn.withPermit(execute))
      executions <- terminalBinding match
        case None =>
          SubagentTaskRegistry.openInput[Command](
            Command.Send(_),
            (command, id, events) => launch(command, id, events, ZIO.unit),
            maxTracked
          )
        case Some(binding) =>
          SubagentTaskRegistry.openPreparedInput[Command](
            Command.Send(_),
            launch,
            maxTracked,
            Some(binding.store),
            binding.workerId,
            binding.completionNotification
          )
    yield new SubagentSession(
      executions,
      (results, events) => executions.submitInput(Command.Resume(results), events)
    )
