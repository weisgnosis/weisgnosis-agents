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
import java.sql.{Connection, PreparedStatement, ResultSet, SQLException}
import javax.sql.DataSource
import zio.*
import zio.json.ast.Json

/**
 * PostgreSQL CAS over a fixed table and a host-owned DataSource. Every operation borrows and closes
 * a fresh autocommit connection; the pool and driver remain owned by the host. Admitted JDBC work
 * and cleanup finish before interruption is observed. Query timeout bounds statement waiting, not
 * the total cancellation duration. A canceled acknowledgement can follow a committed write.
 */
final class PostgresVersionedJsonStore private (
    dataSource: DataSource,
    queryTimeoutSeconds: Int
) extends VersionedJsonStore:
  import PostgresVersionedJsonStore.*

  /**
   * Explicit idempotent DDL, not verification of an existing table. The host must supply this exact
   * compatible schema; construction and ordinary reads/writes never initialize it.
   */
  def initialize: IO[AgentError, Unit] = withConnection: connection =>
    for
      statement <- prepare(connection, createTable)
      _         <- blocking(statement.executeUpdate()).unit
    yield ()

  def read(address: StorageAddress): IO[AgentError, Option[VersionedJson]] =
    withConnection: connection =>
      for
        statement <- prepare(connection, selectValue)
        _         <- bindAddress(statement, address)
        rows      <- lease(statement.executeQuery())
        present   <- blocking(rows.next())
        result    <- if present then readValue(rows).map(Some(_)) else ZIO.none
      yield result

  def compareAndSet(
      address: StorageAddress,
      expected: ExpectedRevision,
      value: Json
  ): IO[AgentError, ConditionalWrite] =
    val checkRevision = expected match
      case ExpectedRevision.AtRevision(revision) if revision.value == Long.MaxValue =>
        ZIO.fail(AgentError.StorageError("Storage revision is exhausted"))
      case _ => ZIO.unit
    checkRevision *> ZIO
      .fromEither(validateValue(value))
      .flatMap: validated =>
        val sql = expected match
          case ExpectedRevision.Absent        => insertValue
          case ExpectedRevision.AtRevision(_) => updateValue
        withConnection: connection =>
          for
            statement <- prepare(connection, sql)
            _ <- expected match
              case ExpectedRevision.Absent =>
                bindAddress(statement, address) *>
                  blocking(statement.setString(3, JsonBoundary.render(validated)))
              case ExpectedRevision.AtRevision(revision) =>
                bindAddress(statement, address, 2) *> blocking:
                  statement.setString(1, JsonBoundary.render(validated))
                  statement.setLong(4, revision.value)
            rows    <- lease(statement.executeQuery())
            present <- blocking(rows.next())
            result <-
              if present then readRevision(rows, 1).map(ConditionalWrite.Applied(_))
              else ZIO.succeed(ConditionalWrite.Conflict)
          yield result

  private def readValue(rows: ResultSet): IO[AgentError, VersionedJson] =
    for
      encoded  <- blocking(Option(rows.getString(1)))
      revision <- readRevision(rows, 2)
      value <- ZIO
        .fromEither(encoded.toRight(AgentError.StorageError("Stored JSON is SQL NULL")))
        .flatMap(text =>
          ZIO.fromEither(
            JsonBoundary
              .decode(text)
              .left
              .map(_ => AgentError.StorageError("Stored JSON is invalid"))
              .flatMap(validateValue)
          )
        )
    yield VersionedJson(value, revision)

  private def validateValue(value: Json): Either[AgentError.StorageError, Json] =
    JsonBoundary
      .validate(value)
      .left
      .map(_ => AgentError.StorageError("Storage JSON contains duplicate members"))
      .flatMap(validated =>
        Either.cond(
          validUnicode(validated),
          validated,
          AgentError.StorageError("Storage JSON contains an unpaired UTF-16 surrogate")
        )
      )

  private def validUnicode(value: Json): Boolean =
    def validText(text: String): Boolean = text.indices.forall: index =>
      val character = text.charAt(index)
      if Character.isHighSurrogate(character) then
        text.lift(index + 1).exists(Character.isLowSurrogate)
      else if Character.isLowSurrogate(character) then
        text.lift(index - 1).exists(Character.isHighSurrogate)
      else true

    @annotation.tailrec
    def loop(pending: List[Json]): Boolean = pending match
      case Nil                    => true
      case Json.Str(text) :: rest => if validText(text) then loop(rest) else false
      case Json.Obj(fields) :: rest =>
        if fields.forall((key, _) => validText(key)) then loop(fields.map(_._2).toList ::: rest)
        else false
      case Json.Arr(values) :: rest => loop(values.toList ::: rest)
      case _ :: rest                => loop(rest)
    loop(List(value))

  private def readRevision(rows: ResultSet, column: Int): IO[AgentError, StorageRevision] =
    blocking(rows.getLong(column)).flatMap(value =>
      ZIO.fromEither(
        StorageRevision.fromLong(value).left.map(error => AgentError.StorageError(error.message))
      )
    )

  private def bindAddress(
      statement: PreparedStatement,
      address: StorageAddress,
      firstColumn: Int = 1
  ): IO[AgentError, Unit] =
    blocking:
      statement.setString(firstColumn, address.serializedNamespace)
      statement.setString(firstColumn + 1, address.key)

  private def prepare(
      connection: Connection,
      sql: String
  ): ZIO[Scope, AgentError, PreparedStatement] =
    for
      statement <- lease(connection.prepareStatement(sql))
      _         <- blocking(statement.setQueryTimeout(queryTimeoutSeconds))
    yield statement

  private def withConnection[A](
      operation: Connection => ZIO[Scope, AgentError, A]
  ): IO[AgentError, A] =
    ZIO.uninterruptibleMask: restore =>
      ZIO
        .scoped:
          for
            connection <- lease(dataSource.getConnection())
            autocommit <- blocking(connection.getAutoCommit())
            _ <- ZIO
              .fail(AgentError.StorageError("Storage requires a fresh autocommit connection"))
              .unless(autocommit)
            result <- operation(connection)
          yield result
        .exit
        .flatMap:
          case Exit.Success(result) => restore(ZIO.succeed(result))
          case Exit.Failure(cause) =>
            restore(ZIO.unit).exit.flatMap:
              case Exit.Success(_)            => ZIO.refailCause(cause)
              case Exit.Failure(interruption) => ZIO.refailCause(cause ++ interruption)

  private def lease[A <: AutoCloseable](acquire: => A): ZIO[Scope, AgentError, A] =
    ZIO.acquireRelease(blocking(acquire))(resource => blocking(resource.close()).orDie)

  private def blocking[A](operation: => A): IO[AgentError, A] =
    ZIO
      .attemptBlocking(operation)
      .mapError:
        case sql: SQLException if Option(sql.getSQLState).contains("22003") =>
          AgentError.StorageError("PostgreSQL storage numeric revision limit exceeded")
        case sql: SQLException =>
          val state = Option(sql.getSQLState).filter(value =>
            value.length == 5 && value.forall(character =>
              (character >= 'A' && character <= 'Z') || (character >= '0' && character <= '9')
            )
          )
          val classification = state.fold("")(value => s", SQLSTATE=$value")
          AgentError.StorageError(
            s"PostgreSQL storage operation failed (${sql.getClass.getSimpleName}$classification)"
          )
        case failure =>
          AgentError.StorageError(
            s"PostgreSQL storage operation failed (${failure.getClass.getSimpleName})"
          )

object PostgresVersionedJsonStore:
  /** Positive JDBC statement timeout in seconds; it is not a connection-acquisition deadline. */
  def make(
      dataSource: DataSource,
      queryTimeoutSeconds: Int = 30
  ): Either[AgentError.InvalidConfiguration, PostgresVersionedJsonStore] =
    Either.cond(
      queryTimeoutSeconds > 0,
      new PostgresVersionedJsonStore(dataSource, queryTimeoutSeconds),
      AgentError.InvalidConfiguration("PostgreSQL query timeout must be positive seconds")
    )

  private val createTable: String =
    """CREATE TABLE IF NOT EXISTS public.weisgnosis_versioned_json (
      |namespace_path TEXT COLLATE "C" NOT NULL CHECK (octet_length(namespace_path) BETWEEN 1 AND 512),
      |item_key TEXT COLLATE "C" NOT NULL CHECK (octet_length(item_key) BETWEEN 1 AND 512),
      |value_json TEXT NOT NULL,
      |revision BIGINT NOT NULL CHECK (revision > 0),
      |PRIMARY KEY (namespace_path, item_key)
      |)""".stripMargin

  private val selectValue: String =
    "SELECT value_json, revision FROM public.weisgnosis_versioned_json WHERE namespace_path = ? AND item_key = ?"

  private val insertValue: String =
    """INSERT INTO public.weisgnosis_versioned_json (namespace_path, item_key, value_json, revision)
      |VALUES (?, ?, ?, 1) ON CONFLICT (namespace_path, item_key) DO NOTHING RETURNING revision""".stripMargin

  private val updateValue: String =
    """UPDATE public.weisgnosis_versioned_json SET value_json = ?, revision = revision + 1
      |WHERE namespace_path = ? AND item_key = ? AND revision = ? RETURNING revision""".stripMargin
