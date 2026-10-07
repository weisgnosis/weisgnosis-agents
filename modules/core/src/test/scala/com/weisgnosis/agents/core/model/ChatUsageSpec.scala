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
package com.weisgnosis.agents.core.model

import zio.test.*

object ChatUsageSpec extends ZIOSpecDefault:
  def spec: Spec[TestEnvironment, Any] = suite("ChatUsageSpec")(
    test("usage combines every metric across model turns") {
      val first = ChatUsage(1, 2, 3, 4, 5, 6, 0.25)
      assertTrue(
        first.combine(first) == Right(ChatUsage(2, 4, 6, 8, 10, 12, 0.5)),
        first.combine(ChatUsage()) == Right(first),
        ChatUsage(inputTokens = Int.MaxValue).totalTokens == Right(Int.MaxValue),
        ChatUsage(inputTokens = Int.MaxValue).combine(ChatUsage()) == Right(
          ChatUsage(inputTokens = Int.MaxValue)
        ),
        ChatUsage(inputTokens = Int.MaxValue, outputTokens = 1).totalTokens.isLeft,
        first.combine(ChatUsage(cachedTokens = -1)).isLeft,
        ChatUsage(time = Double.MaxValue).combine(ChatUsage(time = Double.MaxValue)).isLeft
      )
    }
  )
