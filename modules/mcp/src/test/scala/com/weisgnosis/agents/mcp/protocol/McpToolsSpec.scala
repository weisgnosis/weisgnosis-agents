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

object McpToolsSpec extends ZIOSpecDefault:
  private val schema: Json.Obj = Json.Obj("type" -> Json.Str("object"))
  private val basic: Json.Obj  = Json.Obj("name" -> Json.Str("lookup"), "inputSchema" -> schema)

  private def page(tools: Chunk[Json]): Json.Obj = Json.Obj("tools" -> Json.Arr(tools))

  private def replacing(field: String, value: Json): Json.Obj =
    Json.Obj(basic.fields.filterNot(_._1 == field) :+ (field -> value))

  def spec: Spec[TestEnvironment, Any] = suite("McpToolsSpec")(
    test("list and call requests preserve cursors metadata and absent versus empty arguments") {
      val id       = McpRequestId.Number(1)
      val metadata = Json.Obj("custom" -> Json.Str("data"))
      assertTrue(
        McpTools.listRequest(id) == McpMessage.decode(
          """{"jsonrpc":"2.0","id":1,"method":"tools/list"}"""
        ),
        McpTools.listRequest(id, Some(""), Some(metadata)) == McpMessage.decode(
          """{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{"cursor":"","_meta":{"custom":"data"}}}"""
        ),
        McpTools.callRequest(id, "lookup") == McpMessage.decode(
          """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"lookup"}}"""
        ),
        McpTools.callRequest(id, "lookup", Some(Json.Obj()), Some(metadata)) == McpMessage.decode(
          """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"lookup","arguments":{},"_meta":{"custom":"data"}}}"""
        ),
        McpTools.listRequest(McpRequestId.Number(BigInt(1) << 255)).isLeft,
        McpTools.callRequest(McpRequestId.Number(BigInt(1) << 255), "lookup").isLeft
      )
    },
    test("discovery retains ordered declarations schemas hints and opaque extensions") {
      val extension = Json.Obj("vendor" -> Json.Str("value"))
      val richSchema = Json.Obj(
        schema.fields ++ Chunk(
          "properties"  -> Json.Obj("value" -> Json.Obj("$ref" -> Json.Str("#/$defs/Value"))),
          "required"    -> Json.Arr(Json.Str("value")),
          "$defs"       -> Json.Obj("Value" -> Json.Obj("type" -> Json.Str("string"))),
          "definitions" -> Json.Obj("Other" -> Json.Obj("type" -> Json.Str("number"))),
          "additionalProperties" -> Json.Bool(false)
        )
      )
      val hints = Json.Obj(
        "title"           -> Json.Str("Hint title"),
        "readOnlyHint"    -> Json.Bool(true),
        "destructiveHint" -> Json.Bool(false),
        "idempotentHint"  -> Json.Bool(true),
        "openWorldHint"   -> Json.Bool(false),
        "futureHint"      -> extension
      )
      val full = Json.Obj(
        Chunk(
          "name"         -> Json.Str("lookup"),
          "inputSchema"  -> richSchema,
          "title"        -> Json.Str("Tool title"),
          "description"  -> Json.Str("Description"),
          "outputSchema" -> richSchema,
          "annotations"  -> hints,
          "_meta"        -> extension,
          "futureTool"   -> extension
        )
      )
      val decoded = McpTools.readListResult(
        Json.Obj(
          page(Chunk(full, basic)).fields ++ Chunk(
            "nextCursor" -> Json.Str("next"),
            "_meta"      -> extension,
            "futurePage" -> extension
          )
        )
      )
      val first       = decoded.toOption.flatMap(_.tools.headOption)
      val annotations = first.flatMap(_.annotations)
      assertTrue(
        decoded.map(_.tools.map(_.name)) == Right(Chunk("lookup", "lookup")),
        decoded.map(_.nextCursor) == Right(Some("next")),
        decoded.map(_.meta) == Right(Some(extension)),
        decoded.map(_.extensions) == Right(Json.Obj("futurePage" -> extension)),
        first.map(_.inputSchema).contains(richSchema),
        first.flatMap(_.outputSchema).contains(richSchema),
        first.flatMap(_.title).contains("Tool title"),
        first.flatMap(_.description).contains("Description"),
        first.flatMap(_.meta).contains(extension),
        first.map(_.extensions).contains(Json.Obj("futureTool" -> extension)),
        annotations.flatMap(_.title).contains("Hint title"),
        annotations.flatMap(_.readOnlyHint).contains(true),
        annotations.flatMap(_.destructiveHint).contains(false),
        annotations.flatMap(_.idempotentHint).contains(true),
        annotations.flatMap(_.openWorldHint).contains(false),
        annotations.map(_.extensions).contains(Json.Obj("futureHint" -> extension)),
        McpTools
          .readListResult(page(Chunk.empty))
          .toOption
          .exists(value => value.tools.isEmpty && value.nextCursor.isEmpty),
        McpTools
          .readListResult(page(Chunk(basic)))
          .toOption
          .flatMap(_.tools.headOption)
          .exists(value =>
            value.annotations.isEmpty && value.title.isEmpty && value.description.isEmpty && value.outputSchema.isEmpty && value.meta.isEmpty
          )
      )
    },
    test("discovery rejects malformed known fields without evaluating JSON Schema") {
      val badTools = Chunk[Json](
        Json.Null,
        Json.Obj(),
        Json.Obj("name"        -> Json.Str("lookup")),
        Json.Obj("inputSchema" -> schema),
        replacing("name", Json.Num(1)),
        replacing("inputSchema", Json.Null),
        replacing("inputSchema", Json.Obj()),
        replacing("inputSchema", Json.Obj("type" -> Json.Str("array"))),
        replacing("inputSchema", Json.Obj(schema.fields :+ ("properties" -> Json.Arr()))),
        replacing("inputSchema", Json.Obj(schema.fields :+ ("required" -> Json.Arr(Json.Num(1))))),
        replacing("outputSchema", Json.Obj("type" -> Json.Str("string"))),
        replacing("title", Json.Null),
        replacing("description", Json.Num(1)),
        replacing("_meta", Json.Null),
        replacing("annotations", Json.Null),
        replacing("annotations", Json.Obj("readOnlyHint" -> Json.Str("true")))
      )
      val badPages = Chunk(
        Json.Obj(),
        Json.Obj("tools" -> Json.Null),
        Json.Obj("tools" -> Json.Obj()),
        Json.Obj(page(Chunk.empty).fields :+ ("nextCursor" -> Json.Num(1))),
        Json.Obj(page(Chunk.empty).fields :+ ("_meta"      -> Json.Null))
      )
      val externalReference =
        Json.Obj(schema.fields :+ ("$ref" -> Json.Str("https://example.invalid/schema")))
      assertTrue(
        badTools.forall(tool => McpTools.readListResult(page(Chunk(tool))).isLeft),
        badPages.forall(McpTools.readListResult(_).isLeft),
        McpTools.readListResult(page(Chunk(replacing("inputSchema", externalReference)))).isRight
      )
    },
    test(
      "duplicate keys are rejected inside discovery schemas and outgoing arguments or metadata"
    ) {
      val duplicate = Json.Obj("key" -> Json.Num(1), "key" -> Json.Num(2))
      val id        = McpRequestId.Text("call")
      assertTrue(
        McpTools
          .readListResult(
            page(Chunk(replacing("inputSchema", Json.Obj(schema.fields :+ ("$defs" -> duplicate)))))
          )
          .isLeft,
        McpTools
          .readListResult(Json.Obj(page(Chunk(basic)).fields :+ ("tools" -> Json.Arr())))
          .isLeft,
        McpTools.callRequest(id, "lookup", Some(duplicate)).isLeft,
        McpTools.callRequest(id, "lookup", meta = Some(duplicate)).isLeft,
        McpTools.listRequest(id, meta = Some(duplicate)).isLeft,
        McpTools
          .callRequest(id, "lookup", Some(Json.Obj("a" -> Json.Num(1), "b" -> Json.Num(2))))
          .isRight
      )
    }
  )
