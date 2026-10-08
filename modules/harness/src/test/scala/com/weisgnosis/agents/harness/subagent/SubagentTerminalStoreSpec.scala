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
import com.weisgnosis.agents.harness.storage.*
import java.util.UUID
import java.nio.charset.StandardCharsets
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

object SubagentTerminalStoreSpec extends ZIOSpecDefault:
  private val firstId: SubagentTaskId  = SubagentTaskId(new UUID(0L, 1L))
  private val secondId: SubagentTaskId = SubagentTaskId(new UUID(0L, 2L))

  final private[subagent] case class Fixture(
      store: VersionedJsonStore,
      values: Ref[Map[StorageAddress, VersionedJson]],
      writes: Ref[Int]
  )

  private[subagent] def fixture: UIO[Fixture] =
    for
      values <- Ref.make(Map.empty[StorageAddress, VersionedJson])
      writes <- Ref.make(0)
      store = new VersionedJsonStore:
        def read(address: StorageAddress): IO[AgentError, Option[VersionedJson]] =
          values.get.map(_.get(address))
        def compareAndSet(
            address: StorageAddress,
            expected: ExpectedRevision,
            value: Json
        ): IO[AgentError, ConditionalWrite] =
          for
            _ <- writes.update(_ + 1)
            result <- values.modify[Either[AgentError, ConditionalWrite]]: records =>
              val existing = records.get(address)
              val matches = existing.fold(expected == ExpectedRevision.Absent)(record =>
                expected == ExpectedRevision.AtRevision(record.revision)
              )
              if !matches then (Right(ConditionalWrite.Conflict), records)
              else
                val current = existing.fold(0L)(_.revision.value)
                val next =
                  if current == Long.MaxValue then
                    Left(AgentError.StorageError("Fixture revision exhausted"))
                  else StorageRevision.fromLong(current + 1L)
                next match
                  case Left(error) => (Left(error), records)
                  case Right(revision) =>
                    (
                      Right(ConditionalWrite.Applied(revision)),
                      records.updated(address, VersionedJson(value, revision))
                    )
            applied <- ZIO.fromEither(result)
          yield applied
    yield Fixture(store, values, writes)

  private def address(owner: String): IO[AgentError, StorageAddress] =
    ZIO.fromEither(StorageAddress.from(Chunk("terminal-fixture", owner), "tasks"))

  private def accepted(
      admission: TaskAdmission[AgentResult]
  ): IO[AgentError, SupervisedTask[AgentResult]] = admission match
    case TaskAdmission.Accepted(task) => ZIO.succeed(task)
    case TaskAdmission.Closed =>
      ZIO.fail(AgentError.InvalidConfiguration("Fixture supervisor closed"))

  def spec: Spec[TestEnvironment & Scope, Any] = suite("SubagentTerminalStoreSpec")(
    test(
      "publication waits for joined cleanup and exact terminal observations reopen in one owner partition"
    ):
      ZIO.scoped:
        for
          backend <- fixture
          target  <- address("owner-a")
          other   <- address("owner-b")
          store = SubagentTerminalStore.make(backend.store, target)
          worker     <- ZIO.fromEither(SubagentWorkerId.from("joined worker λ"))
          supervisor <- TaskSupervisor.open[AgentResult]
          cleanup    <- Promise.make[Nothing, Unit]
          release    <- Promise.make[Nothing, Unit]
          reply = Msg.assistant(" exact output \n" + "x" * 5000)
          admission <- supervisor.attach(
            ZIO.acquireRelease(ZIO.unit)(_ => cleanup.succeed(()).unit *> release.await) *> ZIO
              .succeed(AgentResult(reply, Chunk(reply), 1))
          )
          task <- accepted(admission)
          _    <- ZIO.addFinalizer(release.succeed(()).unit)
          publishing <- ZIO.uninterruptible:
            for
              fiber <- store.publishJoined(firstId, task, Some(worker)).interruptible.forkScoped
              _     <- ZIO.addFinalizer(release.succeed(()).unit)
            yield fiber
          _             <- cleanup.await
          abandoned     <- store.publishJoined(secondId, task).forkScoped
          _             <- (ZIO.yieldNow *> abandoned.status).repeatUntil(_.isSuspended)
          abandonedExit <- abandoned.interrupt
          ownedTask     <- task.poll
          unpublished   <- store.read(secondId)
          before        <- store.read(firstId)
          pending       <- publishing.poll
          writesBefore  <- backend.writes.get
          _             <- release.succeed(())
          receipt       <- publishing.join
          reopened      <- SubagentTerminalStore.make(backend.store, target).read(firstId)
          isolated      <- SubagentTerminalStore.make(backend.store, other).list
          failedAdmission <- supervisor.attach(
            ZIO.fail(AgentError.StorageError("execution failure"))
          )
          failedTask <- accepted(failedAdmission)
          _          <- store.publishJoined(secondId, failedTask)
          records    <- store.list
        yield assertTrue(
          before.isEmpty,
          pending.isEmpty,
          writesBefore == 0,
          abandonedExit.isInterrupted,
          ownedTask.isEmpty,
          unpublished.isEmpty,
          receipt match
            case SubagentTerminalPublication.Stored(_)        => true
            case SubagentTerminalPublication.AlreadyStored(_) => false
          ,
          reopened.contains(
            SubagentTerminalRecord(
              firstId,
              SubagentTerminalOutcome.Completed(reply.text),
              Some(worker)
            )
          ),
          isolated.isEmpty,
          records.map(_.id) == Chunk(firstId, secondId),
          records.lastOption.exists(
            _.outcome == SubagentTerminalOutcome.Failed(
              Chunk("execution failure"),
              SubagentTaskDefects.Absent
            )
          )
        )
    ,
    test(
      "idempotent and concurrent terminal writes preserve the first observation and never retry uncertain acknowledgements"
    ):
      for
        backend <- fixture
        target  <- address("races")
        first  = SubagentTerminalRecord(firstId, SubagentTerminalOutcome.Completed("first"))
        second = SubagentTerminalRecord(secondId, SubagentTerminalOutcome.Completed("second"))
        stores = Chunk(
          SubagentTerminalStore.make(backend.store, target),
          SubagentTerminalStore.make(backend.store, target)
        )
        receipts <- ZIO.foreachPar(stores)(_.publish(first))
        _ <- stores.headOption.fold[IO[AgentError, SubagentTerminalPublication]](
          ZIO.fail(AgentError.StorageError("Missing fixture"))
        )(_.publish(second))
        conflict <- SubagentTerminalStore
          .make(backend.store, target)
          .publish(first.copy(outcome = SubagentTerminalOutcome.Completed("replacement")))
          .exit
        records         <- SubagentTerminalStore.make(backend.store, target).list
        uncertainTarget <- address("uncertain")
        attempts        <- Ref.make(0)
        uncertain = new VersionedJsonStore:
          def read(address: StorageAddress): IO[AgentError, Option[VersionedJson]] =
            backend.store.read(address)
          def compareAndSet(
              address: StorageAddress,
              expected: ExpectedRevision,
              value: Json
          ): IO[AgentError, ConditionalWrite] =
            attempts.update(_ + 1) *> backend.store.compareAndSet(address, expected, value) *> ZIO
              .fail(AgentError.StorageError("Acknowledgement lost"))
        lost      <- SubagentTerminalStore.make(uncertain, uncertainTarget).publish(first).exit
        calls     <- attempts.get
        recovered <- SubagentTerminalStore.make(backend.store, uncertainTarget).read(firstId)
      yield assertTrue(
        receipts.count(publication =>
          publication match
            case SubagentTerminalPublication.Stored(_)        => true
            case SubagentTerminalPublication.AlreadyStored(_) => false
        ) == 1,
        receipts.count(publication =>
          publication match
            case SubagentTerminalPublication.AlreadyStored(_) => true
            case SubagentTerminalPublication.Stored(_)        => false
        ) == 1,
        conflict.isFailure,
        records == Chunk(first, second),
        lost.isFailure,
        calls == 1,
        recovered.contains(first)
      )
    ,
    test(
      "strict aggregates and limits reject corruption; only confirmed conflicts retry and cancellation publishes nothing"
    ):
      ZIO.scoped:
        for
          backend <- fixture
          target  <- address("strict")
          store  = SubagentTerminalStore.make(backend.store, target)
          record = SubagentTerminalRecord(firstId, SubagentTerminalOutcome.Completed("é"))
          _ <- store.publish(record)
          stored <- backend.store
            .read(target)
            .someOrFail(AgentError.StorageError("Missing fixture aggregate"))
          corruptions <- stored.value match
            case Json.Obj(fields) =>
              fields.find(_._1 == "records").map(_._2) match
                case Some(Json.Arr(entries)) =>
                  val malformedStamps =
                    Chunk(Json.Str("invalid-instant"), Json.Null, Json.Num(1)).map(stamp =>
                      Json.Obj(
                        "schemaVersion" -> Json.Num(2),
                        "records" -> Json.Arr(
                          entries.map(entry =>
                            entry match
                              case Json.Obj(values) =>
                                Json.Obj(values :+ ("acknowledgedAt" -> stamp))
                              case other => other
                          )
                        )
                      )
                    )
                  ZIO.succeed(
                    Chunk(
                      Json.Obj(fields :+ ("unknown" -> Json.Bool(true))),
                      Json.Obj(
                        fields.map((key, value) =>
                          key -> (if key == "schemaVersion" then Json.Num(4) else value)
                        )
                      ),
                      Json.Obj(
                        fields.map((key, value) =>
                          key -> (if key == "records" then Json.Arr(entries ++ entries) else value)
                        )
                      ),
                      Json.Obj("schemaVersion" -> Json.Num(1), "records" -> Json.Str("invalid"))
                    ) ++ malformedStamps ++ Chunk("", " " * 2, "w" * 1025).map(identity =>
                      Json.Obj(
                        "schemaVersion" -> Json.Num(3),
                        "records" -> Json.Arr(entries.map:
                          case Json.Obj(values) =>
                            Json.Obj(values :+ ("workerId" -> Json.Str(identity)))
                          case other => other)
                      )
                    )
                  )
                case _ => ZIO.fail(AgentError.StorageError("Missing fixture records"))
            case _ => ZIO.fail(AgentError.StorageError("Missing fixture object"))
          corrupt <- ZIO.foreach(corruptions): value =>
            backend.values.set(Map(target -> stored.copy(value = value))) *> store.list.either
          _           <- backend.values.set(Map(target -> stored))
          recordLimit <- ZIO.fromEither(SubagentTerminalStore.Limits.make(1, 1048576, 2))
          byteLimit   <- ZIO.fromEither(SubagentTerminalStore.Limits.make(4, 1, 2))
          rejectedRecord <- SubagentTerminalStore
            .make(backend.store, target, recordLimit)
            .publish(SubagentTerminalRecord(secondId, SubagentTerminalOutcome.Completed("second")))
            .either
          rejectedBytes <- SubagentTerminalStore.make(backend.store, target, byteLimit).list.either
          invalidUnicode <- store
            .publish(SubagentTerminalRecord(secondId, SubagentTerminalOutcome.Completed("\uD800")))
            .either
          unchanged          <- store.list
          writesAfterInvalid <- backend.writes.get
          conflicts          <- Ref.make(0)
          conflictOnly = new VersionedJsonStore:
            def read(address: StorageAddress): IO[AgentError, Option[VersionedJson]] = ZIO.none
            def compareAndSet(
                address: StorageAddress,
                expected: ExpectedRevision,
                value: Json
            ): IO[AgentError, ConditionalWrite] =
              conflicts.update(_ + 1).as(ConditionalWrite.Conflict)
          exhausted <- SubagentTerminalStore
            .make(conflictOnly, target, recordLimit)
            .publish(record)
            .either
          conflictCalls  <- conflicts.get
          entered        <- Promise.make[Nothing, Unit]
          cancelAttempts <- Ref.make(0)
          cancelledBackend = new VersionedJsonStore:
            def read(address: StorageAddress): IO[AgentError, Option[VersionedJson]] = ZIO.none
            def compareAndSet(
                address: StorageAddress,
                expected: ExpectedRevision,
                value: Json
            ): IO[AgentError, ConditionalWrite] =
              cancelAttempts.update(_ + 1) *> entered.succeed(()) *> ZIO.never
          publishing <- SubagentTerminalStore
            .make(cancelledBackend, target)
            .publish(record)
            .forkScoped
          _            <- entered.await
          cancelled    <- publishing.interrupt
          cancelCalls  <- cancelAttempts.get
          finalRecords <- store.list
        yield assertTrue(
          corrupt.forall(_.isLeft),
          rejectedRecord.isLeft,
          rejectedBytes.isLeft,
          invalidUnicode.isLeft,
          unchanged == Chunk(record),
          writesAfterInvalid == 1,
          exhausted.isLeft,
          conflictCalls == 3,
          cancelled.isInterrupted,
          cancelCalls == 1,
          finalRecords == Chunk(record),
          SubagentTerminalStore.Limits.make(0, 1, 0).isLeft,
          SubagentTerminalStore.Limits.make(1, 0, 0).isLeft,
          SubagentTerminalStore.Limits.make(1, 1, -1).isLeft,
          SubagentTerminalStore.Limits.make(1, 1, 0).isRight
        )
    ,
    test(
      "legacy observations migrate without invented dates and first acknowledgement survives reopen and publication"
    ):
      for
        backend  <- fixture
        target   <- address("legacy-ack")
        revision <- ZIO.fromEither(StorageRevision.fromLong(1L))
        first = SubagentTerminalRecord(firstId, SubagentTerminalOutcome.Completed("exact λ"))
        worker <- ZIO.fromEither(SubagentWorkerId.from("metadata λ"))
        second = SubagentTerminalRecord(
          secondId,
          SubagentTerminalOutcome.Failed(Chunk("failed"), SubagentTaskDefects.Absent),
          Some(worker)
        )
        outcome <- ZIO.fromEither(first.outcome.toJsonAST.left.map(AgentError.StorageError(_)))
        legacy = Json.Obj(
          "schemaVersion" -> Json.Num(1),
          "records" -> Json.Arr(
            Json.Obj("id" -> Json.Str(firstId.value.toString), "outcome" -> outcome)
          )
        )
        _ <- backend.values.set(Map(target -> VersionedJson(legacy, revision)))
        store = SubagentTerminalStore.make(backend.store, target)
        before        <- store.pending
        unstamped     <- store.acknowledgement(firstId)
        missing       <- store.acknowledge(secondId)
        unchanged     <- backend.store.read(target)
        noBackfill    <- store.publish(first.copy(workerId = Some(worker))).either
        schema2Target <- address("schema2-worker")
        schema2 = Json.Obj(
          "schemaVersion" -> Json.Num(2),
          "records" -> Json.Arr(
            Json.Obj("id" -> Json.Str(firstId.value.toString), "outcome" -> outcome)
          )
        )
        _ <- backend.values.update(_.updated(schema2Target, VersionedJson(schema2, revision)))
        schema2Store = SubagentTerminalStore.make(backend.store, schema2Target)
        schema2Read      <- schema2Store.list
        schema2Unchanged <- backend.store.read(schema2Target)
        schema2Ack       <- schema2Store.acknowledge(firstId)
        schema2Reopened  <- schema2Store.list
        boundedTarget    <- address("ack-byte-limit")
        _                <- SubagentTerminalStore.make(backend.store, boundedTarget).publish(first)
        boundedBefore <- backend.store
          .read(boundedTarget)
          .someOrFail(AgentError.StorageError("Missing bounded fixture aggregate"))
        limits <- ZIO.fromEither(
          SubagentTerminalStore.Limits.make(
            256,
            boundedBefore.value.toJson.getBytes(StandardCharsets.UTF_8).length,
            8
          )
        )
        bounded = SubagentTerminalStore.make(backend.store, boundedTarget, limits)
        boundedPending      <- bounded.pending
        oversizedAck        <- bounded.acknowledge(firstId).either
        longWorker          <- ZIO.fromEither(SubagentWorkerId.from("w" * 1024))
        workerTarget        <- address("worker-byte-limit")
        workerControlTarget <- address("worker-byte-control")
        _ <- backend.values.update(values =>
          values.updated(workerTarget, boundedBefore).updated(workerControlTarget, boundedBefore)
        )
        workerLimits <- ZIO.fromEither(
          SubagentTerminalStore.Limits.make(
            256,
            boundedBefore.value.toJson.getBytes(StandardCharsets.UTF_8).length + 512,
            8
          )
        )
        workerBounded = SubagentTerminalStore.make(backend.store, workerTarget, workerLimits)
        workerBaseline <- workerBounded.list
        workerControl <- SubagentTerminalStore
          .make(backend.store, workerControlTarget, workerLimits)
          .publish(second.copy(workerId = None))
        oversizedWorker <- workerBounded.publish(second.copy(workerId = Some(longWorker))).either
        workerAfter     <- backend.store.read(workerTarget)
        boundedAfter    <- backend.store.read(boundedTarget)
        boundedStamp    <- bounded.acknowledgement(firstId)
        at              <- Clock.instant
        acknowledged    <- store.acknowledge(firstId)
        _               <- TestClock.adjust(1.hour)
        repeated        <- store.acknowledge(firstId)
        idempotent      <- store.publish(first)
        _               <- store.publish(second)
        secondStamp     <- store.acknowledge(secondId)
        differentWorker <- store.publish(second.copy(workerId = None)).either
        sameWorker      <- store.publish(second)
        reopened = SubagentTerminalStore.make(backend.store, target)
        records <- reopened.list
        pending <- reopened.pending
        stamp   <- reopened.acknowledgement(firstId)
        absent  <- reopened.acknowledgement(SubagentTaskId(new UUID(0L, 99L)))
        current <- backend.store.read(target)
      yield assertTrue(
        before == Chunk(first),
        noBackfill.isLeft,
        schema2Read == Chunk(first),
        schema2Unchanged.contains(VersionedJson(schema2, revision)),
        schema2Reopened == Chunk(first),
        schema2Ack match
          case SubagentTerminalAcknowledgeResult.Acknowledged(_) => true
          case _                                                 => false
        ,
        secondStamp match
          case SubagentTerminalAcknowledgeResult.Acknowledged(_) => true
          case _                                                 => false
        ,
        differentWorker.isLeft,
        sameWorker match
          case SubagentTerminalPublication.AlreadyStored(_) => true
          case _                                            => false
        ,
        unstamped.contains(SubagentTerminalAcknowledgement.Pending),
        missing == SubagentTerminalAcknowledgeResult.Missing,
        unchanged.contains(VersionedJson(legacy, revision)),
        boundedPending == Chunk(first),
        oversizedAck.isLeft,
        workerBaseline == Chunk(first),
        workerControl match
          case SubagentTerminalPublication.Stored(_) => true
          case _                                     => false
        ,
        oversizedWorker.isLeft,
        workerAfter.contains(boundedBefore),
        boundedAfter.contains(boundedBefore),
        boundedStamp.contains(SubagentTerminalAcknowledgement.Pending),
        acknowledged == SubagentTerminalAcknowledgeResult.Acknowledged(at),
        repeated == SubagentTerminalAcknowledgeResult.AlreadyAcknowledged(at),
        idempotent match
          case SubagentTerminalPublication.AlreadyStored(_) => true
          case _                                            => false
        ,
        records == Chunk(first, second),
        pending.isEmpty,
        stamp.contains(SubagentTerminalAcknowledgement.Acknowledged(at)),
        absent.isEmpty,
        current.exists(_.value match
          case Json.Obj(fields) => fields.contains("schemaVersion" -> Json.Num(3))
          case _                => false)
      )
    ,
    test(
      "acknowledgement retries only confirmed conflicts with one timestamp and preserves uncertain or cancelled writes"
    ):
      ZIO.scoped:
        for
          backend <- fixture
          target  <- address("ack-conflicts")
          first  = SubagentTerminalRecord(firstId, SubagentTerminalOutcome.Completed("first"))
          second = SubagentTerminalRecord(secondId, SubagentTerminalOutcome.Completed("second"))
          store  = SubagentTerminalStore.make(backend.store, target)
          _        <- store.publish(first)
          _        <- store.publish(second)
          attempts <- Ref.make(0)
          conflicting = new VersionedJsonStore:
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
          at                <- Clock.instant
          receipt           <- SubagentTerminalStore.make(conflicting, target).acknowledge(firstId)
          count             <- attempts.get
          uncertainAttempts <- Ref.make(0)
          uncertainty = AgentError.StorageError("Acknowledgement receipt lost")
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
          secondAt       <- Clock.instant
          lost           <- SubagentTerminalStore.make(uncertain, target).acknowledge(secondId).exit
          uncertainCount <- uncertainAttempts.get
          recovered      <- store.acknowledge(secondId)
          entered        <- Promise.make[Nothing, Unit]
          cancelAttempts <- Ref.make(0)
          thirdId = SubagentTaskId(new UUID(0L, 3L))
          _ <- store.publish(
            SubagentTerminalRecord(thirdId, SubagentTerminalOutcome.Completed("third"))
          )
          cancelledBackend = new VersionedJsonStore:
            def read(address: StorageAddress): IO[AgentError, Option[VersionedJson]] =
              backend.store.read(address)
            def compareAndSet(
                address: StorageAddress,
                expected: ExpectedRevision,
                value: Json
            ): IO[AgentError, ConditionalWrite] =
              cancelAttempts.update(_ + 1) *> entered.succeed(()) *> ZIO.never
          writing <- SubagentTerminalStore
            .make(cancelledBackend, target)
            .acknowledge(thirdId)
            .forkScoped
          _           <- entered.await
          cancelled   <- writing.interrupt
          cancelCount <- cancelAttempts.get
          pending     <- store.pending
        yield assertTrue(
          receipt == SubagentTerminalAcknowledgeResult.Acknowledged(at),
          count == 2,
          lost == Exit.fail(uncertainty),
          uncertainCount == 1,
          recovered == SubagentTerminalAcknowledgeResult.AlreadyAcknowledged(secondAt),
          cancelled.isInterrupted,
          cancelCount == 1,
          pending.map(_.id) == Chunk(thirdId)
        )
  ) @@ TestAspect.timeout(15.seconds)
