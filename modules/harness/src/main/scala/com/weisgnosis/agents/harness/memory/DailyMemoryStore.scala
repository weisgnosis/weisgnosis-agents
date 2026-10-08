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
import zio.json.JsonCodec

/** A daily-ledger capability: curated memory can be read, but only dated entries can be appended. */
trait DailyMemoryStore:
  def readCurated: IO[AgentError, Option[String]]
  def readDaily(day: LocalDate): IO[AgentError, Option[String]]
  def appendDaily(day: LocalDate, entry: String): IO[AgentError, Unit]

  /** Current daily ledgers, including acknowledged content, without archived days. */
  def days: IO[AgentError, Chunk[LocalDate]] =
    ZIO.fail(
      AgentError.MemoryStorageError("Daily memory enumeration is unsupported by this binding")
    )

object DailyMemoryStore:
  final case class DiscoveryLimits(maxStores: Int = 16, maxDays: Int = 4096) derives JsonCodec

  /**
   * Union of explicitly borrowed stores, sorted chronologically and deduplicated by day.
   * Every returned candidate counts before deduplication. Each store owns its read bounds;
   * this operation neither merges content nor establishes an atomic cross-store snapshot.
   */
  def discoverDays(
      stores: Chunk[DailyMemoryStore],
      limits: DiscoveryLimits = DiscoveryLimits()
  ): IO[AgentError, Chunk[LocalDate]] =
    if limits.maxStores <= 0 || limits.maxDays <= 0 || stores.size > limits.maxStores then
      ZIO.fail(
        AgentError.InvalidConfiguration("Invalid daily memory discovery limits or source count")
      )
    else
      ZIO
        .foldLeft(stores)((0L, Set.empty[LocalDate])): (current, store) =>
          ZIO
            .suspendSucceed(store.days)
            .flatMap: days =>
              val count = current._1 + days.size.toLong
              if count > limits.maxDays.toLong then
                ZIO.fail(
                  AgentError.MemoryStorageError("Daily memory discovery candidate limit exceeded")
                )
              else ZIO.succeed(count -> (current._2 ++ days))
        .map(value => Chunk.fromIterable(value._2.toList.sortBy(_.toEpochDay)))
