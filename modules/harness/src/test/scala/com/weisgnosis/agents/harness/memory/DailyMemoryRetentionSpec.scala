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
import com.weisgnosis.agents.harness.eviction.WorkspaceTrust
import java.time.LocalDate
import zio.*
import zio.json.*
import zio.test.*

object DailyMemoryRetentionSpec extends ZIOSpecDefault:
  private enum Backend:
    case InMemory, Local

  private val day: LocalDate    = LocalDate.of(2026, 1, 1)
  private val cutoff: LocalDate = LocalDate.of(2026, 4, 1)

  private def open(backend: Backend): ZIO[Scope, Throwable, MemoryRepository] = backend match
    case Backend.InMemory => MemoryRepository.inMemory()
    case Backend.Local =>
      MemoryTestSupport.temporaryDirectory.flatMap(
        LocalMemoryRepository.open(_, WorkspaceTrust.HostControlled)
      )

  private def acknowledge(repository: MemoryRepository): IO[AgentError, Unit] =
    val store = repository.consolidation
    store.snapshot.flatMap(snapshot => store.commit(snapshot.revision, "curated")).unit

  def spec: Spec[TestEnvironment & Scope, Any] = suite("DailyMemoryRetentionSpec")(
    suite("fingerprints")(
      test(
        "canonical digests and typed archive identities round trip while invalid input is rejected"
      ) {
        for
          fingerprint <- MemoryFingerprint.of("abc")
          archive = DailyMemoryArchive(day, fingerprint)
        yield assertTrue(
          fingerprint.value == "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
          archive.toJson.fromJson[DailyMemoryArchive] == Right(archive),
          List("", "../private", "A" * 64, "0" * 63).forall(value =>
            MemoryFingerprint.fromString(value).isLeft
          ),
          List("1", "true", "{}", "\"short\"").forall(_.fromJson[MemoryFingerprint].isLeft)
        )
      },
      test("unpaired UTF-16 surrogates cannot alias legitimate UTF-8 archive content") {
        for
          high  <- MemoryFingerprint.of(0xd800.toChar.toString).exit
          low   <- MemoryFingerprint.of(0xdc00.toChar.toString).exit
          valid <- MemoryFingerprint.of("valid \uD83D\uDE00").exit
        yield assertTrue(high.isFailure, low.isFailure, valid.isSuccess)
      }
    ),
    suite("shared storage contracts")(
      Backend.values.toList.map(backend =>
        suite(backend.toString)(
          test("only exact acknowledged content strictly before cutoff is archived") {
            for
              repository <- open(backend)
              _          <- repository.daily.appendDaily(day, "old confirmed")
              _          <- repository.daily.appendDaily(cutoff, "on cutoff")
              _          <- repository.daily.appendDaily(cutoff.plusDays(1), "recent")
              _          <- acknowledge(repository)
              _          <- repository.daily.appendDaily(day.minusDays(1), "old unconfirmed")
              _          <- repository.daily.appendDaily(day, " changed after consolidation")
              none       <- repository.retention.archiveBefore(cutoff)
              _          <- acknowledge(repository)
              archived   <- repository.retention.archiveBefore(cutoff)
              contents   <- ZIO.foreach(archived)(repository.retention.readArchive)
              retained   <- repository.daily.readDaily(cutoff)
              recent     <- repository.daily.readDaily(cutoff.plusDays(1))
              removed    <- repository.daily.readDaily(day)
              curated    <- repository.daily.readCurated
            yield assertTrue(
              none.isEmpty,
              archived.map(_.day) == Chunk(day.minusDays(1), day),
              contents == Chunk(
                Some("old unconfirmed"),
                Some("old confirmed changed after consolidation")
              ),
              retained.contains("on cutoff"),
              recent.contains("recent"),
              removed.isEmpty,
              curated.contains("curated")
            )
          },
          test("archive operations preserve pending observations and unrelated acknowledgements") {
            for
              repository <- open(backend)
              _          <- repository.daily.appendDaily(day, "old")
              _          <- repository.daily.appendDaily(cutoff, "retained acknowledged")
              _          <- acknowledge(repository)
              _          <- repository.daily.appendDaily(day.minusDays(1), "pending")
              archived   <- repository.retention.archiveBefore(cutoff)
              snapshot   <- repository.consolidation.snapshot
              again      <- repository.retention.archiveBefore(cutoff)
            yield assertTrue(
              archived.size == 1,
              snapshot.pending == Chunk(DailyMemory(day.minusDays(1), "pending")),
              again.isEmpty
            )
          },
          test(
            "equal-content recreation is pending and cannot revive an old consolidation revision"
          ) {
            for
              repository <- open(backend)
              _          <- repository.daily.appendDaily(day, "same")
              _          <- acknowledge(repository)
              store = repository.consolidation
              before    <- store.snapshot
              archived  <- repository.retention.archiveBefore(cutoff)
              _         <- repository.daily.appendDaily(day, "same")
              pending   <- store.snapshot
              stale     <- store.commit(before.revision, "must not replace curated")
              premature <- repository.retention.archiveBefore(cutoff)
              _         <- acknowledge(repository)
              duplicate <- repository.retention.archiveBefore(cutoff)
              all       <- repository.retention.archives
            yield assertTrue(
              pending.pending == Chunk(DailyMemory(day, "same")),
              stale == ConsolidationCommit.Conflict,
              premature.isEmpty,
              duplicate == archived,
              all == archived
            )
          },
          test("different versions of the same day coexist in the archive") {
            for
              repository <- open(backend)
              _          <- repository.daily.appendDaily(day, "first")
              _          <- acknowledge(repository)
              first      <- repository.retention.archiveBefore(cutoff)
              _          <- repository.daily.appendDaily(day, "second")
              _          <- acknowledge(repository)
              second     <- repository.retention.archiveBefore(cutoff)
              all        <- repository.retention.archives
              contents   <- ZIO.foreach(all)(repository.retention.readArchive)
            yield assertTrue(
              first != second,
              all.size == 2,
              contents.toSet == Set(Some("first"), Some("second"))
            )
          },
          test("concurrent retirement calls archive each acknowledged daily version once") {
            for
              repository <- open(backend)
              _          <- repository.daily.appendDaily(day, "confirmed")
              _          <- acknowledge(repository)
              results    <- ZIO.foreachPar(1 to 20)(_ => repository.retention.archiveBefore(cutoff))
              all        <- repository.retention.archives
            yield assertTrue(results.map(_.size).sum == 1, all.size == 1)
          },
          test("an empty retention pass neither changes a revision nor creates archive entries") {
            for
              repository <- open(backend)
              store = repository.consolidation
              before     <- store.snapshot
              archived   <- repository.retention.archiveBefore(cutoff)
              committed  <- store.commit(before.revision, "still current")
              all        <- repository.retention.archives
              missingKey <- MemoryFingerprint.of("missing")
              missing    <- repository.retention.readArchive(DailyMemoryArchive(day, missingKey))
            yield assertTrue(
              archived.isEmpty,
              committed == ConsolidationCommit.Applied,
              all.isEmpty,
              missing.isEmpty
            )
          }
        )
      )*
    )
  )
