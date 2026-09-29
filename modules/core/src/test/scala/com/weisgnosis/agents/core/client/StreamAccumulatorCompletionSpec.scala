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

import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.model.{ChatUsage, ContentBlock}
import zio.Chunk
import zio.json.ast.Json
import zio.test.*

object StreamAccumulatorCompletionSpec extends ZIOSpecDefault:
  private def assemble(chunks: LlmChunk*): Either[AgentError, StreamAccumulator] =
    chunks.foldLeft[Either[AgentError, StreamAccumulator]](Right(StreamAccumulator.empty))(
      (acc, chunk) => acc.flatMap(_.feed(chunk))
    )

  private def call(arguments: String): LlmChunk =
    LlmChunk.ToolCallChunk(0, Some("call"), Some("tool"), arguments)

  def spec: Spec[TestEnvironment, Any] = suite("StreamAccumulatorCompletionSpec")(
    test("successful output requires a compatible terminal marker") {
      assertTrue(
        assemble(LlmChunk.TextDelta("partial")).flatMap(_.toTurnOutput).isLeft,
        assemble(call("{}"), LlmChunk.FinishReason("stop")).flatMap(_.toTurnOutput).isLeft,
        assemble(LlmChunk.FinishReason("tool_calls")).flatMap(_.toTurnOutput).isLeft,
        assemble(LlmChunk.TextDelta("partial"), LlmChunk.FinishReason("length"))
          .flatMap(_.toTurnOutput)
          .isLeft,
        assemble(LlmChunk.FinishReason("content_filter")).flatMap(_.toTurnOutput).isLeft
      )
    },
    test(
      "completed malformed non-object and ambiguous arguments fail instead of dispatching defaults"
    ) {
      val invalid =
        Chunk("", "{", "null", "[]", "42", "{} trailing", "{}{}", "{}]junk", """{"x":1,"x":2}""")
      assertTrue(
        invalid.forall(raw =>
          assemble(call(raw), LlmChunk.FinishReason("tool_calls")).flatMap(_.toTurnOutput).isLeft
        )
      )
    },
    test("interruption sanitizes both parsed and raw malformed arguments") {
      val invalid =
        Chunk("", "{", "null", "[]", "42", "{} trailing", "{}{}", "{}]junk", """{"x":1,"x":2}""")
      assertTrue(
        invalid.forall(raw =>
          assemble(call(raw))
            .map(_.toInterrupted.message.toolUses)
            .exists(tools =>
              tools.size == 1 && tools
                .forall(tool => tool.input == Json.Obj() && tool.content.contains("{}"))
            )
        )
      )
    },
    test("complete and interrupted valid calls preserve exact assembled raw JSON") {
      val raw = """ { "value" : 7 } """
      val acc = assemble(call(raw), LlmChunk.FinishReason("tool_calls"))
      assertTrue(
        acc
          .flatMap(_.toTurnOutput)
          .exists(
            _.message.toolUses.exists(tool =>
              tool.input == Json.Obj("value" -> Json.Num(7)) && tool.content.contains(raw)
            )
          ),
        acc.exists(_.toInterrupted.message.toolUses.exists(_.content.contains(raw)))
      )
    },
    test("identity can arrive after arguments but must be complete before dispatch") {
      val partial = assemble(LlmChunk.ToolCallChunk(0, argumentsDelta = "{}"))
      val completed = partial
        .flatMap(_.feed(LlmChunk.ToolCallChunk(0, Some("late"), Some("tool"))))
        .flatMap(_.feed(LlmChunk.FinishReason("tool_calls")))
        .flatMap(_.toTurnOutput)
      assertTrue(
        partial.flatMap(_.feed(LlmChunk.FinishReason("tool_calls"))).flatMap(_.toTurnOutput).isLeft,
        completed.exists(
          _.message.toolUses.exists(tool => tool.id == "late" && tool.name == "tool")
        ),
        partial.exists(_.toInterrupted.message.toolUses.exists(_.id == "stream_call_0"))
      )
    },
    test("tool identity cannot change at an established stream index") {
      assertTrue(
        assemble(
          call("{"),
          LlmChunk.ToolCallChunk(0, Some("different"), argumentsDelta = "}")
        ).isLeft,
        assemble(
          call("{"),
          LlmChunk.ToolCallChunk(0, name = Some("different"), argumentsDelta = "}")
        ).isLeft,
        assemble(
          call("{"),
          LlmChunk.ToolCallChunk(0, Some("call"), Some("tool"), "}"),
          LlmChunk.FinishReason("tool_calls")
        ).flatMap(_.toTurnOutput).isRight
      )
    },
    test("different tool indexes cannot complete with the same call ID") {
      assertTrue(
        assemble(
          call("{}"),
          LlmChunk.ToolCallChunk(1, Some("call"), Some("other"), "{}"),
          LlmChunk.FinishReason("tool_calls")
        ).flatMap(_.toTurnOutput).isLeft
      )
    },
    test("interleaved tool fragments retain numeric index ordering") {
      val result = assemble(
        LlmChunk.ToolCallChunk(1, Some("second"), Some("tool"), "{\"x\":"),
        LlmChunk.ToolCallChunk(0, Some("first"), Some("tool"), "{}"),
        LlmChunk.ToolCallChunk(1, argumentsDelta = "2}"),
        LlmChunk.FinishReason("tool_calls")
      ).flatMap(_.toTurnOutput)
      assertTrue(result.exists(_.message.toolUses.map(_.id) == Chunk("first", "second")))
    },
    test("indexed text and thinking blocks preserve order and signature fragments") {
      val result = assemble(
        LlmChunk.ThinkingDelta("reason ", 0, Some("sig-")),
        LlmChunk.TextDelta("first ", 0),
        LlmChunk.ThinkingDelta("second", 1, Some("other")),
        LlmChunk.TextDelta("block", 0),
        LlmChunk.ThinkingDelta("done", 0, Some("end")),
        LlmChunk.FinishReason("stop")
      ).flatMap(_.toTurnOutput)
      assertTrue(
        result.exists(
          _.message.content == Chunk(
            ContentBlock.Thinking("reason done", Map("thoughtSignature" -> Json.Str("sig-end"))),
            ContentBlock.Text("first block"),
            ContentBlock.Thinking("second", Map("thoughtSignature" -> Json.Str("other")))
          )
        )
      )
    },
    test("signature-only thinking blocks survive interruption and completion") {
      val acc =
        assemble(LlmChunk.ThinkingDelta("", 2, Some(" signed ")), LlmChunk.FinishReason("stop"))
      val expected =
        Chunk(ContentBlock.Thinking("", Map("thoughtSignature" -> Json.Str(" signed "))))
      assertTrue(
        acc.flatMap(_.toTurnOutput).exists(_.message.content == expected),
        acc.exists(_.toInterrupted.message.content == expected)
      )
    },
    test("usage is the latest snapshot and may arrive after the finish marker") {
      val usage =
        ChatUsage(inputTokens = 5, outputTokens = 9, cachedTokens = 2, reasoningTokens = 3)
      val result = assemble(
        LlmChunk.UsageChunk(ChatUsage(inputTokens = 5)),
        LlmChunk.TextDelta("done"),
        LlmChunk.FinishReason("stop"),
        LlmChunk.UsageChunk(usage)
      ).flatMap(_.toTurnOutput)
      assertTrue(result.exists(_.message.usage.contains(usage)))
    },
    test("content and additional finish markers after termination are rejected") {
      val finished = assemble(LlmChunk.TextDelta("done"), LlmChunk.FinishReason("stop"))
      assertTrue(
        finished.flatMap(_.feed(LlmChunk.TextDelta("late"))).isLeft,
        finished.flatMap(_.feed(call("{}"))).isLeft,
        finished.flatMap(_.feed(LlmChunk.FinishReason("stop"))).isLeft,
        finished.flatMap(_.feed(LlmChunk.FinishReason("tool_calls"))).isLeft
      )
    },
    test("negative indexes blank identities and empty finish reasons fail") {
      assertTrue(
        assemble(LlmChunk.TextDelta("text", -1)).isLeft,
        assemble(LlmChunk.ThinkingDelta("reason", -1)).isLeft,
        assemble(LlmChunk.ToolCallChunk(-1)).isLeft,
        assemble(LlmChunk.ToolCallChunk(0, Some(" "))).isLeft,
        assemble(LlmChunk.ToolCallChunk(0, name = Some(""))).isLeft,
        assemble(LlmChunk.FinishReason("")).isLeft
      )
    }
  )
