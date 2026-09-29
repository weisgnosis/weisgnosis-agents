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

import java.time.LocalDate
import zio.*
import zio.json.*
import zio.test.*

object MemoryRepositorySpec extends ZIOSpecDefault:
  private val day: LocalDate = LocalDate.of(2026, 9, 29)

  def spec: Spec[TestEnvironment, Any] = suite("MemoryRepositorySpec")(
    test("daily appends become pending without mutating curated content") {
      for
        repository <- MemoryRepository.inMemory(Some("curated"))
        initial    <- repository.consolidation.snapshot
        _          <- repository.daily.appendDaily(day, "first")
        _          <- repository.daily.appendDaily(day, " second")
        _          <- repository.daily.appendDaily(day.plusDays(1), "")
        snapshot   <- repository.consolidation.snapshot
        curated    <- repository.daily.readCurated
        daily      <- repository.daily.readDaily(day)
        missing    <- repository.daily.readDaily(day.plusDays(1))
      yield assertTrue(
        initial.pending.isEmpty,
        curated.contains("curated"),
        daily.contains("first second"),
        missing.isEmpty,
        snapshot.curated == "curated",
        snapshot.pending == Chunk(DailyMemory(day, "first second"))
      )
    },
    test("conditional commit updates content and progress in one snapshot transition") {
      for
        repository <- MemoryRepository.inMemory(Some("old"))
        store = repository.consolidation
        _      <- repository.daily.appendDaily(day, "new fact")
        before <- store.snapshot
        observations <- ZIO
          .foreachPar(1 to 32)(_ => store.snapshot)
          .zipPar(store.commit(before.revision, "merged"))
        after     <- store.snapshot
        duplicate <- store.commit(before.revision, "stale")
        retained  <- store.snapshot
      yield assertTrue(
        observations._2 == ConsolidationCommit.Applied,
        observations._1.forall(snapshot =>
          (snapshot.curated == "old" && snapshot.pending == Chunk(DailyMemory(day, "new fact"))) ||
            (snapshot.curated == "merged" && snapshot.pending.isEmpty)
        ),
        after.curated == "merged",
        after.pending.isEmpty,
        duplicate == ConsolidationCommit.Conflict,
        retained == after
      )
    },
    test("a later daily mutation invalidates the whole snapshot without losing either input") {
      for
        repository <- MemoryRepository.inMemory(Some("old"))
        store = repository.consolidation
        _      <- repository.daily.appendDaily(day, "first")
        before <- store.snapshot
        _      <- repository.daily.appendDaily(day.plusDays(1), "second")
        result <- store.commit(before.revision, "stale")
        after  <- store.snapshot
      yield assertTrue(
        result == ConsolidationCommit.Conflict,
        after.curated == "old",
        after.pending == Chunk(DailyMemory(day, "first"), DailyMemory(day.plusDays(1), "second"))
      )
    },
    test("appending after acknowledgement makes the full changed day pending again") {
      for
        repository <- MemoryRepository.inMemory()
        store = repository.consolidation
        _       <- repository.daily.appendDaily(day, "first")
        first   <- store.snapshot
        _       <- store.commit(first.revision, "curated first")
        _       <- repository.daily.appendDaily(day, " second")
        pending <- store.snapshot
      yield assertTrue(
        pending.curated == "curated first",
        pending.pending == Chunk(DailyMemory(day, "first second"))
      )
    },
    test("snapshot and commit result codecs retain their domain fields") {
      val snapshot = ConsolidationSnapshot(7, "curated", Chunk(DailyMemory(day, "fact")))
      assertTrue(
        snapshot.toJson.fromJson[ConsolidationSnapshot[Int]] == Right(snapshot),
        ConsolidationCommit.Conflict.toJson.fromJson[ConsolidationCommit] == Right(
          ConsolidationCommit.Conflict
        )
      )
    },
    test("revision types from different store capabilities cannot be mixed") {
      for result <- typeCheck("""
          import com.weisgnosis.agents.harness.memory.*
          import com.weisgnosis.agents.core.error.AgentError
          import zio.*
          def mixed(first: ConsolidationStore, second: ConsolidationStore): IO[AgentError, ConsolidationCommit] =
            first.snapshot.flatMap(snapshot => second.commit(snapshot.revision, "wrong store"))
        """)
      yield assertTrue(result.isLeft)
    }
  )
