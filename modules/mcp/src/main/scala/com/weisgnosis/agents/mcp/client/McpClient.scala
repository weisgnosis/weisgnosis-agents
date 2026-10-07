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
import zio.stream.ZStream

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
    boundToolMetadata: Ref[McpToolMetadataPropagation],
    resourceEvents: Hub[McpResourceChange],
    resourceFailure: Promise[Nothing, Cause[AgentError]]
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

  def listResourcesPage(
      cursor: Option[String] = None,
      meta: Option[Json.Obj] = None
  ): IO[AgentError, McpResourcePage[McpResourceDefinition]] =
    resourceRequest("resources/list", id => McpResources.listRequest(id, cursor, meta))
      .flatMap(value => ZIO.fromEither(McpResources.readListResult(value)))

  def listResourceTemplatesPage(
      cursor: Option[String] = None,
      meta: Option[Json.Obj] = None
  ): IO[AgentError, McpResourcePage[McpResourceTemplate]] =
    resourceRequest(
      "resources/templates/list",
      id => McpResources.templatesRequest(id, cursor, meta)
    )
      .flatMap(value => ZIO.fromEither(McpResources.readTemplatesResult(value)))

  def readResource(
      uri: String,
      meta: Option[Json.Obj] = None
  ): IO[AgentError, McpReadResourceResult] =
    resourceRequest("resources/read", id => McpResources.readRequest(id, uri, meta))
      .flatMap(value => ZIO.fromEither(McpResources.readResult(value)))

  /** Preserve ordered duplicates; cursors are opaque and each page owns its request deadline. */
  def discoverResources(
      maxPages: Int,
      maxResources: Int,
      meta: Option[Json.Obj] = None
  ): IO[AgentError, Chunk[McpResourceDefinition]] =
    discoverResourcePages(maxPages, maxResources, cursor => listResourcesPage(cursor, meta))

  def discoverResourceTemplates(
      maxPages: Int,
      maxTemplates: Int,
      meta: Option[Json.Obj] = None
  ): IO[AgentError, Chunk[McpResourceTemplate]] =
    discoverResourcePages(maxPages, maxTemplates, cursor => listResourceTemplatesPage(cursor, meta))

  /** Requires advertised subscriptions; ownership of explicit unsubscribe belongs to the host. */
  def subscribeResource(uri: String, meta: Option[Json.Obj] = None): IO[AgentError, Unit] =
    requireResourceSubscriptions *>
      resourceRequest("resources/subscribe", id => McpResources.subscribeRequest(id, uri, meta))
        .flatMap(value => ZIO.fromEither(McpClient.emptyResult(value)))

  def unsubscribeResource(uri: String, meta: Option[Json.Obj] = None): IO[AgentError, Unit] =
    requireResourceSubscriptions *>
      resourceRequest("resources/unsubscribe", id => McpResources.unsubscribeRequest(id, uri, meta))
        .flatMap(value => ZIO.fromEither(McpClient.emptyResult(value)))

  /**
   * Connection-local hints, not a replay log or a resource cache. Start consumption before subscribing.
   * A slow observer exceeding the 64-event buffer fails explicitly; RPC correlation remains usable.
   * Malformed resource notifications also fail observers. Closing this client ends the stream.
   */
  def resourceChanges: ZStream[Any, AgentError, McpResourceChange] =
    ZStream.unwrapScoped(observeResourceChanges)

  /** Acquire the observer before sending subscribe requests, avoiding a local registration race. */
  def observeResourceChanges: ZIO[Scope, AgentError, ZStream[Any, AgentError, McpResourceChange]] =
    for
      _        <- requireResources
      released <- Promise.make[Nothing, Unit]
      _        <- ZIO.addFinalizer(released.succeed(()).unit)
      queue    <- resourceEvents.subscribe
    yield ZStream
      .fromQueue(queue)
      .filter:
        case _: McpResourceChange.ListChanged =>
          initialization.capabilities.resources.exists(_.listChanged.contains(true))
        case _: McpResourceChange.Updated =>
          initialization.capabilities.resources.exists(_.subscribe.contains(true))
      .merge(
        ZStream.fromZIO(
          resourceFailure.await
            .raceFirst(
              released.await.as(Cause.fail(AgentError.McpError("MCP resource observer closed")))
            )
            .flatMap(ZIO.refailCause(_))
        )
      )

  def close: UIO[Unit] = connection.close.ensuring(resourceEvents.shutdown)

  private def resourceRequest(
      operation: String,
      build: McpRequestId => Either[AgentError.InvalidMessage, McpMessage.Request]
  ): IO[AgentError, Json.Obj] =
    McpClient.withDeadline(
      requireResources *> connection.request(build).flatMap(McpClient.payload),
      deadline,
      operation
    )

  private def requireResources: IO[AgentError, Unit] =
    ZIO
      .fail(AgentError.McpError("MCP server did not advertise the resources capability"))
      .when(initialization.capabilities.resources.isEmpty)
      .unit

  private def requireResourceSubscriptions: IO[AgentError, Unit] =
    requireResources *> ZIO
      .fail(AgentError.McpError("MCP server did not advertise resource subscriptions"))
      .unless(initialization.capabilities.resources.exists(_.subscribe.contains(true)))
      .unit

  private def discoverResourcePages[A](
      maxPages: Int,
      maxItems: Int,
      fetch: Option[String] => IO[AgentError, McpResourcePage[A]]
  ): IO[AgentError, Chunk[A]] =
    def loop(
        cursor: Option[String],
        seen: Set[String],
        pages: Int,
        accumulated: Chunk[A]
    ): IO[AgentError, Chunk[A]] =
      fetch(cursor).flatMap: page =>
        if page.items.size > maxItems - accumulated.size then
          ZIO.fail(AgentError.McpError("MCP resource discovery item limit exceeded"))
        else
          val items = accumulated ++ page.items
          page.nextCursor match
            case None => ZIO.succeed(items)
            case Some(next) if seen.contains(next) =>
              ZIO.fail(AgentError.McpError("MCP resource discovery repeated a cursor"))
            case Some(_) if pages >= maxPages =>
              ZIO.fail(AgentError.McpError("MCP resource discovery page limit exceeded"))
            case Some(next) => loop(Some(next), seen + next, pages + 1, items)
    if maxPages <= 0 || maxItems <= 0 then
      ZIO.fail(
        AgentError.InvalidConfiguration(
          "MCP resource discovery page and item limits must be positive"
        )
      )
    else loop(None, Set.empty, 1, Chunk.empty)

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
        events    <- ZIO.acquireRelease(Hub.dropping[McpResourceChange](64))(_.shutdown)
        failure   <- Promise.make[Nothing, Cause[AgentError]]
        resources <- Ref.make(Option.empty[McpResourceCapabilities])
        connection <- McpConnection.observed(
          transport,
          64,
          16,
          notification => observeResourceNotification(notification, resources, events, failure),
          cause => failure.succeed(cause).unit
        )
        initialized <- restore(
          withDeadline(
            initialize(connection, clientInfo, resources),
            deadlines.initialization,
            "initialization"
          )
        ).exit
        client <- initialized match
          case Exit.Success(result) =>
            Ref
              .make(McpToolMetadataPropagation.Allow)
              .map(mode =>
                new McpClient(result, connection, deadlines.request, mode, events, failure)
              )
          case Exit.Failure(cause) =>
            connection.close.exit.flatMap:
              case Exit.Success(_) => ZIO.refailCause(cause)
              case Exit.Failure(cleanup) =>
                ZIO.refailCause(if cause.contains(cleanup) then cause else cause ++ cleanup)
      yield client

  private def initialize(
      connection: McpConnection,
      clientInfo: McpImplementation,
      resources: Ref[Option[McpResourceCapabilities]]
  ): IO[AgentError, McpInitializationResult] =
    for
      reply  <- connection.request(id => McpInitialization.request(id, clientInfo))
      value  <- payload(reply)
      result <- ZIO.fromEither(McpInitialization.readResult(value))
      _      <- resources.set(result.capabilities.resources)
      _      <- connection.notify(McpInitialization.initialized)
    yield result

  private def observeResourceNotification(
      notification: McpMessage.Notification,
      resources: Ref[Option[McpResourceCapabilities]],
      events: Hub[McpResourceChange],
      failure: Promise[Nothing, Cause[AgentError]]
  ): UIO[Unit] =
    resources.get.flatMap: capabilities =>
      val enabled = notification.method match
        case "notifications/resources/list_changed" =>
          capabilities.exists(_.listChanged.contains(true))
        case "notifications/resources/updated" => capabilities.exists(_.subscribe.contains(true))
        case _                                 => false
      if !enabled then ZIO.unit
      else
        McpResources.readChange(notification) match
          case Left(error) => failure.succeed(Cause.fail(error)).unit
          case Right(None) => ZIO.unit
          case Right(Some(change)) =>
            events
              .publish(change)
              .flatMap:
                case true => ZIO.unit
                case false =>
                  failure
                    .succeed(
                      Cause.fail(AgentError.McpError("MCP resource notification buffer exceeded"))
                    )
                    .unit

  private def emptyResult(value: Json.Obj): Either[AgentError.DeserializationError, Unit] =
    com.weisgnosis.agents.core.json.JsonBoundary
      .validate(value)
      .flatMap: _ =>
        value.get("_meta") match
          case None | Some(_: Json.Obj) => Right(())
          case _                        => Left("Result _meta must be an object")
      .left
      .map(AgentError.DeserializationError(_))

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
