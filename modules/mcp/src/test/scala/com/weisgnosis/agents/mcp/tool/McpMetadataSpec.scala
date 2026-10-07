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

import zio.Chunk
import zio.json.ast.Json
import zio.test.*

object McpMetadataSpec extends ZIOSpecDefault:
  def spec: Spec[TestEnvironment, Any] = suite("McpMetadataSpec")(
    test(
      "ordered top-level replacement preserves exact values and validates even overridden sources"
    ):
      val first =
        Json.Obj("key" -> Json.Str("first"), "nested" -> Json.Obj("old" -> Json.Bool(true)))
      val second = Json.Obj("key" -> Json.Null, "nested" -> Json.Obj("new" -> Json.Str("value")))
      val precise = Json.Obj(
        "minimum" -> Json.Num(Long.MinValue),
        "decimal" -> Json.Num(BigDecimal("9007199254740993.000000000000000001")),
        "unicode" -> Json.Str("valid \ud83d\ude80")
      )
      val cases = Chunk(
        Chunk.empty[Json.Obj]                -> Json.Obj(),
        Chunk(Json.Obj(), first, Json.Obj()) -> first,
        Chunk(first, second)                 -> second,
        Chunk(first, second, Json.Obj("key" -> Json.Str("last")), precise) -> Json.Obj(
          Chunk(
            "key"    -> Json.Str("last"),
            "nested" -> Json.Obj("new" -> Json.Str("value"))
          ) ++ precise.fields
        )
      )
      val invalid = Chunk(
        Json.Obj("key" -> Json.Num(1), "key" -> Json.Num(2)),
        Json.Obj(
          "key" -> Json.Arr(Json.Obj("duplicate" -> Json.Num(1), "duplicate" -> Json.Num(2)))
        )
      )
      assertTrue(
        cases.forall((sources, expected) =>
          McpMetadata
            .merge(sources)
            .exists(result =>
              result.fields.size == expected.fields.size && result.fields.toMap == expected.fields.toMap
            )
        ),
        invalid.forall(source =>
          Chunk(
            Chunk(source, Json.Obj("key" -> Json.Str("replacement"))),
            Chunk(
              Json.Obj("before" -> Json.Bool(true)),
              source,
              Json.Obj("key" -> Json.Str("replacement"))
            ),
            Chunk(Json.Obj("before" -> Json.Bool(true)), source)
          ).forall(sources => McpMetadata.merge(sources).isLeft)
        ),
        McpMetadata.merge(Chunk(precise)).contains(precise)
      )
  )
