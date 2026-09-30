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
import com.weisgnosis.agents.core.tool.{Tool, ToolDef, Toolkit}
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

object SubagentToolBoundarySpec extends ZIOSpecDefault:
  private def tool(name: String): Tool[Any, Json, String] =
    Tool.make(ToolDef(name, "Boundary fixture", Json.Obj()))(_ => ZIO.succeed(name))

  def spec: Spec[TestEnvironment & Scope, Any] = suite("SubagentToolBoundarySpec")(
    test(
      "selection intersections and accumulated denials never widen a lineage, including JSON round trips"
    ) {
      val names = Set("read", "write", "execute")
      val selections = Chunk(
        SubagentToolSelection.AllAvailable,
        SubagentToolSelection.Only(Set.empty),
        SubagentToolSelection.Only(Set("read", "write")),
        SubagentToolSelection.Only(Set("write", "execute"))
      )
      val boundaries = selections.flatMap(selection =>
        Chunk(SubagentToolBoundary(selection), SubagentToolBoundary(selection, Set("write")))
      )
      val checks = for
        parent     <- boundaries
        child      <- boundaries
        grandchild <- boundaries
      yield
        val narrowed = parent.narrow(child).narrow(grandchild)
        val expected = names.filter(name =>
          Chunk(parent, child, grandchild).forall(boundary =>
            boundary.selection.contains(name) && !boundary.denied.contains(name)
          )
        )
        val actual =
          names.filter(name => narrowed.selection.contains(name) && !narrowed.denied.contains(name))
        actual == expected && narrowed.toJson.fromJson[SubagentToolBoundary] == Right(narrowed)
      assertTrue(checks.forall(identity))
    },
    test("excluded collisions and malformed keys fail deterministically before filtering") {
      val boundary  = SubagentToolBoundary(SubagentToolSelection.Only(Set.empty))
      val inherited = Toolkit(Map("z" -> tool("z"), "a" -> tool("a")))
      val collision = boundary.assemble(inherited, Toolkit(Map("z" -> tool("z"), "a" -> tool("a"))))
      val malformed = Toolkit(Map("z" -> tool("wrong-z"), "a" -> tool("wrong-a")))
      val expectedMismatch = Left(
        AgentError.InvalidConfiguration(
          "Toolkit key 'a' does not match its declared tool name 'wrong-a'"
        )
      )
      assertTrue(
        collision == Left(
          AgentError.ToolExecutionError("a", "Tool collision: 'a' is already registered")
        ),
        boundary.assemble(malformed, Toolkit.empty) == expectedMismatch,
        boundary.assemble(inherited, malformed) == expectedMismatch,
        inherited.tools.keySet == Set("a", "z")
      )
    }
  )
