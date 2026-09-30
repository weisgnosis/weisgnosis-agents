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

enum McpHttpListenResult:
  case Ended, Unsupported

/** Explicit host-run listening; the caller owns and joins the effect within the transport lifetime. */
trait McpStreamableHttpTransport extends McpTransport:
  def listen: IO[AgentError, McpHttpListenResult]

/**
 * 2025-06-18 HTTP transport over a borrowed HTTP client. Each send owns and joins a fresh
 * response Scope; connection workers must finish before the transport owner closes. Escaped caller
 * fibers are not supervised here. The host HTTP pool must permit concurrent request and control
 * POSTs and must not add automatic retries or redirects. The connection supplies cancellation
 * notifications; this transport does not originate them. Issuance is published only after validated
 * response headers, so cancellation before headers is ineligible and response cleanup can delay
 * notification enqueue. Listening is an explicit effect after initialization, never automatic. One
 * active GET is allowed; EOF or 405 releases it for another explicit fresh GET. Caller cancellation
 * joins GET cleanup without closing the transport unless cleanup also fails. No resumption, DELETE
 * or reinitialization is performed; owner closure signals active listeners to finish local cleanup.
 */
object McpStreamableHttpTransport:
  private enum Initialization:
    case Fresh
    case Started(id: McpRequestId)
    case Responded(id: McpRequestId)
    case Ready(id: McpRequestId)

  private enum Listener:
    case Idle, Active

  private enum ListeningGate:
    case Continue, Stopped
    case Rejected(error: AgentError)

  private enum Listening:
    case Completed(exit: Exit[AgentError, McpHttpListenResult])
    case Stopped(cause: Cause[AgentError])

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
  ): ZIO[Scope, AgentError, McpStreamableHttpTransport] =
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
          listener <- Ref.make[Listener](Listener.Idle)
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
            listener,
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
      "mcp-protocol-version",
      "last-event-id"
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
      listener: Ref[Listener],
      terminal: Promise[Nothing, Cause[AgentError]]
  ) extends McpStreamableHttpTransport:
    def listen: IO[AgentError, McpHttpListenResult] = ZIO.scoped:
      ZIO
        .acquireRelease(claimListener)(_ => gate.withPermit(listener.set(Listener.Idle)))
        .flatMap(session =>
          awaitListener(session).onExit:
            case Exit.Failure(cause) if !cause.isInterruptedOnly => stop(cause)
            case _                                               => ZIO.unit
        )

    private def claimListener: IO[AgentError, Option[String]] = gate.withPermit:
      state.get.flatMap:
        case State.Stopped(cause) => ZIO.refailCause(cause)
        case State.Open(Session(_: Initialization.Ready, session)) =>
          listener.get.flatMap:
            case Listener.Active =>
              ZIO.fail(AgentError.McpError("MCP HTTP listener already active"))
            case Listener.Idle => listener.set(Listener.Active).as(session)
        case State.Open(_) =>
          ZIO.fail(AgentError.McpError("MCP HTTP listening requires acknowledged initialization"))

    /** Keep the full GET outcome while racing terminal signals; cancellation always joins cleanup. */
    private def awaitListener(session: Option[String]): IO[AgentError, McpHttpListenResult] =
      ZIO.scoped:
        ZIO.uninterruptibleMask: restore =>
          for
            action <- listenExchange(session).interruptible.forkScoped
            observed <- restore(
              action.await
                .map[Listening](Listening.Completed(_))
                .raceFirst(terminal.await.map[Listening](Listening.Stopped(_)))
            ).exit
            result <- observed match
              case Exit.Success(Listening.Completed(exit)) => exit
              case Exit.Success(Listening.Stopped(cause)) =>
                action.interrupt.flatMap(exit => ZIO.refailCause(combine(cause, exit)))
              case Exit.Failure(cause) =>
                action.interrupt.flatMap(exit => ZIO.refailCause(combine(cause, exit)))
          yield result

    // GET-originated failures are published only after the action ends; they are independent of
    // an external terminal winner. A stopped gate waits for that winner instead of replaying it.
    private def combine(
        cause: Cause[AgentError],
        exit: Exit[AgentError, McpHttpListenResult]
    ): Cause[AgentError] = exit match
      case Exit.Failure(observed) if !observed.isInterruptedOnly => cause ++ observed
      case _                                                     => cause

    private def listenExchange(session: Option[String]): IO[AgentError, McpHttpListenResult] =
      ZIO.scoped:
        val requestHeaders = headers ++ Headers(
          "Accept"               -> "text/event-stream",
          "MCP-Protocol-Version" -> McpInitialization.protocolVersion
        ) ++ session.fold(Headers.empty)(id => Headers("Mcp-Session-Id", id))
        for
          response <- ZIO.uninterruptibleMask: restore =>
            restore(
              Client
                .streaming(Request.get(url).addHeaders(requestHeaders))
                .provideSomeEnvironment[Scope](_.add(http))
            ).mapError(boundary("MCP HTTP GET"))
          result <-
            if response.status.code == 405 then ZIO.succeed(McpHttpListenResult.Unsupported)
            else
              for
                _ <-
                  if response.status.code == 404 && session.nonEmpty then
                    ZIO.fail(
                      AgentError
                        .McpError("MCP HTTP session expired (404); open a fresh initialized client")
                    )
                  else
                    ZIO
                      .fail(
                        AgentError
                          .McpError(s"MCP HTTP GET rejected with status ${response.status.code}")
                      )
                      .unless(response.status.isSuccess)
                      .unit
                _    <- checkListeningSession(session, response)
                kind <- contentType(response)
                _ <- ZIO
                  .fail(AgentError.McpError("MCP HTTP GET requires text/event-stream"))
                  .unless(kind == ContentType.Sse)
                _ <- response.body.asStream
                  .mapError(boundary("MCP HTTP GET body"))
                  .via(McpHttpFraming.sse(limits.sseResponseBytes, limits.sseEventBytes))
                  .runForeach:
                    case message: McpMessage.Request      => offerListening(message)
                    case message: McpMessage.Notification => offerListening(message)
                    case _ =>
                      ZIO.fail(
                        AgentError
                          .McpError("MCP HTTP GET cannot carry responses without resumption")
                      )
              yield McpHttpListenResult.Ended
        yield result

    private def proceedListening(decision: ListeningGate): IO[AgentError, Unit] = decision match
      case ListeningGate.Continue        => ZIO.unit
      case ListeningGate.Rejected(error) => ZIO.fail(error)
      // The existing terminal race interrupts this wait and joins the GET response scope.
      case ListeningGate.Stopped => ZIO.never

    private def checkListeningSession(
        sentSession: Option[String],
        response: Response
    ): IO[AgentError, Unit] =
      gate
        .withPermit(state.get.map:
          case State.Stopped(_) => ListeningGate.Stopped
          case State.Open(session) =>
            sessionHeaders(response) match
              case Left(error) => ListeningGate.Rejected(error)
              case Right(values)
                  if values.headOption
                    .forall(id => sentSession.contains(id) && session.id.contains(id)) =>
                ListeningGate.Continue
              case Right(_) =>
                ListeningGate.Rejected(
                  AgentError.McpError("Conflicting MCP HTTP session header")
                ))
        .flatMap(proceedListening)

    private def offerListening(message: McpMessage): IO[AgentError, Unit] =
      gate
        .withPermit(state.get.flatMap:
          case State.Stopped(_) => ZIO.succeed(ListeningGate.Stopped)
          case State.Open(_) =>
            queue
              .offer(message)
              .map:
                case true => ListeningGate.Continue
                case false =>
                  ListeningGate.Rejected(AgentError.McpError("MCP HTTP incoming capacity reached")))
        .flatMap(proceedListening)

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
      send(message, ZIO.unit)

    override def send(message: McpMessage, onIssued: UIO[Unit]): ZIO[Scope, AgentError, Unit] =
      val lane = message match
        case _: McpMessage.Request => requests
        case _                     => controls
      for
        bytes <- McpHttpFraming.encode(message, limits.requestBytes)
        _     <- ensureOpen
        _ <- lane.withPermit(ZIO.uninterruptibleMask: restore =>
          begin(message).flatMap: session =>
            restore(exchange(message, bytes, session, onIssued))
              .tap(_ => acknowledged(message))
              .onExit:
                case Exit.Failure(cause) if initializing(message) => stop(cause)
                case _                                            => ZIO.unit)
      yield ()

    private def exchange(
        message: McpMessage,
        bytes: Chunk[Byte],
        session: Option[String],
        onIssued: UIO[Unit]
    ): IO[AgentError, Unit] = ZIO.scoped:
      ZIO.uninterruptibleMask: restore =>
        val requestHeaders = headers ++ Headers(
          "Content-Type"         -> "application/json",
          "Accept"               -> "application/json, text/event-stream",
          "MCP-Protocol-Version" -> McpInitialization.protocolVersion
        ) ++ session.fold(Headers.empty)(id => Headers("Mcp-Session-Id", id))
        val request = Request.post(url, Body.fromChunk(bytes)).addHeaders(requestHeaders)
        for
          response <- restore(
            Client
              .streaming(request)
              .provideSomeEnvironment[Scope](_.add(http))
          ).mapError(boundary("MCP HTTP POST"))
          _ <- checkStatus(message, session, response)
          _ <- captureSession(
            message match
              case McpMessage.Request(id, "initialize", _) => Some(id)
              case _                                       => None
            ,
            session,
            response
          )
          // A validated response proves issuance; pre-header interruption stays ineligible.
          _ <- onIssued
          _ <- restore(message match
            case request: McpMessage.Request => consume(request, response)
            case _ =>
              response.body.asStream
                .mapError(boundary("MCP HTTP acknowledgement body"))
                .take(1)
                .runCollect
                .flatMap(bytes =>
                  ZIO
                    .fail(
                      AgentError.McpError("MCP HTTP 202 acknowledgement must have an empty body")
                    )
                    .unless(bytes.isEmpty)
                    .unit
                ))
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
                case _ =>
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

    private def sessionHeaders(response: Response): Either[AgentError, Chunk[String]] =
      val values = response.rawHeaders("Mcp-Session-Id")
      if values.size > 1 || values.headOption.exists(id =>
          id.isEmpty || !id.forall(c => c >= '!' && c <= '~')
        )
      then Left(AgentError.McpError("Invalid MCP HTTP session header"))
      else Right(values)

    private def captureSession(
        initializingId: Option[McpRequestId],
        sentSession: Option[String],
        response: Response
    ): IO[AgentError, Unit] = sessionHeaders(response) match
      case Left(error) => fatal(error)
      case Right(values) =>
        gate.withPermit(state.get.flatMap:
          case State.Stopped(cause) => ZIO.refailCause(cause)
          case State.Open(session) =>
            initializingId match
              case Some(id) if session.initialization == Initialization.Started(id) =>
                state.set(State.Open(session.copy(id = values.headOption)))
              case _
                  if values.headOption
                    .forall(id => sentSession.contains(id) && session.id.contains(id)) =>
                ZIO.unit
              case _ =>
                val cause = Cause.fail(AgentError.McpError("Conflicting MCP HTTP session header"))
                stopWithinGate(cause) *> ZIO.refailCause(cause))

    private def acknowledged(message: McpMessage): IO[AgentError, Unit] = message match
      case McpMessage.Notification("notifications/initialized", _) =>
        gate.withPermit:
          state.get.flatMap:
            case State.Open(session @ Session(Initialization.Responded(id), _)) =>
              state.set(State.Open(session.copy(initialization = Initialization.Ready(id))))
            case _ => ZIO.unit
      case _ => ZIO.unit

    private def received(
        request: McpMessage.Request,
        reply: McpMessage.Success
    ): IO[AgentError, Unit] =
      val mark =
        if request.method == "initialize" then
          gate.withPermit:
            state.get.flatMap:
              case State.Open(session @ Session(Initialization.Started(id), _))
                  if id == request.id =>
                state.set(State.Open(session.copy(initialization = Initialization.Responded(id))))
              case _ => ZIO.unit
        else ZIO.unit
      mark *> offer(reply)

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
              case Some(reply: McpMessage.Success) if reply.id == request.id =>
                received(request, reply)
              case Some(reply: McpMessage.Failure) if reply.id.contains(request.id) => offer(reply)
              case _ =>
                ZIO.fail(AgentError.McpError("MCP HTTP JSON response did not match its request"))
        case ContentType.Sse =>
          body
            .via(McpHttpFraming.sse(limits.sseResponseBytes, limits.sseEventBytes))
            .mapZIO:
              case reply: McpMessage.Success if reply.id == request.id =>
                received(request, reply).as(Progress.Complete)
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
