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
import com.weisgnosis.agents.core.model.ToolResultState
import com.weisgnosis.agents.core.tool.{ToolDef, Toolkit}
import com.weisgnosis.agents.mcp.client.{McpClient, McpClientManager}
import com.weisgnosis.agents.mcp.protocol.*
import com.weisgnosis.agents.mcp.tool.McpTool
import io.modelcontextprotocol.json.McpJsonMapper
import io.modelcontextprotocol.server.{McpAsyncServer, McpServer, McpServerFeatures}
import io.modelcontextprotocol.server.transport.{
  HttpServletStreamableServerTransportProvider,
  HttpServletSseServerTransportProvider
}
import io.modelcontextprotocol.spec.{
  McpSchema,
  McpServerTransportProvider,
  McpStreamableServerTransportProvider
}
import java.nio.file.{Files, Path}
import org.apache.catalina.LifecycleState
import org.apache.catalina.startup.Tomcat
import reactor.core.publisher.Mono
import scala.jdk.CollectionConverters.*
import zio.*
import zio.http.*
import zio.json.ast.Json
import zio.test.*
import zio.stream.ZStream

object McpJavaSdkInteropSpec extends ZIOSpecDefault:
  private enum ProviderOwner:
    case Startup, Servlet
  private enum WireTransport:
    case Streamable, LegacySse

  final private case class Provider(
      port: McpServerTransportProvider | McpStreamableServerTransportProvider,
      servlet: jakarta.servlet.http.HttpServlet,
      close: Task[Unit]
  )

  final private case class Server(
      tomcat: Tomcat,
      directory: Path,
      endpoint: String,
      sdk: McpAsyncServer
  )
  final private case class Exchange(
      method: Method,
      session: Option[String],
      status: Option[Status],
      lastEventId: Option[String] = None
  )

  private def recording(
      http: Client,
      exchanges: Ref[Chunk[Exchange]],
      getCleanup: UIO[Unit] = ZIO.unit
  ): Client =
    http.copy(driver = new ZClient.Driver[Any, Scope, Throwable]:
      def request(
          version: Version,
          method: Method,
          url: URL,
          headers: Headers,
          body: Body,
          sslConfig: Option[ClientSSLConfig],
          proxy: Option[Proxy]
      )(using Trace): ZIO[Scope, Throwable, Response] =
        val session = headers.get("Mcp-Session-Id")
        val cursor  = headers.get("Last-Event-ID")
        ZIO.when(method == Method.GET)(ZIO.addFinalizer(getCleanup)) *>
          exchanges.update(_ :+ Exchange(method, session, None, cursor)) *>
          http.driver
            .request(version, method, url, headers, body, sslConfig, proxy)
            .tap(response =>
              exchanges.update(_ :+ Exchange(method, session, Some(response.status), cursor))
            )

      def socket[Env1 <: Any](
          version: Version,
          url: URL,
          headers: Headers,
          app: WebSocketApp[Env1]
      )(using Trace, Scope =:= Scope): ZIO[Env1 & Scope, Throwable, Response] =
        http.driver.socket(version, url, headers, app))

  private def temporaryDirectory: ZIO[Scope, Throwable, Path] =
    ZIO
      .acquireRelease(
        ZIO.attemptBlocking(Files.createTempDirectory("mcp-java-sdk-"))
      )(directory =>
        ZIO
          .scoped(
            for
              stream <- ZIO.acquireRelease(ZIO.attemptBlocking(Files.walk(directory)))(stream =>
                ZIO.attemptBlocking(stream.close()).orDie
              )
              paths <- ZIO.attemptBlocking(
                stream.iterator().asScala.toList.sortBy(_.getNameCount).reverse
              )
              _ <- ZIO.foreachDiscard(paths)(path =>
                ZIO
                  .fail(AgentError.McpError("SDK fixture cleanup escaped its owned directory"))
                  .unless(path.toAbsolutePath.normalize().startsWith(directory)) *>
                  ZIO.attemptBlocking(Files.deleteIfExists(path)).unit
              )
            yield ()
          )
          .orDie
      )
      .flatMap(directory => ZIO.attemptBlocking(directory.toRealPath()))

  private def deleteSession(http: Client, endpoint: String, session: String): Task[Status] =
    ZIO.scoped:
      for
        url <- ZIO
          .fromEither(URL.decode(endpoint))
          .mapError(_ => AgentError.InvalidConfiguration("Invalid SDK fixture endpoint"))
        response <- Client
          .streaming(
            Request
              .delete(url)
              .addHeaders(
                Headers(
                  "Mcp-Session-Id"       -> session,
                  "MCP-Protocol-Version" -> McpInitialization.protocolVersion
                )
              )
          )
          .provideSomeEnvironment[Scope](_.add(http))
      yield response.status

  private def server(
      keepAlive: Option[java.time.Duration],
      transport: WireTransport = WireTransport.Streamable
  ): ZIO[Scope, Throwable, Server] =
    for
      directory <- temporaryDirectory
      owner     <- Ref.make(ProviderOwner.Startup)
      mapper    <- ZIO.attemptBlocking(McpJsonMapper.createDefault())
      provider <- ZIO.acquireRelease(ZIO.attemptBlocking:
        transport match
          case WireTransport.Streamable =>
            val builder = HttpServletStreamableServerTransportProvider
              .builder()
              .jsonMapper(mapper)
              .mcpEndpoint("/mcp")
            val provider = keepAlive.fold(builder)(builder.keepAliveInterval).build()
            Provider(
              provider,
              provider,
              ZIO.attemptBlocking(provider.closeGracefully().block()).unit
            )
          case WireTransport.LegacySse =>
            val builder = HttpServletSseServerTransportProvider
              .builder()
              .jsonMapper(mapper)
              .sseEndpoint("/sse")
              .messageEndpoint("/messages")
            val provider = keepAlive.fold(builder)(builder.keepAliveInterval).build()
            Provider(
              provider,
              provider,
              ZIO.attemptBlocking(provider.closeGracefully().block()).unit
            ))(provider =>
        owner.get.flatMap:
          case ProviderOwner.Servlet => ZIO.unit
          case ProviderOwner.Startup => provider.close.orDie
      )
      tomcat <- ZIO.acquireRelease(ZIO.attemptBlocking(new Tomcat()))(tomcat =>
        ZIO
          .attemptBlocking(tomcat.stop())
          .orDie
          .ensuring(ZIO.attemptBlocking(tomcat.destroy()).orDie)
      )
      hosted <- ZIO
        .attemptBlocking:
          val schema = """{"type":"object","properties":{}}"""
          val greeting = McpSchema.Tool
            .builder()
            .name("greet")
            .description("Return a deterministic greeting")
            .inputSchema(mapper, schema)
            .build()
          val rejection = McpSchema.Tool
            .builder()
            .name("reject")
            .description("Return a tool execution error")
            .inputSchema(mapper, schema)
            .build()
          val success = Mono.just(
            McpSchema.CallToolResult
              .builder()
              .addTextContent("Hello from Java SDK")
              .isError(false)
              .build()
          )
          val failure = Mono.just(
            McpSchema.CallToolResult
              .builder()
              .addTextContent("Rejected by the tool")
              .isError(true)
              .build()
          )
          val resource = McpSchema.Resource
            .builder()
            .uri("memory://fixture/project")
            .name("Project context")
            .mimeType("text/plain")
            .build()
          val resourceResult = Mono.just(
            new McpSchema.ReadResourceResult(
              java.util.List.of[McpSchema.ResourceContents](
                new McpSchema.TextResourceContents(
                  "memory://fixture/project",
                  "text/plain",
                  "Context from Java SDK"
                )
              )
            )
          )
          val specification: McpServer.AsyncSpecification[?] = provider.port match
            case port: McpServerTransportProvider           => McpServer.async(port)
            case port: McpStreamableServerTransportProvider => McpServer.async(port)
          val sdk = specification
            .jsonMapper(mapper)
            .serverInfo("java-sdk-peer", "0.17.2")
            .capabilities(
              McpSchema.ServerCapabilities.builder().tools(false).resources(false, true).build()
            )
            .toolCall(greeting, (_, _) => success)
            .toolCall(rejection, (_, _) => failure)
            .resources(
              new McpServerFeatures.AsyncResourceSpecification(resource, (_, _) => resourceResult)
            )
            .build()
          tomcat.setBaseDir(directory.toString)
          tomcat.setPort(0)
          val connector = tomcat.getConnector()
          val _         = connector.setProperty("address", "127.0.0.1")
          val context   = tomcat.addContext("", directory.toString)
          val servlet   = Tomcat.addServlet(context, "mcp", provider.servlet)
          servlet.setAsyncSupported(true)
          servlet.setLoadOnStartup(1)
          transport match
            case WireTransport.Streamable => context.addServletMappingDecoded("/mcp", "mcp")
            case WireTransport.LegacySse =>
              context.addServletMappingDecoded("/sse", "mcp")
              context.addServletMappingDecoded("/messages", "mcp")
          tomcat.start()
          val route = transport match
            case WireTransport.Streamable => "/mcp"
            case WireTransport.LegacySse  => "/sse"
          Server(tomcat, directory, s"http://127.0.0.1:${connector.getLocalPort()}$route", sdk)
        .tap(_ => owner.set(ProviderOwner.Servlet))
        .uninterruptible
    yield hosted

  def spec: Spec[TestEnvironment & Scope, Any] = suite("McpJavaSdkInteropSpec")(
    test("unchanged Java SDK legacy SSE initializes and invokes through its advertised endpoint"):
      for
        observed <- ZIO.scoped:
          for
            peer      <- server(None, WireTransport.LegacySse)
            http      <- ZIO.service[Client]
            exchanges <- Ref.make(Chunk.empty[Exchange])
            transport <- McpLegacySseTransport.open(recording(http, exchanges), peer.endpoint)
            client    <- McpClient.open(transport, McpImplementation("scala-legacy", "1"))
            tools     <- client.discoverTools(1, 2)
            result    <- client.callTool("greet", Some(Json.Obj()))
            calls     <- exchanges.get
          yield (peer, tools, result, calls, client.initialization.protocolVersion)
        (peer, tools, result, calls, version) = observed
        destroyed <- ZIO.attemptBlocking(peer.tomcat.getServer().getState())
        removed   <- ZIO.attemptBlocking(Files.notExists(peer.directory))
      yield assertTrue(
        version == McpProtocolVersion.V20241105,
        tools.map(_.name).toSet == Set("greet", "reject"),
        result.content.map(_.block) == Chunk(McpContentBlock.Text("Hello from Java SDK")),
        calls.count(value => value.method == Method.GET && value.status.contains(Status.Ok)) == 1,
        calls.filter(_.method == Method.POST).forall(_.status.forall(_.isSuccess)),
        destroyed == LifecycleState.DESTROYED,
        removed
      )
    ,
    test(
      "unchanged Java SDK executes, distinguishes errors, and permits explicit expired-session replacement"
    ):
      for
        observed <- ZIO.scoped:
          for
            peer      <- server(None)
            http      <- ZIO.service[Client]
            exchanges <- Ref.make(Chunk.empty[Exchange])
            recorded = recording(http, exchanges)
            transport         <- McpStreamableHttpTransport.open(recorded, peer.endpoint)
            client            <- McpClient.open(transport, McpImplementation("scala-interop", "1"))
            page              <- client.listToolsPage()
            resources         <- client.discoverResources(1, 1)
            resourceTemplates <- client.listResourceTemplatesPage()
            context           <- client.readResource("memory://fixture/project")
            remote <- ZIO
              .fromOption(page.tools.find(_.name == "greet"))
              .orElseFail(AgentError.McpError("SDK greeting was not discovered"))
            old <- ZIO.fromEither(
              Toolkit(
                McpTool.bind(
                  client,
                  remote.name,
                  ToolDef("greeting", "Host-selected greeting", remote.inputSchema)
                )
              )
            )
            greeting     <- client.callTool("greet", Some(Json.Obj()))
            rejected     <- client.callTool("reject", Some(Json.Obj()))
            unknown      <- client.callTool("missing", Some(Json.Obj())).exit
            beforeExpiry <- exchanges.get
            oldSession <- ZIO
              .fromOption(beforeExpiry.flatMap(_.session).headOption)
              .orElseFail(AgentError.McpError("SDK session header was not observed"))
            deleted        <- deleteSession(http, peer.endpoint, oldSession)
            expired        <- client.callTool("greet", Some(Json.Obj())).exit
            afterExpiry    <- exchanges.get
            _              <- client.close
            freshTransport <- McpStreamableHttpTransport.open(recorded, peer.endpoint)
            freshClient   <- McpClient.open(freshTransport, McpImplementation("scala-interop", "1"))
            freshPage     <- freshClient.listToolsPage()
            freshGreeting <- freshClient.callTool("greet", Some(Json.Obj()))
            beforeStale   <- exchanges.get
            freshSession <- ZIO
              .fromOption(beforeStale.drop(afterExpiry.size).flatMap(_.session).headOption)
              .orElseFail(AgentError.McpError("Replacement SDK session header was not observed"))
            stale      <- old.executeSafe("greeting", Json.Obj())
            afterStale <- exchanges.get
            _          <- freshClient.close
            terminated <- freshTransport.terminateSession
          yield (
            peer,
            assertTrue(
              client.initialization.serverInfo == McpImplementation("java-sdk-peer", "0.17.2"),
              client.initialization.capabilities.tools.nonEmpty,
              client.initialization.capabilities.resources.exists(_.listChanged.contains(true)),
              resources.map(_.uri) == Chunk("memory://fixture/project"),
              resourceTemplates.items.isEmpty,
              context.contents.map(_.body) == Chunk(McpResourceBody.Text("Context from Java SDK")),
              page.tools.map(_.name).toSet == Set("greet", "reject"),
              greeting.outcome == McpCallOutcome.Success,
              greeting.content.map(_.block) == Chunk(McpContentBlock.Text("Hello from Java SDK")),
              rejected.outcome == McpCallOutcome.Error,
              rejected.content.map(_.block) == Chunk(McpContentBlock.Text("Rejected by the tool")),
              unknown.causeOption
                .flatMap(_.failureOption)
                .contains(
                  AgentError.McpRemoteError(
                    BigInt(-32602),
                    "Unknown tool: invalid_tool_name",
                    AgentError.McpErrorData.Present(Json.Str("Tool not found: missing"))
                  )
                ),
              deleted == Status.Ok,
              expired == Exit.fail(AgentError.McpSessionExpired()),
              afterExpiry.drop(beforeExpiry.size) == Chunk(
                Exchange(Method.POST, Some(oldSession), None),
                Exchange(Method.POST, Some(oldSession), Some(Status.NotFound))
              ),
              freshClient.initialization == client.initialization,
              freshPage.tools == page.tools,
              freshGreeting == greeting,
              freshSession != oldSession,
              beforeStale.drop(afterExpiry.size).flatMap(_.session).forall(_ == freshSession),
              stale.state == ToolResultState.Error,
              afterStale == beforeStale,
              terminated == McpHttpTermination.Deleted
            )
          )
        (peer, result) = observed
        state  <- ZIO.attemptBlocking(peer.tomcat.getServer().getState())
        exists <- ZIO.attemptBlocking(Files.exists(peer.directory))
      yield result && assertTrue(state == LifecycleState.DESTROYED, !exists)
    ,
    test("manager replacement joins expired SDK ownership and never retargets stale bindings"):
      for
        observed <- ZIO.scoped:
          for
            peer           <- server(None)
            http           <- ZIO.service[Client]
            exchanges      <- Ref.make(Chunk.empty[Exchange])
            acquisitions   <- Ref.make(0)
            releases       <- Ref.make(Chunk.empty[Int])
            transports     <- Ref.make(Chunk.empty[McpStreamableHttpTransport])
            removalStarted <- Promise.make[Nothing, Unit]
            cleaning       <- Promise.make[Nothing, Unit]
            release        <- Promise.make[Nothing, Unit]
            recorded = recording(http, exchanges)
            acquire = for
              index <- acquisitions.getAndUpdate(_ + 1)
              _ <- ZIO.addFinalizer(
                (if index == 0 then
                   removalStarted.isDone.flatMap(started =>
                     if started then cleaning.succeed(()) *> release.await else ZIO.unit
                   )
                 else ZIO.unit) *> releases.update(_ :+ index)
              )
              transport <- McpStreamableHttpTransport.open(recorded, peer.endpoint)
              _         <- transports.update(_ :+ transport)
              client <- McpClient.open(transport, McpImplementation("scala-managed-interop", "1"))
            yield client
            result <- ZIO.scoped(
              (for
                manager <- McpClientManager.make(maxClients = 1, maxTools = 2)
                _ <- manager.register(
                  "peer",
                  acquire,
                  1,
                  remote =>
                    Right(
                      Option.when(remote.name == "greet")(
                        ToolDef("greeting", "Initial SDK binding", remote.inputSchema)
                      )
                    )
                )
                old          <- manager.toolkit
                greeting     <- old.executeSafe("greeting", Json.Obj())
                beforeExpiry <- exchanges.get
                oldSession <- ZIO
                  .fromOption(beforeExpiry.flatMap(_.session).headOption)
                  .orElseFail(AgentError.McpError("Managed SDK session was not observed"))
                deleted     <- deleteSession(http, peer.endpoint, oldSession)
                expired     <- old.executeSafe("greeting", Json.Obj())
                afterExpiry <- exchanges.get
                _           <- removalStarted.succeed(())
                replacing <- (manager.remove("peer") *> manager.register(
                  "peer",
                  acquire,
                  1,
                  remote =>
                    Right(
                      Option.when(remote.name == "greet")(
                        ToolDef("greeting", "Replacement SDK binding", remote.inputSchema)
                      )
                    )
                )).forkScoped
                _                 <- cleaning.await
                waiting           <- replacing.poll
                beforeAcquire     <- acquisitions.get
                hidden            <- manager.toolkit
                _                 <- release.succeed(())
                _                 <- replacing.join
                current           <- manager.toolkit
                afterRegistration <- exchanges.get
                freshSession <- ZIO
                  .fromOption(
                    afterRegistration.drop(afterExpiry.size).flatMap(_.session).headOption
                  )
                  .orElseFail(AgentError.McpError("Managed replacement session was not observed"))
                stale        <- old.executeSafe("greeting", Json.Obj())
                afterStale   <- exchanges.get
                fresh        <- current.executeSafe("greeting", Json.Obj())
                _            <- manager.close
                released     <- releases.get
                beforeClosed <- exchanges.get
                closed       <- current.executeSafe("greeting", Json.Obj())
                owned        <- transports.get
                closedTransports <- ZIO.foreach(owned)(transport =>
                  ZIO
                    .scoped(transport.send(McpMessage.Notification("notifications/initialized")))
                    .exit
                )
                afterClosed <- exchanges.get
              yield assertTrue(
                greeting.state == ToolResultState.Success,
                greeting.output == "Hello from Java SDK",
                deleted == Status.Ok,
                expired.state == ToolResultState.Error,
                expired.output.contains(AgentError.McpSessionExpired().message),
                afterExpiry.drop(beforeExpiry.size) == Chunk(
                  Exchange(Method.POST, Some(oldSession), None),
                  Exchange(Method.POST, Some(oldSession), Some(Status.NotFound))
                ),
                waiting.isEmpty,
                beforeAcquire == 1,
                hidden.isEmpty,
                current.definitions.map(_.description) == Chunk("Replacement SDK binding"),
                old.definitions.map(_.description) == Chunk("Initial SDK binding"),
                freshSession != oldSession,
                afterRegistration.drop(afterExpiry.size).filter(_.status.isEmpty).map(_.session) ==
                  Chunk(None, Some(freshSession), Some(freshSession)),
                stale.state == ToolResultState.Error,
                afterStale == afterRegistration,
                fresh.state == ToolResultState.Success,
                fresh.output == greeting.output,
                released == Chunk(0, 1),
                closed.state == ToolResultState.Error,
                closedTransports.size == 2,
                closedTransports.forall(_.isFailure),
                afterClosed == beforeClosed
              )).ensuring(release.succeed(()).unit)
            )
          yield (peer, result)
        (peer, result) = observed
        state  <- ZIO.attemptBlocking(peer.tomcat.getServer().getState())
        exists <- ZIO.attemptBlocking(Files.exists(peer.directory))
      yield result && assertTrue(state == LifecycleState.DESTROYED, !exists)
    ,
    test(
      "cancelled SDK listeners join local cleanup and permit manual same-session listening again"
    ):
      for
        observed <- ZIO.scoped:
          for
            peer       <- server(Some(java.time.Duration.ofMillis(100)))
            http       <- ZIO.service[Client]
            exchanges  <- Ref.make(Chunk.empty[Exchange])
            closedGets <- Ref.make(0)
            messages   <- ZIO.acquireRelease(Queue.unbounded[McpMessage])(_.shutdown)
            transport <- McpStreamableHttpTransport.open(
              recording(http, exchanges, closedGets.update(_ + 1)),
              peer.endpoint
            )
            tapped = new McpTransport:
              def send(message: McpMessage): ZIO[Scope, AgentError, Unit] = transport.send(message)
              override def send(
                  message: McpMessage,
                  onIssued: UIO[Unit]
              ): ZIO[Scope, AgentError, Unit] =
                transport.send(message, onIssued)
              def incoming: ZStream[Any, AgentError, McpMessage] =
                transport.incoming.tap(message => messages.offer(message).unit)
            client      <- McpClient.open(tapped, McpImplementation("scala-listener-interop", "1"))
            initialized <- exchanges.get
            session <- ZIO
              .fromOption(initialized.flatMap(_.session).headOption)
              .orElseFail(AgentError.McpError("SDK listener session was not observed"))
            resourceChanges <- client.observeResourceChanges
            rounds <- ZIO.foreach(Chunk(0, 1)): index =>
              ZIO.scoped:
                for
                  // The prior GET is joined, then its round's POST reply crosses the same FIFO
                  // incoming consumer. Draining here removes every prior ping and notification.
                  _        <- messages.takeAll
                  listener <- transport.listen.forkScoped
                  _ <- messages.take.repeatUntil:
                    case McpMessage.Request(_, "ping", _) => true
                    case _                                => false
                  _ <- ZIO
                    .attemptBlocking(
                      peer.sdk.notifyToolsListChanged().block(java.time.Duration.ofSeconds(5))
                    )
                    .unit
                  notification <- messages.take.repeatUntil:
                    case McpMessage.Notification("notifications/tools/list_changed", _) => true
                    case _                                                              => false
                  _ <- ZIO
                    .attemptBlocking(
                      peer.sdk.notifyResourcesListChanged().block(java.time.Duration.ofSeconds(5))
                    )
                    .unit
                  resourceChange <- resourceChanges.take(1).runCollect
                  running        <- listener.poll
                  cancelled      <- listener.interrupt
                  closed         <- closedGets.get
                  greeting       <- client.callTool("greet", Some(Json.Obj()))
                yield assertTrue(
                  notification == McpMessage.Notification("notifications/tools/list_changed"),
                  resourceChange == Chunk(McpResourceChange.ListChanged(None)),
                  running.isEmpty,
                  cancelled.isInterruptedOnly,
                  closed == index + 1,
                  greeting.outcome == McpCallOutcome.Success,
                  greeting.content.map(_.block) == Chunk(
                    McpContentBlock.Text("Hello from Java SDK")
                  )
                )
            recorded <- exchanges.get
            gets = recorded.filter(_.method == Method.GET)
            _          <- client.close
            terminated <- transport.terminateSession
          yield (
            peer,
            rounds.foldLeft(assertTrue(true))(_ && _) && assertTrue(
              gets == Chunk(
                Exchange(Method.GET, Some(session), None),
                Exchange(Method.GET, Some(session), Some(Status.Ok)),
                Exchange(Method.GET, Some(session), None),
                Exchange(Method.GET, Some(session), Some(Status.Ok))
              ),
              terminated == McpHttpTermination.Deleted
            )
          )
        (peer, result) = observed
        state  <- ZIO.attemptBlocking(peer.tomcat.getServer().getState())
        exists <- ZIO.attemptBlocking(Files.exists(peer.directory))
      yield result && assertTrue(state == LifecycleState.DESTROYED, !exists)
  ).provide(Client.default) @@ TestAspect.sequential @@ TestAspect.withLiveClock @@ TestAspect
    .timeout(30.seconds)
