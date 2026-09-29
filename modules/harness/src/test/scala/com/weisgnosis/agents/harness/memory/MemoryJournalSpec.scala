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

object MemoryJournalSpec extends ZIOSpecDefault:
  private val first: MemoryCheckpoint =
    MemoryCheckpoint(1, BigInt(1), "11111111-1111-1111-1111-111111111111", Map.empty)
  private val journal: MemoryJournal = MemoryJournal(1, None, None, Map.empty, "curated", first)

  def spec: Spec[TestEnvironment & Scope, Any] = suite("MemoryJournalSpec")(
    test("round trips absent and present empty curated state without conflation") {
      for
        absent     <- MemoryJournal.decode(journal.toJson)
        empty      <- MemoryJournal.decode(journal.copy(beforeCurated = Some("")).toJson)
        checkpoint <- MemoryJournal.checkpoint(first.toJson)
      yield assertTrue(absent == journal, empty.beforeCurated.contains(""), checkpoint == first)
    },
    test("rejects duplicate fields, trailing input, unknown fields and string numbers") {
      val inputs = Chunk(
        journal.toJson.dropRight(1) + ",\"version\":1}",
        journal.toJson + " {}",
        journal.toJson.dropRight(1) + ",\"destination\":\"../MEMORY.md\"}",
        journal.toJson.replace("\"version\":1", "\"version\":\"1\""),
        journal.toJson.replace("\"generation\":1", "\"generation\":\"1\""),
        journal.toJson.replace("\"generation\":1", "\"generation\":1.5")
      )
      ZIO
        .foreach(inputs)(MemoryJournal.decode(_).either)
        .map(results => assertTrue(results.forall(_.isLeft)))
    },
    test("rejects unsupported versions and inconsistent generations or transaction identity") {
      val inputs = Chunk(
        journal.copy(version = 2),
        journal.copy(afterCheckpoint = first.copy(version = 2)),
        journal.copy(afterCheckpoint = first.copy(generation = BigInt(0))),
        journal.copy(afterCheckpoint = first.copy(generation = BigInt(2))),
        journal.copy(
          beforeCheckpoint = Some(first),
          afterCheckpoint = first.copy(generation = BigInt(2))
        ),
        journal.copy(afterCheckpoint = first.copy(transaction = "not-an-identifier"))
      )
      ZIO
        .foreach(inputs)(value => MemoryJournal.decode(value.toJson).either)
        .map(results => assertTrue(results.forall(_.isLeft)))
    },
    test("rejects invalid dates, fingerprints and acknowledgement mismatches") {
      val fingerprints = Chunk(
        Map("../file"    -> ("a" * 64)),
        Map("2026-02-30" -> ("a" * 64)),
        Map("2026-09-29" -> "short"),
        Map("2026-09-29" -> ("A" * 64))
      )
      for
        rejected <- ZIO.foreach(fingerprints)(values =>
          MemoryJournal
            .decode(
              journal
                .copy(daily = values, afterCheckpoint = first.copy(acknowledged = values))
                .toJson
            )
            .either
        )
        mismatch <- MemoryJournal
          .decode(journal.copy(daily = Map("2026-09-29" -> ("a" * 64))).toJson)
          .either
      yield assertTrue(rejected.forall(_.isLeft), mismatch.isLeft)
    },
    test("checkpoint input is strict and legacy timestamps are rejected") {
      val inputs = Chunk(
        first.toJson + " garbage",
        first.toJson.dropRight(1) + ",\"version\":1}",
        "2026-09-29T10:00:00Z",
        first.copy(generation = BigInt(-1)).toJson
      )
      ZIO
        .foreach(inputs)(MemoryJournal.checkpoint(_).either)
        .map(results => assertTrue(results.forall(_.isLeft)))
    },
    test("daily fingerprints use canonical dates and UTF-8 SHA-256") {
      MemoryJournal
        .fingerprints(Map(LocalDate.of(2026, 9, 29) -> "abc"))
        .map(values =>
          assertTrue(
            values == Map(
              "2026-09-29" -> "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
            )
          )
        )
    }
  )
