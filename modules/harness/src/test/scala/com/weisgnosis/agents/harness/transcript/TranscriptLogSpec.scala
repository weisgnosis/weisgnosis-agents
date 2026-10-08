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
    test(
      "recovery documents retain strict branches and reject ambiguous identities, wire shapes and bounds"
    ) {
      val remote = entry.copy(id = new UUID(0L, 2L), key = Some(TranscriptKey.Message("remote")))
      val valid = for
        local       <- TranscriptState.restore(Chunk(entry))
        remoteState <- TranscriptState.restore(Chunk(remote))
        next        <- TranscriptLog.reconcile(TranscriptLog.Document.Linear(local), remoteState)
        encoded     <- TranscriptLog.encodeDocument(next)
        decoded     <- TranscriptLog.decodeDocument(encoded)
      yield (next, encoded, decoded)
      val invalidWire = valid.toOption.toList.flatMap: (_, encoded, _) =>
        List(
          encoded.replace("\"version\":2", "\"version\":2,\"version\":2"),
          encoded.replace("\"version\":2", "\"version\":\"2\""),
          encoded.replace("\"version\":2", "\"version\":3"),
          encoded.replace("\"version\":2", "\"version\":2,\"unknown\":true"),
          encoded + "{}"
        )
      val invalid =
        for
          local <- TranscriptState.restore(Chunk(entry))
          branches = Chunk
            .fromIterable(1 to 17)
            .map(index =>
              Chunk(
                entry.copy(
                  id = new UUID(0L, index.toLong + 10L),
                  key = Some(TranscriptKey.Message(s"branch:$index"))
                )
              )
            )
          oversized = remote.copy(payload =
            TranscriptPayload.Message(Role.User, "x" * 1048576, Chunk(TranscriptBlockType.Text))
          )
          tooManyEntries = Chunk
            .fromIterable(0 to 4096)
            .map(index =>
              entry.copy(
                id = new UUID(1L, index.toLong),
                parentId = if index == 0 then None else Some(new UUID(1L, index.toLong - 1L)),
                key = Some(TranscriptKey.Message(s"entry:$index"))
              )
            )
        yield Chunk(
          TranscriptLog.Document.Recovered(local, Chunk.empty),
          TranscriptLog.Document.Recovered(local, branches),
          TranscriptLog.Document
            .Recovered(local, Chunk(Chunk(entry.copy(recordedAt = Instant.EPOCH.plusSeconds(1L))))),
          TranscriptLog.Document.Recovered(local, Chunk(Chunk(oversized))),
          TranscriptLog.Document.Recovered(local, Chunk(tooManyEntries)),
          TranscriptLog.Document
            .Recovered(local, Chunk(Chunk(remote.copy(parentId = Some(entry.id))))),
          TranscriptLog.Document.Recovered(
            local,
            Chunk(
              Chunk(
                remote.copy(payload =
                  TranscriptPayload.Message(Role.User, "\uD800", Chunk(TranscriptBlockType.Text))
                )
              )
            )
          )
        )
      assertTrue(
        valid.exists((before, encoded, after) =>
          before == after && after.entries == Chunk(remote, entry) &&
            after.state.entries == Chunk(entry) && TranscriptLog.decode(encoded).isLeft
        ),
        invalidWire.size == 5 && invalidWire.forall(TranscriptLog.decodeDocument(_).isLeft),
        invalid.exists(_.forall(TranscriptLog.encodeDocument(_).isLeft))
      )
    },
    test("round trips escaped newlines and Unicode with optional LF or CRLF ending") {
      assertTrue(
        Chunk(row, row + "\n", row + "\r\n").forall(value =>
          TranscriptLog.decode(value).map(_.entries) == Right(Chunk(entry))
        ),
        TranscriptLog.decode("").map(_.entries) == Right(Chunk.empty)
      )
    },
    test("encoder emits one physical line per entry and round trips its own state") {
      val encoded = TranscriptState.restore(Chunk(entry)).flatMap(TranscriptLog.encode)
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
