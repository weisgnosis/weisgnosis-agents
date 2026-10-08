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
package com.weisgnosis.agents.core.event.javawire

import com.weisgnosis.agents.core.json.JsonBoundary
import zio.*
import zio.json.*
import zio.test.*

object JavaContentBlockSpec extends ZIOSpecDefault:
  def spec: Spec[TestEnvironment & Scope, Any] = suite("JavaContentBlockSpec")(
    test("all Java content and source variants preserve Jackson inclusion and nested output"):
      JavaEventFixtures.read.map: fixtures =>
        val content = fixtures.filter(_.kind == "content")
        val sources = fixtures.filter(_.kind == "source")
        val contentChecks = content.map(fixture =>
          JavaEventFixtures.same(
            JsonBoundary.render(fixture.output).fromJson[JavaContentBlock].flatMap(_.toJsonAST),
            fixture.output
          )
        )
        val sourceChecks = sources.map(fixture =>
          JavaEventFixtures.same(
            JsonBoundary.render(fixture.output).fromJson[JavaContentSource].flatMap(_.toJsonAST),
            fixture.output
          )
        )
        assertTrue(
          content.size == 15,
          sources.size == 2,
          contentChecks.forall(identity),
          sourceChecks.forall(identity)
        )
    ,
    test(
      "creator aliases preserve dimensions, while missing data identity and invalid content reject"
    ):
      JavaEventFixtures.read.map: fixtures =>
        val media = fixtures.filter(fixture =>
          fixture.name == "content-image" ||
            fixture.name == "content-video"
        )
        val aliases = media.map(fixture =>
          JavaEventFixtures.same(
            JsonBoundary.render(fixture.input).fromJson[JavaContentBlock].flatMap(_.toJsonAST),
            fixture.output
          )
        )
        val invalid = Chunk(
          """{"type":"data","id":"","source":{"type":"url","url":"https://example.invalid"}}""",
          "{\"type\":\"data\",\"source\":{\"type\":\"url\",\"url\":\"https://example.invalid\"}}",
          "{\"type\":\"unknown\"}",
          "{\"type\":\"image\",\"source\":{\"type\":\"unknown\"}}",
          "{\"type\":\"text\",\"text\":\"first\",\"text\":\"second\"}"
        )
        val useRejectsText = "{\"type\":\"text\",\"text\":\"wrong\"}"
          .fromJson[JavaContentBlock.ToolUse]
          .isLeft
        assertTrue(
          aliases.forall(identity),
          invalid.forall(_.fromJson[JavaContentBlock].isLeft),
          useRejectsText
        )
  )
