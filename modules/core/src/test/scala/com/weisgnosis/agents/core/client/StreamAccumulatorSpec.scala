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
import com.weisgnosis.agents.core.model.ContentBlock
import zio.json.ast.Json
import zio.test.*

/**
 * Verifies invariant INV-12: Chunk index assembly across parallel interleaved tool calls
 * and sanitization of malformed JSON on interruption.
 */
object StreamAccumulatorSpec extends ZIOSpecDefault:

  private def accumulate(chunks: Seq[LlmChunk]): Either[AgentError, StreamAccumulator] =
    chunks.foldLeft[Either[AgentError, StreamAccumulator]](Right(StreamAccumulator.empty))(
      (acc, chunk) => acc.flatMap(_.feed(chunk))
    )

  def spec: Spec[TestEnvironment, Any] = suite("StreamAccumulatorSpec (INV-12)")(
    test("Assembles interleaved parallel tool calls keyed by streamIndex") {
      val chunks = Seq(
        LlmChunk.ToolCallChunk(
          streamIndex = 0,
          callId = Some("call_1"),
          name = Some("calculator"),
          argumentsDelta = "{\"x\":"
        ),
        LlmChunk.ToolCallChunk(
          streamIndex = 1,
          callId = Some("call_2"),
          name = Some("web_search"),
          argumentsDelta = "{\"query\":"
        ),
        LlmChunk
          .ToolCallChunk(streamIndex = 0, callId = None, name = None, argumentsDelta = " 42}"),
        LlmChunk.ToolCallChunk(
          streamIndex = 1,
          callId = None,
          name = None,
          argumentsDelta = " \"scala 3\"}"
        )
      )

      val acc    = accumulate(chunks)
      val output = acc.flatMap(_.feed(LlmChunk.FinishReason("tool_calls"))).flatMap(_.toTurnOutput)

      assertTrue(
        output match
          case Right(TurnOutput.ToolCalls(msg)) =>
            msg.toolUses.size == 2 &&
            msg.toolUses.lift(0).exists(_.id == "call_1") &&
            msg.toolUses.lift(0).exists(_.name == "calculator") &&
            msg.toolUses.lift(0).exists(_.input == Json.Obj("x" -> Json.Num(42))) &&
            msg.toolUses.lift(1).exists(_.id == "call_2") &&
            msg.toolUses.lift(1).exists(_.name == "web_search") &&
            msg.toolUses.lift(1).exists(_.input == Json.Obj("query" -> Json.Str("scala 3")))
          case _ => false
      )
    },
    test("Detects Thinking-Only turn when no text and no tools are produced") {
      val chunks = Seq(
        LlmChunk.ThinkingDelta("Thinking step 1..."),
        LlmChunk.ThinkingDelta(" Thinking step 2...")
      )

      val acc    = accumulate(chunks)
      val output = acc.flatMap(_.feed(LlmChunk.FinishReason("stop"))).flatMap(_.toTurnOutput)

      val signed = accumulate(Seq(LlmChunk.ThinkingDelta("", signature = Some("opaque"))))
        .flatMap(_.feed(LlmChunk.FinishReason("stop")))
        .flatMap(_.toTurnOutput)
      val empty = accumulate(Seq(LlmChunk.ThinkingDelta("")))
        .flatMap(_.feed(LlmChunk.FinishReason("stop")))
        .flatMap(_.toTurnOutput)

      assertTrue(
        output match
          case Right(TurnOutput.ThinkingOnly(msg)) =>
            msg.thinking == "Thinking step 1... Thinking step 2..." &&
            msg.text.isEmpty &&
            msg.toolUses.isEmpty
          case _ => false,
        signed match
          case Right(TurnOutput.ThinkingOnly(msg)) =>
            msg.thinking.isEmpty && msg.text.isEmpty && msg.content.contains(
              ContentBlock.Thinking("", Map("thoughtSignature" -> Json.Str("opaque")))
            )
          case _ => false,
        empty.exists(_.isInstanceOf[TurnOutput.FinalText])
      )
    },
    test("Detects FinalText turn when plain text is produced") {
      val chunks = Seq(
        LlmChunk.TextDelta("Hello "),
        LlmChunk.TextDelta("world!")
      )

      val acc    = accumulate(chunks)
      val output = acc.flatMap(_.feed(LlmChunk.FinishReason("stop"))).flatMap(_.toTurnOutput)

      assertTrue(
        output match
          case Right(TurnOutput.FinalText(msg)) =>
            msg.text == "Hello world!"
          case _ => false
      )
    },
    test("Interruption sanitizes incomplete tool arguments to empty object {}") {
      val chunks = Seq(
        LlmChunk.ToolCallChunk(
          streamIndex = 0,
          callId = Some("call_interrupted"),
          name = Some("bash"),
          argumentsDelta = "{\"command\": \"cat /etc/passwd"
        )
      )

      val acc         = accumulate(chunks)
      val interrupted = acc.map(_.toInterrupted)

      assertTrue(
        interrupted match
          case Right(TurnOutput.Interrupted(msg)) =>
            msg.toolUses.size == 1 &&
            msg.toolUses.headOption.exists(call =>
              call.id == "call_interrupted" && call.input == Json.Obj()
            )
          case _ => false
      )
    }
  )
