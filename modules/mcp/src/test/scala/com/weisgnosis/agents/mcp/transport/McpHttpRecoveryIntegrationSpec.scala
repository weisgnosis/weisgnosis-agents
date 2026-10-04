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
import com.weisgnosis.agents.mcp.protocol.*
import java.io.IOException
import java.nio.charset.StandardCharsets
import zio.*
import zio.http.*
import zio.json.ast.Json
import zio.stream.ZStream
import zio.test.*

object McpHttpRecoveryIntegrationSpec extends ZIOSpecDefault:
  private val initialization: Json.Obj = Json.Obj(
    "protocolVersion" -> Json.Str(McpInitialization.protocolVersion),
    "serverInfo"      -> Json.Obj("name" -> Json.Str("netty-peer"), "version" -> Json.Str("1")),
    "capabilities"    -> Json.Obj("tools" -> Json.Obj())
  )
  private val notice: McpMessage =
    McpMessage.Notification("notifications/message", Some(Json.Obj()))
  private val request: McpMessage.Request = McpMessage.Request(McpRequestId.Number(2), "tools/list")
  private val reply: McpMessage = McpMessage.Success(request.id, Json.Obj("tools" -> Json.Arr()))

  private def bytes(text: String): ZStream[Any, Nothing, Byte] =
    ZStream.suspend(ZStream.fromIterable(text.getBytes(StandardCharsets.UTF_8)))

  private def event(message: McpMessage): ZStream[Any, AgentError, Byte] =
    ZStream
      .fromZIO(ZIO.fromEither(McpMessage.encode(message)))
      .flatMap(wire => bytes(s"data: $wire\n\n"))

  private def stream(body: ZStream[Any, Throwable, Byte]): Response = Response(
    status = Status.Ok,
    headers = Headers("Content-Type", "text/event-stream"),
    body = Body.fromStreamChunked(body)
  )

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

  def spec: Spec[TestEnvironment & Scope, Any] = suite("McpHttpRecoveryIntegrationSpec")(
    test(
      "real Netty body truncation resumes the original request from its committed session cursor"
    ):
      ZIO.scoped:
        val serverFailure = new IllegalStateException("Deliberate server stream termination")
        for
          probeConsumed <- Promise.make[Nothing, Unit]
          probeClosed   <- Promise.make[Nothing, Unit]
          committed     <- Promise.make[Nothing, Unit]
          postClosed    <- Promise.make[Nothing, Unit]
          result <- (for
            posts     <- Ref.make(Chunk.empty[McpMessage.Request])
            gets      <- Ref.make(Chunk.empty[(Headers, Boolean)])
            observed  <- Ref.make(Chunk.empty[McpMessage])
            completed <- Promise.make[Nothing, Unit]
            port <- Server.install(
              Routes(
                Method.GET / "probe" -> handler {
                  stream(
                    (bytes("probe") ++ ZStream.fromZIO(
                      probeConsumed.await *> ZIO.fail(serverFailure)
                    ))
                      .ensuring(probeClosed.succeed(()).unit)
                  )
                },
                Method.POST / "mcp" -> handler { (incoming: Request) =>
                  incoming.body.asString
                    .flatMap(wire => ZIO.fromEither(McpMessage.decode(wire)))
                    .flatMap:
                      case call: McpMessage.Request =>
                        posts.update(_ :+ call) *> (call.method match
                          case "initialize" =>
                            json(McpMessage.Success(call.id, initialization))
                              .map(_.addHeader("Mcp-Session-Id", "captured-session"))
                          case "tools/list" =>
                            ZIO.succeed(
                              stream(
                                (bytes("id: committed-cursor\n") ++ event(notice) ++
                                  ZStream.fromZIO(committed.await *> ZIO.fail(serverFailure)))
                                  .ensuring(postClosed.succeed(()).unit)
                              )
                            )
                          case _ => ZIO.succeed(Response.status(Status.NotFound)))
                      case _ => ZIO.succeed(Response.status(Status.Accepted))
                    .orElseSucceed(Response.status(Status.InternalServerError))
                },
                Method.GET / "mcp" -> handler { (incoming: Request) =>
                  postClosed.isDone
                    .flatMap(closed => gets.update(_ :+ (incoming.headers, closed)))
                    .as(stream(event(reply)))
                }
              )
            )
            http <- ZIO.service[Client]
            url  <- ZIO.fromEither(URL.decode(s"http://127.0.0.1:$port/probe"))
            probe <- ZIO.scoped:
              Client
                .streaming(Request.get(url))
                .provideSomeEnvironment[Scope](_.add(http))
                .flatMap(response =>
                  response.body.asStream
                    .tap(_ => probeConsumed.succeed(()).unit)
                    .runDrain
                    .exit
                    .map(exit => (response.headers.get("Transfer-Encoding"), exit))
                )
            _ <- probeClosed.await
            transport <- McpStreamableHttpTransport.open(
              http,
              s"http://127.0.0.1:$port/mcp",
              Headers("Authorization", "Bearer host-selected"),
              McpHttpLimits(maxConcurrentRequests = 1),
              McpHttpContinuation.OnPrematureEofOrBodyIoFailure(1)
            )
            reader <- transport.incoming
              .runForeach(message =>
                observed.update(_ :+ message) *>
                  (if message == notice then committed.succeed(()).unit
                   else if message == reply then completed.succeed(()).unit
                   else ZIO.unit)
              )
              .forkScoped
            _        <- transport.send(McpMessage.Request(McpRequestId.Number(1), "initialize"))
            _        <- transport.send(McpInitialization.initialized)
            _        <- transport.send(request)
            _        <- completed.await
            received <- observed.get
            sent     <- posts.get
            resumed  <- gets.get
            stopped  <- reader.interrupt
          yield assertTrue(
            probe._1.contains("chunked"),
            probe._2.causeOption.exists(cause =>
              cause.failures match
                case List(error) =>
                  error.getClass == classOf[IOException] && Option(error.getCause).isEmpty &&
                  cause.defects.isEmpty && !cause.isInterrupted
                case _ => false
            ),
            sent == Chunk(McpMessage.Request(McpRequestId.Number(1), "initialize"), request),
            received == Chunk(
              McpMessage.Success(McpRequestId.Number(1), initialization),
              notice,
              reply
            ),
            resumed.size == 1,
            resumed.forall((headers, closed) =>
              closed &&
                headers.get("Last-Event-ID").contains("committed-cursor") &&
                headers.get("Mcp-Session-Id").contains("captured-session") &&
                headers.get("MCP-Protocol-Version").contains(McpInitialization.protocolVersion) &&
                headers.get("Authorization").contains("Bearer host-selected")
            ),
            stopped.isInterrupted
          )).ensuring(probeConsumed.succeed(()).unit *> committed.succeed(()).unit)
        yield result
  ).provide(Client.default, Server.defaultWithPort(0)) @@ TestAspect.withLiveClock @@ TestAspect
    .timeout(20.seconds)
