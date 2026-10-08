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
  )
