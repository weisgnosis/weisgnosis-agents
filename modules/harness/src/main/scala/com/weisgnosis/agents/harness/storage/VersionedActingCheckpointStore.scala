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
package com.weisgnosis.agents.harness.storage

import com.weisgnosis.agents.core.agent.{AgentResult, PendingActing}
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.json.JsonBoundary
import zio.*
import zio.json.*
import zio.json.ast.Json

/** Strict suspended checkpoint publication; retries and exclusive execution remain host concerns. */
object VersionedActingCheckpointStore:
  def make(store: VersionedJsonStore, address: StorageAddress): ActingCheckpointStore =
    new Backend(store, address)

  final private case class Payload(schemaVersion: Int, checkpoint: AgentResult) derives JsonCodec

  final private class Backend(store: VersionedJsonStore, address: StorageAddress)
      extends ActingCheckpointStore:
    def read: IO[AgentError, Option[VersionedActingCheckpoint]] =
      store
        .read(address)
        .flatMap:
          case None => ZIO.none
          case Some(record) =>
            ZIO
              .fromEither(decode(record.value))
              .map(checkpoint => Some(VersionedActingCheckpoint(checkpoint, record.revision)))

    def save(
        expected: ExpectedRevision,
        checkpoint: AgentResult
    ): IO[AgentError, ConditionalWrite] =
      for
        _      <- ZIO.fromEither(PendingActing.validate(checkpoint))
        value  <- ZIO.fromEither(encode(checkpoint))
        result <- store.compareAndSet(address, expected, value)
      yield result

  private def encode(checkpoint: AgentResult): Either[AgentError, Json] =
    for
      value <- Payload(1, checkpoint).toJsonAST.left.map(AgentError.StorageError.apply)
      _     <- decode(value)
    yield value

  private def decode(value: Json): Either[AgentError.StorageError, AgentResult] =
    for
      validated <- JsonBoundary.validate(value).left.map(AgentError.StorageError.apply)
      _ <- Either.cond(
        JsonBoundary.validUnicode(validated),
        (),
        AgentError.StorageError("Acting checkpoint contains malformed Unicode")
      )
      payload <- JsonBoundary
        .render(validated)
        .fromJson[Payload]
        .left
        .map(AgentError.StorageError.apply)
      canonical <- payload.toJsonAST.left.map(AgentError.StorageError.apply)
      _ <- Either.cond(
        payload.schemaVersion == 1 && JsonBoundary.sameRepresentation(validated, canonical),
        (),
        AgentError.StorageError("Unsupported or noncanonical acting checkpoint aggregate")
      )
      _ <- PendingActing
        .validate(payload.checkpoint)
        .left
        .map(_ => AgentError.StorageError("Invalid persisted acting checkpoint"))
    yield payload.checkpoint
