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

import zio.*
import zio.json.ast.Json
import zio.test.*

/** 1:1 test parity suite for ToolkitTest.java from AgentScope Java.
  */
object ToolkitSpec extends ZIOSpecDefault:

  private val dummyTool1: Tool[Any, Json, String] =
    Tool.make(ToolDef("calc", "Performs arithmetic", Json.Obj())) { _ =>
      ZIO.succeed("result")
    }

  private val dummyTool2: Tool[Any, Json, String] =
    Tool.make(ToolDef("search", "Performs web search", Json.Obj(), isReadOnly = true)) { _ =>
      ZIO.succeed("found")
    }

  def spec: Spec[TestEnvironment, Any] = suite("ToolkitSpec (AgentScope Java Parity)")(
    test("Register tools and lookup by name") {
      val tk = Toolkit.empty.withTool(dummyTool1).withTool(dummyTool2)

      assertTrue(
        tk.size == 2,
        !tk.isEmpty,
        tk.contains("calc"),
        tk.contains("search"),
        !tk.contains("unknown"),
        tk.get("calc").isDefined,
        tk.get("calc").get.definition.name == "calc",
        tk.definitions.map(_.name).toSet == Set("calc", "search")
      )
    },
    test("Reject duplicate tool registrations via register method") {
      val tk1 = Toolkit.empty.withTool(dummyTool1)
      val result = tk1.register(dummyTool1)

      assertTrue(
        result.isLeft,
        result.swap.toOption.get.contains("Tool collision")
      )
    },
    test("Merge two Toolkits via ++") {
      val tk1 = Toolkit.empty.withTool(dummyTool1)
      val tk2 = Toolkit.empty.withTool(dummyTool2)
      val merged = tk1 ++ tk2

      assertTrue(
        merged.size == 2,
        merged.contains("calc"),
        merged.contains("search")
      )
    }
  )
