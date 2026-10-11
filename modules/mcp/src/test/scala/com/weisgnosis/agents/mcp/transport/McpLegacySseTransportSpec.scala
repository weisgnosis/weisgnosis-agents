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
import com.weisgnosis.agents.core.json.JsonBoundary
import com.weisgnosis.agents.mcp.client.{McpClient, McpConnection}
import com.weisgnosis.agents.mcp.protocol.*
import zio.*
import zio.http.*
import zio.json.ast.Json
import zio.stream.ZStream
import zio.test.*

object McpLegacySseTransportSpec extends ZIOSpecDefault:
  private val endpoint: String = "https://peer.example/sse"

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
        ZIO.fail(AgentError.McpError("No WebSocket in legacy transport fixture")))

  private def bytes(value: String): Chunk[Byte] =
    Chunk.fromArray(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))

  private def events(body: ZStream[Any, Throwable, Byte]): Response =
    Response(
      status = Status.Ok,
      headers = Headers("Content-Type" -> "text/event-stream"),
      body = Body.fromStreamChunked(body)
    )

  def spec: Spec[TestEnvironment & Scope, Any] = suite("Legacy MCP HTTP SSE")(
    test("legacy acquisition diagnostics omit external text and preserve typed failures"):
      val sentinel = "Authorization: Bearer diagnostic-sentinel"
      val domain   = AgentError.McpError("Host supplied domain failure")
      val cases: Chunk[(Throwable, AgentError)] = Chunk(
        new java.net.UnknownHostException(sentinel) ->
          AgentError.McpError("MCP legacy SSE GET failed (dns)"),
        domain -> domain
      )
      ZIO
        .foreach(cases): (failure, expected) =>
          ZIO
            .scoped(
              McpLegacySseTransport.open(scripted(_ => ZIO.fail(failure)), endpoint)
            )
            .exit
            .map(exit =>
              assertTrue(
                exit.causeOption.exists(_.failures == List(expected)),
                !exit.causeOption.toList.flatMap(_.failures).exists(_.message.contains(sentinel))
              )
            )
        .map(_.foldLeft(assertTrue(true))(_ && _))
    ,
    test(
      "endpoint discovery precedes leased alternative decoding and selected-version POST headers"
    ):
      ZIO
        .foreach(
          Chunk(
            (McpProtocolVersion.V20250618, McpProtocolVersion.V20250326),
            (McpProtocolVersion.V20241105, McpProtocolVersion.V20250618)
          )
        ): (preferred, selected) =>
          ZIO.scoped:
            for
              supported <- ZIO.fromEither(McpSupportedVersions.make(Set(preferred, selected)))
              messages  <- ZIO.acquireRelease(Queue.unbounded[Chunk[Byte]])(_.shutdown)
              posts     <- Ref.make(Chunk.empty[Headers])
              pong      <- Promise.make[Nothing, Unit]
              http = scripted: request =>
                if request.method == Method.GET then
                  ZIO.succeed(
                    events(
                      ZStream.fromChunk(
                        bytes(
                          "event: endpoint\ndata: /messages\n\ndata: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/ready\"}\n\n"
                        )
                      ) ++
                        ZStream.fromQueue(messages).flattenChunks
                    )
                  )
                else
                  posts.update(_ :+ request.headers) *> request.body.asString.flatMap: wire =>
                    ZIO
                      .fromEither(McpPacket.decode(wire, selected))
                      .flatMap:
                        case McpPacket.Single(McpMessage.Request(id, "initialize", params)) =>
                          for
                            _ <- ZIO
                              .fail(AgentError.InvalidMessage("Wrong preferred legacy revision"))
                              .unless(
                                params.exists(
                                  _.get("protocolVersion").contains(Json.Str(preferred.wire))
                                )
                              )
                            success <- ZIO.fromEither(
                              McpMessage.encode(
                                McpMessage.Success(
                                  id,
                                  Json.Obj(
                                    "protocolVersion" -> Json.Str(selected.wire),
                                    "serverInfo" -> Json.Obj(
                                      "name"    -> Json.Str("alternative"),
                                      "version" -> Json.Str("1")
                                    ),
                                    "capabilities" -> Json.Obj("tools" -> Json.Obj())
                                  )
                                )
                              )
                            )
                            suffix =
                              if selected == McpProtocolVersion.V20250326 then
                                "data: [{\"jsonrpc\":\"2.0\",\"id\":70,\"method\":\"ping\"}]\n\n"
                              else ""
                            _ <- messages.offer(bytes(s"data: $success\n\n$suffix"))
                          yield Response.status(Status.Accepted)
                        case McpPacket.Batch(members)
                            if members == Chunk(
                              McpBatchMember.Message(
                                McpMessage.Success(McpRequestId.Number(70), Json.Obj())
                              )
                            ) =>
                          pong.succeed(()).as(Response.status(Status.Accepted))
                        case McpPacket.Single(McpMessage.Request(id, "tools/list", _)) =>
                          ZIO
                            .fromEither(
                              McpMessage
                                .encode(McpMessage.Success(id, Json.Obj("tools" -> Json.Arr())))
                            )
                            .flatMap(value => messages.offer(bytes(s"data: $value\n\n")))
                            .as(Response.status(Status.Accepted))
                        case McpPacket.Single(_: McpMessage.Notification) =>
                          ZIO.succeed(Response.status(Status.Accepted))
                        case _ =>
                          ZIO.fail(AgentError.InvalidMessage("Unexpected negotiated legacy packet"))
              transport <- McpLegacySseTransport
                .open(http, endpoint, version = preferred, supportedVersions = Some(supported))
              unleased <- transport.incomingPackets.runHead.exit
              client   <- McpClient.open(transport, McpImplementation("alternative", "1"))
              second   <- McpClient.open(transport, McpImplementation("second", "1")).exit
              page     <- client.listToolsPage()
              _        <- pong.await.when(selected == McpProtocolVersion.V20250326)
              headers  <- posts.get
              current  <- transport.currentProtocolVersion
            yield assertTrue(
              unleased.isFailure,
              second.isFailure,
              page.tools.isEmpty,
              client.initialization.protocolVersion == selected,
              current == selected,
              headers.headOption.exists(_.get("MCP-Protocol-Version").contains(preferred.wire)),
              headers.drop(1).forall(_.get("MCP-Protocol-Version").contains(selected.wire))
            )
        .map(_.foldLeft(assertTrue(true))(_ && _))
    ,
    test("initialization permits server ping batches but rejects a batched initialization reply"):
      ZIO.scoped:
        for
          messages     <- ZIO.acquireRelease(Queue.unbounded[Chunk[Byte]])(_.shutdown)
          grouped      <- Ref.make(Chunk.empty[Json])
          initializing <- Ref.make[Option[McpRequestId]](None)
          http = scripted: request =>
            if request.method == Method.GET then
              ZIO.succeed(
                events(
                  ZStream.fromChunk(bytes("event: endpoint\ndata: /messages\n\n")) ++ ZStream
                    .fromQueue(messages)
                    .flattenChunks
                )
              )
            else
              request.body.asString.flatMap: wire =>
                ZIO
                  .fromEither(JsonBoundary.decode(wire).left.map(AgentError.InvalidMessage(_)))
                  .flatMap:
                    case batch: Json.Arr =>
                      grouped.update(_ :+ batch) *> initializing.get.flatMap:
                        case Some(id) =>
                          ZIO
                            .fromEither(McpMessage.encode(McpMessage.Success(id, Json.Obj())))
                            .flatMap(value => messages.offer(bytes(s"data: [$value]\n\n")))
                            .as(Response.status(Status.Accepted))
                        case None =>
                          ZIO.fail(AgentError.InvalidMessage("Missing initialization identity"))
                    case value =>
                      ZIO
                        .fromEither(McpMessage.fromJson(value))
                        .flatMap:
                          case McpMessage.Request(id, "initialize", _) =>
                            initializing.set(Some(id)) *>
                              messages
                                .offer(
                                  bytes(
                                    "data: [{\"jsonrpc\":\"2.0\",\"id\":70,\"method\":\"ping\"}]\n\n"
                                  )
                                )
                                .as(Response.status(Status.Accepted))
                          case _ =>
                            ZIO.fail(
                              AgentError.InvalidMessage("Unexpected initialization fixture message")
                            )
          transport <- McpLegacySseTransport.open(
            http,
            endpoint,
            version = McpProtocolVersion.V20250326
          )
          connection <- McpConnection.make(transport)
          result     <- connection.request("initialize").exit
          groups     <- grouped.get
          expected = Json.Arr(
            Json.Obj("jsonrpc" -> Json.Str("2.0"), "id" -> Json.Num(70), "result" -> Json.Obj())
          )
        yield assertTrue(
          groups == Chunk(expected),
          result.isFailure,
          result.causeOption.toList
            .flatMap(_.failures)
            .exists(_.message.contains("initialization cannot receive batch"))
        )
    ,
    test("March request batches produce one grouped response including invalid members"):
      ZIO.scoped:
        for
          messages <- ZIO.acquireRelease(Queue.unbounded[Chunk[Byte]])(_.shutdown)
          grouped  <- Ref.make(Chunk.empty[Json])
          pending  <- Ref.make[Option[McpRequestId]](None)
          http = scripted: request =>
            if request.method == Method.GET then
              ZIO.succeed(
                events(
                  ZStream.fromChunk(bytes("event: endpoint\ndata: /messages\n\n")) ++
                    ZStream.fromQueue(messages).flattenChunks
                )
              )
            else
              for
                wire <- request.body.asString
                json <- ZIO.fromEither(
                  JsonBoundary.decode(wire).left.map(AgentError.InvalidMessage(_))
                )
                _ <- json match
                  case batch: Json.Arr =>
                    grouped.update(_ :+ batch) *> pending.get.flatMap:
                      case Some(id) =>
                        ZIO
                          .fromEither(McpMessage.encode(McpMessage.Success(id, Json.Obj())))
                          .flatMap(value => messages.offer(bytes(s"data: $value\n\n")))
                          .unit
                      case None => ZIO.fail(AgentError.InvalidMessage("Batch preceded request"))
                  case _ =>
                    ZIO
                      .fromEither(McpMessage.fromJson(json))
                      .flatMap:
                        case McpMessage.Request(id, "probe", _) =>
                          pending.set(Some(id)) *>
                            messages
                              .offer(
                                bytes(
                                  "data: [{\"jsonrpc\":\"2.0\",\"id\":70,\"method\":\"ping\"}," +
                                    "42,{\"jsonrpc\":\"2.0\",\"method\":\"notifications/ignored\"}]\n\n"
                                )
                              )
                              .unit
                        case _ => ZIO.fail(AgentError.InvalidMessage("Unexpected fixture envelope"))
              yield Response(status = Status.Accepted)
          transport <- McpLegacySseTransport.open(
            http,
            endpoint,
            version = McpProtocolVersion.V20250326
          )
          connection <- McpConnection.make(transport)
          result     <- connection.request("probe").exit
          responses  <- grouped.get
          expected = Json.Arr(
            Json.Obj("jsonrpc" -> Json.Str("2.0"), "id" -> Json.Num(70), "result" -> Json.Obj()),
            Json.Obj(
              "jsonrpc" -> Json.Str("2.0"),
              "id"      -> Json.Null,
              "error" -> Json.Obj(
                "code"    -> Json.Num(-32600),
                "message" -> Json.Str("Invalid Request")
              )
            )
          )
        yield assertTrue(result.isSuccess, responses == Chunk(expected))
    ,
    test(
      "malformed response batches never complete a valid prefix or produce response-error loops"
    ):
      ZIO.scoped:
        for
          messages <- ZIO.acquireRelease(Queue.unbounded[Chunk[Byte]])(_.shutdown)
          posts    <- Ref.make(0)
          http = scripted: request =>
            if request.method == Method.GET then
              ZIO.succeed(
                events(
                  ZStream.fromChunk(bytes("event: endpoint\ndata: /messages\n\n")) ++ ZStream
                    .fromQueue(messages)
                    .flattenChunks
                )
              )
            else
              for
                _       <- posts.update(_ + 1)
                wire    <- request.body.asString
                decoded <- ZIO.fromEither(McpMessage.decode(wire))
                _ <- decoded match
                  case McpMessage.Request(id, _, _) =>
                    ZIO
                      .fromEither(McpMessage.encode(McpMessage.Success(id, Json.Obj())))
                      .flatMap: valid =>
                        messages
                          .offer(
                            bytes(
                              s"data: [$valid,{\"jsonrpc\":\"2.0\",\"id\":70,\"error\":false}]\n\n"
                            )
                          )
                          .unit
                  case _ => ZIO.fail(AgentError.InvalidMessage("Unexpected response-error loop"))
              yield Response(status = Status.Accepted)
          transport <- McpLegacySseTransport.open(
            http,
            endpoint,
            version = McpProtocolVersion.V20250326
          )
          connection <- McpConnection.make(transport)
          result     <- connection.request("probe").exit
          count      <- posts.get
        yield assertTrue(
          result.isFailure,
          count == 1,
          result.causeOption.toList
            .flatMap(_.failures)
            .exists:
              case _: AgentError.DeserializationError => true
              case _                                  => false
        )
    ,
    test(
      "all selected revisions initialize through the advertised POST endpoint and receive replies over GET"
    ):
      ZIO
        .foreach(
          Chunk(
            McpProtocolVersion.V20241105,
            McpProtocolVersion.V20250326,
            McpProtocolVersion.V20250618
          )
        ): version =>
          ZIO.scoped:
            for
              messages <- ZIO.acquireRelease(Queue.unbounded[Chunk[Byte]])(_.shutdown)
              observed <- Ref.make(Chunk.empty[(Method, String, Option[String])])
              http = scripted: request =>
                observed.update(
                  _ :+ (request.method, request.url.encode, request.headers.get("Authorization"))
                ) *>
                  (if request.method == Method.GET then
                     ZIO.succeed(
                       events(
                         ZStream.fromChunk(
                           bytes("event: endpoint\ndata: /messages?session=opaque\n\n")
                         ) ++
                           ZStream.fromQueue(messages).flattenChunks
                       )
                     )
                   else
                     for
                       wire    <- request.body.asString
                       message <- ZIO.fromEither(McpMessage.decode(wire))
                       _ <- message match
                         case McpMessage.Request(id, "initialize", params) =>
                           ZIO
                             .fail(
                               AgentError.InvalidMessage("Wrong selected initialization revision")
                             )
                             .unless(
                               params.exists(
                                 _.fields.contains("protocolVersion" -> Json.Str(version.wire))
                               )
                             ) *>
                             ZIO
                               .fromEither(
                                 McpMessage.encode(
                                   McpMessage.Success(
                                     id,
                                     Json.Obj(
                                       "protocolVersion" -> Json.Str(version.wire),
                                       "serverInfo" -> Json.Obj(
                                         "name"    -> Json.Str("peer"),
                                         "version" -> Json.Str("1")
                                       ),
                                       "capabilities" -> Json.Obj("tools" -> Json.Obj())
                                     )
                                   )
                                 )
                               )
                               .flatMap(value =>
                                 messages.offer(bytes(s"event: message\ndata: $value\n\n"))
                               )
                               .unit
                         case McpMessage.Request(id, "tools/list", _) =>
                           ZIO
                             .fromEither(
                               McpMessage
                                 .encode(McpMessage.Success(id, Json.Obj("tools" -> Json.Arr())))
                             )
                             .flatMap(value => messages.offer(bytes(s"data: $value\n\n")))
                             .unit
                         case _: McpMessage.Notification => ZIO.unit
                         case _ => ZIO.fail(AgentError.InvalidMessage("Unexpected fixture message"))
                     yield Response(status = Status.Accepted))
              transport <- McpLegacySseTransport.open(
                http,
                endpoint,
                Headers("Authorization" -> "Bearer fixture"),
                version = version
              )
              client <- McpClient.open(transport, McpImplementation("test", "1"))
              tools  <- client.listToolsPage()
              calls  <- observed.get
            yield assertTrue(
              tools.tools.isEmpty,
              client.initialization.protocolVersion == version,
              calls.count(_._1 == Method.GET) == 1,
              calls.count(_._1 == Method.POST) == 3,
              calls
                .filter(_._1 == Method.POST)
                .forall(_._2 == "https://peer.example/messages?session=opaque"),
              calls.forall(_._3.contains("Bearer fixture"))
            )
        .map(_.foldLeft(assertTrue(true))(_ && _))
    ,
    test(
      "advertised URI authority rejects unsafe near-misses and permits same-origin relative endpoints"
    ):
      for
        base <- ZIO.fromEither(URL.decode(endpoint))
        safe <- ZIO.foreach(
          Chunk(
            "/messages?session=one",
            "messages",
            "https://PEER.example:443/messages",
            "//peer.example/messages"
          )
        )(value => McpLegacySseTransport.resolveEndpoint(base, value).either)
        unsafe <- ZIO.foreach(
          Chunk(
            "",
            "http://peer.example/messages",
            "https://other.example/messages",
            "https://peer.example:444/messages",
            "https://user@peer.example/messages",
            "/messages#fragment",
            "file:///tmp/messages"
          )
        )(value => McpLegacySseTransport.resolveEndpoint(base, value).either)
        attempts <- Ref.make(Chunk.empty[Method])
        http = scripted(request =>
          attempts
            .update(_ :+ request.method)
            .as(
              events(
                ZStream.fromChunk(
                  bytes("event: endpoint\ndata: https://other.example/messages\n\n")
                ) ++ ZStream.never
              )
            )
        )
        rejected <- ZIO.scoped(McpLegacySseTransport.open(http, endpoint)).exit
        seen     <- attempts.get
      yield assertTrue(
        safe.forall(_.isRight),
        unsafe.forall(_.isLeft),
        rejected.isFailure,
        seen == Chunk(Method.GET)
      )
    ,
    test("inbound protocol violations, EOF and bounded queue exhaustion terminate observation"):
      val endpointEvent = "event: endpoint\ndata: /messages\n\n"
      val notice = "event: message\ndata: {\"jsonrpc\":\"2.0\",\"method\":\"notification\"}\n\n"
      val cases: Chunk[(String, Option[String])] = Chunk(
        ("event: message\ndata: {}\n\n", Some("before endpoint")),
        (endpointEvent + endpointEvent, Some("second endpoint")),
        (endpointEvent + "event: message\ndata: malformed\n\n", None),
        (endpointEvent, Some("connection ended")),
        (endpointEvent + notice + notice, Some("queue overflow")),
        (
          endpointEvent + notice + "data: [{\"jsonrpc\":\"2.0\",\"method\":\"notification\"},{\"jsonrpc\":\"2.0\",\"method\":\"notification\"}]\n\n",
          Some("queue overflow")
        )
      )
      ZIO
        .foreach(cases): (wire, expected) =>
          for
            ended <- Promise.make[Nothing, Unit]
            http = scripted(_ =>
              ZIO.succeed(
                events(
                  ZStream
                    .fromChunk(bytes(wire))
                    .ensuring(ended.succeed(()).unit)
                )
              )
            )
            exit <- ZIO
              .scoped:
                for
                  transport <- McpLegacySseTransport
                    .open(
                      http,
                      endpoint,
                      limits = McpHttpLimits(incomingCapacity = 1),
                      version = McpProtocolVersion.V20250326
                    )
                  _ <- ended.await
                  _ <- transport.incoming.runDrain
                yield ()
              .exit
          yield assertTrue(
            exit.isFailure,
            exit.causeOption.toList
              .flatMap(_.failures)
              .exists(error =>
                expected match
                  case Some(message) => error.message.contains(message)
                  case None =>
                    error match
                      case _: AgentError.DeserializationError => true
                      case _                                  => false
              )
          )
        .map(_.foldLeft(assertTrue(true))(_ && _))
    ,
    test("endpoint timeout cancels GET and joins held response cleanup before returning"):
      for
        acquired <- Promise.make[Nothing, Unit]
        cleaning <- Promise.make[Nothing, Unit]
        release  <- Promise.make[Nothing, Unit]
        http = scripted(_ =>
          ZIO.addFinalizer(cleaning.succeed(()) *> release.await) *>
            acquired.succeed(()).as(events(ZStream.never))
        )
        waiting <- ZIO
          .scoped(McpLegacySseTransport.open(http, endpoint, endpointDeadline = 1.second))
          .exit
          .fork
        _      <- acquired.await
        _      <- TestClock.adjust(1.second)
        _      <- cleaning.await
        before <- waiting.poll
        _      <- release.succeed(())
        exit   <- waiting.join
      yield assertTrue(
        before.isEmpty,
        exit.isFailure,
        exit.causeOption.toList.flatMap(_.failures).exists(_.message.contains("endpoint deadline"))
      )
    ,
    test("cancelling singleton and grouped POSTs joins independent response cleanup"):
      ZIO
        .foreach(
          Chunk(
            McpPacket.Single(McpMessage.Notification("example")),
            McpPacket.Batch(
              Chunk(McpBatchMember.Message(McpMessage.Success(McpRequestId.Number(1), Json.Obj())))
            )
          )
        ): packet =>
          ZIO.scoped:
            for
              issued   <- Promise.make[Nothing, Unit]
              cleaning <- Promise.make[Nothing, Unit]
              release  <- Promise.make[Nothing, Unit]
              http = scripted: request =>
                if request.method == Method.GET then
                  ZIO.succeed(
                    events(
                      ZStream.fromChunk(
                        bytes("event: endpoint\ndata: /messages\n\n")
                      ) ++ ZStream.never
                    )
                  )
                else
                  ZIO
                    .addFinalizer(cleaning.succeed(()) *> release.await)
                    .as(
                      Response(
                        status = Status.Accepted,
                        body = Body.fromStreamChunked(
                          ZStream.fromZIO(issued.succeed(())).drain ++ ZStream.never
                        )
                      )
                    )
              transport <- McpLegacySseTransport
                .open(http, endpoint, version = McpProtocolVersion.V20250326)
              sending      <- ZIO.scoped(transport.sendPacket(packet)).fork
              _            <- issued.await
              cancellation <- sending.interrupt.fork
              _            <- cleaning.await
              before       <- cancellation.poll
              _            <- release.succeed(())
              exit         <- cancellation.join
            yield assertTrue(before.isEmpty, exit.isInterrupted)
        .map(_.foldLeft(assertTrue(true))(_ && _))
  ) @@ TestAspect.timeout(15.seconds)
