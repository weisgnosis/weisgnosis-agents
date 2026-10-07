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
import com.weisgnosis.agents.mcp.client.{McpClient, McpConnection}
import com.weisgnosis.agents.mcp.protocol.{
  McpCallOutcome,
  McpContentBlock,
  McpImplementation,
  McpMessage
}
import java.nio.file.Paths
import scala.jdk.OptionConverters.*
import zio.*
import zio.json.ast.Json
import zio.stream.ZStream
import zio.test.*

object McpStdioTransportSpec extends ZIOSpecDefault:
  final private case class Peer(
      transport: McpTransport,
      ready: Promise[AgentError, Long],
      writeStarted: Promise[Nothing, Unit]
  )

  private def property(name: String): IO[AgentError, String] =
    ZIO
      .attempt(Option(java.lang.System.getProperty(name)))
      .mapError(error => AgentError.McpError(error.toString))
      .someOrFail(AgentError.McpError(s"Missing test JVM property: $name"))

  private def command(mode: String): IO[AgentError, Chunk[String]] =
    for
      home      <- property("java.home")
      os        <- property("os.name")
      classpath <- property("java.class.path")
      java <- ZIO
        .attempt(
          Paths.get(home, "bin", if os.startsWith("Windows") then "java.exe" else "java").toString
        )
        .mapError(error => AgentError.McpError(error.toString))
    yield Chunk(
      java,
      "-Xms16m",
      "-Xmx64m",
      "-XX:ActiveProcessorCount=2",
      "-cp",
      classpath,
      "com.weisgnosis.agents.mcp.transport.McpStdioPeer",
      mode
    )

  private def alive(pid: Long): IO[AgentError, Boolean] =
    ZIO
      .attempt(ProcessHandle.of(pid).toScala.exists(_.isAlive))
      .mapError(error => AgentError.McpError(error.toString))

  private def environmentValue(name: String): IO[AgentError, Option[String]] =
    ZIO
      .attempt(Option(java.lang.System.getenv(name)))
      .mapError(error => AgentError.McpError(error.getClass.getName))

  private def peer(
      mode: String,
      environment: Map[String, String] = Map.empty
  ): ZIO[Scope, AgentError, Peer] =
    for
      arguments <- command(mode)
      underlying <- McpStdioTransport.open(
        arguments,
        maxFrameBytes = 16 * 1024 * 1024,
        environment = environment
      )
      ready        <- Promise.make[AgentError, Long]
      writeStarted <- Promise.make[Nothing, Unit]
      observed = new McpTransport:
        def send(message: McpMessage): ZIO[Scope, AgentError, Unit] = underlying.send(message)
        def incoming: ZStream[Any, AgentError, McpMessage] =
          underlying.incoming
            .tap:
              case McpMessage.Notification("test/ready", Some(params)) =>
                val pid = params.fields.collectFirst:
                  case ("pid", Json.Str(value)) => value
                ZIO
                  .fromOption(pid.flatMap(_.toLongOption))
                  .orElseFail(AgentError.McpError("Invalid test peer identity"))
                  .flatMap(value => ready.succeed(value).unit)
              case McpMessage.Notification("test/write-started", _) => writeStarted.succeed(()).unit
              case _                                                => ZIO.unit
            .onError(cause => ready.failCause(cause).unit)
            .ensuring(ready.fail(AgentError.McpError("Peer ended before readiness")).unit)
    yield Peer(observed, ready, writeStarted)

  def spec: Spec[TestEnvironment, Any] = suite("McpStdioTransportSpec")(
    test(
      "real processes support concurrent RPC and initialized tool calls and are reaped after release"
    ) {
      for
        outcome <- ZIO.scoped:
          for
            os <- property("os.name")
            inheritedKey = if os.startsWith("Windows") then "SystemRoot" else "HOME"
            inherited      <- environmentValue(inheritedKey)
            parentPath     <- environmentValue("PATH")
            parentOverride <- environmentValue("WEISGNOSIS_MCP_STDIO_TEST_OVERRIDE")
            server <- peer(
              "echo",
              Map(
                "PATH"                               -> "child-only-path",
                "WEISGNOSIS_MCP_STDIO_TEST_OVERRIDE" -> "child-only-value",
                "WEISGNOSIS_MCP_STDIO_TEST_EMPTY"    -> ""
              )
            )
            connection   <- McpConnection.make(server.transport)
            pid          <- server.ready.await
            running      <- alive(pid)
            secondReader <- server.transport.incoming.runCollect.exit
            invalid <- server.transport
              .send(
                McpMessage
                  .Notification("invalid", Some(Json.Obj("x" -> Json.Null, "x" -> Json.Null)))
              )
              .exit
            methods = Chunk("one", "two", "three")
            replies <- ZIO.foreachPar(methods)(connection.request(_))
            childEnvironment <- connection.request(
              "environment",
              Some(
                Json.Obj(
                  "inherited" -> Json.Str(inheritedKey),
                  "path"      -> Json.Str("PATH"),
                  "override"  -> Json.Str("WEISGNOSIS_MCP_STDIO_TEST_OVERRIDE"),
                  "empty"     -> Json.Str("WEISGNOSIS_MCP_STDIO_TEST_EMPTY")
                )
              )
            )
            parentPathAfter     <- environmentValue("PATH")
            parentOverrideAfter <- environmentValue("WEISGNOSIS_MCP_STDIO_TEST_OVERRIDE")
          yield (
            pid,
            assertTrue(
              running,
              inherited.nonEmpty,
              parentPath.nonEmpty,
              parentPathAfter == parentPath,
              parentOverrideAfter == parentOverride,
              childEnvironment match
                case McpMessage.Success(_, result) =>
                  result == Json.Obj(
                    "inherited" -> inherited.fold[Json](Json.Null)(Json.Str(_)),
                    "path"      -> Json.Str("child-only-path"),
                    "override"  -> Json.Str("child-only-value"),
                    "empty"     -> Json.Str("")
                  )
                case _ => false
              ,
              secondReader.isFailure,
              invalid.isFailure,
              replies
                .zip(methods)
                .forall:
                  case (McpMessage.Success(_, result), method) =>
                    result == Json.Obj("method" -> Json.Str(method))
                  case _ => false
            )
          )
        remains <- alive(outcome._1)
        initialized <- ZIO.scoped:
          for
            server <- peer("client")
            client <- McpClient.open(server.transport, McpImplementation("stdio-test", "1"))
            pid    <- server.ready.await
            page   <- client.listToolsPage()
            result <- client.callTool("echo")
          yield (
            pid,
            assertTrue(
              page.tools.map(_.name) == Chunk("echo"),
              result.outcome == McpCallOutcome.Success,
              result.content.map(_.block) == Chunk(McpContentBlock.Text("ok"))
            )
          )
        clientRemains <- alive(initialized._1)
      yield outcome._2 && initialized._2 && assertTrue(!remains, !clientRemains)
    },
    test(
      "invalid configuration, malformed stdout and process EOF cannot become successful replies"
    ) {
      for
        invalid         <- ZIO.scoped(McpStdioTransport.open(Chunk.empty)).exit
        emptyExecutable <- ZIO.scoped(McpStdioTransport.open(Chunk(""))).exit
        invalidLimit <- ZIO.scoped(McpStdioTransport.open(Chunk("unused"), maxFrameBytes = 0)).exit
        invalidEnvironments <- ZIO.foreach(
          Chunk(
            Map(""                 -> "private-value"),
            Map("INVALID=KEY"      -> "private-value"),
            Map("INVALID\u0000KEY" -> "private-value"),
            Map("VALID"            -> "private\u0000value"),
            Map("PATH"             -> "private-one", "path"   -> "private-two"),
            Map("I"                -> "private-one", "\u0131" -> "private-two")
          )
        ): overrides =>
          ZIO
            .scoped(McpStdioTransport.open(Chunk("unused"), environment = overrides))
            .exit
            .map:
              case Exit.Failure(cause) =>
                cause.failureOption.exists:
                  case AgentError.InvalidConfiguration(message) =>
                    message == "MCP stdio environment overrides are invalid"
                  case _ => false
              case Exit.Success(_) => false
        results <- ZIO.foreach(Chunk("malformed", "eof")): mode =>
          for
            outcome <- ZIO.scoped:
              for
                server     <- peer(mode)
                connection <- McpConnection.make(server.transport)
                pid        <- server.ready.await
                failed     <- connection.request("ping").exit
              yield (pid, failed)
            remains <- alive(outcome._1)
          yield assertTrue(outcome._2.isFailure, !remains)
      yield results.foldLeft(
        assertTrue(
          invalid.isFailure,
          emptyExecutable.isFailure,
          invalidLimit.isFailure,
          invalidEnvironments.forall(identity)
        )
      )(_ && _)
    },
    test(
      "blocked read and partial-write cancellation terminate and reap the child before returning"
    ) {
      for
        readResult <- ZIO.scoped:
          for
            server      <- peer("echo")
            reader      <- server.transport.incoming.runDrain.forkScoped
            pid         <- server.ready.await
            _           <- (ZIO.yieldNow *> reader.status).repeatUntil(_.isSuspended)
            interrupted <- reader.interrupt
            remains     <- alive(pid)
          yield assertTrue(interrupted.isInterrupted, !remains)
        writeResult <- ZIO.scoped:
          for
            server     <- peer("blocked-write")
            connection <- McpConnection.make(server.transport)
            pid        <- server.ready.await
            request <- connection
              .request(
                "blocked",
                Some(
                  Json.Obj(
                    "payload" -> Json.Str("x" * (8 * 1024 * 1024))
                  )
                )
              )
              .forkScoped
            _           <- server.writeStarted.await
            queued      <- server.transport.send(McpMessage.Notification("queued")).forkScoped
            _           <- (ZIO.yieldNow *> queued.status).repeatUntil(_.isSuspended)
            queuedExit  <- queued.interrupt
            afterQueued <- alive(pid)
            interrupted <- request.interrupt
            remains     <- alive(pid)
            rejected    <- connection.request("after cancellation").exit
          yield assertTrue(
            queuedExit.isInterrupted,
            afterQueued,
            interrupted.isInterrupted,
            !remains,
            rejected.isFailure
          )
      yield readResult && writeResult
    }
  ) @@ TestAspect.withLiveClock @@ TestAspect.timeout(30.seconds)
