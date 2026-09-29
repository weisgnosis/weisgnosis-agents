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

import com.weisgnosis.agents.core.model.Role
import java.time.Instant
import java.util.UUID
import zio.*
import zio.json.*
import zio.test.*

object TranscriptLogSpec extends ZIOSpecDefault:
  private val entry: TranscriptEntry = TranscriptEntry(
    UUID.fromString("11111111-1111-1111-1111-111111111111"),
    None,
    Instant.EPOCH,
    Some(TranscriptKey.Message("one")),
    TranscriptPayload.Message(
      Role.User,
      "line one\nline two\r\nUnicode \u20ac",
      Chunk(TranscriptBlockType.Text)
    )
  )
  private val row: String = s"""{"version":1,"entry":${entry.toJson}}"""

  def spec: Spec[TestEnvironment & Scope, Any] = suite("TranscriptLogSpec")(
    test("round trips escaped newlines and Unicode with optional LF or CRLF ending") {
      assertTrue(
        Chunk(row, row + "\n", row + "\r\n").forall(value =>
          TranscriptLog.decode(value).map(_.entries) == Right(Chunk(entry))
        ),
        TranscriptLog.decode("").map(_.entries) == Right(Chunk.empty)
      )
    },
    test("encoder emits one physical line per entry and round trips its own state") {
      val encoded = TranscriptState.restore(Chunk(entry)).map(TranscriptLog.encode)
      assertTrue(
        encoded == Right(row + "\n"),
        encoded.flatMap(TranscriptLog.decode).map(_.entries) == Right(Chunk(entry))
      )
    },
    test("rejects empty rows, truncated rows and trailing documents without partial recovery") {
      val invalid =
        Chunk("\n", " " + "\n", row + "\n\n", "\n" + row, row + "\n{broken", row + " {}")
      assertTrue(invalid.forall(value => TranscriptLog.decode(value).isLeft))
    },
    test("rejects unknown versions, unknown fields, numeric coercion and duplicate fields") {
      val invalid = Chunk(
        row.replace("\"version\":1", "\"version\":2"),
        row.replace("\"version\":1", "\"version\":\"1\""),
        row.replace("\"version\":1", "\"version\":1,\"version\":1"),
        row.dropRight(1) + ",\"unknown\":true}",
        row.replace("\"recordedAt\"", "\"unexpected\":true,\"recordedAt\"")
      )
      assertTrue(invalid.forall(value => TranscriptLog.decode(value).isLeft))
    },
    test("rejects explicitly null optional fields instead of accepting an alternate disk format") {
      val alternate = row.replace("\"recordedAt\"", "\"parentId\":null,\"recordedAt\"")
      assertTrue(TranscriptLog.decode(alternate).isLeft)
    },
    test("rejects duplicate identities and malformed chain contents across valid JSON rows") {
      assertTrue(TranscriptLog.decode(row + "\n" + row).isLeft)
    }
  )
