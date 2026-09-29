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
import com.weisgnosis.agents.core.json.JsonBoundary
import java.time.Instant
import java.util.UUID
import zio.Chunk

/** Shared pure authority for live appends and complete persisted-chain validation. */
final private[transcript] case class TranscriptState private (
    entries: Chunk[TranscriptEntry],
    keys: Set[TranscriptKey],
    ids: Set[UUID]
):
  def append(
      candidates: Chunk[(TranscriptDraft, UUID)],
      instant: Instant
  ): Either[AgentError, TranscriptState] =
    candidates.foldLeft[Either[AgentError, TranscriptState]](Right(this)):
      case (result, (draft, id)) =>
        for
          state <- result
          _     <- TranscriptState.validateDraft(draft)
          next <-
            if draft.key.exists(state.keys.contains) then Right(state)
            else if state.ids.contains(id) then
              TranscriptState.invalid("Transcript entry identity collision")
            else
              Right(
                state.accept(
                  TranscriptEntry(
                    id,
                    state.entries.lastOption.map(_.id),
                    instant,
                    draft.key,
                    draft.payload
                  )
                )
              )
        yield next

  private def accept(entry: TranscriptEntry): TranscriptState =
    TranscriptState(entries :+ entry, keys ++ entry.key, ids + entry.id)

private[transcript] object TranscriptState:
  val empty: TranscriptState = TranscriptState(Chunk.empty, Set.empty, Set.empty)

  def restore(entries: Chunk[TranscriptEntry]): Either[AgentError, TranscriptState] =
    entries.foldLeft[Either[AgentError, TranscriptState]](Right(empty)):
      case (result, entry) =>
        for
          state <- result
          _     <- validateDraft(TranscriptDraft(entry.key, entry.payload))
          _ <- Either.cond(
            !state.ids.contains(entry.id) && !entry.key.exists(state.keys.contains) &&
              entry.parentId == state.entries.lastOption.map(_.id),
            (),
            AgentError.MemoryStorageError(
              "Invalid transcript identity, stable key, or parent chain"
            )
          )
        yield state.accept(entry)

  private def validateDraft(draft: TranscriptDraft): Either[AgentError, Unit] =
    val validKey = draft.key.forall:
      case TranscriptKey.Message(messageId) =>
        !messageId.isBlank && (draft.payload match
          case _: TranscriptPayload.Message => true
          case _                            => false)
      case TranscriptKey.ToolUse(messageId, occurrence) =>
        !messageId.isBlank && occurrence >= 0 && (draft.payload match
          case _: TranscriptPayload.ToolUse => true
          case _                            => false)
      case TranscriptKey.ToolResult(messageId, occurrence) =>
        !messageId.isBlank && occurrence >= 0 && (draft.payload match
          case _: TranscriptPayload.ToolResult => true
          case _                               => false)
    for
      _ <- Either.cond(validKey, (), AgentError.MemoryStorageError("Invalid transcript stable key"))
      _ <- draft.payload match
        case _: TranscriptPayload.Message => Right(())
        case TranscriptPayload.ToolUse(_, _, input) =>
          input match
            case TranscriptPreview.Complete(value) =>
              JsonBoundary
                .validate(value)
                .left
                .map(AgentError.MemoryStorageError.apply)
                .map(_ => ())
            case TranscriptPreview.Truncated(prefix, original) => validPreview(prefix, original)
        case TranscriptPayload.ToolResult(_, _, output, _, _) =>
          output match
            case TranscriptPreview.Complete(_)                 => Right(())
            case TranscriptPreview.Truncated(prefix, original) => validPreview(prefix, original)
    yield ()

  private def validPreview(prefix: String, original: Int): Either[AgentError, Unit] =
    Either.cond(
      original > prefix.length,
      (),
      AgentError.MemoryStorageError("Invalid transcript preview length")
    )

  private def invalid(message: String): Either[AgentError, Nothing] =
    Left(AgentError.MemoryStorageError(message))
