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
import java.io.IOException
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.channels.{ClosedByInterruptException, ClosedChannelException}
import io.netty.handler.codec.PrematureChannelClosureException
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

enum McpHttpListenPolicy derives JsonCodec:
  case Single
  case Resume(maxRestarts: Int)
  case ResumeOnBodyIoFailure(maxRestarts: Int)

enum McpHttpTermination:
  case Deleted, Unsupported, AlreadyExpired, NoSession

enum McpHttpContinuation derives JsonCodec:
  case Disabled
  case OnPrematureEof(maxAttempts: Int)
  case OnPrematureEofOrConnectionLoss(maxAttempts: Int)
  case OnPrematureEofOrBodyIoFailure(maxAttempts: Int)

/** Explicit host-run listening and termination; callers own and join both within the transport lifetime. */
trait McpStreamableHttpTransport extends McpTransport:
  def listen: IO[AgentError, McpHttpListenResult]

  /** Conservative compatibility default: custom transports must explicitly support resumption. */
  def listen(policy: McpHttpListenPolicy): IO[AgentError, McpHttpListenResult] =
    McpStreamableHttpTransport.listenBudget(policy) *> (policy match
      case McpHttpListenPolicy.Single => listen
      case _: McpHttpListenPolicy.Resume | _: McpHttpListenPolicy.ResumeOnBodyIoFailure =>
        ZIO.fail(
          AgentError.McpError("MCP HTTP listener resumption is unsupported by this transport")
        ))

  /**
   * Stops local admission for every outcome and attempts DELETE at most once. Callers share the full
   * outcome after joined response cleanup; cancelling an additional caller waiting for the shared
   * outcome does not cancel that attempt.
   */
  def terminateSession: IO[AgentError, McpHttpTermination]

/**
 * 2025-06-18 HTTP transport over a borrowed HTTP client. Each send owns and joins a fresh
 * response Scope; connection workers must finish before the transport owner closes. Escaped caller
 * fibers are not supervised here. The host HTTP pool must permit concurrent request and control
 * POSTs and must not add automatic retries or redirects. The connection supplies cancellation
 * notifications; this transport does not originate them. Issuance is published only after validated
 * response headers, so cancellation before headers is ineligible and response cleanup can delay
 * notification enqueue. Listening is an explicit effect after initialization, never automatic. One
 * active explicit listener is allowed. Optional bounded resumption keeps its lease across GETs and
 * requires a committed cursor; missing cursors and exhausted clean EOF return Ended, while 405
 * returns Unsupported. Listener streams never accept RPC responses, including after resumption. Caller cancellation
 * joins GET cleanup without closing the transport unless cleanup also fails. Optional bounded
 * continuation resumes only the same send after joined response cleanup. Connection-loss recovery
 * also recognizes direct ClosedChannelException (except ClosedByInterruptException) and
 * PrematureChannelClosureException typed failures. Explicit body-I/O policies additionally accept
 * direct cause-free exact-class IOException from raw SSE bodies, including unclassified truncation or timeout.
 * Other I/O failures, compound causes, defects and cancellation remain nonretryable. No POST is
 * replayed. Session termination is explicit and joins its response
 * cleanup; concurrent callers share the first attempt's full outcome. The host must join termination,
 * sends and listening before releasing the transport owner. Owner closure never sends DELETE and
 * rejects a first termination attempt afterward. No automatic reinitialization is performed.
 */
object McpStreamableHttpTransport:
  private def listenBudget(policy: McpHttpListenPolicy): IO[AgentError.InvalidConfiguration, Int] =
    policy match
      case McpHttpListenPolicy.Single                                    => ZIO.succeed(0)
      case McpHttpListenPolicy.Resume(value) if value > 0                => ZIO.succeed(value)
      case McpHttpListenPolicy.ResumeOnBodyIoFailure(value) if value > 0 => ZIO.succeed(value)
      case _: McpHttpListenPolicy.Resume | _: McpHttpListenPolicy.ResumeOnBodyIoFailure =>
        ZIO.fail(AgentError.InvalidConfiguration("MCP HTTP listener restarts must be positive"))

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
    case Stopped(cause: Cause[AgentError], session: Session)

  private type TerminationResult = Promise[Nothing, Exit[AgentError, McpHttpTermination]]

  private enum Termination:
    case Unclaimed, Sealed
    case Claimed(result: TerminationResult)

  private enum TerminationClaim:
    case Lead(session: Option[String], result: TerminationResult)
    case Observe(result: TerminationResult)

  private enum Consumer:
    case Available, Claimed

  private enum Progress:
    case Continue(cursor: String)
    case Complete

  final private case class ExchangeResult(progress: Progress, session: Option[String])

  final private case class ConnectionLoss(error: AgentError)
  final private case class BodyIoFailure(error: AgentError)
  private type AttemptError = AgentError | ConnectionLoss | BodyIoFailure

  private enum ContentType:
    case Json, Sse

  def open(
      http: Client,
      endpoint: String,
      headers: Headers = Headers.empty,
      limits: McpHttpLimits = McpHttpLimits(),
      continuation: McpHttpContinuation = McpHttpContinuation.Disabled
  ): ZIO[Scope, AgentError, McpStreamableHttpTransport] =
    for
      _ <- ZIO.fromEither(validate(headers, limits))
      _ <- ZIO
        .fail(AgentError.InvalidConfiguration("MCP HTTP continuation attempts must be positive"))
        .when(continuation match
          case McpHttpContinuation.Disabled                                 => false
          case McpHttpContinuation.OnPrematureEof(attempts)                 => attempts <= 0
          case McpHttpContinuation.OnPrematureEofOrConnectionLoss(attempts) => attempts <= 0
          case McpHttpContinuation.OnPrematureEofOrBodyIoFailure(attempts)  => attempts <= 0)
      url <- address(endpoint)
      live <- ZIO.uninterruptible:
        for
          gate        <- Semaphore.make(1)
          requests    <- Semaphore.make(limits.maxConcurrentRequests.toLong)
          controls    <- Semaphore.make(1)
          queue       <- Queue.dropping[McpMessage](limits.incomingCapacity)
          state       <- Ref.make[State](State.Open(Session(Initialization.Fresh, None)))
          consumer    <- Ref.make[Consumer](Consumer.Available)
          listener    <- Ref.make[Listener](Listener.Idle)
          termination <- Ref.make[Termination](Termination.Unclaimed)
          terminal    <- Promise.make[Nothing, Cause[AgentError]]
          transport = new Live(
            http,
            url,
            headers,
            limits,
            continuation,
            gate,
            requests,
            controls,
            queue,
            state,
            consumer,
            listener,
            termination,
            terminal
          )
          _ <- ZIO.addFinalizer(
            transport.closeOwner
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

  private def attemptBoundary(operation: String)(error: Throwable): AttemptError = error match
    case interrupted: ClosedByInterruptException => boundary(operation)(interrupted)
    case _: ClosedChannelException | _: PrematureChannelClosureException =>
      ConnectionLoss(boundary(operation)(error))
    case other => boundary(operation)(other)

  private def domainError(error: AttemptError): AgentError = error match
    case ConnectionLoss(value) => value
    case BodyIoFailure(value)  => value
    case value: AgentError     => value

  /** This additional provenance is assigned only before decoding a raw SSE response body. */
  private def bodyBoundary(operation: String)(error: Throwable): AttemptError =
    if error.getClass == classOf[IOException] && Option(error.getCause).isEmpty then
      BodyIoFailure(boundary(operation)(error))
    else attemptBoundary(operation)(error)

  private def resumesFailure(policy: McpHttpListenPolicy, error: AttemptError): Boolean =
    (policy, error) match
      case (_: McpHttpListenPolicy.Resume, _: ConnectionLoss) => true
      case (_: McpHttpListenPolicy.ResumeOnBodyIoFailure, _: ConnectionLoss | _: BodyIoFailure) =>
        true
      case _ => false

  private def continuesFailure(policy: McpHttpContinuation, error: AttemptError): Boolean =
    (policy, error) match
      case (_: McpHttpContinuation.OnPrematureEofOrConnectionLoss, _: ConnectionLoss) => true
      case (
            _: McpHttpContinuation.OnPrematureEofOrBodyIoFailure,
            _: ConnectionLoss | _: BodyIoFailure
          ) =>
        true
      case _ => false

  final private class Live(
      http: Client,
      url: URL,
      headers: Headers,
      limits: McpHttpLimits,
      continuation: McpHttpContinuation,
      gate: Semaphore,
      requests: Semaphore,
      controls: Semaphore,
      queue: Queue[McpMessage],
      state: Ref[State],
      consumer: Ref[Consumer],
      listener: Ref[Listener],
      termination: Ref[Termination],
      terminal: Promise[Nothing, Cause[AgentError]]
  ) extends McpStreamableHttpTransport:
    def terminateSession: IO[AgentError, McpHttpTermination] =
      ZIO.uninterruptibleMask: restore =>
        gate
          .withPermit(claimTermination)
          .flatMap:
            case TerminationClaim.Observe(result) => restore(result.await).flatMap(exit => exit)
            case TerminationClaim.Lead(session, result) =>
              restore(deleteSession(session)).exit.flatMap(exit => result.succeed(exit) *> exit)

    private def claimTermination: IO[AgentError, TerminationClaim] =
      termination.get.flatMap:
        case Termination.Sealed => ZIO.fail(AgentError.McpError("MCP HTTP transport owner closed"))
        case Termination.Claimed(result) => ZIO.succeed(TerminationClaim.Observe(result))
        case Termination.Unclaimed =>
          for
            current <- state.get
            session = current match
              case State.Open(value)       => value.id
              case State.Stopped(_, value) => value.id
            result <- Promise.make[Nothing, Exit[AgentError, McpHttpTermination]]
            _      <- termination.set(Termination.Claimed(result))
            _ <- stopWithinGate(
              Cause.fail(AgentError.McpError("MCP HTTP session termination requested"))
            )
          yield TerminationClaim.Lead(session, result)

    private def deleteSession(session: Option[String]): IO[AgentError, McpHttpTermination] =
      session match
        case None => ZIO.succeed(McpHttpTermination.NoSession)
        case Some(id) =>
          ZIO.scoped:
            for
              response <- ZIO.uninterruptibleMask: restore =>
                val requestHeaders = headers ++ Headers(
                  "Mcp-Session-Id"       -> id,
                  "MCP-Protocol-Version" -> McpInitialization.protocolVersion
                )
                restore(
                  Client
                    .streaming(Request.delete(url).addHeaders(requestHeaders))
                    .provideSomeEnvironment[Scope](_.add(http))
                ).mapError(boundary("MCP HTTP DELETE"))
              result <- response.status.code match
                case 405 => ZIO.succeed(McpHttpTermination.Unsupported)
                case 404 => ZIO.succeed(McpHttpTermination.AlreadyExpired)
                case _ if response.status.isSuccess => ZIO.succeed(McpHttpTermination.Deleted)
                case code =>
                  ZIO.fail(AgentError.McpError(s"MCP HTTP DELETE rejected with status $code"))
            yield result

    def closeOwner: UIO[Unit] = ZIO.uninterruptible:
      gate.withPermit:
        for
          _ <- termination.update:
            case Termination.Unclaimed => Termination.Sealed
            case other                 => other
          _ <- stopWithinGate(Cause.fail(AgentError.McpError("MCP HTTP transport closed")))
        yield ()

    def listen: IO[AgentError, McpHttpListenResult] = listen(McpHttpListenPolicy.Single)

    override def listen(policy: McpHttpListenPolicy): IO[AgentError, McpHttpListenResult] =
      listenBudget(policy).flatMap: budget =>
        ZIO.scoped:
          ZIO
            .acquireRelease(claimListener)(_ => gate.withPermit(listener.set(Listener.Idle)))
            .flatMap(session =>
              awaitListener(session, policy, budget).onExit:
                case Exit.Failure(cause) if !cause.isInterruptedOnly => stop(cause)
                case _                                               => ZIO.unit
            )

    private def claimListener: IO[AgentError, Option[String]] = gate.withPermit:
      state.get.flatMap:
        case State.Stopped(cause, _) => ZIO.refailCause(cause)
        case State.Open(Session(_: Initialization.Ready, session)) =>
          listener.get.flatMap:
            case Listener.Active =>
              ZIO.fail(AgentError.McpError("MCP HTTP listener already active"))
            case Listener.Idle => listener.set(Listener.Active).as(session)
        case State.Open(_) =>
          ZIO.fail(AgentError.McpError("MCP HTTP listening requires acknowledged initialization"))

    /** Keep the full GET outcome while racing terminal signals; cancellation always joins cleanup. */
    private def awaitListener(
        session: Option[String],
        policy: McpHttpListenPolicy,
        budget: Int
    ): IO[AgentError, McpHttpListenResult] =
      ZIO.scoped:
        ZIO.uninterruptibleMask: restore =>
          for
            action <- listenLoop(session, "", policy, budget).interruptible.forkScoped
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

    private def listenLoop(
        session: Option[String],
        cursor: String,
        policy: McpHttpListenPolicy,
        remaining: Int
    ): IO[AgentError, McpHttpListenResult] =
      ZIO.uninterruptibleMask: restore =>
        for
          checkpoint <- Ref.make(cursor)
          exit       <- restore(listenExchange(session, cursor, checkpoint)).exit
          result <- exit match
            case Exit.Success(McpHttpListenResult.Ended) if remaining > 0 =>
              checkpoint.get.flatMap: committed =>
                if committed.isEmpty then ZIO.succeed(McpHttpListenResult.Ended)
                else restore(listenLoop(session, committed, policy, remaining - 1))
            case Exit.Success(value) => ZIO.succeed(value)
            case Exit.Failure(cause @ Cause.Fail(error, _))
                if remaining > 0 && resumesFailure(policy, error) =>
              checkpoint.get.flatMap: committed =>
                if committed.isEmpty then ZIO.refailCause(cause.map(domainError))
                else restore(listenLoop(session, committed, policy, remaining - 1))
            case Exit.Failure(cause) => ZIO.refailCause(cause.map(domainError))
        yield result

    private def listenExchange(
        session: Option[String],
        cursor: String,
        checkpoint: Ref[String]
    ): IO[AttemptError, McpHttpListenResult] =
      ZIO.scoped:
        for
          _ <- state.get.flatMap:
            case State.Open(_) => ZIO.unit
            // The outer terminal race interrupts this wait; never reflect its Cause into the action.
            case State.Stopped(_, _) => ZIO.never
          resumeHeaders <-
            if cursor.isEmpty then ZIO.succeed(Headers.empty)
            else cursorHeader(cursor).map(Headers("Last-Event-ID", _))
          requestHeaders = headers ++ Headers(
            "Accept"               -> "text/event-stream",
            "MCP-Protocol-Version" -> McpInitialization.protocolVersion
          ) ++ session.fold(Headers.empty)(id => Headers("Mcp-Session-Id", id)) ++ resumeHeaders
          response <- ZIO.uninterruptibleMask: restore =>
            restore(
              Client
                .streaming(Request.get(url).addHeaders(requestHeaders))
                .provideSomeEnvironment[Scope](_.add(http))
            ).mapError(attemptBoundary("MCP HTTP GET"))
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
                  .mapError(bodyBoundary("MCP HTTP GET body"))
                  .via(McpHttpFraming.sseEvents(limits.sseResponseBytes, limits.sseEventBytes))
                  .runForeach(event => offerListening(event, checkpoint))
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
          case State.Stopped(_, _) => ListeningGate.Stopped
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

    private def offerListening(
        event: McpHttpFraming.McpSseEvent,
        checkpoint: Ref[String]
    ): IO[AgentError, Unit] =
      event.message match
        case Some(_: McpMessage.Success) | Some(_: McpMessage.Failure) =>
          ZIO.fail(AgentError.McpError("MCP HTTP listener streams cannot carry RPC responses"))
        case _ =>
          gate
            .withPermit(ZIO.uninterruptible:
              state.get.flatMap:
                case State.Stopped(_, _) => ZIO.succeed(ListeningGate.Stopped)
                case State.Open(_) =>
                  val commit = checkpoint.set(event.lastEventId).as(ListeningGate.Continue)
                  event.message match
                    case None => commit
                    case Some(message) =>
                      queue
                        .offer(message)
                        .flatMap:
                          case true => commit
                          case false =>
                            ZIO.succeed(
                              ListeningGate
                                .Rejected(AgentError.McpError("MCP HTTP incoming capacity reached"))
                            ))
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
            restore(
              Ref
                .make(ExchangeResult(Progress.Continue(""), session))
                .flatMap: checkpoint =>
                  val attempts = continuation match
                    case McpHttpContinuation.Disabled                              => 0
                    case McpHttpContinuation.OnPrematureEof(value)                 => value
                    case McpHttpContinuation.OnPrematureEofOrConnectionLoss(value) => value
                    case McpHttpContinuation.OnPrematureEofOrBodyIoFailure(value)  => value
                  evaluateAttempt(
                    message,
                    checkpoint,
                    exchange(message, bytes, session, onIssued, checkpoint),
                    attempts
                  )
            )
              .tap(_ => acknowledged(message))
              .onExit:
                case Exit.Failure(cause) if initializing(message) => stop(cause)
                case _                                            => ZIO.unit)
      yield ()

    private def exchange(
        message: McpMessage,
        bytes: Chunk[Byte],
        session: Option[String],
        onIssued: UIO[Unit],
        checkpoint: Ref[ExchangeResult]
    ): IO[AttemptError, ExchangeResult] = ZIO.scoped:
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
          ).mapError(attemptBoundary("MCP HTTP POST"))
          _ <- checkStatus(message, session, response)
          selected <- captureSession(
            message match
              case McpMessage.Request(id, "initialize", _) => Some(id)
              case _                                       => None
            ,
            session,
            response
          )
          // A validated response proves issuance; pre-header interruption stays ineligible.
          _ <- checkpoint.update(_.copy(session = selected))
          _ <- onIssued
          progress <- restore(message match
            case request: McpMessage.Request => consume(request, response, checkpoint)
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
                    .as(Progress.Complete)
                ))
        yield ExchangeResult(progress, selected)

    /** Inspect the full attempt only after its response scope has joined cleanup. */
    private def evaluateAttempt(
        message: McpMessage,
        checkpoint: Ref[ExchangeResult],
        effect: IO[AttemptError, ExchangeResult],
        remaining: Int
    ): IO[AgentError, Unit] = ZIO.uninterruptibleMask: restore =>
      restore(effect).exit.flatMap:
        case Exit.Success(result) =>
          message match
            case request: McpMessage.Request => restore(continueRequest(request, result, remaining))
            case _                           => ZIO.unit
        case Exit.Failure(cause) =>
          val failure = ZIO.refailCause(cause.map(domainError))
          (message, cause) match
            case (request: McpMessage.Request, Cause.Fail(error, _))
                if remaining > 0 && continuesFailure(continuation, error) =>
              checkpoint.get.flatMap: result =>
                result.progress match
                  case Progress.Continue(cursor) if cursor.nonEmpty =>
                    restore(continueRequest(request, result, remaining))
                  case _ => failure
            case _ => failure

    private def continueRequest(
        request: McpMessage.Request,
        result: ExchangeResult,
        remaining: Int
    ): IO[AgentError, Unit] = result.progress match
      case Progress.Complete => ZIO.unit
      case Progress.Continue(cursor) =>
        if remaining <= 0 || cursor.isEmpty then
          ZIO.fail(
            AgentError.McpError(
              "MCP HTTP SSE ended before its matching response; continuation unavailable"
            )
          )
        else
          Ref
            .make(result)
            .flatMap: checkpoint =>
              evaluateAttempt(
                request,
                checkpoint,
                resume(request, result.session, cursor, checkpoint),
                remaining - 1
              )

    private def cursorHeader(cursor: String): IO[AgentError, String] =
      if cursor.exists(c => (c < ' ' && c != '\t') || c == '\u007f') ||
        cursor.headOption.exists(c => c == ' ' || c == '\t') ||
        cursor.lastOption.exists(c => c == ' ' || c == '\t')
      then
        ZIO.fail(
          AgentError.McpError(
            "MCP SSE cursor cannot be represented without HTTP header normalization"
          )
        )
      // Netty writes byte-valued header characters; preserve UTF-8 octets through that representation.
      else
        ZIO
          .attempt(new String(cursor.getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1))
          .mapError(boundary("MCP HTTP cursor encoding"))

    private def resume(
        request: McpMessage.Request,
        session: Option[String],
        cursor: String,
        checkpoint: Ref[ExchangeResult]
    ): IO[AttemptError, ExchangeResult] = ZIO.scoped:
      for
        encoded <- cursorHeader(cursor)
        _       <- ensureOpen
        response <- ZIO.uninterruptibleMask: restore =>
          val requestHeaders = headers ++ Headers(
            "Accept"               -> "text/event-stream",
            "MCP-Protocol-Version" -> McpInitialization.protocolVersion,
            "Last-Event-ID"        -> encoded
          ) ++ session.fold(Headers.empty)(id => Headers("Mcp-Session-Id", id))
          restore(
            Client
              .streaming(Request.get(url).addHeaders(requestHeaders))
              .provideSomeEnvironment[Scope](_.add(http))
          ).mapError(attemptBoundary("MCP HTTP continuation GET"))
        _        <- checkStatus(request, session, response)
        selected <- captureSession(None, session, response)
        _        <- checkpoint.update(_.copy(session = selected))
        kind     <- contentType(response)
        _ <- ZIO
          .fail(AgentError.McpError("MCP HTTP continuation requires text/event-stream"))
          .unless(kind == ContentType.Sse)
        progress <- consumeSse(request, response, cursor, checkpoint)
      yield ExchangeResult(progress, selected)

    private def initializing(message: McpMessage): Boolean = message match
      case McpMessage.Request(_, "initialize", _) => true
      case _                                      => false

    private def ensureOpen: IO[AgentError, Unit] = state.get.flatMap:
      case State.Open(_)           => ZIO.unit
      case State.Stopped(cause, _) => ZIO.refailCause(cause)

    private def begin(message: McpMessage): IO[AgentError, Option[String]] = gate.withPermit:
      state.get.flatMap:
        case State.Stopped(cause, _) => ZIO.refailCause(cause)
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
        ZIO.fail(
          AgentError.McpError(s"MCP HTTP request rejected with status ${response.status.code}")
        )

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
    ): IO[AgentError, Option[String]] = sessionHeaders(response) match
      case Left(error) => fatal(error)
      case Right(values) =>
        gate.withPermit(state.get.flatMap:
          case State.Stopped(cause, _) => ZIO.refailCause(cause)
          case State.Open(session) =>
            initializingId match
              case Some(id) if session.initialization == Initialization.Started(id) =>
                state.set(State.Open(session.copy(id = values.headOption))).as(values.headOption)
              case _
                  if values.headOption
                    .forall(id => sentSession.contains(id) && session.id.contains(id)) =>
                ZIO.succeed(sentSession)
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

    private def consume(
        request: McpMessage.Request,
        response: Response,
        checkpoint: Ref[ExchangeResult]
    ): IO[AttemptError, Progress] =
      val body = response.body.asStream.mapError(boundary("MCP HTTP response body"))
      contentType(response).flatMap:
        case ContentType.Json =>
          body
            .via(McpHttpFraming.json(limits.jsonResponseBytes))
            .runHead
            .flatMap:
              case Some(reply: McpMessage.Success) if reply.id == request.id =>
                received(request, reply).as(Progress.Complete)
              case Some(reply: McpMessage.Failure) if reply.id.contains(request.id) =>
                offer(reply).as(Progress.Complete)
              case _ =>
                ZIO.fail(AgentError.McpError("MCP HTTP JSON response did not match its request"))
        case ContentType.Sse => consumeSse(request, response, "", checkpoint)

    private def consumeSse(
        request: McpMessage.Request,
        response: Response,
        previousCursor: String,
        checkpoint: Ref[ExchangeResult]
    ): IO[AttemptError, Progress] =
      response.body.asStream
        .mapError(bodyBoundary("MCP HTTP response body"))
        .via(McpHttpFraming.sseEvents(limits.sseResponseBytes, limits.sseEventBytes))
        .mapZIO: event =>
          // Acceptance and its cursor checkpoint are one finite local publication step.
          ZIO.uninterruptible:
            val accept = event.message match
              case None => ZIO.succeed(Progress.Continue(event.lastEventId))
              case Some(reply: McpMessage.Success) if reply.id == request.id =>
                received(request, reply).as(Progress.Complete)
              case Some(reply: McpMessage.Failure) if reply.id.contains(request.id) =>
                offer(reply).as(Progress.Complete)
              case Some(other: McpMessage.Request) =>
                offer(other).as(Progress.Continue(event.lastEventId))
              case Some(other: McpMessage.Notification) =>
                offer(other).as(Progress.Continue(event.lastEventId))
              case _ =>
                ZIO.fail(AgentError.McpError("MCP HTTP SSE response did not match its request"))
            accept.tap(progress => checkpoint.update(_.copy(progress = progress)))
        .takeUntil(_ == Progress.Complete)
        .runLast
        .map(_.getOrElse(Progress.Continue(previousCursor)))

    private def offer(message: McpMessage): IO[AgentError, Unit] = gate.withPermit:
      state.get.flatMap:
        case State.Stopped(cause, _) => ZIO.refailCause(cause)
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
        case State.Stopped(_, _) => ZIO.unit
        case State.Open(session) =>
          state.set(State.Stopped(cause, session)) *> terminal.succeed(cause) *> queue.takeAll.unit

    def stop(cause: Cause[AgentError]): UIO[Unit] =
      ZIO.uninterruptible(gate.withPermit(stopWithinGate(cause)))
