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

import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.event.AgentEvent
import com.weisgnosis.agents.core.model.Msg
import com.weisgnosis.agents.core.tool.ToolDef
import zio.*
import zio.json.*

final case class LlmConfig(
  model: String,
  endpoint: String = "https://api.openai.com/v1/chat/completions",
  apiKey: Option[String] = None,
  temperature: Double = 0.7,
  maxTokens: Option[Int] = None
)

object LlmConfig:
  given JsonCodec[LlmConfig] = DeriveJsonCodec.gen

/** Streaming LLM client interface for invoking models and streaming token deltas.
  */
trait LlmClient:
  def streamTurn(
    history: Chunk[Msg],
    tools: Chunk[ToolDef],
    config: LlmConfig,
    eventHub: Hub[AgentEvent]
  ): ZIO[Scope, AgentError, TurnOutput]

object LlmClient:

  /** Creates a mock client returning a sequence of fixed responses or deltas. */
  def mock(outputs: Chunk[TurnOutput]): UIO[LlmClient] =
    Ref.make(outputs).map { queueRef =>
      new LlmClient:
        def streamTurn(
          history: Chunk[Msg],
          tools: Chunk[ToolDef],
          config: LlmConfig,
          eventHub: Hub[AgentEvent]
        ): ZIO[Scope, AgentError, TurnOutput] =
          queueRef.modify { chunk =>
            if chunk.isEmpty then
              (
                TurnOutput.FinalText(Msg.assistant("Mock: No more responses")),
                Chunk.empty
              )
            else
              (chunk.head, chunk.tail)
          }
    }
