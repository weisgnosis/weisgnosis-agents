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
import zio.stream.ZStream
import zio.test.*

/**
 * Verifies event streaming behavior over ZStream, Hub, and Queue with hierarchical source tagging.
 */
object AgentEventStreamingSpec extends ZIOSpecDefault:

  def spec: Spec[TestEnvironment, Any] = suite("AgentEventStreamingSpec")(
    test("ZStream emits and preserves ordering of agent events") {
      val e1 = AgentEvent.AgentStartEvent("sess-1", "rep-1", "agent-1")
      val e2 = AgentEvent.TextBlockDeltaEvent("Hello", 0)
      val e3 = AgentEvent.TextBlockEndEvent(0, "Hello")
      val e4 = AgentEvent.AgentEndEvent("rep-1")

      val stream = ZStream(e1, e2, e3, e4)

      for events <- stream.runCollect
      yield assertTrue(
        events.size == 4,
        events(0) == e1,
        events(1) == e2,
        events(2) == e3,
        events(3) == e4
      )
    },
    test("Hierarchical source tagging transforms stream events from subagents") {
      val rawEvents = Chunk(
        AgentEvent.TextBlockStartEvent(0),
        AgentEvent.TextBlockDeltaEvent("subagent result", 0),
        AgentEvent.TextBlockEndEvent(0, "subagent result")
      )

      val subagentStream = ZStream
        .fromChunk(rawEvents)
        .map(_.withSource("parent-sess/subagent-1"))

      for events <- subagentStream.runCollect
      yield assertTrue(
        events.size == 3,
        events.forall(_.source.contains("parent-sess/subagent-1"))
      )
    },
    test("Hub broadcasts events to multiple concurrent subscribers") {
      val ev = AgentEvent.AgentProgressEvent("Step completed", 1, Some(3))

      ZIO.scoped {
        for
          hub       <- Hub.bounded[AgentEvent](16)
          sub1      <- hub.subscribe
          sub2      <- hub.subscribe
          _         <- hub.publish(ev)
          received1 <- sub1.take
          received2 <- sub2.take
        yield assertTrue(
          received1 == ev,
          received2 == ev
        )
      }
    }
  )
