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

/** Count bounds apply to the complete visible history, not individual backend payload bytes. */
final class TranscriptSegmentLimits private (
    val maxSegments: Int,
    val maxEntries: Int,
    val maxAttempts: Int
)

object TranscriptSegmentLimits:
  val default: TranscriptSegmentLimits = new TranscriptSegmentLimits(1024, 100000, 8)

  def make(
      maxSegments: Int,
      maxEntries: Int,
      maxAttempts: Int = 8
  ): Either[AgentError.InvalidConfiguration, TranscriptSegmentLimits] =
    Either.cond(
      maxSegments > 0 && maxEntries > 0 && maxAttempts > 0,
      new TranscriptSegmentLimits(maxSegments, maxEntries, maxAttempts),
      AgentError.InvalidConfiguration(
        "Transcript segment, entry and publication limits must be positive"
      )
    )

/** Prepared segments become visible only through the host-owned manifest's successful CAS. */
object SegmentedTranscriptStore:
  def make(
      store: VersionedJsonStore,
      manifest: StorageAddress,
      limits: TranscriptSegmentLimits = TranscriptSegmentLimits.default
  ): Either[AgentError.InvalidConfiguration, TranscriptStore] =
    val namespace = manifest.namespace ++ Chunk(manifest.key, "segments")
    StorageAddress
      .from(namespace, "00000000-0000-0000-0000-000000000000")
      .map(_ => new Backend(store, manifest, namespace, limits))

  final private case class Manifest(schemaVersion: Int, head: UUID) derives JsonCodec
  final private case class Segment(
      schemaVersion: Int,
      id: UUID,
      @jsonExplicitNull previous: Option[UUID],
      entries: Chunk[TranscriptEntry]
  ) derives JsonCodec
  final private case class Loaded(
      revision: ExpectedRevision,
      head: Option[UUID],
      segments: Int,
      state: TranscriptState
  )

  final private class Backend(
      store: VersionedJsonStore,
      manifest: StorageAddress,
      namespace: Chunk[String],
      limits: TranscriptSegmentLimits
  ) extends TranscriptStore:
    def entries: IO[AgentError, Chunk[TranscriptEntry]] = read.map(_.state.entries)

    def append(drafts: Chunk[TranscriptDraft]): IO[AgentError, Chunk[TranscriptEntry]] =
      if drafts.isEmpty then ZIO.succeed(Chunk.empty)
      else
        for
          instant    <- Clock.instant
          candidates <- ZIO.foreach(drafts)(draft => Random.nextUUID.map(id => draft -> id))
          result     <- publish(candidates, instant, limits.maxAttempts)
        yield result

    private def read: IO[AgentError, Loaded] =
      store
        .read(manifest)
        .flatMap:
          case None => ZIO.succeed(Loaded(ExpectedRevision.Absent, None, 0, TranscriptState.empty))
          case Some(record) =>
            for
              pointer <- ZIO.fromEither(decode[Manifest](record.value))
              _ <- ZIO
                .fail(invalid("Unsupported transcript manifest version"))
                .when(pointer.schemaVersion != 1)
              restored <- chain(pointer.head, Set.empty, List.empty, 0)
            yield Loaded(
              ExpectedRevision.AtRevision(record.revision),
              Some(pointer.head),
              restored._1,
              restored._2
            )

    private def chain(
        id: UUID,
        visited: Set[UUID],
        blocks: List[Chunk[TranscriptEntry]],
        entryCount: Int
    ): IO[AgentError, (Int, TranscriptState)] =
      if visited.contains(id) || visited.size >= limits.maxSegments then
        ZIO.fail(invalid("Cyclic or oversized transcript segment chain"))
      else
        for
          address <- ZIO.fromEither(StorageAddress.from(namespace, id.toString))
          record <- store.read(address).someOrFail(invalid("Missing referenced transcript segment"))
          segment <- ZIO.fromEither(decode[Segment](record.value))
          _ <- ZIO
            .fail(invalid("Invalid or oversized transcript segment"))
            .when(
              record.revision.value != 1L || segment.schemaVersion != 1 || segment.id != id ||
                segment.entries.isEmpty || segment.entries.size > limits.maxEntries - entryCount
            )
          nextBlocks  = segment.entries :: blocks
          nextVisited = visited + id
          result <- segment.previous match
            case Some(previous) =>
              ZIO.suspendSucceed(
                chain(previous, nextVisited, nextBlocks, entryCount + segment.entries.size)
              )
            case None =>
              ZIO
                .fromEither(TranscriptState.restore(Chunk.fromIterable(nextBlocks).flatten))
                .map(nextVisited.size -> _)
        yield result

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
          else if current.segments >= limits.maxSegments || next.entries.size > limits.maxEntries
          then ZIO.fail(invalid("Transcript segment or entry capacity exceeded"))
          else
            for
              id       <- Random.nextUUID
              address  <- ZIO.fromEither(StorageAddress.from(namespace, id.toString))
              segment  <- ZIO.fromEither(encode(Segment(1, id, current.head, added)))
              pointer  <- ZIO.fromEither(encode(Manifest(1, id)))
              prepared <- store.compareAndSet(address, ExpectedRevision.Absent, segment)
              result <- prepared match
                case ConditionalWrite.Conflict =>
                  ZIO.fail(invalid("Transcript segment identity collision"))
                case ConditionalWrite.Applied(revision) if revision.value != 1L =>
                  ZIO.fail(invalid("Invalid transcript segment creation revision"))
                case ConditionalWrite.Applied(_) =>
                  store
                    .compareAndSet(manifest, current.revision, pointer)
                    .flatMap:
                      case ConditionalWrite.Applied(_) => ZIO.succeed(added)
                      case ConditionalWrite.Conflict if attempts > 1 =>
                        ZIO.yieldNow *> ZIO.suspendSucceed(
                          publish(candidates, instant, attempts - 1)
                        )
                      case ConditionalWrite.Conflict =>
                        ZIO.fail(invalid("Transcript manifest conflict retry limit exceeded"))
            yield result
      yield committed

  private def invalid(message: String): AgentError.MemoryStorageError =
    AgentError.MemoryStorageError(message)

  private def encode[A: JsonCodec](value: A): Either[AgentError, Json] =
    for
      ast <- value.toJsonAST.left.map(invalid)
      _   <- decode[A](ast)
    yield ast

  private def decode[A: JsonCodec](value: Json): Either[AgentError, A] =
    for
      validated <- JsonBoundary.validate(value).left.map(invalid)
      _ <- Either.cond(
        JsonBoundary.validUnicode(validated),
        (),
        invalid("Malformed transcript Unicode")
      )
      decoded <- JsonBoundary
        .render(validated)
        .fromJson[A]
        .left
        .map(_ => invalid("Invalid transcript segment schema"))
      canonical <- decoded.toJsonAST.left.map(invalid)
      _ <- Either.cond(
        JsonBoundary.sameRepresentation(validated, canonical),
        (),
        invalid("Noncanonical transcript segment schema")
      )
    yield decoded
