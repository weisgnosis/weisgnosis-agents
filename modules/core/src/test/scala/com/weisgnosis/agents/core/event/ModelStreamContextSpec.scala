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
package com.weisgnosis.agents.core.event

import zio.*
import zio.json.*
import zio.test.*

object ModelStreamContextSpec extends ZIOSpecDefault:
  private val context = ModelStreamContext("reply", "request")
  private val events: Chunk[AgentEvent] = Chunk(
    AgentEvent.TextBlockStartEvent(0, context = Some(context)),
    AgentEvent.TextBlockDeltaEvent("text", 0, context = Some(context)),
    AgentEvent.TextBlockEndEvent(0, "text", context = Some(context)),
    AgentEvent.ThinkingBlockStartEvent(0, context = Some(context)),
    AgentEvent.ThinkingBlockDeltaEvent("thought", 0, context = Some(context)),
    AgentEvent.ThinkingBlockEndEvent(0, "thought", context = Some(context)),
    AgentEvent.ToolCallStartEvent("call", "tool", 0, context = Some(context)),
    AgentEvent.ToolCallDeltaEvent("call", "{}", 0, context = Some(context)),
    AgentEvent
      .ToolCallEndEvent("call", "tool", zio.json.ast.Json.Obj(), 0, context = Some(context)),
    AgentEvent.ModelRequestStartEvent("model", 0, context = Some(context)),
    AgentEvent.ModelResponseChunkEvent("chunk", context = Some(context)),
    AgentEvent.ModelResponseEndEvent("model", 1, 2, context = Some(context))
  )

  def spec: Spec[TestEnvironment & Scope, Any] = suite("ModelStreamContext")(
    test("round-trips supplied context across all twelve event variants and source updates"):
      assertTrue(
        events.forall(event =>
          event.toJson.fromJson[AgentEvent] == Right(event) &&
            event.withSource("agent").toJson.fromJson[AgentEvent] == Right(
              event.withSource("agent")
            ) &&
            event.toJson.contains(context.toJson)
        )
      )
    ,
    test("retains legacy absent context and rejects incomplete supplied identity"):
      assertTrue(
        """{"event":"text_block_delta","delta":"x","blockIndex":0}""".fromJson[AgentEvent] == Right(
          AgentEvent.TextBlockDeltaEvent("x", 0)
        ),
        """{"event":"text_block_delta","delta":"x","blockIndex":0,"context":{"replyId":"reply"}}"""
          .fromJson[AgentEvent]
          .isLeft,
        context.toJson.fromJson[ModelStreamContext] == Right(context)
      )
  )
