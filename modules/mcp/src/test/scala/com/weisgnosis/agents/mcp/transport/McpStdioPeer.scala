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
import com.weisgnosis.agents.mcp.protocol.{
  McpBatchMember,
  McpInitialization,
  McpMessage,
  McpPacket,
  McpProtocolVersion,
  McpRequestId
}
import zio.*
import zio.json.ast.Json

/** Test subprocess; readiness and write-entry signals use protocol messages, never sleeps. */
object McpStdioPeer extends ZIOAppDefault:
  private def failure(error: Throwable): AgentError.McpError =
    AgentError.McpError(s"Test peer I/O failed: ${error.getClass.getName}")

  private def emit(message: McpMessage): IO[AgentError, Unit] =
    ZIO
      .fromEither(McpMessage.encode(message))
      .flatMap(value => Console.printLine(value).mapError(failure))

  private def readMessage: IO[AgentError, McpMessage] =
    Console.readLine.mapError(failure).flatMap(line => ZIO.fromEither(McpMessage.decode(line)))

  private def echo: IO[AgentError, Nothing] =
    readMessage
      .flatMap:
        case McpMessage.Request(id, "environment", Some(params)) =>
          ZIO
            .foreach(params.fields): (field, key) =>
              key match
                case Json.Str(name) =>
                  ZIO
                    .attempt(Option(java.lang.System.getenv(name)))
                    .mapError(failure)
                    .map(value => field -> value.fold[Json](Json.Null)(Json.Str(_)))
                case _ => ZIO.fail(AgentError.McpError("Invalid test environment key"))
            .flatMap(fields => emit(McpMessage.Success(id, Json.Obj(fields))))
        case McpMessage.Request(id, method, _) =>
          emit(McpMessage.Success(id, Json.Obj("method" -> Json.Str(method))))
        case _: McpMessage.Notification => ZIO.unit
        case _ => ZIO.fail(AgentError.McpError("Unexpected test peer message"))
      .forever

  private def batch: IO[AgentError, Unit] =
    for
      request <- readMessage
      id <- request match
        case McpMessage.Request(value, "batch", _) => ZIO.succeed(value)
        case _ => ZIO.fail(AgentError.McpError("Expected batch probe"))
      _ <- Console
        .printLine(
          """[{"jsonrpc":"2.0","id":70,"method":"ping"},42,{"jsonrpc":"2.0","method":"notifications/ignored"}]"""
        )
        .mapError(failure)
      wire   <- Console.readLine.mapError(failure)
      actual <- ZIO.fromEither(JsonBoundary.decode(wire).left.map(AgentError.InvalidMessage(_)))
      expected = Json.Arr(
        Json.Obj("jsonrpc" -> Json.Str("2.0"), "id" -> Json.Num(70), "result" -> Json.Obj()),
        Json.Obj(
          "jsonrpc" -> Json.Str("2.0"),
          "id"      -> Json.Null,
          "error" -> Json.Obj("code" -> Json.Num(-32600), "message" -> Json.Str("Invalid Request"))
        )
      )
      _ <- ZIO
        .fail(AgentError.McpError("Subprocess received ungrouped or incomplete responses"))
        .unless(actual == expected)
      reply <- ZIO.fromEither(
        McpMessage.encode(McpMessage.Success(id, Json.Obj("grouped" -> Json.Bool(true))))
      )
      _ <- Console.printLine(s"[$reply]").mapError(failure)
      _ <- ZIO.never
    yield ()

  private enum ClientPhase:
    case Initialize, Initialized, Ready

  private def client(phase: ClientPhase, version: String): IO[AgentError, Nothing] =
    readMessage.flatMap: message =>
      (phase, message) match
        case (ClientPhase.Initialize, McpMessage.Request(id, "initialize", Some(params)))
            if params
              .get("protocolVersion")
              .contains(Json.Str(version)) =>
          emit(
            McpMessage.Success(
              id,
              Json.Obj(
                "protocolVersion" -> Json.Str(version),
                "serverInfo" -> Json
                  .Obj("name" -> Json.Str("test-peer"), "version" -> Json.Str("1")),
                "capabilities" -> Json.Obj("tools" -> Json.Obj())
              )
            )
          ) *> client(ClientPhase.Initialized, version)
        case (
              ClientPhase.Initialized,
              McpMessage.Notification("notifications/initialized", None)
            ) =>
          client(ClientPhase.Ready, version)
        case (ClientPhase.Ready, McpMessage.Request(id, "tools/list", _)) =>
          emit(
            McpMessage.Success(
              id,
              Json.Obj(
                "tools" -> Json.Arr(
                  Json.Obj(
                    "name"        -> Json.Str("echo"),
                    "inputSchema" -> Json.Obj("type" -> Json.Str("object"))
                  )
                )
              )
            )
          ) *> client(ClientPhase.Ready, version)
        case (ClientPhase.Ready, McpMessage.Request(id, "tools/call", Some(params)))
            if params.get("name").contains(Json.Str("echo")) =>
          emit(
            McpMessage.Success(
              id,
              Json.Obj(
                "content" -> Json.Arr(
                  Json.Obj(
                    "type" -> Json.Str("text"),
                    "text" -> Json.Str("ok")
                  )
                )
              )
            )
          ) *> client(ClientPhase.Ready, version)
        case _ => ZIO.fail(AgentError.McpError("Unexpected message in test client lifecycle"))

  private enum HandshakeMarker:
    case Initialized, Pong

  private def finishNegotiation(
      pending: Set[HandshakeMarker],
      selected: McpProtocolVersion
  ): IO[AgentError, Nothing] =
    if pending.isEmpty then client(ClientPhase.Ready, selected.wire)
    else
      Console.readLine
        .mapError(failure)
        .flatMap(line => ZIO.fromEither(McpPacket.decode(line, selected)))
        .flatMap:
          case McpPacket.Single(McpMessage.Notification("notifications/initialized", None))
              if pending.contains(HandshakeMarker.Initialized) =>
            finishNegotiation(pending - HandshakeMarker.Initialized, selected)
          case McpPacket.Batch(members)
              if pending.contains(HandshakeMarker.Pong) && members == Chunk(
                McpBatchMember.Message(
                  McpMessage.Success(McpRequestId.Number(70), Json.Obj())
                )
              ) =>
            finishNegotiation(pending - HandshakeMarker.Pong, selected)
          case _ =>
            ZIO.fail(AgentError.McpError("Unexpected alternative initialization acknowledgement"))

  private def negotiate: IO[AgentError, Nothing] =
    readMessage.flatMap:
      case McpMessage.Request(id, "initialize", Some(params)) =>
        val selected = params.get("protocolVersion") match
          case Some(Json.Str("2025-03-26")) => McpProtocolVersion.V20250618
          case _                            => McpProtocolVersion.V20250326
        for
          success <- ZIO.fromEither(
            McpMessage.encode(
              McpMessage.Success(
                id,
                Json.Obj(
                  "protocolVersion" -> Json.Str(selected.wire),
                  "serverInfo" -> Json
                    .Obj("name" -> Json.Str("alternative-peer"), "version" -> Json.Str("1")),
                  "capabilities" -> Json.Obj("tools" -> Json.Obj())
                )
              )
            )
          )
          suffix =
            if selected == McpProtocolVersion.V20250326 then
              "\n[{\"jsonrpc\":\"2.0\",\"id\":70,\"method\":\"ping\"}]"
            else ""
          _ <- Console.printLine(success + suffix).mapError(failure)
          pending =
            if selected == McpProtocolVersion.V20250326 then
              Set(HandshakeMarker.Initialized, HandshakeMarker.Pong)
            else Set(HandshakeMarker.Initialized)
          result <- finishNegotiation(pending, selected)
        yield result
      case _ => ZIO.fail(AgentError.McpError("Expected alternative initialization"))

  def run: ZIO[ZIOAppArgs, AgentError, Unit] =
    for
      arguments <- getArgs
      pid       <- ZIO.attempt(ProcessHandle.current().pid()).mapError(failure)
      _ <- emit(
        McpMessage.Notification("test/ready", Some(Json.Obj("pid" -> Json.Str(pid.toString))))
      )
      _ <- arguments.headOption match
        case Some("echo")  => echo
        case Some("batch") => batch
        case Some("stderr-pressure") =>
          Console.printError("x" * (2 * 1024 * 1024)).mapError(failure) *> echo
        case Some("stderr-inherit") =>
          Console.printError("MCP stderr inheritance probe\n").mapError(failure) *> echo
        case Some("negotiate") => negotiate
        case Some("client") =>
          client(
            ClientPhase.Initialize,
            arguments.drop(1).headOption.getOrElse(McpInitialization.protocolVersion)
          )
        case Some("malformed") => Console.printLine("not JSON").mapError(failure) *> ZIO.never
        case Some("eof")       => ZIO.unit
        case Some("blocked-write") =>
          ZIO.attemptBlocking(java.lang.System.in.read()).mapError(failure) *>
            emit(McpMessage.Notification("test/write-started")) *> ZIO.never
        case _ => ZIO.fail(AgentError.McpError("Unknown test peer mode"))
    yield ()
