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
import com.weisgnosis.agents.harness.storage.*
import java.util.UUID
import zio.*
import zio.json.ast.Json
import zio.test.*

object SubagentRemoteTaskSpec extends ZIOSpecDefault:
  final private case class Fixture(
      backend: SubagentTerminalStoreSpec.Fixture,
      address: StorageAddress,
      store: SubagentTerminalStore,
      identity: SubagentTaskAdmissionIdentity,
      endpoint: SubagentProtocolBaseUri,
      generation: SubagentRemoteApprovalGeneration
  )

  private def fixture: IO[AgentError, Fixture] =
    for
      backend    <- SubagentTerminalStoreSpec.fixture
      address    <- ZIO.fromEither(StorageAddress.from(Chunk("remote-observation"), "tasks"))
      endpoint   <- ZIO.fromEither(SubagentProtocolBaseUri.from("https://remote.example/agent"))
      generation <- ZIO.fromEither(SubagentRemoteApprovalGeneration.fromLong(1L))
      store = SubagentTerminalStore.make(backend.store, address)
      admission <- store.admitAgentProtocol(SubagentTaskId(new UUID(0L, 201L)), endpoint)
      identity <- admission match
        case SubagentTaskAdmissionResult.Created(identity) => ZIO.succeed(identity)
        case _ => ZIO.fail(AgentError.StorageError("Unexpected fixture admission"))
    yield Fixture(backend, address, store, identity, endpoint, generation)

  private val calls: Chunk[SubagentRemotePendingApproval] = Chunk(
    SubagentRemotePendingApproval("approval", "read", Json.Obj("path" -> Json.Str("safe")))
  )
  private val decisions: Chunk[SubagentRemoteConfirmation] = Chunk(
    SubagentRemoteConfirmation("approval", SubagentRemoteDecision.Deny("host decision"))
  )

  private enum Delivery:
    case Confirmed, Uncertain

  private def deliveryContract(delivery: Delivery): ZIO[Scope, AgentError, TestResult] =
    for
      f        <- fixture
      status   <- Ref.make[SubagentRemoteObservation](SubagentRemoteObservation.Pending)
      contacts <- Ref.make(Chunk.empty[String])
      closed   <- Ref.make(0)
      entered  <- Promise.make[Nothing, Unit]
      release  <- Promise.make[Nothing, Unit]
      failure = AgentError.StorageError("remote decision delivery uncertain")
      transport = new SubagentRemoteTransport:
        val endpoint: SubagentProtocolBaseUri = f.endpoint
        def observe(id: SubagentTaskId): ZIO[Scope, AgentError, SubagentRemoteObservation] =
          ZIO.addFinalizer(closed.update(_ + 1)) *> contacts.update(_ :+ "observe") *> status.get
        def cancel(id: SubagentTaskId): ZIO[Scope, AgentError, Unit] =
          f.store
            .taskRecord(id)
            .flatMap:
              case Some(record)
                  if record.cancellationIntent != SubagentCancellationIntent.NotRequested =>
                contacts.update(_ :+ "cancel")
              case _ => ZIO.fail(AgentError.StorageError("Cancel arrived before intent"))
        def confirm(
            id: SubagentTaskId,
            generation: SubagentRemoteApprovalGeneration,
            input: Chunk[SubagentRemoteConfirmation]
        ): ZIO[Scope, AgentError, Unit] =
          f.store
            .taskRecord(id)
            .flatMap:
              case Some(record)
                  if record.remoteApproval == SubagentRemoteApproval.ConfirmationClaimed(
                    generation,
                    calls,
                    input
                  ) =>
                contacts.update(_ :+ "confirm") *> entered.succeed(()) *> release.await *>
                  (delivery match
                    case Delivery.Confirmed => status.set(SubagentRemoteObservation.Running)
                    case Delivery.Uncertain => ZIO.fail(failure))
              case _ => ZIO.fail(AgentError.StorageError("Confirmation arrived before exact claim"))
      first     <- SubagentRemoteTask.attach(f.store, f.identity, transport)
      second    <- SubagentRemoteTask.attach(f.store, f.identity, transport)
      noContact <- contacts.get
      pending   <- first.observe
      _         <- status.set(SubagentRemoteObservation.Awaiting(f.generation, calls))
      awaiting  <- first.observe
      invalid   <- first.confirm(f.generation, Chunk.empty).either
      attempt <- ZIO.uninterruptibleMask: restore =>
        for
          fiber <- restore(first.confirm(f.generation, decisions).either).forkScoped
          _     <- ZIO.addFinalizer(release.succeed(()).unit)
        yield fiber
      _         <- entered.await
      competing <- second.confirm(f.generation, decisions).either
      claimed   <- f.store.taskRecord(f.identity.id)
      _         <- release.succeed(())
      completed <- attempt.join
      _         <- status.set(SubagentRemoteObservation.Awaiting(f.generation, calls))
      sameBatch <- second.observe
      repeated  <- second.confirm(f.generation, decisions).either
      _         <- status.set(SubagentRemoteObservation.Running)
      running   <- second.observe
      cancelled <- first.requestCancellation
      _ <- status.set(
        SubagentRemoteObservation.Terminal(SubagentTerminalOutcome.Completed(" exact result \n"))
      )
      terminal       <- second.observe
      _              <- f.store.acknowledge(f.identity.id)
      before         <- f.backend.store.read(f.address)
      terminalCancel <- first.requestCancellation
      after          <- f.backend.store.read(f.address)
      observed       <- contacts.get
      cleanupCount   <- closed.get
      reopened <- SubagentTerminalStore.make(f.backend.store, f.address).taskRecord(f.identity.id)
    yield assertTrue(
      noContact.isEmpty,
      pending.state == SubagentTaskRecordState.Pending,
      awaiting.remoteApproval == SubagentRemoteApproval.Awaiting(f.generation, calls),
      invalid.isLeft,
      competing.isLeft,
      repeated.isLeft,
      claimed.exists(
        _.remoteApproval == SubagentRemoteApproval
          .ConfirmationClaimed(f.generation, calls, decisions)
      ),
      claimed.exists(_.remoteApproval == sameBatch.remoteApproval),
      running.remoteApproval == sameBatch.remoteApproval,
      cancelled.terminal.isEmpty && cancelled.cancellationIntent != SubagentCancellationIntent.NotRequested,
      terminal.terminal.exists(_.outcome == SubagentTerminalOutcome.Completed(" exact result \n")),
      terminal.origin == f.identity.origin && terminal.workerId == f.identity.workerId,
      terminalCancel.terminal == terminal.terminal,
      before == after,
      observed.count(_ == "confirm") == 1 && observed.count(_ == "cancel") == 1,
      cleanupCount == observed.count(_ == "observe"),
      reopened.exists(record =>
        record.terminal == terminal.terminal && record.cancellationIntent == terminal.cancellationIntent
      ),
      completed.fold(_ == failure, _ => delivery == Delivery.Confirmed)
    )

  private def forgedGeneration: ZIO[Scope, AgentError, TestResult] =
    for
      f <- fixture
      original <- f.store
        .taskRecord(f.identity.id)
        .someOrFail(AgentError.StorageError("Missing admission"))
      awaiting <- f.store.applyRemoteObservation(
        original,
        SubagentRemoteObservation.Awaiting(f.generation, calls)
      )
      wrongGeneration <- ZIO.fromEither(SubagentRemoteApprovalGeneration.fromLong(2L))
      record          <- Ref.make(awaiting)
      contacted       <- Ref.make(0)
      custom = new SubagentTerminalStore:
        def read(id: SubagentTaskId): IO[AgentError, Option[SubagentTerminalRecord]] = ZIO.none
        def list: IO[AgentError, Chunk[SubagentTerminalRecord]] = ZIO.succeed(Chunk.empty)
        def publish(value: SubagentTerminalRecord): IO[AgentError, SubagentTerminalPublication] =
          ZIO.fail(AgentError.StorageError("Unexpected publication"))
        override def taskRecord(id: SubagentTaskId): IO[AgentError, Option[SubagentTaskRecord]] =
          record.get.map(Some(_))
        override def claimRemoteConfirmation(
            expected: SubagentTaskRecord,
            input: Chunk[SubagentRemoteConfirmation]
        ): IO[AgentError, SubagentTaskRecord] =
          record.updateAndGet(
            _.copy(remoteApproval =
              SubagentRemoteApproval.ConfirmationClaimed(wrongGeneration, calls, input)
            )
          )
      transport = new SubagentRemoteTransport:
        val endpoint: SubagentProtocolBaseUri = f.endpoint
        def observe(id: SubagentTaskId): ZIO[Scope, AgentError, SubagentRemoteObservation] =
          ZIO.succeed(SubagentRemoteObservation.Running)
        def cancel(id: SubagentTaskId): ZIO[Scope, AgentError, Unit] = contacted.update(_ + 1)
        def confirm(
            id: SubagentTaskId,
            generation: SubagentRemoteApprovalGeneration,
            input: Chunk[SubagentRemoteConfirmation]
        ): ZIO[Scope, AgentError, Unit] = contacted.update(_ + 1)
      wrongIdentity <- SubagentRemoteTask
        .attach(
          custom,
          f.identity.copy(createdAt = f.identity.createdAt.plusSeconds(1L)),
          transport
        )
        .either
      differentPeer <- ZIO.fromEither(SubagentProtocolBaseUri.from("https://different.example"))
      wrongPeer <- SubagentRemoteTask
        .attach(
          custom,
          f.identity.copy(origin = SubagentExecutionOrigin.AgentProtocol(differentPeer)),
          transport
        )
        .either
      task     <- SubagentRemoteTask.attach(custom, f.identity, transport)
      result   <- task.confirm(f.generation, decisions).either
      attempts <- contacted.get
    yield assertTrue(result.isLeft, attempts == 0, wrongIdentity.isLeft, wrongPeer.isLeft)

  private enum Stop:
    case Deadline, DeadlineClean, Caller

  private def stopContract(stop: Stop): ZIO[TestEnvironment & Scope, AgentError, TestResult] =
    for
      f             <- fixture
      entered       <- Promise.make[Nothing, Unit]
      cleaning      <- Promise.make[Nothing, Unit]
      release       <- Promise.make[Nothing, Unit]
      cancellations <- Ref.make(0)
      defect = new IllegalStateException("remote request cleanup")
      transport = new SubagentRemoteTransport:
        val endpoint: SubagentProtocolBaseUri = f.endpoint
        def observe(id: SubagentTaskId): ZIO[Scope, AgentError, SubagentRemoteObservation] =
          ZIO.addFinalizer(cleaning.succeed(()) *> release.await *> (stop match
            case Stop.DeadlineClean => ZIO.unit
            case _                  => ZIO.die(defect))) *> entered.succeed(()) *> ZIO.never
        def cancel(id: SubagentTaskId): ZIO[Scope, AgentError, Unit] = cancellations.update(_ + 1)
        def confirm(
            id: SubagentTaskId,
            generation: SubagentRemoteApprovalGeneration,
            input: Chunk[SubagentRemoteConfirmation]
        ): ZIO[Scope, AgentError, Unit] = ZIO.unit
      task    <- SubagentRemoteTask.attach(f.store, f.identity, transport)
      timeout <- ZIO.fromEither(SubagentRemoteTask.WaitTimeout.fromMilliseconds(1000L))
      active <- ZIO.uninterruptibleMask: restore =>
        for
          fiber <- restore(task.await(timeout)).forkScoped
          _     <- ZIO.addFinalizer(release.succeed(()).unit)
        yield fiber
      _          <- entered.await
      controller <- ZIO.fiberId
      _ <- stop match
        case Stop.Caller => active.interruptAsFork(controller)
        case _           => TestClock.adjust(1.second)
      _        <- cleaning.await
      held     <- active.poll
      _        <- release.succeed(())
      exit     <- active.await
      noCancel <- cancellations.get
      record <- f.store
        .taskRecord(f.identity.id)
        .someOrFail(AgentError.StorageError("Missing fixture"))
      invalid <- f.store
        .applyRemoteObservation(
          record,
          SubagentRemoteObservation.Awaiting(f.generation, calls ++ calls)
        )
        .either
      suspended <- f.store
        .applyRemoteObservation(
          record,
          SubagentRemoteObservation.Terminal(SubagentTerminalOutcome.Suspended("not terminal"))
        )
        .either
      _ <- f.store.applyRemoteObservation(record, SubagentRemoteObservation.Running)
      stale <- f.store
        .applyRemoteObservation(
          record,
          SubagentRemoteObservation.Terminal(SubagentTerminalOutcome.Completed("stale"))
        )
        .either
    yield assertTrue(
      held.isEmpty,
      stop match
        case Stop.DeadlineClean => exit == Exit.succeed(SubagentRemoteAwaitResult.TimedOut)
        case Stop.Deadline =>
          exit.causeOption.exists(cause =>
            cause.defects == List(defect) && !cause.isInterrupted && cause.failures.isEmpty
          )
        case Stop.Caller =>
          exit.causeOption.exists(cause =>
            cause.defects == List(defect) && cause.interruptors.contains(
              controller
            ) && cause.failures.isEmpty
          )
      ,
      noCancel == 0,
      invalid.isLeft,
      suspended.isLeft,
      stale.isLeft,
      SubagentRemoteApprovalGeneration.fromLong(0L).isLeft,
      SubagentRemoteTask.WaitTimeout.fromMilliseconds(-1L).isLeft,
      SubagentRemoteTask.WaitTimeout.fromMilliseconds(120001L).isLeft
    )

  private enum ApprovalField:
    case Keep, Omit

  private def schemaContract: IO[AgentError, TestResult] =
    def rewrite(value: Json, version: Int, approval: ApprovalField): Json = value match
      case Json.Obj(fields) =>
        Json.Obj(fields.map:
          case ("schemaVersion", _) => "schemaVersion" -> Json.Num(version)
          case ("records", Json.Arr(records)) =>
            "records" -> Json.Arr(records.map:
              case Json.Obj(entry) =>
                Json.Obj(
                  entry.filterNot(pair =>
                    approval == ApprovalField.Omit && pair._1 == "remoteApproval"
                  )
                )
              case other => other)
          case other => other)
      case other => other
    for
      f <- fixture
      record <- f.store
        .taskRecord(f.identity.id)
        .someOrFail(AgentError.StorageError("Missing admission"))
      raw <- f.backend.store
        .read(f.address)
        .someOrFail(AgentError.StorageError("Missing aggregate"))
      original = raw.copy(value = rewrite(raw.value, 7, ApprovalField.Omit))
      _         <- f.backend.values.set(Map(f.address -> original))
      before    <- f.backend.writes.get
      read      <- f.store.taskRecord(f.identity.id)
      unchanged <- f.backend.store.read(f.address)
      after     <- f.backend.writes.get
      _ <- f.backend.values.set(
        Map(f.address -> raw.copy(value = rewrite(raw.value, 7, ApprovalField.Keep)))
      )
      spoof <- f.store.taskRecords.either
      _ <- f.backend.values.set(
        Map(f.address -> raw.copy(value = rewrite(raw.value, 8, ApprovalField.Omit)))
      )
      missing <- f.store.taskRecords.either
      _       <- f.backend.values.set(Map(f.address -> raw))
      updated <- f.store.applyRemoteObservation(
        record,
        SubagentRemoteObservation.Awaiting(f.generation, calls)
      )
      claimed  <- f.store.claimRemoteConfirmation(updated, decisions)
      _        <- f.store.applyRemoteObservation(claimed, SubagentRemoteObservation.Running)
      reopened <- SubagentTerminalStore.make(f.backend.store, f.address).taskRecord(f.identity.id)
      resolved <- f.store.applyRemoteObservation(
        claimed,
        SubagentRemoteObservation.ApprovalResolved(f.generation)
      )
      reused <- f.store
        .applyRemoteObservation(resolved, SubagentRemoteObservation.Awaiting(f.generation, calls))
        .either
      newer <- ZIO.fromEither(SubagentRemoteApprovalGeneration.fromLong(2L))
      next <- f.store.applyRemoteObservation(
        resolved,
        SubagentRemoteObservation.Awaiting(newer, calls)
      )
      regressed <- f.store
        .applyRemoteObservation(next, SubagentRemoteObservation.Awaiting(f.generation, calls))
        .either
      changedCalls <- f.store
        .applyRemoteObservation(
          next,
          SubagentRemoteObservation.Awaiting(newer, calls.map(_.copy(toolName = "different")))
        )
        .either
      attempts <- Ref.make(0)
      contacts <- Ref.make(0)
      uncertainFailure = AgentError.StorageError("claim receipt lost after commit")
      uncertainBackend = new VersionedJsonStore:
        def read(address: StorageAddress): IO[AgentError, Option[VersionedJson]] =
          f.backend.store.read(address)
        def compareAndSet(
            address: StorageAddress,
            expected: ExpectedRevision,
            value: Json
        ): IO[AgentError, ConditionalWrite] =
          attempts.update(_ + 1) *> f.backend.store.compareAndSet(address, expected, value) *>
            ZIO.fail(uncertainFailure)
      uncertainStore = SubagentTerminalStore.make(uncertainBackend, f.address)
      transport = new SubagentRemoteTransport:
        val endpoint: SubagentProtocolBaseUri = f.endpoint
        def observe(id: SubagentTaskId): ZIO[Scope, AgentError, SubagentRemoteObservation] =
          ZIO.fail(AgentError.StorageError("Unexpected observation"))
        def cancel(id: SubagentTaskId): ZIO[Scope, AgentError, Unit] = contacts.update(_ + 1)
        def confirm(
            id: SubagentTaskId,
            generation: SubagentRemoteApprovalGeneration,
            input: Chunk[SubagentRemoteConfirmation]
        ): ZIO[Scope, AgentError, Unit] = contacts.update(_ + 1)
      owner        <- SubagentRemoteTask.attach(uncertainStore, f.identity, transport)
      uncertain    <- owner.confirm(newer, decisions).either
      retry        <- owner.confirm(newer, decisions).either
      committed    <- f.store.taskRecord(f.identity.id)
      writeCount   <- attempts.get
      contactCount <- contacts.get
    yield assertTrue(
      read.contains(record),
      unchanged.contains(original),
      before == after,
      spoof.isLeft,
      missing.isLeft,
      reopened.contains(claimed),
      reused.isLeft,
      regressed.isLeft,
      changedCalls.isLeft,
      next.remoteApproval == SubagentRemoteApproval.Awaiting(newer, calls),
      uncertain == Left(uncertainFailure),
      retry.isLeft,
      writeCount == 1,
      contactCount == 0,
      committed.exists(
        _.remoteApproval == SubagentRemoteApproval.ConfirmationClaimed(newer, calls, decisions)
      )
    )

  private def staleConsent: ZIO[Scope, AgentError, TestResult] =
    for
      f        <- fixture
      contacts <- Ref.make(0)
      first <- f.store
        .taskRecord(f.identity.id)
        .someOrFail(AgentError.StorageError("Missing admission"))
      original <- f.store.applyRemoteObservation(
        first,
        SubagentRemoteObservation.Awaiting(f.generation, calls)
      )
      nextGeneration <- ZIO.fromEither(SubagentRemoteApprovalGeneration.fromLong(2L))
      changedCalls = calls.map(
        _.copy(input = Json.Obj("path" -> Json.Str("changed after consent")))
      )
      latest <- f.store.applyRemoteObservation(
        original,
        SubagentRemoteObservation.Awaiting(nextGeneration, changedCalls)
      )
      transport = new SubagentRemoteTransport:
        val endpoint: SubagentProtocolBaseUri = f.endpoint
        def observe(id: SubagentTaskId): ZIO[Scope, AgentError, SubagentRemoteObservation] =
          ZIO.succeed(SubagentRemoteObservation.Running)
        def cancel(id: SubagentTaskId): ZIO[Scope, AgentError, Unit] = ZIO.unit
        def confirm(
            id: SubagentTaskId,
            generation: SubagentRemoteApprovalGeneration,
            input: Chunk[SubagentRemoteConfirmation]
        ): ZIO[Scope, AgentError, Unit] = contacts.update(_ + 1)
      task    <- SubagentRemoteTask.attach(f.store, f.identity, transport)
      outcome <- task.confirm(f.generation, decisions).either
      actual  <- f.store.taskRecord(f.identity.id)
      sent    <- contacts.get
    yield assertTrue(outcome.isLeft, actual.contains(latest), sent == 0)

  def spec: Spec[TestEnvironment & Scope, Any] = suite("SubagentRemoteTaskSpec")(
    test("attachment and complete approval claims preserve remote truth without resubmission"):
      for
        rows   <- ZIO.foreach(Chunk(Delivery.Confirmed, Delivery.Uncertain))(deliveryContract)
        forged <- forgedGeneration
        stale  <- staleConsent
      yield rows.foldLeft(forged && stale)(_ && _)
    ,
    test(
      "bounded observation preserves cleanup and cancellation; schema migration cannot reset approval claims"
    ):
      for
        rows   <- ZIO.foreach(Chunk(Stop.Deadline, Stop.DeadlineClean, Stop.Caller))(stopContract)
        schema <- schemaContract
      yield rows.foldLeft(schema)(_ && _)
  ) @@ TestAspect.timeout(20.seconds)
