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
package com.weisgnosis.agents.core.model

import com.weisgnosis.agents.core.error.AgentError
import zio.Chunk
import zio.json.*

/**
 * Represents token and resource usage metrics for model interactions.
 *
 * Corresponds to `io.agentscope.core.model.ChatUsage` in AgentScope Java.
 */
final case class ChatUsage(
    inputTokens: Int = 0,
    outputTokens: Int = 0,
    cachedTokens: Int = 0,
    cacheCreationTokens: Int = 0,
    reasoningTokens: Int = 0,
    toolUsePromptTokens: Int = 0,
    time: Double = 0.0
) derives JsonCodec:
  /** Existing wire counters are bounded Int values; invalid telemetry is never saturated. */
  def validated: Either[AgentError.InvalidMessage, ChatUsage] =
    val counters = Chunk(
      inputTokens,
      outputTokens,
      cachedTokens,
      cacheCreationTokens,
      reasoningTokens,
      toolUsePromptTokens
    )
    Either.cond(
      counters.forall(_ >= 0) && inputTokens.toLong + outputTokens.toLong <= Int.MaxValue &&
        time.isFinite && time >= 0.0,
      this,
      ChatUsage.invalid
    )

  def totalTokens: Either[AgentError.InvalidMessage, Int] =
    validated.map(value => (value.inputTokens.toLong + value.outputTokens.toLong).toInt)

  def combine(other: ChatUsage): Either[AgentError.InvalidMessage, ChatUsage] =
    for
      _          <- validated
      _          <- other.validated
      input      <- ChatUsage.add(inputTokens, other.inputTokens)
      output     <- ChatUsage.add(outputTokens, other.outputTokens)
      cached     <- ChatUsage.add(cachedTokens, other.cachedTokens)
      creation   <- ChatUsage.add(cacheCreationTokens, other.cacheCreationTokens)
      reasoning  <- ChatUsage.add(reasoningTokens, other.reasoningTokens)
      toolPrompt <- ChatUsage.add(toolUsePromptTokens, other.toolUsePromptTokens)
      combined <- ChatUsage(
        input,
        output,
        cached,
        creation,
        reasoning,
        toolPrompt,
        time + other.time
      ).validated
    yield combined

object ChatUsage:
  private val invalid: AgentError.InvalidMessage =
    AgentError.InvalidMessage("Invalid or unrepresentable model usage")

  private def add(left: Int, right: Int): Either[AgentError.InvalidMessage, Int] =
    val sum = left.toLong + right.toLong
    Either.cond(sum <= Int.MaxValue, sum.toInt, invalid)
