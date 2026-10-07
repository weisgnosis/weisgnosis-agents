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
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

object VersionedTranscriptStoreSpec extends ZIOSpecDefault:
  import PostgresFixture.*

  private def transcript(
      backend: VersionedJsonStore,
      target: StorageAddress,
      maxAttempts: Int = 8
  ): IO[AgentError, TranscriptStore] =
    ZIO.fromEither(VersionedTranscriptStore.make(backend, target, maxAttempts))

  private def draft(text: String, key: Option[String] = None): TranscriptDraft =
    TranscriptDraft(
      key.map(TranscriptKey.Message.apply),
      TranscriptPayload.Message(Role.User, text, Chunk(TranscriptBlockType.Text))
    )

  private def aggregate(entries: Json, version: Int = 1): Json.Obj =
    Json.Obj("schemaVersion" -> Json.Num(version), "entries" -> entries)

  private def encoded(entries: Chunk[TranscriptEntry]): IO[AgentError, Json] =
    ZIO.fromEither(entries.toJsonAST.left.map(AgentError.MemoryStorageError.apply))

  private def proposed(value: Json): IO[AgentError, Chunk[TranscriptEntry]] =
    ZIO.fromEither(
      (value match
        case Json.Obj(fields) =>
          fields.collectFirst { case ("entries", entries) => entries }.toRight("Missing entries")
        case _ => Left("Expected aggregate object")
      )
        .flatMap(_.as[Chunk[TranscriptEntry]])
        .left
        .map(AgentError.MemoryStorageError.apply)
    )

  private def linked(entries: Chunk[TranscriptEntry]): Boolean =
    entries.zipWithIndex.forall: (entry, index) =>
      entry.parentId == entries.lift(index - 1).map(_.id)

  def spec: Spec[TestEnvironment, Any] = suite("VersionedTranscriptStoreSpec")(
    test("independent appenders preserve first observations, anonymous entries and parent chains"):
      fixture: (source, backend, name) =>
        for
          target        <- address(name, "transcript")
          other         <- address(name + "-isolated", "transcript")
          first         <- transcript(backend, target, 32)
          secondSource  <- dataSource(name + "-second")
          secondBackend <- store(secondSource)
          second        <- transcript(secondBackend, target, 32)
          initial       <- first.entries
          empty         <- first.append(Chunk.empty)
          absent        <- backend.read(target)
          seed          <- first.append(Chunk(draft("first", Some("shared"))))
          results <- ZIO.foreachPar(Chunk.fromIterable(1 to 8)): index =>
            val selected = if index % 2 == 0 then first else second
            selected.append(
              Chunk(draft("ignored", Some("shared")), draft(s"entry-$index", Some(s"key-$index")))
            )
          anonymous       <- first.append(Chunk(draft("anonymous"), draft("anonymous")))
          before          <- backend.read(target)
          repeated        <- second.append(Chunk(draft("ignored again", Some("shared"))))
          after           <- backend.read(target)
          reopenedBackend <- store(source)
          reopened        <- transcript(reopenedBackend, target)
          recovered       <- reopened.entries
          isolated        <- transcript(secondBackend, other)
          untouched       <- isolated.entries
        yield assertTrue(
          initial.isEmpty,
          empty.isEmpty,
          absent.isEmpty,
          seed.size == 1,
          results.forall(_.size == 1),
          anonymous.size == 2,
          recovered.size == 11,
          recovered
            .filter(_.key.exists {
              case TranscriptKey.Message(value) => value.startsWith("key-")
              case _                            => false
            })
            .map(_.payload)
            .toSet == (1 to 8)
            .map(index => draft(s"entry-$index", Some(s"key-$index")).payload)
            .toSet,
          results.zipWithIndex.forall((entries, index) =>
            entries.map(_.payload) == Chunk(draft(s"entry-${index + 1}").payload)
          ),
          anonymous.map(_.payload) == Chunk.fill(2)(draft("anonymous").payload),
          recovered.map(_.id).distinct.size == 11,
          recovered.filter(_.key.contains(TranscriptKey.Message("shared"))) == seed,
          anonymous.forall(_.key.isEmpty),
          linked(recovered),
          repeated.isEmpty,
          after == before,
          untouched.isEmpty
        )
    ,
    test("definite conflicts rebase parent links with fixed identities and bounded attempts"):
      fixture: (_, backend, name) =>
        ZIO
          .foreach(Chunk(1, 8)): budget =>
            for
              target     <- address(name, s"retry-$budget")
              competitor <- transcript(backend, target)
              writes     <- Ref.make(Chunk.empty[Json])
              wrapped = new VersionedJsonStore:
                def read(location: StorageAddress): IO[AgentError, Option[VersionedJson]] =
                  backend.read(location)
                def compareAndSet(location: StorageAddress, expected: ExpectedRevision, value: Json)
                    : IO[AgentError, ConditionalWrite] =
                  writes
                    .getAndUpdate(_ :+ value)
                    .flatMap: previous =>
                      ZIO.when(previous.isEmpty)(competitor.append(Chunk(draft("competitor")))) *>
                        backend.compareAndSet(location, expected, value)
              subject <- transcript(wrapped, target, budget)
              outcome <- subject
                .append(Chunk(draft("subject", Some("subject")), draft("anonymous")))
                .exit
              attempts  <- writes.get
              proposals <- ZIO.foreach(attempts)(proposed)
              current   <- competitor.entries
              firstCandidates = proposals.headOption.toList.flatMap(_.toList)
              laterCandidates = proposals.drop(1).flatMap(_.drop(1))
            yield assertTrue(
              if budget == 1 then outcome.isFailure && attempts.size == 1 && current.size == 1
              else
                outcome == Exit.succeed(current.drop(1)) && attempts.size == 2 && current.size == 3
              ,
              linked(current),
              laterCandidates.map(entry => entry.id -> entry.recordedAt).toList ==
                (if budget == 1 then List.empty
                 else firstCandidates.map(entry => entry.id -> entry.recordedAt)),
              if budget == 1 then true
              else laterCandidates.headOption.flatMap(_.parentId) == current.headOption.map(_.id)
            )
          .map(_.foldLeft(assertTrue(true))(_ && _))
    ,
    test(
      "strict aggregate validation preserves exact tool JSON and never replays lost acknowledgements"
    ):
      fixture: (_, backend, name) =>
        for
          target  <- address(name, "valid")
          subject <- transcript(backend, target)
          input = Json.Obj(
            "precise" -> Json.Num(BigDecimal("9007199254740993.000000000000000001")),
            "minimum" -> Json.Num(Long.MinValue),
            "unicode" -> Json.Str("valid \ud83d\ude80")
          )
          tool = TranscriptDraft(
            Some(TranscriptKey.ToolUse("message", 0)),
            TranscriptPayload.ToolUse("call", "inspect", TranscriptPreview.Complete(input))
          )
          committed     <- subject.append(Chunk(draft("message", Some("message")), tool))
          reread        <- subject.entries
          beforeInvalid <- backend.read(target)
          invalidBatches = Chunk(
            Chunk(draft("not published"), draft("invalid", Some(""))),
            Chunk(draft("not published"), draft("bad " + 0xd800.toChar))
          )
          invalidAppends <- ZIO.foreach(invalidBatches)(subject.append(_).exit)
          afterInvalid   <- backend.read(target)
          json           <- encoded(committed)
          firstJson      <- encoded(committed.take(1))
          badChain       <- encoded(committed.reverse)
          duplicate      <- encoded(committed ++ committed.take(1))
          unknownEntry = firstJson match
            case Json.Arr(values) =>
              Json.Arr(values.map:
                case Json.Obj(fields) => Json.Obj(fields :+ ("extra" -> Json.Bool(true)))
                case value            => value)
            case value => value
          malformed = Chunk(
            aggregate(json, 2),
            Json.Obj("schemaVersion" -> Json.Num(1)),
            Json.Obj("schemaVersion" -> Json.Num(1), "entries" -> json, "extra" -> Json.Bool(true)),
            aggregate(Json.Str("not an array")),
            aggregate(badChain),
            aggregate(duplicate),
            aggregate(unknownEntry)
          )
          rejected <- ZIO.foreach(malformed.zipWithIndex): (value, index) =>
            for
              invalidTarget <- address(name, s"invalid-$index")
              _             <- backend.compareAndSet(invalidTarget, ExpectedRevision.Absent, value)
              before        <- backend.read(invalidTarget)
              invalid       <- transcript(backend, invalidTarget)
              read          <- invalid.entries.exit
              append        <- invalid.append(Chunk(draft("must not publish"))).exit
              after         <- backend.read(invalidTarget)
            yield read.causeOption.exists(
              _.failures.exists(_.isInstanceOf[AgentError.MemoryStorageError])
            ) && append.isFailure && before == after
          uncertainTarget <- address(name, "ambiguous")
          calls           <- Ref.make(0)
          uncertainBackend = new VersionedJsonStore:
            def read(location: StorageAddress): IO[AgentError, Option[VersionedJson]] =
              backend.read(location)
            def compareAndSet(location: StorageAddress, expected: ExpectedRevision, value: Json)
                : IO[AgentError, ConditionalWrite] =
              calls.update(_ + 1) *> backend
                .compareAndSet(location, expected, value)
                .flatMap:
                  case ConditionalWrite.Applied(_) =>
                    ZIO.fail(AgentError.StorageError("Lost acknowledgement"))
                  case ConditionalWrite.Conflict => ZIO.succeed(ConditionalWrite.Conflict)
          uncertain <- transcript(uncertainBackend, uncertainTarget)
          outcome   <- uncertain.append(Chunk(draft("once"))).exit
          attempts  <- calls.get
          restored  <- transcript(backend, uncertainTarget)
          persisted <- restored.entries
        yield assertTrue(
          reread == committed,
          invalidAppends.forall(_.isFailure),
          beforeInvalid == afterInvalid,
          linked(reread),
          rejected.forall(identity),
          outcome.causeOption.exists(_.failures.exists(_.isInstanceOf[AgentError.StorageError])),
          attempts == 1,
          persisted.size == 1,
          VersionedTranscriptStore.make(backend, target, maxAttempts = 0).isLeft
        )
  ) @@ TestAspect.sequential @@ TestAspect.withLiveRandom @@ TestAspect.withLiveSystem @@ TestAspect.withLiveClock @@
    TestAspect.timeout(30.seconds) @@ TestAspect.ifEnvSet("WEISGNOSIS_POSTGRES_URL")
