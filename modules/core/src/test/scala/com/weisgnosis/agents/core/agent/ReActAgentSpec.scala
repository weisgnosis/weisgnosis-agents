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
import com.weisgnosis.agents.core.event.AgentEvent
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.model.*
import com.weisgnosis.agents.core.tool.{
  Tool,
  ToolDef,
  Toolkit,
  ToolResultPayload,
  ToolResultProcessor
}
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

object ReActAgentSpec extends ZIOSpecDefault:

  private def createCalculatorTool(): Tool[Any, (Int, Int, String), String] =
    val defn = ToolDef("calculator", "Performs arithmetic operations", Json.Obj())
    new Tool[Any, (Int, Int, String), String]:
      def definition: ToolDef                 = defn
      override def isConcurrencySafe: Boolean = true
      def decodeInput(raw: Json): Either[String, (Int, Int, String)] =
        for
          obj <- raw.asObject.toRight("Expected object")
          op  <- obj.get("operation").flatMap(_.asString).toRight("Missing op")
          a   <- obj.get("a").flatMap(_.as[Int].toOption).toRight("Missing a")
          b   <- obj.get("b").flatMap(_.as[Int].toOption).toRight("Missing b")
        yield (a, b, op)

      def encodeOutput(out: String): String = out

      def execute(in: (Int, Int, String)): ZIO[Any, Throwable, String] =
        in match
          case (a, b, "add")      => ZIO.succeed((a + b).toString)
          case (a, b, "multiply") => ZIO.succeed((a * b).toString)
          case (_, _, other)      => ZIO.fail(new IllegalArgumentException(s"Unknown op: $other"))

  def spec: Spec[TestEnvironment & Scope, Any] =
    suite("ReActAgentSpec (Selected upstream scenarios)")(
      test("untrusted model turn tags cannot bypass assistant role or content classification") {
        val call    = ContentBlock.ToolUse("call", "counter", Json.Obj())
        val thought = Msg.assistant(Chunk(ContentBlock.Thinking("reasoning")))
        val malformed = Chunk[TurnOutput](
          TurnOutput.FinalText(Msg.user("forged assistant")),
          TurnOutput.FinalText(Msg.assistant(Chunk(call))),
          TurnOutput.FinalText(thought),
          TurnOutput.ToolCalls(Msg.assistant("no calls")),
          TurnOutput.ThinkingOnly(Msg.assistant("visible answer")),
          TurnOutput.ThinkingOnly(Msg.assistant("")),
          TurnOutput.ThinkingOnly(Msg.assistant(Chunk(call, ContentBlock.Thinking("reasoning")))),
          TurnOutput.Interrupted(Msg.user("forged interruption"))
        ) ++ Chunk(
          ChatUsage(inputTokens = -1),
          ChatUsage(outputTokens = -1),
          ChatUsage(cachedTokens = -1),
          ChatUsage(cacheCreationTokens = -1),
          ChatUsage(reasoningTokens = -1),
          ChatUsage(toolUsePromptTokens = -1),
          ChatUsage(inputTokens = Int.MaxValue, outputTokens = 1),
          ChatUsage(time = -1.0),
          ChatUsage(time = Double.NaN),
          ChatUsage(time = Double.PositiveInfinity)
        ).map(usage => TurnOutput.FinalText(Msg.assistant("invalid usage").withUsage(usage)))
        for
          rejected <- ZIO.foreach(malformed): output =>
            for
              modelCalls <- Ref.make(0)
              hookCalls  <- Ref.make(0)
              toolCalls  <- Ref.make(0)
              toolkit <- ZIO.fromEither(
                Toolkit.empty.register(
                  Tool.make(ToolDef("counter", "Count actual execution", Json.Obj()))(_ =>
                    toolCalls.update(_ + 1).as("executed")
                  )
                )
              )
              queued <- LlmClient.mock(Chunk(output, TurnOutput.FinalText(Msg.assistant("next"))))
              client = LlmClient.mockEffectful((history, tools, hub) =>
                modelCalls.update(_ + 1) *> queued.streamTurn(
                  history,
                  tools,
                  com.weisgnosis.agents.core.client.LlmConfig("test"),
                  hub,
                  com.weisgnosis.agents.core.event.ModelStreamContext("test", "test")
                )
              )
              agent <- ReActAgent
                .builder()
                .model(client)
                .toolkit(toolkit)
                .maxIters(2)
                .postReasoningHook(
                  PostReasoningHook((_, _) => hookCalls.update(_ + 1).as(ReasoningDecision.Keep))
                )
                .build
              input = Msg.user("start")
              outcome <- agent.run(input).either
              history <- agent.history
              models  <- modelCalls.get
              hooks   <- hookCalls.get
              tools   <- toolCalls.get
            yield assertTrue(
              outcome.left.exists(_.isInstanceOf[AgentError.ModelError]),
              history == Chunk(input),
              models == 1,
              hooks == 0,
              tools == 0
            )
          accepted <- ZIO.foreach(
            Chunk(
              Msg.assistant(""),
              Msg.assistant("visible"),
              Msg.assistant("boundary").withUsage(ChatUsage(inputTokens = Int.MaxValue)),
              Msg
                .assistant("boundary")
                .withUsage(ChatUsage(cachedTokens = Int.MaxValue, time = Double.MaxValue))
            )
          ): message =>
            for
              client <- LlmClient.mock(Chunk(TurnOutput.FinalText(message)))
              agent  <- ReActAgent.builder().model(client).build
              result <- agent.run(Msg.user("start"))
            yield assertTrue(result.reply.text == message.text, result.usage == message.usage)
        yield (rejected ++ accepted).foldLeft(assertTrue(true))(_ && _)
      },
      test("accumulated usage is checked before the next completion becomes runtime truth") {
        val pairs = Chunk(
          ChatUsage(inputTokens = Int.MaxValue)         -> ChatUsage(inputTokens = 1),
          ChatUsage(outputTokens = Int.MaxValue)        -> ChatUsage(outputTokens = 1),
          ChatUsage(cachedTokens = Int.MaxValue)        -> ChatUsage(cachedTokens = 1),
          ChatUsage(cacheCreationTokens = Int.MaxValue) -> ChatUsage(cacheCreationTokens = 1),
          ChatUsage(reasoningTokens = Int.MaxValue)     -> ChatUsage(reasoningTokens = 1),
          ChatUsage(toolUsePromptTokens = Int.MaxValue) -> ChatUsage(toolUsePromptTokens = 1),
          ChatUsage(inputTokens = Int.MaxValue)         -> ChatUsage(outputTokens = 1),
          ChatUsage(time = Double.MaxValue)             -> ChatUsage(time = Double.MaxValue)
        )
        ZIO
          .foreach(pairs): (firstUsage, secondUsage) =>
            for
              hooks    <- Ref.make(0)
              observed <- Ref.make(Chunk.empty[AgentEvent])
              first = Msg.assistant(Chunk(ContentBlock.Thinking("reasoning"))).withUsage(firstUsage)
              second = Msg.assistant("must not persist").withUsage(secondUsage)
              model <- LlmClient.mock(
                Chunk(TurnOutput.ThinkingOnly(first), TurnOutput.FinalText(second))
              )
              agent <- ReActAgent
                .builder()
                .model(model)
                .maxIters(2)
                .postReasoningHook(
                  PostReasoningHook((_, _) => hooks.update(_ + 1).as(ReasoningDecision.Keep))
                )
                .build
              outcome <- agent
                .streamEvents(Msg.user("start"))
                .tap(event => observed.update(_ :+ event))
                .runDrain
                .either
              history   <- agent.history
              events    <- observed.get
              hookCount <- hooks.get
            yield assertTrue(
              outcome.left.exists(_.isInstanceOf[AgentError.ModelError]),
              !history.exists(_.text == second.text),
              hookCount == 1,
              events.count(_.isInstanceOf[AgentEvent.ModelResponseEndEvent]) == 1
            )
          .map(_.foldLeft(assertTrue(true))(_ && _))
      },
      test("Should initialize ReActAgent with correct properties") {
        for
          agent <- ReActAgent
            .builder()
            .name("test-react-agent")
            .model(LlmClient.mockFunction((_, _) => TurnOutput.FinalText(Msg.assistant("ready"))))
            .sysPrompt("You are a helpful assistant")
            .maxIters(10)
            .build
          history <- agent.history
        yield assertTrue(
          agent.name == "test-react-agent",
          agent.maxIterations == 10,
          agent.getMaxIters == 10,
          agent.getAgentId.nonEmpty,
          history.isEmpty
        )
      },
      test("Should generate simple text response") {
        val mockClient = LlmClient.mockFunction { (_, _) =>
          TurnOutput.FinalText(Msg.assistant("Hello, how can I assist you?"))
        }
        for
          agent <- ReActAgent
            .builder()
            .name("test-agent")
            .model(mockClient)
            .build
          userMsg = Msg.user("Hi there")
          reply   <- agent.call(userMsg)
          history <- agent.history
        yield assertTrue(
          reply.role == Role.Assistant,
          reply.text == "Hello, how can I assist you?",
          history.size == 2,
          history.headOption.exists(_.text == "Hi there"),
          history.lastOption.exists(_.text == "Hello, how can I assist you?")
        )
      },
      test("Should handle thinking and final response") {
        val mockClient = LlmClient.mockFunction { (_, _) =>
          val thinkingBlock = ContentBlock.Thinking("Let me think about this step by step.")
          val textBlock     = ContentBlock.Text("Here is the answer.")
          TurnOutput.FinalText(
            Msg.assistant(content = Chunk(thinkingBlock, textBlock), name = None)
          )
        }
        for
          agent <- ReActAgent.builder().model(mockClient).build
          reply <- agent.call(Msg.user("What is 2+2?"))
        yield assertTrue(
          reply.thinking.contains("Let me think about this step by step"),
          reply.text == "Here is the answer."
        )
      },
      test("Should call tools when requested by model") {
        val calcTool = createCalculatorTool()
        val toolkit  = Toolkit.single(calcTool)

        val mockClient = LlmClient.mockEffectful { (history, _, _) =>
          val hasToolResult = history.exists(_.role == Role.Tool)
          if !hasToolResult then
            val call = ContentBlock.ToolUse(
              id = "call_calc_1",
              name = "calculator",
              input = Json.Obj(
                Chunk(
                  "operation" -> Json.Str("add"),
                  "a"         -> Json.Num(5),
                  "b"         -> Json.Num(7)
                )
              )
            )
            ZIO.succeed(TurnOutput.ToolCalls(Msg.assistant(content = Chunk(call), name = None)))
          else ZIO.succeed(TurnOutput.FinalText(Msg.assistant("The result of 5 + 7 is 12.")))
        }

        for
          agent <- ReActAgent
            .builder()
            .name("calc-agent")
            .toolkit(toolkit)
            .model(mockClient)
            .build
          reply   <- agent.call(Msg.user("Add 5 and 7"))
          history <- agent.history
          toolMsgOpt = history.find(_.role == Role.Tool)
        yield assertTrue(
          reply.text == "The result of 5 + 7 is 12.",
          toolMsgOpt.isDefined,
          toolMsgOpt.exists(_.toolResults.exists(_.text == "12")),
          toolMsgOpt.exists(_.toolResults.exists(_.id == "call_calc_1"))
        )
      },
      test("Should execute multiple tools in acting phase") {
        val calcTool = createCalculatorTool()
        val toolkit  = Toolkit.single(calcTool)

        val mockClient = LlmClient.mockEffectful { (history, _, _) =>
          val toolResults = history.flatMap(_.toolResults)
          if toolResults.isEmpty then
            val call1 = ContentBlock.ToolUse(
              id = "call_1",
              name = "calculator",
              input = Json.Obj(
                Chunk("operation" -> Json.Str("add"), "a" -> Json.Num(1), "b" -> Json.Num(2))
              )
            )
            val call2 = ContentBlock.ToolUse(
              id = "call_2",
              name = "calculator",
              input = Json.Obj(
                Chunk("operation" -> Json.Str("multiply"), "a" -> Json.Num(3), "b" -> Json.Num(4))
              )
            )
            ZIO.succeed(
              TurnOutput.ToolCalls(Msg.assistant(content = Chunk(call1, call2), name = None))
            )
          else ZIO.succeed(TurnOutput.FinalText(Msg.assistant("Computed both successfully.")))
        }

        for
          agent <- ReActAgent
            .builder()
            .toolkit(toolkit)
            .model(mockClient)
            .build
          reply   <- agent.call(Msg.user("Compute two math problems"))
          history <- agent.history
          results = history.flatMap(_.toolResults)
        yield assertTrue(
          reply.text == "Computed both successfully.",
          results.size == 2,
          results.exists(r => r.id == "call_1" && r.outputText == "3"),
          results.exists(r => r.id == "call_2" && r.outputText == "12")
        )
      },
      test("Should handle tool execution errors in acting phase") {
        val errorTool = new Tool[Any, Json, String]:
          def definition: ToolDef = ToolDef("failing_tool", "Always fails", Json.Obj())
          def decodeInput(raw: Json): Either[String, Json] = Right(raw)
          def encodeOutput(out: String): String            = out
          def execute(input: Json): ZIO[Any, Throwable, String] =
            ZIO.fail(new RuntimeException("Simulated hardware failure"))

        val toolkit = Toolkit.single(errorTool)

        val mockClient = LlmClient.mockEffectful { (history, _, _) =>
          val hasToolResult = history.exists(_.role == Role.Tool)
          if !hasToolResult then
            val call = ContentBlock.ToolUse("call_err", "failing_tool", Json.Obj())
            ZIO.succeed(TurnOutput.ToolCalls(Msg.assistant(content = Chunk(call), name = None)))
          else
            val toolResult = history.flatMap(_.toolResults).headOption
            ZIO.succeed(
              TurnOutput.FinalText(Msg.assistant(s"Handled failure: ${toolResult.map(_.state)}"))
            )
        }

        for
          agent   <- ReActAgent.builder().toolkit(toolkit).model(mockClient).build
          reply   <- agent.call(Msg.user("Execute failing tool"))
          history <- agent.history
          toolMsg = history.find(_.role == Role.Tool)
        yield assertTrue(
          toolMsg.exists(_.toolResults.exists(_.state == ToolResultState.Error)),
          reply.text.contains("Error")
        )
      },
      test("Should handle max iterations limit") {
        // Model keeps returning tool calls in an infinite loop
        val loopClient = LlmClient.mockFunction { (_, _) =>
          val call = ContentBlock.ToolUse("infinite_call", "non_existent_tool", Json.Obj())
          TurnOutput.ToolCalls(Msg.assistant(content = Chunk(call), name = None))
        }

        for
          agent <- ReActAgent
            .builder()
            .model(loopClient)
            .maxIters(3)
            .build
          result <- agent.run(Msg.user("Loop infinitely"))
        yield assertTrue(
          result.iterationCount == 3,
          result.reply.generateReason.contains("max_iterations"),
          result.reply.text.contains("Maximum iterations (3) reached")
        )
      },
      test(
        "Should finish without executing server tool when result is inline (AgentScope ServerTool Parity)"
      ) {
        val serverUse = ContentBlock.ToolUse(
          id = "srv_01",
          name = "web_search",
          input = Json.Obj(Chunk("query" -> Json.Str("AgentScope"))),
          metadata = Map("agentscope_server_tool" -> Json.Bool(true))
        )
        val serverRes = ContentBlock.ToolResult(
          id = "srv_01",
          name = Some("web_search"),
          output = Chunk(ContentBlock.Text("AgentScope docs (https://example.com)")),
          state = ToolResultState.Success,
          metadata = Map("agentscope_server_tool" -> Json.Bool(true))
        )
        val finalText = ContentBlock.Text("Based on the search results...")

        val mockClient = LlmClient.mockFunction { (_, _) =>
          TurnOutput.ToolCalls(
            Msg.assistant(content = Chunk(serverUse, serverRes, finalText), name = None)
          )
        }

        for
          agent <- ReActAgent
            .builder()
            .model(mockClient)
            .maxIters(3)
            .build
          reply   <- agent.call(Msg.user("Search for AgentScope"))
          history <- agent.history
          hasLocalToolMsg = history.exists(_.role == Role.Tool)
        yield assertTrue(
          reply.role == Role.Assistant,
          reply.toolUses.size == 1,
          reply.toolUses.exists(_.isServerTool),
          reply.toolResults.size == 1,
          reply.toolResults.exists(_.isServerTool),
          !hasLocalToolMsg // Server tool must not produce local tool execution messages
        )
      },
      test("canonical provider tool markers preserve inline results without local dispatch") {
        val marker = Map("serverTool" -> Json.Bool(true))
        val use    = ContentBlock.ToolUse("server", "web_search", Json.Obj(), metadata = marker)
        val result =
          ContentBlock.ToolResult.text("server", "web_search", "inline", metadata = marker)
        val reply  = Msg.assistant(Chunk(use, result, ContentBlock.Text("done")))
        val client = LlmClient.mockFunction((_, _) => TurnOutput.ToolCalls(reply))
        for
          agent     <- ReActAgent.builder().model(client).maxIters(2).build
          completed <- agent.run(Msg.user("search"))
          history   <- agent.history
        yield assertTrue(
          completed.iterationCount == 1,
          completed.reply == reply,
          !history.exists(_.role == Role.Tool),
          completed.reply.toolUses.forall(_.isServerTool),
          completed.reply.toolResults.forall(_.isServerTool)
        )
      },
      test("only terminal inline provider observations complete a turn or reach result processing") {
        ZIO
          .foreach(Chunk(ToolResultState.Running, ToolResultState.Success, ToolResultState.Error)):
            state =>
              val marker = Map("serverTool" -> Json.Bool(true))
              val call =
                ContentBlock.ToolUse("provider", "calculator", Json.Obj(), metadata = marker)
              val observation = ContentBlock.ToolResult.text(
                "provider",
                "calculator",
                "preview",
                state = state,
                metadata = marker
              )
              val first = Msg.assistant(Chunk(call, observation, ContentBlock.Text("preview")))
              val terminal = Msg.assistant(
                Chunk(
                  call,
                  observation.copy(state = ToolResultState.Success),
                  ContentBlock.Text("finished")
                )
              )
              for
                requests  <- Ref.make(Chunk.empty[Chunk[Msg]])
                processed <- Ref.make(Chunk.empty[ToolResultState])
                client = LlmClient.mockEffectful((history, _, _) =>
                  requests.modify(previous =>
                    (
                      TurnOutput.ToolCalls(if previous.isEmpty then first else terminal),
                      previous :+ history
                    )
                  )
                )
                agent <- ReActAgent.make(
                  client = client,
                  toolkit = Toolkit.single(createCalculatorTool()),
                  toolResultProcessor = ToolResultProcessor(result =>
                    processed.update(_ :+ result.state).as(ToolResultPayload.fromResult(result))
                  )
                )
                result       <- agent.run(Msg.user("search"))
                seen         <- requests.get
                observations <- processed.get
                expected         = if state == ToolResultState.Running then terminal else first
                expectedRequests = if state == ToolResultState.Running then 2 else 1
              yield assertTrue(
                result.state == AgentRunState.Completed,
                result.reply == expected,
                result.iterationCount == expectedRequests,
                seen.size == expectedRequests,
                seen.drop(1).forall(_.contains(first)),
                observations == Chunk(
                  if state == ToolResultState.Running then ToolResultState.Success else state
                ),
                !result.history.exists(_.role == Role.Tool)
              )
          .map(results => results.reduceOption(_ && _).getOrElse(assertTrue(false)))
      },
      test("Should emit events while streaming turn") {
        val mockClient = LlmClient.mockFunction { (_, _) =>
          TurnOutput.FinalText(Msg.assistant("Streamed reply"))
        }
        for
          agent  <- ReActAgent.builder().model(mockClient).build
          events <- agent.streamEvents(Msg.user("Hello")).runCollect
        yield assertTrue(
          events.exists(_.isInstanceOf[AgentEvent.AgentStartEvent]),
          events.exists(_.isInstanceOf[AgentEvent.AgentEndEvent])
        )
      }
    )
