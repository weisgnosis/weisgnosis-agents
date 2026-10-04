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
package com.weisgnosis.agents.core.model

import zio.*
import zio.json.ast.Json
import zio.test.*

object ToolHistorySpec extends ZIOSpecDefault:
  private def call(name: String): ContentBlock.ToolUse =
    ContentBlock.ToolUse("same", name, Json.Obj())
  def spec: Spec[TestEnvironment, Any] = suite("ToolHistorySpec")(
    test("reversed named results retain exact assistant positions and each occurrence once") {
      val analysis = ToolHistory.inspect(
        Chunk(
          Msg.assistant(Chunk(call("first"))),
          Msg.assistant(Chunk(call("second"))),
          Msg.tool("same", "second", "two"),
          Msg.tool("same", "first", "one")
        )
      )
      assertTrue(
        analysis.isComplete,
        analysis.spans == Chunk(ToolHistory.Span(1, 2), ToolHistory.Span(0, 3))
      )
    },
    test("inline observations only consume provider calls in their own assistant message") {
      val local  = call("local")
      val server = call("provider").withServerTool(true)
      val result = ContentBlock.ToolResult.text("same", "provider", "inline")
      val complete = ToolHistory.inspect(
        Chunk(
          Msg.assistant(Chunk(local)),
          Msg.assistant(Chunk(server, result)),
          Msg.tool("same", "local", "local")
        )
      )
      val invalid =
        ToolHistory.inspect(Chunk(Msg.assistant(Chunk(local)), Msg.assistant(Chunk(result))))
      assertTrue(
        complete.isComplete,
        complete.spans == Chunk(ToolHistory.Span(1, 1), ToolHistory.Span(0, 2)),
        invalid.pending.map(_.call) == Chunk(local),
        invalid.unmatched == Chunk(result)
      )
    },
    test("unnamed observations consume FIFO while wrong named observations remain unmatched") {
      val result = ContentBlock.ToolResult("same", None)
      val wrong  = ContentBlock.ToolResult.text("same", "unknown", "wrong")
      val analysis = ToolHistory.inspect(
        Chunk(Msg.assistant(Chunk(call("first"), call("second"))), Msg.tool(Chunk(result, wrong)))
      )
      assertTrue(
        !analysis.isComplete,
        analysis.pending.map(_.call.name) == Chunk("second"),
        analysis.pending.map(_.ordinal) == Chunk(1),
        analysis.unmatched == Chunk(wrong),
        analysis.spans == Chunk(ToolHistory.Span(0, 1))
      )
    }
  )
