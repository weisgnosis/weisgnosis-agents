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

import com.weisgnosis.agents.core.client.{LlmClient, TurnOutput, StreamAccumulator, LlmChunk}
import com.weisgnosis.agents.core.model.*
import zio.json.ast.Json
import zio.*
import zio.test.*

/**
 * Selected upstream scenario tests for Thinking-only empty response guard and synthetic reminder retry loopback (INV-3).
 *
 * Corresponds to `io.agentscope.core.agent.ReActAgentEmptyResponseRetryTest` in AgentScope Java.
 */
object ReActAgentThinkingRetrySpec extends ZIOSpecDefault:

  def spec: Spec[TestEnvironment & Scope, Any] =
    suite("ReActAgentThinkingRetrySpec (Selected upstream scenarios)")(
      test("Thinking-only final response loops back and the retry carries a reminder") {
        val mockClient = LlmClient.mockEffectful { (history, _, _) =>
          val hasReminder =
            history.lastOption.exists(m =>
              m.isSynthetic && m.reminderKind.contains("empty_response")
            )
          if !hasReminder then
            // First turn: thinking only
            val thinkingMsg = Msg.assistant(
              content = Chunk(ContentBlock.Thinking("Let me ponder silently...")),
              name = None
            )
            ZIO.succeed(TurnOutput.ThinkingOnly(thinkingMsg))
          else
            // Second turn: visible text response
            ZIO.succeed(TurnOutput.FinalText(Msg.assistant("Here is the visible final answer.")))
        }

        for
          agent <- ReActAgent
            .builder()
            .name("test-asst")
            .model(mockClient)
            .maxIters(10)
            .build
          result  <- agent.run(Msg.user("hello"))
          history <- agent.history
          reminderOpt = history.find(m =>
            m.isSynthetic && m.reminderKind.contains("empty_response")
          )
        yield assertTrue(
          result.reply.text == "Here is the visible final answer.",
          result.iterationCount == 2,
          history.count(_.reminderKind.contains("empty_response")) == 1,
          reminderOpt.isDefined,
          reminderOpt.exists(_.role == Role.User),
          reminderOpt.exists(_.name.contains("system")),
          reminderOpt.exists(_.text.startsWith("<system-reminder>")),
          reminderOpt.exists(_.text.contains("Your previous reply had empty content"))
        )
      },
      test("blank text with reasoning triggers the reminder") {
        val assembled = StreamAccumulator.empty
          .feed(LlmChunk.ThinkingDelta("reasoning"))
          .flatMap(_.feed(LlmChunk.TextDelta("   ")))
          .flatMap(_.feed(LlmChunk.FinishReason("stop")))
          .flatMap(_.toTurnOutput)
        for
          output <- ZIO.fromEither(assembled)
          client <- LlmClient.mock(Chunk(output, TurnOutput.FinalText(Msg.assistant("answer"))))
          agent  <- ReActAgent.make(client = client)
          result <- agent.run(Msg.user("question"))
        yield assertTrue(
          result.iterationCount == 2,
          result.history.count(_.reminderKind.contains("empty_response")) == 1
        )
      },
      test("thinking with tool calls is an acting turn rather than an empty response") {
        val assembled = StreamAccumulator.empty
          .feed(LlmChunk.ThinkingDelta("reasoning"))
          .flatMap(_.feed(LlmChunk.ToolCallChunk(0, Some("call"), Some("missing"), "{}")))
          .flatMap(_.feed(LlmChunk.FinishReason("tool_calls")))
          .flatMap(_.toTurnOutput)
        for
          output <- ZIO.fromEither(assembled)
          client <- LlmClient.mock(Chunk(output, TurnOutput.FinalText(Msg.assistant("answer"))))
          agent  <- ReActAgent.make(client = client)
          result <- agent.run(Msg.user("question"))
        yield assertTrue(
          !result.history.exists(_.isSynthetic),
          result.history.flatMap(_.toolUses).exists(_.input == Json.Obj()),
          result.history.flatMap(_.toolResults).size == 1
        )
      },
      test("visible responses never consume thinking retries") {
        for
          agent <- ReActAgent.make(client =
            LlmClient.mockFunction((_, _) => TurnOutput.FinalText(Msg.assistant("answer")))
          )
          result <- agent.run(Msg.user("question"))
        yield assertTrue(result.iterationCount == 1, !result.history.exists(_.isSynthetic))
      },
      test("A model that keeps returning thinking-only stops at retry limit") {
        val thinkingMsg = Msg.assistant(
          content = Chunk(ContentBlock.Thinking("Thinking forever...")),
          name = None
        )

        val policies = Chunk(
          (0, 5, 1, "empty_response"),
          (1, 5, 2, "empty_response"),
          (2, 5, 3, "empty_response"),
          (5, 2, 2, "max_iterations")
        )
        ZIO
          .foreach(policies): (retries, iterations, expectedCalls, reason) =>
            for
              calls <- Ref.make(0)
              client = LlmClient.mockEffectful((_, _, _) =>
                calls.update(_ + 1).as(TurnOutput.ThinkingOnly(thinkingMsg))
              )
              agent <- ReActAgent.make(
                client = client,
                config = AgentConfig(maxIterations = iterations, emptyThinkingRetryLimit = retries)
              )
              result <- agent.run(Msg.user("hello"))
              count  <- calls.get
            yield assertTrue(
              count == expectedCalls,
              result.iterationCount == expectedCalls,
              result.reply.generateReason.contains(reason),
              result.history.count(
                _.reminderKind.contains("empty_response")
              ) == (if reason == "empty_response" then retries else expectedCalls)
            )
          .map(_.foldLeft(assertTrue(true))(_ && _))
      }
    )
