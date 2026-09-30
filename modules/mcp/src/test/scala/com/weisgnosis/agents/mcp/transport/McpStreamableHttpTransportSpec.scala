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
import zio.*
import zio.http.*
import zio.json.ast.Json
import zio.stream.ZStream
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

  def spec: Spec[TestEnvironment & Scope, Any] = suite("McpStreamableHttpTransportSpec")(
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
            listening   <- transport.listen.forkScoped
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
          _         <- beforeHeaders.await
          earlyExit <- preHeader.interrupt
          count     <- issued.get
        yield assertTrue(
          page.tools.isEmpty,
          closed,
          earlyExit.isInterrupted,
          count == 0
        ) && cancelled
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
