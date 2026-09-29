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
package com.weisgnosis.agents.harness.eviction

import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.model.*
import zio.*
import zio.json.ast.Json
import zio.test.*

object ToolResultEvictorSpec extends ZIOSpecDefault:
  private def memoryStore(saved: Ref[Map[String, String]]): ToolResultStore = new ToolResultStore:
    def resolve(key: String): IO[AgentError, Option[StoredToolResult]] =
      saved.get.map(
        _.get(key).map(content => StoredToolResult(content, s"large_tool_results/$key.txt"))
      )
    def persist(key: String, content: String): IO[AgentError, String] =
      saved.update(_.updated(key, content)).as(s"large_tool_results/$key.txt")

  def spec: Spec[TestEnvironment, Any] = suite("ToolResultEvictorSpec")(
    test(
      "oversized results are persisted with 2k head and tail previews and read_file instructions"
    ) {
      val text = "A" * 2000 + "M" * 76001 + "Z" * 2000
      for
        saved <- Ref.make(Map.empty[String, String])
        result <- new ToolResultEvictor(memoryStore(saved))
          .evictOversizedToolResults(Chunk(Msg.tool("call", "shell", text)))
        files <- saved.get
        block = result.flatMap(_.toolResults).headOption
      yield assertTrue(
        files.values.toSet == Set(text),
        block.exists(_.text.contains("A" * 2000)),
        block.exists(_.text.endsWith("Z" * 2000)),
        block.exists(_.text.contains("read_file")),
        block.exists(_.text.length < 5000)
      )
    },
    test("threshold equality and excluded tools preserve the exact messages") {
      val input =
        Chunk(Msg.tool("a", "shell", "a" * 80000), Msg.tool("b", "read_file", "b" * 80001))
      for
        saved  <- Ref.make(Map.empty[String, String])
        result <- new ToolResultEvictor(memoryStore(saved)).evictOversizedToolResults(input)
        files  <- saved.get
      yield assertTrue(result == input, files.isEmpty)
    },
    test("persistence failure preserves the full original observation") {
      val store = new ToolResultStore:
        def resolve(key: String): IO[AgentError, Option[StoredToolResult]] =
          ZIO.fail(AgentError.ToolExecutionError("store", "disk full"))
        def persist(key: String, content: String): IO[AgentError, String] =
          ZIO.fail(AgentError.ToolExecutionError("store", "disk full"))
      val input = Chunk(Msg.tool("a", "shell", "a" * 80001))
      for result <- new ToolResultEvictor(store).evictOversizedToolResults(input)
      yield assertTrue(result == input)
    },
    test("cached content keeps rewritten metadata and error state, including repeated call IDs") {
      val original = ContentBlock.ToolResult
        .text("same", "shell", "a" * 80001)
        .copy(metadata = Map("hook" -> Json.Str("first")))
      val rewritten =
        original.copy(state = ToolResultState.Error, metadata = Map("hook" -> Json.Str("second")))
      val different = ContentBlock.ToolResult.text("same", "shell", "b" * 80001)
      for
        saved <- Ref.make(Map.empty[String, String])
        evictor = new ToolResultEvictor(memoryStore(saved))
        result <- evictor.evictOversizedToolResults(
          Chunk(Msg.tool(content = Chunk(original, rewritten, different), name = None))
        )
        repeated <- evictor.evictOversizedToolResults(result)
        files    <- saved.get
        results = result.flatMap(_.toolResults)
      yield assertTrue(
        files.size == 2,
        results.size == 3,
        results.exists(r =>
          r.state == ToolResultState.Error && r.metadata.get("hook").contains(Json.Str("second"))
        ),
        repeated == result
      )
    },
    test("real output beginning with the placeholder prefix is still evicted") {
      val text = "Tool output was too large" + "x" * 80001
      for
        saved <- Ref.make(Map.empty[String, String])
        _ <- new ToolResultEvictor(memoryStore(saved))
          .evictOversizedToolResults(Chunk(Msg.tool("a", "shell", text)))
        files <- saved.get
      yield assertTrue(files.values.toSet == Set(text))
    },
    test("persistence interruption propagates instead of becoming an observation") {
      val store = new ToolResultStore:
        def resolve(key: String): IO[AgentError, Option[StoredToolResult]] =
          ZIO.interrupt
        def persist(key: String, content: String): IO[AgentError, String] = ZIO.interrupt
      for exit <- new ToolResultEvictor(store)
          .evictOversizedToolResults(Chunk(Msg.tool("a", "shell", "x" * 80001)))
          .exit
      yield assertTrue(exit.isInterrupted)
    }
  )
