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
import com.weisgnosis.agents.core.model.{Msg, Role, ToolHistory}
import zio.*

/** Scoped history rewriting at a completed tool-phase boundary, before a model request. */
trait HistoryPreparer:
  def prepare(history: Chunk[Msg]): ZIO[Scope, AgentError.HistoryPreparationError, Chunk[Msg]]

object HistoryPreparer:
  val identity: HistoryPreparer = HistoryPreparer(history => ZIO.succeed(history))

  def apply(
      rewrite: Chunk[Msg] => ZIO[Scope, AgentError.HistoryPreparationError, Chunk[Msg]]
  ): HistoryPreparer = new HistoryPreparer:
    def prepare(history: Chunk[Msg]): ZIO[Scope, AgentError.HistoryPreparationError, Chunk[Msg]] =
      ZIO.suspendSucceed(rewrite(history))

  private[agent] def validate(
      original: Chunk[Msg],
      candidate: Chunk[Msg]
  ): Either[AgentError.HistoryPreparationError, Chunk[Msg]] =
    if candidate == original then Right(original)
    else if original.filter(_.role == Role.System) != candidate.filter(_.role == Role.System) then
      Left(
        AgentError.HistoryPreparationError(
          "History preparation must preserve ordered system messages"
        )
      )
    else if !ToolHistory.inspect(candidate).isComplete then
      Left(
        AgentError.HistoryPreparationError(
          "Prepared history contains unmatched tool calls or results"
        )
      )
    else Right(candidate)
