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
package com.weisgnosis.agents.mcp.tool

import com.weisgnosis.agents.core.tool.ToolDef
import zio.*
import zio.json.ast.Json
import zio.test.*

object McpToolPresetsSpec extends ZIOSpecDefault:
  private val duplicate: Json.Obj    = Json.Obj("same" -> Json.Num(1), "same" -> Json.Num(2))
  private val stringSchema: Json.Obj = Json.Obj("type" -> Json.Str("string"))
  private val defaults: Json.Obj = Json.Obj(
    "fixed"    -> Json.Num(1),
    "nested"   -> Json.Obj("left" -> Json.Bool(true), "right" -> Json.Bool(true)),
    "nullable" -> Json.Null
  )

  def spec: Spec[TestEnvironment, Any] = suite("McpToolPresetsSpec")(
    test(
      "checked shallow defaults preserve supplied values and reject malformed original branches"
    ) {
      for
        presets <- ZIO.fromEither(McpToolPresets.make(defaults))
        input = Json.Obj("fixed" -> Json.Null, "nested" -> Json.Obj("left" -> Json.Bool(false)))
        expected = Json.Obj(
          "nullable" -> Json.Null,
          "fixed"    -> Json.Null,
          "nested"   -> Json.Obj("left" -> Json.Bool(false))
        )
        cases = Chunk(
          (Json.Obj(), defaults),
          (input, expected),
          (
            Json.Obj("extra" -> Json.Str("new")),
            Json.Obj(defaults.fields :+ ("extra" -> Json.Str("new")))
          )
        )
        invalidDefaults = McpToolPresets
          .make(Json.Obj("nested" -> duplicate))
          .flatMap(_.merge(Json.Obj("nested" -> Json.Obj())))
      yield assertTrue(
        cases.forall((value, result) => presets.merge(value) == Right(result)),
        McpToolPresets.empty.merge(input) == Right(input),
        McpToolPresets.empty.merge(Json.Obj()) == Right(Json.Obj()),
        presets.merge(duplicate).isLeft,
        presets.merge(Json.Obj("nested" -> duplicate)).isLeft,
        invalidDefaults.isLeft
      )
    },
    test(
      "schema projection removes only top-level preset names after validating the complete original"
    ) {
      val definitions = Json.Obj("fixed" -> stringSchema)
      val original = Json.Obj(
        "type"                 -> Json.Str("object"),
        "properties"           -> Json.Obj("keep" -> stringSchema, "fixed" -> stringSchema),
        "required"             -> Json.Arr(Json.Str("fixed"), Json.Str("keep"), Json.Str("keep")),
        "$defs"                -> definitions,
        "definitions"          -> Json.Obj(),
        "additionalProperties" -> Json.Bool(false),
        "extension"            -> Json.Obj("fixed" -> Json.Str("untouched"))
      )
      val host = ToolDef("local", "host", original, isConcurrencySafe = false, isReadOnly = true)
      val expected = host.copy(parametersSchema =
        Json.Obj(
          "type"                 -> Json.Str("object"),
          "properties"           -> Json.Obj("keep" -> stringSchema),
          "required"             -> Json.Arr(Json.Str("keep"), Json.Str("keep")),
          "$defs"                -> definitions,
          "definitions"          -> Json.Obj(),
          "additionalProperties" -> Json.Bool(false),
          "extension"            -> Json.Obj("fixed" -> Json.Str("untouched"))
        )
      )
      val invalid: Chunk[Json] = Chunk(
        Json.Bool(true),
        Json.Obj(),
        Json.Obj("type" -> Json.Str("array")),
        Json.Obj("type" -> Json.Str("object"), "properties" -> Json.Null),
        Json.Obj("type" -> Json.Str("object"), "required"   -> Json.Null),
        Json.Obj("type" -> Json.Str("object"), "required"   -> Json.Arr(Json.Num(1))),
        Json.Obj("type" -> Json.Str("object"), "properties" -> Json.Obj("fixed" -> duplicate))
      )
      for
        presets <- ZIO.fromEither(
          McpToolPresets.make(Json.Obj("fixed" -> Json.Str("default"), "absent" -> Json.Num(2)))
        )
        binding <- ZIO.fromEither(
          McpToolBinding.withPresets(host, Json.Obj("fixed" -> Json.Str("default")))
        )
        absent = host.copy(parametersSchema = Json.Obj("type" -> Json.Str("object")))
      yield assertTrue(
        presets.project(host) == Right(expected),
        binding.definition == expected,
        binding.presets.merge(Json.Obj()) == Right(Json.Obj("fixed" -> Json.Str("default"))),
        McpToolPresets.empty.project(host) == Right(host),
        presets.project(absent) == Right(absent),
        McpToolBinding(host, presets = presets).definition == host,
        invalid.forall(schema => presets.project(host.copy(parametersSchema = schema)).isLeft)
      )
    }
  )
