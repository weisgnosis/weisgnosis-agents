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
import com.weisgnosis.agents.harness.memory.MemoryTestSupport.temporaryDirectory
import com.weisgnosis.agents.harness.storage.{LocalVersionedJsonStore, StorageAddress}
import java.time.LocalDate
import zio.*
import zio.test.*

object DailyMemoryDiscoverySpec extends ZIOSpecDefault:
  private val day: LocalDate = LocalDate.of(2026, 10, 8)

  private def source(operation: IO[AgentError, Chunk[LocalDate]]): DailyMemoryStore =
    new DailyMemoryStore:
      def readCurated: IO[AgentError, Option[String]] = ZIO.none
      def readDaily(day: LocalDate): IO[AgentError, Option[String]] =
        ZIO.fail(AgentError.MemoryStorageError(s"Unexpected content read: $day"))
      def appendDaily(day: LocalDate, entry: String): IO[AgentError, Unit] =
        ZIO.fail(AgentError.MemoryStorageError(s"Unexpected append: $day (${entry.length})"))
      override def days: IO[AgentError, Chunk[LocalDate]] = operation

  def spec: Spec[TestEnvironment & Scope, Any] = suite("DailyMemoryDiscoverySpec")(
    test(
      "explicit local, remote and memory ledgers enumerate without merging or acknowledging content"
    ) {
      for
        localDirectory  <- temporaryDirectory
        remoteDirectory <- temporaryDirectory
        local           <- LocalMemoryRepository.open(localDirectory, WorkspaceTrust.HostControlled)
        address         <- ZIO.fromEither(StorageAddress.from(Chunk("remote"), "memory"))
        backend <- LocalVersionedJsonStore.open(
          remoteDirectory.resolve("memory.json"),
          address,
          WorkspaceTrust.HostControlled
        )
        remote  <- ZIO.fromEither(VersionedMemoryRepository.make(backend, address))
        memory  <- MemoryRepository.inMemory()
        _       <- local.daily.appendDaily(day, "acknowledged local")
        _       <- local.daily.appendDaily(day.plusDays(1), "local-only content")
        initial <- local.consolidation.snapshot
        _       <- local.consolidation.commit(initial.revision, "curated local")
        _       <- remote.daily.appendDaily(day.plusDays(1), "independent remote content")
        _       <- remote.daily.appendDaily(day.plusDays(2), "remote-only content")
        _       <- memory.daily.appendDaily(day.plusDays(3), "memory-only content")
        sources = Chunk(local.daily, remote.daily, memory.daily)
        localBefore  <- local.consolidation.snapshot
        remoteBefore <- backend.read(address)
        listed       <- DailyMemoryStore.discoverDays(sources)
        overflow <- DailyMemoryStore
          .discoverDays(sources, DailyMemoryStore.DiscoveryLimits(maxDays = 4))
          .either
        localAfter    <- local.consolidation.snapshot
        remoteAfter   <- backend.read(address)
        localContent  <- local.daily.readDaily(day.plusDays(1))
        remoteContent <- remote.daily.readDaily(day.plusDays(1))
        _             <- local.retention.archiveBefore(day.plusDays(1))
        retained      <- DailyMemoryStore.discoverDays(sources)
      yield assertTrue(
        listed == Chunk(day, day.plusDays(1), day.plusDays(2), day.plusDays(3)),
        overflow.isLeft,
        localBefore == localAfter,
        remoteBefore == remoteAfter,
        localBefore.pending.isEmpty,
        localContent.contains("local-only content"),
        remoteContent.contains("independent remote content"),
        retained == listed.drop(1)
      )
    },
    test(
      "invalid bounds acquire nothing and source failures stop enumeration with their exact cause"
    ) {
      val failure = AgentError.MemoryStorageError("Selected daily source failed")
      for
        calls   <- Ref.make(0)
        cleaned <- Ref.make(false)
        next = source(calls.update(_ + 1).as(Chunk(day)))
        rejected <- ZIO.foreach(
          Chunk(
            (Chunk(next), DailyMemoryStore.DiscoveryLimits(0, 1)),
            (Chunk(next), DailyMemoryStore.DiscoveryLimits(1, 0)),
            (Chunk(next, next), DailyMemoryStore.DiscoveryLimits(1, 1))
          )
        )((stores, limits) => DailyMemoryStore.discoverDays(stores, limits).either)
        before <- calls.get
        first = source(ZIO.fail(failure).ensuring(cleaned.set(true)))
        failed   <- DailyMemoryStore.discoverDays(Chunk(first, next)).exit
        after    <- calls.get
        released <- cleaned.get
        empty    <- DailyMemoryStore.discoverDays(Chunk.empty)
        duplicateOverflow <- DailyMemoryStore
          .discoverDays(Chunk(next, next), DailyMemoryStore.DiscoveryLimits(maxDays = 1))
          .either
      yield assertTrue(
        before == 0,
        after == 0,
        rejected.forall(_.isLeft),
        released,
        failed.causeOption.exists(_.failures == List(failure)),
        empty.isEmpty,
        duplicateOverflow.isLeft
      )
    }
  )
