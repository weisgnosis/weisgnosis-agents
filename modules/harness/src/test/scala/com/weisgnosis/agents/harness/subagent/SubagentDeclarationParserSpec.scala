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
package com.weisgnosis.agents.harness.subagent

import com.weisgnosis.agents.core.error.AgentError
import zio.*
import zio.test.*

object SubagentDeclarationParserSpec extends ZIOSpecDefault:
  private def document(header: String, body: String = "Body\n"): String =
    "---\n" + header + "\n---\n" + body

  def spec: Spec[TestEnvironment & Scope, Any] = suite("SubagentDeclarationParserSpec")(
    test(
      "supported scalar and sequence forms preserve body and distinguish inheritance from empty selection"
    ) {
      val base = SubagentDeclaration("reviewer", "Review", "Body\n")
      val examples = Chunk(
        ("description: Review", base),
        (
          "description: Review\nmodel: null\ntemperature: ~\nsteps:\nmaxIters: NULL\ntools: Null",
          base
        ),
        (
          "description: Review\nmodel: 'null'\ntemperature: '4e-1'\nsteps: '12'\nmaxIters: 3",
          base.copy(model = Some("null"), temperature = Some(0.4), maxIterations = Some(12))
        ),
        ("description: Review\nsteps: null\nmaxIters: 7", base.copy(maxIterations = Some(7))),
        (
          "description: Review\ntools: []",
          base.copy(tools = SubagentToolSelection.Only(Set.empty))
        ),
        (
          "description: Review\ntools: ''",
          base.copy(tools = SubagentToolSelection.Only(Set.empty))
        ),
        (
          "description: Review\ntools: read, search, read",
          base.copy(tools = SubagentToolSelection.Only(Set("read", "search")))
        ),
        (
          "description: Review\ntools: [read, 'null', read]",
          base.copy(tools = SubagentToolSelection.Only(Set("read", "null")))
        ),
        (
          "description: Review\ntools:\n  - read\n  - search",
          base.copy(tools = SubagentToolSelection.Only(Set("read", "search")))
        )
      )
      val body = "\r\n# Instructions\r\nKeep --- inside the body.\r\n  "
      for
        parsed <- ZIO.foreach(examples) { case (header, expected) =>
          SubagentDeclarationParser.parse("reviewer", document(header)).map(_ == expected)
        }
        multiline <- SubagentDeclarationParser.parse(
          "reviewer",
          "--- \t\r\ndescription: >-\r\n  Review --- carefully\r\n  and explain.\r\n---\t\r\n" + body
        )
        quoted <- SubagentDeclarationParser.parse("reviewer", document("description: 'A --- B'"))
      yield assertTrue(
        parsed.forall(identity),
        multiline == base
          .copy(description = "Review --- carefully and explain.", systemPrompt = body),
        quoted.description == "A --- B"
      )
    },
    test(
      "ambiguous structure, authority fields and malformed present overrides fail with bounded typed errors"
    ) {
      val headers = Chunk(
        "description: Review\n'description': Duplicate",
        "description: Review\nmodel: a\nmodel: b",
        "description: &anchor Review",
        "description: *missing",
        "&root {description: Review}",
        "!!map {description: Review}",
        "description: !!str Review",
        "description: Review\ntools: !!seq [read]",
        "description: Review\ntools: [!!str read]",
        "description: Review\ntools: [&name read, *name]",
        "description: Review\ntools: [[read]]",
        "description: Review\ntools: {read: true}",
        "description: {text: Review}",
        "[description, Review]",
        "description: Review\nname: spoofed",
        "description: Review\nworkspace: /host",
        "description: Review\npermissions: allow",
        "description: Review\n...\n--- {description: Another}",
        "description: Review\n...\nunexpected suffix",
        "description: null",
        "description: ''",
        "model: only",
        "description: Review\nmodel: ''",
        "description: Review\ntemperature: nullish",
        "description: Review\ntemperature: 'null'",
        "description: Review\ntemperature: .inf",
        "description: Review\ntemperature: 1e999",
        "description: Review\nsteps: 2.5",
        "description: Review\nsteps: 0",
        "description: Review\nsteps: 2147483648",
        "description: Review\nsteps: 12\nmaxIters: malformed",
        "description: Review\nsteps: nope\nmaxIters: 12",
        "description: Review\ntools: [null]",
        "description: Review\ntools: [read, '']",
        "description: Review\ntools: read,,search",
        "description: [unterminated"
      )
      for outcomes <- ZIO.foreach(headers)(header =>
          SubagentDeclarationParser.parse("reviewer", document(header)).exit
        )
      yield assertTrue(
        outcomes.forall(
          _.causeOption.exists(cause =>
            cause.failureOption.exists {
              case error: AgentError.InvalidConfiguration => error.message.length < 256
              case _                                      => false
            } && cause.defects.isEmpty
          )
        )
      )
    },
    test(
      "exact framing and UTF-16 limits are enforced before YAML decoding without changing valid boundary input"
    ) {
      val header   = "description: Review\n"
      val markdown = "---\n" + header + "---\n" + "\ud83d\ude00\n"
      val exact    = SubagentDeclarationLimits(markdown.length, header.length)
      val invalidLimits = Chunk(
        exact.copy(maxDocumentCharacters = markdown.length - 1),
        exact.copy(maxHeaderCharacters = header.length - 1),
        exact.copy(maxDocumentCharacters = 0),
        exact.copy(maxHeaderCharacters = -1)
      )
      val invalidFrames = Chunk(
        "---description: Review\n---\n",
        "---\ndescription: 'unclosed ---'\n",
        " ---\ndescription: Review\n---\n",
        "\ufeff" + markdown,
        "---\rdescription: Review\r---\r",
        "---\ndescription: Review\r---\nBody",
        "---\ndescription: Review\u0085---\nBody",
        "---\ndescription: Review\u2028---\nBody",
        "---\ndescription: Review\u2029---\nBody"
      )
      for
        accepted <- SubagentDeclarationParser.parse("reviewer", markdown, exact)
        sizes <- ZIO.foreach(invalidLimits)(limits =>
          SubagentDeclarationParser.parse("reviewer", markdown, limits).exit
        )
        framing <- ZIO.foreach(invalidFrames)(value =>
          SubagentDeclarationParser.parse("reviewer", value).exit
        )
        malformedOversized <- SubagentDeclarationParser
          .parse("reviewer", "[", SubagentDeclarationLimits(1, 0))
          .exit
        invalidName <- SubagentDeclarationParser.parse(" ", markdown).exit
      yield assertTrue(
        accepted.systemPrompt == "\ud83d\ude00\n",
        sizes.forall(_.isFailure),
        framing.forall(_.isFailure),
        malformedOversized.causeOption.exists(
          _.failureOption.exists(_.message.contains("limits must be positive"))
        ),
        invalidName.isFailure
      )
    }
  )
