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

/** One explicit host catalog; membership grants no execution, inactivity or retention authority. */
trait TranscriptSessionCatalog:
  def sessions: IO[AgentError, Chunk[TranscriptSession]]
  def register(session: TranscriptSession): IO[AgentError, TranscriptRegistration]

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

  final private case class Payload(schemaVersion: Int, sessions: Chunk[TranscriptSession])
      derives JsonCodec
  final private case class Loaded(revision: ExpectedRevision, sessions: Chunk[TranscriptSession])

  final private class Backend(
      store: VersionedJsonStore,
      address: StorageAddress,
      maxSessions: Int,
      maxAttempts: Int
  ) extends TranscriptSessionCatalog:
    def sessions: IO[AgentError, Chunk[TranscriptSession]] = read.map(_.sessions)

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
              payload <- JsonBoundary
                .render(value)
                .fromJson[Payload]
                .left
                .map(_ => invalid("Invalid transcript catalog schema"))
              canonical <- payload.toJsonAST.left.map(invalid)
              _ <- Either.cond(
                payload.schemaVersion == 1 && JsonBoundary.sameRepresentation(value, canonical) &&
                  payload.sessions.size <= maxSessions && payload.sessions == payload.sessions
                    .sortBy(_.id.value) &&
                  payload.sessions.map(_.id).distinct.size == payload.sessions.size &&
                  payload.sessions.forall(session => !collidesWithCatalog(session)) &&
                  payload.sessions.zipWithIndex.forall((session, index) =>
                    !payload.sessions.take(index).exists(conflicts(session, _))
                  ),
                (),
                invalid("Invalid transcript catalog identities, ordering or capacity")
              )
            yield Loaded(ExpectedRevision.AtRevision(record.revision), payload.sessions)
            ZIO.fromEither(decoded)

    private def publish(
        session: TranscriptSession,
        attempts: Int
    ): IO[AgentError, TranscriptRegistration] =
      read.flatMap: current =>
        if current.sessions.contains(session) then ZIO.succeed(TranscriptRegistration.Existing)
        else if current.sessions.exists(other =>
            other.id == session.id || conflicts(session, other)
          )
        then ZIO.fail(invalid("Conflicting transcript session binding"))
        else if current.sessions.size >= maxSessions then
          ZIO.fail(invalid("Transcript catalog capacity exceeded"))
        else
          val next = Payload(1, (current.sessions :+ session).sortBy(_.id.value))
          ZIO
            .fromEither(next.toJsonAST.left.map(invalid))
            .flatMap: value =>
              store
                .compareAndSet(address, current.revision, value)
                .flatMap:
                  case ConditionalWrite.Applied(_) => ZIO.succeed(TranscriptRegistration.Added)
                  case ConditionalWrite.Conflict if attempts > 1 =>
                    ZIO.yieldNow *> ZIO.suspendSucceed(publish(session, attempts - 1))
                  case ConditionalWrite.Conflict =>
                    ZIO.fail(invalid("Transcript catalog conflict retry limit exceeded"))

  private def conflicts(first: TranscriptSession, second: TranscriptSession): Boolean =
    first.manifest == second.manifest ||
      first.reservedNamespace.exists(second.manifest.namespace.startsWith(_)) ||
      second.reservedNamespace.exists(first.manifest.namespace.startsWith(_)) ||
      first.reservedNamespace.exists(left =>
        second.reservedNamespace.exists(right => left.startsWith(right) || right.startsWith(left))
      )

  private def invalid(message: String): AgentError.MemoryStorageError =
    AgentError.MemoryStorageError(message)
