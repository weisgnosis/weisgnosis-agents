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
import com.weisgnosis.agents.core.model.{Msg, Role, ToolHistory}
import scala.annotation.unused
import zio.*

/** Scoped history rewriting at a completed tool-phase boundary, before a model request. */
trait HistoryPreparer:
  def prepare(history: Chunk[Msg]): ZIO[Scope, AgentError.HistoryPreparationError, Chunk[Msg]]

  /** Legacy preparers receive the same history through this invocation-aware entry point. */
  def prepare(
      @unused context: InvocationContext,
      history: Chunk[Msg]
  ): ZIO[Scope, AgentError.HistoryPreparationError, Chunk[Msg]] = prepare(history)

  /** Run both stages with the same invocation identity and request-owned scope. */
  final def andThen(next: HistoryPreparer): HistoryPreparer =
    val first: HistoryPreparer = this
    new HistoryPreparer:
      def prepare(history: Chunk[Msg]): ZIO[Scope, AgentError.HistoryPreparationError, Chunk[Msg]] =
        HistoryPreparer.compose(history, first.prepare, next.prepare)

      override def prepare(
          context: InvocationContext,
          history: Chunk[Msg]
      ): ZIO[Scope, AgentError.HistoryPreparationError, Chunk[Msg]] =
        HistoryPreparer.compose(history, first.prepare(context, _), next.prepare(context, _))

object HistoryPreparer:
  val identity: HistoryPreparer = HistoryPreparer(history => ZIO.succeed(history))

  def apply(
      rewrite: Chunk[Msg] => ZIO[Scope, AgentError.HistoryPreparationError, Chunk[Msg]]
  ): HistoryPreparer = new HistoryPreparer:
    def prepare(history: Chunk[Msg]): ZIO[Scope, AgentError.HistoryPreparationError, Chunk[Msg]] =
      ZIO.suspendSucceed(rewrite(history))

  private def compose(
      history: Chunk[Msg],
      first: Chunk[Msg] => ZIO[Scope, AgentError.HistoryPreparationError, Chunk[Msg]],
      next: Chunk[Msg] => ZIO[Scope, AgentError.HistoryPreparationError, Chunk[Msg]]
  ): ZIO[Scope, AgentError.HistoryPreparationError, Chunk[Msg]] =
    for
      candidate    <- ZIO.suspendSucceed(first(history))
      intermediate <- ZIO.fromEither(validate(history, candidate))
      result       <- ZIO.suspendSucceed(next(intermediate))
      validated    <- ZIO.fromEither(validate(intermediate, result))
    yield validated

  private[agent] def validate(
      original: Chunk[Msg],
      candidate: Chunk[Msg]
  ): Either[AgentError.HistoryPreparationError, Chunk[Msg]] =
    HistoryRewrite
      .validate(original, candidate)
      .left
      .map: violation =>
        AgentError.HistoryPreparationError(violation.message)

private[agent] object HistoryRewrite:
  enum Violation:
    case SystemMessagesChanged, IncompleteToolHistory

    def message: String = this match
      case SystemMessagesChanged => "History rewriting must preserve ordered system messages"
      case IncompleteToolHistory => "Rewritten history contains unmatched tool calls or results"

  def validate(original: Chunk[Msg], candidate: Chunk[Msg]): Either[Violation, Chunk[Msg]] =
    if candidate == original then Right(original)
    else if original.filter(_.role == Role.System) != candidate.filter(_.role == Role.System) then
      Left(Violation.SystemMessagesChanged)
    else if !ToolHistory.inspect(candidate).isComplete then Left(Violation.IncompleteToolHistory)
    else Right(candidate)
