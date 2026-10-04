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
import com.weisgnosis.agents.core.event.{AgentEvent, ModelStreamContext}
import com.weisgnosis.agents.core.model.Msg
import com.weisgnosis.agents.core.tool.ToolDef
import zio.*
import zio.http.Client

/** Live text/function client; the runtime supplies request identity and model boundaries. */
final class ChatCompletionsClient private (
    transport: ChatCompletionsTransport,
    textTransform: ModelTextTransform
) extends LlmClient:
  def streamTurn(
      history: Chunk[Msg],
      tools: Chunk[ToolDef],
      config: LlmConfig,
      eventHub: Hub[AgentEvent],
      context: ModelStreamContext
  ): ZIO[Scope, AgentError, TurnOutput] =
    BeforeResponseRejection.expose(attemptTurn(history, tools, config, eventHub, context))

  override private[core] def attemptTurn(
      history: Chunk[Msg],
      tools: Chunk[ToolDef],
      config: LlmConfig,
      eventHub: Hub[AgentEvent],
      context: ModelStreamContext
  ): ZIO[Scope, AgentError | BeforeResponseRejection, TurnOutput] =
    for
      publisher <- ModelStreamPublisher.make(eventHub, context)
      output <- transport.observeAttempt(history, tools, config)(
        publisher.frame,
        fragment => textTransform.transform(context, fragment)
      )
      _ <- publisher.finish(output)
    yield output

object ChatCompletionsClient:
  def make(
      http: Client,
      limits: CompletionTransportLimits = CompletionTransportLimits(),
      reasoningReplay: ReasoningReplay = ReasoningReplay.Omit,
      textTransform: ModelTextTransform = ModelTextTransform.identity,
      toolSignatureFormat: ToolSignatureFormat = ToolSignatureFormat.Unsupported,
      retryPolicy: CompletionRetryPolicy = CompletionRetryPolicy.disabled
  ): IO[AgentError, ChatCompletionsClient] =
    ChatCompletionsTransport
      .make(http, limits, reasoningReplay, toolSignatureFormat, retryPolicy)
      .map(new ChatCompletionsClient(_, textTransform))
