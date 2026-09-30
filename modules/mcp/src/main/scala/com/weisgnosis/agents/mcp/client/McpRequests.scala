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
package com.weisgnosis.agents.mcp.client

import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.mcp.protocol.{McpMessage, McpRequestId}
import zio.*
import zio.stm.*

/** Bounded correlation only; each register/build/send/await sequence requires a fresh request scope. */
trait McpRequests:
  def register: ZIO[Scope, AgentError.McpError, McpRequests.PendingRequest] =
    register(_ => ZIO.unit)

  /** Called outside STM only by the winning abandonment, after its full cause is settled. */
  def register(
      onAbandon: Cause[AgentError] => UIO[Unit]
  ): ZIO[Scope, AgentError.McpError, McpRequests.PendingRequest]
  def complete(reply: McpRequests.Reply): UIO[McpRequests.Delivery]
  def failAll(cause: Cause[AgentError]): UIO[Unit]
  def close: UIO[Unit]

object McpRequests:
  type Reply = McpMessage.Success | McpMessage.Failure

  enum Delivery:
    case Delivered, UnknownId, MissingId, Closed

  trait PendingRequest:
    def id: McpRequestId
    def await: IO[AgentError, Reply]

  private enum Abandonment:
    case Removed, Unchanged

  private type Completion = TPromise[Nothing, Exit[AgentError, Reply]]

  private enum State:
    case Open(lastId: Long, pending: Map[McpRequestId, Completion])
    case Closed

  def make(capacity: Int = 64): ZIO[Scope, AgentError.InvalidConfiguration, McpRequests] =
    if capacity <= 0 then
      ZIO.fail(AgentError.InvalidConfiguration("MCP request capacity must be positive"))
    else
      ZIO.acquireRelease(
        TRef.make[State](State.Open(0L, Map.empty)).commit.map(new Live(_, capacity))
      )(_.close)

  final private class Live(state: TRef[State], capacity: Int) extends McpRequests:
    def register(
        onAbandon: Cause[AgentError] => UIO[Unit]
    ): ZIO[Scope, AgentError.McpError, PendingRequest] =
      ZIO.acquireReleaseExit(reserve(onAbandon).commit)((request, exit) =>
        val cause = exit match
          case Exit.Success(_) =>
            Cause.fail(AgentError.McpError("MCP request scope closed before reply"))
          case Exit.Failure(cause) =>
            cause.map:
              case error: AgentError => error
              case _ =>
                AgentError.McpError("MCP request scope failed outside the agent error domain")
        abandon(request.id, cause, onAbandon)
      )

    private def reserve(
        onAbandon: Cause[AgentError] => UIO[Unit]
    ): STM[AgentError.McpError, PendingRequest] =
      state.get.flatMap:
        case State.Closed => STM.fail(AgentError.McpError("MCP request registry closed"))
        case State.Open(_, pending) if pending.size >= capacity =>
          STM.fail(AgentError.McpError("MCP request capacity reached"))
        case State.Open(Long.MaxValue, _) =>
          STM.fail(AgentError.McpError("MCP request identities exhausted"))
        case State.Open(lastId, pending) =>
          for
            completion <- TPromise.make[Nothing, Exit[AgentError, Reply]]
            nextId    = lastId + 1L
            requestId = McpRequestId.Number(BigInt(nextId))
            _ <- state.set(State.Open(nextId, pending.updated(requestId, completion)))
          yield new PendingRequest:
            val id: McpRequestId = requestId
            def await: IO[AgentError, Reply] =
              completion.await.commit
                .flatMap(exit => exit)
                .onExit:
                  case Exit.Failure(cause) if cause.isInterrupted => abandon(id, cause, onAbandon)
                  case _                                          => ZIO.unit

    /** Replies are already validated by the caller's protocol boundary; remote errors remain data. */
    def complete(reply: Reply): UIO[Delivery] =
      val id = reply match
        case McpMessage.Success(id, _) => Some(id)
        case McpMessage.Failure(id, _) => id
      state.get
        .flatMap:
          case State.Closed => STM.succeed(Delivery.Closed)
          case State.Open(lastId, pending) =>
            id match
              case None => STM.succeed(Delivery.MissingId)
              case Some(value) =>
                pending.get(value) match
                  case None => STM.succeed(Delivery.UnknownId)
                  case Some(completion) =>
                    state.set(State.Open(lastId, pending.removed(value))) *>
                      completion.succeed(Exit.succeed(reply)).as(Delivery.Delivered)
        .commit

    private def abandon(
        id: McpRequestId,
        cause: Cause[AgentError],
        onAbandon: Cause[AgentError] => UIO[Unit]
    ): UIO[Unit] =
      state.get
        .flatMap:
          case State.Closed => STM.succeed(Abandonment.Unchanged)
          case State.Open(lastId, pending) =>
            pending.get(id) match
              case None => STM.succeed(Abandonment.Unchanged)
              case Some(completion) =>
                state.set(State.Open(lastId, pending.removed(id))) *>
                  completion.succeed(Exit.failCause(cause)).as(Abandonment.Removed)
        .commit
        .flatMap:
          case Abandonment.Removed   => ZIO.suspendSucceed(onAbandon(cause))
          case Abandonment.Unchanged => ZIO.unit

    /** Empty causes become a local closure failure; every nonempty cause is retained unchanged. */
    def failAll(cause: Cause[AgentError]): UIO[Unit] =
      val terminal =
        if cause.isEmpty then Cause.fail(AgentError.McpError("MCP request registry closed"))
        else cause
      state.get
        .flatMap:
          case State.Closed => STM.unit
          case State.Open(_, pending) =>
            state.set(State.Closed) *>
              STM.foreachDiscard(pending.values)(_.succeed(Exit.failCause(terminal)))
        .commit

    def close: UIO[Unit] = failAll(Cause.fail(AgentError.McpError("MCP request registry closed")))
