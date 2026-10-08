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

import com.weisgnosis.agents.core.agent.{AgentConfig, AgentResult}
import com.weisgnosis.agents.core.client.{LlmClient, LlmConfig, TurnOutput}
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.model.Msg
import com.weisgnosis.agents.core.tool.{Tool, ToolAuthorizer, ToolDef, Toolkit}
import com.weisgnosis.agents.harness.storage.*
import com.weisgnosis.agents.harness.eviction.WorkspaceTrust
import com.weisgnosis.agents.harness.memory.MemoryTestSupport.temporaryDirectory
import java.nio.file.Files
import java.nio.charset.StandardCharsets
import java.util.UUID
import zio.*
import zio.json.ast.Json
import zio.test.*

object SubagentTaskRegistrySpec extends ZIOSpecDefault:
  private val input: Msg = Msg.user("work")

  private enum HeartbeatMode:
    case Normal, FirstFails, Defect, Interrupted, Cancel

  private enum DurableAdmission:
    case Ready, LostReceipt, CallerCancelled, StartFailed, Closed, HostClosed

  private enum CancellationAttempt:
    case Failed, Uncertain, CallerCancelled, BeforeStart, AlreadyTerminal, StoredOnly,
      IdentityMismatch, ForgedNoIntent, ForgedTerminalRequested, ForgedNonterminalAlreadyTerminal

  private enum CancellationJoin:
    case Normal, CallerInterrupted

  private enum RejectedAdmission:
    case Uncertain, Collision, CallerCancelled

  private enum WaitMode:
    case Attached, Promoted

  private def owner: ZIO[Scope, Nothing, Scope.Closeable] =
    ZIO.acquireRelease(Scope.make)(_.close(Exit.unit))

  private def factory(
      register: SubagentSpawner[AgentResult] => ZIO[Scope, AgentError, Toolkit],
      client: SubagentSpawner[AgentResult] => ZIO[Scope, AgentError, LlmClient] = _ =>
        LlmClient.mock(Chunk(TurnOutput.FinalText(Msg.assistant("done"))))
  ): IO[AgentError, SubagentFactory] =
    ZIO.fromEither(
      SubagentFactory.make(
        AgentConfig(name = "registry-child"),
        LlmConfig("registry-model"),
        Toolkit.empty,
        SubagentToolBoundary(denied = Set("forbidden")),
        SubagentToolBoundary(),
        register,
        client,
        ToolAuthorizer.allow
      )
    )

  private def accepted(
      submission: ZIO[Scope, AgentError, SubagentTaskSubmission]
  ): ZIO[Scope, AgentError, SubagentTaskId] =
    submission.flatMap:
      case SubagentTaskSubmission.Accepted(id) => ZIO.succeed(id)
      case rejected => ZIO.fail(AgentError.InvalidConfiguration(s"Unexpected rejection: $rejected"))

  private def completed(
      snapshot: Option[SubagentTaskSnapshot]
  ): Option[Exit[AgentError, AgentResult]] =
    snapshot.flatMap:
      case SubagentTaskSnapshot(_, SubagentTaskState.Completed(exit)) => Some(exit)
      case SubagentTaskSnapshot(_, SubagentTaskState.Running)         => None

  private def durableAdmission(
      mode: DurableAdmission
  ): ZIO[TestEnvironment & Scope, AgentError, TestResult] =
    ZIO.scoped:
      for
        backend        <- SubagentTerminalStoreSpec.fixture
        address        <- ZIO.fromEither(StorageAddress.from(Chunk("admission", "public"), "tasks"))
        admitting      <- Promise.make[Nothing, Unit]
        allowAdmission <- Promise.make[Nothing, Unit]
        starting       <- Promise.make[Nothing, Unit]
        allowStart     <- Promise.make[Nothing, Unit]
        preparing      <- Promise.make[Nothing, Unit]
        allowWork      <- Promise.make[Nothing, Unit]
        builds         <- Ref.make(0)
        writes         <- Ref.make(0)
        failure      = AgentError.StorageError("Admission receipt lost")
        startFailure = AgentError.StorageError("Running transition failed")
        controlled = new VersionedJsonStore:
          def read(target: StorageAddress): IO[AgentError, Option[VersionedJson]] =
            backend.store.read(target)
          def compareAndSet(
              target: StorageAddress,
              expected: ExpectedRevision,
              value: Json
          ): IO[AgentError, ConditionalWrite] =
            writes
              .updateAndGet(_ + 1)
              .flatMap:
                case 1 =>
                  backend.store
                    .compareAndSet(target, expected, value)
                    .flatMap(receipt =>
                      admitting.succeed(()) *> allowAdmission.await *>
                        (if mode == DurableAdmission.LostReceipt then ZIO.fail(failure)
                         else ZIO.succeed(receipt))
                    )
                case 2 =>
                  if mode == DurableAdmission.HostClosed then
                    backend.store.compareAndSet(target, expected, value)
                  else
                    starting.succeed(()) *> allowStart.await *>
                      (if mode == DurableAdmission.StartFailed then ZIO.fail(startFailure)
                       else backend.store.compareAndSet(target, expected, value))
                case _ => backend.store.compareAndSet(target, expected, value)
        store  = SubagentTerminalStore.make(controlled, address)
        reader = SubagentTerminalStore.make(backend.store, address)
        host       <- owner
        supervisor <- host.extend(TaskSupervisor.open[AgentResult])
        parent     <- SubagentSpawner.root(supervisor)
        configured <- factory(_ =>
          builds.update(_ + 1) *> preparing.succeed(()) *> allowWork.await.as(Toolkit.empty)
        )
        registry <- SubagentTaskRegistry.openDurable(parent, configured, store, 4)
        _ <- ZIO.addFinalizer(
          allowAdmission.succeed(()) *> allowStart.succeed(()) *> allowWork.succeed(()).unit
        )
        _ <- if mode == DurableAdmission.Closed then host.close(Exit.unit) else ZIO.unit
        outcome <-
          if mode == DurableAdmission.Closed then
            registry.submit(input).exit.map(result => (result, assertTrue(true)))
          else
            for
              submitting <- ZIO.uninterruptibleMask: restore =>
                for
                  fiber <- restore(registry.submit(input)).forkScoped
                  _ <- ZIO.addFinalizer(
                    allowAdmission.succeed(()) *> allowStart
                      .succeed(()) *> allowWork.succeed(()).unit
                  )
                yield fiber
              _       <- admitting.await
              pending <- reader.taskRecords
              before  <- builds.get
              _ <- if mode == DurableAdmission.HostClosed then host.close(Exit.unit) else ZIO.unit
              result <-
                if mode == DurableAdmission.CallerCancelled then submitting.interrupt
                else allowAdmission.succeed(()) *> submitting.await
              startCheck <- result match
                case Exit.Success(SubagentTaskSubmission.Accepted(id))
                    if mode == DurableAdmission.HostClosed =>
                  for
                    joined   <- registry.await(id)
                    _        <- registry.publication(id)
                    terminal <- reader.taskRecord(id)
                  yield assertTrue(
                    joined.exists(_.isInterrupted),
                    terminal.exists(
                      _.terminal.exists(
                        _.outcome == SubagentTerminalOutcome
                          .Interrupted(Chunk.empty, SubagentTaskDefects.Absent)
                      )
                    )
                  )
                case Exit.Success(SubagentTaskSubmission.Accepted(id)) =>
                  for
                    _           <- starting.await
                    held        <- reader.taskRecord(id)
                    stillBefore <- builds.get
                    _           <- allowStart.succeed(())
                    active <-
                      if mode == DurableAdmission.Ready then
                        preparing.await *> reader.taskRecord(id)
                      else ZIO.none
                    _        <- allowWork.succeed(())
                    joined   <- registry.await(id)
                    _        <- registry.publication(id)
                    terminal <- reader.taskRecord(id)
                  yield assertTrue(
                    held.exists(_.state == SubagentTaskRecordState.Pending),
                    stillBefore == 0,
                    terminal.exists(record =>
                      record.terminal.nonEmpty && record.metadata.exists(metadata =>
                        pending.headOption
                          .flatMap(_.metadata)
                          .exists(initial =>
                            metadata.createdAt == initial.createdAt && metadata.lastCheckedAt == initial.lastCheckedAt
                          )
                      )
                    ),
                    mode match
                      case DurableAdmission.Ready =>
                        active.exists(_.state == SubagentTaskRecordState.Running) && joined.exists(
                          _.isSuccess
                        )
                      case DurableAdmission.StartFailed =>
                        joined.exists(
                          _.causeOption.exists(_.failures == List(startFailure))
                        ) && terminal.exists(
                          _.terminal.exists(
                            _.outcome == SubagentTerminalOutcome
                              .Failed(
                                Chunk(SubagentTaskFailure(startFailure.message)),
                                SubagentTaskDefects.Absent
                              )
                          )
                        )
                      case _ => false
                  )
                case _ => ZIO.succeed(assertTrue(true))
            yield (
              result,
              assertTrue(
                before == 0,
                pending.size == 1,
                pending.forall(record =>
                  record.state == SubagentTaskRecordState.Pending && record.metadata.nonEmpty
                )
              ) && startCheck
            )
        (result, phases) = outcome
        records     <- reader.taskRecords
        count       <- builds.get
        totalWrites <- writes.get
        resultMatches = mode match
          case DurableAdmission.Ready | DurableAdmission.StartFailed |
              DurableAdmission.HostClosed =>
            result match
              case Exit.Success(SubagentTaskSubmission.Accepted(_)) => true
              case _                                                => false
          case DurableAdmission.LostReceipt =>
            result.causeOption.exists(cause =>
              cause.failures == List(failure) && cause.defects.isEmpty && !cause.isInterrupted
            )
          case DurableAdmission.CallerCancelled => result.isInterrupted
          case DurableAdmission.Closed => result == Exit.succeed(SubagentTaskSubmission.Closed)
      yield assertTrue(
        resultMatches,
        count == (if mode == DurableAdmission.Ready then 1 else 0),
        mode match
          case DurableAdmission.LostReceipt | DurableAdmission.CallerCancelled =>
            records.size == 1 && records.forall(
              _.state == SubagentTaskRecordState.Pending
            ) && totalWrites == 1
          case DurableAdmission.Closed     => records.isEmpty && totalWrites == 0
          case DurableAdmission.HostClosed => records.size == 1 && totalWrites == 2
          case _                           => records.size == 1 && totalWrites == 3
      ) && phases

  private def cancellationAttempt(
      mode: CancellationAttempt
  ): ZIO[TestEnvironment & Scope, AgentError, TestResult] =
    ZIO.scoped:
      for
        backend <- SubagentTerminalStoreSpec.fixture
        address <- ZIO.fromEither(
          StorageAddress.from(Chunk("cancel-attempt", mode.toString), "tasks")
        )
        base = SubagentTerminalStore.make(backend.store, address)
        starting       <- Promise.make[Nothing, Unit]
        allowStart     <- Promise.make[Nothing, Unit]
        entered        <- Promise.make[Nothing, Unit]
        cleaned        <- Ref.make(0)
        attempts       <- Ref.make(0)
        requestEntered <- Promise.make[Nothing, Unit]
        failure = AgentError.StorageError("Cancellation intent unavailable")
        store = new SubagentTerminalStore:
          def read(id: SubagentTaskId): IO[AgentError, Option[SubagentTerminalRecord]] =
            base.read(id)
          def list: IO[AgentError, Chunk[SubagentTerminalRecord]] = base.list
          def publish(record: SubagentTerminalRecord): IO[AgentError, SubagentTerminalPublication] =
            base.publish(record)
          override def admit(
              id: SubagentTaskId,
              workerId: Option[SubagentWorkerId]
          ): IO[AgentError, SubagentTaskAdmissionResult] = base.admit(id, workerId)
          override def confirmStart(
              identity: SubagentTaskAdmissionIdentity
          ): IO[AgentError, SubagentTaskStartResult] =
            starting.succeed(()) *> (if mode == CancellationAttempt.BeforeStart then
                                       allowStart.await
                                     else ZIO.unit) *> base.confirmStart(identity)
          override def requestCancellation(
              id: SubagentTaskId,
              expectedIdentity: Option[SubagentTaskAdmissionIdentity]
          ): IO[AgentError, SubagentCancellationRequestResult] =
            attempts.update(_ + 1) *> requestEntered.succeed(()) *> (mode match
              case CancellationAttempt.Failed => ZIO.fail(failure)
              case CancellationAttempt.Uncertain =>
                base.requestCancellation(id, expectedIdentity) *> ZIO.fail(failure)
              case CancellationAttempt.CallerCancelled => ZIO.never
              case CancellationAttempt.IdentityMismatch =>
                base
                  .requestCancellation(id, expectedIdentity)
                  .map:
                    case SubagentCancellationRequestResult.Requested(record) =>
                      SubagentCancellationRequestResult.Requested(
                        record.copy(metadata =
                          record.metadata.map(metadata =>
                            metadata.copy(createdAt = metadata.createdAt.plusSeconds(1L))
                          )
                        )
                      )
                    case other => other
              case CancellationAttempt.ForgedNoIntent |
                  CancellationAttempt.ForgedTerminalRequested |
                  CancellationAttempt.ForgedNonterminalAlreadyTerminal =>
                base
                  .requestCancellation(id, expectedIdentity)
                  .map:
                    case SubagentCancellationRequestResult.Requested(record) =>
                      mode match
                        case CancellationAttempt.ForgedNoIntent =>
                          SubagentCancellationRequestResult.Requested(
                            record
                              .copy(cancellationIntent = SubagentCancellationIntent.NotRequested)
                          )
                        case CancellationAttempt.ForgedTerminalRequested =>
                          SubagentCancellationRequestResult.Requested(
                            record.copy(state =
                              SubagentTaskRecordState.Terminal(
                                SubagentTerminalOutcome.Completed("forged"),
                                SubagentTerminalAcknowledgement.Pending
                              )
                            )
                          )
                        case _ => SubagentCancellationRequestResult.AlreadyTerminal(record)
                    case other => other
              case _ => base.requestCancellation(id, expectedIdentity))
        host       <- owner
        supervisor <- host.extend(TaskSupervisor.open[AgentResult])
        parent     <- SubagentSpawner.root(supervisor)
        configured <- factory(_ =>
          ZIO.acquireRelease(ZIO.unit)(_ => cleaned.update(_ + 1)) *> entered.succeed(
            ()
          ) *> ZIO.never
        )
        registry <- SubagentTaskRegistry.openDurable(parent, configured, store)
        _        <- ZIO.addFinalizer(allowStart.succeed(()).unit)
        id       <- accepted(registry.submit(input))
        _        <- starting.await
        _        <- if mode == CancellationAttempt.BeforeStart then ZIO.unit else entered.await
        priorTerminal = SubagentTerminalRecord(
          id,
          SubagentTerminalOutcome.Completed("persisted terminal truth")
        )
        _ <-
          if mode == CancellationAttempt.AlreadyTerminal then base.publish(priorTerminal).unit
          else ZIO.unit
        selected <-
          if mode == CancellationAttempt.StoredOnly then
            SubagentTaskRegistry.openDurable(parent, configured, store)
          else ZIO.succeed(registry)
        cancelling <- selected.cancelTask(id).exit.forkScoped
        _          <- requestEntered.await
        result <-
          if mode == CancellationAttempt.CallerCancelled then
            cancelling.interrupt.map:
              case Exit.Success(value) => value
              case Exit.Failure(cause) => Exit.failCause(cause)
          else if mode == CancellationAttempt.BeforeStart then
            allowStart.succeed(()) *> cancelling.join
          else cancelling.join
        successful = result match
          case Exit.Success(value) => Some(value)
          case Exit.Failure(_)     => None
        cleanupBeforeOwnerClose <- cleaned.get
        local                   <- registry.inspect(id)
        stored                  <- base.taskRecord(id)
        count                   <- attempts.get
        joined <- if mode == CancellationAttempt.BeforeStart then registry.await(id) else ZIO.none
      yield assertTrue(
        count == 1,
        mode match
          case CancellationAttempt.Failed | CancellationAttempt.Uncertain =>
            result == Exit.fail(failure) && cleanupBeforeOwnerClose == 0
          case CancellationAttempt.CallerCancelled =>
            result.isInterrupted && cleanupBeforeOwnerClose == 0
          case CancellationAttempt.IdentityMismatch | CancellationAttempt.ForgedNoIntent |
              CancellationAttempt.ForgedTerminalRequested |
              CancellationAttempt.ForgedNonterminalAlreadyTerminal =>
            result.causeOption.exists(
              _.failures.exists(
                _.message == "Cancellation receipt does not match the captured durable admission"
              )
            ) && cleanupBeforeOwnerClose == 0
          case CancellationAttempt.BeforeStart =>
            successful.exists:
              case SubagentTaskCancellation.Joined(exit) =>
                exit.isInterrupted && joined.contains(exit) && cleanupBeforeOwnerClose == 0
              case _ => false
          case CancellationAttempt.AlreadyTerminal =>
            successful.exists:
              case SubagentTaskCancellation.AlreadyTerminal(record) =>
                record.terminal.contains(priorTerminal) && cleanupBeforeOwnerClose == 0
              case _ => false
          case CancellationAttempt.StoredOnly =>
            successful.exists:
              case SubagentTaskCancellation.Requested(record) =>
                record.id == id && record.state == SubagentTaskRecordState.Running && cleanupBeforeOwnerClose == 0
              case _ => false
        ,
        mode == CancellationAttempt.BeforeStart || local.exists(
          _.state == SubagentTaskState.Running
        ),
        stored.exists(record =>
          if mode == CancellationAttempt.Failed || mode == CancellationAttempt.CallerCancelled then
            record.cancellationIntent == SubagentCancellationIntent.NotRequested
          else record.cancellationIntent != SubagentCancellationIntent.NotRequested
        )
      )

  private def joinedCancellation(
      mode: CancellationJoin
  ): ZIO[TestEnvironment & Scope, AgentError, TestResult] =
    ZIO.scoped:
      for
        backend <- SubagentTerminalStoreSpec.fixture
        address <- ZIO.fromEither(StorageAddress.from(Chunk("cancel", "public"), "tasks"))
        store  = SubagentTerminalStore.make(backend.store, address)
        defect = new IllegalStateException("owned cancellation cleanup defect")
        entered        <- Promise.make[Nothing, Unit]
        cleanupEntered <- Promise.make[Nothing, Unit]
        releaseCleanup <- Promise.make[Nothing, Unit]
        atCleanup      <- Promise.make[Nothing, Option[VersionedJson]]
        host           <- owner
        supervisor     <- host.extend(TaskSupervisor.open[AgentResult])
        parent         <- SubagentSpawner.root(supervisor)
        configured <- factory(_ =>
          ZIO.acquireRelease(ZIO.unit)(_ =>
            backend.values.get.flatMap(values => atCleanup.succeed(values.get(address))) *>
              cleanupEntered.succeed(()) *> releaseCleanup.await *>
              (mode match
                case CancellationJoin.Normal            => ZIO.unit
                case CancellationJoin.CallerInterrupted => ZIO.die(defect))
          ) *> entered.succeed(()) *> ZIO.never
        )
        registry <- SubagentTaskRegistry.openDurable(parent, configured, store)
        _        <- ZIO.addFinalizer(releaseCleanup.succeed(()).unit)
        id       <- accepted(registry.submit(input))
        _        <- entered.await
        cancelling <- ZIO.uninterruptibleMask: restore =>
          for
            fiber <- restore(
              SubagentTools
                .make(registry)
                .executeSafe(
                  "agent_cancel",
                  Json.Obj("task_id" -> Json.Str(id.value.toString))
                )
            ).forkScoped
            _ <- ZIO.addFinalizer(releaseCleanup.succeed(()).unit)
          yield fiber
        _               <- cleanupEntered.await
        snapshot        <- atCleanup.await
        beforeJoin      <- store.taskRecord(id)
        stillCancelling <- cancelling.poll
        controller      <- ZIO.fiberId
        _ <- mode match
          case CancellationJoin.Normal            => ZIO.unit
          case CancellationJoin.CallerInterrupted => cancelling.interruptAsFork(controller)
        afterCallerSignal <- cancelling.poll
        _                 <- releaseCleanup.succeed(())
        callerExit        <- cancelling.await
        _                 <- registry.publication(id)
        joined            <- registry.await(id)
        published         <- store.read(id)
        intentBeforeSignal = snapshot.exists(_.value match
          case Json.Obj(fields) =>
            fields
              .find(_._1 == "records")
              .exists(_._2 match
                case Json.Arr(records) =>
                  records.exists:
                    case Json.Obj(entry) =>
                      entry.contains("id" -> Json.Str(id.value.toString)) &&
                      entry
                        .find(_._1 == "cancellationIntent")
                        .exists(_._2 match
                          case Json.Obj(intent) =>
                            intent
                              .find(_._1 == "Requested")
                              .exists(_._2 match
                                case Json.Obj(request) =>
                                  request.exists:
                                    case ("at", Json.Str(value)) => value.nonEmpty
                                    case _                       => false
                                case _ => false)
                          case _ => false)
                    case _ => false
                case _ => false)
          case _ => false)
      yield assertTrue(
        intentBeforeSignal,
        beforeJoin.exists(_.state == SubagentTaskRecordState.Running),
        stillCancelling.isEmpty,
        joined.exists(_.isInterrupted),
        afterCallerSignal.isEmpty,
        mode match
          case CancellationJoin.Normal =>
            callerExit.isSuccess && published.exists(
              _.outcome == SubagentTerminalOutcome.Interrupted(
                Chunk.empty,
                SubagentTaskDefects.Absent
              )
            )
          case CancellationJoin.CallerInterrupted =>
            callerExit.causeOption.exists(cause =>
              cause.isInterrupted && cause.interruptors.contains(
                controller
              ) && cause.defects == List(defect) && cause.failures.isEmpty
            ) &&
            joined
              .flatMap(_.causeOption)
              .exists(cause =>
                cause.isInterrupted && cause.defects == List(defect) && cause.failures.isEmpty
              ) &&
            published.exists(
              _.outcome == SubagentTerminalOutcome.Interrupted(
                Chunk.empty,
                SubagentTaskDefects.Present
              )
            )
      )

  private def heartbeatOwner: ZIO[TestEnvironment & Scope, AgentError, TestResult] =
    ZIO.scoped:
      for
        backend <- SubagentTerminalStoreSpec.fixture
        address <- ZIO.fromEither(StorageAddress.from(Chunk("owner-heartbeat"), "records"))
        base = SubagentTerminalStore.make(backend.store, address)
        mode            <- Ref.make(HeartbeatMode.Normal)
        firstId         <- Ref.make(Option.empty[SubagentTaskId])
        reads           <- Ref.make(0)
        refreshes       <- Ref.make(Chunk.empty[SubagentTaskId])
        entered         <- Queue.unbounded[Unit]
        releaseChildren <- Promise.make[Nothing, Unit]
        attemptEntered  <- Promise.make[Nothing, Unit]
        cleanupEntered  <- Promise.make[Nothing, Unit]
        releaseCleanup  <- Promise.make[Nothing, Unit]
        failure = AgentError.StorageError("First heartbeat failed exactly")
        defect  = new IllegalStateException("Heartbeat observation cleanup exactly")
        store = new SubagentTerminalStore:
          def read(id: SubagentTaskId): IO[AgentError, Option[SubagentTerminalRecord]] =
            reads.update(_ + 1) *> base.read(id)
          def list: IO[AgentError, Chunk[SubagentTerminalRecord]] = reads.update(_ + 1) *> base.list
          def publish(record: SubagentTerminalRecord): IO[AgentError, SubagentTerminalPublication] =
            base.publish(record)
          override def admit(
              id: SubagentTaskId,
              workerId: Option[SubagentWorkerId]
          ): IO[AgentError, SubagentTaskAdmissionResult] = base.admit(id, workerId)
          override def confirmStart(
              identity: SubagentTaskAdmissionIdentity
          ): IO[AgentError, SubagentTaskStartResult] = base.confirmStart(identity)
          override def refreshHeartbeat(
              identity: SubagentTaskAdmissionIdentity
          ): IO[AgentError, SubagentTaskHeartbeatResult] =
            refreshes.update(_ :+ identity.id) *> mode.get
              .zip(firstId.get)
              .flatMap:
                case (HeartbeatMode.Normal, _) => base.refreshHeartbeat(identity)
                case (HeartbeatMode.Defect, first) if first.contains(identity.id) => ZIO.die(defect)
                case (HeartbeatMode.Interrupted, first) if first.contains(identity.id) =>
                  ZIO.interrupt
                case (_, first) if first.contains(identity.id) => ZIO.fail(failure)
                case (
                      HeartbeatMode.FirstFails | HeartbeatMode.Defect | HeartbeatMode.Interrupted,
                      _
                    ) =>
                  base.refreshHeartbeat(identity)
                case (HeartbeatMode.Cancel, _) =>
                  attemptEntered.succeed(()) *> ZIO.never.ensuring(
                    cleanupEntered.succeed(()) *> releaseCleanup.await *> ZIO.die(defect)
                  )
        configured <- factory(_ => entered.offer(()) *> releaseChildren.await.as(Toolkit.empty))
        supervisor <- TaskSupervisor.open[AgentResult]
        parent     <- SubagentSpawner.root(supervisor)
        lifetime   <- owner
        registry   <- lifetime.extend(SubagentTaskRegistry.openDurable(parent, configured, store))
        empty      <- registry.refreshCurrentOwner
        emptyReads <- reads.get
        emptyRefreshes <- refreshes.get
        _      <- ZIO.addFinalizer(releaseChildren.succeed(()) *> releaseCleanup.succeed(()).unit)
        first  <- lifetime.extend(accepted(registry.submit(input)))
        second <- lifetime.extend(accepted(registry.submit(input)))
        _      <- firstId.set(Some(first))
        _      <- entered.takeN(2)
        before <- base.taskRecords
        at     <- Clock.instant
        _      <- TestClock.adjust(5.seconds)
        normal <- registry.refreshCurrentOwner
        refreshed    <- base.taskRecords
        _            <- mode.set(HeartbeatMode.FirstFails)
        _            <- TestClock.adjust(5.seconds)
        partial      <- registry.refreshCurrentOwner.exit
        afterPartial <- base.taskRecords
        exceptional <- ZIO.foreach(Chunk(HeartbeatMode.Defect, HeartbeatMode.Interrupted)):
          exceptionalMode =>
            for
              _         <- mode.set(exceptionalMode)
              _         <- refreshes.set(Chunk.empty)
              result    <- registry.refreshCurrentOwner.exit
              attempted <- refreshes.get
            yield assertTrue(
              attempted == Chunk(first),
              result.causeOption.exists(cause =>
                cause.failures.isEmpty && (exceptionalMode match
                  case HeartbeatMode.Defect => cause.defects == List(defect) && !cause.isInterrupted
                  case _                    => cause.defects.isEmpty && cause.isInterrupted)
              )
            )
        _ <- mode.set(HeartbeatMode.Cancel)
        _ <- refreshes.set(Chunk.empty)
        worker <- ZIO.uninterruptibleMask: restore =>
          for
            fiber <- restore(registry.refreshCurrentOwner).forkScoped
            _     <- ZIO.addFinalizer(releaseCleanup.succeed(()).unit)
          yield fiber
        // Await explicit second-attempt admission, not a timer or a worker status guess.
        _                 <- attemptEntered.await
        admitted          <- refreshes.get
        controller        <- ZIO.fiberId
        _                 <- worker.interruptAsFork(controller)
        _                 <- cleanupEntered.await
        early             <- worker.poll
        _                 <- releaseCleanup.succeed(())
        cancelled         <- worker.await
        alive             <- registry.list
        _                 <- releaseChildren.succeed(())
        _                 <- registry.await(first)
        _                 <- registry.await(second)
        _                 <- registry.flushPublications
        beforeCompleted   <- backend.store.read(address)
        completed         <- registry.refreshCurrentOwner
        afterCompleted    <- backend.store.read(address)
        _                 <- lifetime.close(Exit.unit)
        beforeClosedReads <- reads.get
        closed            <- registry.refreshCurrentOwner.either
        afterClosedReads  <- reads.get
        volatile          <- SubagentTaskRegistry.open(parent, configured)
        unsupported       <- volatile.refreshCurrentOwner.either
      yield assertTrue(
        empty.isEmpty,
        emptyReads == 0,
        emptyRefreshes.isEmpty,
        normal == refreshed.map(SubagentTaskHeartbeatResult.Refreshed(_)),
        refreshed == before.map(record =>
          record.copy(metadata = record.metadata.map(_.copy(lastUpdatedAt = at.plusSeconds(5L))))
        ),
        partial.causeOption.exists(cause =>
          cause.failures == List(failure) && cause.defects.isEmpty && !cause.isInterrupted
        ),
        afterPartial == refreshed.map(record =>
          if record.id == second then
            record.copy(metadata = record.metadata.map(_.copy(lastUpdatedAt = at.plusSeconds(10L))))
          else record
        ),
        admitted == Chunk(first, second),
        early.isEmpty,
        cancelled.causeOption.exists(cause =>
          cause.failures == List(failure) && cause.defects == List(defect) && cause.interruptors
            .contains(controller)
        ),
        alive.map(_.id) == Chunk(first, second),
        alive.forall(_.state == SubagentTaskState.Running),
        completed.isEmpty,
        beforeCompleted == afterCompleted,
        closed == Left(
          AgentError.InvalidConfiguration("A closed registry cannot refresh its current owner")
        ),
        beforeClosedReads == afterClosedReads,
        unsupported == Left(
          AgentError.InvalidConfiguration("This registry does not support durable heartbeat")
        )
      ) && exceptional.foldLeft(assertTrue(true))(_ && _)

  def spec: Spec[TestEnvironment & Scope, Any] = suite("SubagentTaskRegistrySpec")(
    test(
      "public durable tasks persist mixed workspace JSON states at the session path and reopen without execution replay"
    ):
      for
        root <- temporaryDirectory
        directory <- ZIO.attemptBlocking(
          Files.createDirectories(root.resolve("agents/parent/tasks"))
        )
        target = directory.resolve("sess-rt.json")
        address  <- ZIO.fromEither(StorageAddress.from(Chunk("parent", "sess-rt"), "tasks"))
        lifetime <- owner
        backend <- lifetime.extend(
          LocalVersionedJsonStore.open(target, address, WorkspaceTrust.HostControlled)
        )
        store     = SubagentTerminalStore.make(backend, address)
        pendingId = SubagentTaskId(new UUID(0L, 991L))
        _       <- store.admit(pendingId)
        release <- Promise.make[Nothing, Unit]
        entered <- Promise.make[Nothing, Unit]
        builds  <- Ref.make(0)
        text = "complete λ 😀\n" * 1000
        registration = builds
          .updateAndGet(_ + 1)
          .flatMap:
            case 1 => entered.succeed(()) *> release.await.as(Toolkit.empty)
            case _ =>
              ZIO.fail(
                AgentError
                  .ToolExecutionError("file-child", "exact failed output", Some("typed cause λ"))
              )
        configured <- factory(
          _ => registration,
          _ => LlmClient.mock(Chunk(TurnOutput.FinalText(Msg.assistant(text))))
        )
        supervisor   <- lifetime.extend(TaskSupervisor.open[AgentResult])
        parent       <- SubagentSpawner.root(supervisor)
        registry     <- lifetime.extend(SubagentTaskRegistry.openDurable(parent, configured, store))
        _            <- ZIO.addFinalizer(release.succeed(()).unit)
        runningId    <- lifetime.extend(accepted(registry.submit(input)))
        _            <- entered.await
        failedId     <- lifetime.extend(accepted(registry.submit(input)))
        _            <- registry.await(failedId)
        _            <- registry.publication(failedId)
        mixed        <- store.taskRecords
        observations <- registry.observations
        exists       <- ZIO.attemptBlocking(Files.isRegularFile(target))
        json         <- ZIO.attemptBlocking(Files.readString(target, StandardCharsets.UTF_8))
        _            <- release.succeed(())
        _            <- registry.await(runningId)
        _            <- registry.publication(runningId)
        _            <- registry.acknowledgeTerminal(runningId)
        finished     <- store.taskRecords
        _            <- lifetime.close(Exit.unit)
        closed       <- backend.read(address).either
        freshBackend <- LocalVersionedJsonStore.open(target, address, WorkspaceTrust.HostControlled)
        freshStore = SubagentTerminalStore.make(freshBackend, address)
        recovered       <- freshStore.taskRecords
        freshSupervisor <- TaskSupervisor.open[AgentResult]
        freshParent     <- SubagentSpawner.root(freshSupervisor)
        freshRegistry   <- SubagentTaskRegistry.openDurable(freshParent, configured, freshStore)
        reopened        <- freshRegistry.observations
        checked         <- freshStore.acknowledgement(runningId)
        pending         <- freshStore.pending
        count           <- builds.get
      yield assertTrue(
        exists,
        json.nonEmpty,
        mixed.map(_.id).toSet == Set(pendingId, runningId, failedId),
        mixed.find(_.id == pendingId).exists(_.state == SubagentTaskRecordState.Pending),
        mixed.find(_.id == runningId).exists(_.state == SubagentTaskRecordState.Running),
        mixed
          .find(_.id == failedId)
          .flatMap(_.terminal)
          .contains(
            SubagentTerminalRecord(
              failedId,
              SubagentTerminalOutcome.Failed(
                Chunk(SubagentTaskFailure("exact failed output", Some("typed cause λ"))),
                SubagentTaskDefects.Absent
              )
            )
          ),
        observations.map(_.id).toSet == Set(pendingId, runningId, failedId),
        closed.isLeft,
        recovered == finished,
        recovered
          .find(_.id == runningId)
          .flatMap(_.terminal)
          .contains(SubagentTerminalRecord(runningId, SubagentTerminalOutcome.Completed(text))),
        reopened.map(_.id).toSet == Set(pendingId, runningId, failedId),
        checked.exists(value =>
          value match
            case SubagentTerminalAcknowledgement.Acknowledged(_) => true
            case _                                               => false
        ),
        pending.map(_.id) == Chunk(failedId),
        count == 2
      )
    ,
    test(
      "host heartbeat captures admitted local handles, continues typed failures and joins cancelled observation cleanup without cancelling tasks"
    ):
      heartbeatOwner
    ,
    test(
      "durable cancellation failures never signal children; pre-start, stored-only and already-terminal requests retain truthful authority"
    ):
      ZIO
        .foreach(
          Chunk(
            CancellationAttempt.Failed,
            CancellationAttempt.Uncertain,
            CancellationAttempt.CallerCancelled,
            CancellationAttempt.BeforeStart,
            CancellationAttempt.AlreadyTerminal,
            CancellationAttempt.StoredOnly,
            CancellationAttempt.IdentityMismatch,
            CancellationAttempt.ForgedNoIntent,
            CancellationAttempt.ForgedTerminalRequested,
            CancellationAttempt.ForgedNonterminalAlreadyTerminal
          )
        )(cancellationAttempt)
        .map(_.foldLeft(assertTrue(true))(_ && _))
    ,
    test(
      "durable cancellation persists intent before signalling and publishes terminal truth only after joined cleanup"
    ):
      ZIO
        .foreach(Chunk(CancellationJoin.Normal, CancellationJoin.CallerInterrupted))(
          joinedCancellation
        )
        .map(_.foldLeft(assertTrue(true))(_ && _))
    ,
    test(
      "public durable admission persists before child effects, releases only confirmed creation and retains truthful uncertainty or pre-start failure"
    ):
      ZIO
        .foreach(
          Chunk(
            DurableAdmission.Ready,
            DurableAdmission.LostReceipt,
            DurableAdmission.CallerCancelled,
            DurableAdmission.StartFailed,
            DurableAdmission.Closed,
            DurableAdmission.HostClosed
          )
        )(durableAdmission)
        .map(_.foldLeft(assertTrue(true))(_ && _))
    ,
    test("ordinary volatile launch preserves preparation before coordinated acquisition returns"):
      ZIO.scoped:
        for
          supervisor      <- TaskSupervisor.open[AgentResult]
          attached        <- Promise.make[Nothing, Unit]
          allowAcceptance <- Promise.make[Nothing, Unit]
          prepared        <- Ref.make(false)
          coordinated = new TaskSupervisor[AgentResult]:
            def own[B](
                initialize: TaskSupervisor[AgentResult] => ZIO[Scope, AgentError, B]
            ): ZIO[Scope, AgentError, TaskResourceAdmission[B]] = supervisor.own(initialize)
            def ownRetained[B](
                initialize: TaskSupervisor[AgentResult] => ZIO[Scope, AgentError, B]
            ): IO[AgentError, TaskResourceAdmission[B]] = supervisor.ownRetained(initialize)
            def attach(
                task: => ZIO[Scope, AgentError, AgentResult]
            ): ZIO[Scope, Nothing, TaskAdmission[AgentResult]] =
              supervisor
                .attach(task)
                .flatMap(admission => attached.succeed(()) *> allowAcceptance.await.as(admission))
          root       <- SubagentSpawner.root(coordinated)
          configured <- factory(_ => prepared.set(true).as(Toolkit.empty))
          registry   <- SubagentTaskRegistry.open(root, configured, 4)
          submitting <- ZIO.uninterruptibleMask: restore =>
            for
              fiber <- restore(registry.submit(input)).forkScoped
              _     <- ZIO.addFinalizer(allowAcceptance.succeed(()).unit)
            yield fiber
          _                <- attached.await
          _                <- TestClock.adjust(0.seconds)
          beforeAcceptance <- prepared.get
          _                <- allowAcceptance.succeed(())
          result           <- submitting.join
        yield assertTrue(
          beforeAcceptance,
          result match
            case SubagentTaskSubmission.Accepted(_) => true
            case _                                  => false
        )
    ,
    test(
      "rejected durable admission joins paused cleanup and preserves original failures and cleanup defects without starting work"
    ):
      val cases = Chunk(
        RejectedAdmission.Uncertain,
        RejectedAdmission.Collision,
        RejectedAdmission.CallerCancelled
      )
      ZIO
        .foreach(cases): mode =>
          ZIO.scoped:
            for
              backend <- SubagentTerminalStoreSpec.fixture
              target <- ZIO.fromEither(StorageAddress.from(Chunk("admission", "rejected"), "tasks"))
              failure = AgentError.StorageError("Admission receipt lost")
              defect  = new IllegalStateException("paused cleanup failed")
              admissionEntered <- Promise.make[Nothing, Unit]
              uncertain = new VersionedJsonStore:
                def read(address: StorageAddress): IO[AgentError, Option[VersionedJson]] =
                  backend.store.read(address)
                def compareAndSet(address: StorageAddress, expected: ExpectedRevision, value: Json)
                    : IO[AgentError, ConditionalWrite] =
                  backend.store
                    .compareAndSet(address, expected, value)
                    .flatMap: receipt =>
                      mode match
                        case RejectedAdmission.Uncertain => ZIO.fail(failure)
                        case RejectedAdmission.Collision => ZIO.succeed(receipt)
                        case RejectedAdmission.CallerCancelled =>
                          admissionEntered.succeed(()) *> ZIO.never
              store = SubagentTerminalStore.make(uncertain, target)
              _  <- TestRandom.feedLongs(11L, 22L)
              id <- Random.nextUUID.map(SubagentTaskId(_))
              _  <- if mode == RejectedAdmission.Collision then store.admit(id).unit else ZIO.unit
              supervisor <- TaskSupervisor.open[AgentResult]
              entered    <- Promise.make[Nothing, Unit]
              effects    <- Ref.make(0)
              registry <- SubagentTaskRegistry.openPreparedInput[Msg](
                identity,
                (_, _, _, beforeStart) =>
                  supervisor
                    .attach(
                      (entered.succeed(()) *> beforeStart *> effects
                        .update(_ + 1)
                        .as(AgentResult(Msg.assistant("never"), Chunk.empty, 1)))
                        .ensuring(ZIO.die(defect))
                    )
                    .tap(_ => entered.await),
                4,
                Some(store)
              )
              _ <- TestRandom.feedLongs(11L, 22L)
              result <-
                if mode == RejectedAdmission.CallerCancelled then
                  for
                    submitting <- registry.submit(input).forkScoped
                    _          <- admissionEntered.await
                    exit       <- submitting.interrupt
                  yield exit
                else registry.submit(input).exit
              records    <- SubagentTerminalStore.make(backend.store, target).taskRecords
              executions <- effects.get
              tracked    <- registry.list
              writes     <- backend.writes.get
              cause = result.causeOption.getOrElse(Cause.empty)
            yield assertTrue(
              cause.failures == (if mode == RejectedAdmission.Uncertain then List(failure)
                                 else Nil),
              cause.defects == List(defect),
              cause.isInterrupted == (mode == RejectedAdmission.CallerCancelled),
              executions == 0,
              tracked.isEmpty,
              writes == 1,
              records.size == 1,
              records.forall(record =>
                record.state == SubagentTaskRecordState.Pending && record.metadata.nonEmpty
              )
            )
        .map(_.foldLeft(assertTrue(true))(_ && _))
    ,
    test(
      "delivery snapshots preserve submission order and acknowledgement retains polling and capacity"
    ):
      ZIO.scoped:
        for
          supervisor <- TaskSupervisor.open[AgentResult]
          release    <- Promise.make[Nothing, Unit]
          result = AgentResult(Msg.assistant("done"), Chunk.empty, 1)
          observed <- (for
            registry <- SubagentTaskRegistry.openWith(
              (message, _, _) =>
                supervisor.attach(
                  (if message.text == "first" then release.await else ZIO.unit).as(result)
                ),
              2
            )
            first     <- accepted(registry.submit(Msg.user("first")))
            second    <- accepted(registry.submit(Msg.user("second")))
            _         <- registry.await(second)
            early     <- registry.pendingDeliveries
            active    <- registry.acknowledge(first)
            _         <- release.succeed(())
            firstExit <- registry.await(first)
            before    <- registry.pendingDeliveries
            ack       <- registry.acknowledge(first)
            repeated  <- registry.acknowledge(first)
            after     <- registry.pendingDeliveries
            retained  <- registry.await(first)
            snapshot  <- registry.inspect(first)
            full      <- registry.submit(input)
            forgotten <- registry.forgetCompleted(second)
            empty     <- registry.pendingDeliveries
            missing   <- registry.acknowledge(second)
          yield assertTrue(
            early == Chunk(SubagentTaskDelivery(second, Exit.succeed(result))),
            active == SubagentDeliveryAcknowledgement.Running,
            before.map(_.id) == Chunk(first, second),
            ack == SubagentDeliveryAcknowledgement.Acknowledged,
            repeated == SubagentDeliveryAcknowledgement.AlreadyAcknowledged,
            after == early,
            retained == firstExit && retained.contains(Exit.succeed(result)),
            completed(snapshot) == retained,
            full == SubagentTaskSubmission.Capacity,
            forgotten == SubagentTaskRemoval.Removed,
            empty.isEmpty,
            missing == SubagentDeliveryAcknowledgement.Missing
          )).ensuring(release.succeed(()).unit)
        yield observed
    ,
    test(
      "promoted delivery waits for cleanup and failed or cancelled host delivery leaves the full outcome pending"
    ):
      ZIO.scoped:
        for
          supervisor       <- TaskSupervisor.open[AgentResult]
          host             <- owner
          caller           <- owner
          entered          <- Promise.make[Nothing, Unit]
          finish           <- Promise.make[Nothing, Unit]
          cleaning         <- Promise.make[Nothing, Unit]
          release          <- Promise.make[Nothing, Unit]
          deliveryStarted  <- Promise.make[Nothing, Unit]
          deliveryCleaning <- Promise.make[Nothing, Unit]
          deliveryRelease  <- Promise.make[Nothing, Unit]
          failure         = AgentError.InvalidConfiguration("child failed")
          defect          = new IllegalStateException("child cleanup failed")
          deliveryFailure = AgentError.InvalidConfiguration("host delivery failed")
          deliveryDefect  = new IllegalStateException("host delivery cleanup failed")
          observed <- (for
            registry <- host.extend(
              SubagentTaskRegistry.openWith(
                (_, _, _) =>
                  supervisor.attach(
                    ZIO.addFinalizer(cleaning.succeed(()) *> release.await *> ZIO.die(defect)) *>
                      entered.succeed(()) *> finish.await *> ZIO.fail(failure)
                  ),
                1
              )
            )
            id       <- caller.extend(accepted(registry.submit(input)))
            _        <- entered.await
            waiting  <- registry.awaitOrPromote(id).forkScoped
            _        <- TestClock.adjust(30.seconds)
            promoted <- waiting.join
            _        <- caller.close(Exit.unit)
            _        <- finish.succeed(()) *> cleaning.await
            early    <- registry.pendingDeliveries
            active   <- registry.acknowledge(id)
            _        <- release.succeed(())
            terminal <- registry.await(id)
            pending  <- registry.pendingDeliveries
            failed <- (registry.pendingDeliveries *> ZIO.fail(deliveryFailure) *> registry
              .acknowledge(id)).exit
            stillPending <- registry.pendingDeliveries
            delivering <- ZIO
              .scoped(
                ZIO.addFinalizer(
                  deliveryCleaning.succeed(()) *> deliveryRelease.await *> ZIO.die(deliveryDefect)
                ) *>
                  registry.pendingDeliveries *> deliveryStarted.succeed(()) *> ZIO.never *> registry
                    .acknowledge(id)
              )
              .forkScoped
            _                 <- deliveryStarted.await
            cancelling        <- delivering.interrupt.forkScoped
            _                 <- deliveryCleaning.await
            joined            <- cancelling.poll
            _                 <- deliveryRelease.succeed(())
            cancelled         <- cancelling.join
            afterCancellation <- registry.pendingDeliveries
            _                 <- host.close(Exit.unit)
            afterClose        <- registry.pendingDeliveries
            retained          <- registry.await(id)
            ack               <- registry.acknowledge(id)
            empty             <- registry.pendingDeliveries
          yield assertTrue(
            promoted.contains(TaskWait.Promoted),
            early.isEmpty,
            active == SubagentDeliveryAcknowledgement.Running,
            terminal.exists(
              _.causeOption.exists(cause =>
                cause.failures == List(failure) && cause.defects == List(defect)
              )
            ),
            pending.map(_.exit) == Chunk.fromIterable(terminal),
            failed.causeOption.exists(_.failures == List(deliveryFailure)),
            stillPending == pending,
            joined.isEmpty,
            cancelled.isInterrupted,
            cancelled.causeOption.exists(_.defects == List(deliveryDefect)),
            afterCancellation == pending && afterClose == pending,
            retained == terminal,
            ack == SubagentDeliveryAcknowledgement.Acknowledged,
            empty.isEmpty
          )).ensuring(
            finish.succeed(()).unit *> release.succeed(()).unit *> deliveryRelease.succeed(()).unit
          )
        yield observed
    ,
    test("concurrent admission bounds retained results and executes fresh configured children") {
      for
        supervisor <- TaskSupervisor.open[AgentResult]
        root       <- SubagentSpawner.root(supervisor)
        builds     <- Ref.make(0)
        visible    <- Ref.make(Chunk.empty[(Int, Set[String])])
        forbidden = Tool.make(ToolDef("forbidden", "Must be filtered", Json.Obj()))(_ =>
          ZIO.succeed("forbidden")
        )
        configured <- factory(
          _ => builds.update(_ + 1).as(Toolkit.single(forbidden)),
          _ =>
            ZIO.succeed(
              LlmClient.mockEffectful((history, tools, _) =>
                visible
                  .update(_ :+ (history.count(_.text == "work"), tools.map(_.name).toSet))
                  .as(TurnOutput.FinalText(Msg.assistant("done")))
              )
            )
        )
        registry  <- SubagentTaskRegistry.open(root, configured, 2)
        submitted <- ZIO.foreachPar(Chunk.fromIterable(1 to 8))(_ => registry.submit(input))
        ids = submitted.collect { case SubagentTaskSubmission.Accepted(id) => id }
        results  <- ZIO.foreach(ids)(registry.await)
        full     <- registry.submit(input)
        listed   <- registry.list
        count    <- builds.get
        seen     <- visible.get
        removals <- ZIO.foreach(ids)(registry.forgetCompleted)
        next     <- accepted(registry.submit(input))
        _        <- registry.await(next)
      yield assertTrue(
        ids.size == 2,
        ids.toSet.size == 2,
        submitted.count(_ == SubagentTaskSubmission.Capacity) == 6,
        results.forall(_.exists(_.isSuccess)),
        full == SubagentTaskSubmission.Capacity,
        listed.map(_.id).toSet == ids.toSet,
        count == 2,
        seen == Chunk((1, Set.empty[String]), (1, Set.empty[String])),
        removals.forall(_ == SubagentTaskRemoval.Removed)
      )
    },
    test("identity collisions cannot replace work and lookup is isolated to a registry") {
      for
        supervisor <- TaskSupervisor.open[AgentResult]
        root       <- SubagentSpawner.root(supervisor)
        entered    <- Promise.make[Nothing, Unit]
        release    <- Promise.make[Nothing, Unit]
        builds     <- Ref.make(0)
        configured <- factory(_ =>
          builds.update(_ + 1) *> entered.succeed(()) *> release.await.as(Toolkit.empty)
        )
        registry        <- SubagentTaskRegistry.open(root, configured, 2)
        other           <- SubagentTaskRegistry.open(root, configured, 2)
        _               <- TestRandom.feedLongs(11L, 22L, 33L, 44L)
        id              <- accepted(registry.submit(input))
        _               <- entered.await
        _               <- TestRandom.feedLongs(11L, 22L)
        collision       <- registry.submit(input)
        live            <- registry.inspect(id)
        removal         <- registry.forgetCompleted(id)
        missing         <- other.inspect(id)
        absentWait      <- other.await(id)
        absentPromotion <- other.awaitOrPromote(id)
        absentCancel    <- other.cancel(id)
        absentRemoval   <- other.forgetCompleted(id)
        _               <- release.succeed(())
        result          <- registry.await(id)
        same            <- registry.cancel(id)
        count           <- builds.get
        removed         <- registry.forgetCompleted(id)
        forgotten       <- registry.inspect(id)
      yield assertTrue(
        collision == SubagentTaskSubmission.IdentityCollision,
        live.contains(SubagentTaskSnapshot(id, SubagentTaskState.Running)),
        removal == SubagentTaskRemoval.Running,
        missing.isEmpty && absentWait.isEmpty && absentPromotion.isEmpty && absentCancel.isEmpty,
        absentRemoval == SubagentTaskRemoval.Missing,
        result.exists(_.isSuccess),
        same == result,
        count == 1,
        removed == SubagentTaskRemoval.Removed,
        forgotten.isEmpty
      )
    },
    test("snapshots retain typed failures, defects and interruption as complete exits") {
      val failure = AgentError.InvalidConfiguration("registration failed")
      val defect  = new IllegalStateException("registration cleanup failed")
      val cases
          : Chunk[(ZIO[Scope, AgentError, Toolkit], Exit[AgentError, AgentResult] => Boolean)] =
        Chunk(
          (ZIO.succeed(Toolkit.empty), _.isSuccess),
          (
            ZIO.addFinalizer(ZIO.die(defect)) *> ZIO.fail(failure),
            _.causeOption.exists(cause =>
              cause.failures.contains(failure) && cause.defects.contains(defect)
            )
          ),
          (ZIO.interrupt, _.isInterrupted)
        )
      ZIO
        .foreach(cases) { case (registration, valid) =>
          for
            supervisor <- TaskSupervisor.open[AgentResult]
            root       <- SubagentSpawner.root(supervisor)
            configured <- factory(_ => registration)
            registry   <- SubagentTaskRegistry.open(root, configured, 1)
            id         <- accepted(registry.submit(input))
            result     <- registry.await(id)
            observed   <- registry.inspect(id)
            cancelled  <- registry.cancel(id)
          yield assertTrue(result.exists(valid), completed(observed) == result, cancelled == result)
        }
        .map(results => results.reduceOption(_ && _).getOrElse(assertTrue(false)))
    },
    test("caller closure joins attached cleanup while promoted tasks remain retrievable") {
      ZIO
        .foreach(Chunk(WaitMode.Attached, WaitMode.Promoted)) { mode =>
          for
            supervisor <- TaskSupervisor.open[AgentResult]
            root       <- SubagentSpawner.root(supervisor)
            host       <- owner
            caller     <- owner
            entered    <- Promise.make[Nothing, Unit]
            cleaning   <- Promise.make[Nothing, Unit]
            release    <- Promise.make[Nothing, Unit]
            configured <- factory(_ =>
              ZIO.addFinalizer(cleaning.succeed(()) *> release.await) *>
                entered.succeed(()) *> ZIO.never
            )
            registry <- host.extend(SubagentTaskRegistry.open(root, configured, 1))
            id       <- caller.extend(accepted(registry.submit(input)))
            _        <- entered.await
            promoted <-
              if mode == WaitMode.Promoted then
                for
                  waiting <- registry.awaitOrPromote(id).forkScoped
                  _       <- TestClock.adjust(30.seconds)
                  result  <- waiting.join
                yield result.contains(TaskWait.Promoted)
              else ZIO.succeed(false)
            closing      <- caller.close(Exit.unit).forkScoped
            _            <- if mode == WaitMode.Promoted then closing.join else cleaning.await
            current      <- registry.inspect(id)
            pending      <- closing.poll
            hostClosing  <- host.close(Exit.unit).forkScoped
            _            <- cleaning.await
            stillClosing <- hostClosing.poll
            _            <- release.succeed(())
            _            <- closing.join *> hostClosing.join
            result       <- registry.await(id)
          yield assertTrue(
            promoted == (mode == WaitMode.Promoted),
            current.contains(SubagentTaskSnapshot(id, SubagentTaskState.Running)),
            pending.isDefined == (mode == WaitMode.Promoted),
            stillClosing.isEmpty,
            result.exists(_.isInterrupted)
          )
        }
        .map(results => results.reduceOption(_ && _).getOrElse(assertTrue(false)))
    },
    test(
      "registry shutdown closes admission and interrupts workers in parallel before joining cleanup"
    ) {
      for
        supervisor <- TaskSupervisor.open[AgentResult]
        root       <- SubagentSpawner.root(supervisor)
        host       <- owner
        entered    <- Queue.unbounded[Unit]
        cleaning   <- Queue.unbounded[Unit]
        release    <- Promise.make[Nothing, Unit]
        builds     <- Ref.make(0)
        configured <- factory(_ =>
          ZIO.addFinalizer(cleaning.offer(()).unit *> release.await) *>
            builds.update(_ + 1) *> entered.offer(()) *> ZIO.never
        )
        registry <- host.extend(SubagentTaskRegistry.open(root, configured, 16))
        initial  <- ZIO.foreach(Chunk(1, 2))(_ => accepted(registry.submit(input)))
        _        <- entered.take *> entered.take
        closing  <- host.close(Exit.unit).forkScoped
        racing   <- ZIO.foreachPar(Chunk.fromIterable(1 to 8))(_ => registry.submit(input))
        _        <- cleaning.take *> cleaning.take
        pending  <- closing.poll
        rejected <- registry.submit(input)
        _        <- release.succeed(())
        _        <- closing.join
        ids = initial ++ racing.collect { case SubagentTaskSubmission.Accepted(id) => id }
        results      <- ZIO.foreach(ids)(registry.await)
        listed       <- registry.list
        before       <- builds.get
        closed       <- registry.submit(input)
        after        <- builds.get
        closedCaller <- closedCallerAdmission
      yield assertTrue(
        pending.isEmpty,
        rejected == SubagentTaskSubmission.Closed,
        closed == SubagentTaskSubmission.Closed,
        before == after,
        listed.map(_.id).toSet == ids.toSet,
        results.forall(_.exists(_.isInterrupted))
      ) && closedCaller
    },
    test(
      "invalid capacity, rejected depth and a closed supervisor never consume registry capacity"
    ) {
      for
        supervisorHost <- owner
        supervisor     <- supervisorHost.extend(TaskSupervisor.open[AgentResult])
        root           <- SubagentSpawner.root(supervisor)
        calls          <- Ref.make(0)
        configured     <- factory(_ => calls.update(_ + 1).as(Toolkit.empty))
        invalid        <- SubagentTaskRegistry.open(root, configured, 0).exit
        deepest        <- Promise.make[Nothing, SubagentSpawner[AgentResult]]
        _              <- root.spawn(child => descend(child, deepest))
        leaf           <- deepest.await
        registry       <- SubagentTaskRegistry.open(leaf, configured, 1)
        rejected       <- registry.submit(input).exit
        retry          <- registry.submit(input).exit
        empty          <- registry.list
        _              <- supervisorHost.close(Exit.unit)
        closedRegistry <- SubagentTaskRegistry.open(root, configured, 1)
        closed         <- closedRegistry.submit(input)
        closedRetry    <- closedRegistry.submit(input)
        closedEntries  <- closedRegistry.list
        count          <- calls.get
      yield assertTrue(
        invalid.isFailure,
        rejected.causeOption.exists(_.failures.contains(AgentError.SpawnDepthExceeded(3))),
        retry.causeOption.exists(_.failures.contains(AgentError.SpawnDepthExceeded(3))),
        empty.isEmpty,
        closed == SubagentTaskSubmission.Closed,
        closedRetry == SubagentTaskSubmission.Closed,
        closedEntries.isEmpty,
        count == 0
      )
    }
  )

  private def descend(
      parent: SubagentSpawner[AgentResult],
      deepest: Promise[Nothing, SubagentSpawner[AgentResult]]
  ): ZIO[Scope, AgentError, AgentResult] =
    if parent.depth == SubagentSpawner.MaximumDepth then deepest.succeed(parent) *> ZIO.never
    else
      parent
        .spawn(child => descend(child, deepest))
        .flatMap:
          case TaskAdmission.Accepted(task) => task.await.flatMap(exit => exit)
          case TaskAdmission.Closed =>
            ZIO.fail(AgentError.InvalidConfiguration("Unexpected closure"))

  private def closedCallerAdmission: ZIO[Scope, AgentError, TestResult] =
    for
      supervisor <- TaskSupervisor.open[AgentResult]
      entered    <- Promise.make[Nothing, Unit]
      cleaning   <- Promise.make[Nothing, Unit]
      release    <- Promise.make[Nothing, Unit]
      // Force the valid schedule where the worker starts before its caller lease is registered.
      coordinated = new TaskSupervisor[AgentResult]:
        def own[B](
            initialize: TaskSupervisor[AgentResult] => ZIO[Scope, AgentError, B]
        ): ZIO[Scope, AgentError, TaskResourceAdmission[B]] =
          supervisor.own(initialize)

        def ownRetained[B](
            initialize: TaskSupervisor[AgentResult] => ZIO[Scope, AgentError, B]
        ): IO[AgentError, TaskResourceAdmission[B]] = supervisor.ownRetained(initialize)

        def attach(
            task: => ZIO[Scope, AgentError, AgentResult]
        ): ZIO[Scope, Nothing, TaskAdmission[AgentResult]] =
          ZIO.uninterruptibleMask: restore =>
            for
              lease     <- Scope.make
              admission <- lease.extend(supervisor.attach(task))
              _         <- restore(entered.await).onInterrupt(lease.close(Exit.unit))
              _         <- ZIO.addFinalizer(lease.close(Exit.unit))
            yield admission
      root   <- SubagentSpawner.root(coordinated)
      caller <- owner
      _      <- caller.close(Exit.unit)
      configured <- factory(_ =>
        ZIO.addFinalizer(cleaning.succeed(()) *> release.await) *>
          entered.succeed(()) *> ZIO.never
      )
      registry   <- SubagentTaskRegistry.open(root, configured, 1)
      submitting <- caller.extend(registry.submit(input)).forkScoped
      _          <- cleaning.await
      visible    <- registry.list
      contender  <- registry.submit(input).forkScoped
      _          <- TestClock.adjust(0.seconds)
      admission  <- contender.poll
      // Release every barrier before asserting, including on the defective implementation.
      _         <- release.succeed(())
      _         <- submitting.join *> contender.join
      remaining <- registry.list
    yield assertTrue(
      visible.size == 1,
      admission.contains(Exit.succeed(SubagentTaskSubmission.Capacity)),
      remaining.size == 1,
      remaining.forall(snapshot => completed(Some(snapshot)).exists(_.isInterrupted))
    )
