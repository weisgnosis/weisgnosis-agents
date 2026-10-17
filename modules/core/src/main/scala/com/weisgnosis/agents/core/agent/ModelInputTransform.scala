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
package com.weisgnosis.agents.core.agent

import com.weisgnosis.agents.core.client.{
  ReasoningEffort,
  StopSequences,
  ThinkingBudget,
  TemperaturePolicy
}
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.event.ModelStreamContext
import com.weisgnosis.agents.core.model.Msg
import com.weisgnosis.agents.core.tool.ToolDef
import zio.*

/** Transient request projection data, not serialized agent state or a provider configuration. */
final case class ModelRequestSettings(
    temperature: Double,
    maxTokens: Option[Int],
    topP: Option[Double] = None,
    frequencyPenalty: Option[Double] = None,
    presencePenalty: Option[Double] = None,
    seed: Option[Long] = None,
    stopSequences: Option[StopSequences] = None,
    reasoningEffort: Option[ReasoningEffort] = None,
    thinkingBudget: Option[ThinkingBudget] = None,
    temperaturePolicy: TemperaturePolicy = TemperaturePolicy.Configured
):
  def configuredTemperature: Option[Double] = temperaturePolicy.configuredValue(temperature)

final case class ModelRequestInput(
    history: Chunk[Msg],
    model: String,
    tools: Chunk[ToolDef],
    settings: ModelRequestSettings
)

final case class ModelInputProjection(history: Chunk[Msg], settings: ModelRequestSettings)

/** Scoped request-only projection; its output never replaces canonical history or tool authority. */
trait ModelInputTransform:
  def apply(
      context: ModelStreamContext,
      canonicalHistory: Chunk[Msg]
  ): ZIO[Scope, AgentError.ModelInputError, Chunk[Msg]]

  /** Legacy implementations project history once and retain the selected target's settings. */
  def projectRequest(
      context: ModelStreamContext,
      request: ModelRequestInput
  ): ZIO[Scope, AgentError.ModelInputError, ModelInputProjection] =
    ZIO
      .suspendSucceed(apply(context, request.history))
      .map(ModelInputProjection(_, request.settings))

object ModelInputTransform:
  val identity: ModelInputTransform = ModelInputTransform((_, history) => ZIO.succeed(history))

  /** Projects inert peer observations into labelled user context without changing stored history. */
  val peerAware: ModelInputTransform =
    ModelInputTransform((_, history) => ZIO.fromEither(PeerObservation.project(history)))

  def apply(
      transform: (ModelStreamContext, Chunk[Msg]) => ZIO[Scope, AgentError.ModelInputError, Chunk[
        Msg
      ]]
  ): ModelInputTransform = new ModelInputTransform:
    def apply(
        context: ModelStreamContext,
        canonicalHistory: Chunk[Msg]
    ): ZIO[Scope, AgentError.ModelInputError, Chunk[Msg]] =
      ZIO.suspendSucceed(transform(context, canonicalHistory))

  /**
   * Adds selected-request behavior to an explicit base. Standalone apply retains the base's legacy
   * behavior; only projectRequest invokes the additional callback. Both stages share the caller's
   * Scope, and intermediate output is validated before the next callback runs.
   */
  def withRequest(base: ModelInputTransform)(
      project: (ModelStreamContext, ModelRequestInput) => ZIO[
        Scope,
        AgentError.ModelInputError,
        ModelInputProjection
      ]
  ): ModelInputTransform = new ModelInputTransform:
    def apply(
        context: ModelStreamContext,
        canonicalHistory: Chunk[Msg]
    ): ZIO[Scope, AgentError.ModelInputError, Chunk[Msg]] =
      ZIO.suspendSucceed(base(context, canonicalHistory))

    override def projectRequest(
        context: ModelStreamContext,
        request: ModelRequestInput
    ): ZIO[Scope, AgentError.ModelInputError, ModelInputProjection] =
      for
        candidate <- ZIO.suspendSucceed(base.projectRequest(context, request))
        validated <- ZIO.fromEither(validateProjection(request.history, candidate))
        result <- ZIO.suspendSucceed(
          project(context, request.copy(history = validated.history, settings = validated.settings))
        )
      yield result

  private[agent] def validateProjection(
      original: Chunk[Msg],
      candidate: ModelInputProjection
  ): Either[AgentError.ModelInputError, ModelInputProjection] =
    for
      _ <- validate(original, candidate.history)
      _ <- Either.cond(
        candidate.settings.configuredTemperature.forall(value =>
          value.isFinite && value >= 0 && value <= 2
        ),
        (),
        AgentError.ModelInputError("Projected temperature must be finite and between 0 and 2")
      )
      _ <- Either.cond(
        candidate.settings.topP.forall(value => value.isFinite && value >= 0 && value <= 1),
        (),
        AgentError.ModelInputError("Projected topP must be finite and between 0 and 1")
      )
      _ <- Either.cond(
        candidate.settings.maxTokens.forall(_ > 0),
        (),
        AgentError.ModelInputError("Projected maximum tokens must be positive")
      )
      _ <- Either.cond(
        candidate.settings.frequencyPenalty.forall(value =>
          value.isFinite && value >= -2 && value <= 2
        ) &&
          candidate.settings.presencePenalty.forall(value =>
            value.isFinite && value >= -2 && value <= 2
          ),
        (),
        AgentError.ModelInputError("Projected penalties must be finite and between -2 and 2")
      )
    yield candidate

  private[agent] def validate(
      original: Chunk[Msg],
      candidate: Chunk[Msg]
  ): Either[AgentError.ModelInputError, Chunk[Msg]] =
    HistoryRewrite
      .validate(original, candidate)
      .left
      .map: violation =>
        AgentError.ModelInputError(violation.message)
