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
package com.weisgnosis.agents.core.tool

import zio.Chunk
import zio.json.*
import zio.json.ast.Json
import zio.test.*

object ToolDefSpec extends ZIOSpecDefault:
  def spec: Spec[TestEnvironment, Any] = suite("ToolDefSpec")(
    test(
      "legacy and null optional output schemas decode absent while descriptive schema ASTs round-trip"
    ) {
      val input = Json.Obj("type" -> Json.Str("object"))
      val legacy = Json.Obj(
        "name"             -> Json.Str("inspect"),
        "description"      -> Json.Str("Inspect"),
        "parametersSchema" -> input
      )
      val definition   = ToolDef("inspect", "Inspect", input)
      val explicitNull = Json.Obj(legacy.fields :+ ("outputSchema" -> Json.Null))
      val outputSchemas: Chunk[Json] = Chunk(
        Json.Obj(
          "type"        -> Json.Str("object"),
          "properties"  -> Json.Obj("answer" -> Json.Obj("$ref" -> Json.Str("#/$defs/Answer"))),
          "$defs"       -> Json.Obj("Answer" -> Json.Obj("type" -> Json.Str("string"))),
          "definitions" -> Json.Obj()
        ),
        Json.Bool(true)
      )
      assertTrue(
        legacy.toJson.fromJson[ToolDef] == Right(definition),
        explicitNull.toJson.fromJson[ToolDef] == Right(definition),
        definition.toJson.fromJson[Json].exists {
          case value: Json.Obj => value.get("outputSchema").isEmpty
          case _               => false
        },
        outputSchemas.forall(schema =>
          val value = definition.copy(outputSchema = Some(schema))
          value.toJson.fromJson[ToolDef] == Right(value)
        )
      )
    }
  )
