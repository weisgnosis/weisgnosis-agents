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

object McpInitializationSpec extends ZIOSpecDefault:
  private val server: Json.Obj = Json.Obj("name" -> Json.Str("server"), "version" -> Json.Str("1"))

  private def result(
      capabilities: Json = Json.Obj(),
      info: Json = server,
      version: Json = Json.Str("2025-06-18"),
      extra: Chunk[(String, Json)] = Chunk.empty
  ): Json.Obj = Json.Obj(
    Chunk(
      "protocolVersion" -> version,
      "capabilities"    -> capabilities,
      "serverInfo"      -> info
    ) ++ extra
  )

  def spec: Spec[TestEnvironment, Any] = suite("McpInitializationSpec")(
    test(
      "initialize advertises only implemented client capabilities and notification carries no identity"
    ) {
      val request = McpInitialization.request(
        McpRequestId.Text("init"),
        McpImplementation("client", "1", Some("Display"))
      )
      val expected = McpMessage.decode(
        """{"jsonrpc":"2.0","id":"init","method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"client","version":"1","title":"Display"}}}"""
      )
      assertTrue(
        request == expected,
        McpMessage
          .encode(McpInitialization.initialized)
          .toOption
          .flatMap(McpMessage.decode(_).toOption)
          .contains(McpMessage.Notification("notifications/initialized")),
        McpInitialization
          .request(McpRequestId.Number(BigInt(1) << 255), McpImplementation("client", "1"))
          .isLeft,
        McpInitialization
          .request(McpRequestId.Number(0), McpImplementation("client", "1"))
          .toOption
          .flatMap(_.params)
          .flatMap(_.get("clientInfo"))
          .flatMap(_.asObject)
          .exists(_.get("title").isEmpty)
      )
    },
    test(
      "initialization results distinguish absent tools and retain uninterpreted capability extensions"
    ) {
      val minimal   = McpInitialization.readResult(result())
      val flags     = Chunk(None, Some(false), Some(true))
      val extension = Json.Obj("future" -> Json.Arr(Json.Num(1)))
      val checks = flags.map: flag =>
        val tools = Json.Obj(
          Chunk("extension" -> extension) ++ Chunk.fromIterable(
            flag.map(value => "listChanged" -> Json.Bool(value))
          )
        )
        val capabilities = Json.Obj("tools" -> tools, "experimental" -> extension)
        val info         = Json.Obj(server.fields :+ ("title" -> Json.Str("Server display")))
        val decoded = McpInitialization.readResult(
          result(
            capabilities,
            info,
            extra = Chunk(
              "instructions" -> Json.Str("Treat this as remote data"),
              "_meta"        -> extension,
              "futureResult" -> Json.Num(2)
            )
          )
        )
        assertTrue(
          decoded.map(_.serverInfo) == Right(
            McpImplementation("server", "1", Some("Server display"))
          ),
          decoded.map(_.capabilities.tools.map(_.listChanged)) == Right(Some(flag)),
          decoded.map(_.capabilities.tools.map(_.extensions)) == Right(
            Some(Json.Obj("extension" -> extension))
          ),
          decoded.map(_.capabilities.extensions) == Right(Json.Obj("experimental" -> extension)),
          decoded.map(_.instructions) == Right(Some("Treat this as remote data")),
          decoded.map(_.meta) == Right(Some(extension))
        )
      assertTrue(
        minimal.map(_.capabilities.tools) == Right(None),
        minimal.map(_.instructions) == Right(None),
        minimal.map(_.meta) == Right(None),
        McpInitialization
          .readResult(result(Json.Obj("tools" -> Json.Obj())))
          .toOption
          .exists(_.capabilities.tools.nonEmpty)
      ) && checks.reduceOption(_ && _).getOrElse(assertTrue(false))
    },
    test("resource capability flags are independent and malformed known flags fail closed") {
      val checks = for
        subscribe <- Chunk(None, Some(false), Some(true))
        changed   <- Chunk(None, Some(false), Some(true))
      yield
        val flags = Json.Obj(
          Chunk.fromIterable(subscribe.map(flag => "subscribe" -> Json.Bool(flag))) ++
            Chunk.fromIterable(changed.map(flag => "listChanged" -> Json.Bool(flag)))
        )
        McpInitialization
          .readResult(result(Json.Obj("resources" -> flags)))
          .exists(parsed =>
            parsed.capabilities.resources
              .exists(value => value.subscribe == subscribe && value.listChanged == changed)
          )
      val invalid = Chunk(Json.Null, Json.Str("true"), Json.Num(1))
      assertTrue(
        checks.forall(identity),
        McpInitialization.readResult(result()).exists(_.capabilities.resources.isEmpty),
        invalid.forall(flag =>
          McpInitialization
            .readResult(result(Json.Obj("resources" -> Json.Obj("subscribe" -> flag))))
            .isLeft
        ),
        McpInitialization.readResult(result(Json.Obj("resources" -> Json.Null))).isLeft
      )
    },
    test("known fields fail closed for missing null malformed and duplicate representations") {
      val missing = Chunk("protocolVersion", "capabilities", "serverInfo").map: field =>
        Json.Obj(result().fields.filterNot(_._1 == field))
      val malformed = Chunk(
        result(capabilities = Json.Null),
        result(capabilities = Json.Obj("tools" -> Json.Null)),
        result(capabilities = Json.Obj("tools" -> Json.Obj("listChanged" -> Json.Null))),
        result(capabilities = Json.Obj("tools" -> Json.Obj("listChanged" -> Json.Str("true")))),
        result(info = Json.Null),
        result(info = Json.Obj("name" -> Json.Str("server"))),
        result(info = Json.Obj("name" -> Json.Num(1), "version" -> Json.Str("1"))),
        result(info = Json.Obj(server.fields :+ ("title" -> Json.Null))),
        result(extra = Chunk("instructions" -> Json.Null)),
        result(extra = Chunk("_meta" -> Json.Arr())),
        result(extra = Chunk("_meta" -> Json.Null)),
        result(capabilities =
          Json.Obj("experimental" -> Json.Obj("key" -> Json.Num(1), "key" -> Json.Num(2)))
        ),
        result(extra = Chunk("protocolVersion" -> Json.Str("2025-06-18")))
      )
      assertTrue((missing ++ malformed).forall(McpInitialization.readResult(_).isLeft))
    },
    test("only the implemented revision is accepted without silent protocol fallback") {
      val unsupported = Chunk(
        Json.Null,
        Json.Num(2025),
        Json.Str("2024-11-05"),
        Json.Str("2025-03-26"),
        Json.Str("2025-11-25"),
        Json.Str("2026-07-28"),
        Json.Str("2025-06-18 ")
      )
      assertTrue(
        unsupported.forall(version =>
          McpInitialization.readResult(result(version = version)).isLeft
        ),
        McpInitialization.readResult(result()).isRight
      )
    }
  )
