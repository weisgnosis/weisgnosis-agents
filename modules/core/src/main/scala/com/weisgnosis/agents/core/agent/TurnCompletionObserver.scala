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
import com.weisgnosis.agents.core.event.InvocationContext
import com.weisgnosis.agents.core.model.Msg
import zio.*

/**
 * Runtime-only completion data; the original execution cause is preserved without serialization.
 * A successful execution outcome does not acknowledge delivery to a caller cancelled during observation.
 */
final case class TurnCompletion(
    agentId: String,
    history: Chunk[Msg],
    outcome: Exit[AgentError, AgentResult],
    invocation: Option[InvocationContext] = None
)

/**
 * Scoped read-only observation, once per admitted invocation, after execution cleanup.
 * Required observation delays cancellation and successful result publication until its scope closes.
 * The agent run lock remains held: callbacks must not await operations requiring that same lock.
 * Early stream consumers no longer receive terminal observation failures through the abandoned queue.
 */
trait TurnCompletionObserver:
  def observe(completion: TurnCompletion): ZIO[Scope, AgentError.CompletionObservationError, Unit]

  /** Close this observer's scope before the next; failure or interruption skips remaining observers. */
  final def andThen(next: TurnCompletionObserver): TurnCompletionObserver =
    TurnCompletionObserver(completion =>
      ZIO.scoped(observe(completion)) *> ZIO.scoped(next.observe(completion))
    )

object TurnCompletionObserver:
  val ignore: TurnCompletionObserver = TurnCompletionObserver(_ => ZIO.unit)

  def apply(
      observation: TurnCompletion => ZIO[Scope, AgentError.CompletionObservationError, Unit]
  ): TurnCompletionObserver = new TurnCompletionObserver:
    def observe(
        completion: TurnCompletion
    ): ZIO[Scope, AgentError.CompletionObservationError, Unit] =
      ZIO.suspendSucceed(observation(completion))
