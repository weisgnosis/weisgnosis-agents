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
import com.weisgnosis.agents.core.model.*
import com.weisgnosis.agents.core.tool.{Tool, ToolDef, Toolkit}
import zio.*
import zio.json.ast.Json
import zio.test.*

/**
 * Selected upstream scenario tests for ReActAgent interruption and dangling tool reconciliation (INV-2).
 *
 * Corresponds to `io.agentscope.core.agent.ReActAgentInterruptToolCallTest` in AgentScope Java.
 */
object ReActAgentInterruptSpec extends ZIOSpecDefault:

  def spec: Spec[TestEnvironment & Scope, Any] =
    suite("ReActAgentInterruptSpec (Selected upstream scenarios)")(
      test("interruptAfterToolUseSynthesizesErrorResultAndKeepsContextConsistent") {
        val mockClient = LlmClient.mockFunction { (_, _) =>
          val call = ContentBlock.ToolUse(
            id = "tc1",
            name = "search",
            input = Json.Obj(Chunk("query" -> Json.Str("x")))
          )
          TurnOutput.ToolCalls(Msg(role = Role.Assistant, content = Chunk(call)))
        }

        for
          startedPromise <- Promise.make[Nothing, Unit]
          hangingTool = new Tool[Any, Json, String]:
            def definition: ToolDef = ToolDef("search", "Search tool", Json.Obj())
            def decodeInput(raw: Json): Either[String, Json] = Right(raw)
            def encodeOutput(out: String): String            = out
            def execute(input: Json): ZIO[Any, Throwable, String] =
              startedPromise.succeed(()) *> ZIO.never

          toolkit = Toolkit(Map(hangingTool.definition.name -> hangingTool))

          agent <- ReActAgent
            .builder()
            .name("asst")
            .model(mockClient)
            .toolkit(toolkit)
            .build

          fiber   <- agent.run(Msg.user("do something")).forkScoped
          _       <- startedPromise.await
          _       <- agent.interrupt
          result  <- fiber.join
          context <- agent.history
          toolUses      = context.flatMap(_.toolUses)
          toolResults   = context.flatMap(_.toolResults)
          lastAssistant = context.reverse.find(_.role == Role.Assistant)
        yield assertTrue(
          result.isInterrupted,
          result.reply.generateReason.contains("interrupted"),
          toolUses.size == 1,
          toolResults.size == 1,
          toolResults.headOption.exists(_.id == "tc1"),
          toolResults.headOption.exists(_.state == ToolResultState.Error),
          toolResults.headOption.exists(
            _.text.contains("Tool execution was interrupted before it could run")
          ),
          lastAssistant.isDefined,
          lastAssistant.exists(_.toolUses.isEmpty),
          lastAssistant.exists(_.generateReason.contains("interrupted")),
          lastAssistant.exists(
            _.text == "I noticed that you have interrupted me. What can I do for you?"
          )
        )
      },
      test("Fiber cancellation reconciles dangling tool calls via onInterrupt") {
        val mockClient = LlmClient.mockFunction { (_, _) =>
          val call = ContentBlock.ToolUse(
            id = "tc_fiber",
            name = "long_job",
            input = Json.Obj()
          )
          TurnOutput.ToolCalls(Msg(role = Role.Assistant, content = Chunk(call)))
        }

        for
          startedPromise <- Promise.make[Nothing, Unit]
          hangingTool = new Tool[Any, Json, String]:
            def definition: ToolDef = ToolDef("long_job", "Hanging tool", Json.Obj())
            def decodeInput(raw: Json): Either[String, Json] = Right(raw)
            def encodeOutput(out: String): String            = out
            def execute(input: Json): ZIO[Any, Throwable, String] =
              startedPromise.succeed(()) *> ZIO.never

          toolkit = Toolkit(Map(hangingTool.definition.name -> hangingTool))

          agent <- ReActAgent
            .builder()
            .name("asst")
            .model(mockClient)
            .toolkit(toolkit)
            .build

          fiber   <- agent.run(Msg.user("Run long job")).forkScoped
          _       <- startedPromise.await
          _       <- fiber.interrupt // Fiber-level cancellation
          context <- agent.history
          toolUses      = context.flatMap(_.toolUses)
          toolResults   = context.flatMap(_.toolResults)
          lastAssistant = context.reverse.find(_.role == Role.Assistant)
        yield assertTrue(
          toolUses.size == 1,
          toolResults.size == 1,
          toolResults.headOption.exists(_.id == "tc_fiber"),
          toolResults.headOption.exists(_.state == ToolResultState.Error),
          lastAssistant.isDefined,
          lastAssistant.exists(_.generateReason.contains("interrupted"))
        )
      }
    )
