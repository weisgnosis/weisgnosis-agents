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

import com.weisgnosis.agents.core.client.{LlmClient, TurnOutput}
import com.weisgnosis.agents.core.model.Msg
import zio.*
import zio.json.*
import zio.test.*

object AgentConfigSpec extends ZIOSpecDefault:
  def spec: Spec[TestEnvironment, Any] = suite("AgentConfigSpec")(
    test("execution policy and durations round-trip") {
      val config = AgentConfig(timeout = 7.seconds, toolExecution = ToolExecution.Sequential)
      assertTrue(
        config.toJson.fromJson[AgentConfig] == Right(config),
        config.validated == Right(config)
      )
    },
    test("invalid iteration, retry, and timeout bounds are typed failures") {
      assertTrue(
        AgentConfig(maxIterations = 0).validated.isLeft,
        AgentConfig(emptyThinkingRetryLimit = -1).validated.isLeft,
        AgentConfig(timeout = Duration.Zero).validated.isLeft
      )
    },
    test("a missing model client cannot create a production agent") {
      for result <- ReActAgent.builder().build.either
      yield assertTrue(result.isLeft)
    },
    test("builder accepts complete configuration and validates fluent retry overrides") {
      val config = AgentConfig(
        name = "configured",
        description = "Configured agent",
        systemPrompt = "Be concise",
        maxIterations = 3,
        timeout = 7.seconds,
        emptyThinkingRetryLimit = 1,
        toolExecution = ToolExecution.Sequential
      )
      for
        client   <- LlmClient.mock(Chunk(TurnOutput.FinalText(Msg.assistant("done"))))
        original <- ReActAgent.builder().client(client).config(config).build
        refined <- ReActAgent
          .builder()
          .client(client)
          .config(config)
          .emptyThinkingRetryLimit(0)
          .build
        invalid <- ReActAgent.builder().client(client).emptyThinkingRetryLimit(-1).build.either
      yield assertTrue(
        original.config == config,
        refined.config == config.copy(emptyThinkingRetryLimit = 0),
        invalid.isLeft
      )
    }
  )
