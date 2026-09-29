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

import com.weisgnosis.agents.core.client.{LlmClient, LlmConfig, TurnOutput}
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.event.{AgentEvent, ModelStreamContext}
import com.weisgnosis.agents.core.model.{ContentBlock, Msg}
import com.weisgnosis.agents.core.tool.ToolDef
import zio.*
import zio.json.ast.Json
import zio.test.*

object MemoryExtractorSpec extends ZIOSpecDefault:
  private val config: LlmConfig = LlmConfig("memory-model", responseFormat = Some(Json.Obj()))
  private val input: Chunk[Msg] = Chunk(Msg.system("extract"), Msg.user("facts"))

  def spec: Spec[TestEnvironment & Scope, Any] = suite("MemoryExtractorSpec")(
    test(
      "client extraction forwards input without tools or response formatting and closes its hub"
    ) {
      for
        observed <- Ref.make(
          Chunk.empty[(Chunk[Msg], Chunk[ToolDef], LlmConfig, ModelStreamContext, Hub[AgentEvent])]
        )
        client = new LlmClient:
          def streamTurn(
              history: Chunk[Msg],
              tools: Chunk[ToolDef],
              settings: LlmConfig,
              hub: Hub[AgentEvent],
              context: ModelStreamContext
          ): ZIO[Scope, AgentError, TurnOutput] =
            observed
              .update(_ :+ (history, tools, settings, context, hub))
              .as(
                TurnOutput.FinalText(
                  Msg
                    .assistant(Chunk(ContentBlock.Thinking("private"), ContentBlock.Text("- fact")))
                )
              )
        extractor = MemoryExtractor.fromClient(client, config)
        first    <- ZIO.scoped(extractor.extract(input))
        second   <- ZIO.scoped(extractor.extract(input))
        requests <- observed.get
        shutdown <- ZIO.foreach(requests)(value => value._5.isShutdown)
      yield assertTrue(
        first == "- fact",
        second == "- fact",
        requests.size == 2,
        requests.forall(value =>
          value._1 == input && value._2.isEmpty && value._3 == config.copy(responseFormat = None)
        ),
        requests.map(_._4.requestId).toSet.size == 2,
        requests.map(_._4.replyId).toSet.size == 2,
        shutdown.forall(identity)
      )
    },
    test("thinking-only output produces no extracted memory") {
      for
        client <- LlmClient.mock(
          Chunk(TurnOutput.ThinkingOnly(Msg.assistant(Chunk(ContentBlock.Thinking("private")))))
        )
        result <- ZIO.scoped(MemoryExtractor.fromClient(client, config).extract(input))
      yield assertTrue(result.isEmpty)
    },
    test("tool calls and disguised tool observations are rejected") {
      val call   = ContentBlock.ToolUse("id", "write", Json.Obj())
      val result = ContentBlock.ToolResult.text("id", "write", "result")
      val outputs = Chunk(
        TurnOutput.ToolCalls(Msg.assistant(Chunk(call))),
        TurnOutput.FinalText(Msg.assistant(Chunk(call))),
        TurnOutput.FinalText(Msg.assistant(Chunk(result)))
      )
      for failures <- ZIO.foreach(outputs): output =>
          for
            client <- LlmClient.mock(Chunk(output))
            result <- ZIO.scoped(MemoryExtractor.fromClient(client, config).extract(input)).either
          yield result.left.exists(_.isInstanceOf[AgentError.InvalidMessage])
      yield assertTrue(failures.forall(identity))
    },
    test("an interrupted model output remains cancellation") {
      for
        client <- LlmClient.mock(Chunk(TurnOutput.Interrupted(Msg.assistant("partial"))))
        exit   <- ZIO.scoped(MemoryExtractor.fromClient(client, config).extract(input)).exit
      yield assertTrue(exit.isInterrupted)
    },
    test("model failure is preserved and scoped client resources are closed") {
      val error = AgentError.ModelError(503, "unavailable")
      for
        closed <- Ref.make(false)
        client = LlmClient.mockEffectful((_, _, _) =>
          ZIO.addFinalizer(closed.set(true)) *> ZIO.fail(error)
        )
        result   <- ZIO.scoped(MemoryExtractor.fromClient(client, config).extract(input)).either
        released <- closed.get
      yield assertTrue(result == Left(error), released)
    }
  )
