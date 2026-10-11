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

import com.weisgnosis.agents.core.event.{AgentEvent, ModelStreamContext}
import com.weisgnosis.agents.core.model.{ContentBlock, Msg}
import zio.*
import zio.json.ast.Json
import zio.test.*

object ModelStreamPublisherSpec extends ZIOSpecDefault:
  private val context = ModelStreamContext("reply", "request")

  def spec: Spec[TestEnvironment & Scope, Any] = suite("ModelStreamPublisher")(
    test("retains independent indexed text and thinking with ordered completed content"):
      ZIO
        .scoped:
          for
            hub       <- Hub.unbounded[AgentEvent]
            queue     <- hub.subscribe
            publisher <- ModelStreamPublisher.make(hub, context)
            _ <- publisher.frame(
              "frame-1",
              Chunk(
                LlmChunk.ThinkingDelta("I think ", 2, Some("opaque-signature")),
                LlmChunk.TextDelta("a", 2)
              )
            )
            _ <- publisher.frame(
              "frame-2",
              Chunk(
                LlmChunk.TextDelta("b", 2),
                LlmChunk.TextDelta("c", 0),
                LlmChunk.ThinkingDelta("", 0),
                LlmChunk.ThinkingDelta("this is ", 2)
              )
            )
            _ <- publisher.frame(
              "frame-3",
              Chunk(LlmChunk.ThinkingDelta("separate", 0), LlmChunk.ThinkingDelta("correct.", 2))
            )
            _      <- publisher.finish(TurnOutput.FinalText(Msg.assistant("abc")))
            events <- queue.takeAll
          yield assertTrue(
            events.collect { case event: AgentEvent.TextBlockStartEvent =>
              event.blockIndex
            } == Chunk(2, 0),
            events.collect { case event: AgentEvent.TextBlockEndEvent =>
              (event.blockIndex, event.fullText, event.context)
            } ==
              Chunk((2, "ab", Some(context)), (0, "c", Some(context))),
            events.collect { case event: AgentEvent.ThinkingBlockEndEvent =>
              (event.blockIndex, event.fullThought)
            } == Chunk((2, "I think this is correct."), (0, "separate")),
            events.collect { case event: AgentEvent.ThinkingBlockStartEvent =>
              event.blockIndex
            } == Chunk(2, 0),
            events.collect { case event: AgentEvent.ThinkingBlockDeltaEvent =>
              (event.blockIndex, event.delta, event.accumulatedThought, event.context)
            } == Chunk(
              (2, "I think ", Some("I think "), Some(context)),
              (0, "", Some(""), Some(context)),
              (2, "this is ", Some("I think this is "), Some(context)),
              (0, "separate", Some("separate"), Some(context)),
              (2, "correct.", Some("I think this is correct."), Some(context))
            ),
            events.collect { case event: AgentEvent.ModelResponseChunkEvent =>
              event.chunk
            } == Chunk("frame-1", "frame-2", "frame-3")
          )
    ,
    test("keeps interleaved tool identities and subsequent deltas separate"):
      ZIO.scoped:
        for
          hub       <- Hub.unbounded[AgentEvent]
          queue     <- hub.subscribe
          publisher <- ModelStreamPublisher.make(hub, context)
          _ <- publisher.frame(
            "calls",
            Chunk(
              LlmChunk.ToolCallChunk(1, Some("b"), Some("second"), "{"),
              LlmChunk.ToolCallChunk(0, Some("a"), Some("first"), "{}"),
              LlmChunk.ToolCallChunk(1, argumentsDelta = "}")
            )
          )
          _ <- publisher.finish(
            TurnOutput.ToolCalls(
              Msg.assistant(
                Chunk(
                  ContentBlock.ToolUse("a", "first", Json.Obj()),
                  ContentBlock.ToolUse("b", "second", Json.Obj())
                )
              )
            )
          )
          events <- queue.takeAll
        yield assertTrue(
          events.collect { case event: AgentEvent.ToolCallStartEvent => event.callId } == Chunk(
            "b",
            "a"
          ),
          events.collect { case event: AgentEvent.ToolCallDeltaEvent =>
            (event.callId, event.delta)
          } == Chunk(("b", "{"), ("a", "{}"), ("b", "}")),
          events.collect { case event: AgentEvent.ToolCallEndEvent => event.callId } == Chunk(
            "b",
            "a"
          )
        )
    ,
    test("buffers fragmented arguments until identity without changing published bytes"):
      val payload = "{\"value\":\"" + "x" * 4096 + "\"}"
      ZIO.scoped:
        for
          hub       <- Hub.unbounded[AgentEvent]
          queue     <- hub.subscribe
          publisher <- ModelStreamPublisher.make(hub, context)
          _ <- ZIO.foreachDiscard(payload.grouped(16).toList)(fragment =>
            publisher.frame("fragment", Chunk(LlmChunk.ToolCallChunk(2, argumentsDelta = fragment)))
          )
          _ <- publisher.frame(
            "identity",
            Chunk(LlmChunk.ToolCallChunk(2, Some("call"), Some("lookup")))
          )
          _ <- publisher.finish(
            TurnOutput.ToolCalls(
              Msg.assistant(
                Chunk(
                  ContentBlock.ToolUse("call", "lookup", Json.Obj("value" -> Json.Str("x" * 4096)))
                )
              )
            )
          )
          events <- queue.takeAll
        yield assertTrue(
          events.collect { case e: AgentEvent.ToolCallStartEvent => e.callId } == Chunk("call"),
          events.collect { case e: AgentEvent.ToolCallDeltaEvent => e.delta } == Chunk(payload),
          events.collect { case e: AgentEvent.ToolCallEndEvent => e.arguments } == Chunk(
            Json.Obj("value" -> Json.Str("x" * 4096))
          )
        )
    ,
    test("fails mismatched completed output without publishing a partial ending batch"):
      ZIO.scoped:
        for
          hub       <- Hub.unbounded[AgentEvent]
          queue     <- hub.subscribe
          publisher <- ModelStreamPublisher.make(hub, context)
          _ <- publisher.frame(
            "calls",
            Chunk(
              LlmChunk.TextDelta("prefix"),
              LlmChunk.ToolCallChunk(0, Some("a"), Some("first"), "{}")
            )
          )
          result <- publisher.finish(TurnOutput.FinalText(Msg.assistant("prefix"))).either
          events <- queue.takeAll
        yield assertTrue(
          result.isLeft,
          !events.exists(_.isInstanceOf[AgentEvent.TextBlockEndEvent]),
          !events.exists(_.isInstanceOf[AgentEvent.ToolCallEndEvent])
        )
  )
