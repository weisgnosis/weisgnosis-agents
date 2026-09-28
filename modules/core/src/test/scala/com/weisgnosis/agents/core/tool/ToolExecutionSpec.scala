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

import com.weisgnosis.agents.core.model.ToolResultState
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

/** 1:1 test parity suite for ToolExecutionTest.java from AgentScope Java.
  */
object ToolExecutionSpec extends ZIOSpecDefault:

  final case class AddInput(a: Int, b: Int) derives JsonCodec

  private val addTool: Tool[Any, AddInput, Int] =
    Tool.fromFunction[AddInput, Int]("add", "Add two integers") { in =>
      ZIO.succeed(in.a + in.b)
    }

  private val failingTool: Tool[Any, Json, String] =
    Tool.make(ToolDef("fail", "Always fails", Json.Obj())) { _ =>
      ZIO.fail(new RuntimeException("Simulated tool crash"))
    }

  private val toolkit = Toolkit.empty.withTool(addTool).withTool(failingTool)

  def spec: Spec[TestEnvironment, Any] = suite("ToolExecutionSpec")(
    test("Successful tool execution with typed argument decoding") {
      val validArgs = Json.Obj("a" -> Json.Num(15), "b" -> Json.Num(27))

      for res <- toolkit.executeSafe("add", validArgs)
      yield assertTrue(
        res.state == ToolResultState.Success,
        res.output == "42"
      )
    },
    test("Malformed arguments yield ToolResultState.Error without throwing") {
      val invalidArgs = Json.Obj("a" -> Json.Str("not_a_number"), "b" -> Json.Num(27))

      for res <- toolkit.executeSafe("add", invalidArgs)
      yield assertTrue(
        res.state == ToolResultState.Error,
        res.output.contains("Invalid arguments for 'add'")
      )
    },
    test("Missing tool returns ToolResultState.Error") {
      for res <- toolkit.executeSafe("nonexistent_tool", Json.Obj())
      yield assertTrue(
        res.state == ToolResultState.Error,
        res.output.contains("not found in toolkit")
      )
    },
    test("Tool execution failure returns ToolResultState.Error with cause") {
      for res <- toolkit.executeSafe("fail", Json.Obj())
      yield assertTrue(
        res.state == ToolResultState.Error,
        res.output.contains("Simulated tool crash")
      )
    },
    test("Long-running tool execution is responsive to fiber interruption") {
      val slowTool: Tool[Any, Json, String] =
        Tool.make(ToolDef("slow", "Takes forever", Json.Obj())) { _ =>
          ZIO.never
        }
      val slowTk = Toolkit.empty.withTool(slowTool)

      for
        fiber <- slowTk.executeSafe("slow", Json.Obj()).fork
        _ <- Live.live(ZIO.sleep(50.millis))
        _ <- fiber.interrupt
        exit <- fiber.await
      yield assertTrue(exit.isInterrupted)
    }
  )
