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
import java.time.Instant
import java.util.UUID
import zio.*
import zio.json.*
import zio.json.ast.Json

/** One host-sized persistent transcript; only definite publication conflicts authorize retries. */
object VersionedTranscriptStore:
  def make(
      store: VersionedJsonStore,
      address: StorageAddress,
      maxAttempts: Int = 8
  ): Either[AgentError.InvalidConfiguration, TranscriptStore] =
    Either.cond(
      maxAttempts > 0,
      new Backend(store, address, maxAttempts),
      AgentError.InvalidConfiguration("Transcript publication attempts must be positive")
    )

  final private case class Payload(schemaVersion: Int, entries: Chunk[TranscriptEntry])
      derives JsonCodec
  final private case class Loaded(revision: ExpectedRevision, state: TranscriptState)

  final private class Backend(store: VersionedJsonStore, address: StorageAddress, maxAttempts: Int)
      extends TranscriptStore:
    def entries: IO[AgentError, Chunk[TranscriptEntry]] = read.map(_.state.entries)

    def append(drafts: Chunk[TranscriptDraft]): IO[AgentError, Chunk[TranscriptEntry]] =
      if drafts.isEmpty then ZIO.succeed(Chunk.empty)
      else
        for
          instant    <- Clock.instant
          candidates <- ZIO.foreach(drafts)(draft => Random.nextUUID.map(id => draft -> id))
          committed  <- publish(candidates, instant, maxAttempts)
        yield committed

    private def read: IO[AgentError, Loaded] =
      store
        .read(address)
        .flatMap:
          case None => ZIO.succeed(Loaded(ExpectedRevision.Absent, TranscriptState.empty))
          case Some(record) =>
            ZIO
              .fromEither(decode(record.value))
              .map(Loaded(ExpectedRevision.AtRevision(record.revision), _))

    private def publish(
        candidates: Chunk[(TranscriptDraft, UUID)],
        instant: Instant,
        attempts: Int
    ): IO[AgentError, Chunk[TranscriptEntry]] =
      for
        current <- read
        next    <- ZIO.fromEither(current.state.append(candidates, instant))
        added = next.entries.drop(current.state.entries.size)
        committed <-
          if added.isEmpty then ZIO.succeed(Chunk.empty)
          else
            ZIO
              .fromEither(encode(next))
              .flatMap: value =>
                store
                  .compareAndSet(address, current.revision, value)
                  .flatMap:
                    case ConditionalWrite.Applied(_) => ZIO.succeed(added)
                    case ConditionalWrite.Conflict if attempts > 1 =>
                      ZIO.yieldNow *> ZIO.suspendSucceed(publish(candidates, instant, attempts - 1))
                    case ConditionalWrite.Conflict =>
                      ZIO.fail(
                        AgentError.MemoryStorageError(
                          "Transcript publication conflict retry limit exceeded"
                        )
                      )
      yield committed

  private def encode(state: TranscriptState): Either[AgentError, Json] =
    for
      value <- Payload(1, state.entries).toJsonAST.left.map(AgentError.MemoryStorageError.apply)
      _     <- decode(value)
    yield value

  private def decode(value: Json): Either[AgentError, TranscriptState] =
    for
      validated <- JsonBoundary.validate(value).left.map(AgentError.MemoryStorageError.apply)
      _ <- Either.cond(
        validUnicode(validated),
        (),
        AgentError.MemoryStorageError("Transcript contains malformed Unicode")
      )
      payload <- JsonBoundary
        .render(validated)
        .fromJson[Payload]
        .left
        .map(AgentError.MemoryStorageError.apply)
      canonical <- payload.toJsonAST.left.map(AgentError.MemoryStorageError.apply)
      _ <- Either.cond(
        payload.schemaVersion == 1 && TranscriptLog.sameRepresentation(validated, canonical),
        (),
        AgentError.MemoryStorageError("Unsupported or noncanonical transcript aggregate")
      )
      state <- TranscriptState.restore(payload.entries)
    yield state

  private def validUnicode(value: Json): Boolean =
    def validText(text: String): Boolean =
      text.indices.forall: index =>
        val character = text.charAt(index)
        if Character.isHighSurrogate(character) then
          text.lift(index + 1).exists(Character.isLowSurrogate)
        else if Character.isLowSurrogate(character) then
          text.lift(index - 1).exists(Character.isHighSurrogate)
        else true
    @annotation.tailrec
    def loop(pending: List[Json]): Boolean = pending match
      case Nil                    => true
      case Json.Str(text) :: rest => validText(text) && loop(rest)
      case Json.Obj(fields) :: rest =>
        fields.forall((key, _) => validText(key)) && loop(fields.map(_._2).toList ::: rest)
      case Json.Arr(values) :: rest => loop(values.toList ::: rest)
      case _ :: rest                => loop(rest)
    loop(List(value))
