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
package com.weisgnosis.agents.mcp.protocol

import zio.*
import zio.json.ast.Json
import zio.test.*

object McpPacketSpec extends ZIOSpecDefault:
  private val march: McpProtocolVersion = McpProtocolVersion.V20250326
  private val ping: String              = """{"jsonrpc":"2.0","id":1,"method":"ping"}"""
  private val reply: String             = """{"jsonrpc":"2.0","id":1,"result":{}}"""

  def spec: Spec[TestEnvironment & Scope, Any] = suite("McpPacketSpec")(
    test("March batches retain invalid request positions and reject ambiguous response groups"):
      val packet = McpPacket.decode(s"[$ping,42,{},[]]", march)
      val malformed = Chunk(
        s"[$reply,$ping]",
        """[{"jsonrpc":"2.0","id":1,"error":false}]""",
        """[{"jsonrpc":"2.0","id":1,"method":"initialize"}]""",
        """[{"jsonrpc":"2.0","id":1,"method":"ping","id":2}]"""
      )
      assertTrue(
        packet == Right(
          McpPacket.Batch(
            Chunk(
              McpBatchMember.Message(McpMessage.Request(McpRequestId.Number(1), "ping")),
              McpBatchMember.InvalidRequest,
              McpBatchMember.InvalidRequest,
              McpBatchMember.InvalidRequest
            )
          )
        ),
        McpPacket.decode("[]", march) == Right(McpPacket.InvalidRequest),
        malformed.forall(value => McpPacket.decode(value, march).isLeft)
      )
    ,
    test("selected revision and finite member budget gate every array before admission"):
      val accepted  = McpPacket.decode(s"[$reply]", march)
      val oversized = List.fill(McpPacket.maxMembers + 1)(ping).mkString("[", ",", "]")
      val rejected  = Chunk(McpProtocolVersion.V20241105, McpProtocolVersion.V20250618)
      assertTrue(
        accepted == Right(
          McpPacket.Batch(
            Chunk(McpBatchMember.Message(McpMessage.Success(McpRequestId.Number(1), Json.Obj())))
          )
        ),
        rejected.forall(version => McpPacket.decode(s"[$reply]", version).isLeft),
        rejected.forall(version => McpPacket.decode("[]", version).isLeft),
        rejected.forall(version => McpPacket.decode(reply, version).isRight),
        McpPacket.decode(oversized, march).isLeft,
        McpPacket.toJson(McpPacket.Batch(Chunk.empty), march).isLeft
      )
    ,
    test(
      "invalid request replies retain explicit null identities without changing singular codecs"
    ):
      val expected = Json.Obj(
        "jsonrpc" -> Json.Str("2.0"),
        "id"      -> Json.Null,
        "error"   -> Json.Obj("code" -> Json.Num(-32600), "message" -> Json.Str("Invalid Request"))
      )
      assertTrue(
        McpPacket.toJson(McpPacket.InvalidRequest, march) == Right(expected),
        McpPacket.toJson(McpPacket.Batch(Chunk(McpBatchMember.InvalidRequest)), march) == Right(
          Json.Arr(expected)
        ),
        McpPacket
          .validateResponse(
            McpPacket.Batch(
              Chunk(McpBatchMember.Message(McpMessage.Request(McpRequestId.Number(1), "ping")))
            )
          )
          .isLeft,
        McpPacket.toJson(McpPacket.InvalidRequest, McpProtocolVersion.V20250618).isLeft
      )
  )
