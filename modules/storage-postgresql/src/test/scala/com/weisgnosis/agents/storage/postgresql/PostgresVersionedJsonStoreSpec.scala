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
package com.weisgnosis.agents.storage.postgresql

import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.json.JsonBoundary
import com.weisgnosis.agents.harness.storage.*
import java.sql.Connection
import javax.sql.DataSource
import org.postgresql.ds.PGSimpleDataSource
import zio.*
import zio.json.ast.Json
import zio.test.*

object PostgresVersionedJsonStoreSpec extends ZIOSpecDefault:
  private def blocking[A](operation: => A): IO[AgentError, A] =
    ZIO.attemptBlocking(operation).mapError(_ => AgentError.StorageError("Fixture JDBC failure"))

  private def lease[A <: AutoCloseable](resource: => A): ZIO[Scope, AgentError, A] =
    ZIO.acquireRelease(blocking(resource))(value => blocking(value.close()).orDie)

  private def required(name: String): IO[AgentError, String] =
    System
      .env(name)
      .mapError(_ => AgentError.StorageError("Fixture environment unavailable"))
      .someOrFail(AgentError.StorageError(s"Missing fixture environment: $name"))

  private def dataSource(
      application: String,
      options: Option[String] = None
  ): IO[AgentError, PGSimpleDataSource] =
    for
      url      <- required("WEISGNOSIS_POSTGRES_URL")
      user     <- required("WEISGNOSIS_POSTGRES_USER")
      password <- required("WEISGNOSIS_POSTGRES_PASSWORD")
      source <- blocking:
        val configured = new PGSimpleDataSource()
        configured.setURL(url)
        configured.setUser(user)
        configured.setPassword(password)
        configured.setApplicationName(application)
        configured.setConnectTimeout(5)
        configured.setSocketTimeout(10)
        options.foreach(configured.setOptions)
        configured
    yield source

  private def store(source: DataSource): IO[AgentError, PostgresVersionedJsonStore] =
    ZIO.fromEither(PostgresVersionedJsonStore.make(source, queryTimeoutSeconds = 5))

  private def address(namespace: String, key: String): IO[AgentError, StorageAddress] =
    ZIO.fromEither(StorageAddress.from(Chunk(namespace), key))

  private def namespace: UIO[String] = Random.nextUUID.map(id => s"fixture-$id")

  private def connection[A](source: DataSource)(
      use: Connection => ZIO[Scope, AgentError, A]
  ): IO[AgentError, A] =
    ZIO.scoped(lease(source.getConnection).flatMap(use))

  private def update(source: DataSource, sql: String, values: Chunk[String]): IO[AgentError, Unit] =
    connection(source): current =>
      for
        statement <- lease(current.prepareStatement(sql))
        _ <- blocking:
          statement.setQueryTimeout(5)
          values.zipWithIndex.foreach((value, index) => statement.setString(index + 1, value))
          statement.executeUpdate()
      yield ()

  private def fixture[A](
      use: (PGSimpleDataSource, PostgresVersionedJsonStore, String) => IO[AgentError, A]
  ): IO[AgentError, A] =
    for
      name    <- namespace
      source  <- dataSource(name)
      backend <- store(source)
      _       <- backend.initialize
      result <- use(source, backend, name).ensuring(
        update(
          source,
          "DELETE FROM public.weisgnosis_versioned_json WHERE namespace_path LIKE ?",
          Chunk(name + "%")
        ).orDie
      )
    yield result

  private def sessions(
      source: DataSource,
      application: String,
      lockedOnly: Boolean
  ): IO[AgentError, Long] =
    connection(source): current =>
      for
        statement <- lease(
          current.prepareStatement(
            "SELECT count(*) FROM pg_stat_activity WHERE application_name = ? AND (? = false OR wait_event_type = 'Lock')"
          )
        )
        _ <- blocking:
          statement.setString(1, application)
          statement.setBoolean(2, lockedOnly)
        rows  <- lease(statement.executeQuery())
        count <- blocking(if rows.next() then rows.getLong(1) else 0L)
      yield count

  def spec: Spec[TestEnvironment, Any] = suite("PostgresVersionedJsonStoreSpec")(
    test(
      "exact JSON and namespace identity survive independent connection and adapter acquisition"
    ):
      fixture: (_, backend, name) =>
        for
          secondSource <- dataSource(name + "-reader")
          second       <- store(secondSource)
          exact <- ZIO.fromEither(
            JsonBoundary.decode("-9223372036854775808").left.map(AgentError.StorageError(_))
          )
          values = Chunk[Json](
            exact,
            Json.Obj("\uD83D\uDE00" -> Json.Str("\uD83D\uDE00")),
            Json.Null,
            Json.Obj("nested" -> Json.Arr(Json.Bool(true), Json.Str("opaque")))
          )
          observations <- ZIO.foreach(values.zipWithIndex): (value, index) =>
            for
              target    <- address(name + "-\u03bb", s"key '$index \u00e9")
              other     <- address(name + "-different", s"key '$index \u00e9")
              absent    <- second.read(target)
              written   <- backend.compareAndSet(target, ExpectedRevision.Absent, value)
              recovered <- second.read(target)
              isolated  <- second.read(other)
            yield absent.isEmpty && isolated.isEmpty && recovered.exists(record =>
              record.value == value && record.revision.value == 1L
            ) && (written match
              case ConditionalWrite.Applied(revision) => revision.value == 1L
              case ConditionalWrite.Conflict          => false)
        yield assertTrue(observations.forall(identity))
    ,
    test("create and stale-revision races have exactly one winner without revision reuse"):
      fixture: (source, backend, name) =>
        for
          second <- store(source)
          target <- address(name, "race")
          created <- ZIO.foreachPar(Chunk(backend, second))(
            _.compareAndSet(target, ExpectedRevision.Absent, Json.Num(1))
          )
          first <- backend
            .read(target)
            .someOrFail(AgentError.StorageError("Missing created fixture"))
          changed <- ZIO.foreachPar(Chunk.fromIterable(1 to 8))(value =>
            second.compareAndSet(
              target,
              ExpectedRevision.AtRevision(first.revision),
              Json.Num(value)
            )
          )
          finalValue <- backend
            .read(target)
            .someOrFail(AgentError.StorageError("Missing updated fixture"))
          stale <- backend.compareAndSet(
            target,
            ExpectedRevision.AtRevision(first.revision),
            Json.Str("stale")
          )
          exhausted <- ZIO.fromEither(StorageRevision.fromLong(Long.MaxValue))
          overflow <- backend
            .compareAndSet(target, ExpectedRevision.AtRevision(exhausted), Json.Null)
            .exit
          unchanged <- second.read(target)
        yield assertTrue(
          created.count { case ConditionalWrite.Applied(_) => true; case _ => false } == 1,
          created.count(_ == ConditionalWrite.Conflict) == 1,
          changed.count { case ConditionalWrite.Applied(_) => true; case _ => false } == 1,
          changed.count(_ == ConditionalWrite.Conflict) == 7,
          finalValue.revision.value == 2L,
          stale == ConditionalWrite.Conflict,
          overflow.causeOption.exists(
            _.failureOption.exists(_.isInstanceOf[AgentError.StorageError])
          ),
          unchanged.contains(finalValue)
        )
    ,
    test(
      "backend failures remain typed and cancellation joins an admitted locked write and closes leases"
    ):
      fixture: (source, backend, name) =>
        ZIO.scoped:
          for
            target <- address(name, "locked")
            _      <- backend.compareAndSet(target, ExpectedRevision.Absent, Json.Str("initial"))
            initial <- backend
              .read(target)
              .someOrFail(AgentError.StorageError("Missing lock fixture"))
            readOnlySource <- dataSource(
              name + "-readonly",
              Some("-c default_transaction_read_only=on")
            )
            readOnly <- store(readOnlySource)
            rejected <- readOnly
              .compareAndSet(
                target,
                ExpectedRevision.AtRevision(initial.revision),
                Json.Str("forbidden")
              )
              .exit
            duplicate <- backend
              .compareAndSet(
                target,
                ExpectedRevision.AtRevision(initial.revision),
                Json.Obj("a" -> Json.Num(1), "a" -> Json.Num(2))
              )
              .exit
            malformedUnicode <- ZIO.foreach(
              Chunk[Json](Json.Str("\uD800"), Json.Obj("\uDC00" -> Json.Str("value"))).zipWithIndex
            ): (value, index) =>
              for
                invalidTarget <- address(name, s"invalid-$index")
                observation <- backend
                  .compareAndSet(invalidTarget, ExpectedRevision.Absent, value)
                  .exit
                stored <- backend.read(invalidTarget)
              yield observation.isFailure && stored.isEmpty
            workerName = name + "-worker"
            workerSource <- dataSource(workerName)
            workerStore  <- store(workerSource)
            lock         <- lease(source.getConnection)
            _            <- blocking(lock.setAutoCommit(false))
            _            <- ZIO.addFinalizer(blocking(lock.rollback()).orDie)
            statement <- lease(
              lock.prepareStatement(
                "SELECT revision FROM public.weisgnosis_versioned_json WHERE namespace_path = ? AND item_key = ? FOR UPDATE"
              )
            )
            _ <- blocking:
              statement.setString(1, target.serializedNamespace)
              statement.setString(2, target.key)
            rows     <- lease(statement.executeQuery())
            acquired <- blocking(rows.next())
            worker <- workerStore
              .compareAndSet(
                target,
                ExpectedRevision.AtRevision(initial.revision),
                Json.Str("committed")
              )
              .forkScoped
            _ <- (sessions(source, workerName, lockedOnly = true) <* ZIO.sleep(10.millis))
              .repeatUntil(_ == 1L)
              .timeoutFail(AgentError.StorageError("Worker did not reach row lock"))(3.seconds)
            cancel    <- worker.interrupt.forkScoped
            _         <- ZIO.sleep(100.millis)
            waiting   <- cancel.poll
            _         <- blocking(lock.rollback())
            canceled  <- cancel.join
            remaining <- sessions(source, workerName, lockedOnly = false)
            current   <- backend.read(target)
            timeoutName = name + "-timeout"
            timeoutSource <- dataSource(timeoutName)
            timeoutStore <- ZIO.fromEither(
              PostgresVersionedJsonStore.make(timeoutSource, queryTimeoutSeconds = 1)
            )
            relock <- lease(
              lock.prepareStatement(
                "SELECT revision FROM public.weisgnosis_versioned_json WHERE namespace_path = ? AND item_key = ? FOR UPDATE"
              )
            )
            _ <- blocking:
              relock.setString(1, target.serializedNamespace)
              relock.setString(2, target.key)
            lockedRows <- lease(relock.executeQuery())
            relocked   <- blocking(lockedRows.next())
            latest <- ZIO
              .fromOption(current)
              .orElseFail(AgentError.StorageError("Missing current fixture"))
            timingOut <- timeoutStore
              .compareAndSet(
                target,
                ExpectedRevision.AtRevision(latest.revision),
                Json.Str("timed out")
              )
              .forkScoped
            _ <- (sessions(source, timeoutName, lockedOnly = true) <* ZIO.sleep(10.millis))
              .repeatUntil(_ == 1L)
              .timeoutFail(AgentError.StorageError("Timeout worker did not reach row lock"))(
                3.seconds
              )
            failingCancellation <- timingOut.interrupt
            timeoutSessions     <- sessions(source, timeoutName, lockedOnly = false)
            _                   <- blocking(lock.rollback())
            _ <- update(
              source,
              "UPDATE public.weisgnosis_versioned_json SET value_json = ? WHERE namespace_path = ? AND item_key = ?",
              Chunk("{\"a\":1,\"a\":2}", target.serializedNamespace, target.key)
            )
            corrupted <- backend.read(target).exit
          yield assertTrue(
            acquired,
            relocked,
            failingCancellation.isInterrupted,
            failingCancellation.causeOption.exists(
              _.failures.exists(_.isInstanceOf[AgentError.StorageError])
            ),
            timeoutSessions == 0L,
            rejected.causeOption.exists(
              _.failureOption.exists(_.isInstanceOf[AgentError.StorageError])
            ),
            duplicate.isFailure,
            malformedUnicode.forall(identity),
            waiting.isEmpty,
            canceled.isInterrupted,
            remaining == 0L,
            current.exists(record =>
              record.revision.value == 2L && record.value == Json.Str("committed")
            ),
            corrupted.causeOption.exists(
              _.failureOption.exists(_.isInstanceOf[AgentError.StorageError])
            )
          )
  ) @@ TestAspect.sequential @@ TestAspect.withLiveSystem @@ TestAspect.withLiveClock @@ TestAspect
    .timeout(
      30.seconds
    ) @@ TestAspect.ifEnvSet("WEISGNOSIS_POSTGRES_URL")
