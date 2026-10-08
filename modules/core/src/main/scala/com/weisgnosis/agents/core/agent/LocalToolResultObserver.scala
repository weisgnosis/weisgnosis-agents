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
import com.weisgnosis.agents.core.model.{ContentBlock, Msg}
import zio.*

/** Runtime-only evidence of one completed local dispatch and its atomically persisted final view. */
final case class PersistedLocalToolObservation(
    context: ModelStreamContext,
    call: ContentBlock.ToolUse,
    callOrdinal: Int,
    original: ContentBlock.ToolResult,
    persisted: ContentBlock.ToolResult,
    history: Chunk[Msg]
)

/**
 * Required scoped observation after optional processing and final local result persistence, before
 * completion events or stop publication. Completed results remain recorded if observation fails.
 * Completed local dispatch includes normalized decoding or execution errors; it does not prove
 * that a handler executed. Pre-dispatch denial/preparation, supplied, inline and recovery results
 * are excluded. Progress events may already exist; terminal completion is withheld on failure. Persistence does not prove model receipt or caller delivery. Callbacks must not
 * await the agent run lock and must explicitly join children whose success matters.
 */
trait LocalToolResultObserver:
  def observe(
      observation: PersistedLocalToolObservation
  ): ZIO[Scope, AgentError.CompletionObservationError, Unit]

  /** Close each callback scope before the next; failure or interruption skips later callbacks. */
  final def andThen(next: LocalToolResultObserver): LocalToolResultObserver =
    LocalToolResultObserver(observation =>
      ZIO.scoped(observe(observation)) *> ZIO.scoped(next.observe(observation))
    )

object LocalToolResultObserver:
  val ignore: LocalToolResultObserver = LocalToolResultObserver(_ => ZIO.unit)

  def apply(
      callback: PersistedLocalToolObservation => ZIO[
        Scope,
        AgentError.CompletionObservationError,
        Unit
      ]
  ): LocalToolResultObserver = new LocalToolResultObserver:
    def observe(
        observation: PersistedLocalToolObservation
    ): ZIO[Scope, AgentError.CompletionObservationError, Unit] =
      ZIO.suspendSucceed(callback(observation))
