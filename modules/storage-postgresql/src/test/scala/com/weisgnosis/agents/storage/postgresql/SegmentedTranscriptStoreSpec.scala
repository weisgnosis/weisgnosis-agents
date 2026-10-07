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
package com.weisgnosis.agents.storage.postgresql

import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.model.Role
import com.weisgnosis.agents.harness.storage.*
import com.weisgnosis.agents.harness.transcript.*
import java.util.UUID
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

object SegmentedTranscriptStoreSpec extends ZIOSpecDefault:
  import PostgresFixture.*

  private enum PublicationStage:
    case Segment, Manifest

  private enum SegmentPublication:
    case Created, Rewritten, Missing

  private val preciseTool: TranscriptDraft = TranscriptDraft(
    None,
    TranscriptPayload.ToolUse(
      "call",
      "lookup",
      TranscriptPreview.Complete(
        Json.Obj(
          "integer" -> Json.Num(Long.MinValue),
          "decimal" -> Json.Num(BigDecimal("12345678901234567890.123456789"))
        )
      )
    )
  )

  private def draft(text: String, key: Option[String] = None): TranscriptDraft =
    TranscriptDraft(
      key.map(TranscriptKey.Message.apply),
      TranscriptPayload.Message(Role.User, text, Chunk(TranscriptBlockType.Text))
    )

  private def transcript(
      backend: VersionedJsonStore,
      target: StorageAddress,
      limits: TranscriptSegmentLimits = TranscriptSegmentLimits.default
  ): IO[AgentError, TranscriptStore] =
    ZIO.fromEither(SegmentedTranscriptStore.make(backend, target, limits))

  private def linked(entries: Chunk[TranscriptEntry]): Boolean =
    entries.zipWithIndex.forall((entry, index) =>
      entry.parentId == entries.lift(index - 1).map(_.id)
    )

  private def segmentAddress(target: StorageAddress, id: UUID): IO[AgentError, StorageAddress] =
    ZIO.fromEither(
      StorageAddress.from(target.namespace ++ Chunk(target.key, "segments"), id.toString)
    )

  private def pointer(id: UUID): Json.Obj =
    Json.Obj("schemaVersion" -> Json.Num(1), "head" -> Json.Str(id.toString))

  private def changed(value: Json.Obj, name: String, replacement: Json): Json.Obj =
    Json.Obj(value.fields.filterNot(_._1 == name) :+ (name -> replacement))

  private def proposed(value: Json): IO[AgentError, Chunk[TranscriptEntry]] =
    ZIO.fromEither((value match
      case obj: Json.Obj =>
        obj.get("entries").toRight("Missing entries").flatMap(_.as[Chunk[TranscriptEntry]])
      case _ => Left("Expected segment")
    ).left.map(AgentError.MemoryStorageError.apply))

  def spec: Spec[TestEnvironment, Any] = suite("SegmentedTranscriptStoreSpec")(
    test("independent writers publish immutable segments and restore one exact deduplicated chain"):
      fixture: (source, backend, name) =>
        for
          target   <- address(name, "segments")
          limits   <- ZIO.fromEither(TranscriptSegmentLimits.make(32, 64, 32))
          observed <- Ref.make(Chunk.empty[(StorageAddress, Json)])
          recording = new VersionedJsonStore:
            def read(address: StorageAddress): IO[AgentError, Option[VersionedJson]] =
              backend.read(address)
            def compareAndSet(address: StorageAddress, expected: ExpectedRevision, value: Json)
                : IO[AgentError, ConditionalWrite] =
              backend
                .compareAndSet(address, expected, value)
                .tap:
                  case ConditionalWrite.Applied(_) if address != target =>
                    observed.update(_ :+ (address -> value))
                  case _ => ZIO.unit
          first         <- transcript(recording, target, limits)
          initial       <- first.entries
          empty         <- first.append(Chunk.empty)
          absent        <- backend.read(target)
          seed          <- first.append(Chunk(draft("first", Some("shared"))))
          seedSegments  <- observed.get
          secondSource  <- dataSource(name + "-second")
          secondBackend <- store(secondSource)
          second        <- transcript(secondBackend, target, limits)
          results <- ZIO.foreachPar(Chunk.fromIterable(1 to 6))(index =>
            (if index % 2 == 0 then first else second).append(
              Chunk(draft("ignored", Some("shared")), draft(s"entry-$index", Some(s"key-$index")))
            )
          )
          anonymous       <- first.append(Chunk(draft("anonymous"), preciseTool))
          before          <- backend.read(target)
          duplicates      <- first.append(Chunk(draft("ignored", Some("shared"))))
          after           <- backend.read(target)
          reopenedBackend <- store(source)
          reopened        <- transcript(reopenedBackend, target, limits)
          recovered       <- reopened.entries
          immutable <- ZIO.foreach(seedSegments)((address, value) =>
            backend
              .read(address)
              .map(_.exists(record => record.revision.value == 1L && record.value == value))
          )
        yield assertTrue(
          initial.isEmpty,
          empty.isEmpty,
          absent.isEmpty,
          seed.size == 1,
          results.forall(_.size == 1),
          anonymous.map(_.payload) == Chunk(draft("anonymous").payload, preciseTool.payload),
          recovered.size == 9,
          recovered.map(_.id).distinct.size == 9,
          linked(recovered),
          recovered.filter(_.key.contains(TranscriptKey.Message("shared"))) == seed,
          results.zipWithIndex.forall((entries, index) =>
            entries.map(_.payload) == Chunk(draft(s"entry-${index + 1}").payload)
          ),
          recovered
            .map(_.payload)
            .toSet == (results.flatten ++ seed ++ anonymous).map(_.payload).toSet,
          duplicates.isEmpty,
          before == after,
          immutable.nonEmpty,
          immutable.forall(identity)
        )
    ,
    test(
      "manifest conflicts reparent stable candidates while orphan preparations remain invisible"
    ):
      ZIO
        .foreach(Chunk(1, 8)): budget =>
          fixture: (_, backend, name) =>
            for
              target   <- address(name, "conflict")
              other    <- transcript(backend, target)
              prepared <- Ref.make(Chunk.empty[Json])
              attempts <- Ref.make(0)
              interposed = new VersionedJsonStore:
                def read(address: StorageAddress): IO[AgentError, Option[VersionedJson]] =
                  backend.read(address)
                def compareAndSet(address: StorageAddress, expected: ExpectedRevision, value: Json)
                    : IO[AgentError, ConditionalWrite] =
                  if address == target then
                    attempts
                      .getAndUpdate(_ + 1)
                      .flatMap(previous =>
                        ZIO.when(previous == 0)(
                          other.append(Chunk(draft("competitor", Some("other"))))
                        ) *>
                          backend.compareAndSet(address, expected, value)
                      )
                  else
                    backend
                      .compareAndSet(address, expected, value)
                      .tap:
                        case ConditionalWrite.Applied(_) => prepared.update(_ :+ value)
                        case _                           => ZIO.unit
              limits       <- ZIO.fromEither(TranscriptSegmentLimits.make(32, 64, budget))
              instance     <- transcript(interposed, target, limits)
              added        <- instance.append(Chunk(draft("candidate", Some("candidate")))).either
              preparations <- prepared.get
              proposedEntries <- ZIO.foreach(preparations)(proposed)
              manifestCalls   <- attempts.get
              visible         <- instance.entries
              orphanId <- ZIO.fromEither(
                preparations.headOption
                  .toRight(AgentError.StorageError("missing prepare"))
                  .flatMap(value =>
                    value.asObject
                      .flatMap(_.get("id"))
                      .flatMap(_.asString)
                      .toRight(AgentError.StorageError("missing id"))
                  )
              )
              orphan <- ZIO
                .attempt(UUID.fromString(orphanId))
                .mapError(_ => AgentError.StorageError("invalid fixture UUID"))
              orphanAddress   <- segmentAddress(target, orphan)
              persistedOrphan <- backend.read(orphanAddress)
              finalManifest   <- backend.read(target)
            yield assertTrue(
              preparations.size == (if budget == 1 then 1 else 2),
              manifestCalls == (if budget == 1 then 1 else 2),
              proposedEntries.map(_.map(_.id)).distinct.size == 1,
              proposedEntries.map(_.map(_.recordedAt)).distinct.size == 1,
              proposedEntries.map(_.map(_.parentId)).distinct.size == (if budget == 1 then 1
                                                                       else 2),
              proposedEntries.forall(_.map(_.payload) == Chunk(draft("candidate").payload)),
              visible.map(
                _.payload
              ) == (if budget == 1 then Chunk(draft("competitor").payload)
                    else Chunk(draft("competitor").payload, draft("candidate").payload)),
              added match
                case Right(entries) => budget != 1 && visible.drop(1) == entries
                case Left(error) => budget == 1 && error.isInstanceOf[AgentError.MemoryStorageError]
              ,
              linked(visible),
              persistedOrphan.exists(record =>
                record.revision.value == 1L && preparations.headOption.contains(record.value)
              ),
              !finalManifest.exists(_.value == pointer(orphan))
            )
        .map(_.foldLeft(assertTrue(true))(_ && _))
    ,
    test("corrupt chains, capacity and uncertain publication never expose a partial transcript"):
      fixture: (_, backend, name) =>
        for
          capacities <- ZIO.foreach(Chunk((1, 2), (2, 1))): (maxSegments, maxEntries) =>
            for
              target <- address(name, s"capacity-$maxSegments-$maxEntries")
              limits <- ZIO.fromEither(TranscriptSegmentLimits.make(maxSegments, maxEntries))
              writes <- Ref.make(0)
              counted = new VersionedJsonStore:
                def read(address: StorageAddress): IO[AgentError, Option[VersionedJson]] =
                  backend.read(address)
                def compareAndSet(address: StorageAddress, expected: ExpectedRevision, value: Json)
                    : IO[AgentError, ConditionalWrite] =
                  writes.update(_ + 1) *> backend.compareAndSet(address, expected, value)
              bounded      <- transcript(counted, target, limits)
              _            <- bounded.append(Chunk(draft("seed", Some("seed"))))
              before       <- backend.read(target)
              duplicate    <- bounded.append(Chunk(draft("ignored", Some("seed"))))
              exceeded     <- bounded.append(Chunk(draft("new"))).either
              after        <- backend.read(target)
              calls        <- writes.get
              larger       <- transcript(backend, target)
              _            <- larger.append(Chunk(draft("second")))
              complete     <- larger.entries
              rejectedRead <- bounded.entries.either
            yield duplicate.isEmpty && exceeded.left.exists(
              _.isInstanceOf[AgentError.MemoryStorageError]
            ) &&
              before == after && calls == 2 && complete.size == 2 && rejectedRead.left.exists(
                _.isInstanceOf[AgentError.MemoryStorageError]
              )
          seedTarget <- address(name, "seed")
          seedStore  <- transcript(backend, seedTarget)
          seed       <- seedStore.append(Chunk(draft("seed", Some("seed"))))
          entry <- ZIO.fromEither(seed.headOption.toRight(AgentError.StorageError("missing seed")))
          encoded <- ZIO.fromEither(Chunk(entry).toJsonAST.left.map(AgentError.StorageError.apply))
          id      <- Random.nextUUID
          previousId    <- Random.nextUUID
          missingId     <- Random.nextUUID
          secondEntryId <- Random.nextUUID
          wrongParent <- ZIO.fromEither(
            Chunk(entry.copy(id = secondEntryId, key = None, parentId = None)).toJsonAST.left
              .map(AgentError.StorageError.apply)
          )
          base = Json.Obj(
            "schemaVersion" -> Json.Num(1),
            "id"            -> Json.Str(id.toString),
            "previous"      -> Json.Null,
            "entries"       -> encoded
          )
          invalid = Chunk(
            changed(base, "schemaVersion", Json.Num(2)),
            changed(base, "id", Json.Str(entry.id.toString)),
            changed(base, "previous", Json.Str(id.toString)),
            changed(base, "previous", Json.Str(missingId.toString)),
            changed(base, "entries", Json.Arr()),
            changed(
              changed(base, "previous", Json.Str(previousId.toString)),
              "entries",
              wrongParent
            ),
            Json.Obj(base.fields :+ ("extra" -> Json.Bool(true)))
          ).map(_ -> SegmentPublication.Created) ++ Chunk(
            base -> SegmentPublication.Rewritten,
            base -> SegmentPublication.Missing
          )
          rejected <- ZIO.foreach(invalid.zipWithIndex): (fixture, index) =>
            val (value, publication) = fixture
            for
              badTarget       <- address(name, s"bad-$index")
              segment         <- segmentAddress(badTarget, id)
              previousSegment <- segmentAddress(badTarget, previousId)
              _ <- backend.compareAndSet(
                previousSegment,
                ExpectedRevision.Absent,
                changed(base, "id", Json.Str(previousId.toString))
              )
              _ <- ZIO.unless(publication == SegmentPublication.Missing)(
                backend.compareAndSet(segment, ExpectedRevision.Absent, value)
              )
              created <- backend.read(segment)
              _ <- publication match
                case SegmentPublication.Rewritten =>
                  ZIO
                    .fromOption(created)
                    .orElseFail(AgentError.StorageError("missing fixture segment"))
                    .flatMap(record =>
                      backend
                        .compareAndSet(segment, ExpectedRevision.AtRevision(record.revision), value)
                    )
                    .unit
                case _ => ZIO.unit
              segmentBefore <- backend.read(segment)
              published <- backend.compareAndSet(badTarget, ExpectedRevision.Absent, pointer(id))
              manifestBefore <- backend.read(badTarget)
              bad            <- transcript(backend, badTarget)
              result         <- bad.entries.either
              manifestAfter  <- backend.read(badTarget)
              segmentAfter   <- backend.read(segment)
            yield published.isInstanceOf[ConditionalWrite.Applied] && result.left.exists(
              _.isInstanceOf[AgentError.MemoryStorageError]
            ) &&
              manifestBefore == manifestAfter && segmentBefore == segmentAfter && (publication match
                case SegmentPublication.Created   => segmentBefore.exists(_.revision.value == 1L)
                case SegmentPublication.Rewritten => segmentBefore.exists(_.revision.value == 2L)
                case SegmentPublication.Missing   => segmentBefore.isEmpty)
          uncertainties <- ZIO.foreach(Chunk.fromArray(PublicationStage.values)): stage =>
            for
              uncertainTarget <- address(
                name,
                if stage == PublicationStage.Manifest then "lost-manifest" else "lost-segment"
              )
              writes <- Ref.make(0)
              lost = new VersionedJsonStore:
                def read(address: StorageAddress): IO[AgentError, Option[VersionedJson]] =
                  backend.read(address)
                def compareAndSet(address: StorageAddress, expected: ExpectedRevision, value: Json)
                    : IO[AgentError, ConditionalWrite] =
                  writes.update(_ + 1) *> backend
                    .compareAndSet(address, expected, value)
                    .flatMap:
                      case ConditionalWrite.Applied(_)
                          if (address == uncertainTarget) == (stage == PublicationStage.Manifest) =>
                        ZIO.fail(AgentError.StorageError("lost acknowledgement"))
                      case outcome => ZIO.succeed(outcome)
              instance <- transcript(lost, uncertainTarget)
              result   <- instance.append(Chunk(draft("once", Some("once")))).either
              calls    <- writes.get
              reopened <- transcript(backend, uncertainTarget)
              visible  <- reopened.entries
            yield result.left.exists(_.isInstanceOf[AgentError.StorageError]) &&
              calls == (if stage == PublicationStage.Manifest then 2 else 1) &&
              visible.map(_.payload) == (if stage == PublicationStage.Manifest then
                                           Chunk(draft("once").payload)
                                         else Chunk.empty)
          cancellations <- ZIO.foreach(Chunk.fromArray(PublicationStage.values)): stage =>
            for
              cancelTarget <- address(name, s"cancel-$stage")
              entered      <- Promise.make[Nothing, Unit]
              cleaned      <- Ref.make(false)
              writes       <- Ref.make(0)
              cancelled = new VersionedJsonStore:
                def read(address: StorageAddress): IO[AgentError, Option[VersionedJson]] =
                  backend.read(address)
                def compareAndSet(address: StorageAddress, expected: ExpectedRevision, value: Json)
                    : IO[AgentError, ConditionalWrite] =
                  writes.update(_ + 1) *> backend
                    .compareAndSet(address, expected, value)
                    .flatMap:
                      case ConditionalWrite.Applied(_)
                          if (address == cancelTarget) == (stage == PublicationStage.Manifest) =>
                        (entered.succeed(()) *> ZIO.never).ensuring(cleaned.set(true))
                      case outcome => ZIO.succeed(outcome)
              interrupted <- ZIO.scoped:
                for
                  instance <- transcript(cancelled, cancelTarget)
                  fiber    <- instance.append(Chunk(draft("once", Some("once")))).forkScoped
                  _        <- entered.await
                  exit     <- fiber.interrupt
                  closed   <- cleaned.get
                yield exit.isInterrupted && closed
              calls    <- writes.get
              reopened <- transcript(backend, cancelTarget)
              visible  <- reopened.entries
            yield interrupted && calls == (if stage == PublicationStage.Manifest then 2 else 1) &&
              visible.map(_.payload) == (if stage == PublicationStage.Manifest then
                                           Chunk(draft("once").payload)
                                         else Chunk.empty)
          longAddress <- address("x" * 500, "manifest")
        yield assertTrue(
          capacities.forall(identity),
          rejected.forall(identity),
          uncertainties.forall(identity),
          cancellations.forall(identity),
          SegmentedTranscriptStore.make(backend, longAddress).isLeft,
          TranscriptSegmentLimits.make(0, 1).isLeft,
          TranscriptSegmentLimits.make(1, 0).isLeft,
          TranscriptSegmentLimits.make(1, 1, 0).isLeft
        )
  ) @@ TestAspect.sequential @@ TestAspect.withLiveRandom @@ TestAspect.withLiveSystem @@ TestAspect.withLiveClock @@
    TestAspect.timeout(30.seconds) @@ TestAspect.ifEnvSet("WEISGNOSIS_POSTGRES_URL")
