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
import java.time.Instant
import java.util.UUID
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

object SubagentTaskRecordSpec extends ZIOSpecDefault:
  private enum RecoveryRace:
    case Start, Cancellation, Terminal, Unrelated, Uncertain

  private enum HeartbeatRace:
    case Terminal, Cancellation

  private def countedClock(underlying: Clock, calls: Ref[Int]): Clock = new Clock:
    export underlying.{
      currentTime,
      currentDateTime,
      javaClock,
      localDateTime,
      nanoTime,
      scheduler,
      sleep
    }
    def instant(using Trace): UIO[Instant] = calls.update(_ + 1) *> underlying.instant

  def spec: Spec[TestEnvironment, Any] = suite("SubagentTaskRecordSpec")(
    test(
      "confirmed admissions retain exact identity and observed timestamps through start, terminal publication and acknowledgement; legacy data gains no invented times"
    ):
      for
        backend <- SubagentTerminalStoreSpec.fixture
        target  <- ZIO.fromEither(StorageAddress.from(Chunk("admission"), "records"))
        store    = SubagentTerminalStore.make(backend.store, target)
        id       = SubagentTaskId(new UUID(0L, 41L))
        legacyId = SubagentTaskId(new UUID(0L, 42L))
        worker <- ZIO.fromEither(SubagentWorkerId.from("host<&worker"))
        createdAt = Instant.parse("2026-10-08T00:00:00Z")
        _        <- TestClock.setTime(createdAt)
        admitted <- store.admit(id, Some(worker))
        identity <- admitted match
          case SubagentTaskAdmissionResult.Created(identity) => ZIO.succeed(identity)
          case other => ZIO.fail(AgentError.StorageError(s"Expected fresh admission: $other"))
        pending               <- store.taskRecord(id)
        terminalBefore        <- store.list
        acknowledgementBefore <- store.acknowledge(id)
        duplicate             <- store.admit(id, None)
        wrongIdentity         <- store.markRunning(identity.copy(workerId = None)).either
        _                     <- TestClock.adjust(2.seconds)
        _                     <- store.markRunning(identity)
        running               <- store.taskRecord(id)
        _                     <- TestClock.adjust(3.seconds)
        record = SubagentTerminalRecord(
          id,
          SubagentTerminalOutcome.Completed("exact output λ"),
          Some(worker)
        )
        _            <- store.publish(record)
        terminal     <- store.taskRecord(id)
        _            <- TestClock.adjust(1.second)
        _            <- store.acknowledge(id)
        acknowledged <- store.taskRecord(id)
        _            <- store.publish(record)
        unchanged    <- store.taskRecord(id)
        checkedAt = createdAt.minusSeconds(1L)
        _             <- TestClock.setTime(checkedAt)
        checked       <- store.markChecked(id)
        checkedRecord <- store.taskRecord(id)
        missingBefore <- backend.store.read(target)
        missingCheck  <- store.markChecked(legacyId)
        missingAfter  <- backend.store.read(target)
        revival       <- store.markRunning(identity).either
        unknown       <- store.markRunning(identity.copy(id = legacyId)).either
        legacy: SubagentTerminalOutcome = SubagentTerminalOutcome.Completed("legacy")
        legacyOutcome <- ZIO.fromEither(legacy.toJsonAST.left.map(AgentError.StorageError.apply))
        orderedTarget <- ZIO.fromEither(
          StorageAddress.from(Chunk("admission", "publication-order"), "records")
        )
        ordered = SubagentTerminalStore.make(backend.store, orderedTarget)
        first   = SubagentTerminalRecord(id, SubagentTerminalOutcome.Completed("first admitted"))
        second = SubagentTerminalRecord(
          legacyId,
          SubagentTerminalOutcome.Completed("second admitted")
        )
        _                <- ordered.admit(id)
        _                <- ordered.admit(legacyId)
        _                <- ordered.publish(second)
        _                <- ordered.publish(first)
        publicationOrder <- ordered.pending
        _                <- ordered.publish(second)
        repeatOrder      <- ordered.list
        migrations <- ZIO.foreach(Chunk(1, 2, 3)): version =>
          for
            legacyTarget <- ZIO.fromEither(
              StorageAddress.from(Chunk("admission", s"legacy$version"), "records")
            )
            payload = Json.Obj(
              "schemaVersion" -> Json.Num(version),
              "records" -> Json.Arr(
                Json.Obj("id" -> Json.Str(legacyId.value.toString), "outcome" -> legacyOutcome)
              )
            )
            _ <- backend.store.compareAndSet(legacyTarget, ExpectedRevision.Absent, payload)
            reopened = SubagentTerminalStore.make(backend.store, legacyTarget)
            before    <- backend.store.read(legacyTarget)
            records   <- reopened.taskRecords
            checked   <- reopened.markChecked(legacyId)
            afterRead <- backend.store.read(legacyTarget)
            _         <- reopened.acknowledge(legacyId)
            after     <- reopened.taskRecords
          yield (before == afterRead && checked.isEmpty, records, after)
      yield assertTrue(
        pending.contains(
          SubagentTaskRecord(
            id,
            Some(worker),
            SubagentTaskRecordState.Pending,
            Some(SubagentTaskMetadata(createdAt, createdAt, createdAt))
          )
        ),
        terminalBefore.isEmpty,
        acknowledgementBefore == SubagentTerminalAcknowledgeResult.Missing,
        duplicate match
          case SubagentTaskAdmissionResult.Existing(record) => pending.contains(record)
          case _                                            => false
        ,
        wrongIdentity.isLeft,
        running.exists(record =>
          record.state == SubagentTaskRecordState.Running && record.metadata.contains(
            SubagentTaskMetadata(createdAt, createdAt, createdAt.plusSeconds(2L))
          )
        ),
        terminal.exists(record =>
          record.terminal.contains(
            SubagentTerminalRecord(
              id,
              SubagentTerminalOutcome.Completed("exact output λ"),
              Some(worker)
            )
          ) && record.metadata.contains(
            SubagentTaskMetadata(createdAt, createdAt, createdAt.plusSeconds(5L))
          )
        ),
        acknowledged == unchanged,
        acknowledged.exists(_.metadata == terminal.flatMap(_.metadata)),
        checked.contains(SubagentTaskMetadata(createdAt, checkedAt, createdAt.plusSeconds(5L))),
        checkedRecord == acknowledged.map(_.copy(metadata = checked)),
        missingCheck.isEmpty,
        missingBefore == missingAfter,
        revival.isLeft,
        unknown.isLeft,
        publicationOrder == Chunk(second, first),
        repeatOrder == Chunk(second, first),
        migrations.forall:
          case (readOnly, records, after) =>
            readOnly && records == Chunk(
              SubagentTaskRecord(
                legacyId,
                None,
                SubagentTaskRecordState.Terminal(legacy, SubagentTerminalAcknowledgement.Pending),
                None
              )
            ) && after.size == 1 && after.forall(_.metadata.isEmpty)
      )
    ,
    test(
      "cancellation requests preserve exact records and first stamps, guard identity and never replay uncertain writes"
    ):
      for
        backend <- SubagentTerminalStoreSpec.fixture
        target  <- ZIO.fromEither(StorageAddress.from(Chunk("cancel-intent"), "records"))
        base      = SubagentTerminalStore.make(backend.store, target)
        id        = SubagentTaskId(new UUID(0L, 61L))
        missingId = SubagentTaskId(new UUID(0L, 62L))
        created   = Instant.parse("2026-10-08T01:00:00Z")
        _        <- TestClock.setTime(created)
        admitted <- base.admit(id)
        identity <- admitted match
          case SubagentTaskAdmissionResult.Created(value) => ZIO.succeed(value)
          case other =>
            ZIO.fail(AgentError.StorageError(s"Expected created cancellation fixture: $other"))
        original <- base.taskRecord(id)
        raw <- backend.store
          .read(target)
          .someOrFail(AgentError.StorageError("Missing cancellation fixture"))
        // Schema 5 lacks intent; its ORIGINAL representation must remain a read-only accepted input.
        schema5 = raw.value match
          case Json.Obj(fields) =>
            Json.Obj(fields.map:
              case ("schemaVersion", _) => "schemaVersion" -> Json.Num(5)
              case ("records", Json.Arr(records)) =>
                "records" -> Json.Arr(records.map:
                  case Json.Obj(entry) => Json.Obj(entry.filterNot(_._1 == "cancellationIntent"))
                  case other           => other)
              case pair => pair)
          case other => other
        _           <- backend.values.update(_.updated(target, raw.copy(value = schema5)))
        legacy      <- base.taskRecord(id)
        legacyAfter <- backend.store.read(target)
        invalidId   <- base.requestCancellation(id, Some(identity.copy(id = missingId))).either
        invalidIdentity <- base
          .requestCancellation(id, Some(identity.copy(createdAt = created.plusSeconds(1L))))
          .either
        afterInvalid <- backend.store.read(target)
        missing      <- base.requestCancellation(missingId)
        attempts     <- Ref.make(0)
        conflict = new VersionedJsonStore:
          def read(address: StorageAddress): IO[AgentError, Option[VersionedJson]] =
            backend.store.read(address)
          def compareAndSet(
              address: StorageAddress,
              expected: ExpectedRevision,
              value: Json
          ): IO[AgentError, ConditionalWrite] =
            attempts
              .updateAndGet(_ + 1)
              .flatMap:
                case 1 => TestClock.adjust(1.hour).as(ConditionalWrite.Conflict)
                case _ => backend.store.compareAndSet(address, expected, value)
        at <- Clock.instant
        requested <- SubagentTerminalStore
          .make(conflict, target)
          .requestCancellation(id, Some(identity))
        count        <- attempts.get
        afterRequest <- base.taskRecord(id)
        deniedStart  <- base.confirmStart(identity)
        _            <- TestClock.adjust(1.hour)
        repeated     <- base.requestCancellation(id)
        terminal = SubagentTerminalRecord(
          id,
          SubagentTerminalOutcome.Completed("actual completion")
        )
        _            <- base.publish(terminal)
        _            <- base.acknowledge(id)
        beforeRepeat <- base.taskRecord(id)
        late         <- base.requestCancellation(id)
        afterRepeat  <- base.taskRecord(id)
        _            <- base.admit(missingId)
        uncertainty = AgentError.StorageError("Cancellation receipt lost")
        uncertainAttempts <- Ref.make(0)
        uncertain = new VersionedJsonStore:
          def read(address: StorageAddress): IO[AgentError, Option[VersionedJson]] =
            backend.store.read(address)
          def compareAndSet(
              address: StorageAddress,
              expected: ExpectedRevision,
              value: Json
          ): IO[AgentError, ConditionalWrite] =
            uncertainAttempts.update(_ + 1) *> backend.store
              .compareAndSet(address, expected, value) *> ZIO.fail(uncertainty)
        lost <- SubagentTerminalStore.make(uncertain, target).requestCancellation(missingId).exit
        uncertainCount <- uncertainAttempts.get
        recovered      <- base.taskRecord(missingId)
        boundedTarget <- ZIO.fromEither(
          StorageAddress.from(Chunk("cancel-intent", "bounded"), "records")
        )
        _ <- backend.values.update(_.updated(boundedTarget, raw))
        limits <- ZIO.fromEither(
          SubagentTerminalStore.Limits
            .make(256, raw.value.toJson.getBytes(java.nio.charset.StandardCharsets.UTF_8).length, 8)
        )
        bounded = SubagentTerminalStore.make(backend.store, boundedTarget, limits)
        boundedReadable <- bounded.taskRecord(id)
        oversized       <- bounded.requestCancellation(id).either
        boundedAfter    <- backend.store.read(boundedTarget)
      yield assertTrue(
        legacy == original,
        legacyAfter.contains(raw.copy(value = schema5)),
        invalidId.isLeft,
        invalidIdentity.isLeft,
        afterInvalid == legacyAfter,
        missing == SubagentCancellationRequestResult.Missing,
        count == 2,
        afterRequest == original.map(
          _.copy(cancellationIntent = SubagentCancellationIntent.Requested(at))
        ),
        requested == afterRequest.fold[SubagentCancellationRequestResult](
          SubagentCancellationRequestResult.Missing
        )(SubagentCancellationRequestResult.Requested(_)),
        repeated == afterRequest.fold[SubagentCancellationRequestResult](
          SubagentCancellationRequestResult.Missing
        )(SubagentCancellationRequestResult.AlreadyRequested(_)),
        deniedStart == SubagentTaskStartResult.CancellationRequested,
        late == beforeRepeat.fold[SubagentCancellationRequestResult](
          SubagentCancellationRequestResult.Missing
        )(SubagentCancellationRequestResult.AlreadyTerminal(_)),
        afterRepeat == beforeRepeat,
        lost == Exit.fail(uncertainty),
        uncertainCount == 1,
        recovered.exists(_.cancellationIntent != SubagentCancellationIntent.NotRequested),
        boundedReadable == original,
        oversized.isLeft,
        boundedAfter.contains(raw)
      )
    ,
    test(
      "check-time retries retain one candidate and concurrent metadata; uncertain writes are not replayed"
    ):
      for
        backend <- SubagentTerminalStoreSpec.fixture
        target  <- ZIO.fromEither(StorageAddress.from(Chunk("checked"), "records"))
        base    = SubagentTerminalStore.make(backend.store, target)
        id      = SubagentTaskId(new UUID(0L, 51L))
        other   = SubagentTaskId(new UUID(0L, 52L))
        created = Instant.parse("2026-10-08T00:00:00Z")
        _ <- TestClock.setTime(created)
        _ <- base.admit(id)
        _ <- base.admit(other)
        _ <- base.publish(SubagentTerminalRecord(other, SubagentTerminalOutcome.Completed("other")))
        before   <- base.taskRecord(id)
        attempts <- Ref.make(0)
        conflict = new VersionedJsonStore:
          def read(address: StorageAddress): IO[AgentError, Option[VersionedJson]] =
            backend.store.read(address)
          def compareAndSet(
              address: StorageAddress,
              expected: ExpectedRevision,
              value: Json
          ): IO[AgentError, ConditionalWrite] =
            attempts
              .updateAndGet(_ + 1)
              .flatMap:
                case 1 =>
                  base.acknowledge(other) *> TestClock
                    .adjust(10.seconds)
                    .as(ConditionalWrite.Conflict)
                case _ => backend.store.compareAndSet(address, expected, value)
        _                 <- TestClock.adjust(1.second)
        checked           <- SubagentTerminalStore.make(conflict, target).markChecked(id)
        after             <- base.taskRecord(id)
        concurrent        <- base.acknowledgement(other)
        count             <- attempts.get
        uncertainAttempts <- Ref.make(0)
        uncertain = new VersionedJsonStore:
          def read(address: StorageAddress): IO[AgentError, Option[VersionedJson]] =
            backend.store.read(address)
          def compareAndSet(
              address: StorageAddress,
              expected: ExpectedRevision,
              value: Json
          ): IO[AgentError, ConditionalWrite] =
            uncertainAttempts
              .update(_ + 1) *> backend.store.compareAndSet(address, expected, value) *>
              ZIO.fail(AgentError.StorageError("Check receipt lost"))
        lost      <- SubagentTerminalStore.make(uncertain, target).markChecked(id).either
        lostCount <- uncertainAttempts.get
        persisted <- base.taskRecord(id)
      yield assertTrue(
        count == 2,
        checked == before.flatMap(_.metadata).map(_.copy(lastCheckedAt = created.plusSeconds(1L))),
        after == before.map(_.copy(metadata = checked)),
        concurrent.contains(SubagentTerminalAcknowledgement.Acknowledged(created.plusSeconds(1L))),
        lost == Left(AgentError.StorageError("Check receipt lost")),
        lostCount == 1,
        persisted == before.map(
          _.copy(metadata =
            before.flatMap(_.metadata).map(_.copy(lastCheckedAt = created.plusSeconds(11L)))
          )
        )
      )
    ,
    test(
      "heartbeat refreshes only the exact running admission, preserves concurrent fields and never retries uncertain writes"
    ):
      for
        backend    <- SubagentTerminalStoreSpec.fixture
        target     <- ZIO.fromEither(StorageAddress.from(Chunk("heartbeat"), "records"))
        clock      <- ZIO.clock
        clockCalls <- Ref.make(0)
        observed = countedClock(clock, clockCalls)
        base     = SubagentTerminalStore.make(backend.store, target)
        id       = SubagentTaskId(new UUID(0L, 71L))
        created  = Instant.parse("2026-10-08T01:00:00Z")
        identity = SubagentTaskAdmissionIdentity(id, None, created)
        _                 <- TestClock.setTime(created)
        _                 <- base.admit(id)
        pendingBefore     <- backend.store.read(target)
        pendingRecord     <- base.taskRecord(id)
        pending           <- base.refreshHeartbeat(identity).withClock(observed)
        pendingAfter      <- backend.store.read(target)
        pendingClockCalls <- clockCalls.get
        _                 <- base.markRunning(identity)
        _                 <- base.markChecked(id)
        running           <- base.taskRecord(id)
        _                 <- TestClock.adjust(5.seconds)
        refreshed         <- base.refreshHeartbeat(identity).withClock(observed)
        forward           <- base.taskRecord(id)
        _                 <- TestClock.setTime(created.minusSeconds(1L))
        _                 <- base.refreshHeartbeat(identity).withClock(observed)
        backward          <- base.taskRecord(id)
        invalid <- base
          .refreshHeartbeat(identity.copy(createdAt = created.plusSeconds(1L)))
          .withClock(observed)
          .either
        wrongWorker <- ZIO.fromEither(SubagentWorkerId.from("wrong-worker"))
        invalidWorker <- base
          .refreshHeartbeat(identity.copy(workerId = Some(wrongWorker)))
          .withClock(observed)
          .either
        missing <- base
          .refreshHeartbeat(identity.copy(id = SubagentTaskId(new UUID(0L, 999L))))
          .withClock(observed)
          .either
        _        <- TestClock.setTime(created.plusSeconds(10L))
        attempts <- Ref.make(0)
        conflict = new VersionedJsonStore:
          def read(address: StorageAddress): IO[AgentError, Option[VersionedJson]] =
            backend.store.read(address)
          def compareAndSet(
              address: StorageAddress,
              expected: ExpectedRevision,
              value: Json
          ): IO[AgentError, ConditionalWrite] =
            attempts
              .updateAndGet(_ + 1)
              .flatMap:
                case 1 =>
                  TestClock.adjust(2.seconds) *> base.markChecked(id).withClock(clock) *> TestClock
                    .adjust(8.seconds)
                    .as(ConditionalWrite.Conflict)
                case _ => backend.store.compareAndSet(address, expected, value)
        _ <- SubagentTerminalStore
          .make(conflict, target)
          .refreshHeartbeat(identity)
          .withClock(observed)
        afterConflict     <- base.taskRecord(id)
        count             <- attempts.get
        uncertainAttempts <- Ref.make(0)
        lost = AgentError.StorageError("Heartbeat receipt lost")
        uncertain = new VersionedJsonStore:
          def read(address: StorageAddress): IO[AgentError, Option[VersionedJson]] =
            backend.store.read(address)
          def compareAndSet(
              address: StorageAddress,
              expected: ExpectedRevision,
              value: Json
          ): IO[AgentError, ConditionalWrite] =
            uncertainAttempts.update(_ + 1) *> backend.store
              .compareAndSet(address, expected, value) *> ZIO.fail(lost)
        uncertainResult <- SubagentTerminalStore
          .make(uncertain, target)
          .refreshHeartbeat(identity)
          .withClock(observed)
          .either
        uncertainCount <- uncertainAttempts.get
        persisted      <- base.taskRecord(id)
        raceChecks <- ZIO.foreach(Chunk(HeartbeatRace.Terminal, HeartbeatRace.Cancellation)):
          mode =>
            for
              raceAttempts <- Ref.make(0)
              raceId = SubagentTaskId(
                new UUID(0L, if mode == HeartbeatRace.Cancellation then 73L else 72L)
              )
              raceCreated <- Clock.instant
              raceIdentity = SubagentTaskAdmissionIdentity(raceId, None, raceCreated)
              _ <- base.admit(raceId)
              _ <- base.markRunning(raceIdentity)
              race = new VersionedJsonStore:
                def read(address: StorageAddress): IO[AgentError, Option[VersionedJson]] =
                  backend.store.read(address)
                def compareAndSet(
                    address: StorageAddress,
                    expected: ExpectedRevision,
                    value: Json
                ): IO[AgentError, ConditionalWrite] =
                  raceAttempts.update(
                    _ + 1
                  ) *> (if mode == HeartbeatRace.Cancellation then
                          base.requestCancellation(raceId).withClock(clock).unit
                        else
                          base
                            .publish(
                              SubagentTerminalRecord(
                                raceId,
                                SubagentTerminalOutcome.Completed("exact")
                              )
                            )
                            .withClock(clock) *> base.acknowledge(raceId).withClock(clock).unit
                  ).as(ConditionalWrite.Conflict)
              result <- SubagentTerminalStore
                .make(race, target)
                .refreshHeartbeat(raceIdentity)
                .withClock(observed)
              beforeNoop <- backend.store.read(target)
              repeated   <- base.refreshHeartbeat(raceIdentity).withClock(observed)
              afterNoop  <- backend.store.read(target)
              stored     <- base.taskRecord(raceId)
              raceCount  <- raceAttempts.get
            yield assertTrue(
              stored.exists(record =>
                result == (mode match
                  case HeartbeatRace.Cancellation =>
                    SubagentTaskHeartbeatResult.CancellationRequested(record)
                  case HeartbeatRace.Terminal =>
                    SubagentTaskHeartbeatResult.AlreadyTerminal(record))
              ),
              repeated == result,
              beforeNoop == afterNoop,
              raceCount == 1
            )
        totalClockCalls <- clockCalls.get
      yield assertTrue(
        pendingClockCalls == 0,
        totalClockCalls == 6,
        invalidWorker == invalid,
        pendingRecord.exists(record => pending == SubagentTaskHeartbeatResult.NotRunning(record)),
        pendingBefore == pendingAfter,
        forward.exists(record => refreshed == SubagentTaskHeartbeatResult.Refreshed(record)),
        forward == running.map(record =>
          record.copy(metadata =
            record.metadata.map(_.copy(lastUpdatedAt = created.plusSeconds(5L)))
          )
        ),
        backward == running.map(record =>
          record.copy(metadata =
            record.metadata.map(_.copy(lastUpdatedAt = created.minusSeconds(1L)))
          )
        ),
        invalid == Left(
          AgentError.StorageError("Task heartbeat does not match a confirmed durable admission")
        ),
        missing == invalid,
        count == 2,
        afterConflict == running.map(record =>
          record.copy(metadata =
            record.metadata.map(
              _.copy(
                lastCheckedAt = created.plusSeconds(12L),
                lastUpdatedAt = created.plusSeconds(10L)
              )
            )
          )
        ),
        uncertainResult == Left(lost),
        uncertainCount == 1,
        persisted == afterConflict.map(record =>
          record.copy(metadata =
            record.metadata.map(_.copy(lastUpdatedAt = created.plusSeconds(20L)))
          )
        )
      ) && raceChecks.foldLeft(assertTrue(true))(_ && _)
    ,
    test(
      "recovery compares the whole admitted record and preserves terminal truth, metadata, intent and publication order"
    ):
      for
        backend <- SubagentTerminalStoreSpec.fixture
        target  <- ZIO.fromEither(StorageAddress.from(Chunk("recovery"), "conditional"))
        store     = SubagentTerminalStore.make(backend.store, target)
        one       = SubagentTaskId(new UUID(0L, 601L))
        two       = SubagentTaskId(new UUID(0L, 602L))
        missingId = SubagentTaskId(new UUID(0L, 603L))
        created   = Instant.parse("2026-10-08T00:00:00Z")
        _       <- TestClock.setTime(created)
        _       <- store.admit(one)
        _       <- store.admit(two)
        initial <- store.taskRecord(one).someOrFail(AgentError.StorageError("Missing admission"))
        permit  <- ZIO.fromEither(SubagentTaskRecovery.Permit.local(initial))
        _       <- store.requestCancellation(one)
        changedRecord <- store.taskRecord(one)
        changed       <- store.reconcileAbandoned(permit, created.plusSeconds(5L))
        requested <- ZIO
          .fromOption(changedRecord)
          .orElseFail(AgentError.StorageError("Missing requested record"))
        requestedPermit <- ZIO.fromEither(SubagentTaskRecovery.Permit.local(requested))
        recovered       <- store.reconcileAbandoned(requestedPermit, created.plusSeconds(5L))
        after           <- store.taskRecord(one)
        pending         <- store.pending
        _               <- store.acknowledge(one)
        beforeRepeat    <- backend.store.read(target)
        repeat          <- store.reconcileAbandoned(requestedPermit, created.plusSeconds(9L))
        afterRepeat     <- backend.store.read(target)
        missingPermit <- ZIO.fromEither(
          SubagentTaskRecovery.Permit.local(initial.copy(id = missingId))
        )
        missing <- store.reconcileAbandoned(missingPermit, created)
        current <- store.taskRecord(one)
        raceChecks <- ZIO.foreach(Chunk.fromIterable(RecoveryRace.values)): mode =>
          for
            raced <- SubagentTerminalStoreSpec.fixture
            fresh = SubagentTerminalStore.make(raced.store, target)
            admitted <- fresh.admit(one)
            identity <- admitted match
              case SubagentTaskAdmissionResult.Created(value) => ZIO.succeed(value)
              case other => ZIO.fail(AgentError.StorageError(s"Unexpected admission: $other"))
            before <- fresh
              .taskRecord(one)
              .someOrFail(AgentError.StorageError("Missing race admission"))
            claim <- ZIO.fromEither(SubagentTaskRecovery.Permit.local(before))
            calls <- Ref.make(0)
            lost = AgentError.StorageError("Recovery CAS receipt lost")
            racing = new VersionedJsonStore:
              def read(address: StorageAddress): IO[AgentError, Option[VersionedJson]] =
                raced.store.read(address)
              def compareAndSet(
                  address: StorageAddress,
                  expected: ExpectedRevision,
                  value: Json
              ): IO[AgentError, ConditionalWrite] =
                calls
                  .updateAndGet(_ + 1)
                  .flatMap:
                    case 1 =>
                      mode match
                        case RecoveryRace.Start =>
                          fresh.markRunning(identity).as(ConditionalWrite.Conflict)
                        case RecoveryRace.Cancellation =>
                          fresh.requestCancellation(one).as(ConditionalWrite.Conflict)
                        case RecoveryRace.Terminal =>
                          fresh
                            .publish(
                              SubagentTerminalRecord(
                                one,
                                SubagentTerminalOutcome.Completed("winner")
                              )
                            )
                            .as(ConditionalWrite.Conflict)
                        case RecoveryRace.Unrelated =>
                          fresh.admit(two).as(ConditionalWrite.Conflict)
                        case RecoveryRace.Uncertain =>
                          raced.store.compareAndSet(address, expected, value) *> ZIO.fail(lost)
                    case _ => raced.store.compareAndSet(address, expected, value)
            result <- SubagentTerminalStore
              .make(racing, target)
              .reconcileAbandoned(claim, created.plusSeconds(7L))
              .either
            current <- fresh.taskRecord(one)
            count   <- calls.get
          yield mode match
            case RecoveryRace.Start | RecoveryRace.Cancellation | RecoveryRace.Terminal =>
              assertTrue(
                current.exists(record =>
                  result == Right(SubagentTaskRecovery.Transition.Changed(record))
                ),
                count == 1
              )
            case RecoveryRace.Unrelated =>
              assertTrue(
                result match
                  case Right(SubagentTaskRecovery.Transition.Reconciled(record, _)) =>
                    current.contains(record) && record.metadata.exists(
                      _.lastUpdatedAt == created.plusSeconds(7L)
                    )
                  case _ => false,
                count == 2
              )
            case RecoveryRace.Uncertain =>
              assertTrue(result == Left(lost), count == 1, current.exists(_.terminal.nonEmpty))
        outcome = SubagentTerminalOutcome.Failed(
          Chunk(SubagentTaskFailure("executor lost", None)),
          SubagentTaskDefects.Absent
        )
        expected = requested.copy(
          state =
            SubagentTaskRecordState.Terminal(outcome, SubagentTerminalAcknowledgement.Pending),
          metadata = requested.metadata.map(_.copy(lastUpdatedAt = created.plusSeconds(5L)))
        )
      yield assertTrue(
        changedRecord.exists(record => changed == SubagentTaskRecovery.Transition.Changed(record)),
        after.contains(expected),
        recovered match
          case SubagentTaskRecovery.Transition.Reconciled(record, _) => record == expected
          case _                                                     => false,
        pending == Chunk(SubagentTerminalRecord(one, outcome)),
        current.exists(record => repeat == SubagentTaskRecovery.Transition.Changed(record)),
        beforeRepeat == afterRepeat,
        missing == SubagentTaskRecovery.Transition.Missing
      ) && raceChecks.foldLeft(assertTrue(true))(_ && _)
  )
