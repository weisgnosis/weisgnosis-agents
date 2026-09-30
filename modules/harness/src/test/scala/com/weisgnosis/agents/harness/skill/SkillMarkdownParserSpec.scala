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
        "name: guide\ndescription: Guide\nversion: 1",
        "name: guide\ndescription: Guide\nactivateGroups: [admin]",
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
    }
  )
