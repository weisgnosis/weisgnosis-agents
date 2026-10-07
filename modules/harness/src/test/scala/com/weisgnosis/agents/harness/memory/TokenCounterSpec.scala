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
package com.weisgnosis.agents.harness.memory

import com.weisgnosis.agents.core.model.Msg
import zio.*
import zio.test.*

object TokenCounterSpec extends ZIOSpecDefault:
  def spec: Spec[TestEnvironment, Any] = suite("TokenCounterSpec")(
    test("estimates add exactly within range and cap conservatively on overflow") {
      val first   = Msg.user("hello")
      val second  = Msg.assistant("world")
      val counter = TokenCounter.approximate
      val custom = new TokenCounter:
        def count(message: Msg): Long = message.text match
          case "huge"   => Long.MaxValue
          case "almost" => Long.MaxValue - 1L
          case _        => 1L
      val cases = Chunk(
        Chunk.empty[Msg]                       -> 0L,
        Chunk(Msg.user("huge"))                -> Long.MaxValue,
        Chunk(Msg.user("huge"), first)         -> Long.MaxValue,
        Chunk(first, Msg.user("huge"), second) -> Long.MaxValue,
        Chunk(Msg.user("almost"))              -> (Long.MaxValue - 1L),
        Chunk(first, Msg.user("almost"))       -> Long.MaxValue,
        Chunk(first, second)                   -> 2L
      )
      assertTrue(
        cases.map((history, _) => custom.count(history)) == cases.map(_._2),
        counter.count(Chunk.empty[Msg]) == 0L,
        counter.count(first) > 0L,
        counter.count(Chunk(first, second)) == counter.count(first) + counter.count(second)
      )
    }
  )
