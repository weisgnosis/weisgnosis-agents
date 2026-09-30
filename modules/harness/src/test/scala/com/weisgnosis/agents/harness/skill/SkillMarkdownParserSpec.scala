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
package com.weisgnosis.agents.harness.skill

import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.skill.{SkillCatalog, SkillDefinition}
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

object SkillMarkdownParserSpec extends ZIOSpecDefault:
  private val header: String = "name: guide\ndescription: Read carefully"

  private def document(metadata: String, body: String = "# Guide\nKeep this text.\n"): String =
    "---\n" + metadata + "\n---\n" + body

  private def isInvalid(result: Either[AgentError, SkillDefinition]): Boolean =
    result.left.exists:
      case _: AgentError.InvalidConfiguration => true
      case _                                  => false

  def spec: Spec[TestEnvironment, Any] = suite("SkillMarkdownParserSpec")(
    test(
      "inline scalar profiles preserve exact body and produce capability-free catalogue values"
    ) {
      val body = "\r\n# Guide\r\nKeep --- inside the body.\r\n  "
      val cases = Chunk(
        document(header, body) -> SkillDefinition("guide", "Read carefully", body),
        document("description: ' true '\nname: '123'", body) -> SkillDefinition(
          "123",
          " true ",
          body
        ),
        document("name: 'null'\ndescription: \"A --- B\"", body) -> SkillDefinition(
          "null",
          "A --- B",
          body
        ),
        ("--- \t\r\nname: guide\r\ndescription: >-\r\n  Read --- carefully\r\n  and explain.\r\n---\t\r\n" + body) ->
          SkillDefinition("guide", "Read --- carefully and explain.", body),
        document("name: café\ndescription: |\n  First line\n  Second line", body) ->
          SkillDefinition("café", "First line\nSecond line\n", body),
        document("{name: guide, description: Read carefully}", body) ->
          SkillDefinition("guide", "Read carefully", body)
      )
      ZIO
        .foreach(cases) { case (markdown, expected) =>
          SkillMarkdownParser
            .parse(markdown)
            .map(value =>
              assertTrue(
                value == expected,
                value.resources.isEmpty,
                value.activateGroups.isEmpty,
                SkillCatalog.make(Chunk(value)).isRight
              )
            )
        }
        .map(_.foldLeft(assertTrue(true))(_ && _))
    },
    test("malformed metadata fails as typed configuration data without coercion or fallback") {
      val cases = Chunk(
        "",
        "name: guide",
        "description: Guide",
        "name: ''\ndescription: Guide",
        "name: guide\ndescription: ' '",
        "name: guide\ndescription: Guide\nname: other",
        "name: 123\ndescription: Guide",
        "name: guide\ndescription: 1.5",
        "name: true\ndescription: Guide",
        "name: guide\ndescription: null",
        "name: ~\ndescription: Guide",
        "name: guide\ndescription:",
        "name: guide\ndescription: [nested]",
        "name: guide\ndescription: {nested: text}",
        "name: &id guide\ndescription: *id",
        "name: !!str guide\ndescription: Guide",
        "[guide, description]",
        "name: guide\ndescription: [",
        "%YAML 1.2\nname: guide\ndescription: Guide",
        "name: guide\ndescription: Guide\n...\nname: other"
      )
      ZIO
        .foreach(cases)(metadata =>
          SkillMarkdownParser
            .parse(document(metadata))
            .either
            .map(result => assertTrue(isInvalid(result)))
        )
        .map(_.foldLeft(assertTrue(true))(_ && _))
    },
    test("UTF-16 limits and exact delimiter lines reject boundaries without trimming body") {
      val valid  = document(header, "\nText 😀\n")
      val limits = SkillMarkdownLimits(valid.length, header.length + 1)
      val invalidDocuments = Chunk(
        "",
        "No frontmatter",
        "\uFEFF" + valid,
        " " + valid,
        valid.replaceFirst("---", "---suffix"),
        "---\n" + header + "\n---suffix\nBody",
        "---\n" + header + "\n ---\nBody",
        "---\n" + header,
        document(header, ""),
        document(header, " \r\n\t")
      )
      val invalidLimits = Chunk(
        SkillMarkdownLimits(0, 10),
        SkillMarkdownLimits(10, 0),
        SkillMarkdownLimits(-1, 10),
        SkillMarkdownLimits(10, -1),
        limits.copy(maxMetadataDepth = 0),
        limits.copy(maxMetadataDepth = -1),
        limits.copy(maxDocumentCharacters = valid.length - 1),
        limits.copy(maxHeaderCharacters = header.length)
      )
      for
        accepted  <- SkillMarkdownParser.parse(valid, limits)
        documents <- ZIO.foreach(invalidDocuments)(value => SkillMarkdownParser.parse(value).either)
        bounded <- ZIO.foreach(invalidLimits)(value =>
          SkillMarkdownParser.parse(valid, value).either
        )
      yield assertTrue(
        accepted.content == "\nText 😀\n",
        documents.forall(isInvalid),
        bounded.forall(isInvalid)
      )
    },
    test("ordered nested metadata preserves JSON values without installing capabilities") {
      val extra = """version: 1.5
                    |enabled: TRUE
                    |missing: null
                    |empty:
                    |quoted: 'true'
                    |date: 2026-10-01
                    |summary: >-
                    |  Read these
                    |  notes.
                    |metadata:
                    |  tags: [web, '123', false, null, -2, 1e2]
                    |  "1": {emptyObject: {}, emptyArray: []}
                    |resources: {script: inert}
                    |activateGroups: [admin]
                    |allowed-tools: [write]
                    |""".stripMargin
      val expected = Json.Obj(
        "version" -> Json.Num(BigDecimal("1.5")),
        "enabled" -> Json.Bool(true),
        "missing" -> Json.Null,
        "empty"   -> Json.Null,
        "quoted"  -> Json.Str("true"),
        "date"    -> Json.Str("2026-10-01"),
        "summary" -> Json.Str("Read these notes."),
        "metadata" -> Json.Obj(
          "tags" -> Json.Arr(
            Json.Str("web"),
            Json.Str("123"),
            Json.Bool(false),
            Json.Null,
            Json.Num(-2),
            Json.Num(BigDecimal("1e2"))
          ),
          "1" -> Json.Obj("emptyObject" -> Json.Obj(), "emptyArray" -> Json.Arr())
        ),
        "resources"      -> Json.Obj("script" -> Json.Str("inert")),
        "activateGroups" -> Json.Arr(Json.Str("admin")),
        "allowed-tools"  -> Json.Arr(Json.Str("write"))
      )
      for
        parsed <- SkillMarkdownParser.parse(document(header + "\n" + extra))
        quoted <- SkillMarkdownParser.parse(document(header + "\nvalue: '${SKILL_METADATA_TEST}'"))
      yield assertTrue(
        parsed.metadata == expected,
        parsed.metadata.fields.map(_._1) == expected.fields.map(_._1),
        parsed.content == "# Guide\nKeep this text.\n",
        parsed.resources.isEmpty,
        parsed.activateGroups.isEmpty,
        parsed.toJson.fromJson[SkillDefinition] == Right(parsed),
        SkillCatalog.make(Chunk(parsed)).isRight,
        quoted.metadata == Json.Obj("value" -> Json.Str("${SKILL_METADATA_TEST}"))
      )
    },
    test("metadata rejects ambiguity and unsupported values and bounds collection descent") {
      val invalidExtras = Chunk(
        "value: 1\nvalue: 2",
        "value: {nested: {key: one, key: two}}",
        "value: [{key: one, key: two}]",
        "1: value",
        "value: {true: value}",
        "value: {[key]: value}",
        "value: !!str text",
        "value: !custom [text]",
        "value: &shared [text]\nother: *shared",
        "value: ${SKILL_METADATA_TEST}",
        "value: .inf",
        "value: -.Inf",
        "value: .nan",
        "value: 0x10",
        "value: 0o10",
        "value: +1",
        "value: 01",
        "value: .5",
        "value: 1.",
        "value: 1e2147483648"
      )
      val bounded = Chunk(
        ("value: scalar", 1, true),
        ("value: {}", 1, true),
        ("value: []", 1, true),
        ("value: {nested: []}", 1, false),
        ("value: [[]]", 1, false),
        ("value: {nested: []}", 2, true),
        ("value: [{nested: []}]", 2, false),
        ("value: [{nested: []}]", 3, true)
      )
      for
        rejected <- ZIO.foreach(invalidExtras)(extra =>
          SkillMarkdownParser
            .parse(document(header + "\n" + extra))
            .either
            .map(result => assertTrue(isInvalid(result)))
        )
        depth <- ZIO.foreach(bounded) { case (extra, maximum, accepted) =>
          SkillMarkdownParser
            .parse(
              document(header + "\n" + extra),
              SkillMarkdownLimits(maxMetadataDepth = maximum)
            )
            .either
            .map(result =>
              assertTrue(result.isRight == accepted, result.isRight || isInvalid(result))
            )
        }
      yield (rejected ++ depth).foldLeft(assertTrue(true))(_ && _)
    }
  )
