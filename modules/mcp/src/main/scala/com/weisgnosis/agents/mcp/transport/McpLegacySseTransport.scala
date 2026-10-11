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

import com.weisgnosis.agents.core.error.{AgentError, TransportFailure}
import com.weisgnosis.agents.mcp.protocol.{
  McpMessage,
  McpPacket,
  McpProtocolVersion,
  McpSupportedVersions
}
import java.net.URI
import zio.*
import zio.http.{Body, Client, Headers, Request, URL}
import zio.stream.ZStream

/**
 * The original two-endpoint HTTP transport. One owned GET receives endpoint/message events;
 * every send uses the advertised same-origin POST endpoint. No redirect, reconnect or replay is
 * performed. The borrowed HTTP client must disable implicit retries and redirects. The host joins
 * its connection and sends before releasing this owner. Local closure never cancels remote work.
 */
object McpLegacySseTransport:
  def open(
      http: Client,
      endpoint: String,
      headers: Headers = Headers.empty,
      limits: McpHttpLimits = McpHttpLimits(),
      endpointDeadline: Duration = 30.seconds,
      version: McpProtocolVersion = McpProtocolVersion.V20241105,
      supportedVersions: Option[McpSupportedVersions] = None
  ): ZIO[Scope, AgentError, McpTransport] =
    for
      versions <- McpVersionSession.make(version, supportedVersions)
      _        <- ZIO.fromEither(McpStreamableHttpTransport.validate(headers, limits))
      _ <- ZIO
        .fail(AgentError.InvalidConfiguration("MCP endpoint deadline must be positive and finite"))
        .unless(endpointDeadline > Duration.Zero && endpointDeadline < Duration.Infinity)
      url   <- McpStreamableHttpTransport.address(endpoint)
      owner <- ZIO.acquireRelease(Scope.make)(_.close(Exit.unit))
      transport <- ZIO.uninterruptibleMask: restore =>
        for
          queue <- owner.extend(
            ZIO.acquireRelease(Queue.dropping[McpPacket](limits.incomingCapacity))(_.shutdown)
          )
          ready    <- Promise.make[AgentError, URL]
          terminal <- Promise.make[Nothing, Cause[AgentError]]
          claimed  <- Ref.make(Consumer.Available)
          requests <- Semaphore.make(limits.maxConcurrentRequests.toLong)
          controls <- Semaphore.make(1)
          live = new Live(
            http,
            url,
            headers,
            limits,
            version,
            versions,
            queue,
            ready,
            terminal,
            claimed,
            requests,
            controls
          )
          reader <- owner.extend(restore(live.receive).forkScoped)
          _ <- owner.extend(ZIO.addFinalizer:
            live.stop(Cause.fail(AgentError.McpError("MCP legacy SSE transport owner closed"))) *>
              reader.interrupt.flatMap:
                case Exit.Failure(cause) =>
                  cause.keepDefects.fold[UIO[Unit]](ZIO.unit)(ZIO.refailCause(_))
                case Exit.Success(_) => ZIO.unit)
          result <- restore(
            ready.await.timeoutFail(
              AgentError.McpError("MCP legacy SSE endpoint deadline exceeded")
            )(endpointDeadline)
          ).exit
          _ <- result match
            case Exit.Success(_) => ZIO.unit
            case Exit.Failure(cause) =>
              owner
                .close(result)
                .exit
                .flatMap:
                  case Exit.Success(_) => ZIO.refailCause(cause)
                  case Exit.Failure(cleanup) =>
                    ZIO.refailCause(if cause.contains(cleanup) then cause else cause ++ cleanup)
        yield live
    yield transport

  /** Endpoint data is untrusted: resolve relative URIs without forwarding credentials off-origin. */
  private[transport] def resolveEndpoint(base: URL, advertised: String): IO[AgentError, URL] =
    val invalid = AgentError.InvalidMessage(
      "MCP legacy SSE advertised endpoint must remain on the same HTTP origin without credentials or fragment"
    )
    def port(uri: URI): Int =
      if uri.getPort >= 0 then uri.getPort
      else if Option(uri.getScheme).exists(_.equalsIgnoreCase("https")) then 443
      else 80
    for
      origin   <- ZIO.attempt(new URI(base.encode)).mapError(_ => invalid)
      relative <- ZIO.attempt(new URI(advertised)).mapError(_ => invalid)
      resolved = origin.resolve(relative)
      _ <- ZIO
        .fail(invalid)
        .unless(
          advertised.nonEmpty && Option(resolved.getRawUserInfo).isEmpty &&
            Option(resolved.getRawFragment).isEmpty &&
            Option(resolved.getScheme)
              .exists(scheme => Option(origin.getScheme).exists(_.equalsIgnoreCase(scheme))) &&
            Option(resolved.getHost)
              .exists(host => Option(origin.getHost).exists(_.equalsIgnoreCase(host))) &&
            port(origin) == port(resolved)
        )
      url <- ZIO.fromEither(URL.decode(resolved.toASCIIString)).mapError(_ => invalid)
    yield url

  private enum Consumer:
    case Available, Claimed

  final private class Live(
      http: Client,
      url: URL,
      headers: Headers,
      limits: McpHttpLimits,
      override val protocolVersion: McpProtocolVersion,
      versions: McpVersionSession,
      queue: Queue[McpPacket],
      ready: Promise[AgentError, URL],
      terminal: Promise[Nothing, Cause[AgentError]],
      claimed: Ref[Consumer],
      requests: Semaphore,
      controls: Semaphore
  ) extends McpTransport:
    override def currentProtocolVersion: UIO[McpProtocolVersion]                  = versions.current
    override def acquireProtocolSession: ZIO[Scope, AgentError, McpProtocolLease] = versions.acquire

    private def boundary(operation: String)(error: Throwable): AgentError = error match
      case domain: AgentError => domain
      case other => AgentError.McpError(TransportFailure.describe(s"$operation failed", other))

    def stop(cause: Cause[AgentError]): UIO[Unit] =
      versions.seal *> terminal
        .succeed(cause)
        .flatMap(first => if first then ready.failCause(cause).unit else ZIO.unit)

    private def ensureOpen: IO[AgentError, Unit] =
      terminal.poll.flatMap:
        case Some(result) => result.flatMap(ZIO.refailCause(_))
        case None         => ZIO.unit

    private def deliver(message: McpPacket): IO[AgentError, Unit] =
      queue
        .offer(message)
        .flatMap(accepted =>
          ZIO
            .fail(AgentError.McpError("MCP legacy SSE incoming queue overflow"))
            .unless(accepted)
            .unit
        )

    private def frame(value: McpHttpFraming.McpSseFrame): IO[AgentError, Unit] =
      value.data match
        case None => ZIO.unit
        case Some(data) =>
          value.eventType match
            case "endpoint" =>
              for
                endpoint <- resolveEndpoint(url, data)
                first    <- ready.succeed(endpoint)
                _ <- ZIO
                  .fail(
                    AgentError.InvalidMessage("MCP legacy SSE received a second endpoint event")
                  )
                  .unless(first)
              yield ()
            case "message" | "" =>
              for
                published <- ready.isDone
                _ <- ZIO
                  .fail(AgentError.InvalidMessage("MCP legacy SSE message arrived before endpoint"))
                  .unless(published)
                _       <- versions.awaitOwned
                message <- versions.decode(data)
                _       <- deliver(message)
              yield ()
            case _ => ZIO.unit

    def receive: IO[AgentError, Unit] =
      ZIO
        .scoped(receiveOwned)
        .onExit:
          case Exit.Failure(cause) => stop(cause)
          case Exit.Success(_)     => ZIO.unit

    private def receiveOwned: ZIO[Scope, AgentError, Unit] =
      val request = Request.get(url).addHeaders(headers ++ Headers("Accept" -> "text/event-stream"))
      for
        response <- Client
          .streaming(request)
          .provideSomeEnvironment[Scope](_.add(http))
          .mapError(boundary("MCP legacy SSE GET"))
        _ <- ZIO
          .fail(
            AgentError.McpError(s"MCP legacy SSE GET rejected with status ${response.status.code}")
          )
          .unless(response.status.code == 200)
        _ <- ZIO
          .fail(AgentError.InvalidMessage("MCP legacy SSE GET requires text/event-stream"))
          .unless(
            response.rawHeaders("Content-Type").toList match
              case value :: Nil =>
                value.takeWhile(_ != ';').trim.equalsIgnoreCase("text/event-stream")
              case _ => false
          )
        _ <- response.body.asStream
          .mapError(boundary("MCP legacy SSE body"))
          .via(McpHttpFraming.sseFrames(limits.sseResponseBytes, limits.sseEventBytes))
          .runForeach(frame)
        _ <- ZIO.fail(AgentError.McpError("MCP legacy SSE connection ended"))
      yield ()

    def send(message: McpMessage): ZIO[Scope, AgentError, Unit] = send(message, ZIO.unit)

    override def send(message: McpMessage, onIssued: UIO[Unit]): ZIO[Scope, AgentError, Unit] =
      sendPacketOwned(McpPacket.Single(message), onIssued)

    override def sendPacket(packet: McpPacket): ZIO[Scope, AgentError, Unit] =
      ZIO.fromEither(McpPacket.validateResponse(packet)) *> sendPacketOwned(packet, ZIO.unit)

    private def sendPacketOwned(
        packet: McpPacket,
        onIssued: UIO[Unit]
    ): ZIO[Scope, AgentError, Unit] =
      val semaphore = packet match
        case McpPacket.Single(_: McpMessage.Request) => requests
        case _                                       => controls
      for
        proposed <- versions.current
        encoded  <- McpHttpFraming.encodePacket(packet, proposed, limits.requestBytes)
        _ <- semaphore.withPermit:
          ZIO.scoped:
            ZIO.uninterruptibleMask: restore =>
              for
                _        <- ensureOpen
                target   <- restore(ready.await)
                selected <- versions.prepare(packet)
                _ <- (for
                  bytes <-
                    if selected == proposed then ZIO.succeed(encoded)
                    else McpHttpFraming.encodePacket(packet, selected, limits.requestBytes)
                  response <- restore(
                    Client
                      .streaming(
                        Request
                          .post(target, Body.fromChunk(bytes))
                          .addHeaders(
                            headers ++ Headers(
                              "Content-Type"         -> "application/json",
                              "Accept"               -> "application/json",
                              "MCP-Protocol-Version" -> selected.wire
                            )
                          )
                      )
                      .provideSomeEnvironment[Scope](_.add(http))
                  ).mapError(boundary("MCP legacy SSE POST"))
                  _ <- ZIO
                    .fail(
                      AgentError.McpError(
                        s"MCP legacy SSE POST rejected with status ${response.status.code}"
                      )
                    )
                    .unless(
                      response.status.code == 202 || response.status.code == 200 || response.status.code == 204
                    )
                  _ <- onIssued
                  _ <- restore(
                    response.body.asStream
                      .mapError(boundary("MCP legacy SSE POST body"))
                      .runFoldZIO(0L)((size, _) =>
                        if size >= limits.jsonResponseBytes then
                          ZIO.fail(
                            AgentError.InvalidMessage(
                              "MCP legacy SSE POST acknowledgement exceeds byte limit"
                            )
                          )
                        else ZIO.succeed(size + 1)
                      )
                      .unit
                  )
                  _ <- ensureOpen
                yield ()).onExit:
                  case Exit.Failure(_) if isInitialize(packet) => versions.seal
                  case _                                       => ZIO.unit
              yield ()
      yield ()

    private def isInitialize(packet: McpPacket): Boolean = packet match
      case McpPacket.Single(McpMessage.Request(_, "initialize", _)) => true
      case _                                                        => false

    def incoming: ZStream[Any, AgentError, McpMessage] =
      incomingPackets.mapZIO(packet => ZIO.fromEither(McpPacket.single(packet)))

    override def incomingPackets: ZStream[Any, AgentError, McpPacket] =
      ZStream.unwrap:
        (versions.ensureOwned *> claimed
          .getAndSet(Consumer.Claimed))
          .map:
            case Consumer.Claimed =>
              ZStream.fail(AgentError.McpError("MCP legacy SSE incoming stream already claimed"))
            case Consumer.Available =>
              ZStream
                .fromQueue(queue)
                .merge(
                  ZStream.fromZIO(terminal.await.flatMap(ZIO.refailCause(_)))
                )
