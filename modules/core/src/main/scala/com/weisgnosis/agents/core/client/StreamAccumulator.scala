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
package com.weisgnosis.agents.core.client

import com.weisgnosis.agents.core.model.{ChatUsage, ContentBlock, Msg, Role}
import zio.Chunk
import zio.json.*
import zio.json.ast.Json
import java.util.UUID

/**
 * Pure functional state accumulator enforcing invariant INV-12 (Chunk Index Assembly).
 *
 * Assembles interleaved streaming deltas across parallel tool calls keyed by `streamIndex`,
 * and sanitizes partial or interrupted JSON to `{}`.
 */
final case class StreamAccumulator(
    textBuffer: String = "",
    thinkingBuffer: String = "",
    toolCalls: Map[Int, StreamAccumulator.InFlightToolCall] = Map.empty,
    usage: Option[ChatUsage] = None,
    finishReason: Option[String] = None
):

  /** Purely incorporates an incoming LlmChunk into the accumulated state. */
  def feed(chunk: LlmChunk): StreamAccumulator =
    chunk match
      case LlmChunk.TextDelta(delta, _) =>
        copy(textBuffer = textBuffer + delta)

      case LlmChunk.ThinkingDelta(delta, _, _) =>
        copy(thinkingBuffer = thinkingBuffer + delta)

      case LlmChunk.ToolCallChunk(streamIdx, callIdOpt, nameOpt, argsDelta) =>
        val existing = toolCalls.get(streamIdx)
        val updated = existing match
          case Some(tc) =>
            tc.copy(
              callId = callIdOpt.orElse(Some(tc.callId)).getOrElse(tc.callId),
              name = nameOpt.orElse(Some(tc.name)).getOrElse(tc.name),
              argsBuffer = tc.argsBuffer + argsDelta
            )
          case None =>
            StreamAccumulator.InFlightToolCall(
              streamIndex = streamIdx,
              callId =
                callIdOpt.getOrElse(s"call_${UUID.randomUUID().toString.replace("-", "").take(8)}"),
              name = nameOpt.getOrElse(""),
              argsBuffer = argsDelta
            )
        copy(toolCalls = toolCalls.updated(streamIdx, updated))

      case LlmChunk.UsageChunk(u) =>
        copy(usage = Some(u))

      case LlmChunk.FinishReason(reason) =>
        copy(finishReason = Some(reason))

  /** Materializes the accumulated deltas into a typed TurnOutput. */
  def toTurnOutput: TurnOutput =
    val thinkingBlockOpt =
      if thinkingBuffer.nonEmpty then Some(ContentBlock.Thinking(thinkingBuffer))
      else None

    val textBlockOpt =
      if textBuffer.nonEmpty then Some(ContentBlock.Text(textBuffer))
      else None

    val sortedTools = toolCalls.toSeq.sortBy(_._1).map(_._2)
    val toolBlocks: Chunk[ContentBlock.ToolUse] = Chunk.fromIterable(
      sortedTools.map { tc =>
        val parsedJson =
          if tc.argsBuffer.trim.isEmpty then Json.Obj()
          else tc.argsBuffer.fromJson[Json].getOrElse(Json.Obj())
        ContentBlock.ToolUse(
          id = tc.callId,
          name = tc.name,
          input = parsedJson
        )
      }
    )

    val allBlocks: Chunk[ContentBlock] =
      Chunk.fromIterable(thinkingBlockOpt) ++
        Chunk.fromIterable(textBlockOpt) ++
        toolBlocks

    val msg = Msg(
      role = Role.Assistant,
      name = Some("assistant"),
      content = allBlocks,
      usage = usage
    )

    if toolBlocks.nonEmpty then TurnOutput.ToolCalls(msg)
    else if textBuffer.nonEmpty then TurnOutput.FinalText(msg)
    else if thinkingBuffer.nonEmpty then TurnOutput.ThinkingOnly(msg)
    else TurnOutput.FinalText(msg)

  /** Materializes state when the turn was aborted or interrupted by user/system. */
  def toInterrupted: TurnOutput.Interrupted =
    toTurnOutput match
      case TurnOutput.FinalText(m)    => TurnOutput.Interrupted(m)
      case TurnOutput.ToolCalls(m)    => TurnOutput.Interrupted(m)
      case TurnOutput.ThinkingOnly(m) => TurnOutput.Interrupted(m)
      case TurnOutput.Interrupted(m)  => TurnOutput.Interrupted(m)

object StreamAccumulator:

  final case class InFlightToolCall(
      streamIndex: Int,
      callId: String,
      name: String,
      argsBuffer: String
  )

  val empty: StreamAccumulator = StreamAccumulator()
