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
):
  def totalTokens: Int = inputTokens + outputTokens

object ChatUsage:
  given JsonCodec[ChatUsage] = DeriveJsonCodec.gen
