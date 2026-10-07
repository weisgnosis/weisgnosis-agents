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

/** Shared ledger transitions; revision ownership belongs to the selected repository backend. */
final private[memory] case class MemoryRepositoryState(
    curated: Option[String],
    daily: Map[LocalDate, String],
    acknowledged: Map[LocalDate, String],
    archived: Map[DailyMemoryArchive, String]
):
  def append(day: LocalDate, entry: String): MemoryRepositoryState =
    copy(daily = daily.updated(day, daily.getOrElse(day, "") + entry))

  def snapshot[Revision](revision: Revision): ConsolidationSnapshot[Revision] =
    val pending = daily.toList.collect:
      case (day, content) if !acknowledged.get(day).contains(content) => DailyMemory(day, content)
    ConsolidationSnapshot(
      revision,
      curated.getOrElse(""),
      Chunk.fromIterable(pending.sortBy(_.day.toEpochDay))
    )

  def consolidate(content: String): MemoryRepositoryState =
    copy(curated = Some(content), acknowledged = daily)

  def archives: Chunk[DailyMemoryArchive] =
    Chunk.fromIterable(
      archived.keys.toList.sortBy(archive => (archive.day.toEpochDay, archive.fingerprint.value))
    )

  def archiveBefore(
      cutoff: LocalDate
  ): IO[AgentError, (Chunk[DailyMemoryArchive], MemoryRepositoryState)] =
    val eligible = daily.toList
      .filter((day, content) => day.isBefore(cutoff) && acknowledged.get(day).contains(content))
      .sortBy(_._1.toEpochDay)
    ZIO
      .foreach(eligible): (day, content) =>
        MemoryFingerprint
          .of(content)
          .map(fingerprint => DailyMemoryArchive(day, fingerprint) -> content)
      .flatMap: entries =>
        val days = entries.map(_._1.day).toSet
        val next =
          if entries.isEmpty then this
          else
            copy(
              daily = daily -- days,
              acknowledged = acknowledged -- days,
              archived = archived ++ entries
            )
        ZIO
          .fail(AgentError.MemoryStorageError("Archive content identity conflict"))
          .when(entries.exists((archive, content) => archived.get(archive).exists(_ != content)))
          .as(Chunk.fromIterable(entries.map(_._1)) -> next)

private[memory] object MemoryRepositoryState:
  def empty(curated: Option[String] = None): MemoryRepositoryState =
    MemoryRepositoryState(curated, Map.empty, Map.empty, Map.empty)
