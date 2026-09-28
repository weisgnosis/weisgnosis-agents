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
package com.weisgnosis.agents.core.client

import com.weisgnosis.agents.core.model.{ContentBlock, Msg, Role}
import zio.Chunk
import zio.test.*

/**
 * Verifies ReasoningModelAdapter SSE JSON parsing and context hygiene (obsolete reasoning pruning).
 */
object ReasoningAdapterSpec extends ZIOSpecDefault:

  def spec: Spec[TestEnvironment, Any] = suite("ReasoningAdapterSpec")(
    suite("SSE JSON Delta Parsing")(
      test("Parses chunk with reasoning_content") {
        val json =
          """{
            |  "choices": [
            |    {
            |      "delta": {
            |        "reasoning_content": "Deep thought about problem",
            |        "role": "assistant"
            |      }
            |    }
            |  ]
            |}""".stripMargin

        val chunks = ReasoningModelAdapter.parseChunk(json)
        assertTrue(
          chunks.size == 1,
          chunks.headOption.contains(LlmChunk.ThinkingDelta("Deep thought about problem"))
        )
      },
      test("Parses chunk with tool_calls") {
        val json =
          """{
            |  "choices": [
            |    {
            |      "delta": {
            |        "tool_calls": [
            |          {
            |            "index": 0,
            |            "id": "call_abc",
            |            "function": {
            |              "name": "search",
            |              "arguments": "{\"q\":"
            |            }
            |          }
            |        ]
            |      }
            |    }
            |  ]
            |}""".stripMargin

        val chunks = ReasoningModelAdapter.parseChunk(json)
        assertTrue(
          chunks.size == 1,
          chunks.headOption.contains(
            LlmChunk.ToolCallChunk(0, Some("call_abc"), Some("search"), "{\"q\":")
          )
        )
      },
      test("Parses usage chunk with cached tokens and reasoning tokens") {
        val json =
          """{
            |  "choices": [],
            |  "usage": {
            |    "prompt_tokens": 120,
            |    "completion_tokens": 80,
            |    "prompt_tokens_details": {
            |      "cached_tokens": 50
            |    },
            |    "completion_tokens_details": {
            |      "reasoning_tokens": 40
            |    }
            |  }
            |}""".stripMargin

        val chunks = ReasoningModelAdapter.parseChunk(json)
        assertTrue(
          chunks.size == 1,
          chunks.headOption match
            case Some(LlmChunk.UsageChunk(u)) =>
              u.inputTokens == 120 &&
              u.outputTokens == 80 &&
              u.cachedTokens == 50 &&
              u.reasoningTokens == 40 &&
              u.totalTokens == 200
            case _ => false
        )
      }
    ),
    suite("Context Hygiene (Pruning Obsolete Reasoning)")(
      test(
        "Preserves reasoning on the most recent assistant message and strips it from older turns"
      ) {
        val turn1User = Msg.user("Calculate 1 + 1")
        val turn1Assistant = Msg(
          role = Role.Assistant,
          content = Chunk(
            ContentBlock.Thinking("Let me think: 1 + 1 equals 2"),
            ContentBlock.Text("The answer is 2.")
          )
        )
        val turn2User = Msg.user("Now calculate 2 + 2")
        val turn2Assistant = Msg(
          role = Role.Assistant,
          content = Chunk(
            ContentBlock.Thinking("Now thinking: 2 + 2 equals 4"),
            ContentBlock.Text("The answer is 4.")
          )
        )

        val history = Chunk(turn1User, turn1Assistant, turn2User, turn2Assistant)
        val pruned  = ReasoningModelAdapter.pruneObsoleteReasoning(history)

        assertTrue(
          pruned.size == 4,
          // Turn 1 Assistant has its Thinking block stripped
          pruned.lift(1).exists(_.content.size == 1),
          pruned.lift(1).exists(_.thinking.isEmpty),
          pruned.lift(1).exists(_.text == "The answer is 2."),
          // Turn 2 Assistant retains its Thinking block
          pruned.lift(3).exists(_.content.size == 2),
          pruned.lift(3).exists(_.thinking == "Now thinking: 2 + 2 equals 4"),
          pruned.lift(3).exists(_.text == "The answer is 4.")
        )
      }
    )
  )
