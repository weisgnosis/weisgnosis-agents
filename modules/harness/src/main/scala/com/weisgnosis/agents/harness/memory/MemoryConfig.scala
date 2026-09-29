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
package com.weisgnosis.agents.harness.memory

import com.weisgnosis.agents.harness.transcript.TranscriptRetentionPolicy
import zio.json.JsonCodec

/** Immutable policy values; hosts explicitly share gate, queue, repository and extractor ownership. */
final case class MemoryConfig(
    flushPrompt: Option[String] = None,
    consolidationPrompt: Option[ConsolidationPrompt] = None,
    consolidationBudget: MemoryTokenBudget = MemoryTokenBudget.Default,
    triggers: MemoryTriggerPolicy = MemoryTriggerPolicy(),
    dailyRetention: MemoryRetentionPolicy = MemoryRetentionPolicy.DailyDefault,
    transcriptRetention: TranscriptRetentionPolicy = TranscriptRetentionPolicy.Default
) derives JsonCodec:
  def resolvedFlushPrompt: String = flushPrompt.getOrElse(MemoryFlushManager.DefaultPrompt)
  def resolvedConsolidationPrompt: String =
    consolidationPrompt.fold(ConsolidationPrompt.default(consolidationBudget))(
      _.render(consolidationBudget)
    )

object MemoryConfig:
  val Default: MemoryConfig = MemoryConfig()
