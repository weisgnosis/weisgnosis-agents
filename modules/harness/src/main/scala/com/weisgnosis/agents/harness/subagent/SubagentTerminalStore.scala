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
import com.weisgnosis.agents.core.json.JsonBoundary
import com.weisgnosis.agents.harness.storage.*
import java.nio.charset.StandardCharsets
import java.util.UUID
import zio.*
import zio.json.*
import zio.json.ast.Json

enum SubagentTerminalPublication:
  case Stored(revision: StorageRevision)
  case AlreadyStored(revision: StorageRevision)

/** One explicitly host-addressed owner partition; terminal data grants no execution authority. */
trait SubagentTerminalStore:
  def read(id: SubagentTaskId): IO[AgentError, Option[SubagentTerminalRecord]]
  def list: IO[AgentError, Chunk[SubagentTerminalRecord]]
  def publish(record: SubagentTerminalRecord): IO[AgentError, SubagentTerminalPublication]

  /** The caller owns this effect; awaiting publication does not transfer or cancel task ownership. */
  final def publishJoined(
      id: SubagentTaskId,
      task: SupervisedTask[AgentResult]
  ): IO[AgentError, SubagentTerminalPublication] =
    task.await.flatMap(exit =>
      publish(SubagentTerminalRecord(id, SubagentTerminalOutcome.fromExit(exit)))
    )

object SubagentTerminalStore:
  final class Limits private (
      val maxRecords: Int,
      val maxBytes: Int,
      val maxConflicts: Int
  )

  object Limits:
    val default: Limits = new Limits(256, 1048576, 8)

    def make(
        maxRecords: Int,
        maxBytes: Int,
        maxConflicts: Int
    ): Either[AgentError.InvalidConfiguration, Limits] =
      Either.cond(
        maxRecords > 0 && maxBytes > 0 && maxConflicts >= 0,
        new Limits(maxRecords, maxBytes, maxConflicts),
        AgentError.InvalidConfiguration(
          "Terminal record and byte limits must be positive; conflict retries must be nonnegative"
        )
      )

  def make(
      store: VersionedJsonStore,
      address: StorageAddress,
      limits: Limits = Limits.default
  ): SubagentTerminalStore = new Backend(store, address, limits)

  final private case class Entry(id: UUID, outcome: SubagentTerminalOutcome) derives JsonCodec
  final private case class Payload(schemaVersion: Int, records: Chunk[Entry]) derives JsonCodec

  final private class Backend(store: VersionedJsonStore, address: StorageAddress, limits: Limits)
      extends SubagentTerminalStore:
    def list: IO[AgentError, Chunk[SubagentTerminalRecord]] =
      snapshot.map(_._2)

    def read(id: SubagentTaskId): IO[AgentError, Option[SubagentTerminalRecord]] =
      list.map(_.find(_.id == id))

    def publish(record: SubagentTerminalRecord): IO[AgentError, SubagentTerminalPublication] =
      def attempt(remaining: Int): IO[AgentError, SubagentTerminalPublication] =
        snapshot.flatMap: (revision, records) =>
          (revision, records.find(_.id == record.id)) match
            case (Some(current), Some(existing)) if existing == record =>
              ZIO.succeed(SubagentTerminalPublication.AlreadyStored(current))
            case (_, Some(_)) =>
              ZIO.fail(
                AgentError.StorageError(
                  "A different terminal observation already occupies this task ID"
                )
              )
            case (_, None) =>
              for
                value <- ZIO.fromEither(encode(records :+ record))
                expected = revision.fold[ExpectedRevision](ExpectedRevision.Absent)(
                  ExpectedRevision.AtRevision(_)
                )
                result <- store.compareAndSet(address, expected, value)
                publication <- result match
                  case ConditionalWrite.Applied(updated) =>
                    ZIO.succeed(SubagentTerminalPublication.Stored(updated))
                  case ConditionalWrite.Conflict if remaining > 0 => attempt(remaining - 1)
                  case ConditionalWrite.Conflict =>
                    ZIO.fail(
                      AgentError.StorageError(
                        "Terminal publication exhausted its bounded conflict retries"
                      )
                    )
              yield publication
      attempt(limits.maxConflicts)

    private def snapshot: IO[AgentError, (Option[StorageRevision], Chunk[SubagentTerminalRecord])] =
      store
        .read(address)
        .flatMap:
          case None => ZIO.succeed((None, Chunk.empty))
          case Some(record) =>
            ZIO.fromEither(decode(record.value)).map(records => (Some(record.revision), records))

    private def encode(records: Chunk[SubagentTerminalRecord]): Either[AgentError, Json] =
      for
        value <- Payload(
          1,
          records.map(record => Entry(record.id.value, record.outcome))
        ).toJsonAST.left.map(AgentError.StorageError.apply)
        _ <- decode(value)
      yield value

    private def decode(
        value: Json
    ): Either[AgentError.StorageError, Chunk[SubagentTerminalRecord]] =
      for
        validated <- JsonBoundary.validate(value).left.map(AgentError.StorageError.apply)
        rendered = JsonBoundary.render(validated)
        _ <- Either.cond(
          rendered.getBytes(StandardCharsets.UTF_8).length <= limits.maxBytes && JsonBoundary
            .validUnicode(validated),
          (),
          AgentError.StorageError(
            "Terminal aggregate exceeds its byte limit or contains malformed Unicode"
          )
        )
        payload   <- rendered.fromJson[Payload].left.map(AgentError.StorageError.apply)
        canonical <- payload.toJsonAST.left.map(AgentError.StorageError.apply)
        _ <- Either.cond(
          payload.schemaVersion == 1 && payload.records.size <= limits.maxRecords &&
            payload.records.map(_.id).toSet.size == payload.records.size &&
            JsonBoundary.sameRepresentation(validated, canonical),
          (),
          AgentError.StorageError(
            "Unsupported, duplicate, oversized or noncanonical terminal aggregate"
          )
        )
      yield payload.records.map(record =>
        SubagentTerminalRecord(SubagentTaskId(record.id), record.outcome)
      )
