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
package com.weisgnosis.agents.mcp.transport

import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.mcp.protocol.{McpInitialization, McpMessage, McpRequestId}
import java.net.URI
import java.util.Locale
import zio.*
import zio.http.{Body, Client, Headers, Request, Response, URL}
import zio.json.*
import zio.stream.{ZChannel, ZStream}

final case class McpHttpLimits(
    requestBytes: Int = 1048576,
    jsonResponseBytes: Int = 1048576,
    sseResponseBytes: Long = 8L * 1024 * 1024,
    sseEventBytes: Int = 1048576,
    incomingCapacity: Int = 64,
    maxConcurrentRequests: Int = 8
) derives JsonCodec

/**
 * POST-only 2025-06-18 transport over a borrowed HTTP client. Each send owns and joins a fresh
 * response Scope; connection workers must finish before the transport owner closes. Escaped caller
 * fibers are not supervised here. The host HTTP pool must permit concurrent request and control
 * POSTs and must not add automatic retries or redirects. No GET, resumption, cancellation messages,
 * DELETE or reinitialization is performed; owner closure is local cleanup only.
 */
object McpStreamableHttpTransport:
  private enum Initialization:
    case Fresh
    case Started(id: McpRequestId)

  final private case class Session(initialization: Initialization, id: Option[String])

  private enum State:
    case Open(session: Session)
    case Stopped(cause: Cause[AgentError])

  private enum Consumer:
    case Available, Claimed

  private enum Progress:
    case Continue, Complete

  private enum ContentType:
    case Json, Sse

  def open(
      http: Client,
      endpoint: String,
      headers: Headers = Headers.empty,
      limits: McpHttpLimits = McpHttpLimits()
  ): ZIO[Scope, AgentError, McpTransport] =
    for
      _   <- ZIO.fromEither(validate(headers, limits))
      url <- address(endpoint)
      live <- ZIO.uninterruptible:
        for
          gate     <- Semaphore.make(1)
          requests <- Semaphore.make(limits.maxConcurrentRequests.toLong)
          controls <- Semaphore.make(1)
          queue    <- Queue.dropping[McpMessage](limits.incomingCapacity)
          state    <- Ref.make[State](State.Open(Session(Initialization.Fresh, None)))
          consumer <- Ref.make[Consumer](Consumer.Available)
          terminal <- Promise.make[Nothing, Cause[AgentError]]
          transport = new Live(
            http,
            url,
            headers,
            limits,
            gate,
            requests,
            controls,
            queue,
            state,
            consumer,
            terminal
          )
          _ <- ZIO.addFinalizer(
            transport.stop(Cause.fail(AgentError.McpError("MCP HTTP transport closed")))
          )
        yield transport
    yield live

  private def validate(headers: Headers, limits: McpHttpLimits): Either[AgentError, Unit] =
    val controlled = Set(
      "accept",
      "content-type",
      "content-length",
      "host",
      "connection",
      "transfer-encoding",
      "mcp-session-id",
      "mcp-protocol-version"
    )
    val token = "!#$%&'*+-.^_`|~"
    for
      _ <- Either.cond(
        limits.requestBytes > 0 && limits.jsonResponseBytes > 0 && limits.sseResponseBytes > 0 &&
          limits.sseEventBytes > 0 && limits.incomingCapacity > 0 && limits.maxConcurrentRequests > 0,
        (),
        AgentError.InvalidConfiguration("MCP HTTP limits must be positive")
      )
      _ <- Either.cond(
        headers.toList.forall(header =>
          header.headerName.nonEmpty && header.headerName.forall(c =>
            c < 128 && (c.isLetterOrDigit || token.contains(c))
          ) &&
            !controlled.contains(header.headerName.toLowerCase(Locale.ROOT)) &&
            header.renderedValue.forall(c => c >= ' ' && c < 127)
        ),
        (),
        AgentError.InvalidConfiguration("Invalid or transport-controlled MCP HTTP header")
      )
    yield ()

  private def address(value: String): IO[AgentError, URL] =
    val invalid = AgentError.InvalidConfiguration(
      "MCP endpoint must be an absolute HTTP(S) URL without user information or a fragment"
    )
    for
      uri <- ZIO.attempt(new URI(value)).mapError(_ => invalid)
      _ <- ZIO
        .fail(invalid)
        .unless(
          Option(uri.getScheme)
            .exists(s => s.equalsIgnoreCase("http") || s.equalsIgnoreCase("https")) &&
            Option(uri.getHost).exists(_.nonEmpty) && Option(uri.getUserInfo).isEmpty &&
            Option(
              uri.getFragment
            ).isEmpty && (uri.getPort == -1 || (uri.getPort > 0 && uri.getPort <= 65535))
        )
      url <- ZIO.fromEither(URL.decode(value)).mapError(_ => invalid)
    yield url

  private def boundary(operation: String)(error: Throwable): AgentError = error match
    case domain: AgentError => domain
    case other =>
      AgentError.McpError(
        s"$operation failed (${other.getClass.getName}): ${Option(other.getMessage).getOrElse("no message")}"
      )

  final private class Live(
      http: Client,
      url: URL,
      headers: Headers,
      limits: McpHttpLimits,
      gate: Semaphore,
      requests: Semaphore,
      controls: Semaphore,
      queue: Queue[McpMessage],
      state: Ref[State],
      consumer: Ref[Consumer],
      terminal: Promise[Nothing, Cause[AgentError]]
  ) extends McpTransport:
    def incoming: ZStream[Any, AgentError, McpMessage] = ZStream.unwrapScoped:
      ZIO
        .acquireRelease(
          consumer
            .getAndSet(Consumer.Claimed)
            .flatMap:
              case Consumer.Claimed =>
                ZIO.fail(AgentError.McpError("MCP HTTP incoming is single-use"))
              case Consumer.Available => ZIO.unit
        )(_ => stop(Cause.fail(AgentError.McpError("MCP HTTP incoming closed"))))
        .as(ZStream.fromChannel(receive))

    private def receive: ZChannel[Any, Any, Any, Any, AgentError, Chunk[McpMessage], Unit] =
      ZChannel
        .fromZIO(
          queue.take
            .raceFirst(terminal.await.flatMap(ZIO.refailCause(_)))
            .flatMap(message => ensureOpen.as(message))
        )
        .flatMap(message => ZChannel.write(Chunk(message)) *> receive)

    def send(message: McpMessage): ZIO[Scope, AgentError, Unit] =
      val lane = message match
        case _: McpMessage.Request => requests
        case _                     => controls
      for
        bytes <- McpHttpFraming.encode(message, limits.requestBytes)
        _     <- ensureOpen
        _ <- lane.withPermit(ZIO.uninterruptibleMask: restore =>
          begin(message).flatMap: session =>
            restore(exchange(message, bytes, session)).onExit:
              case Exit.Failure(cause) if initializing(message) => stop(cause)
              case _                                            => ZIO.unit)
      yield ()

    private def exchange(
        message: McpMessage,
        bytes: Chunk[Byte],
        session: Option[String]
    ): IO[AgentError, Unit] = ZIO.scoped:
      val requestHeaders = headers ++ Headers(
        "Content-Type"         -> "application/json",
        "Accept"               -> "application/json, text/event-stream",
        "MCP-Protocol-Version" -> McpInitialization.protocolVersion
      ) ++ session.fold(Headers.empty)(id => Headers("Mcp-Session-Id", id))
      val request = Request.post(url, Body.fromChunk(bytes)).addHeaders(requestHeaders)
      for
        response <- Client
          .streaming(request)
          .provideSomeEnvironment[Scope](_.add(http))
          .mapError(boundary("MCP HTTP POST"))
        _ <- checkStatus(message, session, response)
        _ <- captureSession(message, session, response)
        _ <- message match
          case request: McpMessage.Request => consume(request, response)
          case _ =>
            response.body.asStream
              .mapError(boundary("MCP HTTP acknowledgement body"))
              .take(1)
              .runCollect
              .flatMap(bytes =>
                ZIO
                  .fail(AgentError.McpError("MCP HTTP 202 acknowledgement must have an empty body"))
                  .unless(bytes.isEmpty)
                  .unit
              )
      yield ()

    private def initializing(message: McpMessage): Boolean = message match
      case McpMessage.Request(_, "initialize", _) => true
      case _                                      => false

    private def ensureOpen: IO[AgentError, Unit] = state.get.flatMap:
      case State.Open(_)        => ZIO.unit
      case State.Stopped(cause) => ZIO.refailCause(cause)

    private def begin(message: McpMessage): IO[AgentError, Option[String]] = gate.withPermit:
      state.get.flatMap:
        case State.Stopped(cause) => ZIO.refailCause(cause)
        case State.Open(session) =>
          message match
            case McpMessage.Request(id, "initialize", _) =>
              session.initialization match
                case Initialization.Fresh =>
                  state
                    .set(State.Open(session.copy(initialization = Initialization.Started(id))))
                    .as(session.id)
                case _: Initialization.Started =>
                  ZIO.fail(AgentError.McpError("MCP HTTP initialization was already started"))
            case _ => ZIO.succeed(session.id)

    private def checkStatus(
        message: McpMessage,
        session: Option[String],
        response: Response
    ): IO[AgentError, Unit] =
      val accepted = message match
        case _: McpMessage.Request => response.status.isSuccess
        case _                     => response.status.code == 202
      if response.status.code == 404 && session.nonEmpty then
        fatal(
          AgentError.McpError("MCP HTTP session expired (404); open a fresh initialized client")
        )
      else if accepted then ZIO.unit
      else
        ZIO.fail(AgentError.McpError(s"MCP HTTP POST rejected with status ${response.status.code}"))

    private def captureSession(
        message: McpMessage,
        sentSession: Option[String],
        response: Response
    ): IO[AgentError, Unit] =
      val values = response.rawHeaders("Mcp-Session-Id")
      if values.size > 1 || values.headOption.exists(id =>
          id.isEmpty || !id.forall(c => c >= '!' && c <= '~')
        )
      then fatal(AgentError.McpError("Invalid MCP HTTP session header"))
      else
        gate.withPermit(state.get.flatMap:
          case State.Stopped(cause) => ZIO.refailCause(cause)
          case State.Open(session) =>
            message match
              case McpMessage.Request(id, "initialize", _)
                  if session.initialization == Initialization.Started(id) =>
                state.set(State.Open(session.copy(id = values.headOption)))
              case _
                  if values.headOption
                    .forall(id => sentSession.contains(id) && session.id.contains(id)) =>
                ZIO.unit
              case _ =>
                val cause = Cause.fail(AgentError.McpError("Conflicting MCP HTTP session header"))
                stopWithinGate(cause) *> ZIO.refailCause(cause))

    private def contentType(response: Response): IO[AgentError, ContentType] =
      val values = response.rawHeaders("Content-Type")
      values.headOption.filter(_ => values.size == 1) match
        case Some(value) =>
          val parts = value.split(';').toList.map(_.trim)
          val validParameters = parts
            .drop(1)
            .forall(parameter =>
              parameter.equalsIgnoreCase("charset=utf-8") || parameter.equalsIgnoreCase(
                "charset=\"utf-8\""
              )
            )
          parts.headOption.map(_.toLowerCase(Locale.ROOT)) match
            case Some("application/json") if validParameters  => ZIO.succeed(ContentType.Json)
            case Some("text/event-stream") if validParameters => ZIO.succeed(ContentType.Sse)
            case _ => ZIO.fail(AgentError.McpError("Unsupported MCP HTTP Content-Type"))
        case None =>
          ZIO.fail(AgentError.McpError("MCP HTTP response requires one Content-Type header"))

    private def consume(request: McpMessage.Request, response: Response): IO[AgentError, Unit] =
      val body = response.body.asStream.mapError(boundary("MCP HTTP response body"))
      contentType(response).flatMap:
        case ContentType.Json =>
          body
            .via(McpHttpFraming.json(limits.jsonResponseBytes))
            .runHead
            .flatMap:
              case Some(reply: McpMessage.Success) if reply.id == request.id        => offer(reply)
              case Some(reply: McpMessage.Failure) if reply.id.contains(request.id) => offer(reply)
              case _ =>
                ZIO.fail(AgentError.McpError("MCP HTTP JSON response did not match its request"))
        case ContentType.Sse =>
          body
            .via(McpHttpFraming.sse(limits.sseResponseBytes, limits.sseEventBytes))
            .mapZIO:
              case reply: McpMessage.Success if reply.id == request.id =>
                offer(reply).as(Progress.Complete)
              case reply: McpMessage.Failure if reply.id.contains(request.id) =>
                offer(reply).as(Progress.Complete)
              case other: McpMessage.Request      => offer(other).as(Progress.Continue)
              case other: McpMessage.Notification => offer(other).as(Progress.Continue)
              case _ =>
                ZIO.fail(AgentError.McpError("MCP HTTP SSE response did not match its request"))
            .takeUntil(_ == Progress.Complete)
            .runLast
            .flatMap(last =>
              ZIO
                .fail(AgentError.McpError("MCP HTTP SSE ended before its matching response"))
                .unless(last.contains(Progress.Complete))
                .unit
            )

    private def offer(message: McpMessage): IO[AgentError, Unit] = gate.withPermit:
      state.get.flatMap:
        case State.Stopped(cause) => ZIO.refailCause(cause)
        case State.Open(_) =>
          queue
            .offer(message)
            .flatMap:
              case true => ZIO.unit
              case false =>
                val cause = Cause.fail(AgentError.McpError("MCP HTTP incoming capacity reached"))
                stopWithinGate(cause) *> ZIO.refailCause(cause)

    private def fatal(error: AgentError): IO[AgentError, Nothing] =
      stop(Cause.fail(error)) *> ZIO.fail(error)

    private def stopWithinGate(cause: Cause[AgentError]): UIO[Unit] = ZIO.uninterruptible:
      state.get.flatMap:
        case State.Stopped(_) => ZIO.unit
        case State.Open(_) =>
          state.set(State.Stopped(cause)) *> terminal.succeed(cause) *> queue.takeAll.unit

    def stop(cause: Cause[AgentError]): UIO[Unit] =
      ZIO.uninterruptible(gate.withPermit(stopWithinGate(cause)))
