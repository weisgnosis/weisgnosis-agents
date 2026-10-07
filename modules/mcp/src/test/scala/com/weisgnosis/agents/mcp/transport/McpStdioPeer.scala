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
import com.weisgnosis.agents.mcp.protocol.{McpInitialization, McpMessage}
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

  private enum ClientPhase:
    case Initialize, Initialized, Ready

  private def client(phase: ClientPhase): IO[AgentError, Nothing] =
    readMessage.flatMap: message =>
      (phase, message) match
        case (ClientPhase.Initialize, McpMessage.Request(id, "initialize", Some(params)))
            if params
              .get("protocolVersion")
              .contains(Json.Str(McpInitialization.protocolVersion)) =>
          emit(
            McpMessage.Success(
              id,
              Json.Obj(
                "protocolVersion" -> Json.Str(McpInitialization.protocolVersion),
                "serverInfo" -> Json
                  .Obj("name" -> Json.Str("test-peer"), "version" -> Json.Str("1")),
                "capabilities" -> Json.Obj("tools" -> Json.Obj())
              )
            )
          ) *> client(ClientPhase.Initialized)
        case (
              ClientPhase.Initialized,
              McpMessage.Notification("notifications/initialized", None)
            ) =>
          client(ClientPhase.Ready)
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
          ) *> client(ClientPhase.Ready)
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
          ) *> client(ClientPhase.Ready)
        case _ => ZIO.fail(AgentError.McpError("Unexpected message in test client lifecycle"))

  def run: ZIO[ZIOAppArgs, AgentError, Unit] =
    for
      arguments <- getArgs
      pid       <- ZIO.attempt(ProcessHandle.current().pid()).mapError(failure)
      _ <- emit(
        McpMessage.Notification("test/ready", Some(Json.Obj("pid" -> Json.Str(pid.toString))))
      )
      _ <- arguments.headOption match
        case Some("echo")      => echo
        case Some("client")    => client(ClientPhase.Initialize)
        case Some("malformed") => Console.printLine("not JSON").mapError(failure) *> ZIO.never
        case Some("eof")       => ZIO.unit
        case Some("blocked-write") =>
          ZIO.attemptBlocking(java.lang.System.in.read()).mapError(failure) *>
            emit(McpMessage.Notification("test/write-started")) *> ZIO.never
        case _ => ZIO.fail(AgentError.McpError("Unknown test peer mode"))
    yield ()
