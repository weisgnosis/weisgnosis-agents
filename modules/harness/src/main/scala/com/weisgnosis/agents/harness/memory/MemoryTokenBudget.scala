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

import com.weisgnosis.agents.core.error.AgentError
import zio.json.*
import zio.json.ast.Json

opaque type MemoryTokenBudget = Int

object MemoryTokenBudget:
  val Maximum: Int               = 4000
  val Default: MemoryTokenBudget = Maximum

  def fromInt(value: Int): Either[AgentError.InvalidConfiguration, MemoryTokenBudget] =
    if value > 0 && value <= Maximum then Right(value)
    else
      Left(
        AgentError.InvalidConfiguration("Curated memory budget must be between 1 and 4000 tokens")
      )

  given JsonCodec[MemoryTokenBudget] = summon[JsonCodec[Json]].transformOrFail(
    {
      case Json.Num(value) =>
        val number = BigDecimal(value)
        if number.isWhole && number > 0 && number <= Maximum then Right(number.toInt)
        else Left("Curated memory budget must be an integer between 1 and 4000")
      case _ => Left("Curated memory budget must be a JSON number")
    },
    value => Json.Num(value)
  )

  extension (budget: MemoryTokenBudget)
    def value: Int                 = budget
    def approximateCharacters: Int = budget * 4
