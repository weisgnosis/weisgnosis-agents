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

object McpProtocolSpec extends ZIOSpecDefault:
  private val large: BigInt = (BigInt(1) << 100) + 7

  private def roundTrip(message: McpMessage, wire: String): TestResult =
    assertTrue(
      McpMessage.decode(wire) == Right(message),
      McpMessage.encode(message).toOption.flatMap(McpMessage.decode(_).toOption).contains(message)
    )

  def spec: Spec[TestEnvironment, Any] = suite("McpProtocolSpec")(
    test("integer conversion bounds expanded digits before allocating correlation values") {
      val overLimit = BigInt(10).pow(100)
      val boundary  = (BigInt(1) << 255) - 1
      val outside   = Chunk(boundary + 1, -boundary - 2)
      val inside    = Chunk(boundary, -boundary - 1)
      val tooLarge  = Json.Num(BigDecimal("1e100"))
      val request =
        Json.Obj("jsonrpc" -> Json.Str("2.0"), "id" -> tooLarge, "method" -> Json.Str("custom"))
      val rejected = outside.map: value =>
        assertTrue(
          McpMessage
            .fromJson(
              Json.Obj(
                "jsonrpc" -> Json.Str("2.0"),
                "id"      -> Json.Num(BigDecimal(value)),
                "method"  -> Json.Str("custom")
              )
            )
            .isLeft,
          McpMessage
            .fromJson(
              Json.Obj(
                "jsonrpc" -> Json.Str("2.0"),
                "error" -> Json
                  .Obj("code" -> Json.Num(BigDecimal(value)), "message" -> Json.Str("bad"))
              )
            )
            .isLeft,
          McpMessage.encode(McpMessage.Request(McpRequestId.Number(value), "custom")).isLeft,
          McpMessage.encode(McpMessage.Failure(None, McpRpcError(value, "bad"))).isLeft
        )
      val accepted = inside.map: value =>
        roundTrip(
          McpMessage.Request(McpRequestId.Number(value), "custom"),
          s"""{"jsonrpc":"2.0","id":$value,"method":"custom"}"""
        ) &&
          roundTrip(
            McpMessage.Failure(None, McpRpcError(value, "bad")),
            s"""{"jsonrpc":"2.0","error":{"code":$value,"message":"bad"}}"""
          )
      assertTrue(
        McpMessage.decode("""{"jsonrpc":"2.0","id":1e100,"method":"custom"}""").isLeft,
        McpMessage.fromJson(request).isLeft,
        McpMessage.decode("""{"jsonrpc":"2.0","error":{"code":1e100,"message":"bad"}}""").isLeft,
        McpMessage.encode(McpMessage.Request(McpRequestId.Number(overLimit), "custom")).isLeft,
        McpMessage.toJson(McpMessage.Failure(None, McpRpcError(-overLimit, "bad"))).isLeft,
        McpMessage.decode("""{"jsonrpc":"2.0","id":0e2147483647,"method":"custom"}""") ==
          Right(McpMessage.Request(McpRequestId.Number(0), "custom"))
      ) && (rejected ++ accepted).reduceOption(_ && _).getOrElse(assertTrue(false))
    },
    test("request identifiers preserve text and supported integers without numeric coercion") {
      val cases = Chunk(
        McpRequestId.Text("")      -> Json.Str(""),
        McpRequestId.Text("7")     -> Json.Str("7"),
        McpRequestId.Number(-7)    -> Json.Num(-7),
        McpRequestId.Number(large) -> Json.Num(BigDecimal(large))
      )
      val params = Json.Obj("extension" -> Json.Obj("value" -> Json.Str("kept")))
      val checks = cases.map: (id, wireId) =>
        val message = McpMessage.Request(id, "tools/list", Some(params))
        val wire = Json.Obj(
          "jsonrpc" -> Json.Str("2.0"),
          "id"      -> wireId,
          "method"  -> Json.Str("tools/list"),
          "params"  -> params
        )
        val encoded = McpMessage.toJson(message).toOption
        roundTrip(message, wire.toString) && assertTrue(
          encoded.flatMap(_.get("jsonrpc")).contains(Json.Str("2.0")),
          encoded.flatMap(_.get("id")).contains(wireId),
          encoded.flatMap(_.get("params")).contains(params)
        )
      checks.reduceOption(_ && _).getOrElse(assertTrue(false)) && assertTrue(
        McpMessage.decode("""{"jsonrpc":"2.0","id":7e2,"method":"custom"}""") ==
          Right(McpMessage.Request(McpRequestId.Number(700), "custom"))
      )
    },
    test("notifications carry no identity and preserve absent versus empty object parameters") {
      val absent = McpMessage.Notification("notifications/message")
      val empty  = McpMessage.Notification("notifications/message", Some(Json.Obj()))
      roundTrip(absent, """{"jsonrpc":"2.0","method":"notifications/message"}""") &&
      roundTrip(empty, """{"jsonrpc":"2.0","method":"notifications/message","params":{}}""") &&
      assertTrue(
        McpMessage
          .toJson(absent)
          .toOption
          .exists(obj => obj.get("id").isEmpty && obj.get("params").isEmpty),
        McpMessage.toJson(empty).toOption.flatMap(_.get("params")).contains(Json.Obj())
      )
    },
    test("result and remote error responses retain correlation and arbitrary error data") {
      val result = McpMessage.Success(
        McpRequestId.Text("task"),
        Json.Obj("resultType" -> Json.Str("complete"))
      )
      val absent = McpMessage.Failure(None, McpRpcError(-32700, "Malformed request"))
      val explicitNull = McpMessage.Failure(
        Some(McpRequestId.Number(1)),
        McpRpcError(large, "Remote failure", Some(Json.Null))
      )
      val structured = McpMessage.Failure(
        Some(McpRequestId.Text("1")),
        McpRpcError(-32602, "Invalid arguments", Some(Json.Arr(Json.Str("detail"), Json.Num(3))))
      )
      roundTrip(result, """{"jsonrpc":"2.0","id":"task","result":{"resultType":"complete"}}""") &&
      roundTrip(
        absent,
        """{"jsonrpc":"2.0","error":{"code":-32700,"message":"Malformed request"}}"""
      ) &&
      roundTrip(
        explicitNull,
        s"""{"jsonrpc":"2.0","id":1,"error":{"code":$large,"message":"Remote failure","data":null}}"""
      ) &&
      roundTrip(
        structured,
        """{"jsonrpc":"2.0","id":"1","error":{"code":-32602,"message":"Invalid arguments","data":["detail",3]}}"""
      ) &&
      assertTrue(
        McpMessage.toJson(absent).toOption.exists(obj => obj.get("id").isEmpty),
        McpMessage
          .toJson(explicitNull)
          .toOption
          .flatMap(_.get("error"))
          .flatMap(_.asObject)
          .flatMap(_.get("data"))
          .contains(Json.Null),
        McpMessage.decode("""{"jsonrpc":"2.0","id":0,"result":{}}""") ==
          Right(McpMessage.Success(McpRequestId.Number(0), Json.Obj()))
      )
    },
    test(
      "checked boundaries reject ambiguous envelopes and malformed optional fields before interpretation"
    ) {
      val invalid = Chunk(
        "[]",
        """[{"jsonrpc":"2.0","id":1,"method":"tools/list"}]""",
        """{"jsonrpc":"1.0","id":1,"method":"tools/list"}""",
        """{"id":1,"method":"tools/list"}""",
        """{"jsonrpc":"2.0","id":null,"method":"tools/list"}""",
        """{"jsonrpc":"2.0","id":true,"method":"tools/list"}""",
        """{"jsonrpc":"2.0","id":1.5,"method":"tools/list"}""",
        """{"jsonrpc":"2.0","id":1,"method":7}""",
        """{"jsonrpc":"2.0","method":"note","params":null}""",
        """{"jsonrpc":"2.0","id":1,"method":"tools/list","params":[]}""",
        """{"jsonrpc":"2.0","id":1,"result":null}""",
        """{"jsonrpc":"2.0","result":{}}""",
        """{"jsonrpc":"2.0","id":1,"result":{},"error":{"code":1,"message":"bad"}}""",
        """{"jsonrpc":"2.0","id":1,"method":"tools/list","result":{}}""",
        """{"jsonrpc":"2.0","id":null,"error":{"code":1,"message":"bad"}}""",
        """{"jsonrpc":"2.0","error":{"code":1.5,"message":"bad"}}""",
        """{"jsonrpc":"2.0","error":{"code":1,"message":null}}""",
        """{"jsonrpc":"2.0","error":{"message":"bad"}}""",
        """{"jsonrpc":"2.0","method":"note","unknown":1}""",
        """{"jsonrpc":"2.0","error":{"code":1,"message":"bad","unknown":1}}""",
        """{"jsonrpc":"2.0","id":1,"id":2,"method":"tools/list"}""",
        """{"jsonrpc":"2.0","id":1,"result":{"nested":{"key":1,"key":2}}}""",
        """{"jsonrpc":"2.0","method":"note"} {}"""
      )
      val duplicate = Json.Obj("key" -> Json.Num(1), "key" -> Json.Num(2))
      val ambiguous = McpMessage.Success(McpRequestId.Number(1), duplicate)
      assertTrue(
        invalid.forall(McpMessage.decode(_).isLeft),
        McpMessage
          .fromJson(
            Json.Obj("jsonrpc" -> Json.Str("2.0"), "id" -> Json.Num(1), "result" -> duplicate)
          )
          .isLeft,
        McpMessage.toJson(ambiguous).isLeft,
        McpMessage.encode(ambiguous).isLeft,
        McpMessage.decode(" \r\n{\"jsonrpc\":\"2.0\",\"method\":\"custom\"}\t ") ==
          Right(McpMessage.Notification("custom"))
      )
    }
  )
