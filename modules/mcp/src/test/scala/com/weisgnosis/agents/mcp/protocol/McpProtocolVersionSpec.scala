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

object McpProtocolVersionSpec extends ZIOSpecDefault:
  def spec: Spec[TestEnvironment & Scope, Any] = suite("Explicit MCP lifecycle versions")(
    test("handshake uses the selected revision and rejects a different server selection"):
      val revisions = Chunk(
        McpProtocolVersion.V20241105 -> "2024-11-05",
        McpProtocolVersion.V20250326 -> "2025-03-26",
        McpProtocolVersion.V20250618 -> "2025-06-18"
      )
      ZIO
        .foreach(revisions): (selected, wire) =>
          val result = Json.Obj(
            "protocolVersion" -> Json.Str(wire),
            "serverInfo"      -> Json.Obj("name" -> Json.Str("peer"), "version" -> Json.Str("1")),
            "capabilities"    -> Json.Obj()
          )
          val wrong = Json.Obj(result.fields.map:
            case ("protocolVersion", _) => "protocolVersion" -> Json.Str("2099-01-01")
            case field                  => field)
          ZIO
            .fromEither(
              McpInitialization
                .request(McpRequestId.Text("init"), McpImplementation("host", "1"), selected)
            )
            .map(request =>
              assertTrue(
                request.params.exists(_.fields.contains("protocolVersion" -> Json.Str(wire))),
                McpInitialization.readResult(result, selected).map(_.protocolVersion) == Right(
                  selected
                ),
                McpInitialization.readResult(wrong, selected).isLeft,
                McpInitialization
                  .readResult(result)
                  .isRight == (selected == McpProtocolVersion.V20250618)
              )
            )
        .map(_.foldLeft(assertTrue(true))(_ && _))
  )
