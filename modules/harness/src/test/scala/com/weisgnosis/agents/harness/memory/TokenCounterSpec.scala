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
    test("estimates are nonnegative and additive") {
      val first   = Msg.user("hello")
      val second  = Msg.assistant("world")
      val counter = TokenCounter.approximate
      assertTrue(
        counter.count(Chunk.empty[Msg]) == 0L,
        counter.count(first) > 0L,
        counter.count(Chunk(first, second)) == counter.count(first) + counter.count(second)
      )
    }
  )
