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
  McpInitialization,
  McpSupportedVersions,
  McpMessage,
  McpPacket,
  McpProtocolVersion,
  McpRequestId
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
      environment: Map[String, String] = Map.empty,
      version: McpProtocolVersion = McpProtocolVersion.V20250618,
      stderr: McpStdioTransport.StderrPolicy = McpStdioTransport.StderrPolicy.Discard,
      supportedVersions: Option[McpSupportedVersions] = None
  ): ZIO[Scope, AgentError, Peer] =
    for
      arguments <- command(mode)
      underlying <- McpStdioTransport
        .open(
          arguments :+ version.wire,
          maxFrameBytes = 16 * 1024 * 1024,
          environment = environment,
          version = version,
          stderr = stderr,
          supportedVersions = supportedVersions
        )
        .withSystem(System.SystemLive)
      ready        <- Promise.make[AgentError, Long]
      writeStarted <- Promise.make[Nothing, Unit]
      observed = new McpTransport:
        override val protocolVersion: McpProtocolVersion = underlying.protocolVersion
        override def currentProtocolVersion: UIO[McpProtocolVersion] =
          underlying.currentProtocolVersion
        override def acquireProtocolSession: ZIO[Scope, AgentError, McpProtocolLease] =
          underlying.acquireProtocolSession
        def send(message: McpMessage): ZIO[Scope, AgentError, Unit] = underlying.send(message)
        override def sendPacket(packet: McpPacket): ZIO[Scope, AgentError, Unit] =
          underlying.sendPacket(packet)
        def incoming: ZStream[Any, AgentError, McpMessage] =
          incomingPackets.mapZIO(packet => ZIO.fromEither(McpPacket.single(packet)))
        override def incomingPackets: ZStream[Any, AgentError, McpPacket] =
          underlying.incomingPackets
            .tap:
              case McpPacket.Single(McpMessage.Notification("test/ready", Some(params))) =>
                val pid = params.fields.collectFirst:
                  case ("pid", Json.Str(value)) => value
                ZIO
                  .fromOption(pid.flatMap(_.toLongOption))
                  .orElseFail(AgentError.McpError("Invalid test peer identity"))
                  .flatMap(value => ready.succeed(value).unit)
              case McpPacket.Single(McpMessage.Notification("test/write-started", _)) =>
                writeStarted.succeed(()).unit
              case _ => ZIO.unit
            .onError(cause => ready.failCause(cause).unit)
            .ensuring(ready.fail(AgentError.McpError("Peer ended before readiness")).unit)
    yield Peer(observed, ready, writeStarted)

  def spec: Spec[TestEnvironment, Any] = suite("McpStdioTransportSpec")(
    test("real processes agree supported alternatives without transferring the first client lease"):
      ZIO
        .foreach(Chunk.fromArray(McpProtocolVersion.values)): preferred =>
          for
            observation <- ZIO.scoped:
              for
                supported <- ZIO.fromEither(
                  McpSupportedVersions.make(Set.from(McpProtocolVersion.values))
                )
                server <- peer(
                  "negotiate",
                  version = preferred,
                  supportedVersions = Some(supported)
                )
                client <- McpClient.open(server.transport, McpImplementation("first", "1"))
                pid    <- server.ready.await
                second <- McpClient.open(server.transport, McpImplementation("second", "1")).exit
                page   <- client.listToolsPage()
                result <- client.callTool("echo")
                agreed <- server.transport.currentProtocolVersion
                _      <- client.close
                late   <- server.transport.send(McpMessage.Notification("late")).exit
                closed <- server.transport.currentProtocolVersion
                selected =
                  if preferred == McpProtocolVersion.V20250326 then McpProtocolVersion.V20250618
                  else McpProtocolVersion.V20250326
              yield (
                pid,
                assertTrue(
                  client.initialization.protocolVersion == selected,
                  agreed == selected,
                  closed == selected,
                  second.isFailure,
                  page.tools.map(_.name) == Chunk("echo"),
                  result.content.map(_.block) == Chunk(McpContentBlock.Text("ok")),
                  late.isFailure
                )
              )
            remains <- alive(observation._1)
          yield observation._2 && assertTrue(!remains)
        .map(_.foldLeft(assertTrue(true))(_ && _))
    ,
    test("unleased negotiation readers cannot consume or close the real process reader"):
      ZIO.scoped:
        for
          supported <- ZIO.fromEither(
            McpSupportedVersions.make(
              Set(
                McpProtocolVersion.V20250326,
                McpProtocolVersion.V20250618
              )
            )
          )
          server   <- peer("client", supportedVersions = Some(supported))
          rejected <- server.transport.incomingPackets.runHead.exit
          opened   <- McpClient.open(server.transport, McpImplementation("host", "1")).exit
        yield assertTrue(rejected.isFailure, opened.isSuccess)
    ,
    test("invalid initialization encoding leaves negotiation available for a valid request"):
      ZIO.scoped:
        for
          supported <- ZIO.fromEither(McpSupportedVersions.make(Set(McpProtocolVersion.V20250618)))
          server    <- peer("client", supportedVersions = Some(supported))
          _         <- server.transport.acquireProtocolSession
          reader    <- server.transport.incomingPackets.runDrain.forkScoped
          _         <- server.ready.await
          valid <- ZIO.fromEither(
            McpInitialization.request(McpRequestId.Number(1), McpImplementation("host", "1"))
          )
          invalid = valid.copy(params =
            valid.params.map(params =>
              Json.Obj(
                params.fields ++ Chunk(
                  "protocolVersion" -> Json.Str(McpProtocolVersion.V20250618.wire)
                )
              )
            )
          )
          rejected <- server.transport.send(invalid).exit
          admitted <- server.transport.send(valid).exit
          _        <- reader.interrupt
        yield assertTrue(rejected.isFailure, admitted.isSuccess)
    ,
    test("native stderr policies preserve protocol progress and joined process reaping"):
      ZIO
        .foreach(
          Chunk(
            (McpStdioTransport.StderrPolicy.Discard, "stderr-pressure"),
            (McpStdioTransport.StderrPolicy.Inherit, "stderr-inherit")
          )
        ): (policy, mode) =>
          for
            observed <- ZIO.scoped:
              for
                server     <- peer(mode, stderr = policy)
                connection <- McpConnection.make(server.transport)
                pid        <- server.ready.await
                reply      <- connection.request("stderr-complete")
              yield (pid, reply)
            remains <- alive(observed._1)
            completed = observed._2 match
              case McpMessage.Success(id, result) =>
                id == McpRequestId.Number(BigInt(1)) &&
                result == Json.Obj("method" -> Json.Str("stderr-complete"))
              case _: McpMessage.Failure => false
          yield assertTrue(completed, !remains)
        .map(_.foldLeft(assertTrue(true))(_ && _))
    ,
    test("real March subprocess receives one response batch and its owner reaps it"):
      for
        result <- ZIO.scoped:
          for
            server     <- peer("batch", version = McpProtocolVersion.V20250326)
            connection <- McpConnection.make(server.transport)
            pid        <- server.ready.await
            reply      <- connection.request("batch")
          yield (
            pid,
            reply match
              case McpMessage.Success(_, result) => Some(result)
              case _                             => None
          )
        running <- alive(result._1)
      yield assertTrue(result._2.contains(Json.Obj("grouped" -> Json.Bool(true))), !running)
    ,
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
                (if os.startsWith("Windows") then "pAtH" else "PATH") -> "child-only-path",
                "WEISGNOSIS_MCP_STDIO_TEST_OVERRIDE"                  -> "child-only-value",
                "WEISGNOSIS_MCP_STDIO_TEST_EMPTY"                     -> ""
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
        initialized <- ZIO.foreach(
          Chunk(
            McpProtocolVersion.V20241105,
            McpProtocolVersion.V20250326,
            McpProtocolVersion.V20250618
          )
        ): version =>
          for
            observation <- ZIO.scoped:
              for
                server <- peer("client", version = version)
                client <- McpClient.open(server.transport, McpImplementation("stdio-test", "1"))
                pid    <- server.ready.await
                page   <- client.listToolsPage()
                result <- client.callTool("echo")
              yield (
                pid,
                assertTrue(
                  client.initialization.protocolVersion == version,
                  page.tools.map(_.name) == Chunk("echo"),
                  result.outcome == McpCallOutcome.Success,
                  result.content.map(_.block) == Chunk(McpContentBlock.Text("ok"))
                )
              )
            running <- alive(observation._1)
          yield observation._2 && assertTrue(!running)
      yield outcome._2 && initialized.foldLeft(assertTrue(!remains))(_ && _)

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
            _ <- server.writeStarted.await
            validationResult <- server.transport
              .send(
                McpMessage.Notification(
                  "invalid",
                  Some(Json.Obj("duplicate" -> Json.Num(1), "duplicate" -> Json.Num(2)))
                )
              )
              .exit
              .timeout(1.second)
            queued      <- server.transport.send(McpMessage.Notification("queued")).forkScoped
            _           <- (ZIO.yieldNow *> queued.status).repeatUntil(_.isSuspended)
            queuedExit  <- queued.interrupt
            afterQueued <- alive(pid)
            interrupted <- request.interrupt
            remains     <- alive(pid)
            rejected    <- connection.request("after cancellation").exit
          yield assertTrue(
            validationResult.exists(_.isFailure),
            queuedExit.isInterrupted,
            afterQueued,
            interrupted.isInterrupted,
            !remains,
            rejected.isFailure
          )
      yield readResult && writeResult
    }
  ) @@ TestAspect.withLiveClock @@ TestAspect.timeout(30.seconds)
