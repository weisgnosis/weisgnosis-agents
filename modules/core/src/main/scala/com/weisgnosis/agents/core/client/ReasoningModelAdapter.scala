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

/**
 * Adapter for extended reasoning models (e.g. OpenAI o-series, DeepSeek-R1, QwQ)
 * with delta parsing and context hygiene (pruning obsolete reasoning from dialogue history).
 */
object ReasoningModelAdapter:

  /**
   * Parses an OpenAI-compatible SSE JSON chunk into typed LlmChunk deltas.
   * Handles `reasoning_content`, `thinking`, `content`, `tool_calls`, `usage`, and `finish_reason`.
   */
  def parseChunk(jsonStr: String): Chunk[LlmChunk] =
    jsonStr.fromJson[Json] match
      case Left(_) => Chunk.empty
      case Right(json) =>
        val obj     = json.asObject.getOrElse(Json.Obj.empty)
        val choices = obj.get("choices").flatMap(_.asArray).getOrElse(Chunk.empty)

        val choiceDeltas: Chunk[LlmChunk] = choices.flatMap { choice =>
          val choiceObj = choice.asObject.getOrElse(Json.Obj.empty)
          val deltaObj  = choiceObj.get("delta").flatMap(_.asObject).getOrElse(Json.Obj.empty)

          val reasoningChunk = deltaObj
            .get("reasoning_content")
            .orElse(deltaObj.get("thinking"))
            .flatMap(_.asString)
            .map(rc => LlmChunk.ThinkingDelta(rc))

          val textChunk = deltaObj
            .get("content")
            .flatMap(_.asString)
            .filter(_.nonEmpty)
            .map(t => LlmChunk.TextDelta(t))

          val toolChunks =
            deltaObj.get("tool_calls").flatMap(_.asArray).getOrElse(Chunk.empty).flatMap { tc =>
              val tcObj     = tc.asObject.getOrElse(Json.Obj.empty)
              val index     = tcObj.get("index").flatMap(_.as[Int].toOption).getOrElse(0)
              val id        = tcObj.get("id").flatMap(_.asString)
              val fnObj     = tcObj.get("function").flatMap(_.asObject).getOrElse(Json.Obj.empty)
              val name      = fnObj.get("name").flatMap(_.asString)
              val argsDelta = fnObj.get("arguments").flatMap(_.asString).getOrElse("")
              Some(LlmChunk.ToolCallChunk(index, id, name, argsDelta))
            }

          val finishReasonChunk =
            choiceObj.get("finish_reason").flatMap(_.asString).map(LlmChunk.FinishReason.apply)

          Chunk.fromIterable(reasoningChunk) ++
            Chunk.fromIterable(textChunk) ++
            toolChunks ++
            Chunk.fromIterable(finishReasonChunk)
        }

        val usageDelta: Option[LlmChunk] = obj.get("usage").flatMap(_.asObject).map { u =>
          val inputTokens  = u.get("prompt_tokens").flatMap(_.as[Int].toOption).getOrElse(0)
          val outputTokens = u.get("completion_tokens").flatMap(_.as[Int].toOption).getOrElse(0)
          val promptDetails =
            u.get("prompt_tokens_details").flatMap(_.asObject).getOrElse(Json.Obj.empty)
          val cachedTokens =
            promptDetails.get("cached_tokens").flatMap(_.as[Int].toOption).getOrElse(0)
          val compDetails =
            u.get("completion_tokens_details").flatMap(_.asObject).getOrElse(Json.Obj.empty)
          val reasoningTokens =
            compDetails.get("reasoning_tokens").flatMap(_.as[Int].toOption).getOrElse(0)

          LlmChunk.UsageChunk(
            ChatUsage(
              inputTokens = inputTokens,
              outputTokens = outputTokens,
              cachedTokens = cachedTokens,
              reasoningTokens = reasoningTokens
            )
          )
        }

        choiceDeltas ++ Chunk.fromIterable(usageDelta)

  /**
   * Context Hygiene: Prunes obsolete reasoning tokens from older dialogue rounds.
   * Only preserves reasoning content in the most recent assistant message (e.g. immediately
   * preceding a tool execution turn).
   */
  def pruneObsoleteReasoning(history: Chunk[Msg]): Chunk[Msg] =
    if history.isEmpty then history
    else
      val lastAssistantIdx = history.lastIndexWhere(_.role == Role.Assistant)
      history.zipWithIndex.map { case (msg, idx) =>
        if msg.role == Role.Assistant && idx < lastAssistantIdx then
          val prunedContent = msg.content.filterNot(_.isInstanceOf[ContentBlock.Thinking])
          msg.copy(content = prunedContent)
        else msg
      }
