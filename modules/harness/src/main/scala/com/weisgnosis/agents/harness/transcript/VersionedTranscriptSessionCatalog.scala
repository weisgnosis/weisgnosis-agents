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
package com.weisgnosis.agents.harness.transcript

import com.weisgnosis.agents.core.error.AgentError
import scala.annotation.unused
import com.weisgnosis.agents.core.json.JsonBoundary
import com.weisgnosis.agents.harness.storage.*
import zio.*
import zio.json.*
import zio.json.ast.Json

enum TranscriptLayout derives JsonCodec:
  case Aggregate, Segmented

/** Host-declared interpretation and correlation; registration does not validate transcript contents. */
final class TranscriptSession private (
    val id: TranscriptTargetId,
    val manifest: StorageAddress,
    val layout: TranscriptLayout,
    private[transcript] val reservedNamespace: Option[Chunk[String]]
):
  override def equals(other: Any): Boolean = other match
    case session: TranscriptSession =>
      id == session.id && manifest == session.manifest && layout == session.layout
    case _ => false

  override def hashCode(): Int = (id, manifest, layout).hashCode()

object TranscriptSession:
  final private case class Wire(
      id: TranscriptTargetId,
      manifest: StorageAddress,
      layout: TranscriptLayout
  ) derives JsonCodec

  def make(
      id: TranscriptTargetId,
      manifest: StorageAddress,
      layout: TranscriptLayout
  ): Either[AgentError.InvalidConfiguration, TranscriptSession] =
    for
      _ <- Either.cond(
        JsonBoundary.validUnicode(Json.Str(id.value)),
        (),
        AgentError.InvalidConfiguration("Transcript session ID must contain valid Unicode")
      )
      reserved <- layout match
        case TranscriptLayout.Aggregate => Right(None)
        case TranscriptLayout.Segmented =>
          SegmentedTranscriptStore.segmentNamespace(manifest).map(Some(_))
    yield new TranscriptSession(id, manifest, layout, reserved)

  /** Checked decoding enforces ID Unicode and the selected layout's address bounds. */
  given JsonCodec[TranscriptSession] = JsonCodec[Wire].transformOrFail(
    wire => make(wire.id, wire.manifest, wire.layout).left.map(_.message),
    session => Wire(session.id, session.manifest, session.layout)
  )

enum TranscriptRegistration derives JsonCodec:
  case Added, Existing

enum TranscriptRetirement derives JsonCodec:
  case Missing, AlreadyRetired, Retired

/**
 * Trusted host fence for the exact catalog partition and session binding. The host must exclude
 * every writer while this scoped grant is held, including future admissions. Catalog CAS alone
 * does not fence writers. Cleanup must join before the grant is released; no deletion is implied.
 */
trait TranscriptRetirementAuthority:
  def acquire(
      catalog: StorageAddress,
      expected: TranscriptSession
  ): ZIO[Scope, AgentError, Unit]

/** One explicit host catalog; membership grants no execution, inactivity or retention authority. */
trait TranscriptSessionCatalog:
  def sessions: IO[AgentError, Chunk[TranscriptSession]]
  def register(session: TranscriptSession): IO[AgentError, TranscriptRegistration]

  /** Membership retirement only; custom catalogs must explicitly implement this capability. */
  def retire(
      @unused expected: TranscriptSession,
      @unused authority: TranscriptRetirementAuthority
  ): IO[AgentError, TranscriptRetirement] =
    ZIO.fail(AgentError.MemoryStorageError("Transcript catalog retirement is unsupported"))

object VersionedTranscriptSessionCatalog:
  def make(
      store: VersionedJsonStore,
      address: StorageAddress,
      maxSessions: Int = 256,
      maxAttempts: Int = 8
  ): Either[AgentError.InvalidConfiguration, TranscriptSessionCatalog] =
    Either.cond(
      maxSessions > 0 && maxAttempts > 0,
      new Backend(store, address, maxSessions, maxAttempts),
      AgentError.InvalidConfiguration(
        "Transcript catalog session and publication limits must be positive"
      )
    )

  // Legacy canonical representation is checked before normalization, without writing it back.
  final private case class LegacyPayload(schemaVersion: Int, sessions: Chunk[TranscriptSession])
      derives JsonCodec
  private enum Entry derives JsonCodec:
    case Active(binding: TranscriptSession)
    case Retired(binding: TranscriptSession)

    def session: TranscriptSession = this match
      case Active(value)  => value
      case Retired(value) => value

  final private case class Payload(schemaVersion: Int, entries: Chunk[Entry]) derives JsonCodec
  final private case class Loaded(revision: ExpectedRevision, entries: Chunk[Entry])

  final private class Backend(
      store: VersionedJsonStore,
      address: StorageAddress,
      maxSessions: Int,
      maxAttempts: Int
  ) extends TranscriptSessionCatalog:
    def sessions: IO[AgentError, Chunk[TranscriptSession]] =
      read.map(_.entries.collect { case Entry.Active(session) => session })

    def register(session: TranscriptSession): IO[AgentError, TranscriptRegistration] =
      if collidesWithCatalog(session) then
        ZIO.fail(invalid("Transcript layout overlaps its catalog"))
      else publish(session, maxAttempts)

    private def collidesWithCatalog(session: TranscriptSession): Boolean =
      session.manifest == address || session.reservedNamespace.exists(
        address.namespace.startsWith(_)
      )

    private def read: IO[AgentError, Loaded] =
      store
        .read(address)
        .flatMap:
          case None => ZIO.succeed(Loaded(ExpectedRevision.Absent, Chunk.empty))
          case Some(record) =>
            val decoded = for
              value <- JsonBoundary.validate(record.value).left.map(invalid)
              _ <- Either.cond(
                JsonBoundary.validUnicode(value),
                (),
                invalid("Malformed transcript catalog Unicode")
              )
              entries <- decode(value)
              bindings = entries.map(_.session)
              _ <- Either.cond(
                entries.size <= maxSessions && bindings == bindings.sortBy(_.id.value) &&
                  bindings.map(_.id).distinct.size == bindings.size &&
                  bindings.forall(session => !collidesWithCatalog(session)) &&
                  bindings.zipWithIndex.forall((session, index) =>
                    !bindings.take(index).exists(conflicts(session, _))
                  ),
                (),
                invalid("Invalid transcript catalog identities, ordering or capacity")
              )
            yield Loaded(ExpectedRevision.AtRevision(record.revision), entries)
            ZIO.fromEither(decoded)

    private def decode(value: Json): Either[AgentError, Chunk[Entry]] =
      val rendered = JsonBoundary.render(value)
      value.asObject.flatMap(_.get("schemaVersion")) match
        case Some(Json.Num(version)) if BigDecimal(version) == BigDecimal(1) =>
          for
            payload <- rendered
              .fromJson[LegacyPayload]
              .left
              .map(_ => invalid("Invalid legacy transcript catalog"))
            canonical <- payload.toJsonAST.left.map(invalid)
            _ <- Either.cond(
              JsonBoundary.sameRepresentation(value, canonical),
              (),
              invalid("Noncanonical legacy transcript catalog")
            )
          yield payload.sessions.map(Entry.Active.apply)
        case Some(Json.Num(version)) if BigDecimal(version) == BigDecimal(2) =>
          for
            payload <- rendered
              .fromJson[Payload]
              .left
              .map(_ => invalid("Invalid transcript catalog schema"))
            canonical <- payload.toJsonAST.left.map(invalid)
            _ <- Either.cond(
              JsonBoundary.sameRepresentation(value, canonical),
              (),
              invalid("Noncanonical transcript catalog")
            )
          yield payload.entries
        case _ => Left(invalid("Unsupported transcript catalog schema"))

    private def write(current: Loaded, entries: Chunk[Entry]): IO[AgentError, ConditionalWrite] =
      ZIO
        .fromEither(Payload(2, entries).toJsonAST.left.map(invalid))
        .flatMap: value =>
          store.compareAndSet(address, current.revision, value)

    private def publish(
        session: TranscriptSession,
        attempts: Int
    ): IO[AgentError, TranscriptRegistration] =
      read.flatMap: current =>
        if current.entries.contains(Entry.Active(session)) then
          ZIO.succeed(TranscriptRegistration.Existing)
        else if current.entries.exists(entry =>
            entry.session.id == session.id || conflicts(session, entry.session)
          )
        then ZIO.fail(invalid("Conflicting or retired transcript session binding"))
        else if current.entries.size >= maxSessions then
          ZIO.fail(invalid("Transcript catalog capacity exceeded"))
        else
          write(current, (current.entries :+ Entry.Active(session)).sortBy(_.session.id.value))
            .flatMap:
              case ConditionalWrite.Applied(_) => ZIO.succeed(TranscriptRegistration.Added)
              case ConditionalWrite.Conflict if attempts > 1 =>
                ZIO.yieldNow *> ZIO.suspendSucceed(publish(session, attempts - 1))
              case ConditionalWrite.Conflict =>
                ZIO.fail(invalid("Transcript catalog conflict retry limit exceeded"))

    override def retire(
        expected: TranscriptSession,
        authority: TranscriptRetirementAuthority
    ): IO[AgentError, TranscriptRetirement] =
      read.flatMap: current =>
        retirementState(current, expected).flatMap:
          case Some(result) => ZIO.succeed(result)
          case None =>
            ZIO.scoped(
              ZIO.suspendSucceed(authority.acquire(address, expected)) *>
                ZIO.suspendSucceed(retireConfirmed(expected, maxAttempts))
            )

    private def retirementState(
        current: Loaded,
        expected: TranscriptSession
    ): IO[AgentError, Option[TranscriptRetirement]] =
      current.entries.find(_.session.id == expected.id) match
        case None => ZIO.succeed(Some(TranscriptRetirement.Missing))
        case Some(entry) if entry.session != expected =>
          ZIO.fail(invalid("Transcript retirement binding does not match catalog"))
        case Some(Entry.Retired(_)) => ZIO.succeed(Some(TranscriptRetirement.AlreadyRetired))
        case Some(Entry.Active(_))  => ZIO.succeed(None)

    private def retireConfirmed(
        expected: TranscriptSession,
        attempts: Int
    ): IO[AgentError, TranscriptRetirement] =
      read.flatMap: current =>
        retirementState(current, expected).flatMap:
          case Some(result) => ZIO.succeed(result)
          case None =>
            val next = current.entries.map:
              case Entry.Active(session) if session == expected => Entry.Retired(session)
              case other                                        => other
            write(current, next).flatMap:
              case ConditionalWrite.Applied(_) => ZIO.succeed(TranscriptRetirement.Retired)
              case ConditionalWrite.Conflict if attempts > 1 =>
                ZIO.yieldNow *> ZIO.suspendSucceed(retireConfirmed(expected, attempts - 1))
              case ConditionalWrite.Conflict =>
                ZIO.fail(invalid("Transcript retirement conflict retry limit exceeded"))

  private[transcript] def conflicts(first: TranscriptSession, second: TranscriptSession): Boolean =
    first.manifest == second.manifest ||
      first.reservedNamespace.exists(second.manifest.namespace.startsWith(_)) ||
      second.reservedNamespace.exists(first.manifest.namespace.startsWith(_)) ||
      first.reservedNamespace.exists(left =>
        second.reservedNamespace.exists(right => left.startsWith(right) || right.startsWith(left))
      )

  private def invalid(message: String): AgentError.MemoryStorageError =
    AgentError.MemoryStorageError(message)
