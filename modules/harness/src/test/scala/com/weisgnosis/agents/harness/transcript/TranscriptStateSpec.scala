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
import zio.json.ast.Json
import zio.test.*

object TranscriptStateSpec extends ZIOSpecDefault:
  private val identifier: UUID           = UUID.fromString("11111111-1111-1111-1111-111111111111")
  private val message: TranscriptPayload = TranscriptPayload.Message(Role.User, "text", Chunk.empty)

  def spec: Spec[TestEnvironment & Scope, Any] = suite("TranscriptStateSpec")(
    test("rejects malformed stable keys and mismatched payload kinds before commit") {
      val invalid = Chunk(
        TranscriptDraft(Some(TranscriptKey.Message(" ")), message),
        TranscriptDraft(Some(TranscriptKey.ToolUse("m", 0)), message),
        TranscriptDraft(
          Some(TranscriptKey.ToolUse("m", -1)),
          TranscriptPayload.ToolUse("t", "tool", TranscriptPreview.Complete(Json.Obj()))
        )
      )
      assertTrue(
        invalid.forall(draft =>
          TranscriptState.empty.append(Chunk(draft -> identifier), Instant.EPOCH).isLeft
        )
      )
    },
    test("validates public preview lengths and rejects duplicate-key complete JSON") {
      val invalid = Chunk(
        TranscriptPreview.Truncated("abc", 3),
        TranscriptPreview.Truncated("", -1),
        TranscriptPreview.Complete(Json.Obj("x" -> Json.Num(1), "x" -> Json.Num(2)))
      ).map(input => TranscriptDraft(None, TranscriptPayload.ToolUse("t", "tool", input)))
      val valid = TranscriptDraft(
        None,
        TranscriptPayload.ToolUse("t", "tool", TranscriptPreview.Truncated("custom", 7))
      )
      assertTrue(
        invalid.forall(draft =>
          TranscriptState.empty.append(Chunk(draft -> identifier), Instant.EPOCH).isLeft
        ),
        TranscriptState.empty.append(Chunk(valid -> identifier), Instant.EPOCH).isRight
      )
    },
    test("restore rejects duplicate identities, duplicate keys and broken parents") {
      val first = TranscriptEntry(
        identifier,
        None,
        Instant.EPOCH,
        Some(TranscriptKey.Message("one")),
        message
      )
      val nextId        = UUID.fromString("22222222-2222-2222-2222-222222222222")
      val duplicateId   = first.copy(parentId = Some(identifier), key = None)
      val duplicateKey  = first.copy(id = nextId, parentId = Some(identifier))
      val missingParent = first.copy(id = nextId, key = None)
      assertTrue(
        Chunk(duplicateId, duplicateKey, missingParent).forall(next =>
          TranscriptState.restore(Chunk(first, next)).isLeft
        )
      )
    },
    test("restore preserves append order without demanding monotonic timestamps") {
      val first  = TranscriptEntry(identifier, None, Instant.ofEpochSecond(10), None, message)
      val nextId = UUID.fromString("22222222-2222-2222-2222-222222222222")
      val second = TranscriptEntry(nextId, Some(identifier), Instant.EPOCH, None, message)
      assertTrue(
        TranscriptState.restore(Chunk(first, second)).map(_.entries) == Right(Chunk(first, second))
      )
    }
  )
