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
import java.time.Instant
import zio.*
import zio.json.*
import zio.json.ast.Json

enum SubagentTerminalPublication:
  case Stored(revision: StorageRevision)
  case AlreadyStored(revision: StorageRevision)

enum SubagentTerminalAcknowledgement derives JsonCodec:
  case Pending
  case Acknowledged(at: Instant)

enum SubagentTerminalAcknowledgeResult:
  case Missing
  case Acknowledged(at: Instant)
  case AlreadyAcknowledged(at: Instant)

/** One explicitly host-addressed owner partition; terminal data grants no execution authority. */
trait SubagentTerminalStore:
  def read(id: SubagentTaskId): IO[AgentError, Option[SubagentTerminalRecord]]
  def list: IO[AgentError, Chunk[SubagentTerminalRecord]]
  def publish(record: SubagentTerminalRecord): IO[AgentError, SubagentTerminalPublication]

  /** Persisted terminal observations still awaiting host acknowledgement, in publication order. */
  def pending: IO[AgentError, Chunk[SubagentTerminalRecord]] =
    ZIO.fail(
      AgentError.InvalidConfiguration("This terminal store does not support acknowledgement")
    )

  def acknowledgement(
      id: SubagentTaskId
  ): IO[AgentError, Option[SubagentTerminalAcknowledgement]] =
    ZIO.fail(
      AgentError.InvalidConfiguration(s"Terminal acknowledgement is unsupported for ${id.value}")
    )

  /** First acknowledgement wins; an uncertain write is never automatically retried. */
  def acknowledge(id: SubagentTaskId): IO[AgentError, SubagentTerminalAcknowledgeResult] =
    ZIO.fail(
      AgentError.InvalidConfiguration(s"Terminal acknowledgement is unsupported for ${id.value}")
    )

  /** The caller owns this effect; awaiting publication does not transfer or cancel task ownership. */
  final def publishJoined(
      id: SubagentTaskId,
      task: SupervisedTask[AgentResult],
      workerId: Option[SubagentWorkerId] = None
  ): IO[AgentError, SubagentTerminalPublication] =
    task.await.flatMap(exit =>
      publish(SubagentTerminalRecord(id, SubagentTerminalOutcome.fromExit(exit), workerId))
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

  final private case class LegacyEntry(id: UUID, outcome: SubagentTerminalOutcome) derives JsonCodec
  final private case class LegacyPayload(schemaVersion: Int, records: Chunk[LegacyEntry])
      derives JsonCodec
  final private case class Schema2Entry(
      id: UUID,
      outcome: SubagentTerminalOutcome,
      acknowledgedAt: Option[Instant] = None
  ) derives JsonCodec
  final private case class Schema2Payload(schemaVersion: Int, records: Chunk[Schema2Entry])
      derives JsonCodec
  final private case class Entry(
      id: UUID,
      outcome: SubagentTerminalOutcome,
      acknowledgedAt: Option[Instant] = None,
      workerId: Option[SubagentWorkerId] = None
  ) derives JsonCodec:
    def record: SubagentTerminalRecord =
      SubagentTerminalRecord(SubagentTaskId(id), outcome, workerId)
    def acknowledgement: SubagentTerminalAcknowledgement = acknowledgedAt match
      case None     => SubagentTerminalAcknowledgement.Pending
      case Some(at) => SubagentTerminalAcknowledgement.Acknowledged(at)

  final private case class Payload(schemaVersion: Int, records: Chunk[Entry]) derives JsonCodec
  final private case class Version(schemaVersion: Int) derives JsonCodec

  final private class Backend(store: VersionedJsonStore, address: StorageAddress, limits: Limits)
      extends SubagentTerminalStore:
    def list: IO[AgentError, Chunk[SubagentTerminalRecord]] =
      snapshot.map(_._2.map(_.record))

    def read(id: SubagentTaskId): IO[AgentError, Option[SubagentTerminalRecord]] =
      list.map(_.find(_.id == id))

    override def pending: IO[AgentError, Chunk[SubagentTerminalRecord]] =
      snapshot.map(_._2.filter(_.acknowledgedAt.isEmpty).map(_.record))

    override def acknowledgement(
        id: SubagentTaskId
    ): IO[AgentError, Option[SubagentTerminalAcknowledgement]] =
      snapshot.map(_._2.find(_.id == id.value).map(_.acknowledgement))

    override def acknowledge(
        id: SubagentTaskId
    ): IO[AgentError, SubagentTerminalAcknowledgeResult] =
      def attempt(
          remaining: Int,
          candidate: Option[Instant]
      ): IO[AgentError, SubagentTerminalAcknowledgeResult] =
        snapshot.flatMap: (revision, records) =>
          records.find(_.id == id.value) match
            case None => ZIO.succeed(SubagentTerminalAcknowledgeResult.Missing)
            case Some(existing) =>
              existing.acknowledgedAt match
                case Some(at) =>
                  ZIO.succeed(SubagentTerminalAcknowledgeResult.AlreadyAcknowledged(at))
                case None =>
                  for
                    at <- candidate.fold[UIO[Instant]](Clock.instant)(ZIO.succeed(_))
                    value <- ZIO.fromEither(
                      encode(
                        records.map(entry =>
                          if entry.id == id.value then entry.copy(acknowledgedAt = Some(at))
                          else entry
                        )
                      )
                    )
                    result <- store.compareAndSet(address, expectedRevision(revision), value)
                    receipt <- result match
                      case ConditionalWrite.Applied(_) =>
                        ZIO.succeed(SubagentTerminalAcknowledgeResult.Acknowledged(at))
                      case ConditionalWrite.Conflict if remaining > 0 =>
                        attempt(remaining - 1, Some(at))
                      case ConditionalWrite.Conflict =>
                        ZIO.fail(
                          AgentError.StorageError(
                            "Terminal acknowledgement exhausted its bounded conflict retries"
                          )
                        )
                  yield receipt
      attempt(limits.maxConflicts, None)

    def publish(record: SubagentTerminalRecord): IO[AgentError, SubagentTerminalPublication] =
      def attempt(remaining: Int): IO[AgentError, SubagentTerminalPublication] =
        snapshot.flatMap: (revision, records) =>
          (revision, records.find(_.id == record.id.value)) match
            case (Some(current), Some(existing)) if existing.record == record =>
              ZIO.succeed(SubagentTerminalPublication.AlreadyStored(current))
            case (_, Some(_)) =>
              ZIO.fail(
                AgentError.StorageError(
                  "A different terminal observation already occupies this task ID"
                )
              )
            case (_, None) =>
              for
                value <- ZIO.fromEither(
                  encode(
                    records :+ Entry(record.id.value, record.outcome, workerId = record.workerId)
                  )
                )
                result <- store.compareAndSet(address, expectedRevision(revision), value)
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

    private def expectedRevision(revision: Option[StorageRevision]): ExpectedRevision =
      revision.fold[ExpectedRevision](ExpectedRevision.Absent)(ExpectedRevision.AtRevision(_))

    private def snapshot: IO[AgentError, (Option[StorageRevision], Chunk[Entry])] =
      store
        .read(address)
        .flatMap:
          case None => ZIO.succeed((None, Chunk.empty))
          case Some(record) =>
            ZIO.fromEither(decode(record.value)).map(records => (Some(record.revision), records))

    private def encode(records: Chunk[Entry]): Either[AgentError, Json] =
      for
        value <- Payload(
          3,
          records
        ).toJsonAST.left.map(AgentError.StorageError.apply)
        _ <- decode(value)
      yield value

    private def decode(
        value: Json
    ): Either[AgentError.StorageError, Chunk[Entry]] =
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
        version <- rendered.fromJson[Version].left.map(AgentError.StorageError.apply)
        decoded <- version.schemaVersion match
          case 1 =>
            for
              payload   <- rendered.fromJson[LegacyPayload].left.map(AgentError.StorageError.apply)
              canonical <- payload.toJsonAST.left.map(AgentError.StorageError.apply)
            yield (payload.records.map(entry => Entry(entry.id, entry.outcome)), canonical)
          case 2 =>
            for
              payload   <- rendered.fromJson[Schema2Payload].left.map(AgentError.StorageError.apply)
              canonical <- payload.toJsonAST.left.map(AgentError.StorageError.apply)
            yield (
              payload.records.map(entry => Entry(entry.id, entry.outcome, entry.acknowledgedAt)),
              canonical
            )
          case 3 =>
            for
              payload   <- rendered.fromJson[Payload].left.map(AgentError.StorageError.apply)
              canonical <- payload.toJsonAST.left.map(AgentError.StorageError.apply)
            yield (payload.records, canonical)
          case _ => Left(AgentError.StorageError("Unsupported terminal aggregate schema version"))
        (records, canonical) = decoded
        _ <- Either.cond(
          records.size <= limits.maxRecords &&
            records.map(_.id).toSet.size == records.size &&
            JsonBoundary.sameRepresentation(validated, canonical),
          (),
          AgentError.StorageError(
            "Duplicate, oversized or noncanonical terminal aggregate"
          )
        )
      yield records
