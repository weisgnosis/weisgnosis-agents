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

import com.weisgnosis.agents.core.error.AgentError
import zio.json.*
import zio.json.ast.Json

/** Positive provider preference for explicitly compatible endpoints, not a local execution limit. */
opaque type ThinkingBudget = Int

object ThinkingBudget:
  def fromInt(tokens: Int): Either[AgentError.InvalidConfiguration, ThinkingBudget] =
    Either.cond(
      tokens > 0,
      tokens,
      AgentError.InvalidConfiguration("Thinking budget must be a positive integer")
    )

  extension (budget: ThinkingBudget) def tokens: Int = budget

  /** Wire validation is required to preserve the checked positive-value boundary. */
  given JsonCodec[ThinkingBudget] = JsonCodec[Json].transformOrFail(
    {
      case number: Json.Num => number.as[Int].flatMap(tokens => fromInt(tokens).left.map(_.message))
      case _                => Left("Thinking budget must be a JSON integer")
    },
    budget => Json.Num(budget)
  )
