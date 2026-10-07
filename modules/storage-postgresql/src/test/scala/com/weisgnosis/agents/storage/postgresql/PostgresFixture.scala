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
import com.weisgnosis.agents.harness.storage.*
import java.sql.Connection
import javax.sql.DataSource
import org.postgresql.ds.PGSimpleDataSource
import zio.*

private[postgresql] object PostgresFixture:
  def blocking[A](operation: => A): IO[AgentError, A] =
    ZIO.attemptBlocking(operation).mapError(_ => AgentError.StorageError("Fixture JDBC failure"))

  def lease[A <: AutoCloseable](resource: => A): ZIO[Scope, AgentError, A] =
    ZIO.acquireRelease(blocking(resource))(value => blocking(value.close()).orDie)

  private def required(name: String): IO[AgentError, String] =
    System
      .env(name)
      .mapError(_ => AgentError.StorageError("Fixture environment unavailable"))
      .someOrFail(AgentError.StorageError(s"Missing fixture environment: $name"))

  def dataSource(
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

  def store(source: DataSource): IO[AgentError, PostgresVersionedJsonStore] =
    ZIO.fromEither(PostgresVersionedJsonStore.make(source, queryTimeoutSeconds = 5))

  def address(namespace: String, key: String): IO[AgentError, StorageAddress] =
    ZIO.fromEither(StorageAddress.from(Chunk(namespace), key))

  private def namespace: UIO[String] = Random.nextUUID.map(id => s"fixture-$id")

  def connection[A](source: DataSource)(
      use: Connection => ZIO[Scope, AgentError, A]
  ): IO[AgentError, A] =
    ZIO.scoped(lease(source.getConnection).flatMap(use))

  def update(source: DataSource, sql: String, values: Chunk[String]): IO[AgentError, Unit] =
    connection(source): current =>
      for
        statement <- lease(current.prepareStatement(sql))
        _ <- blocking:
          statement.setQueryTimeout(5)
          values.zipWithIndex.foreach((value, index) => statement.setString(index + 1, value))
          statement.executeUpdate()
      yield ()

  // Only schema provisioning is serialized; namespace-isolated test operations remain concurrent.
  private def initialize(
      source: PGSimpleDataSource,
      backend: PostgresVersionedJsonStore
  ): IO[AgentError, Unit] =
    connection(source): current =>
      for
        statement <- lease(current.prepareStatement("SELECT pg_advisory_lock(178215, 1)"))
        _ <- lease:
          statement.setQueryTimeout(5)
          statement.executeQuery()
        _ <- backend.initialize
      yield ()

  def fixture[A](
      use: (PGSimpleDataSource, PostgresVersionedJsonStore, String) => IO[AgentError, A]
  ): IO[AgentError, A] =
    for
      name    <- namespace
      source  <- dataSource(name)
      backend <- store(source)
      _       <- initialize(source, backend)
      result <- use(source, backend, name).ensuring(
        update(
          source,
          "DELETE FROM public.weisgnosis_versioned_json WHERE namespace_path LIKE ?",
          Chunk(name + "%")
        ).orDie
      )
    yield result

  def sessions(
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
