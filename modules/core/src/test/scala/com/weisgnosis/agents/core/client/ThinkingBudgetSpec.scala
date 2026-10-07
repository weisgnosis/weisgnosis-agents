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

import zio.Chunk
import zio.json.*
import zio.test.*

object ThinkingBudgetSpec extends ZIOSpecDefault:
  def spec: Spec[TestEnvironment, Any] = suite("ThinkingBudgetSpec")(
    test(
      "positive exact token preferences round-trip and invalid wire values cannot construct a budget"
    ) {
      assertTrue(
        Chunk(1, 2048, 4096, Int.MaxValue).forall(tokens =>
          ThinkingBudget
            .fromInt(tokens)
            .exists(budget =>
              budget.tokens == tokens && budget.toJson.fromJson[ThinkingBudget] == Right(budget)
            )
        ),
        Chunk(Int.MinValue, -1, 0).forall(ThinkingBudget.fromInt(_).isLeft),
        Chunk("null", "true", "\"2048\"", "1.5", "2147483648", "0", "-1", "{}", "[]")
          .forall(_.fromJson[ThinkingBudget].isLeft)
      )
    }
  )
