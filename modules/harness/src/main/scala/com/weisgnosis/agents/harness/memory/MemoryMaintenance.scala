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
import java.time.{LocalDate, ZoneId, ZoneOffset}
import zio.*
import zio.json.*

enum DailyArchivalResult derives JsonCodec:
  case Kept
  case Archived(cutoff: LocalDate, entries: Chunk[DailyMemoryArchive])

/** Deferred consolidation outcomes cannot carry an archival success. */
enum MemoryMaintenanceResult derives JsonCodec:
  case Committed(archival: DailyArchivalResult)
  case NoPendingMemories(archival: DailyArchivalResult)
  case EmptyResponse, Conflict

/** One repository supplies both capabilities; compute a valid cutoff before any consolidation work. */
final class MemoryMaintenance private (
    consolidator: MemoryConsolidator,
    retention: DailyMemoryRetention,
    policy: MemoryRetentionPolicy,
    zone: ZoneId
):
  def run: IO[AgentError, MemoryMaintenanceResult] =
    for
      cutoff <- policy match
        case MemoryRetentionPolicy.KeepAll => ZIO.none
        case MemoryRetentionPolicy.ArchiveAfter(_) =>
          Clock.instant
            .flatMap(instant =>
              ZIO
                .attempt(instant.atZone(zone).toLocalDate)
                .mapError(error =>
                  AgentError.InvalidConfiguration(s"Invalid maintenance date: $error")
                )
            )
            .flatMap(today => ZIO.fromEither(policy.cutoff(today)))
      consolidated <- consolidator.consolidate
      result <- consolidated match
        case ConsolidationResult.Committed =>
          archive(cutoff).map(MemoryMaintenanceResult.Committed.apply)
        case ConsolidationResult.NoPendingMemories =>
          archive(cutoff).map(MemoryMaintenanceResult.NoPendingMemories.apply)
        case ConsolidationResult.EmptyResponse => ZIO.succeed(MemoryMaintenanceResult.EmptyResponse)
        case ConsolidationResult.Conflict      => ZIO.succeed(MemoryMaintenanceResult.Conflict)
    yield result

  private def archive(cutoff: Option[LocalDate]): IO[AgentError, DailyArchivalResult] = cutoff match
    case None      => ZIO.succeed(DailyArchivalResult.Kept)
    case Some(day) => retention.archiveBefore(day).map(DailyArchivalResult.Archived(day, _))

object MemoryMaintenance:
  def make(
      repository: MemoryRepository,
      extractor: MemoryExtractor,
      policy: MemoryRetentionPolicy = MemoryRetentionPolicy.DailyDefault,
      zone: ZoneId = ZoneOffset.UTC,
      counter: TokenCounter = TokenCounter.approximate,
      maxTokens: Int = MemoryConsolidator.MaximumMemoryTokens
  ): IO[AgentError, MemoryMaintenance] =
    MemoryConsolidator
      .make(repository.consolidation, extractor, counter, maxTokens)
      .map(new MemoryMaintenance(_, repository.retention, policy, zone))
