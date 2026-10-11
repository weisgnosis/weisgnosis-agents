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
          .exists(_.isInstanceOf[TurnOutput.Truncated]),
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
    test("large one-character argument fragments preserve immutable interruption snapshots") {
      val raw = "{\"value\":\"" + ("x" * 100000) + "\"}"
      val partial = raw
        .dropRight(2)
        .iterator
        .foldLeft[Either[AgentError, StreamAccumulator]](
          assemble(call(""))
        )((acc, character) =>
          acc.flatMap(_.feed(LlmChunk.ToolCallChunk(0, argumentsDelta = character.toString)))
        )
      val completed = partial
        .flatMap(_.feed(LlmChunk.ToolCallChunk(0, argumentsDelta = raw.takeRight(2))))
        .flatMap(_.feed(LlmChunk.FinishReason("tool_calls")))
      assertTrue(
        completed
          .flatMap(_.toTurnOutput)
          .exists(
            _.message.toolUses.exists(tool =>
              tool.input == Json.Obj("value" -> Json.Str("x" * 100000)) && tool.content
                .contains(raw)
            )
          ),
        completed.exists(_.toInterrupted.message.toolUses.exists(_.content.contains(raw))),
        partial.exists(acc =>
          val tools = acc.toInterrupted.message.toolUses
          tools.size == 1 && tools.forall(tool =>
            tool.input == Json.Obj() && tool.content.contains("{}")
          )
        )
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
      val exactNumber = Json.Num(new java.math.BigDecimal("-9223372036854775808.123456789"))
      val firstMetadata = Map(
        "provider" -> Json.Str("old"),
        "items"    -> Json.Arr(Json.Str("a"), Json.Str("b")),
        "changing" -> Json.Arr(Json.Str("before")),
        "object"   -> Json.Obj("old" -> Json.Bool(true)),
        "exact"    -> exactNumber
      )
      val nextMetadata = Map(
        "provider" -> Json.Str("new"),
        "items"    -> Json.Arr(Json.Str("c")),
        "changing" -> Json.Str("replaced"),
        "object"   -> Json.Obj("new" -> Json.Bool(true))
      )
      val expectedMetadata = firstMetadata ++ nextMetadata ++ Map(
        "items"    -> Json.Arr(Json.Str("a"), Json.Str("b"), Json.Str("c")),
        "changing" -> Json.Arr(Json.Str("after"))
      )
      val first    = assemble(LlmChunk.ThinkingDelta("reason ", 0, Some("sig-"), firstMetadata))
      val snapshot = first.map(_.toInterrupted.message)
      val result = assemble(
        LlmChunk.ThinkingDelta("reason ", 0, Some("sig-"), firstMetadata),
        LlmChunk.TextDelta("first ", 0),
        LlmChunk.ThinkingDelta("second", 1, Some("other")),
        LlmChunk.TextDelta("block", 0),
        LlmChunk.ThinkingDelta("done", 0, Some("end"), nextMetadata),
        LlmChunk.ThinkingDelta(
          "",
          0,
          providerMetadata = Map(
            "items"    -> Json.Arr(),
            "changing" -> Json.Arr(Json.Str("after"))
          )
        ),
        LlmChunk.FinishReason("stop")
      ).flatMap(_.toTurnOutput)
      assertTrue(
        result.exists(
          _.message.content == Chunk(
            ContentBlock.Thinking(
              "reason done",
              Map(
                "thoughtSignature" -> Json.Str("sig-end"),
                "providerMetadata" -> Json.Obj(Chunk.fromIterable(expectedMetadata))
              )
            ),
            ContentBlock.Text("first block"),
            ContentBlock.Thinking("second", Map("thoughtSignature" -> Json.Str("other")))
          )
        ),
        snapshot.exists(
          _.content == Chunk(
            ContentBlock.Thinking(
              "reason ",
              Map(
                "thoughtSignature" -> Json.Str("sig-"),
                "providerMetadata" -> Json.Obj(Chunk.fromIterable(firstMetadata))
              )
            )
          )
        ),
        first
          .flatMap(_.feed(LlmChunk.ThinkingDelta("later", providerMetadata = nextMetadata)))
          .exists(
            _.toInterrupted.message.content != snapshot.map(_.content).getOrElse(Chunk.empty)
          ),
        snapshot == first.map(_.toInterrupted.message)
      )
    },
    test("signature-only thinking blocks survive interruption and completion") {
      val acc =
        assemble(LlmChunk.ThinkingDelta("", 2, Some(" signed ")), LlmChunk.FinishReason("stop"))
      val expected =
        Chunk(ContentBlock.Thinking("", Map("thoughtSignature" -> Json.Str(" signed "))))
      val metadataOnly = assemble(
        LlmChunk.ThinkingDelta("", providerMetadata = Map("provider" -> Json.Str("only"))),
        LlmChunk.FinishReason("stop")
      ).flatMap(_.toTurnOutput)
      assertTrue(
        acc.flatMap(_.toTurnOutput).exists(_.message.content == expected),
        acc.exists(_.toInterrupted.message.content == expected),
        metadataOnly.exists(output =>
          output.isInstanceOf[TurnOutput.FinalText] && !output.message.hasReasoning &&
            output.message.content == Chunk(
              ContentBlock.Thinking(
                "",
                Map(
                  "providerMetadata" -> Json.Obj("provider" -> Json.Str("only"))
                )
              )
            )
        )
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
      val reserved = Chunk(
        "reasoningDetails",
        "reasoning_details",
        "thoughtSignature",
        "signature",
        "providerMetadata"
      )
      assertTrue(
        assemble(LlmChunk.TextDelta("text", -1)).isLeft,
        assemble(LlmChunk.ThinkingDelta("reason", -1)).isLeft,
        assemble(LlmChunk.ToolCallChunk(-1)).isLeft,
        assemble(LlmChunk.ToolCallChunk(0, Some(" "))).isLeft,
        assemble(LlmChunk.ToolCallChunk(0, name = Some(""))).isLeft,
        assemble(LlmChunk.FinishReason("")).isLeft,
        reserved.forall(key =>
          assemble(
            LlmChunk.ThinkingDelta("", providerMetadata = Map(key -> Json.Str("forged")))
          ).isLeft &&
            assemble(
              LlmChunk.ReasoningDetailsDelta(Chunk.empty, Map(key -> Json.Str("forged")))
            ).isLeft
        ),
        assemble(
          LlmChunk.ThinkingDelta(
            "",
            providerMetadata = Map(
              "nested" -> Json.Obj("duplicate" -> Json.Num(1), "duplicate" -> Json.Num(2))
            )
          )
        ).isLeft,
        assemble(
          LlmChunk.ThinkingDelta("", providerMetadata = Map("text" -> Json.Str("\uD800")))
        ).isLeft
      )
    }
  )
