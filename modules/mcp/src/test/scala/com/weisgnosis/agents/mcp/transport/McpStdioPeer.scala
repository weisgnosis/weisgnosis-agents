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
import com.weisgnosis.agents.mcp.protocol.McpMessage
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

  private def echo: IO[AgentError, Nothing] =
    Console.readLine
      .mapError(failure)
      .flatMap(line =>
        ZIO
          .fromEither(McpMessage.decode(line))
          .flatMap:
            case McpMessage.Request(id, method, _) =>
              emit(McpMessage.Success(id, Json.Obj("method" -> Json.Str(method))))
            case _: McpMessage.Notification => ZIO.unit
            case _ => ZIO.fail(AgentError.McpError("Unexpected test peer message"))
      )
      .forever

  def run: ZIO[ZIOAppArgs, AgentError, Unit] =
    for
      arguments <- getArgs
      pid       <- ZIO.attempt(ProcessHandle.current().pid()).mapError(failure)
      _ <- emit(
        McpMessage.Notification("test/ready", Some(Json.Obj("pid" -> Json.Str(pid.toString))))
      )
      _ <- arguments.headOption match
        case Some("echo")      => echo
        case Some("malformed") => Console.printLine("not JSON").mapError(failure) *> ZIO.never
        case Some("eof")       => ZIO.unit
        case Some("blocked-write") =>
          ZIO.attemptBlocking(java.lang.System.in.read()).mapError(failure) *>
            emit(McpMessage.Notification("test/write-started")) *> ZIO.never
        case _ => ZIO.fail(AgentError.McpError("Unknown test peer mode"))
    yield ()
