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
import com.weisgnosis.agents.mcp.transport.McpTransport
import zio.*
import zio.json.ast.Json
import zio.stream.*
import zio.test.*

object McpClientSpec extends ZIOSpecDefault:
  private val identity: McpImplementation = McpImplementation("test-client", "1")

  private def initialization(capabilities: Json.Obj = Json.Obj("tools" -> Json.Obj())): Json.Obj =
    Json.Obj(
      "protocolVersion" -> Json.Str(McpInitialization.protocolVersion),
      "serverInfo"      -> Json.Obj("name" -> Json.Str("test-peer"), "version" -> Json.Str("1")),
      "capabilities"    -> capabilities
    )

  private val tools: Json.Obj = Json.Obj(
    "tools" -> Json.Arr(
      Json.Obj("name" -> Json.Str("echo"), "inputSchema" -> Json.Obj("type" -> Json.Str("object")))
    ),
    "nextCursor" -> Json.Str("next")
  )

  final private case class Wire(
      received: Queue[Take[AgentError, McpMessage]],
      sent: Ref[Chunk[McpMessage]],
      started: Promise[Nothing, Unit],
      closed: Promise[Nothing, Unit]
  ):
    def receive(message: McpMessage): UIO[Unit] = received.offer(Take.single(message)).unit
    def reply(request: McpMessage.Request, result: Json.Obj): UIO[Unit] =
      receive(McpMessage.Success(request.id, result))
    def transport(handle: McpMessage => ZIO[Scope, AgentError, Unit]): McpTransport =
      new McpTransport:
        def send(message: McpMessage): ZIO[Scope, AgentError, Unit] =
          sent.update(_ :+ message) *> handle(message)
        def incoming: ZStream[Any, AgentError, McpMessage] =
          (ZStream.fromZIO(started.succeed(())) *> ZStream.fromQueue(received).flattenTake)
            .ensuring(closed.succeed(()).unit)

  private def wire: ZIO[Scope, Nothing, Wire] =
    for
      received <- ZIO.acquireRelease(Queue.unbounded[Take[AgentError, McpMessage]])(_.shutdown)
      sent     <- Ref.make(Chunk.empty[McpMessage])
      started  <- Promise.make[Nothing, Unit]
      closed   <- Promise.make[Nothing, Unit]
    yield Wire(received, sent, started, closed)

  private enum DeadlineCase:
    case InitializeTimeout, InitializeCancelled, CallTimeout, CallCancelled,
      DiscoveryTimeout, DiscoveryCancelled

  private enum DeadlinePhase:
    case Initialization, Request

  private enum DeadlineOutcome:
    case Completed, TimedOut

  private def selectedDeadlineCase(
      phase: DeadlinePhase,
      initializationDeadline: Duration,
      requestDeadline: Duration,
      expected: DeadlineOutcome
  ): ZIO[Scope & TestEnvironment, AgentError, TestResult] =
    for
      peer      <- wire
      entered   <- Promise.make[Nothing, Unit]
      deadlines <- ZIO.fromEither(McpClient.Deadlines.make(initializationDeadline, requestDeadline))
      delay = entered.succeed(()) *> ZIO.sleep(2.seconds)
      transport = peer.transport:
        case request @ McpMessage.Request(_, "initialize", _) =>
          (phase match
            case DeadlinePhase.Initialization => delay
            case DeadlinePhase.Request        => ZIO.unit
          ) *> peer.reply(request, initialization())
        case request @ McpMessage.Request(_, "tools/call", _) =>
          delay *> peer.reply(request, Json.Obj("content" -> Json.Arr()))
        case _ => ZIO.unit
      operation <- McpClient
        .open(transport, identity, deadlines)
        .flatMap: client =>
          phase match
            case DeadlinePhase.Initialization => ZIO.unit
            case DeadlinePhase.Request        => client.callTool("delayed").unit
        .forkScoped
      _        <- entered.await
      _        <- TestClock.adjust(2.seconds)
      observed <- operation.poll
      _        <- operation.interrupt
      result = expected match
        case DeadlineOutcome.Completed => observed.contains(Exit.unit)
        case DeadlineOutcome.TimedOut =>
          observed.exists(_.causeOption.exists(_.failures.exists:
            case _: AgentError.TimeoutError => true
            case _                          => false))
    yield assertTrue(result)

  private def page(names: Chunk[String], next: Option[String] = None): Json.Obj =
    Json.Obj(
      Chunk(
        "tools" -> Json.Arr(
          names.map(name =>
            Json.Obj(
              "name"        -> Json.Str(name),
              "inputSchema" -> Json.Obj("type" -> Json.Str("object"))
            )
          )
        )
      ) ++ Chunk.fromIterable(next.map(value => "nextCursor" -> Json.Str(value)))
    )

  private def listingClient(peer: Wire)(
      respond: McpMessage.Request => UIO[Unit]
  ): ZIO[Scope, AgentError, McpClient] =
    McpClient.open(
      peer.transport {
        case request @ McpMessage.Request(_, "initialize", _) =>
          peer.reply(request, initialization())
        case request @ McpMessage.Request(_, "tools/list", _) => respond(request)
        case _: McpMessage.Notification                       => ZIO.unit
        case _ => ZIO.fail(AgentError.McpError("Unexpected discovery request"))
      },
      identity
    )

  private def deadlineCase(
      mode: DeadlineCase
  ): ZIO[Scope & TestEnvironment, AgentError, TestResult] =
    for
      peer           <- wire
      entered        <- Promise.make[Nothing, Unit]
      cleanupStarted <- Promise.make[Nothing, Unit]
      release        <- Promise.make[Nothing, Unit]
      defect = new IllegalStateException("deadline cleanup defect")
      blocked = ZIO.acquireRelease(ZIO.unit)(_ =>
        cleanupStarted.succeed(()).unit *> release.await *> ZIO.die(defect)
      ) *>
        entered.succeed(()) *> ZIO.never
      transport = peer.transport:
        case request @ McpMessage.Request(_, "initialize", _) =>
          mode match
            case DeadlineCase.InitializeTimeout | DeadlineCase.InitializeCancelled => blocked
            case _ => peer.reply(request, initialization())
        case McpMessage.Request(_, "tools/call", _)              => blocked
        case request @ McpMessage.Request(_, "tools/list", None) => peer.reply(request, tools)
        case McpMessage.Request(_, "tools/list", _)              => blocked
        case _                                                   => ZIO.unit
      operation <- (mode match
        case DeadlineCase.InitializeTimeout | DeadlineCase.InitializeCancelled =>
          McpClient.open(transport, identity, 1.second).unit
        case DeadlineCase.DiscoveryTimeout | DeadlineCase.DiscoveryCancelled =>
          McpClient.open(transport, identity, 1.second).flatMap(_.discoverTools(2, 2)).unit
        case _ => McpClient.open(transport, identity, 1.second).flatMap(_.callTool("blocked")).unit
      ).forkScoped
      _ <- entered.await
      completion <- (mode match
        case DeadlineCase.InitializeTimeout | DeadlineCase.CallTimeout |
            DeadlineCase.DiscoveryTimeout =>
          TestClock.adjust(1.second) *> operation.await
        case _ => operation.interrupt
      ).forkScoped
      _            <- cleanupStarted.await
      before       <- completion.poll
      _            <- release.succeed(())
      exit         <- completion.join
      readerClosed <- peer.closed.isDone
      timedOut = exit.causeOption.exists(_.failures.exists:
        case _: AgentError.TimeoutError => true
        case _                          => false)
      expectedOutcome = mode match
        case DeadlineCase.InitializeTimeout | DeadlineCase.CallTimeout |
            DeadlineCase.DiscoveryTimeout =>
          timedOut
        case _ => exit.isInterrupted && !timedOut
      expectedClosure = mode match
        case DeadlineCase.InitializeTimeout | DeadlineCase.InitializeCancelled => readerClosed
        case _                                                                 => true
    yield assertTrue(
      before.isEmpty,
      expectedOutcome,
      expectedClosure,
      exit.causeOption.exists(_.defects.contains(defect))
    )

  def spec: Spec[TestEnvironment, Any] = suite("McpClientSpec")(
    test(
      "publishes only after initialized notification completes, then preserves tool pages and error distinctions"
    ) {
      ZIO.scoped:
        for
          peer      <- wire
          notifying <- Promise.make[Nothing, Unit]
          release   <- Promise.make[Nothing, Unit]
          remote = McpRpcError(-32602, "invalid arguments", Some(Json.Null))
          opening <- McpClient
            .open(
              peer.transport {
                case request @ McpMessage.Request(_, "initialize", _) =>
                  peer.reply(request, initialization())
                case McpMessage.Notification("notifications/initialized", _) =>
                  notifying.succeed(()).unit *> release.await
                case request @ McpMessage.Request(_, "tools/list", _) => peer.reply(request, tools)
                case request @ McpMessage.Request(_, "tools/call", Some(params)) =>
                  if params.fields.contains("name" -> Json.Str("rpc-error")) then
                    peer.receive(McpMessage.Failure(Some(request.id), remote))
                  else
                    peer.reply(
                      request,
                      Json.Obj("content" -> Json.Arr(), "isError" -> Json.Bool(true))
                    )
                case _ => ZIO.fail(AgentError.McpError("Unexpected client request"))
              },
              identity
            )
            .forkScoped
          _           <- notifying.await
          unpublished <- opening.poll
          _           <- release.succeed(())
          client      <- opening.join
          metadata = Json.Obj("trace" -> Json.Str("correlation"))
          page          <- client.listToolsPage(Some("cursor"), Some(metadata))
          toolError     <- client.callTool("echo", Some(Json.Obj()), Some(metadata))
          protocolError <- client.callTool("rpc-error").exit
          messages      <- peer.sent.get
          methods = messages.collect:
            case McpMessage.Request(_, method, _)   => method
            case McpMessage.Notification(method, _) => method
            case _                                  => "unexpected"
        yield assertTrue(
          unpublished.isEmpty,
          client.initialization.serverInfo.name == "test-peer",
          page.tools.map(_.name) == Chunk("echo"),
          page.nextCursor.contains("next"),
          toolError.outcome == McpCallOutcome.Error,
          protocolError.causeOption.exists(
            _.failures.contains(
              AgentError.McpRemoteError(
                remote.code,
                remote.message,
                AgentError.McpErrorData.Present(Json.Null)
              )
            )
          ),
          methods == Chunk(
            "initialize",
            "notifications/initialized",
            "tools/list",
            "tools/call",
            "tools/call"
          ),
          messages.exists:
            case McpMessage.Request(_, "tools/list", Some(params)) =>
              params.fields.contains("cursor" -> Json.Str("cursor")) && params.fields.contains(
                "_meta" -> metadata
              )
            case _ => false
        )
    },
    test("failed initialization closes its connection, while invalid deadlines never start it") {
      ZIO.scoped:
        for
          peer <- wire
          untouched = peer.transport(_ => ZIO.unit)
          invalid <- ZIO.foreach(Chunk(Duration.Zero, Duration.Infinity))(duration =>
            McpClient.open(untouched, identity, duration).exit
          )
          selectedInvalid <- ZIO.foreach(
            Chunk(
              (Duration.Zero, 1.second),
              (Duration.Infinity, 1.second),
              (1.second, Duration.Zero),
              (1.second, Duration.Infinity)
            )
          ): (initializationDeadline, requestDeadline) =>
            ZIO
              .fromEither(McpClient.Deadlines.make(initializationDeadline, requestDeadline))
              .flatMap(deadlines => McpClient.open(untouched, identity, deadlines))
              .exit
          started <- peer.started.isDone
          failures <- ZIO.foreach(Chunk("version", "remote", "notification")): scenario =>
            for
              endpoint <- wire
              failed <- McpClient
                .open(
                  endpoint.transport {
                    case request @ McpMessage.Request(_, "initialize", _) =>
                      scenario match
                        case "remote" =>
                          endpoint.receive(
                            McpMessage.Failure(Some(request.id), McpRpcError(-32600, "rejected"))
                          )
                        case "version" =>
                          endpoint.reply(
                            request,
                            Json.Obj(initialization().fields.map:
                              case ("protocolVersion", _) =>
                                "protocolVersion" -> Json.Str("2026-07-28")
                              case field => field)
                          )
                        case _ => endpoint.reply(request, initialization())
                    case _: McpMessage.Notification =>
                      ZIO.fail(AgentError.McpError("Notification send failed"))
                    case _ => ZIO.unit
                  },
                  identity
                )
                .exit
              closed <- endpoint.closed.isDone
            yield assertTrue(failed.isFailure, closed)
        yield failures.foldLeft(
          assertTrue(invalid.forall(_.isFailure), selectedInvalid.forall(_.isFailure), !started)
        )(_ && _)
    },
    test(
      "missing tools capability rejects locally without granting authority from other capability fields"
    ) {
      ZIO.scoped:
        for
          peer <- wire
          client <- McpClient.open(
            peer.transport {
              case request: McpMessage.Request =>
                peer.reply(
                  request,
                  initialization(Json.Obj("experimental" -> Json.Obj("tools" -> Json.Obj())))
                )
              case _ => ZIO.unit
            },
            identity
          )
          before    <- peer.sent.get
          listing   <- client.listToolsPage().exit
          calling   <- client.callTool("echo").exit
          discovery <- client.discoverTools(1, 1).exit
          after     <- peer.sent.get
        yield assertTrue(listing.isFailure, calling.isFailure, discovery.isFailure, after == before)
    },
    test(
      "discovery preserves ordered duplicates, opaque cursors and metadata through empty pages at exact limits"
    ) {
      ZIO.scoped:
        for
          peer <- wire
          metadata = Json.Obj("trace" -> Json.Str("discovery"))
          client <- listingClient(peer): request =>
            val cursor = request.params.flatMap(_.get("cursor"))
            val result = cursor match
              case None                         => page(Chunk("first"), Some(""))
              case Some(Json.Str(""))           => page(Chunk.empty, Some("opaque+/%="))
              case Some(Json.Str("opaque+/%=")) => page(Chunk("first", "last"), Some("end"))
              case _                            => page(Chunk.empty)
            peer.reply(request, result)
          discovered <- client.discoverTools(4, 3, Some(metadata))
          sent       <- peer.sent.get
          requests = sent.collect { case request @ McpMessage.Request(_, "tools/list", _) =>
            request
          }
        yield assertTrue(
          discovered.map(_.name) == Chunk("first", "first", "last"),
          requests.map(_.params.flatMap(_.get("cursor"))) ==
            Chunk(None, Some(Json.Str("")), Some(Json.Str("opaque+/%=")), Some(Json.Str("end"))),
          requests.forall(_.params.flatMap(_.get("_meta")).contains(metadata))
        )
    },
    test(
      "discovery rejects invalid budgets, excess results, cursor cycles and later remote errors without partial publication"
    ) {
      val one    = page(Chunk("one"), Some("a"))
      val remote = McpRpcError(-32602, "expired cursor")
      val cases = Chunk(
        (0, 1, Chunk.empty[Json.Obj], 0),
        (-1, 1, Chunk.empty[Json.Obj], 0),
        (1, 0, Chunk.empty[Json.Obj], 0),
        (1, -1, Chunk.empty[Json.Obj], 0),
        (1, 2, Chunk(one), 1),
        (2, 1, Chunk(page(Chunk("one", "two"))), 1),
        (2, 2, Chunk(one, page(Chunk("two", "three"))), 2),
        (4, 4, Chunk(one, page(Chunk.empty, Some("a"))), 2),
        (4, 4, Chunk(one, page(Chunk.empty, Some("b")), page(Chunk.empty, Some("a"))), 3),
        (2, 2, Chunk(one), 2)
      )
      ZIO.scoped:
        ZIO
          .foreach(cases): (maxPages, maxTools, responses, expectedRequests) =>
            for
              peer      <- wire
              remaining <- Ref.make(responses)
              client <- listingClient(peer): request =>
                remaining
                  .modify(pages => (pages.headOption, pages.drop(1)))
                  .flatMap:
                    case Some(result) => peer.reply(request, result)
                    case None         => peer.receive(McpMessage.Failure(Some(request.id), remote))
              result <- client.discoverTools(maxPages, maxTools).exit
              sent   <- peer.sent.get
              count = sent.count {
                case McpMessage.Request(_, "tools/list", _) => true; case _ => false
              }
              expectedRemote = expectedRequests > responses.size
              remotePreserved = result.causeOption.exists(
                _.failures.contains(AgentError.McpRemoteError(remote.code, remote.message))
              )
            yield assertTrue(
              result.isFailure,
              count == expectedRequests,
              remotePreserved == expectedRemote
            )
          .map(_.foldLeft(assertTrue(true))(_ && _))
    },
    test(
      "initialization, call and later discovery deadlines or cancellation join cleanup and retain its defect"
    ) {
      ZIO.scoped:
        for
          uniform <- ZIO.foreach(Chunk.fromArray(DeadlineCase.values))(deadlineCase)
          selected <- ZIO.foreach(
            Chunk(
              (DeadlinePhase.Initialization, 3.seconds, 1.second, DeadlineOutcome.Completed),
              (DeadlinePhase.Initialization, 1.second, 3.seconds, DeadlineOutcome.TimedOut),
              (DeadlinePhase.Request, 3.seconds, 1.second, DeadlineOutcome.TimedOut),
              (DeadlinePhase.Request, 1.second, 3.seconds, DeadlineOutcome.Completed)
            )
          )((phase, initializationDeadline, requestDeadline, expected) =>
            selectedDeadlineCase(phase, initializationDeadline, requestDeadline, expected)
          )
        yield (uniform ++ selected).foldLeft(assertTrue(true))(_ && _)
    }
  ) @@ TestAspect.timeout(15.seconds)
