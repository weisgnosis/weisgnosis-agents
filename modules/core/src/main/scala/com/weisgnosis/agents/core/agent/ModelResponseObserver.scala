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

import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.event.ModelStreamContext
import com.weisgnosis.agents.core.model.Msg
import zio.*

/** Runtime-only accepted response and its actual selected, projected model request. */
final case class AcceptedModelResponse(
    context: ModelStreamContext,
    request: ModelRequestInput,
    message: Msg,
    history: Chunk[Msg]
)

/**
 * Read-only observation after accepted assistant persistence, before acting or result publication.
 * Callback scopes close before execution proceeds. Callbacks must not await the agent's run lock.
 * Observers own any ordering of their internal writes; scope closure does not validate unjoined
 * child success or move an acknowledgement after their own finalizers.
 */
trait ModelResponseObserver:
  def observe(
      response: AcceptedModelResponse
  ): ZIO[Scope, AgentError.CompletionObservationError, Unit]

  /** Close each callback scope before the next; failure or interruption skips later callbacks. */
  final def andThen(next: ModelResponseObserver): ModelResponseObserver =
    ModelResponseObserver(response =>
      ZIO.scoped(observe(response)) *> ZIO.scoped(next.observe(response))
    )

object ModelResponseObserver:
  val ignore: ModelResponseObserver = ModelResponseObserver(_ => ZIO.unit)

  def apply(
      observation: AcceptedModelResponse => ZIO[Scope, AgentError.CompletionObservationError, Unit]
  ): ModelResponseObserver = new ModelResponseObserver:
    def observe(
        response: AcceptedModelResponse
    ): ZIO[Scope, AgentError.CompletionObservationError, Unit] =
      ZIO.suspendSucceed(observation(response))
