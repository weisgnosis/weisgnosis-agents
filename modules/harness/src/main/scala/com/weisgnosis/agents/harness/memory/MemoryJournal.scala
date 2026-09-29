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
package com.weisgnosis.agents.harness.memory

import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.json.JsonBoundary
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.LocalDate
import java.util.UUID
import zio.*
import zio.json.*
import zio.json.ast.Json

final private[memory] case class MemoryCheckpoint(
    version: Int,
    generation: BigInt,
    transaction: String,
    acknowledged: Map[String, String]
) derives JsonCodec

final private[memory] case class MemoryJournal(
    version: Int,
    beforeCurated: Option[String],
    beforeCheckpoint: Option[MemoryCheckpoint],
    daily: Map[String, String],
    afterCurated: String,
    afterCheckpoint: MemoryCheckpoint
) derives JsonCodec

/** Strict, versioned persistence input; no decoded field selects a filesystem destination. */
private[memory] object MemoryJournal:
  def checkpoint(text: String): IO[AgentError, MemoryCheckpoint] =
    for
      json <- parsed(text)
      _    <- ensure(checkpointShape(json), "Invalid consolidation checkpoint shape")
      value <- ZIO
        .fromEither(json.as[MemoryCheckpoint])
        .mapError(AgentError.MemoryStorageError.apply)
      _ <- validateCheckpoint(value)
    yield value

  def decode(text: String): IO[AgentError, MemoryJournal] =
    for
      json  <- parsed(text)
      _     <- ensure(journalShape(json), "Invalid consolidation journal shape")
      value <- ZIO.fromEither(json.as[MemoryJournal]).mapError(AgentError.MemoryStorageError.apply)
      _     <- ensure(value.version == 1, "Unsupported consolidation journal version")
      _     <- ZIO.foreachDiscard(value.beforeCheckpoint)(validateCheckpoint)
      _     <- validateCheckpoint(value.afterCheckpoint)
      _     <- validateFingerprints(value.daily)
      _ <- ensure(
        value.afterCheckpoint.generation == value.beforeCheckpoint.fold(BigInt(0))(
          _.generation
        ) + 1 &&
          value.afterCheckpoint.acknowledged == value.daily &&
          !value.beforeCheckpoint.exists(_.transaction == value.afterCheckpoint.transaction),
        "Inconsistent consolidation journal transition"
      )
    yield value

  def fingerprints(daily: Map[LocalDate, String]): IO[AgentError, Map[String, String]] =
    ZIO
      .foreach(daily.toList): (day, content) =>
        ZIO
          .attempt:
            val digest = MessageDigest
              .getInstance("SHA-256")
              .digest(content.getBytes(StandardCharsets.UTF_8))
              .iterator
              .map(byte => f"${byte & 0xff}%02x")
              .mkString
            day.toString -> digest
          .mapError(error => AgentError.MemoryStorageError(error.toString))
      .map(_.toMap)

  private def parsed(text: String): IO[AgentError, Json] =
    ZIO.fromEither(JsonBoundary.decode(text)).mapError(AgentError.MemoryStorageError.apply)

  private def ensure(condition: Boolean, message: String): IO[AgentError, Unit] =
    ZIO.fail(AgentError.MemoryStorageError(message)).unless(condition).unit

  private def validateCheckpoint(value: MemoryCheckpoint): IO[AgentError, Unit] =
    for
      _ <- ensure(
        value.version == 1 && value.generation > 0,
        "Unsupported checkpoint version or generation"
      )
      identifier <- ZIO
        .attempt(UUID.fromString(value.transaction))
        .mapError(_ => AgentError.MemoryStorageError("Invalid checkpoint transaction identifier"))
      _ <- ensure(
        identifier.toString == value.transaction,
        "Noncanonical checkpoint transaction identifier"
      )
      _ <- validateFingerprints(value.acknowledged)
    yield ()

  private def validateFingerprints(values: Map[String, String]): IO[AgentError, Unit] =
    ZIO.foreachDiscard(values): (name, digest) =>
      for
        day <- ZIO
          .attempt(LocalDate.parse(name))
          .mapError(_ => AgentError.MemoryStorageError("Invalid daily memory date"))
        _ <- ensure(
          day.toString == name && digest.matches("[0-9a-f]{64}"),
          "Invalid daily memory fingerprint"
        )
      yield ()

  private def fields(
      json: Json,
      required: Set[String],
      optional: Set[String] = Set.empty
  ): Option[Map[String, Json]] = json match
    case Json.Obj(entries) =>
      val values = entries.toMap
      Option.when(required.subsetOf(values.keySet) && values.keySet.subsetOf(required ++ optional))(
        values
      )
    case _ => None

  private def integer(json: Json): Boolean = json match
    case Json.Num(value) => BigDecimal(value).isWhole
    case _               => false

  private def text(json: Json): Boolean = json match
    case _: Json.Str => true
    case _           => false

  private def stringMap(json: Json): Boolean = json match
    case Json.Obj(entries) => entries.forall(entry => text(entry._2))
    case _                 => false

  private def optional(values: Map[String, Json], key: String, shape: Json => Boolean): Boolean =
    values
      .get(key)
      .forall:
        case Json.Null => true
        case value     => shape(value)

  private def checkpointShape(json: Json): Boolean =
    fields(json, Set("version", "generation", "transaction", "acknowledged")).exists: values =>
      values.get("version").exists(integer) && values.get("generation").exists(integer) &&
        values.get("transaction").exists(text) && values.get("acknowledged").exists(stringMap)

  private def journalShape(json: Json): Boolean =
    fields(
      json,
      Set("version", "daily", "afterCurated", "afterCheckpoint"),
      Set("beforeCurated", "beforeCheckpoint")
    ).exists: values =>
      values.get("version").exists(integer) && values.get("daily").exists(stringMap) &&
        values.get("afterCurated").exists(text) && values
          .get("afterCheckpoint")
          .exists(checkpointShape) &&
        optional(values, "beforeCurated", text) && optional(
          values,
          "beforeCheckpoint",
          checkpointShape
        )
