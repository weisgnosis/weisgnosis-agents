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

import zio.*
import zio.json.*
import zio.test.*

object MemoryTokenBudgetSpec extends ZIOSpecDefault:
  def spec: Spec[TestEnvironment & Scope, Any] = suite("MemoryTokenBudgetSpec")(
    test("bounds and character guidance share one validated authority") {
      assertTrue(
        MemoryTokenBudget.Default.value == 4000,
        MemoryTokenBudget.Default.approximateCharacters == 16000,
        MemoryTokenBudget.fromInt(1).map(_.approximateCharacters) == Right(4),
        List(Int.MinValue, -1, 0, 4001, Int.MaxValue).forall(MemoryTokenBudget.fromInt(_).isLeft)
      )
    },
    test("wire budgets reject coercion fractions overflow and out-of-range numbers") {
      assertTrue(
        List("\"12\"", "1.5", "false", "[]", "{}", "2147483648", "0", "-1", "4001")
          .forall(_.fromJson[MemoryTokenBudget].isLeft),
        "4000".fromJson[MemoryTokenBudget] == Right(MemoryTokenBudget.Default),
        MemoryTokenBudget.Default.toJson == "4000"
      )
    },
    test("every supported budget round-trips without overflow in character guidance") {
      assertTrue(
        (1 to 4000).forall(value =>
          MemoryTokenBudget
            .fromInt(value)
            .exists(budget =>
              budget.toJson.fromJson[MemoryTokenBudget] == Right(
                budget
              ) && budget.approximateCharacters == value * 4
            )
        )
      )
    }
  )
