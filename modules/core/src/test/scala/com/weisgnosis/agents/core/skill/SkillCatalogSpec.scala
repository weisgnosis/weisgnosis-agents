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
package com.weisgnosis.agents.core.skill

import com.weisgnosis.agents.core.tool.{ToolGroup, ToolGroups, ToolGroupScope}
import zio.{Chunk, ZIO}
import zio.json.*
import zio.test.*

object SkillCatalogSpec extends ZIOSpecDefault:
  private val definition: SkillDefinition = SkillDefinition(
    "guide",
    "A preloaded guide",
    "# Guide\nKeep this text unchanged.\n",
    Map(
      "references/a..b file.md" -> "Nested resource",
      "empty.txt"               -> "",
      "skill.md"                -> "Case-sensitive resource"
    ),
    Set("research")
  )

  private val invalidPaths: Chunk[String] = Chunk(
    "",
    " ",
    ".",
    "..",
    "/root",
    "nested/",
    "nested//file",
    "nested/./file",
    "nested/../file",
    "a\\b",
    "C:/file",
    "a:b",
    "line\nfile",
    "tab\tfile",
    "nul\u0000file",
    "del\u007ffile",
    "next\u0085file"
  )

  def spec: Spec[TestEnvironment, Any] = suite("SkillCatalogSpec")(
    test("checked catalogues reject malformed declarations and duplicates without altering text") {
      val invalid = Chunk(
        definition.copy(id = " "),
        definition.copy(description = "\t"),
        definition.copy(content = "\n"),
        definition.copy(activateGroups = Set("research", " ")),
        definition.copy(resources = Map("SKILL.md" -> "replacement"))
      ) ++ invalidPaths.map(path => definition.copy(resources = Map(path -> "data")))
      val first  = definition.copy(id = "a")
      val second = definition.copy(id = "z")
      assertTrue(
        invalid.forall(value => SkillCatalog.make(Chunk(value)).isLeft),
        SkillCatalog.make(Chunk(definition.copy(resources = Map(" " -> "text")))).isLeft,
        SkillCatalog.make(Chunk(definition, definition.copy(description = "Other"))).isLeft,
        SkillCatalog.make(Chunk(second, first)).exists(_.definitions == Chunk(first, second)),
        SkillCatalog.make(Chunk.empty).exists(_.isEmpty),
        SkillCatalog.empty.load("missing", "SKILL.md").isLeft,
        SkillCatalog
          .make(
            Chunk(
              definition.copy(resources =
                Map("file with spaces" -> "space key", "nested/SKILL.md" -> "nested")
              )
            )
          )
          .isRight
      )
    },
    test(
      "exact logical resource lookup preserves content and proposed groups without path normalization"
    ) {
      val results =
        for catalog <- SkillCatalog.make(Chunk(definition))
        yield
          val invalid = invalidPaths ++ Chunk("missing.md", "references/A..b file.md", "SKILL.MD")
          assertTrue(
            catalog.load("guide", "SKILL.md") == Right(
              SkillCatalog.Resource(definition.content, Set("research"))
            ),
            catalog.load("guide", "references/a..b file.md") == Right(
              SkillCatalog.Resource("Nested resource", Set("research"))
            ),
            catalog.load("guide", "empty.txt") == Right(SkillCatalog.Resource("", Set("research"))),
            catalog.load("guide", "skill.md") == Right(
              SkillCatalog.Resource("Case-sensitive resource", Set("research"))
            ),
            invalid.forall(path =>
              catalog.load("guide", path).left.exists(_.toolName == SkillCatalog.LoadToolName)
            ),
            catalog.load("other", "SKILL.md").left.exists(_.toolName == SkillCatalog.LoadToolName),
            catalog.load(" guide", "SKILL.md").isLeft,
            catalog.definitions == Chunk(definition)
          )
      ZIO.fromEither(results)
    },
    test(
      "Meta targets bind against current groups and decoded declarations require checked reentry"
    ) {
      val result = for
        catalog <- SkillCatalog.make(Chunk(definition))
        meta    <- ToolGroups.empty.register(ToolGroup("research", "Research"))
        external <- ToolGroups.empty.register(
          ToolGroup("research", "Host only", scope = ToolGroupScope.External)
        )
        noTargets <- SkillCatalog.make(Chunk(definition.copy(activateGroups = Set.empty)))
      yield assertTrue(
        catalog.validateGroups(meta) == Right(()),
        catalog.validateGroups(ToolGroups.empty).isLeft,
        catalog.validateGroups(external).isLeft,
        noTargets.validateGroups(ToolGroups.empty) == Right(()),
        definition.toJson.fromJson[SkillDefinition] == Right(definition),
        definition
          .copy(content = " ")
          .toJson
          .fromJson[SkillDefinition]
          .exists(decoded => SkillCatalog.make(Chunk(decoded)).isLeft),
        "{\"id\":\"plain\",\"description\":\"Plain\",\"content\":\"Text\"}"
          .fromJson[SkillDefinition]
          .exists(decoded =>
            decoded.resources.isEmpty && decoded.activateGroups.isEmpty && SkillCatalog
              .make(Chunk(decoded))
              .isRight
          )
      )
      ZIO.fromEither(result)
    }
  )
