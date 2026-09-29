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
package com.weisgnosis.agents.core.json

import zio.Chunk
import zio.json.ast.Json
import zio.test.*

object JsonBoundarySpec extends ZIOSpecDefault:
  def spec: Spec[TestEnvironment, Any] = suite("JsonBoundarySpec")(
    test("valid primitive and nested payloads retain their JSON values") {
      assertTrue(
        JsonBoundary.decode("42") == Right(Json.Num(42)),
        JsonBoundary.decode("""{"items":[{"name":"one"}]}""") == Right(
          Json.Obj("items" -> Json.Arr(Json.Obj("name" -> Json.Str("one"))))
        )
      )
    },
    test("duplicate keys at the root or inside arrays fail before interpretation") {
      val invalid = Chunk("""{"x":1,"x":2}""", """{"items":[{"x":1,"x":2}]}""")
      assertTrue(invalid.forall(JsonBoundary.decode(_).isLeft))
    },
    test("a complete document rejects non-whitespace after any root value") {
      val invalid = Chunk(
        "{} trailing",
        "{}{}",
        "{}]junk",
        """{"nested":[{}]}false""",
        "truefalse",
        "01",
        "1e2x",
        "null[]",
        "\"text\"false",
        "[]{}"
      )
      assertTrue(invalid.forall(JsonBoundary.decode(_).isLeft))
    },
    test("JSON whitespace escaped strings and exponent numbers remain valid") {
      val valid = Chunk(
        " \r\n{\"nested\":[\"}\\\"[\"]}\t ",
        "\"escaped \\\" quote\"",
        "-1.25e+3",
        " true ",
        " null\n"
      )
      assertTrue(valid.forall(JsonBoundary.decode(_).isRight))
    },
    test("incomplete JSON never becomes an empty successful payload") {
      assertTrue(JsonBoundary.decode("{").isLeft, JsonBoundary.decode("").isLeft)
    },
    test("validates supplied ASTs directly without changing their representation"):
      val valid     = Json.Obj("value" -> Json.Arr(Json.Num(1), Json.Str("text")))
      val duplicate = Json.Obj("value" -> Json.Num(1), "value" -> Json.Num(2))
      assertTrue(
        JsonBoundary.validate(valid) == Right(valid),
        JsonBoundary.validate(duplicate).isLeft,
        JsonBoundary.validate(Json.Arr(duplicate)).isLeft
      )
  )
