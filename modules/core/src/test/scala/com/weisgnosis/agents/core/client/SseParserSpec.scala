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

import zio.*
import zio.stream.ZStream
import zio.test.*

/**
 * Selected upstream scenario suite for OpenAISseParserTest.java from AgentScope Java.
 */
object SseParserSpec extends ZIOSpecDefault:

  def spec: Spec[TestEnvironment, Any] = suite("SseParserSpec")(
    test("Parses multi-line SSE data payloads correctly") {
      val rawLines = Chunk(
        "data: first line",
        "data: second line",
        "",
        "data: third event",
        ""
      )

      for events <- ZStream.fromChunk(rawLines).via(SseParser.parseLines).runCollect
      yield assertTrue(
        events.size == 2,
        events.lift(0).exists(_.data == "first line\nsecond line"),
        events.lift(1).exists(_.data == "third event")
      )
    },
    test("Discards comment lines like ': keep-alive'") {
      val rawLines = Chunk(
        ": keep-alive",
        ": ping",
        "data: actual payload",
        ""
      )

      for events <- ZStream.fromChunk(rawLines).via(SseParser.parseLines).runCollect
      yield assertTrue(
        events.size == 1,
        events.lift(0).exists(_.data == "actual payload")
      )
    },
    test("Recognizes standard [DONE] termination frame") {
      val rawLines = Chunk(
        "data: [DONE]",
        ""
      )

      for events <- ZStream.fromChunk(rawLines).via(SseParser.parseLines).runCollect
      yield assertTrue(
        events.size == 1,
        events.headOption.exists(_.isDone),
        events.lift(0).exists(_.data == "[DONE]")
      )
    },
    test("Parses event name and id fields") {
      val rawLines = Chunk(
        "event: custom_event",
        "id: 42",
        "data: payload body",
        ""
      )

      for events <- ZStream.fromChunk(rawLines).via(SseParser.parseLines).runCollect
      yield assertTrue(
        events.size == 1,
        events.headOption.exists(_.event.contains("custom_event")),
        events.headOption.exists(_.id.contains("42")),
        events.lift(0).exists(_.data == "payload body")
      )
    }
  )
