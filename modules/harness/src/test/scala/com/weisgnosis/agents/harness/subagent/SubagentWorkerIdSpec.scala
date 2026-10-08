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
package com.weisgnosis.agents.harness.subagent

import zio.*
import zio.json.*
import zio.test.*

object SubagentWorkerIdSpec extends ZIOSpecDefault:
  def spec: Spec[TestEnvironment & Scope, Any] = suite("SubagentWorkerIdSpec")(
    test(
      "host identities preserve exact Unicode through codecs and reject blank, malformed or oversized values"
    ):
      val values  = Chunk("worker", " worker λ ", "<&\"'😀", "é" * 512)
      val invalid = Chunk("", " \t\n", "\uD800", "\uDC00", "é" * 513, "a" * 1025)
      assertTrue(
        values.forall(value =>
          SubagentWorkerId
            .from(value)
            .exists(identity =>
              identity.value == value && identity.toJson
                .fromJson[SubagentWorkerId] == Right(identity)
            )
        ),
        invalid.forall(value =>
          SubagentWorkerId.from(value).isLeft && value.toJson.fromJson[SubagentWorkerId].isLeft
        )
      )
  )
