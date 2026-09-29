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
import com.weisgnosis.agents.core.agent.TurnCompletionObserver
import com.weisgnosis.agents.core.model.{ContentBlock, Msg, Role}
import zio.*
import zio.json.*

enum AnonymousTranscriptPolicy derives JsonCodec:
  case RequireStableIds, RecordEachSnapshot

/** Lossy observation recording, independent of memory-extraction triggers and prompts. */
final class SessionTranscriptWriter(store: TranscriptStore):
  def appendMessages(messages: Chunk[Msg]): IO[AgentError, Chunk[TranscriptEntry]] =
    store.append(messages.flatMap(SessionTranscriptWriter.project))

  /** Install as the compactor offload callback; extraction and canonical history remain separate. */
  def offload(messages: Chunk[Msg]): IO[AgentError, Unit] = appendMessages(messages).unit

  /** Required observation of each terminal canonical snapshot; anonymous replay policy is explicit. */
  def completionObserver(policy: AnonymousTranscriptPolicy): TurnCompletionObserver =
    TurnCompletionObserver: completion =>
      val drafts = completion.history.flatMap(SessionTranscriptWriter.project)
      if policy == AnonymousTranscriptPolicy.RequireStableIds && drafts.exists(_.key.isEmpty) then
        ZIO.fail(
          AgentError.CompletionObservationError(
            "Transcript completion requires stable message identities"
          )
        )
      else
        store
          .append(drafts)
          .unit
          .mapError(error => AgentError.CompletionObservationError(error.message))

object SessionTranscriptWriter:
  val InputPreviewChars: Int  = 500
  val OutputPreviewChars: Int = 1000

  /** Projection preserves summaries and thinking; only internal user session context is excluded. */
  def project(message: Msg): Chunk[TranscriptDraft] =
    if message.role == Role.User && message.text.contains("<session_context>") then Chunk.empty
    else
      val source = message.id.filterNot(_.isBlank)
      val texts = message.content.collect:
        case ContentBlock.Text(value) if !value.isBlank        => value
        case ContentBlock.Thinking(value, _) if !value.isBlank => value
      val uses    = message.content.collect { case value: ContentBlock.ToolUse => value }
      val results = message.content.collect { case value: ContentBlock.ToolResult => value }
      val messageEntries =
        if texts.nonEmpty || (uses.isEmpty && results.isEmpty) then
          val types =
            if message.content.isEmpty then Chunk(TranscriptBlockType.Empty)
            else message.content.map(blockType)
          Chunk(
            TranscriptDraft(
              source.map(TranscriptKey.Message.apply),
              TranscriptPayload.Message(message.role, texts.mkString("\n"), types)
            )
          )
        else Chunk.empty
      val useEntries = uses.zipWithIndex.map: (call, occurrence) =>
        val input = call.input.toJson
        val preview =
          if input.length > InputPreviewChars then
            TranscriptPreview.Truncated(prefix(input, InputPreviewChars), input.length)
          else TranscriptPreview.Complete(call.input)
        TranscriptDraft(
          source.map(TranscriptKey.ToolUse(_, occurrence)),
          TranscriptPayload.ToolUse(call.id, call.name, preview)
        )
      val resultEntries = results.zipWithIndex.map: (result, occurrence) =>
        val output = result.outputText
        val preview =
          if output.length > OutputPreviewChars then
            TranscriptPreview.Truncated(prefix(output, OutputPreviewChars), output.length)
          else TranscriptPreview.Complete(output)
        TranscriptDraft(
          source.map(TranscriptKey.ToolResult(_, occurrence)),
          TranscriptPayload.ToolResult(
            result.id,
            result.name,
            preview,
            result.output.map(blockType),
            result.state
          )
        )
      messageEntries ++ useEntries ++ resultEntries

  private def prefix(text: String, limit: Int): String =
    val candidate = text.take(limit)
    if candidate.lastOption.exists(Character.isHighSurrogate) then candidate.dropRight(1)
    else candidate

  private def blockType(block: ContentBlock): TranscriptBlockType = block match
    case _: ContentBlock.Text         => TranscriptBlockType.Text
    case _: ContentBlock.Thinking     => TranscriptBlockType.Thinking
    case _: ContentBlock.Image        => TranscriptBlockType.Image
    case _: ContentBlock.Audio        => TranscriptBlockType.Audio
    case _: ContentBlock.Video        => TranscriptBlockType.Video
    case _: ContentBlock.Data         => TranscriptBlockType.Data
    case _: ContentBlock.Hint         => TranscriptBlockType.Hint
    case _: ContentBlock.ToolUse      => TranscriptBlockType.ToolUse
    case _: ContentBlock.ToolResult   => TranscriptBlockType.ToolResult
    case ContentBlock.Custom(kind, _) => TranscriptBlockType.Custom(kind)
