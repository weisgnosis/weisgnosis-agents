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

import com.weisgnosis.agents.core.client.{LlmClient, LlmConfig, TurnOutput}
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.event.{EventPublisher, ModelStreamContext}
import com.weisgnosis.agents.core.model.Msg
import zio.*

/** Produces candidate memory text; the caller owns the bounded extraction scope. */
trait MemoryExtractor:
  def extract(input: Chunk[Msg]): ZIO[Scope, AgentError, String]

object MemoryExtractor:
  def apply(run: Chunk[Msg] => ZIO[Scope, AgentError, String]): MemoryExtractor =
    new MemoryExtractor:
      def extract(input: Chunk[Msg]): ZIO[Scope, AgentError, String] =
        ZIO.suspendSucceed(run(input))

  /** A standalone text request without tools, structured response formatting, or agent mutation. */
  def fromClient(client: LlmClient, config: LlmConfig): MemoryExtractor = MemoryExtractor: input =>
    for
      replyId   <- Random.nextUUID.map(_.toString)
      requestId <- Random.nextUUID.map(_.toString)
      output <- client.streamTurn(
        input,
        Chunk.empty,
        config.copy(responseFormat = None),
        EventPublisher.discard,
        ModelStreamContext(replyId, requestId)
      )
      text <- output match
        case TurnOutput.Interrupted(_) => ZIO.interrupt
        case TurnOutput.Truncated(_) =>
          ZIO.fail(AgentError.ExecutionStopped("Memory extraction token budget exhausted"))
        case TurnOutput.ToolCalls(_) =>
          ZIO.fail(AgentError.InvalidMessage("Memory extraction must not request tools"))
        case value if value.message.toolUses.nonEmpty || value.message.toolResults.nonEmpty =>
          ZIO.fail(AgentError.InvalidMessage("Memory extraction must contain no tool observations"))
        case value => ZIO.succeed(value.message.text)
    yield text
