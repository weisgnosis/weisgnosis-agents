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
import com.weisgnosis.agents.mcp.client.McpClient
import com.weisgnosis.agents.mcp.protocol.*
import scala.annotation.unused
import zio.*
import zio.http.*
import zio.json.ast.Json
import zio.stream.{ZChannel, ZStream}
import zio.test.*

object McpStreamableHttpTransportSpec extends ZIOSpecDefault:
  private val endpoint: String = "http://127.0.0.1/mcp"
  private val initialization: Json.Obj = Json.Obj(
    "protocolVersion" -> Json.Str(McpInitialization.protocolVersion),
    "serverInfo"      -> Json.Obj("name" -> Json.Str("peer"), "version" -> Json.Str("1")),
    "capabilities"    -> Json.Obj("tools" -> Json.Obj())
  )
  private val emptyTools: Json.Obj = Json.Obj("tools" -> Json.Arr())
  private val pingId: McpRequestId = McpRequestId.Text("server-ping")

  private def scripted(f: Request => ZIO[Scope, Throwable, Response]): Client =
    ZClient.fromDriver(new ZClient.Driver[Any, Scope, Throwable]:
      def request(
          version: Version,
          method: Method,
          url: URL,
          headers: Headers,
          body: Body,
          sslConfig: Option[ClientSSLConfig],
          proxy: Option[Proxy]
      )(using Trace): ZIO[Scope, Throwable, Response] =
        f(Request(version = version, method = method, url = url, headers = headers, body = body))

      def socket[Env1 <: Any](
          version: Version,
          url: URL,
          headers: Headers,
          app: WebSocketApp[Env1]
      )(using Trace, Scope =:= Scope): ZIO[Env1 & Scope, Throwable, Response] =
        ZIO.fail(AgentError.McpError("WebSockets are outside this HTTP test driver")))

  private def decode(request: Request): IO[Throwable, McpMessage] =
    request.body.asString.flatMap(value => ZIO.fromEither(McpMessage.decode(value)))

  private def json(message: McpMessage): IO[AgentError, Response] =
    ZIO
      .fromEither(McpMessage.encode(message))
      .map(wire =>
        Response(
          status = Status.Ok,
          headers = Headers("Content-Type", "application/json"),
          body = Body.fromString(wire)
        )
      )

  private def event(message: McpMessage): ZStream[Any, AgentError, Byte] =
    ZStream
      .fromZIO(ZIO.fromEither(McpMessage.encode(message)))
      .flatMap(wire =>
        ZStream.fromIterable(
          s"data: $wire\r\n\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)
        )
      )

  private def stream(body: ZStream[Any, Throwable, Byte]): Response =
    Response(
      status = Status.Ok,
      headers = Headers("Content-Type", "text/event-stream"),
      body = Body.fromStreamChunked(body)
    )

  private def rawEvents(value: String): ZStream[Any, Nothing, Byte] =
    ZStream.suspend(ZStream.fromIterable(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)))

  private def server(
      call: McpMessage.Request => ZIO[Scope, Throwable, Response],
      session: Headers = Headers.empty
  ): Client = scripted(request =>
    decode(request).flatMap:
      case McpMessage.Request(id, "initialize", _) =>
        json(McpMessage.Success(id, initialization)).map(_.addHeaders(session))
      case _: McpMessage.Notification  => ZIO.succeed(Response.status(Status.Accepted))
      case request: McpMessage.Request => call(request)
      case _                           => ZIO.succeed(Response.status(Status.Accepted))
  )

  private def client(
      http: Client,
      limits: McpHttpLimits = McpHttpLimits()
  ): ZIO[Scope, AgentError, McpClient] =
    McpStreamableHttpTransport
      .open(http, endpoint, limits = limits)
      .flatMap(McpClient.open(_, McpImplementation("client", "1")))

  private def readyListener(
      get: Request => ZIO[Scope, Throwable, Response]
  ): ZIO[Scope, AgentError, McpStreamableHttpTransport] =
    for
      transport <- McpStreamableHttpTransport.open(
        scripted(request =>
          if request.method == Method.GET then get(request)
          else
            decode(request).flatMap:
              case McpMessage.Request(id, "initialize", _) =>
                json(McpMessage.Success(id, initialization))
                  .map(_.addHeader("Mcp-Session-Id", "listener"))
              case _ => ZIO.succeed(Response.status(Status.Accepted))
        ),
        endpoint
      )
      _ <- transport.send(McpMessage.Request(McpRequestId.Number(1), "initialize"))
      _ <- transport.send(McpInitialization.initialized)
    yield transport

  def spec: Spec[TestEnvironment & Scope, Any] = suite("McpStreamableHttpTransportSpec")(
    test(
      "listener resumption keeps its stream identity and finite cursor budget without granting reply routing"
    ) {
      for
        routed <- ZIO.scoped:
          for
            seen <- Ref.make(Chunk.empty[Headers])
            notice  = McpMessage.Notification("notifications/message")
            request = McpMessage.Request(pingId, "ping")
            transport <- readyListener(r =>
              seen
                .modify(previous => (previous.size, previous :+ r.headers))
                .map(index =>
                  if index == 0 then stream(rawEvents("id: first\n") ++ event(notice))
                  else stream(rawEvents("id:\n") ++ event(request))
                )
            )
            result   <- transport.listen(McpHttpListenPolicy.Resume(2))
            incoming <- transport.incoming.take(3).runCollect
            headers  <- seen.get
          yield result == McpHttpListenResult.Ended &&
            incoming == Chunk(
              McpMessage.Success(McpRequestId.Number(1), initialization),
              notice,
              request
            ) &&
            headers.map(_.get("Last-Event-ID")) == Chunk(None, Some("first")) &&
            headers.forall(_.get("Mcp-Session-Id").contains("listener"))
        cases <- ZIO.foreach(
          Chunk(
            (
              McpHttpListenPolicy.Single,
              Chunk("id: cursor\n\n"),
              Status.Ok,
              Some(McpHttpListenResult.Ended),
              1
            ),
            (
              McpHttpListenPolicy.Resume(2),
              Chunk("id: cursor\n\n", "", ""),
              Status.Ok,
              Some(McpHttpListenResult.Ended),
              3
            ),
            (
              McpHttpListenPolicy.Resume(2),
              Chunk(": no cursor\n\n"),
              Status.Ok,
              Some(McpHttpListenResult.Ended),
              1
            ),
            (
              McpHttpListenPolicy.Resume(2),
              Chunk("id: cursor\n\n", ": cleared\n\n"),
              Status.Ok,
              Some(McpHttpListenResult.Ended),
              2
            ),
            (
              McpHttpListenPolicy.Resume(2),
              Chunk(""),
              Status.MethodNotAllowed,
              Some(McpHttpListenResult.Unsupported),
              1
            ),
            (
              McpHttpListenPolicy.Resume(2),
              Chunk("id: cursor\n\n", "data: {\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}\n\n"),
              Status.Ok,
              None,
              2
            )
          )
        ): (policy, bodies, status, expected, count) =>
          ZIO.scoped:
            for
              gets <- Ref.make(0)
              transport <- readyListener(_ =>
                gets
                  .getAndUpdate(_ + 1)
                  .map(index =>
                    stream(rawEvents(bodies.lift(index).getOrElse(""))).copy(status = status)
                  )
              )
              invalid <- transport.listen(McpHttpListenPolicy.Resume(0)).exit
              before  <- gets.get
              result  <- transport.listen(policy).exit
              actual  <- gets.get
            yield invalid.isFailure && before == 0 && actual == count && (expected match
              case Some(value) => result == Exit.succeed(value)
              case None        => result.isFailure)
        calls <- Ref.make(0)
        custom = new McpStreamableHttpTransport:
          def listen: IO[AgentError, McpHttpListenResult] =
            calls.update(_ + 1).as(McpHttpListenResult.Ended)
          def terminateSession: IO[AgentError, McpHttpTermination] =
            ZIO.succeed(McpHttpTermination.NoSession)
          def send(@unused message: McpMessage): ZIO[Scope, AgentError, Unit] = ZIO.unit
          def incoming: ZStream[Any, AgentError, McpMessage]                  = ZStream.empty
        delegated      <- custom.listen(McpHttpListenPolicy.Single)
        unsupported    <- custom.listen(McpHttpListenPolicy.Resume(1)).exit
        invalid        <- custom.listen(McpHttpListenPolicy.Resume(0)).exit
        delegatedCount <- calls.get
      yield assertTrue(
        routed,
        cases.forall(identity),
        delegated == McpHttpListenResult.Ended,
        unsupported.isFailure,
        invalid.causeOption.exists(_.failureOption.exists {
          case _: AgentError.InvalidConfiguration => true
          case _                                  => false
        }),
        delegatedCount == 1
      )
    },
    test(
      "listener retries only isolated typed loss after joined cleanup and keeps cancellation ownership across GETs"
    ) {
      def projected(error: Throwable): AgentError = error match
        case domain: AgentError => domain
        case other =>
          AgentError.McpError(
            s"MCP HTTP GET body failed (${other.getClass.getName}): ${Option(other.getMessage).getOrElse("no message")}"
          )
      ZIO.scoped:
        for
          gets          <- Ref.make(0)
          cleaning      <- Promise.make[Nothing, Unit]
          release       <- Promise.make[Nothing, Unit]
          entered       <- Promise.make[Nothing, Unit]
          cancelled     <- Promise.make[Nothing, Unit]
          cancelRelease <- Promise.make[Nothing, Unit]
          loss = new java.nio.channels.ClosedChannelException
          transport <- readyListener(_ =>
            gets
              .getAndUpdate(_ + 1)
              .flatMap(index =>
                if index == 0 then
                  ZIO
                    .addFinalizer(cleaning.succeed(()).unit *> release.await)
                    .as(
                      stream(
                        rawEvents("id: cursor\n\n") ++ ZStream
                          .fromChannel(ZChannel.refailCause(Cause.fail(loss)))
                      )
                    )
                else
                  ZIO
                    .addFinalizer(cancelled.succeed(()).unit *> cancelRelease.await)
                    .as(stream(ZStream.fromZIO(entered.succeed(())).drain ++ ZStream.never))
              )
          )
          listening <- transport.listen(McpHttpListenPolicy.Resume(2)).forkScoped
          observed <- (for
            _         <- cleaning.await
            before    <- gets.get
            duplicate <- transport.listen.exit
            _         <- release.succeed(())
            _         <- entered.await
            stopping  <- listening.interrupt.forkScoped
            _         <- cancelled.await
            pending   <- stopping.poll
            during    <- transport.listen.exit
            _         <- cancelRelease.succeed(())
            stopped   <- stopping.join
            total     <- gets.get
          yield before == 1 && duplicate.isFailure && pending.isEmpty && during.isFailure &&
            stopped.isInterrupted && total == 2)
            .ensuring(release.succeed(()).unit *> cancelRelease.succeed(()).unit)
          excluded <- ZIO.foreach(
            Chunk(
              (
                Cause.fail(new java.io.IOException("generic body loss")),
                Exit.unit,
                "id: cursor\n\n",
                2,
                1
              ),
              (
                Cause.fail(new java.nio.channels.ClosedByInterruptException),
                Exit.unit,
                "id: cursor\n\n",
                2,
                1
              ),
              (
                Cause.fail(loss),
                Exit.die(new IllegalStateException("cleanup failure")),
                "id: cursor\n\n",
                2,
                1
              ),
              (Cause.fail(loss), Exit.unit, "id: unfinished\n", 2, 1),
              (Cause.fail(loss), Exit.unit, "id: cursor\n\n", 1, 2)
            )
          ): (cause, cleanup, prefix, budget, expectedAttempts) =>
            ZIO.scoped:
              for
                count <- Ref.make(0)
                port <- readyListener(_ =>
                  count.update(_ + 1) *> ZIO
                    .addFinalizer(cleanup)
                    .as(
                      stream(
                        rawEvents(prefix) ++
                          ZStream.fromChannel(ZChannel.refailCause(cause))
                      )
                    )
                )
                result   <- port.listen(McpHttpListenPolicy.Resume(budget)).exit
                attempts <- count.get
              yield attempts == expectedAttempts && result.causeOption.contains(
                cause.map(projected) ++ cleanup.causeOption.getOrElse(Cause.empty)
              )
        yield assertTrue(observed, excluded.forall(identity))
    },
    test(
      "typed connection loss resumes the committed cursor and captured session within one GET budget"
    ) {
      val request: McpMessage.Request = McpMessage.Request(McpRequestId.Number(51), "initialize")
      val acquisitionLoss =
        new io.netty.handler.codec.PrematureChannelClosureException("GET connection lost")
      for results <- ZIO.foreach(
          Chunk(
            (Some(new java.nio.channels.ClosedChannelException: Throwable), 0, 1),
            (
              Some(
                new io.netty.handler.codec.PrematureChannelClosureException(
                  "body closed"
                ): Throwable
              ),
              1,
              2
            ),
            (Some(new java.nio.channels.ClosedChannelException: Throwable), 3, 2),
            (None, 1, 2)
          )
        ): (bodyLoss, failedGets, budget) =>
          ZIO.scoped:
            for
              gets  <- Ref.make(Chunk.empty[Headers])
              posts <- Ref.make(Chunk.empty[McpMessage])
              http = scripted(r =>
                if r.method == Method.GET then
                  gets
                    .modify(previous => (previous.size, previous :+ r.headers))
                    .flatMap(index =>
                      if index < failedGets then ZIO.fail(acquisitionLoss)
                      else
                        ZIO.succeed(stream(event(McpMessage.Success(request.id, initialization))))
                    )
                else
                  decode(r).flatMap(message =>
                    posts
                      .update(_ :+ message)
                      .as(
                        stream(
                          rawEvents("id: committed\n") ++ event(
                            McpMessage.Notification("notifications/message")
                          ) ++
                            rawEvents("id: unfinished\n") ++ bodyLoss.fold(
                              ZStream.empty: ZStream[Any, Throwable, Byte]
                            )(error => ZStream.fromChannel(ZChannel.refailCause(Cause.fail(error))))
                        )
                          .addHeader("Mcp-Session-Id", "recovering")
                      )
                  )
              )
              transport <- McpStreamableHttpTransport.open(
                http,
                endpoint,
                continuation = McpHttpContinuation.OnPrematureEofOrConnectionLoss(budget)
              )
              result   <- transport.send(request).exit
              selected <- gets.get
              original <- posts.get
              received <- transport.incoming.take(2).runCollect.exit
            yield selected.size == math.min(failedGets + 1, budget) &&
              selected.forall(h =>
                h.get("Last-Event-ID").contains("committed") &&
                  h.get("Mcp-Session-Id").contains("recovering")
              ) && original == Chunk(request) &&
              (if failedGets < budget then
                 result.isSuccess && received == Exit.succeed(
                   Chunk(
                     McpMessage.Notification("notifications/message"),
                     McpMessage.Success(request.id, initialization)
                   )
                 )
               else
                 result.causeOption.exists(
                   _.failureOption.contains(
                     AgentError.McpError(
                       "MCP HTTP continuation GET failed (io.netty.handler.codec.PrematureChannelClosureException): GET connection lost"
                     )
                   )
                 ))
      yield assertTrue(results.forall(identity))
    },
    test(
      "connection-loss recovery excludes old policies, uncommitted cursors and non-network or cleanup causes"
    ) {
      val request: McpMessage.Request = McpMessage.Request(McpRequestId.Number(61), "tools/list")
      val closed                      = new java.nio.channels.ClosedChannelException
      val domain                      = AgentError.McpError("domain body failure")
      val defect                      = new IllegalStateException("body defect")
      val enabled                     = McpHttpContinuation.OnPrematureEofOrConnectionLoss(2)
      def projected(error: Throwable): AgentError = error match
        case value: AgentError => value
        case other =>
          AgentError.McpError(
            s"MCP HTTP response body failed (${other.getClass.getName}): ${Option(other.getMessage).getOrElse("no message")}"
          )
      val rows = Chunk(
        (McpHttpContinuation.Disabled, "id: cursor\n\n", Cause.fail(closed)),
        (McpHttpContinuation.OnPrematureEof(2), "id: cursor\n\n", Cause.fail(closed)),
        (enabled, "id: unfinished\n", Cause.fail(closed)),
        (enabled, "id: cursor\n\nid:\n\n", Cause.fail(closed)),
        (enabled, "id: cursor\n\n", Cause.fail(new java.nio.channels.ClosedByInterruptException)),
        (enabled, "id: cursor\n\n", Cause.fail(new java.io.IOException("ordinary truncation"))),
        (enabled, "id: cursor\n\n", Cause.fail(new java.net.SocketException("socket failure"))),
        (enabled, "id: cursor\n\n", Cause.fail(new javax.net.ssl.SSLException("TLS failure"))),
        (enabled, "id: cursor\n\n", Cause.fail(new IllegalArgumentException("programmer failure"))),
        (enabled, "id: cursor\n\n", Cause.fail(domain)),
        (enabled, "id: cursor\n\n", Cause.die(closed)),
        (enabled, "id: cursor\n\n", Cause.fail(closed) ++ Cause.die(defect)),
        (enabled, "id: cursor\n\n", Cause.fail(closed) ++ Cause.interrupt(FiberId.None))
      )
      for
        exclusions <- ZIO.foreach(rows): (policy, prefix, cause) =>
          ZIO.scoped:
            for
              gets <- Ref.make(0)
              http = scripted(r =>
                if r.method == Method.GET then gets.update(_ + 1).as(Response.ok)
                else
                  ZIO.succeed(
                    stream(rawEvents(prefix) ++ ZStream.fromChannel(ZChannel.refailCause(cause)))
                  )
              )
              transport <- McpStreamableHttpTransport.open(http, endpoint, continuation = policy)
              running   <- transport.send(request).forkScoped
              result    <- running.await
              retries   <- gets.get
            yield result.causeOption.contains(cause.map(projected)) && retries == 0
        cleanupResult <- ZIO.scoped:
          for
            entered <- Promise.make[Nothing, Unit]
            release <- Promise.make[Nothing, Unit]
            gets    <- Ref.make(0)
            http = scripted(r =>
              if r.method == Method.GET then gets.update(_ + 1).as(Response.ok)
              else
                ZIO
                  .addFinalizer(entered.succeed(()).unit *> release.await *> ZIO.die(defect))
                  .as(
                    stream(
                      rawEvents("id: cursor\n\n") ++ ZStream
                        .fromChannel(ZChannel.refailCause(Cause.fail(closed)))
                    )
                  )
            )
            transport <- McpStreamableHttpTransport.open(http, endpoint, continuation = enabled)
            running   <- transport.send(request).forkScoped
            observed <- (for
              _       <- entered.await
              pending <- running.poll
              before  <- gets.get
              _       <- release.succeed(())
              result  <- running.await
              after   <- gets.get
            yield pending.isEmpty && before == 0 && after == 0 &&
              result.causeOption.contains(Cause.fail(projected(closed)) ++ Cause.die(defect)))
              .ensuring(release.succeed(()).unit)
          yield observed
      yield assertTrue(exclusions.forall(identity), cleanupResult)
    },
    test(
      "explicit termination follows joined client closure, sends one authenticated DELETE and seals owner release"
    ) {
      ZIO
        .scoped {
          for
            status  <- Ref.make[Status](Status.NoContent)
            deletes <- Ref.make(Chunk.empty[Headers])
            port <- Server.install(
              Routes(
                Method.POST / "mcp" -> handler { (request: Request) =>
                  decode(request)
                    .flatMap:
                      case McpMessage.Request(id, "initialize", _) =>
                        json(McpMessage.Success(id, initialization))
                          .map(_.addHeader("Mcp-Session-Id", "terminate"))
                      case _ => ZIO.succeed(Response.status(Status.Accepted))
                    .orElseSucceed(Response.status(Status.InternalServerError))
                },
                Method.DELETE / "mcp" -> handler { (request: Request) =>
                  deletes.update(_ :+ request.headers) *> status.get.map(Response.status(_))
                }
              )
            )
            http <- ZIO.service[Client]
            endpoint = s"http://127.0.0.1:$port/mcp"
            outcomes <- ZIO.foreach(
              Chunk(
                Status.NoContent           -> Some(McpHttpTermination.Deleted),
                Status.MethodNotAllowed    -> Some(McpHttpTermination.Unsupported),
                Status.NotFound            -> Some(McpHttpTermination.AlreadyExpired),
                Status.InternalServerError -> None
              )
            ): (responseStatus, expected) =>
              for
                _ <- status.set(responseStatus)
                saved <- ZIO.scoped:
                  for
                    transport <- McpStreamableHttpTransport
                      .open(http, endpoint, Headers("Authorization", "Bearer deletion"))
                    initialized <- McpClient.open(transport, McpImplementation("client", "1"))
                    _           <- initialized.close
                    results     <- ZIO.collectAllPar(Chunk.fill(2)(transport.terminateSession.exit))
                    rejected    <- transport.send(McpMessage.Notification("late")).exit
                  yield (transport, results, rejected)
                replay <- saved._1.terminateSession.exit
              yield saved._2.forall(exit =>
                expected match
                  case Some(value) => exit == Exit.succeed(value)
                  case None        => exit.isFailure
              ) && saved._2.headOption.contains(replay) && saved._3.isFailure
            empty <- ZIO.scoped(
              McpStreamableHttpTransport.open(http, endpoint).flatMap(_.terminateSession)
            )
            closed   <- ZIO.scoped(McpStreamableHttpTransport.open(http, endpoint))
            rejected <- closed.terminateSession.exit
            seen     <- deletes.get
          yield assertTrue(
            outcomes.forall(identity),
            empty == McpHttpTermination.NoSession,
            rejected.isFailure,
            seen.size == 4,
            seen.forall(h =>
              h.get("Mcp-Session-Id").contains("terminate") &&
                h.get("Authorization").contains("Bearer deletion") &&
                h.get("MCP-Protocol-Version").contains(McpInitialization.protocolVersion)
            )
          )
        }
        .provide(Client.default, Server.defaultWithPort(0))
    } @@ TestAspect.withLiveClock,
    test(
      "termination cancellation joins cleanup and replays its full outcome while follower cancellation stays local"
    ) {
      ZIO.scoped:
        for
          entered <- Promise.make[Nothing, Unit]
          cleanup <- Promise.make[Nothing, Unit]
          release <- Promise.make[Nothing, Unit]
          count   <- Ref.make(0)
          defect = new IllegalStateException("DELETE cleanup")
          http = scripted(request =>
            if request.method == Method.DELETE then
              count.update(_ + 1) *> ZIO.addFinalizer(
                cleanup.succeed(()).unit *> release.await *> ZIO.die(defect)
              ) *>
                entered.succeed(()) *> ZIO.never
            else
              decode(request).flatMap:
                case McpMessage.Request(id, "initialize", _) =>
                  json(McpMessage.Success(id, initialization))
                    .map(_.addHeader("Mcp-Session-Id", "held"))
                case _ => ZIO.succeed(Response.status(Status.Accepted))
          )
          transport <- McpStreamableHttpTransport.open(http, endpoint)
          _         <- transport.send(McpMessage.Request(McpRequestId.Number(1), "initialize"))
          leader    <- transport.terminateSession.forkScoped
          observed <- (for
            _            <- entered.await
            follower     <- transport.terminateSession.forkScoped
            _            <- (ZIO.yieldNow *> follower.status).repeatUntil(_.isSuspended)
            followerExit <- follower.interrupt
            stillRunning <- leader.poll
            rival        <- transport.terminateSession.forkScoped
            _            <- (ZIO.yieldNow *> rival.status).repeatUntil(_.isSuspended)
            cancelling   <- leader.interrupt.forkScoped
            _            <- cleanup.await
            unfinished   <- cancelling.poll
            _            <- release.succeed(())
            leaderExit   <- cancelling.join
            rivalExit    <- rival.await
            replay       <- transport.terminateSession.exit
          yield (followerExit, stillRunning, unfinished, leaderExit, rivalExit, replay))
            .ensuring(release.succeed(()).unit)
          sent <- count.get
        yield assertTrue(
          observed._1.isInterrupted,
          observed._2.isEmpty,
          observed._3.isEmpty,
          observed._4.isInterrupted,
          observed._4.causeOption.exists(_.defects == List(defect)),
          observed._5 == observed._4,
          observed._6 == observed._4,
          sent == 1
        )
    },
    test(
      "same-send continuation carries UTF-8 cursor bytes and captured initialization session without replay"
    ) {
      ZIO
        .scoped {
          for
            original <- Promise.make[Nothing, McpRequestId]
            answered <- Promise.make[Nothing, Unit]
            posts    <- Ref.make(Chunk.empty[McpMessage])
            gets     <- Ref.make(Chunk.empty[Headers])
            cursor = "caf\u00e9-\uD83D\uDE80"
            port <- Server.install(
              Routes(
                Method.POST / "mcp" -> handler { (request: Request) =>
                  decode(request)
                    .flatMap(message =>
                      posts.update(_ :+ message) *> (message match
                        case McpMessage.Request(id, "initialize", _) =>
                          original
                            .succeed(id)
                            .as(
                              stream(rawEvents(s"id: $cursor\n\n"))
                                .addHeader("Mcp-Session-Id", "resuming")
                            )
                        case McpMessage.Success(id, _) if id == pingId =>
                          answered.succeed(()).as(Response.status(Status.Accepted))
                        case McpMessage.Request(id, "tools/list", _) =>
                          json(McpMessage.Success(id, emptyTools))
                        case _ => ZIO.succeed(Response.status(Status.Accepted)))
                    )
                    .orElseSucceed(Response.status(Status.InternalServerError))
                },
                Method.GET / "mcp" -> handler { (request: Request) =>
                  gets.update(_ :+ request.headers) *> original.await.map(id =>
                    stream(
                      (event(McpMessage.Request(pingId, "ping")) ++
                        ZStream.fromZIO(answered.await).drain ++ event(
                          McpMessage.Success(id, initialization)
                        )).rechunk(1)
                    )
                  )
                }
              )
            )
            http <- ZIO.service[Client]
            transport <- McpStreamableHttpTransport.open(
              http,
              s"http://127.0.0.1:$port/mcp",
              Headers("Authorization", "Bearer continuation"),
              McpHttpLimits(maxConcurrentRequests = 1),
              McpHttpContinuation.OnPrematureEof(1)
            )
            initialized <- McpClient.open(transport, McpImplementation("client", "1"))
            page        <- initialized.listToolsPage()
            seen        <- gets.get
            sent        <- posts.get
            expectedBytes <- ZIO.succeed(
              Chunk.fromArray(cursor.getBytes(java.nio.charset.StandardCharsets.UTF_8))
            )
            receivedBytes <- ZIO.succeed(
              seen.map(
                _.get("Last-Event-ID").map(value =>
                  Chunk.fromArray(value.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1))
                )
              )
            )
          yield assertTrue(
            page.tools.isEmpty,
            seen.size == 1,
            receivedBytes == Chunk(Some(expectedBytes)),
            seen.forall(h =>
              h.get("Accept").contains("text/event-stream") &&
                h.get("Mcp-Session-Id").contains("resuming") &&
                h.get("MCP-Protocol-Version").contains(McpInitialization.protocolVersion) &&
                h.get("Authorization").contains("Bearer continuation")
            ),
            sent.count {
              case McpMessage.Request(_, "initialize", _) => true; case _ => false
            } == 1,
            sent.exists { case McpMessage.Success(id, _) => id == pingId; case _ => false }
          )
        }
        .provide(Client.default, Server.defaultWithPort(0))
    } @@ TestAspect.withLiveClock,
    test(
      "continuation is opt-in and rejects missing, reset, unsafe or exhausted cursors and invalid GET replies"
    ) {
      val request = McpMessage.Request(McpRequestId.Number(42), "tools/list")
      val rows = Chunk(
        (
          McpHttpContinuation.Disabled,
          "id: next\n\n",
          Status.Ok,
          "text/event-stream",
          "id: later\n\n",
          0
        ),
        (
          McpHttpContinuation.OnPrematureEof(1),
          ": no cursor\n\n",
          Status.Ok,
          "text/event-stream",
          "",
          0
        ),
        (
          McpHttpContinuation.OnPrematureEof(1),
          "id: next\n\nid:\n\n",
          Status.Ok,
          "text/event-stream",
          "",
          0
        ),
        (
          McpHttpContinuation.OnPrematureEof(1),
          "id:  unsafe\n\n",
          Status.Ok,
          "text/event-stream",
          "",
          0
        ),
        (
          McpHttpContinuation.OnPrematureEof(1),
          "id: unsafe\u0001\n\n",
          Status.Ok,
          "text/event-stream",
          "",
          0
        ),
        (
          McpHttpContinuation.OnPrematureEof(2),
          "id: next\n\n",
          Status.Ok,
          "text/event-stream",
          "id: later\n\n",
          2
        ),
        (
          McpHttpContinuation.OnPrematureEof(2),
          "id: next\n\n",
          Status.Ok,
          "text/event-stream",
          "",
          2
        ),
        (
          McpHttpContinuation.OnPrematureEof(2),
          "id: next\n\n",
          Status.Ok,
          "text/event-stream",
          "id:\n\n",
          1
        ),
        (
          McpHttpContinuation.OnPrematureEof(2),
          "id: next\n\n",
          Status.Ok,
          "text/event-stream",
          ": no inherited cursor\n\n",
          1
        ),
        (
          McpHttpContinuation.OnPrematureEof(1),
          "id: next\n\n",
          Status.MethodNotAllowed,
          "text/event-stream",
          "",
          1
        ),
        (
          McpHttpContinuation.OnPrematureEof(1),
          "id: next\n\n",
          Status.Ok,
          "application/json",
          "{}",
          1
        ),
        (
          McpHttpContinuation.OnPrematureEof(1),
          "id: next\n\n",
          Status.Ok,
          "text/event-stream",
          "data: {\"jsonrpc\":\"2.0\",\"id\":99,\"result\":{}}\n\n",
          1
        )
      )
      for
        results <- ZIO.foreach(rows): (policy, first, status, contentType, next, expected) =>
          ZIO.scoped:
            for
              calls <- Ref.make(Chunk.empty[Method])
              http = scripted(r =>
                calls
                  .update(_ :+ r.method)
                  .as(
                    if r.method == Method.POST then stream(rawEvents(first))
                    else
                      Response(
                        status = status,
                        headers = Headers("Content-Type", contentType),
                        body = Body.fromString(next)
                      )
                  )
              )
              transport <- McpStreamableHttpTransport.open(http, endpoint, continuation = policy)
              exit      <- transport.send(request).exit
              seen      <- calls.get
            yield exit.isFailure && seen.count(_ == Method.POST) == 1 && seen.count(
              _ == Method.GET
            ) == expected
        called <- Ref.make(false)
        invalid <- ZIO.foreach(
          Chunk(
            McpHttpContinuation.OnPrematureEof(0),
            McpHttpContinuation.OnPrematureEofOrConnectionLoss(0)
          )
        )(policy =>
          ZIO
            .scoped(
              McpStreamableHttpTransport.open(
                scripted(_ => called.set(true).as(Response.ok)),
                endpoint,
                continuation = policy
              )
            )
            .exit
        )
        acquired <- called.get
        isolated <- ZIO.scoped:
          for
            entered  <- Promise.make[Nothing, Unit]
            release  <- Promise.make[Nothing, Unit]
            sessions <- Ref.make(Chunk.empty[Option[String]])
            posts    <- Ref.make(Chunk.empty[McpMessage])
            http = scripted(r =>
              if r.method == Method.GET then
                sessions
                  .update(_ :+ r.headers.get("Mcp-Session-Id"))
                  .as(stream(event(McpMessage.Success(McpRequestId.Number(42), emptyTools))))
              else
                decode(r).flatMap(message =>
                  posts.update(_ :+ message) *> (message match
                    case McpMessage.Request(_, "tools/list", _) =>
                      entered.succeed(()) *> release.await.as(stream(rawEvents("id: original\n\n")))
                    case McpMessage.Request(id, "initialize", _) =>
                      json(McpMessage.Success(id, initialization))
                        .map(_.addHeader("Mcp-Session-Id", "new-session"))
                    case _ => ZIO.succeed(Response.status(Status.Accepted)))
                )
            )
            transport <- McpStreamableHttpTransport.open(
              http,
              endpoint,
              continuation = McpHttpContinuation.OnPrematureEof(1)
            )
            original <- transport.send(request).forkScoped
            observed <- (for
              _        <- entered.await
              _        <- transport.send(McpMessage.Request(McpRequestId.Number(43), "initialize"))
              _        <- release.succeed(())
              _        <- original.join
              selected <- sessions.get
              sent     <- posts.get
            yield (selected, sent.count(_ == request))).ensuring(release.succeed(()).unit)
          yield observed
      yield assertTrue(
        results.forall(identity),
        invalid.forall(_.isFailure),
        !acquired,
        isolated._1 == Chunk(None),
        isolated._2 == 1
      )
    },
    test(
      "continuation waits for successful response cleanup and never retries failures or caller cancellation"
    ) {
      ZIO.scoped:
        for
          cleanup <- Promise.make[Nothing, Unit]
          release <- Promise.make[Nothing, Unit]
          gets    <- Ref.make(0)
          request: McpMessage.Request = McpMessage.Request(McpRequestId.Number(7), "tools/list")
          http = scripted(r =>
            if r.method == Method.GET then
              gets.update(_ + 1).as(stream(event(McpMessage.Success(request.id, emptyTools))))
            else
              ZIO
                .addFinalizer(cleanup.succeed(()).unit *> release.await)
                .as(stream(rawEvents("id: next\n\n")))
          )
          transport <- McpStreamableHttpTransport.open(
            http,
            endpoint,
            continuation = McpHttpContinuation.OnPrematureEof(1)
          )
          pending <- transport.send(request).forkScoped
          joined <- (for
            _      <- cleanup.await
            before <- gets.get
            _      <- release.succeed(())
            _      <- pending.join
          yield before).ensuring(release.succeed(()).unit)
          after <- gets.get
          defect = new IllegalStateException("response cleanup")
          cause  = Cause.fail(AgentError.McpError("response body failed")) ++ Cause.die(defect)
          failedGets <- Ref.make(0)
          failureHttp = scripted(r =>
            if r.method == Method.GET then failedGets.update(_ + 1).as(Response.ok)
            else
              ZIO.succeed(
                stream(
                  rawEvents("id: next\n\n") ++ ZStream.fromChannel(ZChannel.refailCause(cause))
                )
              )
          )
          failureTransport <- McpStreamableHttpTransport.open(
            failureHttp,
            endpoint,
            continuation = McpHttpContinuation.OnPrematureEof(1)
          )
          failure       <- failureTransport.send(request).exit
          retried       <- failedGets.get
          cancelEntered <- Promise.make[Nothing, Unit]
          cancelRelease <- Promise.make[Nothing, Unit]
          bodyEntered   <- Promise.make[Nothing, Unit]
          cancelledGets <- Ref.make(0)
          cancelHttp = scripted(r =>
            if r.method == Method.GET then
              cancelledGets.update(_ + 1) *> ZIO
                .addFinalizer(
                  cancelEntered.succeed(()).unit *> cancelRelease.await *> ZIO.die(defect)
                )
                .as(stream(ZStream.fromZIO(bodyEntered.succeed(())).drain ++ ZStream.never))
            else ZIO.succeed(stream(rawEvents("id: next\n\n")))
          )
          cancelTransport <- McpStreamableHttpTransport.open(
            cancelHttp,
            endpoint,
            continuation = McpHttpContinuation.OnPrematureEof(1)
          )
          running <- cancelTransport.send(request).forkScoped
          stopped <- (for
            _          <- bodyEntered.await
            cancelling <- running.interrupt.forkScoped
            _          <- cancelEntered.await
            unfinished <- cancelling.poll
            _          <- cancelRelease.succeed(())
            exit       <- cancelling.join
          yield (unfinished, exit)).ensuring(cancelRelease.succeed(()).unit)
          cancelledRetries <- cancelledGets.get
        yield assertTrue(
          joined == 0,
          after == 1,
          failure.causeOption.contains(cause),
          retried == 0,
          stopped._1.isEmpty,
          stopped._2.isInterrupted,
          stopped._2.causeOption.exists(_.defects == List(defect)),
          cancelledRetries == 1
        )
    },
    test("explicit GET routes server traffic while the only request permit is occupied") {
      ZIO
        .scoped {
          for
            gets      <- Ref.make(Chunk.empty[Headers])
            requested <- Promise.make[Nothing, Unit]
            answered  <- Promise.make[Nothing, Unit]
            port <- Server.install(
              Routes(
                Method.GET / "mcp" -> handler { (request: Request) =>
                  gets
                    .update(_ :+ request.headers)
                    .as(
                      stream(
                        (event(
                          McpMessage.Notification("notifications/message", Some(Json.Obj()))
                        ) ++
                          event(McpMessage.Request(pingId, "ping")) ++
                          ZStream.fromZIO(answered.await).drain).rechunk(1)
                      )
                    )
                },
                Method.POST / "mcp" -> handler { (request: Request) =>
                  decode(request)
                    .flatMap:
                      case McpMessage.Request(id, "initialize", _) =>
                        json(McpMessage.Success(id, initialization))
                          .map(_.addHeader("Mcp-Session-Id", "listening"))
                      case McpMessage.Request(id, "tools/list", _) =>
                        requested.succeed(()) *> answered.await *> json(
                          McpMessage.Success(id, emptyTools)
                        )
                      case McpMessage.Success(id, _) if id == pingId =>
                        answered.succeed(()).as(Response.status(Status.Accepted))
                      case _ => ZIO.succeed(Response.status(Status.Accepted))
                    .orElseSucceed(Response.status(Status.InternalServerError))
                }
              )
            )
            http <- ZIO.service[Client]
            transport <- McpStreamableHttpTransport.open(
              http,
              s"http://127.0.0.1:$port/mcp",
              Headers("Authorization", "Bearer test-token"),
              McpHttpLimits(maxConcurrentRequests = 1)
            )
            initialized <- McpClient.open(transport, McpImplementation("client", "1"))
            before      <- gets.get
            call        <- initialized.listToolsPage().forkScoped
            _           <- requested.await
            listening   <- transport.listen(McpHttpListenPolicy.Resume(1)).forkScoped
            result      <- listening.join
            page        <- call.join
            seen        <- gets.get
          yield assertTrue(
            before.isEmpty,
            result == McpHttpListenResult.Ended,
            page.tools.isEmpty,
            seen.size == 1,
            seen.forall(headers =>
              headers.get("Accept").contains("text/event-stream") &&
                headers.get("Mcp-Session-Id").contains("listening") &&
                headers.get("MCP-Protocol-Version").contains(McpInitialization.protocolVersion) &&
                headers.get("Authorization").contains("Bearer test-token") &&
                headers.get("Last-Event-ID").isEmpty
            )
          )
        }
        .provide(Client.default, Server.defaultWithPort(0))
    } @@ TestAspect.withLiveClock,
    test(
      "listening requires completed initialization cleanup and EOF or 405 permits a fresh explicit GET"
    ) {
      ZIO.scoped:
        for
          gets    <- Ref.make(0)
          cleanup <- Promise.make[Nothing, Unit]
          release <- Promise.make[Nothing, Unit]
          transport <- McpStreamableHttpTransport.open(
            scripted(request =>
              if request.method == Method.GET then
                gets
                  .updateAndGet(_ + 1)
                  .map:
                    case 1 => Response.status(Status.MethodNotAllowed)
                    case _ => stream(ZStream.empty)
              else
                decode(request).flatMap:
                  case McpMessage.Request(id, "initialize", _) =>
                    json(McpMessage.Success(id, initialization))
                  case McpMessage.Notification("notifications/initialized", _) =>
                    ZIO
                      .addFinalizer(cleanup.succeed(()).unit *> release.await)
                      .as(Response.status(Status.Accepted))
                  case McpMessage.Request(id, _, _) => json(McpMessage.Success(id, emptyTools))
                  case _                            => ZIO.succeed(Response.status(Status.Accepted))
            ),
            endpoint
          )
          early <- transport.listen.exit
          result <- ZIO.scoped {
            (for
              opening     <- McpClient.open(transport, McpImplementation("client", "1")).forkScoped
              _           <- cleanup.await
              beforeAck   <- transport.listen.exit
              untouched   <- gets.get
              _           <- release.succeed(())
              initialized <- opening.join
              unsupported <- transport.listen
              afterUnsupported <- initialized.listToolsPage()
              ended            <- transport.listen
              afterEnd         <- initialized.listToolsPage()
              repeated         <- transport.listen
              count            <- gets.get
              invalidHeaders <- McpStreamableHttpTransport
                .open(
                  server(request => json(McpMessage.Success(request.id, emptyTools))),
                  endpoint,
                  Headers("Last-Event-ID", "cursor")
                )
                .exit
            yield assertTrue(
              early.isFailure,
              beforeAck.isFailure,
              untouched == 0,
              unsupported == McpHttpListenResult.Unsupported,
              ended == McpHttpListenResult.Ended,
              repeated == McpHttpListenResult.Ended,
              afterUnsupported.tools.isEmpty,
              afterEnd.tools.isEmpty,
              count == 3,
              invalidHeaders.isFailure
            )).ensuring(release.succeed(()).unit)
          }
        yield result
    },
    test(
      "listener admission and terminal signals preserve joined cleanup and full failure causes"
    ) {
      val defect = new IllegalStateException("GET cleanup failed")
      val domain = AgentError.McpError("GET body failed")
      val cause  = Cause.fail(domain) ++ Cause.die(defect)
      val cancellation = ZIO.scoped:
        for
          entered  <- Promise.make[Nothing, Unit]
          cleaning <- Promise.make[Nothing, Unit]
          release  <- Promise.make[Nothing, Unit]
          attempts <- Ref.make(0)
          transport <- McpStreamableHttpTransport.open(
            scripted(request =>
              if request.method == Method.GET then
                attempts
                  .updateAndGet(_ + 1)
                  .flatMap:
                    case 1 =>
                      ZIO
                        .addFinalizer(cleaning.succeed(()).unit *> release.await)
                        .as(stream(ZStream.fromZIO(entered.succeed(()) *> ZIO.never)))
                    case _ => ZIO.succeed(stream(ZStream.empty))
              else
                decode(request).flatMap:
                  case McpMessage.Request(id, "initialize", _) =>
                    json(McpMessage.Success(id, initialization))
                  case McpMessage.Request(id, _, _) => json(McpMessage.Success(id, emptyTools))
                  case _                            => ZIO.succeed(Response.status(Status.Accepted))
            ),
            endpoint
          )
          initialized <- McpClient.open(transport, McpImplementation("client", "1"))
          result <- ZIO.scoped {
            (for
              listening     <- transport.listen.forkScoped
              _             <- entered.await
              duplicate     <- transport.listen.exit
              stopping      <- listening.interrupt.forkScoped
              _             <- cleaning.await
              pending       <- stopping.poll
              duringCleanup <- transport.listen.exit
              _             <- release.succeed(())
              stopped       <- stopping.join
              later         <- transport.listen
              page          <- initialized.listToolsPage()
              count         <- attempts.get
            yield assertTrue(
              duplicate.isFailure,
              duringCleanup.isFailure,
              pending.isEmpty,
              stopped.isInterrupted,
              later == McpHttpListenResult.Ended,
              page.tools.isEmpty,
              count == 2
            )).ensuring(release.succeed(()).unit)
          }
        yield result
      val failures = ZIO
        .foreach(
          Chunk[(Response, Option[Cause[AgentError]])](
            stream(event(McpMessage.Success(McpRequestId.Number(42), Json.Obj()))) -> None,
            stream(
              ZStream.fromIterable(
                "data: invalid\n\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)
              )
            )                                                                   -> None,
            stream(ZStream.fromChannel(zio.stream.ZChannel.refailCause(cause))) -> Some(cause),
            Response.status(Status.NotFound)                                    -> None
          )
        ) { case (response, expected) =>
          ZIO.scoped:
            for
              transport <- McpStreamableHttpTransport.open(
                scripted(request =>
                  if request.method == Method.GET then ZIO.succeed(response)
                  else
                    decode(request).flatMap:
                      case McpMessage.Request(id, "initialize", _) =>
                        json(McpMessage.Success(id, initialization))
                          .map(_.addHeader("Mcp-Session-Id", "session"))
                      case _ => ZIO.succeed(Response.status(Status.Accepted))
                ),
                endpoint
              )
              _      <- transport.send(McpMessage.Request(McpRequestId.Number(1), "initialize"))
              _      <- transport.send(McpInitialization.initialized)
              result <- transport.listen.exit
              later <- transport.send(McpMessage.Request(McpRequestId.Number(2), "tools/list")).exit
            yield assertTrue(
              result.isFailure,
              later.isFailure,
              expected.forall(value => result.causeOption.contains(value))
            )
        }
        .map(_.foldLeft(assertTrue(true))(_ && _))
      val independentFailures = ZIO.scoped:
        val failure =
          AgentError.McpError("MCP HTTP session expired (404); open a fresh initialized client")
        for
          entered           <- Promise.make[Nothing, Unit]
          interruptObserved <- Promise.make[Nothing, Unit]
          release           <- Promise.make[Nothing, Unit]
          transport <- McpStreamableHttpTransport.open(
            scripted(request =>
              if request.method == Method.GET then
                ZIO.uninterruptible(
                  entered.succeed(()) *>
                    (ZIO.yieldNow *> ZIO.descriptorWith(descriptor =>
                      ZIO.succeed(descriptor.interrupters.nonEmpty)
                    )).repeatUntil(identity) *>
                    interruptObserved.succeed(()) *> release.await *> ZIO.fail(failure)
                )
              else
                decode(request).flatMap:
                  case McpMessage.Request(id, "initialize", _) =>
                    json(McpMessage.Success(id, initialization))
                      .map(_.addHeader("Mcp-Session-Id", "expired"))
                  case _: McpMessage.Request => ZIO.succeed(Response.status(Status.NotFound))
                  case _                     => ZIO.succeed(Response.status(Status.Accepted))
            ),
            endpoint
          )
          initialized <- McpClient.open(transport, McpImplementation("client", "1"))
          result <- ZIO.scoped {
            (for
              listening <- transport.listen.forkScoped
              _         <- entered.await
              terminal  <- initialized.listToolsPage().exit
              _         <- interruptObserved.await
              pending   <- listening.poll
              _         <- release.succeed(())
              exit      <- listening.await
            yield assertTrue(
              terminal.isFailure,
              pending.isEmpty,
              exit.causeOption.exists(_.failures == List(failure, failure))
            )).ensuring(release.succeed(()).unit)
          }
        yield result
      cancellation.zipWith(failures)(_ && _).zipWith(independentFailures)(_ && _)
    },
    test(
      "loopback initialization preserves session headers and answers server ping while request admission is full"
    ) {
      ZIO
        .scoped:
          for
            observed     <- Ref.make(Chunk.empty[(Headers, McpMessage)])
            pingAnswered <- Promise.make[Nothing, Unit]
            port <- Server.install(Routes(Method.POST / "mcp" -> handler { (request: Request) =>
              decode(request)
                .flatMap(message =>
                  observed.update(_ :+ (request.headers -> message)) *> (message match
                    case McpMessage.Request(id, "initialize", _) =>
                      ZIO.succeed(
                        stream(
                          (event(McpMessage.Request(pingId, "ping")) ++
                            ZStream.fromZIO(pingAnswered.await).drain ++
                            event(McpMessage.Success(id, initialization))).rechunk(1)
                        ).addHeader("Mcp-Session-Id", "session-1")
                      )
                    case McpMessage.Request(id, "tools/list", _) =>
                      json(McpMessage.Success(id, emptyTools))
                    case McpMessage.Success(id, _) if id == pingId =>
                      pingAnswered.succeed(()).as(Response.status(Status.Accepted))
                    case McpMessage.Request(id, "tools/call", _) =>
                      json(
                        McpMessage.Success(
                          id,
                          Json.Obj(
                            "content" -> Json
                              .Arr(Json.Obj("type" -> Json.Str("text"), "text" -> Json.Str("echo")))
                          )
                        )
                      )
                    case _: McpMessage.Notification => ZIO.succeed(Response.status(Status.Accepted))
                    case _ => ZIO.succeed(Response.status(Status.BadRequest)))
                )
                .orElseSucceed(Response.status(Status.InternalServerError))
            }))
            http <- ZIO.service[Client]
            transport <- McpStreamableHttpTransport.open(
              http,
              s"http://127.0.0.1:$port/mcp",
              Headers("Authorization", "Bearer test-token"),
              McpHttpLimits(maxConcurrentRequests = 1)
            )
            initialized <- McpClient.open(transport, McpImplementation("client", "1"))
            page        <- initialized.listToolsPage()
            outputs <- ZIO.foreachPar(Chunk("one", "two"))(name =>
              initialized.callTool(name, Some(Json.Obj()))
            )
            sent     <- observed.get
            answered <- pingAnswered.isDone
            requestIds = sent.collect { case (_, McpMessage.Request(id, _, _)) => id }
          yield assertTrue(
            page.tools.isEmpty,
            answered,
            outputs.forall(_.content.map(_.block) == Chunk(McpContentBlock.Text("echo"))),
            sent.headOption.exists(_._1.get("Mcp-Session-Id").isEmpty),
            sent.drop(1).forall(_._1.get("Mcp-Session-Id").contains("session-1")),
            sent
              .drop(1)
              .forall(_._1.get("MCP-Protocol-Version").contains(McpInitialization.protocolVersion)),
            sent.forall(_._1.get("Authorization").contains("Bearer test-token")),
            sent.forall(
              _._1
                .get("Accept")
                .exists(value =>
                  value.contains("application/json") && value.contains("text/event-stream")
                )
            ),
            requestIds.distinct.size == 4
          )
        .provide(Client.default, Server.defaultWithPort(0))
    } @@ TestAspect.withLiveClock,
    test(
      "invalid configuration sends nothing and response failures preserve operation boundaries without retries"
    ) {
      ZIO.scoped:
        for
          sends <- Ref.make(0)
          http = scripted(_ => sends.update(_ + 1).as(Response.status(Status.Accepted)))
          invalid <- ZIO.foreach(
            Chunk(
              McpStreamableHttpTransport.open(http, "file:///tmp/mcp"),
              McpStreamableHttpTransport.open(http, "http://user@localhost/mcp"),
              McpStreamableHttpTransport.open(http, endpoint + "#fragment"),
              McpStreamableHttpTransport
                .open(http, endpoint, Headers("Authorization", "bad\r\nheader")),
              McpStreamableHttpTransport
                .open(http, endpoint, Headers("Mcp-Session-Id", "override")),
              McpStreamableHttpTransport
                .open(http, endpoint, limits = McpHttpLimits(incomingCapacity = 0)),
              McpStreamableHttpTransport.open(
                http,
                endpoint,
                limits = McpHttpLimits(maxConcurrentRequests = 0)
              )
            )
          )(_.exit)
          untouched <- sends.get
          badSessions <- ZIO.foreach(
            Chunk(
              Headers("Mcp-Session-Id", ""),
              Headers("Mcp-Session-Id", "with space"),
              Headers("Mcp-Session-Id", "one") ++ Headers("Mcp-Session-Id", "two")
            )
          )(headers =>
            ZIO.scoped(
              client(
                server(request => json(McpMessage.Success(request.id, emptyTools)), headers)
              ).exit
            )
          )
          byteLimits <- ZIO.foreach(
            Chunk(
              McpHttpLimits(requestBytes = 1),
              McpHttpLimits(jsonResponseBytes = 1),
              McpHttpLimits(sseResponseBytes = 1),
              McpHttpLimits(sseEventBytes = 1)
            )
          )(limits =>
            ZIO
              .scoped(
                client(
                  server(request =>
                    ZIO.succeed(stream(event(McpMessage.Success(request.id, emptyTools))))
                  ),
                  limits
                ).flatMap(_.listToolsPage())
              )
              .exit
          )
          invalidResponses <- ZIO.foreach(
            Chunk[McpMessage.Request => ZIO[Scope, Throwable, Response]](
              _ => ZIO.succeed(Response.status(Status.ServiceUnavailable)),
              request =>
                json(McpMessage.Success(request.id, emptyTools))
                  .map(_.setHeaders(Headers("Content-Type", "text/plain"))),
              request =>
                json(McpMessage.Success(request.id, emptyTools))
                  .map(_.addHeader("Mcp-Session-Id", "replacement")),
              _ => json(McpMessage.Success(McpRequestId.Text("wrong"), emptyTools)),
              _ => ZIO.succeed(stream(ZStream.empty)),
              _ =>
                ZIO.succeed(
                  Response(
                    status = Status.Ok,
                    headers = Headers("Content-Type", "application/json"),
                    body = Body.fromString("{}")
                  )
                )
            )
          )(respond =>
            ZIO.scoped(
              client(server(respond, Headers("Mcp-Session-Id", "original")))
                .flatMap(_.listToolsPage())
                .exit
            )
          )
          calls <- Ref.make(0)
          expired <- client(
            server(
              _ => calls.update(_ + 1).as(Response.status(Status.NotFound)),
              Headers("Mcp-Session-Id", "expiring")
            )
          )
          first <- expired.listToolsPage().exit
          later <- expired.listToolsPage().exit
          count <- calls.get
          badNotification <- McpStreamableHttpTransport
            .open(
              scripted(request =>
                decode(request).flatMap:
                  case McpMessage.Request(id, "initialize", _) =>
                    json(McpMessage.Success(id, initialization))
                  case _ =>
                    ZIO.succeed(
                      Response(status = Status.Accepted, body = Body.fromString("unexpected"))
                    )
              ),
              endpoint
            )
            .flatMap(McpClient.open(_, McpImplementation("client", "1")))
            .exit
          protectedPort <- McpStreamableHttpTransport.open(
            server(request => json(McpMessage.Success(request.id, emptyTools))),
            endpoint
          )
          protectedClient <- McpClient.open(protectedPort, McpImplementation("client", "1"))
          duplicateInitialization <- protectedPort
            .send(McpMessage.Request(McpRequestId.Text("duplicate"), "initialize"))
            .exit
          afterDuplicate <- protectedClient.listToolsPage().exit
          admitted       <- Promise.make[Nothing, Unit]
          finish         <- Promise.make[Nothing, Unit]
          queuedPort <- McpStreamableHttpTransport.open(
            scripted(request =>
              decode(request).flatMap:
                case McpMessage.Request(id, _, _) =>
                  admitted.succeed(()) *> finish.await *> json(
                    McpMessage.Success(id, initialization)
                  )
                case _ => ZIO.succeed(Response.status(Status.Accepted))
            ),
            endpoint,
            limits = McpHttpLimits(maxConcurrentRequests = 1)
          )
          firstInitialization <- queuedPort
            .send(McpMessage.Request(McpRequestId.Number(1), "initialize"))
            .forkScoped
          _ <- admitted.await
          queuedInitialization <- queuedPort
            .send(McpMessage.Request(McpRequestId.Number(2), "initialize"))
            .forkScoped
          _              <- (ZIO.yieldNow *> queuedInitialization.status).repeatUntil(_.isSuspended)
          cancelledQueue <- queuedInitialization.interrupt
          _              <- finish.succeed(())
          admittedResult <- firstInitialization.await
        yield assertTrue(
          invalid.forall(_.isFailure),
          untouched == 0,
          badSessions.forall(_.isFailure),
          byteLimits.forall(_.isFailure),
          invalidResponses.forall(_.isFailure),
          first.isFailure,
          later.isFailure,
          count == 1,
          badNotification.isFailure,
          duplicateInitialization.isFailure,
          afterDuplicate.isSuccess,
          cancelledQueue.isInterrupted,
          admittedResult.isSuccess
        )
    },
    test(
      "SSE completion and cancellation join cleanup; cancellation notification requires response headers"
    ) {
      ZIO.scoped:
        for
          bodyClosed <- Ref.make(false)
          ready <- client(
            server(request =>
              ZIO
                .addFinalizer(bodyClosed.set(true))
                .as(stream(event(McpMessage.Success(request.id, emptyTools)) ++ ZStream.never))
            )
          )
          page      <- ready.listToolsPage()
          closed    <- bodyClosed.get
          entered   <- Promise.make[Nothing, Unit]
          cleanup   <- Promise.make[Nothing, Unit]
          release   <- Promise.make[Nothing, Unit]
          requested <- Promise.make[Nothing, McpRequestId]
          notified  <- Promise.make[Nothing, (Headers, Option[Json.Obj])]
          defect = new IllegalStateException("HTTP request cleanup failed")
          waiting <- client(
            scripted(request =>
              decode(request).flatMap:
                case McpMessage.Request(id, "initialize", _) =>
                  json(McpMessage.Success(id, initialization))
                    .map(_.addHeader("Mcp-Session-Id", "cancel-session"))
                case McpMessage.Request(id, "tools/call", _) =>
                  requested.succeed(id) *> ZIO
                    .addFinalizer(cleanup.succeed(()).unit *> release.await *> ZIO.die(defect))
                    .as(stream(ZStream.fromZIO(entered.succeed(()) *> ZIO.never)))
                case McpMessage.Notification("notifications/cancelled", params) =>
                  notified.succeed(request.headers -> params).as(Response.status(Status.Accepted))
                case _ => ZIO.succeed(Response.status(Status.Accepted))
            ),
            McpHttpLimits(maxConcurrentRequests = 1)
          )
          cancelled <- ZIO.scoped {
            (for
              running    <- waiting.callTool("echo").forkScoped
              _          <- entered.await
              cancelling <- running.interrupt.forkScoped
              _          <- cleanup.await
              pending    <- cancelling.poll
              _          <- release.succeed(())
              exit       <- cancelling.join
              id         <- requested.await
              notice     <- notified.await
              expected   <- ZIO.fromEither(McpMessage.toJson(McpMessage.Success(id, Json.Obj())))
            yield assertTrue(
              pending.isEmpty,
              exit.isInterrupted,
              exit.causeOption.exists(_.defects == List(defect)),
              notice._1.get("Mcp-Session-Id").contains("cancel-session"),
              notice._2.flatMap(_.fields.find(_._1 == "requestId").map(_._2)) ==
                expected.fields.find(_._1 == "id").map(_._2)
            )).ensuring(release.succeed(()).unit)
          }
          beforeHeaders <- Promise.make[Nothing, Unit]
          issued        <- Ref.make(0)
          preHeaderPort <- McpStreamableHttpTransport.open(
            scripted(_ => beforeHeaders.succeed(()) *> ZIO.never),
            endpoint
          )
          preHeader <- preHeaderPort
            .send(McpMessage.Request(McpRequestId.Number(1), "tools/list"), issued.update(_ + 1))
            .forkScoped
          _                  <- beforeHeaders.await
          earlyExit          <- preHeader.interrupt
          count              <- issued.get
          acquisitionEntered <- Promise.make[Nothing, Unit]
          interruptObserved  <- Promise.make[Nothing, Unit]
          releaseAcquisition <- Promise.make[Nothing, Unit]
          acquisitionFailure = AgentError.McpError("POST acquisition race")
          acquiring <- client(
            server(_ =>
              ZIO.uninterruptible(
                acquisitionEntered.succeed(()) *>
                  (ZIO.yieldNow *> ZIO.descriptorWith(descriptor =>
                    ZIO.succeed(descriptor.interrupters.nonEmpty)
                  )).repeatUntil(identity) *>
                  interruptObserved.succeed(()) *> releaseAcquisition.await *>
                  ZIO.fail(acquisitionFailure)
              )
            )
          )
          acquisition <- ZIO.scoped {
            (for
              running  <- acquiring.callTool("blocked").forkScoped
              _        <- acquisitionEntered.await
              stopping <- running.interrupt.forkScoped
              _        <- interruptObserved.await
              pending  <- stopping.poll
              _        <- releaseAcquisition.succeed(())
              exit     <- stopping.join
            yield assertTrue(
              pending.isEmpty,
              exit.isInterrupted,
              exit.causeOption.exists(_.failures == List(acquisitionFailure)),
              exit.causeOption.exists(_.defects.isEmpty)
            )).ensuring(releaseAcquisition.succeed(()).unit)
          }
        yield assertTrue(
          page.tools.isEmpty,
          closed,
          earlyExit.isInterrupted,
          count == 0
        ) && cancelled && acquisition
    },
    test(
      "bounded ingress terminates on overflow and a second reader cannot steal the first reader's lifecycle"
    ) {
      ZIO.scoped:
        val http = server(request => json(McpMessage.Success(request.id, emptyTools)))
        for
          overflowing <- McpStreamableHttpTransport.open(
            http,
            endpoint,
            limits = McpHttpLimits(incomingCapacity = 1)
          )
          _ <- overflowing.send(McpMessage.Request(McpRequestId.Number(1), "initialize"))
          overflow <- overflowing
            .send(McpMessage.Request(McpRequestId.Number(2), "tools/list"))
            .exit
          terminal     <- overflowing.incoming.runCollect.exit
          single       <- McpStreamableHttpTransport.open(http, endpoint)
          reader       <- single.incoming.take(1).runCollect.forkScoped
          _            <- (ZIO.yieldNow *> reader.status).repeatUntil(_.isSuspended)
          duplicate    <- single.incoming.runCollect.exit
          stillWaiting <- reader.poll
          _            <- single.send(McpMessage.Request(McpRequestId.Number(3), "initialize")).exit
          received     <- reader.join
          failure = AgentError.McpError("HTTP exchange failed")
          defect  = new IllegalStateException("HTTP exchange defect")
          cause   = Cause.fail(failure) ++ Cause.die(defect)
          failedPort <- McpStreamableHttpTransport.open(
            scripted(_ => ZIO.refailCause(cause)),
            endpoint
          )
          failedSend <- failedPort
            .send(McpMessage.Request(McpRequestId.Number(4), "initialize"))
            .exit
          failedIncoming <- failedPort.incoming.runCollect.exit
        yield assertTrue(
          overflow.isFailure,
          terminal.isFailure,
          duplicate.isFailure,
          stillWaiting.isEmpty,
          received == Chunk(McpMessage.Success(McpRequestId.Number(3), initialization)),
          failedSend.causeOption.contains(cause),
          failedIncoming.causeOption.contains(cause)
        )
    }
  ) @@ TestAspect.timeout(20.seconds)
