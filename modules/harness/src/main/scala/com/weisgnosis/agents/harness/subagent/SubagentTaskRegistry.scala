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
import com.weisgnosis.agents.core.event.AgentEvent
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

private[subagent] enum SubagentWaitWorkState:
  case Empty, Running, StoredUnresolved, AllTerminal

/** A retained, fully joined outcome; delivery does not transfer execution ownership. */
final case class SubagentTaskDelivery(id: SubagentTaskId, exit: Exit[AgentError, AgentResult])

enum SubagentDeliveryAcknowledgement:
  case Acknowledged, AlreadyAcknowledged, Running, Missing

enum SubagentTaskSubmission:
  case Accepted(id: SubagentTaskId)
  case Capacity, Closed, IdentityCollision

/** Local joined execution data or a durable request without inferred execution ownership. */
enum SubagentTaskCancellation:
  case Missing
  case Requested(record: SubagentTaskRecord)
  case AlreadyTerminal(record: SubagentTaskRecord)
  case Joined(exit: Exit[AgentError, AgentResult])

enum SubagentTaskRemoval:
  case Removed, Missing, Running, Unpublished, Unnotified, MilestonesPending

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

  /** Host-only per-submission policy; custom registries must explicitly support this capability. */
  def submitWithCompletionNotification(
      @unused input: Msg,
      @unused policy: SubagentCompletionNotification,
      @unused events: Option[ToolEventPort] = None
  ): ZIO[Scope, AgentError, SubagentTaskSubmission] =
    ZIO.fail(
      AgentError.InvalidConfiguration("This registry does not support completion notifications")
    )

  def completionNotification(
      @unused id: SubagentTaskId
  ): IO[AgentError, Option[SubagentCompletionReceipt]] =
    ZIO.fail(
      AgentError.InvalidConfiguration("This registry does not support completion notifications")
    )

  def flushCompletionNotifications: IO[AgentError, Chunk[SubagentTaskCompletionReceipt]] =
    ZIO.fail(
      AgentError.InvalidConfiguration("This registry does not support completion notifications")
    )

  def inspect(id: SubagentTaskId): UIO[Option[SubagentTaskSnapshot]]
  def list: UIO[Chunk[SubagentTaskSnapshot]]
  def await(id: SubagentTaskId): UIO[Option[Exit[AgentError, AgentResult]]]
  def awaitOrPromote(id: SubagentTaskId): UIO[Option[TaskWait[AgentResult]]]
  def cancel(id: SubagentTaskId): UIO[Option[Exit[AgentError, AgentResult]]]

  /** Default custom registries preserve local cancellation only; no durable intent is implied. */
  def cancelTask(id: SubagentTaskId): IO[AgentError, SubagentTaskCancellation] =
    cancel(id).flatMap:
      case None => ZIO.succeed(SubagentTaskCancellation.Missing)
      case Some(joined) =>
        ZIO.uninterruptibleMask: restore =>
          restore(awaitTerminalPublication(id)).exit.flatMap:
            case Exit.Success(_)     => ZIO.succeed(SubagentTaskCancellation.Joined(joined))
            case Exit.Failure(cause) =>
              // Preserve the existing custom publication boundary and its caller-cancellation cause.
              val child =
                if cause.isInterrupted then joined.causeOption.getOrElse(Cause.empty)
                else Cause.empty
              ZIO.refailCause(if child.isEmpty then cause else cause ++ child)

  def forgetCompleted(id: SubagentTaskId): UIO[SubagentTaskRemoval]

  /** Effectful observation preserves live-only custom registry behavior by default. */
  def observe(id: SubagentTaskId): IO[AgentError, Option[SubagentTaskObservation]] =
    inspect(id).map(_.map(SubagentTaskObservation.fromSnapshot))

  def observations: IO[AgentError, Chunk[SubagentTaskObservation]] =
    list.map(_.map(SubagentTaskObservation.fromSnapshot))

  /** Status-only admission never waits for publication and never exposes terminal result payloads. */
  private[subagent] def waitWorkState: IO[AgentError, SubagentWaitWorkState] =
    ZIO.fail(AgentError.InvalidConfiguration("This registry does not support async wait readiness"))

  /** Host-driven refresh of captured local admissions; no maintenance worker is installed. */
  def refreshCurrentOwner: IO[AgentError, Chunk[SubagentTaskHeartbeatResult]] =
    ZIO.fail(AgentError.InvalidConfiguration("This registry does not support durable heartbeat"))

  /** Explicit host-owned reconciliation of this registry's bound partition only. */
  def recoverStaleTasks(
      @unused policy: SubagentTaskRecovery.Policy,
      @unused claimGate: SubagentTaskRecovery.Gate,
      @unused authority: SubagentTaskRecovery.Authority
  ): ZIO[Scope, AgentError, SubagentTaskRecovery.Result] =
    ZIO.fail(AgentError.InvalidConfiguration("This registry does not support durable recovery"))

  /** Custom registries retain their existing captured-wait capability unless explicitly overridden. */
  private[subagent] def captureOutput(
      id: SubagentTaskId
  ): IO[AgentError, SubagentTaskOutputTool.Capture] =
    for
      selection <- ZIO.fromEither(SubagentTaskSelection.selected(Chunk(id)))
      captured  <- captureWait(selection)
      entry <- ZIO
        .fromOption(captured.headOption)
        .orElseFail(
          AgentError.InvalidConfiguration("No task with this ID exists in the selected registry")
        )
    yield SubagentTaskOutputTool.Capture.Local(entry)

  /** Custom registries must explicitly implement stable captured wait ownership. */
  private[subagent] def captureWait(
      @unused selection: SubagentTaskSelection
  ): IO[AgentError, Chunk[SubagentCapturedWait]] =
    ZIO.fail(AgentError.InvalidConfiguration("This registry does not support task wait barriers"))

  /** Built-in durable tools wait for publication before presenting a joined live outcome. */
  private[subagent] def awaitTerminalPublication(@unused id: SubagentTaskId): IO[AgentError, Unit] =
    ZIO.unit

  /** Durable terminal observations awaiting explicit host acknowledgement; this is not a claim. */
  def durablePendingDeliveries: IO[AgentError, Chunk[SubagentTerminalRecord]] =
    ZIO.fail(AgentError.InvalidConfiguration("This registry does not support durable delivery"))

  def terminalAcknowledgement(
      @unused id: SubagentTaskId
  ): IO[AgentError, Option[SubagentTerminalAcknowledgement]] =
    ZIO.fail(AgentError.InvalidConfiguration("This registry does not support durable delivery"))

  /** Missing means no persisted terminal observation, not absence of a live execution. */
  def acknowledgeTerminal(
      @unused id: SubagentTaskId
  ): IO[AgentError, SubagentTerminalAcknowledgeResult] =
    ZIO.fail(AgentError.InvalidConfiguration("This registry does not support durable delivery"))

  /** Await a retained publication receipt without cancelling its owned worker. */
  def publication(@unused id: SubagentTaskId): IO[AgentError, Option[SubagentTerminalPublication]] =
    ZIO.fail(AgentError.InvalidConfiguration("This registry does not support durable publication"))

  /** Captures current membership and joins every publication before reporting combined failures. */
  def flushPublications: IO[AgentError, Chunk[SubagentTaskPublication]] =
    ZIO.fail(AgentError.InvalidConfiguration("This registry does not support durable publication"))

  /**
   * Completed foreground and promoted tasks awaiting host acknowledgement, in submission order.
   * Membership and acknowledgement are captured once, then completion is polled independently.
   * Concurrent acknowledgement or forgetting may leave a stale delivery in this snapshot. Hosts
   * own delivery; this is not an exclusive claim, durable record or event-stream replay.
   */
  def pendingDeliveries: IO[AgentError.InvalidConfiguration, Chunk[SubagentTaskDelivery]] =
    ZIO.fail(
      AgentError.InvalidConfiguration(
        "This task registry does not support delivery acknowledgement"
      )
    )

  /**
   * Acknowledgement retains the outcome and capacity; forgetCompleted explicitly discards both.
   * Cancellation may lose the acknowledgement receipt; retry reports AlreadyAcknowledged.
   */
  def acknowledge(
      @unused id: SubagentTaskId
  ): IO[AgentError.InvalidConfiguration, SubagentDeliveryAcknowledgement] =
    ZIO.fail(
      AgentError.InvalidConfiguration(
        "This task registry does not support delivery acknowledgement"
      )
    )

object SubagentTaskRegistry:
  private enum LaunchProtocol:
    case TerminalOnly, Prepared

  private enum CallbackContext:
    case Outside, Notifying

  private enum DeliveryState:
    case Pending, Acknowledged

  final private case class Entry(
      id: SubagentTaskId,
      task: SupervisedTask[AgentResult],
      metadata: Option[Ref[SubagentTaskMetadata]],
      delivery: DeliveryState = DeliveryState.Pending,
      publisher: Option[Fiber.Runtime[AgentError, SubagentTerminalPublication]] = None,
      admissionIdentity: Option[SubagentTaskAdmissionIdentity] = None,
      notification: Option[
        Fiber.Runtime[AgentError.CompletionObservationError, SubagentCompletionReceipt]
      ] = None,
      milestones: Option[Fiber.Runtime[Nothing, Unit]] = None
  )

  final private case class Persistence(store: SubagentTerminalStore, owner: Scope.Closeable)

  private enum State:
    case Open(values: Chunk[Entry])
    case Closed(values: Chunk[Entry])

    def entries: Chunk[Entry] = this match
      case Open(values)   => values
      case Closed(values) => values

    def remove(id: SubagentTaskId): State = this match
      case Open(values)   => Open(values.filterNot(_.id == id))
      case Closed(values) => Closed(values.filterNot(_.id == id))

    def acknowledge(id: SubagentTaskId): State =
      val updated = entries.map(entry =>
        if entry.id == id then entry.copy(delivery = DeliveryState.Acknowledged) else entry
      )
      this match
        case Open(_)   => Open(updated)
        case Closed(_) => Closed(updated)

  def open(
      parent: SubagentSpawner[AgentResult],
      factory: SubagentFactory,
      maxTracked: Int = 256,
      workerId: Option[SubagentWorkerId] = None,
      lifecycle: Option[SubagentLifecycleMetadata] = None
  ): ZIO[Scope, AgentError, SubagentTaskRegistry] = lifecycle match
    case None =>
      openWith(
        (input, id, events) => factory.spawn(parent, input, id, events),
        maxTracked,
        workerId = workerId
      )
    case Some(metadata) =>
      openEventPreparedInput[Msg](
        identity,
        (input, id, events, beforeStart, started) =>
          factory.spawn(parent, input, id, events, beforeStart, started),
        maxTracked,
        None,
        workerId,
        SubagentCompletionNotification.Suppress,
        ZIO.some(metadata)
      )

  /** Opt-in automatic terminal publication and restored read-only observations. */
  def openDurable(
      parent: SubagentSpawner[AgentResult],
      factory: SubagentFactory,
      terminalStore: SubagentTerminalStore,
      maxTracked: Int = 256,
      workerId: Option[SubagentWorkerId] = None,
      completionNotification: SubagentCompletionNotification =
        SubagentCompletionNotification.Suppress,
      lifecycle: Option[SubagentLifecycleMetadata] = None
  ): ZIO[Scope, AgentError, SubagentTaskRegistry] =
    openEventPreparedInput[Msg](
      identity,
      (input, id, events, beforeStart, started) =>
        factory.spawn(parent, input, id, events, beforeStart, started),
      maxTracked,
      Some(terminalStore),
      workerId,
      completionNotification,
      ZIO.succeed(lifecycle)
    )

  private[subagent] def openWith(
      launchTask: (Msg, SubagentTaskId, Option[ToolEventPort]) => ZIO[
        Scope,
        AgentError,
        TaskAdmission[AgentResult]
      ],
      maxTracked: Int,
      terminalStore: Option[SubagentTerminalStore] = None,
      workerId: Option[SubagentWorkerId] = None
  ): ZIO[Scope, AgentError, SubagentTaskRegistry] =
    openInput[Msg](identity, launchTask, maxTracked, terminalStore, workerId)

  private[subagent] def openInput[Input](
      fromMessage: Msg => Input,
      launchTask: (Input, SubagentTaskId, Option[ToolEventPort]) => ZIO[
        Scope,
        AgentError,
        TaskAdmission[AgentResult]
      ],
      maxTracked: Int,
      terminalStore: Option[SubagentTerminalStore] = None,
      workerId: Option[SubagentWorkerId] = None
  ): ZIO[Scope, AgentError, Live[Input]] =
    initialize(
      fromMessage,
      (input, id, events, _, _) => launchTask(input, id, events),
      maxTracked,
      terminalStore,
      workerId,
      LaunchProtocol.TerminalOnly,
      SubagentCompletionNotification.Suppress,
      ZIO.none
    )

  private[subagent] def openPreparedInput[Input](
      fromMessage: Msg => Input,
      launchTask: (Input, SubagentTaskId, Option[ToolEventPort], IO[AgentError, Unit]) => ZIO[
        Scope,
        AgentError,
        TaskAdmission[AgentResult]
      ],
      maxTracked: Int,
      terminalStore: Option[SubagentTerminalStore] = None,
      workerId: Option[SubagentWorkerId] = None,
      completionNotification: SubagentCompletionNotification =
        SubagentCompletionNotification.Suppress
  ): ZIO[Scope, AgentError, Live[Input]] =
    initialize(
      fromMessage,
      (input, id, events, beforeStart, started) =>
        launchTask(input, id, events, beforeStart *> started),
      maxTracked,
      terminalStore,
      workerId,
      LaunchProtocol.Prepared,
      completionNotification,
      ZIO.none
    )

  private[subagent] def openEventPreparedInput[Input](
      fromMessage: Msg => Input,
      launchTask: (
          Input,
          SubagentTaskId,
          Option[ToolEventPort],
          IO[AgentError, Unit],
          UIO[Unit]
      ) => ZIO[
        Scope,
        AgentError,
        TaskAdmission[AgentResult]
      ],
      maxTracked: Int,
      terminalStore: Option[SubagentTerminalStore],
      workerId: Option[SubagentWorkerId],
      completionNotification: SubagentCompletionNotification,
      lifecycle: UIO[Option[SubagentLifecycleMetadata]]
  ): ZIO[Scope, AgentError, Live[Input]] =
    initialize(
      fromMessage,
      launchTask,
      maxTracked,
      terminalStore,
      workerId,
      LaunchProtocol.Prepared,
      completionNotification,
      lifecycle
    )

  private def initialize[Input](
      fromMessage: Msg => Input,
      launchTask: (
          Input,
          SubagentTaskId,
          Option[ToolEventPort],
          IO[AgentError, Unit],
          UIO[Unit]
      ) => ZIO[
        Scope,
        AgentError,
        TaskAdmission[AgentResult]
      ],
      maxTracked: Int,
      terminalStore: Option[SubagentTerminalStore],
      workerId: Option[SubagentWorkerId],
      protocol: LaunchProtocol,
      defaultNotification: SubagentCompletionNotification,
      lifecycle: UIO[Option[SubagentLifecycleMetadata]]
  ): ZIO[Scope, AgentError, Live[Input]] =
    ZIO.uninterruptible:
      for
        _ <- ZIO
          .fail(AgentError.InvalidConfiguration("Subagent task capacity must be positive"))
          .when(maxTracked <= 0)
        gate            <- Semaphore.make(1)
        state           <- Ref.make[State](State.Open(Chunk.empty))
        callbackContext <- FiberRef.make(CallbackContext.Outside)
        owner           <- Scope.make
        persistence = terminalStore.map(store => Persistence(store, owner))
        registry = new Live(
          fromMessage,
          launchTask,
          maxTracked,
          gate,
          state,
          persistence,
          workerId,
          protocol,
          defaultNotification,
          callbackContext,
          owner,
          lifecycle
        )
        _ <- ZIO.addFinalizer(registry.close)
      yield registry

  final private[subagent] class Live[Input] private[SubagentTaskRegistry] (
      fromMessage: Msg => Input,
      launchTask: (
          Input,
          SubagentTaskId,
          Option[ToolEventPort],
          IO[AgentError, Unit],
          UIO[Unit]
      ) => ZIO[
        Scope,
        AgentError,
        TaskAdmission[AgentResult]
      ],
      maxTracked: Int,
      gate: Semaphore,
      state: Ref[State],
      persistence: Option[Persistence],
      workerId: Option[SubagentWorkerId],
      protocol: LaunchProtocol,
      defaultNotification: SubagentCompletionNotification,
      callbackContext: FiberRef[CallbackContext],
      owner: Scope.Closeable,
      lifecycle: UIO[Option[SubagentLifecycleMetadata]]
  ) extends SubagentTaskRegistry:
    def submit(input: Msg): ZIO[Scope, AgentError, SubagentTaskSubmission] =
      submit(input, None)

    override def submit(
        input: Msg,
        events: Option[ToolEventPort]
    ): ZIO[Scope, AgentError, SubagentTaskSubmission] =
      submitInput(fromMessage(input), events)

    private[subagent] def submitInput(
        input: Input,
        events: Option[ToolEventPort]
    ): ZIO[Scope, AgentError, SubagentTaskSubmission] =
      submitNotified(input, events, defaultNotification)

    override def submitWithCompletionNotification(
        input: Msg,
        policy: SubagentCompletionNotification,
        events: Option[ToolEventPort]
    ): ZIO[Scope, AgentError, SubagentTaskSubmission] = persistence match
      case None    => super.submitWithCompletionNotification(input, policy, events)
      case Some(_) => submitNotified(fromMessage(input), events, policy)

    private def submitNotified(
        input: Input,
        events: Option[ToolEventPort],
        policy: SubagentCompletionNotification
    ): ZIO[Scope, AgentError, SubagentTaskSubmission] =
      ZIO.uninterruptibleMask: restore =>
        for
          lease    <- Scope.make
          captured <- Ref.make(Option.empty[Entry])
          close = (exit: Exit[Any, Any]) =>
            lease
              .close(exit)
              .ensuring(
                captured.get.flatMap(
                  ZIO.foreachDiscard(_)(entry =>
                    entry.task.poll.flatMap:
                      case Some(_) => awaitMilestones(entry)
                      case None    => ZIO.unit
                  )
                )
              )
          // Register the caller lease outside admission: a closed caller may synchronously join cleanup.
          result <- restore(gate.withPermit(lease.extend(launch(input, events, policy, captured))))
            .onExit:
              case Exit.Success(SubagentTaskSubmission.Accepted(_)) =>
                ZIO.addFinalizer(close(Exit.unit))
              case Exit.Success(_)          => close(Exit.unit)
              case failed @ Exit.Failure(_) => close(failed)
        yield result

    private def launch(
        input: Input,
        events: Option[ToolEventPort],
        policy: SubagentCompletionNotification,
        captured: Ref[Option[Entry]]
    ): ZIO[Scope, AgentError, SubagentTaskSubmission] =
      ZIO.uninterruptibleMask: restore =>
        state.get.flatMap:
          case State.Closed(_) => ZIO.succeed(SubagentTaskSubmission.Closed)
          case State.Open(entries) if entries.size >= maxTracked =>
            ZIO.succeed(SubagentTaskSubmission.Capacity)
          case State.Open(entries) =>
            for
              id <- Random.nextUUID.map(SubagentTaskId(_))
              collision <-
                if entries.exists(_.id == id) then ZIO.succeed(true)
                else restore(ZIO.foreach(persistence)(_.store.read(id))).map(_.flatten.nonEmpty)
              admission <-
                if collision then ZIO.succeed(SubagentTaskSubmission.IdentityCollision)
                else
                  for
                    metadata <- persistence match
                      case Some(_) => ZIO.none
                      case None =>
                        Clock.instant
                          .flatMap(at => Ref.make(SubagentTaskMetadata(at, at, at)).map(Some(_)))
                    start <- Promise.make[Nothing, Option[SubagentTaskAdmissionIdentity]]
                    beforeStart = start.await.flatMap(identity =>
                      ZIO.foreachDiscard(identity)(value =>
                        ZIO.foreachDiscard(persistence)(
                          _.store
                            .confirmStart(value)
                            .flatMap:
                              case SubagentTaskStartResult.Started               => ZIO.unit
                              case SubagentTaskStartResult.CancellationRequested => ZIO.interrupt
                        )
                      )
                    )
                    metadataForEvents <- lifecycle
                    started = ZIO.foreachDiscard(metadataForEvents.zip(events)):
                      (metadata, destination) =>
                        destination
                          .publish(
                            AgentEvent.SubagentStartEvent(
                              metadata.childSessionId,
                              metadata.agentId,
                              Some(id.value.toString)
                            )
                          )
                          .unit
                    launched <- ZIO.suspendSucceed(
                      launchTask(input, id, events, beforeStart, started)
                    )
                    result <- launched match
                      case TaskAdmission.Closed => ZIO.succeed(SubagentTaskSubmission.Closed)
                      case TaskAdmission.Accepted(task) =>
                        for
                          durable <- (protocol match
                            case LaunchProtocol.TerminalOnly =>
                              ZIO.succeed(Option.empty[SubagentTaskAdmissionResult])
                            case LaunchProtocol.Prepared =>
                              restore(ZIO.foreach(persistence)(_.store.admit(id, workerId)))
                          ).exit
                          accepted <- durable match
                            case Exit.Failure(cause) => rollbackPaused(task, Exit.failCause(cause))
                            case Exit.Success(Some(SubagentTaskAdmissionResult.Existing(_))) =>
                              rollbackPaused(
                                task,
                                Exit.succeed(SubagentTaskSubmission.IdentityCollision)
                              )
                            case Exit.Success(Some(SubagentTaskAdmissionResult.Created(identity)))
                                if identity.origin != SubagentExecutionOrigin.Local || identity.id != id || identity.workerId != workerId =>
                              rollbackPaused(
                                task,
                                Exit.fail(
                                  AgentError.StorageError(
                                    "Local admission returned a different execution identity"
                                  )
                                )
                              )
                            case Exit.Success(created) =>
                              val identity = created.collect {
                                case SubagentTaskAdmissionResult.Created(value) => value
                              }
                              for
                                spawnAttempted <- Promise.make[Nothing, Unit]
                                publisher <- ZIO.foreach(persistence)(binding =>
                                  binding.store
                                    .publishJoined(id, task, workerId)
                                    .interruptible
                                    .forkIn(binding.owner)
                                )
                                notification <- ZIO.foreach(persistence.zip(publisher)):
                                  (binding, published) =>
                                    notifyCompletion(id, task, published, policy).interruptible
                                      .forkIn(binding.owner)
                                milestones <- ZIO.foreach(metadataForEvents.zip(events)):
                                  (metadata, destination) =>
                                    // A supervisor can join the paused worker before acceptance.
                                    // End follows the Spawn attempt, even when Start never occurs.
                                    (spawnAttempted.await *> task.await)
                                      .flatMap(exit =>
                                        destination
                                          .publish(
                                            AgentEvent.SubagentEndEvent(
                                              metadata.childSessionId,
                                              metadata.agentId,
                                              lifecycleStatus(
                                                SubagentTerminalOutcome.fromExit(exit)
                                              ),
                                              Some(id.value.toString)
                                            )
                                          )
                                          .unit
                                      )
                                      .interruptible
                                      .forkIn(owner)
                                entry = Entry(
                                  id,
                                  task,
                                  metadata,
                                  publisher = publisher,
                                  admissionIdentity = identity,
                                  notification = notification,
                                  milestones = milestones
                                )
                                _ <- state.set(State.Open(entries :+ entry))
                                // Capture before release: caller interruption can mask the Accepted return.
                                _ <- captured.set(Some(entry))
                                publishSpawn = ZIO.foreachDiscard(metadataForEvents.zip(events)):
                                  (metadata, destination) =>
                                    destination
                                      .publish(
                                        AgentEvent.SubagentSpawnEvent(
                                          metadata.parentSessionId,
                                          metadata.childSessionId,
                                          metadata.agentId,
                                          metadata.label,
                                          Some(id.value.toString)
                                        )
                                      )
                                      .unit
                                _ <- publishSpawn.ensuring(spawnAttempted.succeed(()).unit)
                                _ <- start.succeed(identity)
                              yield SubagentTaskSubmission.Accepted(id)
                        yield accepted
                  yield result
            yield admission

    private def lifecycleStatus(outcome: SubagentTerminalOutcome): String = outcome match
      case SubagentTerminalOutcome.Completed(_)      => "completed"
      case SubagentTerminalOutcome.Suspended(_)      => "suspended"
      case SubagentTerminalOutcome.Stopped(_)        => "stopped"
      case SubagentTerminalOutcome.Interrupted(_, _) => "interrupted"
      case SubagentTerminalOutcome.Failed(_, _)      => "failed"

    private def awaitMilestones(entry: Entry): UIO[Unit] =
      ZIO.foreachDiscard(entry.milestones)(_.join)

    private def notifyCompletion(
        id: SubagentTaskId,
        task: SupervisedTask[AgentResult],
        publisher: Fiber.Runtime[AgentError, SubagentTerminalPublication],
        policy: SubagentCompletionNotification
    ): IO[AgentError.CompletionObservationError, SubagentCompletionReceipt] =
      task.await.flatMap: joined =>
        publisher.await.flatMap:
          case Exit.Failure(cause) => ZIO.succeed(SubagentCompletionReceipt.NotPublished(cause))
          case Exit.Success(publication) =>
            policy match
              case SubagentCompletionNotification.Suppress =>
                ZIO.succeed(SubagentCompletionReceipt.Suppressed)
              case SubagentCompletionNotification.Notify(callback) =>
                callbackContext
                  .locally(CallbackContext.Notifying)(
                    ZIO.scoped(
                      ZIO.suspendSucceed(
                        callback.observe(
                          SubagentTerminalRecord(
                            id,
                            SubagentTerminalOutcome.fromExit(joined),
                            workerId
                          ),
                          publication
                        )
                      )
                    )
                  )
                  .as(SubagentCompletionReceipt.Observed)

    /**
     * Rejection owns cancellation of the paused losing task tree, whose interruption leaves are
     * expected cleanup. Preserve every failure/defect with provenance and the original admission
     * cause unfiltered, including genuine caller interruption. The caller lease still joins once
     * more as an idempotent ownership fallback, without replaying this captured task Exit.
     */
    private def rollbackPaused[A](
        task: SupervisedTask[AgentResult],
        result: Exit[AgentError, A]
    ): IO[AgentError, A] = task.cancel.flatMap: exit =>
      val cleanup = ownedCancellationCleanup(exit)
      result match
        case Exit.Success(value) =>
          if cleanup.isEmpty then ZIO.succeed(value) else ZIO.refailCause(cleanup)
        case Exit.Failure(cause) =>
          ZIO.refailCause(if cleanup.isEmpty then cause else cause ++ cleanup)

    private def ownedCancellationCleanup[A](exit: Exit[AgentError, A]): Cause[AgentError] =
      exit match
        case Exit.Success(_) => Cause.empty
        case Exit.Failure(cause) =>
          cause.foldLog[Cause[AgentError]](
            Cause.empty,
            (error, trace, spans, annotations) =>
              Cause.fail(error, trace).spanned(spans).annotated(annotations),
            (defect, trace, spans, annotations) =>
              Cause.die(defect, trace).spanned(spans).annotated(annotations),
            (_, _, _, _) => Cause.empty
          )(_ ++ _, _ && _, (value, stackless) => Cause.Stackless(value, stackless))

    def inspect(id: SubagentTaskId): UIO[Option[SubagentTaskSnapshot]] =
      lookup(id).flatMap(ZIO.foreach(_)(snapshot))

    /** Membership is captured once; each task's completion is then observed independently. */
    def list: UIO[Chunk[SubagentTaskSnapshot]] =
      state.get.flatMap(current => ZIO.foreach(current.entries)(snapshot))

    def await(id: SubagentTaskId): UIO[Option[Exit[AgentError, AgentResult]]] =
      lookup(id).flatMap(ZIO.foreach(_)(entry => entry.task.await.tap(_ => awaitMilestones(entry))))

    def awaitOrPromote(id: SubagentTaskId): UIO[Option[TaskWait[AgentResult]]] =
      lookup(id).flatMap(
        ZIO.foreach(_)(entry =>
          entry.task.awaitOrPromote.tap:
            case TaskWait.Completed(_) => awaitMilestones(entry)
            case TaskWait.Promoted     => ZIO.unit
        )
      )

    def cancel(id: SubagentTaskId): UIO[Option[Exit[AgentError, AgentResult]]] =
      lookup(id).flatMap(
        ZIO.foreach(_)(entry => entry.task.cancel.tap(_ => awaitMilestones(entry)))
      )

    /** Intent persistence is interruptible; a confirmed local signal owns and joins its child cleanup. */
    override def cancelTask(id: SubagentTaskId): IO[AgentError, SubagentTaskCancellation] =
      ZIO.uninterruptibleMask: restore =>
        lookup(id).flatMap: captured =>
          persistence match
            case None =>
              ZIO
                .foreach(captured)(entry => entry.task.cancel.tap(_ => awaitMilestones(entry)))
                .map(
                  _.fold[SubagentTaskCancellation](SubagentTaskCancellation.Missing)(
                    SubagentTaskCancellation.Joined(_)
                  )
                )
            case Some(binding) =>
              val expected = captured.flatMap(_.admissionIdentity)
              if expected.isEmpty && captured.nonEmpty then
                captured match
                  case Some(entry) =>
                    // Private terminal-only launchers can observe an already joined worker, never
                    // signal a running child or claim that cancellation intent was persisted.
                    entry.task.poll.flatMap:
                      case Some(joined) => observeCancellation(entry, joined, restore)
                      case None =>
                        ZIO.fail(
                          AgentError.InvalidConfiguration(
                            "This terminal-only task has no confirmed durable cancellation identity"
                          )
                        )
                  case None =>
                    ZIO.fail(AgentError.InvalidConfiguration("Missing captured terminal-only task"))
              else
                restore(binding.store.requestCancellation(id, expected)).flatMap: receipt =>
                  val record = receipt match
                    case SubagentCancellationRequestResult.Missing                 => None
                    case SubagentCancellationRequestResult.Requested(value)        => Some(value)
                    case SubagentCancellationRequestResult.AlreadyRequested(value) => Some(value)
                    case SubagentCancellationRequestResult.AlreadyTerminal(value)  => Some(value)
                  val matches = record.forall(_.id == id) && expected.forall(identity =>
                    record.exists(value =>
                      value.id == identity.id && value.workerId == identity.workerId && value.origin == identity.origin && value.metadata
                        .exists(_.createdAt == identity.createdAt)
                    )
                  )
                  val validReceipt = receipt match
                    case SubagentCancellationRequestResult.Missing => true
                    case SubagentCancellationRequestResult.Requested(value) =>
                      value.terminal.isEmpty && value.cancellationIntent != SubagentCancellationIntent.NotRequested
                    case SubagentCancellationRequestResult.AlreadyRequested(value) =>
                      value.terminal.isEmpty && value.cancellationIntent != SubagentCancellationIntent.NotRequested
                    case SubagentCancellationRequestResult.AlreadyTerminal(value) =>
                      value.terminal.nonEmpty
                  if !matches || !validReceipt then
                    ZIO.fail(
                      AgentError.StorageError(
                        "Cancellation receipt does not match the captured durable admission"
                      )
                    )
                  else
                    receipt match
                      case SubagentCancellationRequestResult.Missing =>
                        ZIO.succeed(SubagentTaskCancellation.Missing)
                      case SubagentCancellationRequestResult.AlreadyTerminal(value) =>
                        ZIO.succeed(SubagentTaskCancellation.AlreadyTerminal(value))
                      case SubagentCancellationRequestResult.Requested(value) =>
                        cancelConfirmed(captured, value, restore)
                      case SubagentCancellationRequestResult.AlreadyRequested(value) =>
                        cancelConfirmed(captured, value, restore)

    private def cancelConfirmed(
        captured: Option[Entry],
        record: SubagentTaskRecord,
        restore: ZIO.InterruptibilityRestorer
    ): IO[AgentError, SubagentTaskCancellation] = captured match
      case None => ZIO.succeed(SubagentTaskCancellation.Requested(record))
      case Some(entry) =>
        entry.task.cancel.flatMap(joined => observeCancellation(entry, joined, restore))

    private def observeCancellation(
        entry: Entry,
        joined: Exit[AgentError, AgentResult],
        restore: ZIO.InterruptibilityRestorer
    ): IO[AgentError, SubagentTaskCancellation] =
      restore(
        awaitMilestones(entry) *> ZIO.foreachDiscard(entry.publisher)(_.await.flatMap(exit => exit))
      ).exit.flatMap:
        case Exit.Success(_)     => ZIO.succeed(SubagentTaskCancellation.Joined(joined))
        case Exit.Failure(cause) =>
          // A normal interrupted child cannot turn a publication error into caller cancellation.
          val cleanup =
            if cause.isInterrupted then ownedCancellationCleanup(joined) else Cause.empty
          ZIO.refailCause(if cleanup.isEmpty then cause else cause ++ cleanup)

    final private case class StoredView(
        terminals: Chunk[SubagentTerminalRecord],
        observations: Chunk[SubagentTaskObservation],
        unresolved: Set[SubagentTaskId]
    )

    private def storedView(
        store: SubagentTerminalStore,
        selected: Option[SubagentTaskId] = None
    ): IO[AgentError, StoredView] =
      store.taskRecordSnapshot.flatMap:
        case None =>
          val records = selected match
            case None     => store.list
            case Some(id) => store.read(id).map(Chunk.fromIterable(_))
          records.map(values => StoredView(values, values.map(fromStored), Set.empty))
        case Some(records) =>
          ZIO.succeed(
            StoredView(
              records.flatMap(_.terminal),
              records.map(record =>
                SubagentTaskObservation(
                  record.id,
                  record.state match
                    case SubagentTaskRecordState.Pending =>
                      SubagentTaskObservationState.Stored(SubagentStoredTaskState.Pending)
                    case SubagentTaskRecordState.Running =>
                      SubagentTaskObservationState.Stored(SubagentStoredTaskState.Running)
                    case SubagentTaskRecordState.Terminal(outcome, _) =>
                      SubagentTaskObservationState.Terminal(outcome)
                )
              ),
              records.filter(_.terminal.isEmpty).map(_.id).toSet
            )
          )

    override private[subagent] def captureOutput(
        id: SubagentTaskId
    ): IO[AgentError, SubagentTaskOutputTool.Capture] =
      state.get.flatMap: current =>
        current.entries.find(_.id == id) match
          case Some(entry) =>
            ZIO.succeed(SubagentTaskOutputTool.Capture.Local(capturedEntry(entry)))
          case None =>
            persistence match
              case None =>
                ZIO.fail(
                  AgentError.InvalidConfiguration(
                    "No task with this ID exists in the selected registry"
                  )
                )
              case Some(binding) =>
                storedView(binding.store).flatMap(view =>
                  if view.observations.exists(_.id == id) then
                    ZIO.succeed(SubagentTaskOutputTool.Capture.Stored(binding.store, id))
                  else
                    ZIO.fail(
                      AgentError.InvalidConfiguration(
                        "No task with this ID exists in the selected registry"
                      )
                    )
                )

    override private[subagent] def waitWorkState: IO[AgentError, SubagentWaitWorkState] =
      for
        entries <- state.get.map(_.entries)
        stored <- persistence match
          case None          => ZIO.succeed(StoredView(Chunk.empty, Chunk.empty, Set.empty))
          case Some(binding) => storedView(binding.store)
        sampled <- ZIO.foreach(entries)(entry => entry.task.poll.map(entry -> _))
        _ <- ZIO.foreachDiscard(entries.flatMap(_.publisher))(_.poll.flatMap:
          case Some(Exit.Failure(cause)) => ZIO.refailCause(cause)
          case _                         => ZIO.unit)
      yield
        if sampled.exists((entry, exit) =>
            exit.isEmpty && !stored.terminals.exists(_.id == entry.id)
          )
        then SubagentWaitWorkState.Running
        else if stored.unresolved.exists(id => !entries.exists(_.id == id)) then
          SubagentWaitWorkState.StoredUnresolved
        else if entries.isEmpty && stored.observations.isEmpty then SubagentWaitWorkState.Empty
        else SubagentWaitWorkState.AllTerminal

    override private[subagent] def captureWait(
        selection: SubagentTaskSelection
    ): IO[AgentError, Chunk[SubagentCapturedWait]] =
      for
        entries <- state.get.map(_.entries)
        initial <- ZIO.foreach(entries)(entry => entry.task.poll.map(entry -> _))
        _ <-
          if selection == SubagentTaskSelection.CurrentRunning &&
            initial.count(_._2.isEmpty) > SubagentTaskSelection.MaximumTasks
          then
            ZIO.fail(AgentError.InvalidConfiguration("Current-running wait set exceeds 128 tasks"))
          else ZIO.unit
        captured <- selection match
          case SubagentTaskSelection.CurrentRunning =>
            ZIO.succeed(initial.collect { case (entry, None) => capturedEntry(entry) })
          case selected: SubagentTaskSelection.Selected =>
            val ids = selected.ids
            val storedRecords = persistence match
              case Some(binding) => storedView(binding.store)
              case None          => ZIO.succeed(StoredView(Chunk.empty, Chunk.empty, Set.empty))
            storedRecords.flatMap: view =>
              val stored = view.terminals
              val nonlocal =
                ids.filter(id => view.unresolved.contains(id) && !entries.exists(_.id == id))
              val missing =
                ids.filterNot(id => entries.exists(_.id == id) || stored.exists(_.id == id))
              if nonlocal.nonEmpty then
                ZIO.fail(
                  AgentError.InvalidConfiguration(
                    s"Selected stored tasks are not locally waitable: ${nonlocal.map(_.value).mkString(", ")}"
                  )
                )
              else if missing.nonEmpty then
                ZIO.fail(
                  AgentError.InvalidConfiguration(
                    s"Unknown selected task IDs: ${missing.map(_.value).mkString(", ")}"
                  )
                )
              else
                ZIO.foreach(ids): id =>
                  entries.find(_.id == id) match
                    case Some(entry) => ZIO.succeed(capturedEntry(entry))
                    case None =>
                      ZIO
                        .fromOption(stored.find(_.id == id))
                        .orElseFail(
                          AgentError.StorageError(
                            "Selected terminal observation disappeared from captured membership"
                          )
                        )
                        .map: record =>
                          new SubagentCapturedWait:
                            def checkedMetadata: IO[AgentError, Option[SubagentTaskMetadata]] =
                              persistence match
                                case Some(binding) => binding.store.markChecked(record.id)
                                case None          => ZIO.none
                            val id: SubagentTaskId                            = record.id
                            def await: IO[AgentError, SubagentTerminalRecord] = ZIO.succeed(record)
                            def readiness: IO[AgentError, SubagentCapturedReadiness] =
                              ZIO.succeed(SubagentCapturedReadiness.Ready(record))
      yield captured

    private def capturedEntry(entry: Entry): SubagentCapturedWait = new SubagentCapturedWait:
      val id: SubagentTaskId = entry.id

      def checkedMetadata: IO[AgentError, Option[SubagentTaskMetadata]] =
        persistence match
          case Some(binding) => binding.store.markChecked(id)
          case None =>
            ZIO.foreach(entry.metadata)(metadata =>
              Clock.instant.flatMap(at => metadata.updateAndGet(_.copy(lastCheckedAt = at)))
            )

      private def completed(
          exit: Exit[AgentError, AgentResult]
      ): IO[AgentError, SubagentTerminalRecord] =
        persistence match
          case None =>
            ZIO.succeed(
              SubagentTerminalRecord(id, SubagentTerminalOutcome.fromExit(exit), workerId)
            )
          case Some(binding) =>
            ZIO
              .fromOption(entry.publisher)
              .orElseFail(
                AgentError.InvalidConfiguration("Durable task has no publication owner")
              )
              .flatMap(_.await.flatMap(receipt => receipt)) *> binding.store
              .read(id)
              .someOrFail(
                AgentError.StorageError(
                  "Successful publication has no visible terminal observation"
                )
              )

      def await: IO[AgentError, SubagentTerminalRecord] =
        poll.flatMap:
          case Some(observation) => ZIO.succeed(observation)
          case None              => entry.task.await.flatMap(completed)

      private def completedReadiness(
          exit: Exit[AgentError, AgentResult]
      ): IO[AgentError, SubagentCapturedReadiness] =
        persistence match
          case None => completed(exit).map(SubagentCapturedReadiness.Ready(_))
          case Some(_) =>
            ZIO
              .fromOption(entry.publisher)
              .orElseFail(AgentError.InvalidConfiguration("Durable task has no publication owner"))
              .flatMap(_.poll)
              .flatMap:
                case Some(Exit.Success(_)) =>
                  completed(exit).map(SubagentCapturedReadiness.Ready(_))
                case Some(Exit.Failure(cause)) => ZIO.refailCause(cause)
                case None => ZIO.succeed(SubagentCapturedReadiness.PublicationPending)

      def readiness: IO[AgentError, SubagentCapturedReadiness] =
        entry.task.poll.flatMap:
          case Some(exit) => completedReadiness(exit)
          case None =>
            persistence match
              case None => ZIO.succeed(SubagentCapturedReadiness.Running)
              case Some(binding) =>
                ZIO.foreachDiscard(entry.publisher)(_.poll.flatMap:
                  case Some(Exit.Failure(cause)) => ZIO.refailCause(cause)
                  case _                         => ZIO.unit) *> binding.store
                  .read(id)
                  .flatMap: stored =>
                    entry.task.poll.flatMap:
                      case Some(exit) => completedReadiness(exit)
                      case None =>
                        ZIO.succeed(
                          stored.fold[SubagentCapturedReadiness](SubagentCapturedReadiness.Running)(
                            SubagentCapturedReadiness.Ready(_)
                          )
                        )

    override def recoverStaleTasks(
        policy: SubagentTaskRecovery.Policy,
        claimGate: SubagentTaskRecovery.Gate,
        authority: SubagentTaskRecovery.Authority
    ): ZIO[Scope, AgentError, SubagentTaskRecovery.Result] =
      persistence match
        case None => super.recoverStaleTasks(policy, claimGate, authority)
        case Some(binding) =>
          def closed: IO[AgentError, Nothing] =
            ZIO.fail(AgentError.InvalidConfiguration("A closed registry cannot reconcile tasks"))
          def ensureOpen: IO[AgentError, Unit] = state.get.flatMap:
            case State.Open(_)   => ZIO.unit
            case State.Closed(_) => closed

          def reconcile(
              record: SubagentTaskRecord,
              at: java.time.Instant
          ): IO[AgentError, SubagentTaskRecovery.Receipt] =
            if record.origin != SubagentExecutionOrigin.Local then
              ZIO.succeed(
                SubagentTaskRecovery.Receipt(record.id, SubagentTaskRecovery.Disposition.Remote)
              )
            else
              state.get.flatMap:
                case State.Closed(_) => closed
                case State.Open(entries) if entries.exists(_.id == record.id) =>
                  ZIO.succeed(
                    SubagentTaskRecovery.Receipt(
                      record.id,
                      SubagentTaskRecovery.Disposition.RetainedLocal
                    )
                  )
                case State.Open(_) =>
                  ZIO.scoped:
                    ZIO
                      .suspendSucceed(authority.acquire(this, record))
                      .flatMap:
                        case SubagentTaskRecovery.Decision.Local(permit) =>
                          if permit.expected != record then
                            ZIO.fail(
                              AgentError.InvalidConfiguration(
                                "Recovery authority returned a different task record"
                              )
                            )
                          else
                            // Keep every retained owner protected, including paused tasks and completed
                            // publisher/notifier receipts. Host acquisition and cleanup stay outside gate.
                            gate.withPermit:
                              ensureOpen *> state.get.flatMap: current =>
                                if current.entries.exists(_.id == record.id) then
                                  ZIO.succeed(
                                    SubagentTaskRecovery.Receipt(
                                      record.id,
                                      SubagentTaskRecovery.Disposition.RetainedLocal
                                    )
                                  )
                                else
                                  binding.store
                                    .reconcileAbandoned(permit, at)
                                    .map: result =>
                                      SubagentTaskRecovery.Receipt(
                                        record.id,
                                        SubagentTaskRecovery.Disposition.Transitioned(result)
                                      )
                        case SubagentTaskRecovery.Decision.Remote =>
                          ZIO.succeed(
                            SubagentTaskRecovery
                              .Receipt(record.id, SubagentTaskRecovery.Disposition.Remote)
                          )
                        case SubagentTaskRecovery.Decision.OwnerPresent =>
                          ZIO.succeed(
                            SubagentTaskRecovery
                              .Receipt(record.id, SubagentTaskRecovery.Disposition.OwnerPresent)
                          )
                        case SubagentTaskRecovery.Decision.Unknown =>
                          ZIO.succeed(
                            SubagentTaskRecovery
                              .Receipt(record.id, SubagentTaskRecovery.Disposition.Unknown)
                          )
          ensureOpen *> claimGate
            .claim(policy)
            .flatMap:
              case SubagentTaskRecovery.Claim.Throttled =>
                ZIO.succeed(SubagentTaskRecovery.Result.Throttled)
              case SubagentTaskRecovery.Claim.Admitted =>
                for
                  at         <- Clock.instant
                  records    <- binding.store.taskRecords
                  candidates <- SubagentTaskRecovery.candidates(records, policy, at)
                  receipts   <- ZIO.foreach(candidates)(record => reconcile(record, at))
                yield SubagentTaskRecovery.Result.Swept(receipts)

    override def refreshCurrentOwner: IO[AgentError, Chunk[SubagentTaskHeartbeatResult]] =
      persistence match
        case None => super.refreshCurrentOwner
        case Some(binding) =>
          def attempt(entry: Entry): IO[AgentError, Option[SubagentTaskHeartbeatResult]] =
            entry.task.poll.flatMap:
              case Some(_) => ZIO.none
              case None =>
                entry.admissionIdentity match
                  case Some(identity) => binding.store.refreshHeartbeat(identity).map(Some(_))
                  case None =>
                    ZIO.fail(
                      AgentError.InvalidConfiguration(
                        "The retained task has no confirmed durable admission for heartbeat"
                      )
                    )

          state.get.flatMap:
            case State.Closed(_) =>
              ZIO.fail(
                AgentError.InvalidConfiguration(
                  "A closed registry cannot refresh its current owner"
                )
              )
            case State.Open(entries) =>
              ZIO.uninterruptibleMask: restore =>
                // Only the transition between attempts is masked. Cancellation/defects stop the
                // traversal and retain earlier failures; no task or publisher is cancelled.
                def refresh(
                    remaining: List[Entry],
                    receipts: Chunk[SubagentTaskHeartbeatResult],
                    failures: Cause[AgentError]
                ): IO[AgentError, Chunk[SubagentTaskHeartbeatResult]] =
                  remaining match
                    case Nil =>
                      if failures.isEmpty then ZIO.succeed(receipts) else ZIO.refailCause(failures)
                    case entry :: tail =>
                      restore(attempt(entry)).exit.flatMap:
                        case Exit.Success(receipt) =>
                          refresh(tail, receipts ++ Chunk.fromIterable(receipt), failures)
                        case Exit.Failure(cause) if !cause.isInterrupted && cause.defects.isEmpty =>
                          refresh(tail, receipts, failures && cause)
                        case Exit.Failure(cause) =>
                          ZIO.refailCause(if failures.isEmpty then cause else failures ++ cause)
                refresh(entries.toList, Chunk.empty, Cause.empty)

    override def observe(id: SubagentTaskId): IO[AgentError, Option[SubagentTaskObservation]] =
      persistence match
        case None => super.observe(id)
        case Some(binding) =>
          for
            entry  <- lookup(id)
            live   <- ZIO.foreach(entry)(snapshot)
            stored <- storedView(binding.store, Some(id))
            observed <- (entry, live) match
              case (Some(value), Some(current)) =>
                observeEntry(value, current, stored.terminals.find(_.id == id), binding.store)
                  .map(Some(_))
              case _ => ZIO.succeed(stored.observations.find(_.id == id))
          yield observed

    override def observations: IO[AgentError, Chunk[SubagentTaskObservation]] =
      persistence match
        case None => super.observations
        case Some(binding) =>
          for
            entries <- state.get.map(_.entries)
            live    <- ZIO.foreach(entries)(entry => snapshot(entry).map(entry -> _))
            stored  <- storedView(binding.store)
            observed <- ZIO.foreach(live): (entry, current) =>
              observeEntry(entry, current, stored.terminals.find(_.id == entry.id), binding.store)
            localIds = entries.map(_.id).toSet
          yield observed ++ stored.observations.filterNot(record => localIds.contains(record.id))

    override private[subagent] def awaitTerminalPublication(
        id: SubagentTaskId
    ): IO[AgentError, Unit] =
      persistence match
        case None => ZIO.unit
        case Some(_) =>
          publication(id).flatMap:
            case Some(_) => ZIO.unit
            case None =>
              ZIO.fail(AgentError.InvalidConfiguration("Durable task has no retained publication"))

    override def publication(
        id: SubagentTaskId
    ): IO[AgentError, Option[SubagentTerminalPublication]] =
      persistence match
        case None => super.publication(id)
        case Some(_) =>
          lookup(id).flatMap(entry =>
            ZIO.foreach(entry.flatMap(_.publisher))(publisher =>
              publisher.await.flatMap(exit => exit)
            )
          )

    override def flushPublications: IO[AgentError, Chunk[SubagentTaskPublication]] =
      persistence match
        case None => super.flushPublications
        case Some(_) =>
          for
            entries <- state.get.map(_.entries)
            outcomes <- ZIO.foreachPar(
              entries.flatMap(entry => entry.publisher.map(entry.id -> _))
            ): (id, publisher) =>
              publisher.await.map(id -> _)
            combined = outcomes.foldLeft[Exit[AgentError, Chunk[SubagentTaskPublication]]](
              Exit.succeed(Chunk.empty)
            ):
              case (accumulated, (id, outcome)) =>
                accumulated.zipWith(outcome)(
                  (records, receipt) => records :+ SubagentTaskPublication(id, receipt),
                  _ ++ _
                )
            receipts <- combined
          yield receipts

    private def notificationObservationAllowed: IO[AgentError, Unit] =
      callbackContext.get.flatMap:
        case CallbackContext.Outside => ZIO.unit
        case CallbackContext.Notifying =>
          ZIO.fail(
            AgentError.InvalidConfiguration(
              "Completion callbacks cannot await this registry's notification receipts"
            )
          )

    override def completionNotification(
        id: SubagentTaskId
    ): IO[AgentError, Option[SubagentCompletionReceipt]] =
      notificationObservationAllowed *> (persistence match
        case None => super.completionNotification(id)
        case Some(_) =>
          lookup(id).flatMap(entry =>
            ZIO.foreach(entry.flatMap(_.notification))(_.await.flatMap(exit => exit))
          ))

    override def flushCompletionNotifications
        : IO[AgentError, Chunk[SubagentTaskCompletionReceipt]] =
      notificationObservationAllowed *> (persistence match
        case None => super.flushCompletionNotifications
        case Some(_) =>
          for
            entries <- state.get.map(_.entries)
            outcomes <- ZIO.foreachPar(
              entries.flatMap(entry => entry.notification.map(entry.id -> _))
            ): (id, notification) =>
              notification.await.map(id -> _)
            combined = outcomes.foldLeft[Exit[AgentError, Chunk[SubagentTaskCompletionReceipt]]](
              Exit.succeed(Chunk.empty)
            ):
              case (accumulated, (id, outcome)) =>
                accumulated.zipWith(outcome)(
                  (receipts, value) => receipts :+ SubagentTaskCompletionReceipt(id, value),
                  _ ++ _
                )
            receipts <- combined
          yield receipts)

    private def fromStored(record: SubagentTerminalRecord): SubagentTaskObservation =
      SubagentTaskObservation(record.id, SubagentTaskObservationState.Terminal(record.outcome))

    private def observeEntry(
        entry: Entry,
        current: SubagentTaskSnapshot,
        stored: Option[SubagentTerminalRecord],
        store: SubagentTerminalStore
    ): IO[AgentError, SubagentTaskObservation] =
      stored match
        case Some(record) =>
          entry.task.poll.flatMap:
            case Some(_) =>
              ZIO
                .fromOption(entry.publisher)
                .orElseFail(
                  AgentError.InvalidConfiguration("Durable task has no publication owner")
                )
                .flatMap(_.await.flatMap(exit => exit))
                .as(fromStored(record))
            case None =>
              ZIO
                .foreachDiscard(entry.publisher)(publisher =>
                  publisher.poll.flatMap:
                    case Some(Exit.Failure(cause)) => ZIO.refailCause(cause)
                    case _                         => ZIO.unit
                )
                .as(fromStored(record))
        case None =>
          current.state match
            case SubagentTaskState.Running =>
              ZIO.succeed(SubagentTaskObservation.fromSnapshot(current))
            case SubagentTaskState.Completed(_) =>
              for
                publisher <- ZIO
                  .fromOption(entry.publisher)
                  .orElseFail(
                    AgentError.InvalidConfiguration("Durable task has no publication owner")
                  )
                _ <- publisher.await.flatMap(exit => exit)
                record <- store
                  .read(entry.id)
                  .someOrFail(
                    AgentError
                      .StorageError("Successful publication has no visible terminal observation")
                  )
              yield fromStored(record)

    override def durablePendingDeliveries: IO[AgentError, Chunk[SubagentTerminalRecord]] =
      persistence match
        case None => super.durablePendingDeliveries
        case Some(binding) =>
          for
            entries <- state.get.map(_.entries)
            _       <- ZIO.foreachDiscard(entries)(awaitCompletedPublication)
            records <- binding.store.pending
            _ <- ZIO.foreachDiscard(records): record =>
              lookup(record.id).flatMap(entry =>
                ZIO.foreachDiscard(entry)(awaitCompletedPublication)
              )
          yield records

    override def terminalAcknowledgement(
        id: SubagentTaskId
    ): IO[AgentError, Option[SubagentTerminalAcknowledgement]] =
      persistence match
        case None => super.terminalAcknowledgement(id)
        case Some(binding) =>
          for
            _     <- awaitRetainedCompletion(id)
            stamp <- binding.store.acknowledgement(id)
            _     <- ZIO.when(stamp.nonEmpty)(awaitRetainedCompletion(id))
          yield stamp

    override def acknowledgeTerminal(
        id: SubagentTaskId
    ): IO[AgentError, SubagentTerminalAcknowledgeResult] =
      persistence match
        case None => super.acknowledgeTerminal(id)
        case Some(binding) =>
          for
            _     <- awaitRetainedCompletion(id)
            stamp <- binding.store.acknowledgement(id)
            result <- stamp match
              case None => ZIO.succeed(SubagentTerminalAcknowledgeResult.Missing)
              case Some(_) =>
                awaitRetainedCompletion(id) *> binding.store.acknowledge(id)
          yield result

    private def awaitRetainedCompletion(id: SubagentTaskId): IO[AgentError, Unit] =
      lookup(id).flatMap(entry => ZIO.foreachDiscard(entry)(awaitCompletedPublication))

    private def awaitCompletedPublication(entry: Entry): IO[AgentError, Unit] =
      entry.task.poll.flatMap:
        case None    => ZIO.unit
        case Some(_) => awaitTerminalPublication(entry.id)

    private def requireLiveDelivery: IO[AgentError.InvalidConfiguration, Unit] =
      ZIO
        .fail(AgentError.InvalidConfiguration("Durable delivery acknowledgement is not supported"))
        .when(persistence.nonEmpty)
        .unit

    override def pendingDeliveries
        : IO[AgentError.InvalidConfiguration, Chunk[SubagentTaskDelivery]] =
      requireLiveDelivery *> state.get.flatMap(current =>
        ZIO
          .foreach(current.entries.filter(_.delivery == DeliveryState.Pending))(entry =>
            entry.task.poll.map(_.map(SubagentTaskDelivery(entry.id, _)))
          )
          .map(_.flatten)
      )

    override def acknowledge(
        id: SubagentTaskId
    ): IO[AgentError.InvalidConfiguration, SubagentDeliveryAcknowledgement] =
      requireLiveDelivery *> gate.withPermit:
        lookup(id).flatMap:
          case None => ZIO.succeed(SubagentDeliveryAcknowledgement.Missing)
          case Some(entry) if entry.delivery == DeliveryState.Acknowledged =>
            ZIO.succeed(SubagentDeliveryAcknowledgement.AlreadyAcknowledged)
          case Some(entry) =>
            entry.task.poll.flatMap:
              case None => ZIO.succeed(SubagentDeliveryAcknowledgement.Running)
              case Some(_) =>
                state.update(_.acknowledge(id)).as(SubagentDeliveryAcknowledgement.Acknowledged)

    def forgetCompleted(id: SubagentTaskId): UIO[SubagentTaskRemoval] =
      gate.withPermit:
        lookup(id).flatMap:
          case None => ZIO.succeed(SubagentTaskRemoval.Missing)
          case Some(entry) =>
            entry.task.poll.flatMap:
              case None => ZIO.succeed(SubagentTaskRemoval.Running)
              case Some(_) =>
                ZIO
                  .foreach(entry.publisher)(_.poll)
                  .flatMap:
                    case Some(None) | Some(Some(Exit.Failure(_))) =>
                      ZIO.succeed(SubagentTaskRemoval.Unpublished)
                    case _ =>
                      ZIO
                        .foreach(entry.notification)(_.poll)
                        .flatMap:
                          case Some(None) | Some(Some(Exit.Failure(_))) =>
                            ZIO.succeed(SubagentTaskRemoval.Unnotified)
                          case _ =>
                            ZIO
                              .foreach(entry.milestones)(_.poll)
                              .flatMap:
                                case Some(None) | Some(Some(Exit.Failure(_))) =>
                                  ZIO.succeed(SubagentTaskRemoval.MilestonesPending)
                                case _ => state.update(_.remove(id)).as(SubagentTaskRemoval.Removed)

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
        for
          entries <- gate.withPermit(
            state.modify(current => (current.entries, State.Closed(current.entries)))
          )
          workers <- ZIO.foreachParDiscard(entries)(_.task.cancel).exit
          _       <- ZIO.foreachParDiscard(entries.flatMap(_.publisher))(_.await)
          _       <- ZIO.foreachParDiscard(entries.flatMap(_.notification))(_.await)
          _       <- ZIO.foreachParDiscard(entries.flatMap(_.milestones))(_.await)
          owned   <- owner.close(Exit.unit).exit
          _ <- workers.zipWith(owned)(
            (_, _) => (),
            (first: Cause[Nothing], second: Cause[Nothing]) => first ++ second
          )
        yield ()
