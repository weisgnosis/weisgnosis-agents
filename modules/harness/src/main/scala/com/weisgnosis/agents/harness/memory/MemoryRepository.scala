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
import java.time.LocalDate
import zio.*

/** Distinct write capabilities sharing one authority for curated and daily content. */
final class MemoryRepository private[memory] (
    val daily: DailyMemoryStore,
    val consolidation: ConsolidationStore,
    val retention: DailyMemoryRetention
)

object MemoryRepository:
  final private case class State(revision: BigInt, data: MemoryRepositoryState)

  /** Process-local storage; snapshots and conditional commits are atomic immutable Ref transitions. */
  def inMemory(initialCurated: Option[String] = None): UIO[MemoryRepository] =
    Ref.Synchronized
      .make(State(BigInt(0), MemoryRepositoryState.empty(initialCurated)))
      .map: state =>
        val daily = new DailyMemoryStore:
          def readCurated: IO[AgentError, Option[String]] = state.get.map(_.data.curated)
          def readDaily(day: LocalDate): IO[AgentError, Option[String]] =
            state.get.map(_.data.daily.get(day))
          override def days: IO[AgentError, Chunk[LocalDate]] =
            state.get
              .map(value => Chunk.fromIterable(value.data.daily.keys.toList.sortBy(_.toEpochDay)))
          def appendDaily(day: LocalDate, entry: String): IO[AgentError, Unit] =
            if entry.isEmpty then ZIO.unit
            else
              state.update(current => State(current.revision + 1, current.data.append(day, entry)))
        val consolidation = new ConsolidationStore:
          type Revision = BigInt
          def snapshot: IO[AgentError, ConsolidationSnapshot[Revision]] =
            state.get.map(current => current.data.snapshot(current.revision))
          def commit(expected: Revision, content: String): IO[AgentError, ConsolidationCommit] =
            state.modify: current =>
              if current.revision != expected then (ConsolidationCommit.Conflict, current)
              else
                (
                  ConsolidationCommit.Applied,
                  State(current.revision + 1, current.data.consolidate(content))
                )
        val retention = new DailyMemoryRetention:
          def archiveBefore(cutoff: LocalDate): IO[AgentError, Chunk[DailyMemoryArchive]] =
            state.modifyZIO: current =>
              current.data
                .archiveBefore(cutoff)
                .map: (archives, next) =>
                  val updated =
                    if archives.isEmpty then current else State(current.revision + 1, next)
                  archives -> updated
          def archives: IO[AgentError, Chunk[DailyMemoryArchive]] = state.get.map(_.data.archives)
          def readArchive(archive: DailyMemoryArchive): IO[AgentError, Option[String]] =
            state.get.map(_.data.archived.get(archive))
        new MemoryRepository(daily, consolidation, retention)
