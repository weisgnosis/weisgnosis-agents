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
package com.weisgnosis.agents.core.agent

import com.weisgnosis.agents.core.model.{ChatUsage, Msg}
import zio.*
import zio.json.*
import zio.test.*

object AgentResultSpec extends ZIOSpecDefault:
  def spec: Spec[TestEnvironment, Any] = suite("AgentResultSpec")(
    test("result serialization preserves interruption, usage, and history") {
      val reply = Msg.assistant("recovered").withGenerateReason("interrupted")
      val result = AgentResult(
        reply,
        Chunk(Msg.user("start"), reply),
        2,
        state = AgentRunState.Interrupted,
        usage = Some(ChatUsage(10, 20))
      )
      val stopped = result.copy(state = AgentRunState.Stopped("host decision"))
      assertTrue(
        result.toJson.fromJson[AgentResult] == Right(result),
        stopped.toJson.fromJson[AgentResult] == Right(stopped)
      )
    }
  )
