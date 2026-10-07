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

import com.weisgnosis.agents.core.error.AgentError
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

object ToolSchemaValidatorSpec extends ZIOSpecDefault:
  private def json(value: String): IO[AgentError, Json] =
    ZIO.fromEither(value.fromJson[Json].left.map(AgentError.InvalidMessage(_)))

  private def validation(
      schema: String,
      input: String
  ): IO[AgentError, Either[AgentError.InvalidMessage, Unit]] =
    for
      definition <- json(schema)
      arguments  <- json(input)
      result     <- ToolSchemaValidator.validate(definition, arguments).either
    yield result

  def spec: Spec[TestEnvironment & Scope, Any] = suite("ToolSchemaValidatorSpec")(
    test("standard constraints and exact numbers distinguish valid and invalid neighbors") {
      val cases = Chunk(
        ("{}", "{\"name\":\"test\"}", true),
        ("""{"type":"object","properties":{}}""", "{}", true),
        ("""{"type":"object"}""", "null", false),
        (
          """{"type":"object","properties":{"name":{"type":"string"},"age":{"type":"integer"}},"required":["name","age"]}""",
          """{"name":"John","age":30}""",
          true
        ),
        (
          """{"type":"object","properties":{"name":{"type":"string"}},"required":["name"]}""",
          "{}",
          false
        ),
        ("""{"type":"string"}""", "123", false),
        ("""{"type":"integer"}""", "\"123\"", false),
        ("""{"type":"number"}""", "95.5", true),
        ("""{"type":"boolean"}""", "true", true),
        ("""{"type":"boolean"}""", "false", true),
        ("""{"type":"boolean"}""", "\"true\"", false),
        ("""{"type":"boolean"}""", "1", false),
        ("""{"enum":["red","green"]}""", "\"red\"", true),
        ("""{"enum":["red","green"]}""", "\"blue\"", false),
        ("""{"type":"integer","minimum":1,"maximum":3}""", "0", false),
        ("""{"type":"integer","minimum":1,"maximum":3}""", "2", true),
        ("""{"type":"integer","minimum":1,"maximum":3}""", "4", false),
        ("""{"type":"string","minLength":2,"maxLength":3}""", "\"a\"", false),
        ("""{"type":"string","minLength":2,"maxLength":3}""", "\"abcd\"", false),
        ("""{"type":"array","items":{"type":"integer"}}""", "[1,2,\"three\"]", false),
        (
          """{"type":"object","properties":{"address":{"type":"object","properties":{"city":{"type":"string"}},"required":["city"]}}}""",
          """{"address":{"city":"Boston"}}""",
          true
        ),
        (
          """{"type":"object","properties":{"address":{"type":"object","properties":{"city":{"type":"string"}},"required":["city"]}}}""",
          """{"address":{}}""",
          false
        ),
        (
          """{"type":"object","properties":{"known":{"type":"string"}},"additionalProperties":false}""",
          """{"unknown":null}""",
          false
        ),
        (
          """{"type":"object","properties":{"optional":{"type":"string"}}}""",
          "{}",
          true
        ),
        (
          """{"type":"object","$defs":{"Material":{"type":"object","properties":{"key":{"type":"string"}}}},"properties":{"request":{"type":"object","properties":{"materialList":{"type":"array","items":{"$ref":"#/$defs/Material"}}}}}}""",
          """{"request":{"materialList":[{"key":"value"}]}}""",
          true
        ),
        (
          """{"type":"object","properties":{"request":{"$defs":{"Material":{"type":"object"}},"type":"object","properties":{"materialList":{"type":"array","items":{"$ref":"#/$defs/Material"}}}}}}""",
          """{"request":{"materialList":[{"key":"value"}]}}""",
          false
        ),
        ("""{"type":"integer","minimum":9007199254740993}""", "9007199254740993", true),
        ("""{"type":"integer","minimum":9007199254740993}""", "9007199254740992", false),
        ("""{"multipleOf":9007199254740993}""", "18014398509481986", true),
        ("""{"multipleOf":9007199254740993}""", "18014398509481985", false),
        ("""{"multipleOf":1e-400}""", "2e-400", true),
        ("""{"multipleOf":1e-400}""", "2.5e-400", false),
        ("""{"const":{"$ref":"#/%FF"}}""", "{\"$ref\":\"#/%FF\"}", true),
        (
          """{"$defs":{"a":{"type":"integer","minimum":2}},"$dynamicRef":"#%2F$defs%2Fa"}""",
          "2",
          true
        ),
        (
          """{"$defs":{"a":{"type":"integer","minimum":2}},"$dynamicRef":"#%2F$defs%2Fa"}""",
          "1",
          false
        ),
        (
          """{"$defs":{"\ufffd":{"type":"integer"}},"allOf":[{"$dynamicRef":"#/$defs/%FF"}]}""",
          "2",
          false
        ),
        ("""{"$defs":{"a b":{"type":"integer"}},"$ref":"#/$defs/a%20b"}""", "2", true),
        ("""{"$defs":{"a+b":{"type":"integer"}},"$ref":"#/$defs/a+b"}""", "2", true),
        ("""{"$defs":{"a/b":{"type":"integer"}},"$ref":"#/$defs/a~1b"}""", "2", true),
        (
          """{"$defs":{"\u00e9":{"type":"integer"}},"allOf":[{"$ref":"#/$defs/%C3%A9"}]}""",
          "2",
          true
        ),
        (
          """{"$defs":{"\ufffd":{"type":"integer"}},"allOf":[{"$ref":"#/$defs/%FF"}]}""",
          "2",
          false
        ),
        (
          """{"$defs":{"a":{"type":"object","properties":{"optional":{"type":"string"}}}},"$ref":"#%2F$defs%2Fa"}""",
          "{\"optional\":null}",
          true
        ),
        ("""{"type":"string","minLength":2,"maxLength":3,"pattern":"^[a-z]+$"}""", "\"abc\"", true),
        ("""{"type":"string","minLength":2,"maxLength":3,"pattern":"^[a-z]+$"}""", "\"a1\"", false),
        (
          """{"type":"array","items":{"type":"integer"},"uniqueItems":true,"minItems":2}""",
          "[1,2]",
          true
        ),
        (
          """{"type":"array","items":{"type":"integer"},"uniqueItems":true,"minItems":2}""",
          "[1,1]",
          false
        ),
        ("""{"oneOf":[{"type":"string"},{"type":"integer","minimum":2}]}""", "2", true),
        ("""{"oneOf":[{"type":"string"},{"type":"integer","minimum":2}]}""", "1", false)
      )
      ZIO
        .foreach(cases): (schema, input, valid) =>
          validation(schema, input)
            .map(result => assertTrue((schema, input, result.isRight) == (schema, input, valid)))
        .map(results => results.foldLeft(assertTrue(true))(_ && _))
    },
    test(
      "declared standard drafts preserve exact constraints and their own reference and tuple semantics"
    ):
      val drafts = Chunk(
        "http://json-schema.org/draft-04/schema#",
        "http://json-schema.org/draft-06/schema#",
        "http://json-schema.org/draft-07/schema#",
        "https://json-schema.org/draft/2019-09/schema"
      )
      val cases = Chunk(
        ("""{"type":"integer","minimum":9007199254740993}""", "9007199254740993", true),
        ("""{"type":"integer","minimum":9007199254740993}""", "9007199254740992", false),
        ("""{"multipleOf":1e-400}""", "2e-400", true),
        ("""{"multipleOf":1e-400}""", "2.5e-400", false),
        (
          """{"definitions":{"a b":{"type":"integer","minimum":2}},"allOf":[{"$ref":"#/definitions/a%20b"}]}""",
          "2",
          true
        ),
        (
          """{"definitions":{"a b":{"type":"integer","minimum":2}},"allOf":[{"$ref":"#/definitions/a%20b"}]}""",
          "1",
          false
        ),
        (
          """{"definitions":{"\ufffd":{"type":"integer"}},"allOf":[{"$ref":"#/definitions/%FF"}]}""",
          "2",
          false
        ),
        (
          """{"type":"array","items":[{"type":"object","properties":{"optional":{"type":"string"}}}],"additionalItems":false}""",
          "[{\"optional\":null}]",
          true
        ),
        (
          """{"type":"array","items":[{"type":"integer"}],"additionalItems":false}""",
          "[1,2]",
          false
        )
      )
      ZIO
        .foreach(drafts): draft =>
          ZIO
            .foreach(
              cases ++ Chunk(
                (
                  """{"type":"integer"}""",
                  "1.0",
                  draft != "http://json-schema.org/draft-04/schema#"
                ),
                ("""{"type":"integer"}""", "1", true),
                ("""{"type":"string","minLength":2}""", "\"ab\"", true),
                ("""{"type":"string","minLength":2}""", "\"a\"", false)
              )
            ): (body, input, expected) =>
              val schema = s"""{"$$schema":"$draft",""" + body.stripPrefix("{")
              validation(schema, input).map(result =>
                assertTrue((draft, body, input, result.isRight) == (draft, body, input, expected))
              )
            .map(_.foldLeft(assertTrue(true))(_ && _))
        .map(_.foldLeft(assertTrue(true))(_ && _))
    ,
    test("2019 recursive references retain the standard root-only policy"):
      val schema =
        """{"$schema":"https://json-schema.org/draft/2019-09/schema","$recursiveAnchor":true,"type":"object","properties":{"value":{"type":"integer","minimum":2},"next":{"$recursiveRef":"#"}}}"""
      val cases = Chunk(
        (schema, """{"next":{"value":2}}""", true),
        (schema, """{"next":{"value":1}}""", false),
        (
          schema.replace("\"$recursiveRef\":\"#\"", "\"$recursiveRef\":\"#/properties/value\""),
          """{"next":2}""",
          false
        ),
        (
          schema.replace("\"$recursiveRef\":\"#\"", "\"$recursiveRef\":\"#/%FF\""),
          """{"next":2}""",
          false
        )
      )
      ZIO
        .foreach(cases): (definition, input, expected) =>
          validation(definition, input).map(result => assertTrue(result.isRight == expected))
        .map(_.foldLeft(assertTrue(true))(_ && _))
    ,
    test("optional nulls affect only the schema-validation view and local references terminate") {
      val schemaText =
        """{"type":"object","properties":{"optional":{"type":"string"},"child":{"$ref":"#/$defs/child"}},"$defs":{"child":{"type":"object","properties":{"value":{"type":"integer"},"optional":{"type":"string"}},"required":["value"]}}}"""
      val inputText = """{"optional":null,"child":{"value":2,"optional":null}}"""
      val required =
        """{"type":"object","properties":{"value":{"type":"integer"}},"required":["value"]}"""
      for
        schema  <- json(schemaText)
        input   <- json(inputText)
        valid   <- ToolSchemaValidator.validate(schema, input).either
        invalid <- validation(required, """{"value":null}""")
        cyclic  <- validation("""{"$ref":"#"}""", inputText)
        paths <- validation(
          """{"type":"object","properties":{"name":{"type":"string"},"address":{"type":"object","properties":{"city":{"type":"string"}}}}}""",
          """{"name":123,"address":{"city":456}}"""
        )
        calls <- Ref.make(Chunk.empty[Json])
        tool = Tool.make(ToolDef("original", "Preserve input", schema))(value =>
          calls.update(_ :+ value).as("ok")
        )
        result   <- Toolkit.single(tool).executeSafe("original", input)
        received <- calls.get
      yield assertTrue(
        valid.isRight,
        invalid.isLeft,
        cyclic.isLeft,
        paths.left.exists(error =>
          error.message.contains("/name") && error.message.contains("/address/city")
        ),
        result.state == com.weisgnosis.agents.core.model.ToolResultState.Success,
        received == Chunk(input)
      )
    },
    test("invalid schemas and external resolution fail closed") {
      val cases = Chunk(
        """{"type":"made-up"}""",
        """{"minimum":"zero"}""",
        """{"$ref":"https://example.invalid/schema"}""",
        """{"$ref":"file:///etc/passwd"}""",
        """{"$ref":"classpath:/application.conf"}"""
      )
      ZIO
        .foreach(cases)(schema =>
          validation(schema, "2")
            .map(result => assertTrue(result.isLeft))
        )
        .map(results => results.foldLeft(assertTrue(true))(_ && _))
    }
  )
