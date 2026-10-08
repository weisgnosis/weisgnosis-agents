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

/** A retained, fully joined outcome; delivery does not transfer execution ownership. */
final case class SubagentTaskDelivery(id: SubagentTaskId, exit: Exit[AgentError, AgentResult])

enum SubagentDeliveryAcknowledgement:
  case Acknowledged, AlreadyAcknowledged, Running, Missing

enum SubagentTaskSubmission:
  case Accepted(id: SubagentTaskId)
  case Capacity, Closed, IdentityCollision

enum SubagentTaskRemoval:
  case Removed, Missing, Running, Unpublished

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

  /** Effectful observation preserves live-only custom registry behavior by default. */
  def observe(id: SubagentTaskId): IO[AgentError, Option[SubagentTaskObservation]] =
    inspect(id).map(_.map(SubagentTaskObservation.fromSnapshot))

  def observations: IO[AgentError, Chunk[SubagentTaskObservation]] =
    list.map(_.map(SubagentTaskObservation.fromSnapshot))

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
  private enum DeliveryState:
    case Pending, Acknowledged

  final private case class Entry(
      id: SubagentTaskId,
      task: SupervisedTask[AgentResult],
      delivery: DeliveryState = DeliveryState.Pending,
      publisher: Option[Fiber.Runtime[AgentError, SubagentTerminalPublication]] = None
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
      maxTracked: Int = 256
  ): ZIO[Scope, AgentError, SubagentTaskRegistry] =
    openWith((input, id, events) => factory.spawn(parent, input, id, events), maxTracked)

  /** Opt-in automatic terminal publication and restored read-only observations. */
  def openDurable(
      parent: SubagentSpawner[AgentResult],
      factory: SubagentFactory,
      terminalStore: SubagentTerminalStore,
      maxTracked: Int = 256
  ): ZIO[Scope, AgentError, SubagentTaskRegistry] =
    openWith(
      (input, id, events) => factory.spawn(parent, input, id, events),
      maxTracked,
      Some(terminalStore)
    )

  private[subagent] def openWith(
      launchTask: (Msg, SubagentTaskId, Option[ToolEventPort]) => ZIO[
        Scope,
        AgentError,
        TaskAdmission[AgentResult]
      ],
      maxTracked: Int,
      terminalStore: Option[SubagentTerminalStore] = None
  ): ZIO[Scope, AgentError, SubagentTaskRegistry] =
    openInput[Msg](identity, launchTask, maxTracked, terminalStore)

  private[subagent] def openInput[Input](
      fromMessage: Msg => Input,
      launchTask: (Input, SubagentTaskId, Option[ToolEventPort]) => ZIO[
        Scope,
        AgentError,
        TaskAdmission[AgentResult]
      ],
      maxTracked: Int,
      terminalStore: Option[SubagentTerminalStore] = None
  ): ZIO[Scope, AgentError, Live[Input]] =
    ZIO.uninterruptible:
      for
        _ <- ZIO
          .fail(AgentError.InvalidConfiguration("Subagent task capacity must be positive"))
          .when(maxTracked <= 0)
        gate        <- Semaphore.make(1)
        state       <- Ref.make[State](State.Open(Chunk.empty))
        persistence <- ZIO.foreach(terminalStore)(store => Scope.make.map(Persistence(store, _)))
        registry = new Live(fromMessage, launchTask, maxTracked, gate, state, persistence)
        _ <- ZIO.addFinalizer(registry.close)
      yield registry

  final private[subagent] class Live[Input] private[SubagentTaskRegistry] (
      fromMessage: Msg => Input,
      launchTask: (Input, SubagentTaskId, Option[ToolEventPort]) => ZIO[
        Scope,
        AgentError,
        TaskAdmission[AgentResult]
      ],
      maxTracked: Int,
      gate: Semaphore,
      state: Ref[State],
      persistence: Option[Persistence]
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
      ZIO.uninterruptibleMask: restore =>
        Scope.make.flatMap: lease =>
          // Register the caller lease outside admission: a closed caller may synchronously join cleanup.
          restore(gate.withPermit(lease.extend(launch(input, events))))
            .onExit:
              case Exit.Success(SubagentTaskSubmission.Accepted(_)) =>
                ZIO.addFinalizer(lease.close(Exit.unit))
              case Exit.Success(_)          => lease.close(Exit.unit)
              case failed @ Exit.Failure(_) => lease.close(failed)

    private def launch(
        input: Input,
        events: Option[ToolEventPort]
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
                  ZIO
                    .suspendSucceed(launchTask(input, id, events))
                    .flatMap:
                      case TaskAdmission.Closed => ZIO.succeed(SubagentTaskSubmission.Closed)
                      case TaskAdmission.Accepted(task) =>
                        for
                          publisher <- ZIO.foreach(persistence)(binding =>
                            binding.store
                              .publishJoined(id, task)
                              .interruptible
                              .forkIn(binding.owner)
                          )
                          _ <- state.set(
                            State.Open(entries :+ Entry(id, task, publisher = publisher))
                          )
                        yield SubagentTaskSubmission.Accepted(id)
            yield admission

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

    override def observe(id: SubagentTaskId): IO[AgentError, Option[SubagentTaskObservation]] =
      persistence match
        case None => super.observe(id)
        case Some(binding) =>
          for
            entry  <- lookup(id)
            live   <- ZIO.foreach(entry)(snapshot)
            stored <- binding.store.read(id)
            observed <- (entry, live) match
              case (Some(value), Some(current)) =>
                observeEntry(value, current, stored, binding.store).map(Some(_))
              case _ => ZIO.succeed(stored.map(fromStored))
          yield observed

    override def observations: IO[AgentError, Chunk[SubagentTaskObservation]] =
      persistence match
        case None => super.observations
        case Some(binding) =>
          for
            entries <- state.get.map(_.entries)
            live    <- ZIO.foreach(entries)(entry => snapshot(entry).map(entry -> _))
            stored  <- binding.store.list
            observed <- ZIO.foreach(live): (entry, current) =>
              observeEntry(entry, current, stored.find(_.id == entry.id), binding.store)
            localIds = entries.map(_.id).toSet
          yield observed ++ stored.filterNot(record => localIds.contains(record.id)).map(fromStored)

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
          owner   <- ZIO.foreachDiscard(persistence)(_.owner.close(Exit.unit)).exit
          _ <- workers.zipWith(owner)(
            (_, _) => (),
            (first: Cause[Nothing], second: Cause[Nothing]) => first ++ second
          )
        yield ()
