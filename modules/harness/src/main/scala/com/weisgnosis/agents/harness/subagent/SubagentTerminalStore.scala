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

  /** Complete durable observations, including unresolved admissions; no execution handles are restored. */
  def taskRecords: IO[AgentError, Chunk[SubagentTaskRecord]] =
    ZIO.fail(
      AgentError.InvalidConfiguration("This terminal store does not support durable admission")
    )
  def taskRecord(id: SubagentTaskId): IO[AgentError, Option[SubagentTaskRecord]] =
    taskRecords.map(_.find(_.id == id))
  def admit(
      id: SubagentTaskId,
      workerId: Option[SubagentWorkerId] = None
  ): IO[AgentError, SubagentTaskAdmissionResult] =
    ZIO.fail(
      AgentError.InvalidConfiguration(
        s"Durable admission is unsupported for ${id.value} and worker $workerId"
      )
    )

  /** None means admission metadata is unavailable; custom stores override to persist check time. */
  def markChecked(id: SubagentTaskId): IO[AgentError, Option[SubagentTaskMetadata]] =
    read(id).as(None)

  def markRunning(identity: SubagentTaskAdmissionIdentity): IO[AgentError, Unit] =
    ZIO.fail(
      AgentError.InvalidConfiguration(s"Durable start is unsupported for ${identity.id.value}")
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
  final private case class Schema3Entry(
      id: UUID,
      outcome: SubagentTerminalOutcome,
      acknowledgedAt: Option[Instant] = None,
      workerId: Option[SubagentWorkerId] = None
  ) derives JsonCodec
  final private case class Schema3Payload(schemaVersion: Int, records: Chunk[Schema3Entry])
      derives JsonCodec

  final private case class Entry(
      id: UUID,
      workerId: Option[SubagentWorkerId],
      state: SubagentTaskRecordState,
      metadata: Option[SubagentTaskMetadata]
  ) derives JsonCodec:
    def record: SubagentTaskRecord =
      SubagentTaskRecord(SubagentTaskId(id), workerId, state, metadata)
    def terminal: Option[SubagentTerminalRecord] = record.terminal
    def matches(identity: SubagentTaskAdmissionIdentity): Boolean =
      id == identity.id.value && workerId == identity.workerId && metadata.exists(
        _.createdAt == identity.createdAt
      )

  final private case class Payload(schemaVersion: Int, records: Chunk[Entry]) derives JsonCodec
  final private case class Version(schemaVersion: Int) derives JsonCodec

  final private class Backend(store: VersionedJsonStore, address: StorageAddress, limits: Limits)
      extends SubagentTerminalStore:
    override def taskRecords: IO[AgentError, Chunk[SubagentTaskRecord]] =
      snapshot.map(_._2.map(_.record))
    override def taskRecord(id: SubagentTaskId): IO[AgentError, Option[SubagentTaskRecord]] =
      taskRecords.map(_.find(_.id == id))

    def list: IO[AgentError, Chunk[SubagentTerminalRecord]] = snapshot.map(_._2.flatMap(_.terminal))
    def read(id: SubagentTaskId): IO[AgentError, Option[SubagentTerminalRecord]] =
      list.map(_.find(_.id == id))

    override def pending: IO[AgentError, Chunk[SubagentTerminalRecord]] = snapshot.map(
      _._2.flatMap(entry =>
        entry.state match
          case SubagentTaskRecordState.Terminal(_, SubagentTerminalAcknowledgement.Pending) =>
            entry.terminal
          case _ => None
      )
    )

    override def acknowledgement(
        id: SubagentTaskId
    ): IO[AgentError, Option[SubagentTerminalAcknowledgement]] =
      snapshot.map(
        _._2
          .find(_.id == id.value)
          .flatMap(entry =>
            entry.state match
              case SubagentTaskRecordState.Terminal(_, acknowledgement) => Some(acknowledgement)
              case _                                                    => None
          )
      )

    override def admit(
        id: SubagentTaskId,
        workerId: Option[SubagentWorkerId]
    ): IO[AgentError, SubagentTaskAdmissionResult] =
      def attempt(
          remaining: Int,
          candidate: Option[Instant]
      ): IO[AgentError, SubagentTaskAdmissionResult] =
        snapshot.flatMap: (revision, records) =>
          records.find(_.id == id.value) match
            case Some(existing) =>
              ZIO.succeed(SubagentTaskAdmissionResult.Existing(existing.record))
            case None =>
              for
                at <- candidate.fold[UIO[Instant]](Clock.instant)(ZIO.succeed(_))
                entry = Entry(
                  id.value,
                  workerId,
                  SubagentTaskRecordState.Pending,
                  Some(SubagentTaskMetadata(at, at, at))
                )
                result <- write(revision, records :+ entry)
                admitted <- result match
                  case ConditionalWrite.Applied(_) =>
                    ZIO.succeed(
                      SubagentTaskAdmissionResult.Created(
                        SubagentTaskAdmissionIdentity(id, workerId, at)
                      )
                    )
                  case ConditionalWrite.Conflict if remaining > 0 =>
                    attempt(remaining - 1, Some(at))
                  case ConditionalWrite.Conflict => exhausted("Task admission")
              yield admitted
      attempt(limits.maxConflicts, None)

    override def markRunning(identity: SubagentTaskAdmissionIdentity): IO[AgentError, Unit] =
      def attempt(remaining: Int, candidate: Option[Instant]): IO[AgentError, Unit] =
        snapshot.flatMap: (revision, records) =>
          records.find(_.id == identity.id.value) match
            case Some(existing) if existing.matches(identity) =>
              existing.state match
                case SubagentTaskRecordState.Running => ZIO.unit
                case SubagentTaskRecordState.Pending =>
                  for
                    at <- candidate.fold[UIO[Instant]](Clock.instant)(ZIO.succeed(_))
                    updated = existing.copy(
                      state = SubagentTaskRecordState.Running,
                      metadata = existing.metadata.map(_.copy(lastUpdatedAt = at))
                    )
                    result <- write(
                      revision,
                      records.map(entry => if entry.id == existing.id then updated else entry)
                    )
                    _ <- result match
                      case ConditionalWrite.Applied(_) => ZIO.unit
                      case ConditionalWrite.Conflict if remaining > 0 =>
                        attempt(remaining - 1, Some(at))
                      case ConditionalWrite.Conflict => exhausted("Task start")
                  yield ()
                case SubagentTaskRecordState.Terminal(_, _) =>
                  ZIO.fail(
                    AgentError.StorageError(
                      "The admitted task is already terminal; execution cannot start"
                    )
                  )
            case _ =>
              ZIO.fail(
                AgentError.StorageError("Task start does not match a confirmed durable admission")
              )
      attempt(limits.maxConflicts, None)

    override def markChecked(
        id: SubagentTaskId
    ): IO[AgentError, Option[SubagentTaskMetadata]] =
      def attempt(
          remaining: Int,
          candidate: Option[Instant]
      ): IO[AgentError, Option[SubagentTaskMetadata]] =
        snapshot.flatMap: (revision, records) =>
          records.find(_.id == id.value) match
            case Some(existing) =>
              existing.metadata match
                case None => ZIO.none
                case Some(metadata) =>
                  for
                    at <- candidate.fold[UIO[Instant]](Clock.instant)(ZIO.succeed(_))
                    checked = metadata.copy(lastCheckedAt = at)
                    updated = existing.copy(metadata = Some(checked))
                    result <- write(
                      revision,
                      records.map(entry => if entry.id == existing.id then updated else entry)
                    )
                    value <- result match
                      case ConditionalWrite.Applied(_) => ZIO.some(checked)
                      case ConditionalWrite.Conflict if remaining > 0 =>
                        attempt(remaining - 1, Some(at))
                      case ConditionalWrite.Conflict => exhausted("Task check")
                  yield value
            case None => ZIO.none
      attempt(limits.maxConflicts, None)

    override def acknowledge(
        id: SubagentTaskId
    ): IO[AgentError, SubagentTerminalAcknowledgeResult] =
      def attempt(
          remaining: Int,
          candidate: Option[Instant]
      ): IO[AgentError, SubagentTerminalAcknowledgeResult] =
        snapshot.flatMap: (revision, records) =>
          records.find(_.id == id.value) match
            case Some(existing) =>
              existing.state match
                case SubagentTaskRecordState.Terminal(
                      _,
                      SubagentTerminalAcknowledgement.Acknowledged(at)
                    ) =>
                  ZIO.succeed(SubagentTerminalAcknowledgeResult.AlreadyAcknowledged(at))
                case SubagentTaskRecordState.Terminal(
                      outcome,
                      SubagentTerminalAcknowledgement.Pending
                    ) =>
                  for
                    at <- candidate.fold[UIO[Instant]](Clock.instant)(ZIO.succeed(_))
                    updated = existing.copy(state =
                      SubagentTaskRecordState.Terminal(
                        outcome,
                        SubagentTerminalAcknowledgement.Acknowledged(at)
                      )
                    )
                    result <- write(
                      revision,
                      records.map(entry => if entry.id == existing.id then updated else entry)
                    )
                    receipt <- result match
                      case ConditionalWrite.Applied(_) =>
                        ZIO.succeed(SubagentTerminalAcknowledgeResult.Acknowledged(at))
                      case ConditionalWrite.Conflict if remaining > 0 =>
                        attempt(remaining - 1, Some(at))
                      case ConditionalWrite.Conflict => exhausted("Terminal acknowledgement")
                  yield receipt
                case _ => ZIO.succeed(SubagentTerminalAcknowledgeResult.Missing)
            case None => ZIO.succeed(SubagentTerminalAcknowledgeResult.Missing)
      attempt(limits.maxConflicts, None)

    def publish(record: SubagentTerminalRecord): IO[AgentError, SubagentTerminalPublication] =
      def attempt(
          remaining: Int,
          candidate: Option[Instant]
      ): IO[AgentError, SubagentTerminalPublication] =
        snapshot.flatMap: (revision, records) =>
          records.find(_.id == record.id.value) match
            case Some(existing) if existing.terminal.nonEmpty =>
              revision match
                case Some(current) if existing.terminal.contains(record) =>
                  ZIO.succeed(SubagentTerminalPublication.AlreadyStored(current))
                case _ =>
                  ZIO.fail(
                    AgentError.StorageError(
                      "A different terminal observation already occupies this task ID"
                    )
                  )
            case Some(existing) if existing.workerId != record.workerId =>
              ZIO.fail(
                AgentError.StorageError(
                  "Terminal observation does not match the admitted worker identity"
                )
              )
            case existing =>
              for
                at <- existing.fold[UIO[Option[Instant]]](ZIO.none)(_ =>
                  candidate.fold[UIO[Instant]](Clock.instant)(ZIO.succeed(_)).map(Some(_))
                )
                entry = existing.fold(
                  Entry(
                    record.id.value,
                    record.workerId,
                    SubagentTaskRecordState
                      .Terminal(record.outcome, SubagentTerminalAcknowledgement.Pending),
                    None
                  )
                )(entry =>
                  entry.copy(
                    state = SubagentTaskRecordState
                      .Terminal(record.outcome, SubagentTerminalAcknowledgement.Pending),
                    metadata = entry.metadata.map(metadata =>
                      at.fold(metadata)(time => metadata.copy(lastUpdatedAt = time))
                    )
                  )
                )
                updated = records.filterNot(_.id == entry.id) :+ entry
                result <- write(revision, updated)
                receipt <- result match
                  case ConditionalWrite.Applied(revision) =>
                    ZIO.succeed(SubagentTerminalPublication.Stored(revision))
                  case ConditionalWrite.Conflict if remaining > 0 =>
                    attempt(remaining - 1, at.orElse(candidate))
                  case ConditionalWrite.Conflict => exhausted("Terminal publication")
              yield receipt
      attempt(limits.maxConflicts, None)

    private def exhausted[A](operation: String): IO[AgentError, A] =
      ZIO.fail(AgentError.StorageError(s"$operation exhausted its bounded conflict retries"))

    private def write(
        revision: Option[StorageRevision],
        records: Chunk[Entry]
    ): IO[AgentError, ConditionalWrite] =
      ZIO
        .fromEither(encode(records))
        .flatMap(value =>
          store.compareAndSet(
            address,
            revision
              .fold[ExpectedRevision](ExpectedRevision.Absent)(ExpectedRevision.AtRevision(_)),
            value
          )
        )

    private def snapshot: IO[AgentError, (Option[StorageRevision], Chunk[Entry])] = store
      .read(address)
      .flatMap:
        case None => ZIO.succeed((None, Chunk.empty))
        case Some(record) =>
          ZIO.fromEither(decode(record.value)).map(records => (Some(record.revision), records))

    private def encode(records: Chunk[Entry]): Either[AgentError, Json] =
      for
        value <- Payload(4, records).toJsonAST.left.map(AgentError.StorageError.apply)
        _     <- decode(value)
      yield value

    private def legacy(
        id: UUID,
        outcome: SubagentTerminalOutcome,
        acknowledgedAt: Option[Instant],
        worker: Option[SubagentWorkerId]
    ): Entry =
      Entry(
        id,
        worker,
        SubagentTaskRecordState.Terminal(
          outcome,
          acknowledgedAt.fold[SubagentTerminalAcknowledgement](
            SubagentTerminalAcknowledgement.Pending
          )(SubagentTerminalAcknowledgement.Acknowledged(_))
        ),
        None
      )

    private def decode(value: Json): Either[AgentError.StorageError, Chunk[Entry]] =
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
            yield (
              payload.records.map(entry => legacy(entry.id, entry.outcome, None, None)),
              canonical
            )
          case 2 =>
            for
              payload   <- rendered.fromJson[Schema2Payload].left.map(AgentError.StorageError.apply)
              canonical <- payload.toJsonAST.left.map(AgentError.StorageError.apply)
            yield (
              payload.records.map(entry =>
                legacy(entry.id, entry.outcome, entry.acknowledgedAt, None)
              ),
              canonical
            )
          case 3 =>
            for
              payload   <- rendered.fromJson[Schema3Payload].left.map(AgentError.StorageError.apply)
              canonical <- payload.toJsonAST.left.map(AgentError.StorageError.apply)
            yield (
              payload.records.map(entry =>
                legacy(entry.id, entry.outcome, entry.acknowledgedAt, entry.workerId)
              ),
              canonical
            )
          case 4 =>
            for
              payload   <- rendered.fromJson[Payload].left.map(AgentError.StorageError.apply)
              canonical <- payload.toJsonAST.left.map(AgentError.StorageError.apply)
            yield (payload.records, canonical)
          case _ => Left(AgentError.StorageError("Unsupported terminal aggregate schema version"))
        (records, canonical) = decoded
        _ <- Either.cond(
          records.size <= limits.maxRecords && records
            .map(_.id)
            .toSet
            .size == records.size && JsonBoundary.sameRepresentation(
            validated,
            canonical
          ) && records.forall(entry => entry.terminal.nonEmpty || entry.metadata.nonEmpty),
          (),
          AgentError.StorageError("Duplicate, oversized or noncanonical terminal aggregate")
        )
      yield records
