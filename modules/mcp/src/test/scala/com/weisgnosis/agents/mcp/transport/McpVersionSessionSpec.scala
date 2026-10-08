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
import com.weisgnosis.agents.mcp.protocol.*
import java.nio.charset.StandardCharsets
import zio.*
import zio.json.ast.Json
import zio.stream.ZStream
import zio.test.*

object McpVersionSessionSpec extends ZIOSpecDefault:
  private val id: McpRequestId            = McpRequestId.Number(BigInt(1))
  private val identity: McpImplementation = McpImplementation("negotiation-test", "1")

  private def result(version: McpProtocolVersion): Json.Obj = Json.Obj(
    "protocolVersion" -> Json.Str(version.wire),
    "serverInfo"      -> Json.Obj("name" -> Json.Str("peer"), "version" -> Json.Str("1")),
    "capabilities"    -> Json.Obj("tools" -> Json.Obj())
  )

  private def pending(preferred: McpProtocolVersion): ZIO[Scope, AgentError, McpVersionSession] =
    for
      supported <- ZIO.fromEither(McpSupportedVersions.make(Set.from(McpProtocolVersion.values)))
      session   <- McpVersionSession.make(preferred, Some(supported))
      _         <- session.acquire
      request   <- ZIO.fromEither(McpInitialization.request(id, identity, preferred))
      _         <- session.prepare(McpPacket.Single(request))
    yield session

  def spec: Spec[TestEnvironment, Any] = suite("McpVersionSessionSpec")(
    test(
      "a complete initialization switches decoding before the next frame in the same input chunk"
    ) {
      ZIO.scoped:
        ZIO
          .foreach(
            Chunk(
              (McpProtocolVersion.V20250618, McpProtocolVersion.V20250326),
              (McpProtocolVersion.V20250326, McpProtocolVersion.V20250618)
            )
          ): (preferred, selected) =>
            ZIO
              .foreach(Chunk("stdio", "sse")): framing =>
                for
                  session <- pending(preferred)
                  success <- ZIO
                    .fromEither(McpMessage.encode(McpMessage.Success(id, result(selected))))
                  batch = "[{\"jsonrpc\":\"2.0\",\"id\":70,\"method\":\"ping\"}]"
                  wire =
                    if framing == "stdio" then s"$success\n$batch\n"
                    else s"data: $success\n\ndata: $batch\n\n"
                  bytes = Chunk.fromArray(wire.getBytes(StandardCharsets.UTF_8))
                  observed <- (if framing == "stdio" then
                                 ZStream
                                   .fromChunk(bytes)
                                   .via(McpStdioFraming.decodeSession(session, 4096))
                                   .runCollect
                               else
                                 ZStream
                                   .fromChunk(bytes)
                                   .via(McpHttpFraming.sseSession(session, 8192L, 4096))
                                   .runCollect
                                   .map(_.flatMap(_.packet))
                  ).exit
                  current <- session.current
                yield assertTrue(
                  current == selected,
                  if selected == McpProtocolVersion.V20250326 then
                    observed.exists(
                      _ == Chunk(
                        McpPacket.Single(McpMessage.Success(id, result(selected))),
                        McpPacket.Batch(
                          Chunk(
                            McpBatchMember.Message(
                              McpMessage.Request(McpRequestId.Number(70), "ping")
                            )
                          )
                        )
                      )
                    )
                  else observed.isFailure
                )
              .map(_.foldLeft(assertTrue(true))(_ && _))
          .map(_.foldLeft(assertTrue(true))(_ && _))
    },
    test("only a fully validated correlated singleton success may select a revision") {
      ZIO.scoped:
        ZIO
          .foreach(Chunk("unsupported", "malformed", "wrong-id", "failure", "batched")): scenario =>
            for
              session <- pending(McpProtocolVersion.V20250326)
              full = result(McpProtocolVersion.V20250618)
              malformed = Json.Obj(
                full.fields.filterNot(_._1 == "capabilities") :+ ("capabilities" -> Json.Null)
              )
              unsupported = Json.Obj(
                full.fields.filterNot(_._1 == "protocolVersion") :+ ("protocolVersion" -> Json
                  .Str("unknown"))
              )
              message = scenario match
                case "unsupported" => McpMessage.Success(id, unsupported)
                case "malformed"   => McpMessage.Success(id, malformed)
                case "wrong-id"    => McpMessage.Success(McpRequestId.Number(BigInt(2)), full)
                case "failure" => McpMessage.Failure(Some(id), McpRpcError(-32602, "unsupported"))
                case _         => McpMessage.Success(id, full)
              encoded <- ZIO.fromEither(McpMessage.encode(message))
              wire = if scenario == "batched" then s"[$encoded]" else encoded
              first     <- session.decode(wire).exit
              unchanged <- session.current
              valid     <- ZIO.fromEither(McpMessage.encode(McpMessage.Success(id, full)))
              late      <- session.decode(valid).exit
              current   <- session.current
            yield assertTrue(
              unchanged == McpProtocolVersion.V20250326,
              first.isSuccess == Set("wrong-id", "failure").contains(scenario),
              late.isSuccess == (scenario == "wrong-id"),
              current == (if scenario == "wrong-id" then McpProtocolVersion.V20250618
                          else McpProtocolVersion.V20250326)
            )
          .map(_.foldLeft(assertTrue(true))(_ && _))
    },
    test("one scoped lease owns negotiation and close freezes the selected revision") {
      ZIO.scoped:
        for
          supported <- ZIO.fromEither(
            McpSupportedVersions.make(
              Set(McpProtocolVersion.V20250326, McpProtocolVersion.V20250618)
            )
          )
          session <- McpVersionSession.make(McpProtocolVersion.V20250618, Some(supported))
          request <- ZIO.fromEither(McpInitialization.request(id, identity))
          unowned <- session.prepare(McpPacket.Single(request)).exit
          owner   <- ZIO.acquireRelease(Scope.make)(_.close(Exit.unit))
          lease   <- owner.extend(session.acquire)
          second  <- session.acquire.exit
          _       <- session.prepare(McpPacket.Single(request))
          success <- ZIO.fromEither(
            McpMessage.encode(McpMessage.Success(id, result(McpProtocolVersion.V20250326)))
          )
          _          <- session.decode(success)
          _          <- owner.close(Exit.unit)
          _          <- lease.close
          current    <- session.current
          late       <- session.decode(success).exit
          sending    <- session.prepare(McpPacket.Single(McpMessage.Notification("late"))).exit
          reacquired <- session.acquire.exit
        yield assertTrue(
          unowned.isFailure,
          second.isFailure,
          current == McpProtocolVersion.V20250326,
          late.isFailure,
          sending.isFailure,
          reacquired.isFailure
        )
    }
  ) @@ TestAspect.timeout(10.seconds)
