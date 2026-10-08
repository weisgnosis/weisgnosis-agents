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

import com.weisgnosis.agents.core.agent.AgentSnapshot
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.json.JsonBoundary
import com.weisgnosis.agents.harness.storage.*
import java.nio.charset.StandardCharsets
import zio.*
import zio.json.*

/** Checked stable registration identity, independent of execution and directory IDs. */
opaque type SubagentNamedSessionKey = (String, String, String)

object SubagentNamedSessionKey:
  final private case class Wire(parentSessionId: String, agentId: String, label: String)
      derives JsonCodec

  def from(
      parentSessionId: String,
      agentId: String,
      label: String
  ): Either[AgentError.InvalidConfiguration, SubagentNamedSessionKey] =
    StorageAddress
      .from(Chunk(parentSessionId, agentId), label)
      .map(_ => (parentSessionId, agentId, label))

  given JsonCodec[SubagentNamedSessionKey] = JsonCodec[Wire].transformOrFail(
    value => from(value.parentSessionId, value.agentId, value.label).left.map(_.message),
    value => Wire(value._1, value._2, value._3)
  )

  extension (key: SubagentNamedSessionKey)
    def parentSessionId: String = key._1
    def agentId: String         = key._2
    def label: String           = key._3

/** Borrowed versioned backend; snapshots are data and CAS is not an execution fence. */
final class SubagentNamedSessionStore private (
    backend: VersionedJsonStore,
    namespace: Chunk[String],
    limits: SubagentNamedSessionStore.Limits
):
  import SubagentNamedSessionStore.*

  def address(
      key: SubagentNamedSessionKey
  ): Either[AgentError.InvalidConfiguration, StorageAddress] =
    StorageAddress.from(
      namespace ++ Chunk("named-sessions", key.parentSessionId, key.agentId),
      key.label
    )

  private[subagent] def load(key: SubagentNamedSessionKey): IO[AgentError, Loaded] =
    for
      target <- ZIO.fromEither(address(key))
      stored <- backend.read(target)
      loaded <- stored match
        case Some(record) => ZIO.fromEither(decode(key, record))
        case None =>
          val snapshot: AgentSnapshot = AgentSnapshot.Idle(Chunk.empty)
          for
            value   <- ZIO.fromEither(encode(key, snapshot))
            written <- backend.compareAndSet(target, ExpectedRevision.Absent, value)
            result <- written match
              case ConditionalWrite.Applied(revision) => ZIO.succeed(Loaded(snapshot, revision))
              case ConditionalWrite.Conflict =>
                ZIO.fail(
                  AgentError.StorageError(
                    "Named session registration conflicted; explicit reopen is required"
                  )
                )
          yield result
    yield loaded

  private[subagent] def save(
      key: SubagentNamedSessionKey,
      expected: StorageRevision,
      snapshot: AgentSnapshot
  ): IO[AgentError, StorageRevision] =
    for
      target  <- ZIO.fromEither(address(key))
      value   <- ZIO.fromEither(encode(key, snapshot))
      written <- backend.compareAndSet(target, ExpectedRevision.AtRevision(expected), value)
      revision <- written match
        case ConditionalWrite.Applied(revision) => ZIO.succeed(revision)
        case ConditionalWrite.Conflict =>
          ZIO.fail(
            AgentError.StorageError(
              "Named session snapshot conflicted; explicit reopen is required"
            )
          )
    yield revision

  private def encode(
      key: SubagentNamedSessionKey,
      snapshot: AgentSnapshot
  ): Either[AgentError.StorageError, zio.json.ast.Json] =
    for
      value     <- Envelope(1, key, snapshot).toJsonAST.left.map(AgentError.StorageError(_))
      validated <- JsonBoundary.validate(value).left.map(AgentError.StorageError(_))
      _         <- bounded(validated, snapshot)
    yield validated

  private def decode(
      key: SubagentNamedSessionKey,
      stored: VersionedJson
  ): Either[AgentError.StorageError, Loaded] =
    for
      validated <- JsonBoundary.validate(stored.value).left.map(AgentError.StorageError(_))
      envelope <- JsonBoundary
        .render(validated)
        .fromJson[Envelope]
        .left
        .map(AgentError.StorageError(_))
      canonical <- envelope.toJsonAST.left.map(AgentError.StorageError(_))
      _         <- bounded(validated, envelope.snapshot)
      _ <- Either.cond(
        envelope.schemaVersion == 1 && envelope.registration == key && JsonBoundary
          .sameRepresentation(validated, canonical),
        (),
        AgentError.StorageError(
          "Invalid named session registration or noncanonical snapshot envelope"
        )
      )
    yield Loaded(envelope.snapshot, stored.revision)

  private def bounded(
      value: zio.json.ast.Json,
      snapshot: AgentSnapshot
  ): Either[AgentError.StorageError, Unit] =
    val messages = snapshot match
      case AgentSnapshot.Idle(history)     => history.size
      case AgentSnapshot.Suspended(result) => result.history.size
    Either.cond(
      messages <= limits.maxMessages && JsonBoundary.validUnicode(value) && JsonBoundary
        .render(value)
        .getBytes(StandardCharsets.UTF_8)
        .length <= limits.maxBytes,
      (),
      AgentError.StorageError(
        "Named session snapshot exceeds its bounds or contains malformed Unicode"
      )
    )

object SubagentNamedSessionStore:
  final private case class Envelope(
      schemaVersion: Int,
      registration: SubagentNamedSessionKey,
      snapshot: AgentSnapshot
  ) derives JsonCodec
  final private[subagent] case class Loaded(snapshot: AgentSnapshot, revision: StorageRevision)

  final class Limits private (val maxBytes: Int, val maxMessages: Int)
  object Limits:
    val default: Limits = new Limits(1024 * 1024, 512)
    def make(maxBytes: Int, maxMessages: Int): Either[AgentError.InvalidConfiguration, Limits] =
      Either.cond(
        maxBytes > 0 && maxMessages > 0,
        new Limits(maxBytes, maxMessages),
        AgentError.InvalidConfiguration("Named session snapshot bounds must be positive")
      )

  def make(
      backend: VersionedJsonStore,
      namespace: Chunk[String],
      limits: Limits = Limits.default
  ): Either[AgentError.InvalidConfiguration, SubagentNamedSessionStore] =
    StorageAddress
      .from(namespace, "named-sessions")
      .map(_ => new SubagentNamedSessionStore(backend, namespace, limits))
