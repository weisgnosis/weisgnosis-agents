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
import com.weisgnosis.agents.harness.storage.*
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import zio.*
import zio.json.*
import zio.json.ast.Json

/** Bounds the complete encoded aggregate and all raw ledger/archive entries. */
final class VersionedMemoryLimits private (val maxBytes: Int, val maxEntries: Int)

object VersionedMemoryLimits:
  val default: VersionedMemoryLimits = new VersionedMemoryLimits(1024 * 1024, 4096)

  def make(
      maxBytes: Int,
      maxEntries: Int
  ): Either[AgentError.InvalidConfiguration, VersionedMemoryLimits] =
    Either.cond(
      maxBytes > 0 && maxEntries > 0,
      new VersionedMemoryLimits(maxBytes, maxEntries),
      AgentError.InvalidConfiguration("Memory aggregate byte and entry limits must be positive")
    )

/** One host-selected persistent aggregate; only definite CAS conflicts authorize retries. */
object VersionedMemoryRepository:
  def make(
      store: VersionedJsonStore,
      address: StorageAddress,
      maxAttempts: Int = 8,
      limits: VersionedMemoryLimits = VersionedMemoryLimits.default
  ): Either[AgentError.InvalidConfiguration, MemoryRepository] =
    Either.cond(
      maxAttempts > 0,
      new Backend(store, address, maxAttempts, limits).repository,
      AgentError.InvalidConfiguration("Memory publication attempts must be positive")
    )

  final private case class ArchivedMemory(archive: DailyMemoryArchive, content: String)
      derives JsonCodec
  final private case class Payload(
      schemaVersion: Int,
      @jsonExplicitNull curated: Option[String],
      daily: Chunk[DailyMemory],
      acknowledged: Chunk[DailyMemory],
      archived: Chunk[ArchivedMemory]
  ) derives JsonCodec
  final private case class Loaded(revision: ExpectedRevision, data: MemoryRepositoryState)

  final private class Backend(
      store: VersionedJsonStore,
      address: StorageAddress,
      maxAttempts: Int,
      limits: VersionedMemoryLimits
  ):
    def repository: MemoryRepository =
      val daily = new DailyMemoryStore:
        def readCurated: IO[AgentError, Option[String]] = read.map(_.data.curated)
        def readDaily(day: LocalDate): IO[AgentError, Option[String]] =
          read.map(_.data.daily.get(day))
        override def days: IO[AgentError, Chunk[LocalDate]] =
          read.map(value => Chunk.fromIterable(value.data.daily.keys.toList.sortBy(_.toEpochDay)))
        def appendDaily(day: LocalDate, entry: String): IO[AgentError, Unit] =
          if entry.isEmpty then ZIO.unit
          else modify(data => ZIO.succeed(() -> data.append(day, entry)), maxAttempts)
      val consolidation = new ConsolidationStore:
        type Revision = ExpectedRevision
        def snapshot: IO[AgentError, ConsolidationSnapshot[Revision]] =
          read.map(current => current.data.snapshot(current.revision))
        def commit(expected: Revision, content: String): IO[AgentError, ConsolidationCommit] =
          read.flatMap: current =>
            if current.revision != expected then ZIO.succeed(ConsolidationCommit.Conflict)
            else
              publish(expected, current.data.consolidate(content)).map:
                case ConditionalWrite.Applied(_) => ConsolidationCommit.Applied
                case ConditionalWrite.Conflict   => ConsolidationCommit.Conflict
      val retention = new DailyMemoryRetention:
        def archiveBefore(cutoff: LocalDate): IO[AgentError, Chunk[DailyMemoryArchive]] =
          modify(_.archiveBefore(cutoff), maxAttempts)
        def archives: IO[AgentError, Chunk[DailyMemoryArchive]] = read.map(_.data.archives)
        def readArchive(archive: DailyMemoryArchive): IO[AgentError, Option[String]] =
          read.map(_.data.archived.get(archive))
      new MemoryRepository(daily, consolidation, retention)

    private def read: IO[AgentError, Loaded] =
      store
        .read(address)
        .flatMap:
          case None => ZIO.succeed(Loaded(ExpectedRevision.Absent, MemoryRepositoryState.empty()))
          case Some(record) =>
            decode(record.value, limits).map(
              Loaded(ExpectedRevision.AtRevision(record.revision), _)
            )

    private def publish(
        expected: ExpectedRevision,
        data: MemoryRepositoryState
    ): IO[AgentError, ConditionalWrite] =
      ZIO
        .fromEither(encode(data, limits))
        .flatMap(value => store.compareAndSet(address, expected, value))

    private def modify[A](
        transition: MemoryRepositoryState => IO[AgentError, (A, MemoryRepositoryState)],
        attempts: Int
    ): IO[AgentError, A] =
      read.flatMap: current =>
        transition(current.data).flatMap: (result, next) =>
          if next == current.data then ZIO.succeed(result)
          else
            publish(current.revision, next).flatMap:
              case ConditionalWrite.Applied(_) => ZIO.succeed(result)
              case ConditionalWrite.Conflict if attempts > 1 =>
                ZIO.yieldNow *> ZIO.suspendSucceed(modify(transition, attempts - 1))
              case ConditionalWrite.Conflict =>
                ZIO.fail(
                  AgentError.MemoryStorageError("Memory publication conflict retry limit exceeded")
                )

  private def encode(
      state: MemoryRepositoryState,
      limits: VersionedMemoryLimits
  ): Either[AgentError.MemoryStorageError, Json] =
    if state.daily.size.toLong + state.acknowledged.size.toLong + state.archived.size.toLong > limits.maxEntries
    then Left(AgentError.MemoryStorageError("Memory aggregate entry limit exceeded"))
    else encodeAdmitted(state, limits)

  private def encodeAdmitted(
      state: MemoryRepositoryState,
      limits: VersionedMemoryLimits
  ): Either[AgentError.MemoryStorageError, Json] =
    val payload = Payload(
      1,
      state.curated,
      entries(state.daily),
      entries(state.acknowledged),
      Chunk.fromIterable(
        state.archived.toList
          .sortBy((archive, _) => (archive.day.toEpochDay, archive.fingerprint.value))
          .map((archive, content) => ArchivedMemory(archive, content))
      )
    )
    if !validTextFields(payload) then
      Left(AgentError.MemoryStorageError("Memory contains malformed Unicode"))
    else
      JsonBoundary
        .decode(payload.toJson)
        .left
        .map(_ => AgentError.MemoryStorageError("Memory encoding failed"))
        .flatMap(value => admit(value, limits).map(_ => value))

  private def entries(values: Map[LocalDate, String]): Chunk[DailyMemory] =
    Chunk.fromIterable(
      values.toList.sortBy(_._1.toEpochDay).map((day, content) => DailyMemory(day, content))
    )

  private def admit(
      value: Json,
      limits: VersionedMemoryLimits
  ): Either[AgentError.MemoryStorageError, Unit] =
    val byteSize = JsonBoundary.render(value).getBytes(StandardCharsets.UTF_8).length
    val entryCount = value match
      case root: Json.Obj =>
        Chunk("daily", "acknowledged", "archived").foldLeft(0L): (total, name) =>
          root.get(name) match
            case Some(Json.Arr(entries)) => total + entries.size.toLong
            case _                       => total
      case _ => 0L
    if byteSize > limits.maxBytes then
      Left(AgentError.MemoryStorageError("Memory aggregate byte limit exceeded"))
    else if entryCount > limits.maxEntries then
      Left(AgentError.MemoryStorageError("Memory aggregate entry limit exceeded"))
    else Right(())

  private def decode(
      value: Json,
      limits: VersionedMemoryLimits
  ): IO[AgentError, MemoryRepositoryState] =
    val parsed = for
      _         <- admit(value, limits).left.map(_.message)
      validated <- JsonBoundary.validate(value).left.map(_ => "Duplicate memory fields")
      _         <- Either.cond(validPayloadShape(validated), (), "Invalid memory schema fields")
      payload <- JsonBoundary
        .render(validated)
        .fromJson[Payload]
        .left
        .map(_ => "Invalid memory schema")
      _ <- Either.cond(payload.schemaVersion == 1, (), "Unsupported memory schema version")
      _ <- Either.cond(validTextFields(payload), (), "Memory contains malformed Unicode")
      _ <- Either.cond(
        payload.daily.map(_.day).distinct.size == payload.daily.size &&
          payload.acknowledged.map(_.day).distinct.size == payload.acknowledged.size &&
          payload.archived.map(_.archive).distinct.size == payload.archived.size,
        (),
        "Duplicate memory identities"
      )
      _ <- Either.cond(
        (payload.daily ++ payload.acknowledged).forall(_.content.nonEmpty) &&
          payload.archived.forall(_.content.nonEmpty),
        (),
        "Empty memory ledger content"
      )
      _ <- Either.cond(
        payload.curated.nonEmpty || (payload.acknowledged.isEmpty && payload.archived.isEmpty),
        (),
        "Memory progress requires curated content"
      )
      daily        = payload.daily.map(entry => entry.day -> entry.content).toMap
      acknowledged = payload.acknowledged.map(entry => entry.day -> entry.content).toMap
      _ <- Either.cond(
        acknowledged.forall((day, content) => daily.get(day).exists(_.startsWith(content))),
        (),
        "Invalid memory acknowledgement"
      )
    yield payload -> MemoryRepositoryState(
      payload.curated,
      daily,
      acknowledged,
      payload.archived.map(entry => entry.archive -> entry.content).toMap
    )
    ZIO
      .fromEither(parsed.left.map(AgentError.MemoryStorageError(_)))
      .flatMap: (payload, state) =>
        ZIO
          .foreachDiscard(payload.archived): entry =>
            MemoryFingerprint
              .of(entry.content)
              .flatMap(fingerprint =>
                ZIO
                  .fail(AgentError.MemoryStorageError("Invalid memory archive fingerprint"))
                  .unless(fingerprint == entry.archive.fingerprint)
              )
          .as(state)

  private def validPayloadShape(value: Json): Boolean =
    def fields(value: Json, names: Set[String]): Boolean = value match
      case Json.Obj(entries) => entries.map(_._1).toSet == names
      case _                 => false
    def ledger(value: Json): Boolean = fields(value, Set("day", "content"))
    def archive(value: Json): Boolean = value match
      case root: Json.Obj =>
        fields(root, Set("archive", "content")) &&
        root.get("archive").exists(fields(_, Set("day", "fingerprint")))
      case _ => false
    def array(root: Json.Obj, name: String, validEntry: Json => Boolean): Boolean =
      root.get(name) match
        case Some(Json.Arr(entries)) => entries.forall(validEntry)
        case _                       => false
    value match
      case root: Json.Obj =>
        fields(root, Set("schemaVersion", "curated", "daily", "acknowledged", "archived")) &&
        array(root, "daily", ledger) && array(root, "acknowledged", ledger) && array(
          root,
          "archived",
          archive
        )
      case _ => false

  private def validTextFields(payload: Payload): Boolean =
    val fields = payload.curated.toList ++ payload.daily.map(_.content).toList ++
      payload.acknowledged.map(_.content).toList ++ payload.archived.map(_.content).toList
    fields.forall(text =>
      text.indices.forall: index =>
        val character = text.charAt(index)
        if Character.isHighSurrogate(character) then
          text.lift(index + 1).exists(Character.isLowSurrogate)
        else if Character.isLowSurrogate(character) then
          text.lift(index - 1).exists(Character.isHighSurrogate)
        else true
    )
