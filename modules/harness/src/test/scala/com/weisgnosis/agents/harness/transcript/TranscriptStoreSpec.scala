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
package com.weisgnosis.agents.harness.transcript

import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.model.Role
import java.time.Instant
import zio.*
import zio.test.*

object TranscriptStoreSpec extends ZIOSpecDefault:
  private def draft(id: String, text: String = "text"): TranscriptDraft = TranscriptDraft(
    Some(TranscriptKey.Message(id)),
    TranscriptPayload.Message(Role.User, text, Chunk(TranscriptBlockType.Text))
  )

  private def linked(entries: Chunk[TranscriptEntry]): Boolean =
    entries.map(_.parentId) == (Chunk(None) ++ entries.dropRight(1).map(entry => Some(entry.id)))

  def spec: Spec[TestEnvironment & Scope, Any] = suite("TranscriptStoreSpec")(
    test("deduplicates within and across batches and assigns parents after filtering") {
      for
        store <- TranscriptStore.inMemory
        first <- store.append(Chunk(draft("one"), draft("two")))
        second <- store.append(
          Chunk(draft("one"), draft("three"), draft("two"), draft("four"), draft("four"))
        )
        duplicate <- store.append(Chunk(draft("one"), draft("four")))
        all       <- store.entries
      yield assertTrue(
        first.size == 2,
        second.size == 2,
        duplicate.isEmpty,
        all.map(_.key) == Chunk("one", "two", "three", "four").map(id =>
          Some(TranscriptKey.Message(id))
        ),
        linked(all),
        second == all.drop(2),
        all.map(_.id).distinct.size == 4
      )
    },
    test("first observation wins when content changes under a stable key") {
      for
        store <- TranscriptStore.inMemory
        first <- store.append(Chunk(draft("same", "raw")))
        later <- store.append(Chunk(draft("same", "eviction preview")))
        all   <- store.entries
      yield assertTrue(later.isEmpty, all == first)
    },
    test("anonymous drafts append every time and stores isolate stable keys") {
      val anonymous = draft("ignored").copy(key = None)
      for
        left   <- TranscriptStore.inMemory
        right  <- TranscriptStore.inMemory
        _      <- left.append(Chunk(anonymous, draft("same")))
        _      <- left.append(Chunk(anonymous, draft("same")))
        _      <- right.append(Chunk(draft("same")))
        first  <- left.entries
        second <- right.entries
      yield assertTrue(
        first.size == 3,
        second.size == 1,
        linked(first),
        linked(second),
        first.map(_.id).toSet.intersect(second.map(_.id).toSet).isEmpty
      )
    },
    test("recording time is read lazily and binds the committed batch") {
      for
        store <- TranscriptStore.inMemory
        _     <- TestClock.setTime(Instant.EPOCH)
        append = store.append(Chunk(draft("one"), draft("two")))
        _       <- TestClock.setTime(Instant.parse("2026-09-29T10:00:00Z"))
        entries <- append
      yield assertTrue(entries.forall(_.recordedAt == Instant.parse("2026-09-29T10:00:00Z")))
    },
    test("an identity collision rejects the complete batch including earlier candidates") {
      for
        store   <- TranscriptStore.inMemory
        _       <- TestRandom.setSeed(19L)
        _       <- Random.nextUUID
        initial <- store.append(Chunk(draft("existing")))
        _       <- TestRandom.setSeed(19L)
        failed  <- store.append(Chunk(draft("new"), draft("collision"))).either
        all     <- store.entries
      yield assertTrue(
        failed.left.exists(_.isInstanceOf[AgentError.MemoryStorageError]),
        all == initial
      )
    },
    test("concurrent overlapping batches publish one continuous deduplicated chain") {
      for
        store <- TranscriptStore.inMemory
        start <- Promise.make[Nothing, Unit]
        left <- (start.await *> store.append(
          Chunk.fromIterable(1 to 20).map(index => draft(index.toString))
        )).forkScoped
        right <- (start.await *> store.append(
          Chunk.fromIterable(11 to 30).map(index => draft(index.toString))
        )).forkScoped
        _      <- start.succeed(())
        first  <- left.join
        second <- right.join
        all    <- store.entries
      yield assertTrue(
        all.size == 30,
        first.size + second.size == 30,
        all
          .flatMap(_.key)
          .toSet == (1 to 30).map(index => TranscriptKey.Message(index.toString)).toSet,
        linked(all),
        all.map(_.id).distinct.size == 30
      )
    },
    test("empty append returns no committed entries") {
      for
        store <- TranscriptStore.inMemory
        added <- store.append(Chunk.empty)
        all   <- store.entries
      yield assertTrue(added.isEmpty, all.isEmpty)
    }
  )
