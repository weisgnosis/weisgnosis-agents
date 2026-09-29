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
import com.weisgnosis.agents.core.model.*
import com.weisgnosis.agents.core.tool.{Tool, ToolDef, Toolkit}
import zio.*
import zio.json.ast.Json
import zio.test.*

/** Runtime limit correlation and lossless publication of inline provider result observations. */
object ReActAgentEmissionSpec extends ZIOSpecDefault:
  private val serverCall: ContentBlock.ToolUse =
    ContentBlock.ToolUse(
      "server",
      "web_search",
      Json.Obj(),
      metadata = Map("serverTool" -> Json.Bool(true))
    )

  private def resultEvents(events: Chunk[AgentEvent]): Chunk[AgentEvent] =
    events.filter {
      case _: AgentEvent.ToolResultStartEvent     => true
      case _: AgentEvent.ToolResultDeltaEvent     => true
      case _: AgentEvent.ToolResultDataDeltaEvent => true
      case _: AgentEvent.ToolResultEndEvent       => true
      case _                                      => false
    }

  def spec: Spec[TestEnvironment & Scope, Any] = suite("ReActAgentEmissionSpec")(
    test("iteration limit emits one correlated event after the last completed tool turn") {
      val call  = ContentBlock.ToolUse("local", "unregistered", Json.Obj())
      val model = LlmClient.mockFunction((_, _) => TurnOutput.ToolCalls(Msg.assistant(Chunk(call))))
      for
        agent   <- ReActAgent.builder().model(model).maxIters(2).build
        events  <- agent.streamEvents(Msg.user("work")).runCollect
        history <- agent.history
        starts = events.collect { case event: AgentEvent.AgentStartEvent => event.replyId }
        limits = events.collect { case event: AgentEvent.ExceedMaxItersEvent => event }
        ends   = events.collect { case event: AgentEvent.AgentEndEvent => event.replyId }
      yield assertTrue(
        starts.size == 1,
        starts == ends,
        limits.size == 1,
        limits.map(_.replyId) == starts,
        limits.forall(event => event.maxIters == 2 && event.currentIter == 2),
        events.takeRight(3).collect { case event: AgentEvent.ExceedMaxItersEvent =>
          event
        } == limits,
        history.flatMap(_.toolResults).size == 2,
        history.lastOption.exists(_.generationReason == GenerateReason.MaxIterations)
      )
    },
    test("repeated server IDs consume distinct inline result occurrences exactly once") {
      val first  = ContentBlock.ToolResult.text("server", "web_search", "first")
      val second = ContentBlock.ToolResult.text("server", "web_search", "second")
      val reply  = Msg.assistant(Chunk(serverCall, serverCall, first, second))
      val model  = LlmClient.mockFunction((_, _) => TurnOutput.ToolCalls(reply))
      for
        agent   <- ReActAgent.make(client = model)
        events  <- agent.streamEvents(Msg.user("search")).runCollect
        history <- agent.history
      yield assertTrue(
        events.collect { case event: AgentEvent.ToolResultDeltaEvent => event.delta } == Chunk(
          "first",
          "second"
        ),
        events.collect { case event: AgentEvent.ToolResultEndEvent => event.output } == Chunk(
          "first",
          "second"
        ),
        !history.exists(_.role == Role.Tool),
        history.lastOption.contains(reply)
      )
    },
    test("a local call sharing a completed server ID is still executed") {
      val local  = ContentBlock.ToolUse("server", "unregistered", Json.Obj())
      val inline = ContentBlock.ToolResult.text("server", "web_search", "inline")
      for
        model <- LlmClient.mock(
          Chunk(
            TurnOutput.ToolCalls(Msg.assistant(Chunk(serverCall, local, inline))),
            TurnOutput.FinalText(Msg.assistant("done"))
          )
        )
        agent   <- ReActAgent.make(client = model)
        _       <- agent.streamEvents(Msg.user("work")).runDrain
        history <- agent.history
        localResults = history.filter(_.role == Role.Tool).flatMap(_.toolResults)
      yield assertTrue(
        localResults.size == 1,
        localResults.forall(result =>
          result.id == "server" && result.name.contains(
            "unregistered"
          ) && result.state == ToolResultState.Error
        ),
        history.lastOption.exists(_.text == "done")
      )
    },
    test("cancellation reconciles only the unfinished duplicate local occurrence") {
      val first  = ContentBlock.ToolUse("duplicate", "work", Json.Num(1))
      val second = first.copy(input = Json.Num(2))
      val model =
        LlmClient.mockFunction((_, _) => TurnOutput.ToolCalls(Msg.assistant(Chunk(first, second))))
      for
        entered <- Promise.make[Nothing, Unit]
        tool = Tool.make(ToolDef("work", "test", Json.Obj()))(input =>
          if input == Json.Num(1) then ZIO.succeed("completed")
          else entered.succeed(()) *> ZIO.never
        )
        toolkit <- ZIO.fromEither(Toolkit(tool))
        agent <- ReActAgent
          .builder()
          .model(model)
          .toolkit(toolkit)
          .toolExecution(ToolExecution.Sequential)
          .build
        fiber   <- agent.run(Msg.user("work")).forkScoped
        _       <- entered.await
        _       <- fiber.interrupt
        history <- agent.history
        results = history.filter(_.role == Role.Tool).flatMap(_.toolResults)
      yield assertTrue(
        results.size == 2,
        results.headOption.exists(result =>
          result.state == ToolResultState.Success && result.text == "completed"
        ),
        results.lastOption.exists(result =>
          result.state == ToolResultState.Error && result.id == "duplicate"
        ),
        history.lastOption.exists(_.generationReason == GenerateReason.Interrupted)
      )
    },
    test("parallel cancellation distinguishes tool names when local IDs collide") {
      val slow = ContentBlock.ToolUse("duplicate", "slow", Json.Obj())
      val fast = ContentBlock.ToolUse("duplicate", "fast", Json.Obj())
      val model =
        LlmClient.mockFunction((_, _) => TurnOutput.ToolCalls(Msg.assistant(Chunk(slow, fast))))
      for
        slowEntered   <- Promise.make[Nothing, Unit]
        fastCompleted <- Promise.make[Nothing, Unit]
        slowTool = Tool.make(ToolDef("slow", "test", Json.Obj(), isConcurrencySafe = true))(_ =>
          slowEntered.succeed(()) *> ZIO.never
        )
        fastTool = Tool.make(ToolDef("fast", "test", Json.Obj(), isConcurrencySafe = true))(_ =>
          ZIO.succeed("completed")
        )
        toolkit <- ZIO.fromEither(Toolkit(slowTool, fastTool))
        agent   <- ReActAgent.builder().model(model).toolkit(toolkit).build
        fiber <- agent
          .streamEvents(Msg.user("work"))
          .tap {
            case event: AgentEvent.ToolResultEndEvent if event.name == "fast" =>
              fastCompleted.succeed(()).unit
            case _ => ZIO.unit
          }
          .runDrain
          .forkScoped
        _       <- slowEntered.await
        _       <- fastCompleted.await
        _       <- fiber.interrupt
        history <- agent.history
        results = history.filter(_.role == Role.Tool).flatMap(_.toolResults)
      yield assertTrue(
        results.size == 2,
        results.exists(result =>
          result.name.contains(
            "fast"
          ) && result.state == ToolResultState.Success && result.text == "completed"
        ),
        results.exists(result =>
          result.name.contains("slow") && result.state == ToolResultState.Error
        )
      )
    },
    test("completion at the configured iteration bound does not emit a limit event") {
      val model = LlmClient.mockFunction((_, _) => TurnOutput.FinalText(Msg.assistant("done")))
      for
        agent  <- ReActAgent.builder().model(model).maxIters(1).build
        events <- agent.streamEvents(Msg.user("work")).runCollect
      yield assertTrue(!events.exists(_.isInstanceOf[AgentEvent.ExceedMaxItersEvent]))
    },
    test("inline mixed content emits ordered deltas with result metadata and correlated data") {
      val data     = ContentBlock.Data.url("https://example.com/image", Some("image"))
      val metadata = Map("trace" -> Json.Str("result"), "serverTool" -> Json.Bool(true))
      val result = ContentBlock.ToolResult(
        "server",
        Some("web_search"),
        Chunk(ContentBlock.Text("head"), data, ContentBlock.Text("tail")),
        metadata = metadata
      )
      val reply = Msg.assistant(Chunk(serverCall, result, ContentBlock.Text("done")))
      val model = LlmClient.mockFunction((_, _) => TurnOutput.ToolCalls(reply))
      for
        agent   <- ReActAgent.make(client = model)
        events  <- agent.streamEvents(Msg.user("search")).runCollect
        history <- agent.history
        starts     = events.collect { case event: AgentEvent.AgentStartEvent => event.replyId }
        dataEvents = events.collect { case event: AgentEvent.ToolResultDataDeltaEvent => event }
        expected = starts.flatMap(replyId =>
          Chunk[AgentEvent](
            AgentEvent.ToolResultStartEvent("server", "web_search", metadata = metadata),
            AgentEvent.ToolResultDeltaEvent("server", "head", metadata = metadata),
            AgentEvent.ToolResultDataDeltaEvent(replyId, "server", "web_search", data, metadata),
            AgentEvent.ToolResultDeltaEvent("server", "tail", metadata = metadata),
            AgentEvent.ToolResultEndEvent(
              "server",
              "web_search",
              "head\ntail",
              ToolResultState.Success,
              metadata = metadata
            )
          )
        )
      yield assertTrue(
        starts.size == 1,
        resultEvents(events) == expected,
        dataEvents.map(_.replyId) == starts,
        history.lastOption.contains(reply),
        !history.exists(_.role == Role.Tool),
        result.metadata == metadata
      )
    },
    test("empty inline error results retain state and metadata without inventing deltas") {
      val metadata = Map("trace" -> Json.Str("empty"))
      val result = ContentBlock.ToolResult(
        "server",
        Some("web_search"),
        state = ToolResultState.Error,
        metadata = metadata
      )
      val model = LlmClient.mockFunction((_, _) =>
        TurnOutput.ToolCalls(Msg.assistant(Chunk(serverCall, result)))
      )
      for
        agent   <- ReActAgent.make(client = model)
        events  <- agent.streamEvents(Msg.user("search")).runCollect
        history <- agent.history
      yield assertTrue(
        resultEvents(events) == Chunk(
          AgentEvent.ToolResultStartEvent("server", "web_search", metadata = metadata),
          AgentEvent.ToolResultEndEvent(
            "server",
            "web_search",
            "",
            ToolResultState.Error,
            metadata = metadata
          )
        ),
        !history.exists(_.role == Role.Tool)
      )
    },
    test("inline server publication preserves execution of unrelated local calls") {
      val local = ContentBlock.ToolUse("local", "unregistered", Json.Obj())
      val result = ContentBlock.ToolResult
        .text("server", "web_search", "inline", metadata = Map("trace" -> Json.Str("kept")))
      for
        model <- LlmClient.mock(
          Chunk(
            TurnOutput.ToolCalls(Msg.assistant(Chunk(serverCall, local, result))),
            TurnOutput.FinalText(Msg.assistant("done"))
          )
        )
        agent   <- ReActAgent.make(client = model)
        events  <- agent.streamEvents(Msg.user("work")).runCollect
        history <- agent.history
        localResults = history.filter(_.role == Role.Tool).flatMap(_.toolResults)
      yield assertTrue(
        localResults.map(_.id) == Chunk("local"),
        localResults.forall(_.state == ToolResultState.Error),
        events.collect { case event: AgentEvent.ToolResultDeltaEvent => event.callId } == Chunk(
          "server"
        ),
        history.lastOption.exists(_.text == "done")
      )
    }
  )
