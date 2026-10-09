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

import com.weisgnosis.agents.core.model.{ContentBlock, Msg}
import zio.json.ast.Json

/**
 * Outcome of accumulating streaming deltas over an LLM reasoning turn.
 */
sealed trait TurnOutput:
  def message: Msg

object TurnOutput:

  /** The model produced a complete answer with text content and zero tool calls. */
  final case class FinalText(message: Msg) extends TurnOutput

  /** The model produced one or more tool use invocations. */
  final case class ToolCalls(message: Msg) extends TurnOutput

  /** The model produced reasoning tokens but empty content and zero tool calls (triggers INV-3 loopback). */
  final case class ThinkingOnly(message: Msg) extends TurnOutput

  /** The turn was interrupted or canceled while in-flight. */
  final case class Interrupted(message: Msg) extends TurnOutput

  /** Explicit provider token exhaustion. Visible text is partial, never a completed answer. */
  final case class Truncated(message: Msg) extends TurnOutput

  /** Incomplete calls, reasoning signatures and provider replay data cannot grant execution. */
  private[client] def tokenExhausted(message: Msg): Truncated =
    Truncated(
      Msg.assistant(
        message.content.collect { case text: ContentBlock.Text => text },
        metadata = Map("agentscope_stop_reason" -> Json.Str("token_budget_exhausted")),
        usage = message.usage
      )
    )
