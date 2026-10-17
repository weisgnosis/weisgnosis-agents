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

import com.weisgnosis.agents.core.agent.{ModelRequestInput, ModelRequestSettings}
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.event.{EventPublisher, ModelStreamContext}
import com.weisgnosis.agents.core.model.Msg
import com.weisgnosis.agents.core.tool.ToolDef
import zio.*

/** Request data for assertions; transport endpoints and API keys are deliberately excluded. */
final case class RecordedModelRequest(input: ModelRequestInput, context: ModelStreamContext)

/** A finite script whose admitted attempts are recorded before their scoped response runs. */
final class ScriptedLlmClient private (
    state: Ref[ScriptedLlmClient.State],
    maxRecordedRequests: Int
) extends LlmClient:
  def requests: UIO[Chunk[RecordedModelRequest]] = state.get.map(_.requests)

  def remaining: UIO[Int] = state.get.map(_.responses.size)

  /** Call after joining all model requests; an interrupted attempt still consumes its response. */
  def verifyConsumed: IO[AgentError, Unit] =
    remaining.flatMap(count =>
      ZIO
        .fail(AgentError.InvalidConfiguration(s"Script has $count unused response(s)"))
        .when(count > 0)
        .unit
    )

  def streamTurn(
      history: Chunk[Msg],
      tools: Chunk[ToolDef],
      config: LlmConfig,
      eventPublisher: EventPublisher,
      context: ModelStreamContext
  ): ZIO[Scope, AgentError, TurnOutput] =
    val request = RecordedModelRequest(
      ModelRequestInput(
        history,
        config.model,
        tools,
        ModelRequestSettings(
          temperature = config.temperature,
          maxTokens = config.maxTokens,
          topP = config.topP,
          frequencyPenalty = config.frequencyPenalty,
          presencePenalty = config.presencePenalty,
          seed = config.seed,
          stopSequences = config.stopSequences,
          reasoningEffort = config.reasoningEffort,
          thinkingBudget = config.thinkingBudget,
          temperaturePolicy = config.temperaturePolicy
        )
      ),
      context
    )
    state
      .modify { current =>
        if current.requests.size >= maxRecordedRequests then
          (
            Left(
              AgentError.InvalidConfiguration(
                s"Script request recording limit reached ($maxRecordedRequests)"
              )
            ),
            current
          )
        else
          val recorded = current.copy(requests = current.requests :+ request)
          current.responses.headOption match
            case None =>
              (
                Left(AgentError.InvalidConfiguration("Script exhausted: unexpected model request")),
                recorded
              )
            case Some(response) =>
              (Right(response), recorded.copy(responses = current.responses.drop(1)))
      }
      .flatMap(ZIO.fromEither(_).flatten)

object ScriptedLlmClient:
  final private case class State(
      responses: Chunk[ZIO[Scope, AgentError, TurnOutput]],
      requests: Chunk[RecordedModelRequest]
  )

  def make(
      outputs: Chunk[TurnOutput],
      maxRecordedRequests: Int
  ): IO[AgentError, ScriptedLlmClient] =
    makeEffectful(outputs.map(output => ZIO.succeed(output)), maxRecordedRequests)

  /** Effects retain the caller's Scope and complete failure cause; no response is replayed. */
  def makeEffectful(
      responses: Chunk[ZIO[Scope, AgentError, TurnOutput]],
      maxRecordedRequests: Int
  ): IO[AgentError, ScriptedLlmClient] =
    if maxRecordedRequests <= 0 then
      ZIO.fail(AgentError.InvalidConfiguration("Script request recording limit must be positive"))
    else Ref.make(State(responses, Chunk.empty)).map(new ScriptedLlmClient(_, maxRecordedRequests))
