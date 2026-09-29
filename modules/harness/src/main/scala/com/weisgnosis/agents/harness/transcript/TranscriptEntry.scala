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
import zio.Chunk
import zio.json.*
import zio.json.ast.Json

/** Separate key variants avoid delimiter collisions and preserve repeated tool-call occurrences. */
enum TranscriptKey derives JsonCodec:
  case Message(messageId: String)
  case ToolUse(messageId: String, occurrence: Int)
  case ToolResult(messageId: String, occurrence: Int)

enum TranscriptPreview[+A] derives JsonCodec:
  case Complete(value: A)
  case Truncated(prefix: String, originalChars: Int)

enum TranscriptBlockType derives JsonCodec:
  case Text, Thinking, Image, Audio, Video, Data, Hint, ToolUse, ToolResult, Empty
  case Custom(kind: String)

enum TranscriptPayload derives JsonCodec:
  case Message(role: Role, text: String, blockTypes: Chunk[TranscriptBlockType])
  case ToolUse(callId: String, name: String, input: TranscriptPreview[Json])
  case ToolResult(
      callId: String,
      name: Option[String],
      output: TranscriptPreview[String],
      blockTypes: Chunk[TranscriptBlockType],
      state: ToolResultState
  )

/** A proposed observation without storage-owned identity, time, or parent linkage. */
final case class TranscriptDraft(key: Option[TranscriptKey], payload: TranscriptPayload)
    derives JsonCodec

/** Recording time belongs to the append operation, not the original conversation message. */
final case class TranscriptEntry(
    id: UUID,
    parentId: Option[UUID],
    recordedAt: Instant,
    key: Option[TranscriptKey],
    payload: TranscriptPayload
) derives JsonCodec
