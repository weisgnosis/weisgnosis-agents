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

/** Immutable state and input at an admitted fresh invocation, before input is persisted. */
final case class InvocationPreparation(history: Chunk[Msg], input: Chunk[Msg])

/**
 * Host preparation runs once per fresh invocation, including structured fallback and streaming.
 * Restoring a snapshot and resuming persisted acting state do not invoke this port.
 */
trait InvocationPreparer:
  def prepare(
      context: InvocationContext,
      preparation: InvocationPreparation
  ): ZIO[Scope, AgentError, InvocationPreparation]

  final def andThen(next: InvocationPreparer): InvocationPreparer =
    val first: InvocationPreparer = this
    InvocationPreparer: (context, original) =>
      for
        intermediate <- first.prepare(context, original)
        checked      <- ZIO.fromEither(InvocationPreparer.validate(original, intermediate))
        result       <- next.prepare(context, checked)
        validated    <- ZIO.fromEither(InvocationPreparer.validate(checked, result))
      yield validated

object InvocationPreparer:
  val identity: InvocationPreparer =
    InvocationPreparer((_, preparation) => ZIO.succeed(preparation))

  def apply(
      callback: (InvocationContext, InvocationPreparation) => ZIO[
        Scope,
        AgentError,
        InvocationPreparation
      ]
  ): InvocationPreparer = new InvocationPreparer:
    def prepare(
        context: InvocationContext,
        preparation: InvocationPreparation
    ): ZIO[Scope, AgentError, InvocationPreparation] =
      ZIO.suspendSucceed(callback(context, preparation))

  private[agent] def validate(
      original: InvocationPreparation,
      candidate: InvocationPreparation
  ): Either[AgentError.HistoryPreparationError, InvocationPreparation] =
    for
      _ <- HistoryPreparer.validate(original.history, candidate.history)
      _ <- HistoryPreparer.validate(
        original.history ++ original.input,
        candidate.history ++ candidate.input
      )
    yield candidate
