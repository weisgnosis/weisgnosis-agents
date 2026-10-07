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
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

object StopSequencesSpec extends ZIOSpecDefault:
  def spec: Spec[TestEnvironment & Scope, Any] = suite("StopSequencesSpec")(
    test("checks nonempty string arrays and preserves exact provider values"):
      val cases = Chunk(
        Chunk("END"),
        Chunk("END", "STOP"),
        Chunk("", " ", "\n", "END", "END"),
        Chunk("1", "2", "3", "4", "5")
      )
      assertTrue(
        StopSequences.one("END", "STOP").values == Chunk("END", "STOP"),
        StopSequences.one("").values == Chunk(""),
        StopSequences.fromChunk(Chunk.empty) == Left(
          AgentError.InvalidConfiguration("Stop sequences must contain at least one value")
        ),
        cases.forall(values =>
          StopSequences
            .fromChunk(values)
            .exists(sequences =>
              sequences.values == values &&
                sequences.toJson.fromJson[StopSequences] == Right(sequences) &&
                sequences.toJson.fromJson[Json] == Right(Json.Arr(values.map(Json.Str(_))))
            )
        ),
        Chunk("[]", "[1]", "[true]", "[null]", "[\"END\",{}]", "null", "{}", "\"END\"", "[")
          .forall(_.fromJson[StopSequences].isLeft)
      )
  )
