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

/** Scoped request-only history projection; its output never replaces canonical agent history. */
trait ModelInputTransform:
  def apply(
      context: ModelStreamContext,
      canonicalHistory: Chunk[Msg]
  ): ZIO[Scope, AgentError.ModelInputError, Chunk[Msg]]

object ModelInputTransform:
  val identity: ModelInputTransform = ModelInputTransform((_, history) => ZIO.succeed(history))

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

  private[agent] def validate(
      original: Chunk[Msg],
      candidate: Chunk[Msg]
  ): Either[AgentError.ModelInputError, Chunk[Msg]] =
    HistoryRewrite
      .validate(original, candidate)
      .left
      .map: violation =>
        AgentError.ModelInputError(violation.message)
