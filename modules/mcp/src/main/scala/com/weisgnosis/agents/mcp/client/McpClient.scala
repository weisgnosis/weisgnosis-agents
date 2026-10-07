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
import com.weisgnosis.agents.mcp.protocol.*
import com.weisgnosis.agents.mcp.tool.McpToolMetadataPropagation
import com.weisgnosis.agents.mcp.transport.McpTransport
import zio.*
import zio.json.ast.Json

/**
 * Initialized 2025-06-18 client over a borrowed transport. The host retains transport ownership.
 * Checked initialization and request deadlines cancel the handshake and each list/call operation;
 * the Duration overload applies one uniform deadline. Returning waits for owned cleanup and can
 * exceed the selected deadline. Discovery is one page or an explicitly bounded
 * sequential traversal. Interrupted issued requests may send best-effort cancellation without
 * waiting for delivery; pre-header HTTP cancellation is ineligible. No cache, reconnect, notification
 * invalidation, schema evaluation, or Toolkit authority is installed.
 */
final class McpClient private (
    val initialization: McpInitializationResult,
    connection: McpConnection,
    deadline: Duration,
    boundToolMetadata: Ref[McpToolMetadataPropagation]
):
  /**
   * Each bound invocation snapshots this restriction before its resolver. Changing it does not revoke
   * a resolver already running, even before send. Explicit list/call metadata is unaffected.
   */
  def setToolMetadataPropagation(mode: McpToolMetadataPropagation): UIO[Unit] =
    boundToolMetadata.set(mode)

  private[mcp] def toolMetadataPropagation: UIO[McpToolMetadataPropagation] = boundToolMetadata.get

  def listToolsPage(
      cursor: Option[String] = None,
      meta: Option[Json.Obj] = None
  ): IO[AgentError, McpToolsPage] =
    McpClient.withDeadline(
      requireTools *> connection
        .request(id => McpTools.listRequest(id, cursor, meta))
        .flatMap(McpClient.payload)
        .flatMap(value => ZIO.fromEither(McpTools.readListResult(value))),
      deadline,
      "tools/list"
    )

  /**
   * Retain ordered duplicates within explicit page/tool bounds, with the configured deadline per
   * page. Page metadata is omitted; the result is not an atomic server snapshot or a tool registry.
   */
  def discoverTools(
      maxPages: Int,
      maxTools: Int,
      meta: Option[Json.Obj] = None
  ): IO[AgentError, Chunk[McpToolDefinition]] =
    def loop(
        cursor: Option[String],
        seen: Set[String],
        pages: Int,
        accumulated: Chunk[McpToolDefinition]
    ): IO[AgentError, Chunk[McpToolDefinition]] =
      listToolsPage(cursor, meta).flatMap: page =>
        if page.tools.size > maxTools - accumulated.size then
          ZIO.fail(AgentError.McpError("MCP discovery tool limit exceeded"))
        else
          val tools = accumulated ++ page.tools
          page.nextCursor match
            case None => ZIO.succeed(tools)
            case Some(next) if seen.contains(next) =>
              ZIO.fail(AgentError.McpError("MCP discovery repeated a cursor"))
            case Some(_) if pages >= maxPages =>
              ZIO.fail(AgentError.McpError("MCP discovery page limit exceeded"))
            case Some(next) => loop(Some(next), seen + next, pages + 1, tools)

    if maxPages <= 0 || maxTools <= 0 then
      ZIO.fail(
        AgentError.InvalidConfiguration("MCP discovery page and tool limits must be positive")
      )
    else loop(None, Set.empty, 1, Chunk.empty)

  def callTool(
      name: String,
      arguments: Option[Json.Obj] = None,
      meta: Option[Json.Obj] = None
  ): IO[AgentError, McpCallResult] =
    McpClient.withDeadline(
      requireTools *> connection
        .request(id => McpTools.callRequest(id, name, arguments, meta))
        .flatMap(McpClient.payload)
        .flatMap(value => ZIO.fromEither(McpCallResult.read(value))),
      deadline,
      "tools/call"
    )

  def close: UIO[Unit] = connection.close

  private def requireTools: IO[AgentError, Unit] =
    ZIO
      .fail(AgentError.McpError("MCP server did not advertise the tools capability"))
      .when(initialization.capabilities.tools.isEmpty)
      .unit

object McpClient:
  /** Positive finite operation limits; they do not bound joined resource cleanup. */
  final class Deadlines private (
      val initialization: Duration,
      val request: Duration
  )

  object Deadlines:
    def make(
        initialization: Duration,
        request: Duration
    ): Either[AgentError.InvalidConfiguration, Deadlines] =
      Either.cond(
        initialization > Duration.Zero && initialization < Duration.Infinity &&
          request > Duration.Zero && request < Duration.Infinity,
        new Deadlines(initialization, request),
        AgentError.InvalidConfiguration(
          "MCP initialization and request deadlines must be positive and finite"
        )
      )

  private enum Observation[+A]:
    case Completed(exit: Exit[AgentError, A])
    case Elapsed

  def open(
      transport: McpTransport,
      clientInfo: McpImplementation,
      deadline: Duration = 30.seconds
  ): ZIO[Scope, AgentError, McpClient] =
    ZIO
      .fromEither(Deadlines.make(deadline, deadline))
      .mapError(_ =>
        AgentError.InvalidConfiguration("MCP client deadline must be positive and finite")
      )
      .flatMap(deadlines => open(transport, clientInfo, deadlines))

  def open(
      transport: McpTransport,
      clientInfo: McpImplementation,
      deadlines: Deadlines
  ): ZIO[Scope, AgentError, McpClient] =
    ZIO.uninterruptibleMask: restore =>
      for
        connection <- McpConnection.make(transport)
        initialized <- restore(
          withDeadline(
            initialize(connection, clientInfo),
            deadlines.initialization,
            "initialization"
          )
        ).exit
        client <- initialized match
          case Exit.Success(result) =>
            Ref
              .make(McpToolMetadataPropagation.Allow)
              .map(mode => new McpClient(result, connection, deadlines.request, mode))
          case Exit.Failure(cause) =>
            connection.close.exit.flatMap:
              case Exit.Success(_) => ZIO.refailCause(cause)
              case Exit.Failure(cleanup) =>
                ZIO.refailCause(if cause.contains(cleanup) then cause else cause ++ cleanup)
      yield client

  private def initialize(
      connection: McpConnection,
      clientInfo: McpImplementation
  ): IO[AgentError, McpInitializationResult] =
    for
      reply  <- connection.request(id => McpInitialization.request(id, clientInfo))
      value  <- payload(reply)
      result <- ZIO.fromEither(McpInitialization.readResult(value))
      _      <- connection.notify(McpInitialization.initialized)
    yield result

  private def payload(reply: McpRequests.Reply): IO[AgentError, Json.Obj] = reply match
    case McpMessage.Success(_, result) => ZIO.succeed(result)
    case McpMessage.Failure(_, error) =>
      ZIO.fail(
        AgentError.McpRemoteError(
          error.code,
          error.message,
          error.data.fold[AgentError.McpErrorData](AgentError.McpErrorData.Absent)(
            AgentError.McpErrorData.Present(_)
          )
        )
      )

  /** Observe the whole operation; losing actions are interrupted and joined explicitly. */
  private def withDeadline[A](
      effect: IO[AgentError, A],
      deadline: Duration,
      operation: String
  ): IO[AgentError, A] =
    ZIO.scoped:
      ZIO.uninterruptibleMask: restore =>
        for
          action <- effect.interruptible.forkScoped
          observed <- restore(
            action.await
              .map[Observation[A]](Observation.Completed(_))
              .raceFirst(
                ZIO.sleep(deadline).as[Observation[A]](Observation.Elapsed)
              )
          ).exit
          result <- observed match
            case Exit.Success(Observation.Completed(exit)) => exit
            case Exit.Success(Observation.Elapsed) =>
              val cause =
                Cause.fail(
                  AgentError.TimeoutError(
                    deadline.toSeconds,
                    s"MCP $operation timed out after $deadline"
                  )
                )
              action.interrupt.flatMap(exit => ZIO.refailCause(combine(cause, exit)))
            case Exit.Failure(cause) =>
              action.interrupt.flatMap(exit => ZIO.refailCause(combine(cause, exit)))
        yield result

  private def combine[A](cause: Cause[AgentError], exit: Exit[AgentError, A]): Cause[AgentError] =
    exit match
      case Exit.Failure(observed) if observed.contains(cause)    => observed
      case Exit.Failure(observed) if !observed.isInterruptedOnly => cause ++ observed
      case _                                                     => cause
