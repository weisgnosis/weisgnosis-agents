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

import com.weisgnosis.agents.core.model.{Role, ToolResultState}
import java.time.Instant
import java.util.UUID
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

object TranscriptEntrySpec extends ZIOSpecDefault:
  def spec: Spec[TestEnvironment & Scope, Any] = suite("TranscriptEntrySpec")(
    test("all payloads, preview variants, keys and parent links round trip") {
      val id     = UUID.fromString("11111111-1111-1111-1111-111111111111")
      val parent = UUID.fromString("22222222-2222-2222-2222-222222222222")
      val payloads = Chunk(
        TranscriptPayload.Message(
          Role.User,
          "text",
          Chunk(TranscriptBlockType.Empty, TranscriptBlockType.Custom("widget"))
        ),
        TranscriptPayload
          .ToolUse("call", "tool", TranscriptPreview.Complete(Json.Obj("x" -> Json.Num(1)))),
        TranscriptPayload.ToolUse("call", "tool", TranscriptPreview.Truncated("prefix", 900)),
        TranscriptPayload.ToolResult(
          "call",
          None,
          TranscriptPreview.Complete("done"),
          Chunk(TranscriptBlockType.Text),
          ToolResultState.Success
        ),
        TranscriptPayload.ToolResult(
          "call",
          Some("tool"),
          TranscriptPreview.Truncated("prefix", 2000),
          Chunk(TranscriptBlockType.Image),
          ToolResultState.Error
        )
      )
      val keys = Chunk(
        None,
        Some(TranscriptKey.Message("message")),
        Some(TranscriptKey.ToolUse("message", 0)),
        Some(TranscriptKey.ToolResult("message", 1))
      )
      val entries = for
        payload <- payloads
        key     <- keys
      yield TranscriptEntry(id, Some(parent), Instant.EPOCH, key, payload)
      assertTrue(entries.forall(entry => entry.toJson.fromJson[TranscriptEntry] == Right(entry)))
    },
    test("structured keys distinguish delimiters and repeated occurrences") {
      val keys = Set[TranscriptKey](
        TranscriptKey.Message("a:use:t"),
        TranscriptKey.ToolUse("a", 0),
        TranscriptKey.ToolUse("a", 1),
        TranscriptKey.ToolResult("a", 0)
      )
      assertTrue(keys.size == 4, keys.map(_.toJson).size == 4)
    }
  )
