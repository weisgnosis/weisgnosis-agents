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
    ,
    test("renders exact decimal values and ordered nested data without consuming the call stack"):
      val minimum = Json.Num(Long.MinValue)
      val scaled =
        Json.Num(new java.math.BigDecimal(java.math.BigInteger.valueOf(Long.MinValue), 3))
      val decimals = Chunk(
        minimum                      -> "-9223372036854775808",
        Json.Num(Long.MinValue + 1L) -> "-9223372036854775807",
        Json.Num(-2147483649L)       -> "-2147483649",
        Json.Num(Long.MaxValue)      -> "9223372036854775807",
        scaled                       -> "-9223372036854775.808"
      )
      val nested = Json.Obj(
        "quote\"\n" -> Json.Arr(
          minimum,
          scaled,
          Json.Str("escaped\t\\\""),
          Json.Null,
          Json.Bool(true),
          Json.Bool(false)
        ),
        "empty" -> Json.Obj(),
        "array" -> Json.Arr()
      )
      val expected =
        """{"quote\"\n":[-9223372036854775808,-9223372036854775.808,"escaped\t\\\"",null,true,false],"empty":{},"array":[]}"""
      val depth = 20000
      val deep  = (0 until depth).foldLeft[Json](Json.Null)((value, _) => Json.Arr(value))
      assertTrue(
        minimum.value.toString == "-9223372036854775808",
        JsonBoundary.render(minimum) == "-9223372036854775808",
        decimals.forall((value, expected) => JsonBoundary.render(value) == expected),
        JsonBoundary.render(nested) == expected,
        JsonBoundary.decode(expected) == Right(nested),
        JsonBoundary.render(deep) == "[" * depth + "null" + "]" * depth
      )
    ,
    test("canonical representation comparison ignores object order and numeric spelling only"):
      val expected = Json.Obj("value" -> Json.Num(1), "items" -> Json.Arr(Json.Str("one")))
      val reordered =
        Json.Obj("items" -> Json.Arr(Json.Str("one")), "value" -> Json.Num(BigDecimal("1.00")))
      val invalid = Chunk(
        Json.Obj("value" -> Json.Str("1"), "items" -> Json.Arr(Json.Str("one"))),
        Json.Obj("value" -> Json.Num(1)),
        Json.Obj(expected.fields :+ ("extra" -> Json.Null)),
        Json.Obj("value" -> Json.Num(1), "items"   -> Json.Arr(Json.Str("one"), Json.Str("two")))
      )
      assertTrue(
        JsonBoundary.sameRepresentation(reordered, expected),
        invalid.forall(value => !JsonBoundary.sameRepresentation(value, expected)),
        !JsonBoundary.sameRepresentation(
          Json.Arr(Json.Num(1), Json.Num(2)),
          Json.Arr(Json.Num(2), Json.Num(1))
        )
      )
    ,
    test(
      "Unicode validation checks nested values and keys without changing general AST validation"
    ):
      val invalid = "bad " + 0xd800.toChar
      val malformed = Chunk(
        Json.Str(invalid),
        Json.Obj(invalid -> Json.Null),
        Json.Arr(Json.Obj("nested" -> Json.Str(invalid)))
      )
      val valid = Json.Obj("valid \ud83d\ude80" -> Json.Arr(Json.Str("text \ud83d\ude80")))
      assertTrue(
        JsonBoundary.validUnicode(valid),
        malformed.forall(value => !JsonBoundary.validUnicode(value)),
        malformed.forall(JsonBoundary.validate(_).isRight)
      )
  )
