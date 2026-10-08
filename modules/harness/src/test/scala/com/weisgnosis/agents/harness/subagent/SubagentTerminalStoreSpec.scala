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
import zio.*
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
              fiber <- store.publishJoined(firstId, task).interruptible.forkScoped
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
            SubagentTerminalRecord(firstId, SubagentTerminalOutcome.Completed(reply.text))
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
                  ZIO.succeed(
                    Chunk(
                      Json.Obj(fields :+ ("unknown" -> Json.Bool(true))),
                      Json.Obj(
                        fields.map((key, value) =>
                          key -> (if key == "schemaVersion" then Json.Num(2) else value)
                        )
                      ),
                      Json.Obj(
                        fields.map((key, value) =>
                          key -> (if key == "records" then Json.Arr(entries ++ entries) else value)
                        )
                      ),
                      Json.Obj("schemaVersion" -> Json.Num(1), "records" -> Json.Str("invalid"))
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
  ) @@ TestAspect.timeout(15.seconds)
