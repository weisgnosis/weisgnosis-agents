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

import com.weisgnosis.agents.core.event.{AgentEvent, ModelStreamContext}
import com.weisgnosis.agents.core.model.Msg
import zio.*
import zio.test.*

/**
 * Verifies LlmClient streaming interface with mock client and sequential turns.
 */
object LlmClientSpec extends ZIOSpecDefault:

  def spec: Spec[TestEnvironment, Any] = suite("LlmClientSpec")(
    test("factory construction effectful mock defers callback construction defects"):
      ZIO.scoped:
        val client = LlmClient.mockEffectful: (history, _, _) =>
          val quotient = 1 / history.size
          ZIO.succeed(TurnOutput.FinalText(Msg.assistant(quotient.toString)))
        for
          hub <- ZIO.acquireRelease(Hub.unbounded[AgentEvent])(_.shutdown)
          constructed <- ZIO
            .attempt(
              client.streamTurn(
                Chunk.empty,
                Chunk.empty,
                LlmConfig("fixture"),
                hub,
                ModelStreamContext("reply", "request")
              )
            )
            .either
          result <- constructed match
            case Left(_) =>
              ZIO.succeed(assertTrue(false).label("Callback escaped effect construction"))
            case Right(effect) =>
              effect.exit.map(exit =>
                assertTrue(
                  exit.causeOption.exists(_.dieOption.exists(_.isInstanceOf[ArithmeticException]))
                )
              )
        yield result
    ,
    test("Mock LlmClient dispatches configured turn outputs in order") {
      val resp1 = TurnOutput.FinalText(Msg.assistant("First response"))
      val resp2 = TurnOutput.FinalText(Msg.assistant("Second response"))

      ZIO.scoped {
        for
          client <- LlmClient.mock(Chunk(resp1, resp2))
          hub    <- Hub.bounded[AgentEvent](16)
          config = LlmConfig(model = "test-model")
          out1 <- client.streamTurn(
            Chunk.empty,
            Chunk.empty,
            config,
            hub,
            ModelStreamContext("reply", "request")
          )
          out2 <- client.streamTurn(
            Chunk.empty,
            Chunk.empty,
            config,
            hub,
            ModelStreamContext("reply", "request")
          )
          out3 <- client.streamTurn(
            Chunk.empty,
            Chunk.empty,
            config,
            hub,
            ModelStreamContext("reply", "request")
          )
        yield assertTrue(
          out1 == resp1,
          out2 == resp2,
          out3.message.text.contains("Mock: No more responses")
        )
      }
    }
  )
