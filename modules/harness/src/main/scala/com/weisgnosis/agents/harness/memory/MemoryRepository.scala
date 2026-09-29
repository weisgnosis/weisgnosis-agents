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
  final private case class State(
      revision: BigInt,
      curated: Option[String],
      daily: Map[LocalDate, String],
      acknowledged: Map[LocalDate, String],
      archived: Map[DailyMemoryArchive, String]
  )

  /** Process-local storage; snapshots and conditional commits are atomic immutable Ref transitions. */
  def inMemory(initialCurated: Option[String] = None): UIO[MemoryRepository] =
    Ref.Synchronized
      .make(State(BigInt(0), initialCurated, Map.empty, Map.empty, Map.empty))
      .map: state =>
        val daily = new DailyMemoryStore:
          def readCurated: IO[AgentError, Option[String]] = state.get.map(_.curated)
          def readDaily(day: LocalDate): IO[AgentError, Option[String]] =
            state.get.map(_.daily.get(day))
          def appendDaily(day: LocalDate, entry: String): IO[AgentError, Unit] =
            if entry.isEmpty then ZIO.unit
            else
              state.update(current =>
                current.copy(
                  revision = current.revision + 1,
                  daily = current.daily.updated(day, current.daily.getOrElse(day, "") + entry)
                )
              )
        val consolidation = new ConsolidationStore:
          type Revision = BigInt
          def snapshot: IO[AgentError, ConsolidationSnapshot[Revision]] = state.get.map: current =>
            val pending = current.daily.toList.collect:
              case (day, content) if !current.acknowledged.get(day).contains(content) =>
                DailyMemory(day, content)
            ConsolidationSnapshot(
              current.revision,
              current.curated.getOrElse(""),
              Chunk.fromIterable(pending.sortBy(_.day.toEpochDay))
            )

          def commit(expected: Revision, content: String): IO[AgentError, ConsolidationCommit] =
            state.modify: current =>
              if current.revision != expected then (ConsolidationCommit.Conflict, current)
              else
                (
                  ConsolidationCommit.Applied,
                  current.copy(
                    revision = current.revision + 1,
                    curated = Some(content),
                    acknowledged = current.daily
                  )
                )
        val retention = new DailyMemoryRetention:
          def archiveBefore(cutoff: LocalDate): IO[AgentError, Chunk[DailyMemoryArchive]] =
            state.modifyZIO: current =>
              val eligible = current.daily.toList
                .filter((day, content) =>
                  day.isBefore(cutoff) && current.acknowledged.get(day).contains(content)
                )
                .sortBy(_._1.toEpochDay)
              ZIO
                .foreach(eligible): (day, content) =>
                  MemoryFingerprint
                    .of(content)
                    .map(fingerprint => DailyMemoryArchive(day, fingerprint) -> content)
                .flatMap: entries =>
                  val days = entries.map(_._1.day).toSet
                  val next =
                    if entries.isEmpty then current
                    else
                      current.copy(
                        revision = current.revision + 1,
                        daily = current.daily -- days,
                        acknowledged = current.acknowledged -- days,
                        archived = current.archived ++ entries
                      )
                  ZIO
                    .fail(AgentError.MemoryStorageError("Archive content identity conflict"))
                    .when(
                      entries.exists((archive, content) =>
                        current.archived.get(archive).exists(_ != content)
                      )
                    )
                    .as(Chunk.fromIterable(entries.map(_._1)) -> next)
          def archives: IO[AgentError, Chunk[DailyMemoryArchive]] =
            state.get.map(current =>
              Chunk.fromIterable(
                current.archived.keys.toList
                  .sortBy(archive => (archive.day.toEpochDay, archive.fingerprint.value))
              )
            )
          def readArchive(archive: DailyMemoryArchive): IO[AgentError, Option[String]] =
            state.get.map(_.archived.get(archive))
        new MemoryRepository(daily, consolidation, retention)
