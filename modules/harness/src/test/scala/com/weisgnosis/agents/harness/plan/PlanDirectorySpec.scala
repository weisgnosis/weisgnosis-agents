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
package com.weisgnosis.agents.harness.plan

import zio.*
import zio.json.*
import zio.test.*

object PlanDirectorySpec extends ZIOSpecDefault:
  def spec: Spec[TestEnvironment & Scope, Any] = suite("PlanDirectorySpec")(
    test("boundary normalization removes whitespace without hiding non-whitespace controls") {
      val invalid = Chunk("\u0000plans", "plans\u0001", "\u0000")
      assertTrue(
        invalid.forall(PlanDirectory.fromString(_).isLeft),
        invalid.forall(_.toJson.fromJson[PlanDirectory].isLeft),
        PlanDirectory.fromString("\u2003/docs/plans/\u2003").exists(_.value == "docs/plans")
      )
    },
    test(
      "logical configuration strips outer whitespace and slashes without interpreting a native root"
    ) {
      val inputs = Chunk("/docs/plans/", " docs/plans ", "///docs/plans///")
      assertTrue(
        inputs.forall(input => PlanDirectory.fromString(input).exists(_.value == "docs/plans"))
      )
    },
    test("blank and all-slash configuration select the plans default") {
      val inputs = Chunk("", " ", "\t\n", " / ", "////")
      assertTrue(
        PlanDirectory.Default.value == "plans",
        inputs.forall(PlanDirectory.fromString(_) == Right(PlanDirectory.Default)),
        "null".fromJson[PlanDirectory].isLeft,
        "42".fromJson[PlanDirectory].isLeft
      )
    },
    test("segments reject traversal ambiguity native drive syntax and nonportable characters") {
      val inputs = Chunk(
        ".",
        "..",
        "plans/../outside",
        "plans/./draft",
        "plans//draft",
        "C:/plans",
        "docs\\plans",
        "plans/a:b",
        "plans/*.md",
        "plans/a?b",
        "plans/\"name\"",
        "plans/a<b",
        "plans/a>b",
        "plans/a|b",
        "plans/ trailing",
        "plans/trailing /child",
        "plans/trailing.",
        "plans/hidden\nname",
        "plans/\uDB40\uDC01",
        "plans/\uD800"
      )
      assertTrue(inputs.forall(PlanDirectory.fromString(_).isLeft))
    },
    test("portable logical names reject reserved device stems regardless of case or extension") {
      val inputs = Chunk(
        "CON",
        "prn.txt",
        "Aux",
        "nul",
        "NUL .txt",
        "CONIN$",
        "CONOUT$",
        "COM1",
        "COM9.txt",
        "lpt1",
        "LPT9",
        "COM\u00b9",
        "LPT\u00b2.txt",
        "COM\u00b3"
      )
      assertTrue(inputs.forall(input => PlanDirectory.fromString(s"plans/$input").isLeft))
    },
    test(
      "the codec emits canonical logical text and preserves visible Unicode and internal spaces"
    ) {
      for
        normalized <- ZIO.fromEither("\"/docs/plans/\"".fromJson[PlanDirectory])
        visible    <- ZIO.fromEither(PlanDirectory.fromString("draft notes/\uD83D\uDE00-plan"))
      yield assertTrue(
        normalized.toJson == "\"docs/plans\"",
        visible.value == "draft notes/\uD83D\uDE00-plan",
        visible.toJson.fromJson[PlanDirectory] == Right(visible),
        PlanDirectory.fromString("plans/COM10").isRight
      )
    }
  )
