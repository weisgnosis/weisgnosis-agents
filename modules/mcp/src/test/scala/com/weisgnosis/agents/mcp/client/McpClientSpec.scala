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
      DiscoveryTimeout, DiscoveryCancelled, ResourceTimeout, ResourceCancelled

  private enum DeadlinePhase:
    case Initialization, Request

  private enum DeadlineOutcome:
    case Completed, TimedOut

  private enum CallerInterruption:
    case Interruptible, Uninterruptible

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
            case _ =>
              peer.reply(
                request,
                initialization(Json.Obj("tools" -> Json.Obj(), "resources" -> Json.Obj()))
              )
        case McpMessage.Request(_, "resources/read", _)          => blocked
        case McpMessage.Request(_, "tools/call", _)              => blocked
        case request @ McpMessage.Request(_, "tools/list", None) => peer.reply(request, tools)
        case McpMessage.Request(_, "tools/list", _)              => blocked
        case _                                                   => ZIO.unit
      operation <- (mode match
        case DeadlineCase.InitializeTimeout | DeadlineCase.InitializeCancelled =>
          McpClient.open(transport, identity, 1.second).unit
        case DeadlineCase.DiscoveryTimeout | DeadlineCase.DiscoveryCancelled =>
          McpClient.open(transport, identity, 1.second).flatMap(_.discoverTools(2, 2)).unit
        case DeadlineCase.ResourceTimeout | DeadlineCase.ResourceCancelled =>
          McpClient
            .open(transport, identity, 1.second)
            .flatMap(_.readResource("memory://blocked"))
            .unit
        case _ => McpClient.open(transport, identity, 1.second).flatMap(_.callTool("blocked")).unit
      ).forkScoped
      _ <- entered.await
      completion <- (mode match
        case DeadlineCase.InitializeTimeout | DeadlineCase.CallTimeout |
            DeadlineCase.DiscoveryTimeout | DeadlineCase.ResourceTimeout =>
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
            DeadlineCase.DiscoveryTimeout | DeadlineCase.ResourceTimeout =>
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
      "successful initialization and discovery do not wait for deadlines under either caller interruption state"
    ) {
      ZIO
        .foreach(Chunk.fromArray(CallerInterruption.values)): caller =>
          ZIO.scoped:
            for
              peer      <- wire
              deadlines <- ZIO.fromEither(McpClient.Deadlines.make(2.seconds, 2.seconds))
              transport = peer.transport:
                case request @ McpMessage.Request(_, "initialize", _) =>
                  peer.reply(request, initialization())
                case request @ McpMessage.Request(_, "tools/list", _) =>
                  peer.reply(request, page(Chunk("echo")))
                case _ => ZIO.unit
              operation = McpClient
                .open(transport, identity, deadlines)
                .flatMap(_.listToolsPage())
                .map(_.tools)
              started <- Clock.nanoTime
              discovered <- caller match
                case CallerInterruption.Interruptible   => operation.interruptible
                case CallerInterruption.Uninterruptible => operation.uninterruptible
              elapsed <- Clock.nanoTime.map(_ - started)
              sent    <- peer.sent.get
              methods = sent.map:
                case McpMessage.Request(_, method, _)   => method
                case McpMessage.Notification(method, _) => method
                case _                                  => "unexpected"
            yield assertTrue(
              discovered.map(_.name) == Chunk("echo"),
              elapsed < 1.second.toNanos,
              methods == Chunk("initialize", "notifications/initialized", "tools/list")
            )
        .map(results => results.foldLeft(assertTrue(true))(_ && _))
    } @@ TestAspect.withLiveClock @@ TestAspect.timeout(10.seconds),
    test("discovery timeout joins cleanup promptly under either caller interruption state") {
      ZIO
        .foreach(Chunk.fromArray(CallerInterruption.values)): caller =>
          ZIO.scoped:
            for
              peer      <- wire
              cleaned   <- Ref.make(false)
              deadlines <- ZIO.fromEither(McpClient.Deadlines.make(2.seconds, 500.millis))
              transport = peer.transport:
                case request @ McpMessage.Request(_, "initialize", _) =>
                  peer.reply(request, initialization())
                case request @ McpMessage.Request(_, "tools/list", _) =>
                  (ZIO.sleep(2.seconds) *> peer.reply(request, page(Chunk("echo"))))
                    .ensuring(cleaned.set(true))
                case _ => ZIO.unit
              client  <- McpClient.open(transport, identity, deadlines)
              started <- Clock.nanoTime
              result <- caller match
                case CallerInterruption.Interruptible => client.listToolsPage().interruptible.either
                case CallerInterruption.Uninterruptible =>
                  client.listToolsPage().uninterruptible.either
              elapsed <- Clock.nanoTime.map(_ - started)
              joined  <- cleaned.get
            yield assertTrue(
              result.left.exists {
                case _: AgentError.TimeoutError => true
                case _                          => false
              },
              elapsed < 1.second.toNanos,
              joined
            )
        .map(results => results.foldLeft(assertTrue(true))(_ && _))
    } @@ TestAspect.withLiveClock @@ TestAspect.timeout(10.seconds),
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
      "tool catalogue retains original paginated definitions and last-name lookup without refreshing"
    ) {
      ZIO.scoped:
        for
          peer <- wire
          metadata = Json.Obj("trace" -> Json.Str("catalogue"))
          original = Json.Obj(
            "name"        -> Json.Str("same"),
            "title"       -> Json.Str("Original title"),
            "description" -> Json.Str("Remote description"),
            "inputSchema" -> Json
              .Obj("type" -> Json.Str("object"), "additionalProperties" -> Json.Bool(false)),
            "outputSchema" -> Json.Obj("type" -> Json.Str("object")),
            "annotations" -> Json
              .Obj("readOnlyHint" -> Json.Bool(true), "remoteHint" -> Json.Num(7)),
            "_meta"           -> Json.Obj("origin" -> Json.Str("remote")),
            "remoteExtension" -> Json.Arr(Json.Str("preserved"))
          )
          last = Json.Obj(
            original.fields.filterNot(_._1 == "title") :+ ("title" -> Json.Str("Last title"))
          )
          firstPage = Json.Obj("tools" -> Json.Arr(original), "nextCursor" -> Json.Str("next"))
          finalPage = Json.Obj(
            "tools" -> Json.Arr(
              last,
              Json.Obj(
                "name"        -> Json.Str("other"),
                "inputSchema" -> Json.Obj("type" -> Json.Str("object"))
              )
            )
          )
          responses <- Ref.make(Chunk(firstPage, finalPage, page(Chunk("changed"))))
          client <- listingClient(peer): request =>
            responses
              .modify(current => (current.headOption, current.drop(1)))
              .flatMap(result => peer.reply(request, result.getOrElse(page(Chunk.empty))))
          before        <- client.cachedTools
          missingBefore <- client.cachedTool("same")
          acquired      <- client.acquireToolCatalogue(2, 3, Some(metadata))
          expectedFirst <- ZIO.fromEither(McpTools.readListResult(firstPage))
          expectedLast  <- ZIO.fromEither(McpTools.readListResult(finalPage))
          same          <- client.cachedTool("same")
          other         <- client.cachedTool("other")
          missing       <- client.cachedTool("absent")
          live          <- client.discoverTools(1, 1)
          repeated      <- client.acquireToolCatalogue(1, 1)
          invalid       <- client.acquireToolCatalogue(0, 1).exit
          snapshot      <- client.cachedTools
          sent          <- peer.sent.get
          requests = sent.collect { case request @ McpMessage.Request(_, "tools/list", _) =>
            request
          }
        yield assertTrue(
          before.isEmpty,
          missingBefore.isEmpty,
          acquired == expectedFirst.tools ++ expectedLast.tools,
          same == expectedLast.tools.headOption,
          other == expectedLast.tools.lastOption,
          missing.isEmpty,
          live.map(_.name) == Chunk("changed"),
          repeated == acquired,
          snapshot == acquired,
          invalid.isFailure,
          requests.size == 3,
          requests.take(2).forall(_.params.flatMap(_.get("_meta")).contains(metadata)),
          requests.lastOption.forall(_.params.flatMap(_.get("_meta")).isEmpty)
        )
    },
    test("failed and cancelled partial catalogue acquisition publishes nothing and can retry") {
      ZIO.scoped:
        ZIO
          .foreach(Chunk("failed", "cancelled")): scenario =>
            for
              peer     <- wire
              entered  <- Promise.make[Nothing, Unit]
              cleaned  <- Promise.make[Nothing, Unit]
              requests <- Ref.make(0)
              client <- listingClient(peer): request =>
                requests
                  .updateAndGet(_ + 1)
                  .flatMap:
                    case 1 => peer.reply(request, page(Chunk("partial"), Some("next")))
                    case 2 if scenario == "cancelled" =>
                      (entered.succeed(()) *> ZIO.never).ensuring(cleaned.succeed(()).unit)
                    case 2 =>
                      peer.receive(
                        McpMessage.Failure(Some(request.id), McpRpcError(-32602, "expired"))
                      )
                    case _ => peer.reply(request, page(Chunk("retry")))
              attempt <- client.acquireToolCatalogue(2, 2).forkScoped
              observed <-
                if scenario == "cancelled" then entered.await *> attempt.interrupt
                else attempt.await
              empty    <- client.cachedTools
              cleanup  <- cleaned.isDone
              retried  <- client.acquireToolCatalogue(1, 1)
              retained <- client.cachedTools
              count    <- requests.get
            yield assertTrue(
              observed.isFailure,
              empty.isEmpty,
              scenario != "cancelled" || (observed.isInterrupted && cleanup),
              scenario != "failed" || observed.causeOption
                .exists(_.failures.contains(AgentError.McpRemoteError(-32602, "expired"))),
              retried.map(_.name) == Chunk("retry"),
              retained == retried,
              count == 3
            )
          .map(_.foldLeft(assertTrue(true))(_ && _))
    },
    test(
      "concurrent catalogue acquisition performs one traversal, including an empty successful snapshot"
    ) {
      ZIO.scoped:
        ZIO
          .foreach(Chunk(Chunk.empty[String], Chunk("one", "two"))): names =>
            for
              peer          <- wire
              entered       <- Promise.make[Nothing, Unit]
              release       <- Promise.make[Nothing, Unit]
              secondStarted <- Promise.make[Nothing, Unit]
              count         <- Ref.make(0)
              client <- listingClient(peer): request =>
                count.update(_ + 1) *> entered.succeed(()) *> release.await *> peer
                  .reply(request, page(names))
              first  <- client.acquireToolCatalogue(1, 2).forkScoped
              _      <- entered.await
              second <- (secondStarted.succeed(()) *> client.acquireToolCatalogue(1, 2)).forkScoped
              _      <- secondStarted.await
              before <- client.cachedTools
              _      <- release.succeed(())
              firstResult  <- first.join
              secondResult <- second.join
              repeated     <- client.acquireToolCatalogue(1, 2)
              sent         <- count.get
            yield assertTrue(
              before.isEmpty,
              firstResult.map(_.name) == names,
              secondResult == firstResult,
              repeated == firstResult,
              sent == 1
            )
          .map(_.foldLeft(assertTrue(true))(_ && _))
    },
    test("explicit and natural client close clear the catalogue and prevent reacquisition") {
      ZIO.scoped:
        ZIO
          .foreach(Chunk("explicit", "natural")): scenario =>
            for
              peer  <- wire
              owner <- ZIO.acquireRelease(Scope.make)(_.close(Exit.unit))
              client <- owner.extend(
                listingClient(peer)(request => peer.reply(request, page(Chunk("retained"))))
              )
              acquired <- client.acquireToolCatalogue(1, 1)
              _        <- if scenario == "explicit" then client.close else owner.close(Exit.unit)
              empty    <- client.cachedTools
              missing  <- client.cachedTool("retained")
              rejected <- client.acquireToolCatalogue(1, 1).exit
              _        <- client.close
              sent     <- peer.sent.get
            yield assertTrue(
              acquired.map(_.name) == Chunk("retained"),
              empty.isEmpty,
              missing.isEmpty,
              rejected.causeOption
                .exists(_.failures.contains(AgentError.McpError("MCP tool catalogue closed"))),
              sent.count {
                case McpMessage.Request(_, "tools/list", _) => true; case _ => false
              } == 1
            )
          .map(_.foldLeft(assertTrue(true))(_ && _))
    },
    test("close joins an in-flight catalogue send cleanup and cannot resurrect its snapshot") {
      ZIO.scoped:
        for
          peer    <- wire
          entered <- Promise.make[Nothing, Unit]
          cleanup <- Promise.make[Nothing, Unit]
          release <- Promise.make[Nothing, Unit]
          client <- McpClient.open(
            peer.transport {
              case request @ McpMessage.Request(_, "initialize", _) =>
                peer.reply(request, initialization())
              case McpMessage.Request(_, "tools/list", _) =>
                ZIO.acquireRelease(ZIO.unit)(_ => cleanup.succeed(()).unit *> release.await) *>
                  entered.succeed(()) *> ZIO.never
              case _ => ZIO.unit
            },
            identity
          )
          acquisition  <- client.acquireToolCatalogue(1, 1).forkScoped
          _            <- entered.await
          closing      <- client.close.forkScoped
          _            <- cleanup.await
          before       <- closing.poll
          emptyDuring  <- client.cachedTools
          _            <- release.succeed(())
          _            <- closing.join
          result       <- acquisition.await
          emptyAfter   <- client.cachedTools
          rejected     <- client.acquireToolCatalogue(1, 1).exit
          readerClosed <- peer.closed.isDone
        yield assertTrue(
          before.isEmpty,
          emptyDuring.isEmpty,
          emptyAfter.isEmpty,
          result.isFailure,
          rejected.isFailure,
          readerClosed
        )
    },
    test(
      "resource discovery, reads and subscriptions share correlation, metadata and scoped notifications"
    ) {
      ZIO.scoped:
        for
          peer <- wire
          metadata = Json.Obj("trace" -> Json.Str("resource"))
          client <- McpClient.open(
            peer.transport {
              case request @ McpMessage.Request(_, "initialize", _) =>
                peer.reply(
                  request,
                  initialization(
                    Json.Obj(
                      "resources" -> Json.Obj(
                        "subscribe"   -> Json.Bool(true),
                        "listChanged" -> Json.Bool(true)
                      )
                    )
                  )
                )
              case request @ McpMessage.Request(_, "resources/list", params) =>
                val first = params.flatMap(_.get("cursor")).isEmpty
                peer.reply(
                  request,
                  Json.Obj(
                    Chunk(
                      "resources" -> Json.Arr(
                        Json.Obj(
                          "uri"  -> Json.Str("memory://project"),
                          "name" -> Json.Str("Project")
                        )
                      )
                    ) ++
                      Chunk.fromIterable(Option.when(first)("nextCursor" -> Json.Str("")))
                  )
                )
              case request @ McpMessage.Request(_, "resources/templates/list", _) =>
                peer.reply(
                  request,
                  Json.Obj(
                    "resourceTemplates" -> Json.Arr(
                      Json.Obj(
                        "uriTemplate" -> Json.Str("memory://{name}"),
                        "name"        -> Json.Str("Memory")
                      )
                    )
                  )
                )
              case request @ McpMessage.Request(_, "resources/read", _) =>
                peer.reply(
                  request,
                  Json.Obj(
                    "contents" -> Json.Arr(
                      Json.Obj("uri" -> Json.Str("memory://project"), "text" -> Json.Str("context"))
                    )
                  )
                )
              case request @ McpMessage.Request(_, "resources/subscribe", _) =>
                peer.receive(
                  McpMessage.Notification(
                    "notifications/resources/updated",
                    Some(Json.Obj("uri" -> Json.Str("memory://project")))
                  )
                ) *>
                  peer.reply(request, Json.Obj())
              case request @ McpMessage.Request(_, "resources/unsubscribe", _) =>
                peer.reply(request, Json.Obj())
              case _: McpMessage.Notification => ZIO.unit
              case _ => ZIO.fail(AgentError.McpError("Unexpected resource fixture request"))
            },
            identity
          )
          observer  <- client.observeResourceChanges
          listed    <- client.discoverResources(2, 2, Some(metadata))
          templates <- client.discoverResourceTemplates(1, 1, Some(metadata))
          read      <- client.readResource("memory://project", Some(metadata))
          _         <- client.subscribeResource("memory://project", Some(metadata))
          change    <- observer.runHead
          _         <- client.unsubscribeResource("memory://project", Some(metadata))
          sent      <- peer.sent.get
          resourceRequests = sent.collect:
            case request: McpMessage.Request if request.method.startsWith("resources/") => request
        yield assertTrue(
          listed.map(_.uri) == Chunk("memory://project", "memory://project"),
          templates.map(_.uriTemplate) == Chunk("memory://{name}"),
          read.contents.map(_.body) == Chunk(McpResourceBody.Text("context")),
          change.contains(McpResourceChange.Updated("memory://project", None)),
          resourceRequests.size == 6,
          resourceRequests.forall(_.params.flatMap(_.get("_meta")).contains(metadata))
        )
    },
    test(
      "resource gates and pagination reject locally or at exact bounded traversal without partial publication"
    ) {
      ZIO.scoped:
        ZIO
          .foreach(Chunk("absent", "no-subscriptions", "cycle", "items", "pages", "invalid")):
            scenario =>
              for
                peer <- wire
                client <- McpClient.open(
                  peer.transport {
                    case request @ McpMessage.Request(_, "initialize", _) =>
                      peer.reply(
                        request,
                        initialization(
                          if scenario == "absent" then Json.Obj()
                          else Json.Obj("resources" -> Json.Obj())
                        )
                      )
                    case request @ McpMessage.Request(_, "resources/list", _) =>
                      peer.reply(
                        request,
                        Json.Obj(
                          "resources" -> Json.Arr(
                            Json.Obj("uri" -> Json.Str("r"), "name" -> Json.Str("R"))
                          ),
                          "nextCursor" -> Json.Str("same")
                        )
                      )
                    case _: McpMessage.Notification => ZIO.unit
                    case _ => ZIO.fail(AgentError.McpError("Unexpected resource gate request"))
                  },
                  identity
                )
                before <- peer.sent.get
                result <- (scenario match
                  case "absent"           => client.readResource("r").unit
                  case "no-subscriptions" => client.subscribeResource("r")
                  case "cycle"            => client.discoverResources(3, 3).unit
                  case "items"            => client.discoverResources(3, 1).unit
                  case "pages"            => client.discoverResources(1, 3).unit
                  case _                  => client.discoverResources(0, 1).unit
                ).exit
                after <- peer.sent.get
                local = Set("absent", "no-subscriptions", "invalid").contains(scenario)
              yield assertTrue(result.isFailure, !local || after == before)
          .map(_.foldLeft(assertTrue(true))(_ && _))
    },
    test(
      "notification overflow, malformed updates and connection closure preserve explicit observer failures"
    ) {
      ZIO.scoped:
        ZIO
          .foreach(Chunk("overflow", "malformed", "closed", "observer-closed")): scenario =>
            for
              peer <- wire
              client <- McpClient.open(
                peer.transport {
                  case request @ McpMessage.Request(_, "initialize", _) =>
                    peer.reply(
                      request,
                      initialization(
                        Json.Obj("resources" -> Json.Obj("subscribe" -> Json.Bool(true)))
                      )
                    )
                  case request @ McpMessage.Request(_, "resources/read", _) =>
                    peer.reply(request, Json.Obj("contents" -> Json.Arr()))
                  case _: McpMessage.Notification => ZIO.unit
                  case _ => ZIO.fail(AgentError.McpError("Unexpected observer fixture request"))
                },
                identity
              )
              observerScope <- Scope.make
              _             <- ZIO.addFinalizer(observerScope.close(Exit.unit))
              observer <- client.observeResourceChanges
                .provideEnvironment(ZEnvironment[Scope](observerScope))
              notification = McpMessage.Notification(
                "notifications/resources/updated",
                Option.when(scenario == "overflow")(Json.Obj("uri" -> Json.Str("r")))
              )
              count =
                if scenario == "overflow" then 65
                else if Set("closed", "observer-closed").contains(scenario) then 0
                else 1
              _ <- ZIO.foreachDiscard(0 until count)(_ => peer.receive(notification))
              // Correlated reply proves all preceding notifications passed through the same reader.
              result <- client.readResource("r")
              _      <- ZIO.when(scenario == "closed")(client.close)
              _      <- ZIO.when(scenario == "observer-closed")(observerScope.close(Exit.unit))
              failed <- observer.runDrain.exit
            yield assertTrue(
              result.contents.isEmpty,
              failed.isFailure,
              scenario != "closed" || failed.causeOption
                .exists(_.failures.contains(AgentError.McpError("MCP connection closed"))),
              scenario != "observer-closed" || failed.causeOption
                .exists(_.failures.contains(AgentError.McpError("MCP resource observer closed")))
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
