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
import com.weisgnosis.agents.harness.memory.*
import com.weisgnosis.agents.harness.storage.*
import java.time.LocalDate
import zio.*
import zio.json.ast.Json
import zio.test.*

object VersionedMemoryRepositorySpec extends ZIOSpecDefault:
  import PostgresFixture.*

  private val day: LocalDate = LocalDate.of(2026, 10, 7)

  private def repository(
      backend: VersionedJsonStore,
      target: StorageAddress,
      maxAttempts: Int = 8
  ): IO[AgentError, MemoryRepository] =
    ZIO.fromEither(VersionedMemoryRepository.make(backend, target, maxAttempts))

  private def aggregate(
      daily: Json = Json.Arr(),
      acknowledged: Json = Json.Arr(),
      archived: Json = Json.Arr(),
      schemaVersion: Int = 1,
      curated: Json = Json.Null
  ): Json.Obj =
    Json.Obj(
      "schemaVersion" -> Json.Num(schemaVersion),
      "curated"       -> curated,
      "daily"         -> daily,
      "acknowledged"  -> acknowledged,
      "archived"      -> archived
    )

  private def ledger(content: String): Json.Obj =
    Json.Obj("day" -> Json.Str(day.toString), "content" -> Json.Str(content))

  private def withExtra(value: Json.Obj): Json.Obj =
    Json.Obj(value.fields :+ ("extra" -> Json.Bool(true)))

  private def interpose(
      backend: VersionedJsonStore,
      beforeWrite: IO[AgentError, Unit],
      calls: Ref[Int]
  ): VersionedJsonStore = new VersionedJsonStore:
    def read(target: StorageAddress): IO[AgentError, Option[VersionedJson]] = backend.read(target)
    def compareAndSet(
        target: StorageAddress,
        expected: ExpectedRevision,
        value: Json
    ): IO[AgentError, ConditionalWrite] =
      calls
        .getAndUpdate(_ + 1)
        .flatMap: previous =>
          ZIO.when(previous == 0)(beforeWrite) *> backend.compareAndSet(target, expected, value)

  def spec: Spec[TestEnvironment, Any] = suite("VersionedMemoryRepositorySpec")(
    test("competing appends survive independent repository acquisition and namespace isolation"):
      fixture: (source, backend, name) =>
        for
          target        <- address(name, "memory")
          other         <- address(name + "-isolated", "memory")
          secondSource  <- dataSource(name + "-second")
          secondBackend <- store(secondSource)
          first         <- repository(backend, target, maxAttempts = 32)
          second        <- repository(secondBackend, target, maxAttempts = 32)
          isolated      <- repository(secondBackend, other)
          initial       <- first.consolidation.snapshot
          absent        <- backend.read(target)
          entries = Chunk.fromIterable(1 to 12).map(index => s"entry-$index")
          _ <- ZIO.foreachParDiscard(entries.zipWithIndex): (entry, index) =>
            val selected = if index % 2 == 0 then first else second
            selected.daily.appendDaily(day, entry + "|")
          reopenedBackend <- store(source)
          reopened        <- repository(reopenedBackend, target)
          content         <- reopened.daily.readDaily(day)
          curated         <- reopened.daily.readCurated
          pending         <- reopened.consolidation.snapshot
          untouched       <- isolated.daily.readDaily(day)
          _               <- isolated.daily.appendDaily(day, "separate")
          unchanged       <- reopened.daily.readDaily(day)
        yield assertTrue(
          initial.curated.isEmpty,
          initial.pending.isEmpty,
          absent.isEmpty,
          content.exists(value => value.split("\\|").toList.sorted == entries.toList.sorted),
          curated.isEmpty,
          pending.pending == content.fold(Chunk.empty[DailyMemory])(value =>
            Chunk(DailyMemory(day, value))
          ),
          untouched.isEmpty,
          unchanged == content
        )
    ,
    test(
      "stale consolidation cannot acknowledge later inputs and retention races preserve exact content"
    ):
      fixture: (source, backend, name) =>
        for
          target        <- address(name, "memory")
          first         <- repository(backend, target)
          secondBackend <- store(source)
          second        <- repository(secondBackend, target)
          consolidation = first.consolidation
          _            <- first.daily.appendDaily(day, "old")
          stale        <- consolidation.snapshot
          _            <- second.daily.appendDaily(day.plusDays(1), "pending")
          rejected     <- consolidation.commit(stale.revision, "stale")
          pending      <- consolidation.snapshot
          accepted     <- consolidation.commit(pending.revision, "curated")
          acknowledged <- consolidation.snapshot
          race <- first.retention
            .archiveBefore(day.plusDays(1))
            .zipPar(
              second.daily.appendDaily(day, "+new")
            )
          archives          <- second.retention.archives
          archived          <- ZIO.foreach(archives)(second.retention.readArchive)
          current           <- second.daily.readDaily(day)
          after             <- second.consolidation.snapshot
          recent            <- second.daily.readDaily(day.plusDays(1))
          reopened          <- repository(backend, target)
          recovered         <- reopened.daily.readDaily(day)
          recoveredArchives <- reopened.retention.archives
        yield assertTrue(
          rejected == ConsolidationCommit.Conflict,
          pending.curated.isEmpty,
          pending.pending == Chunk(
            DailyMemory(day, "old"),
            DailyMemory(day.plusDays(1), "pending")
          ),
          accepted == ConsolidationCommit.Applied,
          acknowledged.curated == "curated",
          acknowledged.pending.isEmpty,
          race.size <= 1,
          archived.flatten.mkString + current.getOrElse("") == "old+new",
          after.curated == "curated",
          after.pending == current.fold(Chunk.empty[DailyMemory])(value =>
            Chunk(DailyMemory(day, value))
          ),
          recent.contains("pending"),
          recovered == current,
          recoveredArchives == archives
        )
    ,
    test("invalid persisted aggregates never mutate and only explicit CAS conflicts are retried"):
      fixture: (_, backend, name) =>
        for
          fingerprint <- MemoryFingerprint.of("original")
          archiveIdentity = Json.Obj(
            "day"         -> Json.Str(day.toString),
            "fingerprint" -> Json.Str(fingerprint.value)
          )
          archived = Json.Obj(
            "archive" -> archiveIdentity,
            "content" -> Json.Str("changed")
          )
          validArchive = Json.Obj("archive" -> archiveIdentity, "content" -> Json.Str("original"))
          malformed = Chunk(
            aggregate(schemaVersion = 2),
            withExtra(aggregate()),
            Json.Obj("schemaVersion" -> Json.Num(1), "curated" -> Json.Null),
            aggregate(daily = Json.Arr(withExtra(ledger("one")))),
            aggregate(daily = Json.Arr(ledger("one"), ledger("two"))),
            aggregate(acknowledged = Json.Arr(ledger("missing")), curated = Json.Str("curated")),
            aggregate(
              daily = Json.Arr(ledger("one")),
              acknowledged = Json.Arr(ledger("other")),
              curated = Json.Str("curated")
            ),
            aggregate(daily = Json.Arr(ledger(""))),
            aggregate(archived = Json.Arr(archived), curated = Json.Str("curated")),
            aggregate(archived = Json.Arr(withExtra(validArchive)), curated = Json.Str("curated")),
            aggregate(
              archived = Json.Arr(
                Json.Obj(
                  "archive" -> withExtra(archiveIdentity),
                  "content" -> Json.Str("original")
                )
              ),
              curated = Json.Str("curated")
            ),
            aggregate(
              archived = Json.Arr(validArchive, validArchive),
              curated = Json.Str("curated")
            )
          )
          rejected <- ZIO.foreach(malformed.zipWithIndex): (value, index) =>
            for
              target  <- address(name, s"invalid-$index")
              _       <- backend.compareAndSet(target, ExpectedRevision.Absent, value)
              before  <- backend.read(target)
              invalid <- repository(backend, target)
              read    <- invalid.consolidation.snapshot.exit
              append  <- invalid.daily.appendDaily(day, "must not publish").exit
              after   <- backend.read(target)
            yield read.causeOption.exists(
              _.failures.exists(_.isInstanceOf[AgentError.MemoryStorageError])
            ) &&
              append.isFailure && after == before
          validTarget <- address(name, "valid-archive")
          _ <- backend.compareAndSet(
            validTarget,
            ExpectedRevision.Absent,
            aggregate(archived = Json.Arr(validArchive), curated = Json.Str("curated"))
          )
          valid         <- repository(backend, validTarget)
          validArchives <- valid.retention.archives
          validContent  <- valid.retention.readArchive(DailyMemoryArchive(day, fingerprint))
          retried <- ZIO.foreach(Chunk(1, 8)): budget =>
            for
              target     <- address(name, s"retry-$budget")
              competitor <- repository(backend, target)
              calls      <- Ref.make(0)
              wrapped = interpose(backend, competitor.daily.appendDaily(day, "competitor|"), calls)
              subject   <- repository(wrapped, target, budget)
              outcome   <- subject.daily.appendDaily(day, "subject|").exit
              count     <- calls.get
              persisted <- competitor.daily.readDaily(day)
            yield
              if budget == 1 then
                outcome.isFailure && count == 1 && persisted.contains("competitor|")
              else outcome.isSuccess && count == 2 && persisted.contains("competitor|subject|")
          target <- address(name, "ambiguous")
          calls  <- Ref.make(0)
          ambiguous = new VersionedJsonStore:
            def read(location: StorageAddress): IO[AgentError, Option[VersionedJson]] =
              backend.read(location)
            def compareAndSet(
                location: StorageAddress,
                expected: ExpectedRevision,
                value: Json
            ): IO[AgentError, ConditionalWrite] =
              calls.update(_ + 1) *> backend
                .compareAndSet(location, expected, value)
                .flatMap:
                  case ConditionalWrite.Applied(_) =>
                    ZIO.fail(AgentError.StorageError("Lost acknowledgement"))
                  case ConditionalWrite.Conflict => ZIO.succeed(ConditionalWrite.Conflict)
          uncertain       <- repository(ambiguous, target)
          acknowledgement <- uncertain.daily.appendDaily(day, "once").exit
          attempts        <- calls.get
          restored        <- repository(backend, target)
          committed       <- restored.daily.readDaily(day)
        yield assertTrue(
          rejected.forall(identity),
          validArchives == Chunk(DailyMemoryArchive(day, fingerprint)),
          validContent.contains("original"),
          retried.forall(identity),
          acknowledgement.causeOption.exists(
            _.failures.exists(_.isInstanceOf[AgentError.StorageError])
          ),
          attempts == 1,
          committed.contains("once"),
          VersionedMemoryRepository.make(backend, target, maxAttempts = 0).isLeft
        )
  ) @@ TestAspect.sequential @@ TestAspect.withLiveRandom @@ TestAspect.withLiveSystem @@ TestAspect.withLiveClock @@
    TestAspect.timeout(30.seconds) @@ TestAspect.ifEnvSet("WEISGNOSIS_POSTGRES_URL")
