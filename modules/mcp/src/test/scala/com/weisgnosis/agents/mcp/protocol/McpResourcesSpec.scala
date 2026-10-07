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

object McpResourcesSpec extends ZIOSpecDefault:
  private val resource: Json.Obj = Json.Obj(
    "uri"         -> Json.Str("memory://project"),
    "name"        -> Json.Str("Project"),
    "size"        -> Json.Num(42),
    "annotations" -> Json.Obj("priority" -> Json.Num(1)),
    "_meta"       -> Json.Obj("origin" -> Json.Str("remote")),
    "future"      -> Json.Bool(true)
  )

  def spec: Spec[TestEnvironment, Any] = suite("McpResourcesSpec")(
    test("resource requests preserve opaque selectors, cursors and explicit metadata") {
      val id   = McpRequestId.Number(7)
      val meta = Json.Obj("trace" -> Json.Str("host"))
      val requests = Chunk(
        McpResources.listRequest(id, Some(""), Some(meta)) -> "resources/list",
        McpResources
          .templatesRequest(id, Some("opaque+/%="), Some(meta))      -> "resources/templates/list",
        McpResources.readRequest(id, "memory://project", Some(meta)) -> "resources/read",
        McpResources.subscribeRequest(id, "memory://project", Some(meta)) -> "resources/subscribe",
        McpResources.unsubscribeRequest(
          id,
          "memory://project",
          Some(meta)
        ) -> "resources/unsubscribe"
      )
      assertTrue(
        requests.forall((request, method) =>
          request.exists(value =>
            value.id == id && value.method == method && value.params
              .flatMap(_.get("_meta"))
              .contains(meta)
          )
        ),
        McpResources.listRequest(id).exists(_.params.isEmpty),
        McpResources.readRequest(McpRequestId.Number(BigInt(1) << 256), "x").isLeft
      )
    },
    test(
      "resource pages, templates and reads preserve ordered payloads, annotations and extensions"
    ) {
      val listed = McpResources.readListResult(
        Json.Obj(
          "resources"  -> Json.Arr(resource, resource),
          "nextCursor" -> Json.Str(""),
          "_meta"      -> Json.Obj(),
          "futurePage" -> Json.Num(1)
        )
      )
      val template = McpResources.readTemplatesResult(
        Json.Obj(
          "resourceTemplates" -> Json.Arr(
            Json.Obj("uriTemplate" -> Json.Str("memory://{name}"), "name" -> Json.Str("Memory"))
          )
        )
      )
      val read = McpResources.readResult(
        Json.Obj(
          "contents" -> Json.Arr(
            Json.Obj(
              "uri"    -> Json.Str("memory://project"),
              "text"   -> Json.Str(""),
              "future" -> Json.Bool(true)
            ),
            Json.Obj("uri" -> Json.Str("memory://binary"), "blob" -> Json.Str("AQ=="))
          )
        )
      )
      assertTrue(
        listed.exists(page =>
          page.items.map(_.uri) == Chunk("memory://project", "memory://project") &&
            page.nextCursor
              .contains("") && page.extensions.get("futurePage").contains(Json.Num(1)) &&
            page.items.forall(item =>
              item.size.contains(BigDecimal(42)) && item.annotations
                .flatMap(_.priority)
                .contains(BigDecimal(1)) &&
                item.extensions.get("future").contains(Json.Bool(true))
            )
        ),
        template.exists(_.items.map(_.uriTemplate) == Chunk("memory://{name}")),
        read.exists(
          _.contents.map(_.body) == Chunk(McpResourceBody.Text(""), McpResourceBody.Blob("AQ=="))
        )
      )
    },
    test(
      "known resource fields reject malformed values and recursive duplicates without partial results"
    ) {
      val malformed = Chunk(
        Json.Obj(),
        Json.Obj("resources" -> Json.Null),
        Json.Obj("resources" -> Json.Arr(Json.Obj("uri" -> Json.Str("r")))),
        Json.Obj(
          "resources" -> Json.Arr(Json.Obj(resource.fields :+ ("name" -> Json.Str("duplicate"))))
        ),
        Json.Obj("resources" -> Json.Arr(Json.Obj(resource.fields.map:
          case ("size", _) => "size" -> Json.Num(-1)
          case field       => field))),
        Json.Obj("resources" -> Json.Arr(resource), "nextCursor" -> Json.Null),
        Json.Obj(
          "resources" -> Json.Arr(resource),
          "_meta"     -> Json.Obj("x" -> Json.Num(1), "x" -> Json.Num(2))
        )
      )
      assertTrue(
        malformed.forall(McpResources.readListResult(_).isLeft),
        McpResources
          .readTemplatesResult(Json.Obj("resourceTemplates" -> Json.Arr(resource)))
          .isLeft,
        McpResources
          .readResult(Json.Obj("contents" -> Json.Arr(Json.Obj("uri" -> Json.Str("r")))))
          .isLeft,
        McpResources.readChange(McpMessage.Notification("notifications/resources/updated")).isLeft,
        McpResources
          .readChange(McpMessage.Notification("notifications/resources/list_changed"))
          .contains(Some(McpResourceChange.ListChanged(None))),
        McpResources.readChange(McpMessage.Notification("unrelated")).contains(None)
      )
    }
  )
