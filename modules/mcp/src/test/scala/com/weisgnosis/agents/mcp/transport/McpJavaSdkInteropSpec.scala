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
import io.modelcontextprotocol.json.McpJsonMapper
import io.modelcontextprotocol.server.McpServer
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider
import io.modelcontextprotocol.spec.McpSchema
import java.nio.file.{Files, Path}
import org.apache.catalina.LifecycleState
import org.apache.catalina.startup.Tomcat
import reactor.core.publisher.Mono
import scala.jdk.CollectionConverters.*
import zio.*
import zio.http.Client
import zio.json.ast.Json
import zio.test.*

object McpJavaSdkInteropSpec extends ZIOSpecDefault:
  final private case class Server(tomcat: Tomcat, directory: Path, endpoint: String)

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

  private def server: ZIO[Scope, Throwable, Server] =
    for
      directory <- temporaryDirectory
      tomcat <- ZIO.acquireRelease(ZIO.attemptBlocking(new Tomcat()))(tomcat =>
        ZIO
          .attemptBlocking(tomcat.stop())
          .orDie
          .ensuring(ZIO.attemptBlocking(tomcat.destroy()).orDie)
      )
      endpoint <- ZIO.attemptBlocking:
        val mapper = McpJsonMapper.createDefault()
        val provider = HttpServletStreamableServerTransportProvider
          .builder()
          .jsonMapper(mapper)
          .mcpEndpoint("/mcp")
          .build()
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
        val _ = McpServer
          .async(provider)
          .jsonMapper(mapper)
          .serverInfo("java-sdk-peer", "0.17.2")
          .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
          .toolCall(greeting, (_, _) => success)
          .toolCall(rejection, (_, _) => failure)
          .build()
        tomcat.setBaseDir(directory.toString)
        tomcat.setPort(0)
        val connector = tomcat.getConnector()
        val _         = connector.setProperty("address", "127.0.0.1")
        val context   = tomcat.addContext("", directory.toString)
        val servlet   = Tomcat.addServlet(context, "mcp", provider)
        servlet.setAsyncSupported(true)
        context.addServletMappingDecoded("/mcp", "mcp")
        // The servlet's destroy closes the provider. No sessions or keepalive scheduler exist
        // before startup, so Tomcat is the only provider cleanup owner, including failed startup.
        tomcat.start()
        s"http://127.0.0.1:${connector.getLocalPort()}/mcp"
    yield Server(tomcat, directory, endpoint)

  def spec: Spec[TestEnvironment & Scope, Any] = suite("McpJavaSdkInteropSpec")(
    test("unchanged Java SDK HTTP server initializes, executes, distinguishes errors, and closes"):
      for
        observed <- ZIO.scoped:
          for
            peer       <- server
            http       <- ZIO.service[Client]
            transport  <- McpStreamableHttpTransport.open(http, peer.endpoint)
            client     <- McpClient.open(transport, McpImplementation("scala-interop", "1"))
            page       <- client.listToolsPage()
            greeting   <- client.callTool("greet", Some(Json.Obj()))
            rejected   <- client.callTool("reject", Some(Json.Obj()))
            unknown    <- client.callTool("missing", Some(Json.Obj())).exit
            _          <- client.close
            terminated <- transport.terminateSession
          yield (
            peer,
            assertTrue(
              client.initialization.serverInfo == McpImplementation("java-sdk-peer", "0.17.2"),
              client.initialization.capabilities.tools.nonEmpty,
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
              terminated == McpHttpTermination.Deleted
            )
          )
        (peer, result) = observed
        state  <- ZIO.attemptBlocking(peer.tomcat.getServer().getState())
        exists <- ZIO.attemptBlocking(Files.exists(peer.directory))
      yield result && assertTrue(state == LifecycleState.DESTROYED, !exists)
  ).provide(Client.default) @@ TestAspect.withLiveClock @@ TestAspect.timeout(30.seconds)
