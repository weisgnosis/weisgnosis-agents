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

import com.weisgnosis.agents.core.model.ChatUsage
import zio.Chunk
import zio.json.*
import zio.json.ast.Json

/**
 * Sealed algebraic data type representing streaming deltas emitted by an LLM completion stream.
 */
sealed trait LlmChunk

object LlmChunk:

  final case class TextDelta(
      delta: String,
      blockIndex: Int = 0
  ) extends LlmChunk derives JsonCodec

  /**
   * Ordered checked objects, including an explicitly present empty array, with one plaintext projection.
   * Provider metadata is retained in a separate canonical namespace and is not replayed on the wire.
   */
  final case class ReasoningDetailsDelta(
      details: Chunk[ReasoningDetail],
      providerMetadata: Map[String, Json] = Map.empty
  ) extends LlmChunk derives JsonCodec

  /** Provider metadata appends array fragments and otherwise retains the latest supplied value. */
  final case class ThinkingDelta(
      delta: String,
      blockIndex: Int = 0,
      signature: Option[String] = None,
      providerMetadata: Map[String, Json] = Map.empty
  ) extends LlmChunk derives JsonCodec

  final case class ToolCallChunk(
      streamIndex: Int,
      callId: Option[String] = None,
      name: Option[String] = None,
      argumentsDelta: String = "",
      thoughtSignature: Option[String] = None
  ) extends LlmChunk derives JsonCodec

  final case class UsageChunk(
      usage: ChatUsage
  ) extends LlmChunk derives JsonCodec

  final case class FinishReason(
      reason: String
  ) extends LlmChunk derives JsonCodec
