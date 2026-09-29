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
package com.weisgnosis.agents.harness.eviction

import com.weisgnosis.agents.core.agent.ReActAgent
import com.weisgnosis.agents.core.client.{LlmClient, TurnOutput}
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.event.AgentEvent
import com.weisgnosis.agents.core.model.*
import com.weisgnosis.agents.core.tool.{Tool, ToolDef, Toolkit}
import zio.*
import zio.json.ast.Json
import zio.test.*
import java.nio.file.{Files, Path}
import EvictionTestSupport.temporaryDirectory

object ToolResultEvictorIntegrationSpec extends ZIOSpecDefault:
  private val large: String              = "A" * 2000 + "M" * 76001 + "Z" * 2000
  private val call: ContentBlock.ToolUse = ContentBlock.ToolUse("call", "lookup", Json.Obj())
  private val firstTurn: TurnOutput      = TurnOutput.ToolCalls(Msg.assistant(Chunk(call)))
  private val toolkit: Toolkit = Toolkit(
    Map(
      "lookup" -> Tool.make(ToolDef("lookup", "Read output", Json.Obj()))(_ => ZIO.succeed(large))
    )
  )

  def spec: Spec[TestEnvironment & Scope, Any] = suite("ToolResultEvictorIntegrationSpec")(
    test(
      "immediate eviction updates persisted history model input and result events with one cached payload"
    ) {
      for
        saved <- Ref.make(Map.empty[String, String])
        input <- Ref.make(Chunk.empty[ContentBlock.ToolResult])
        store = new ToolResultStore:
          def persist(key: String, content: String): IO[AgentError, String] =
            saved.update(_.updated(key, content)).as(s"large_tool_results/$key.txt")
        model = LlmClient.mockEffectful((history, _, _) =>
          if history.flatMap(_.toolResults).isEmpty then ZIO.succeed(firstTurn)
          else
            input
              .set(history.flatMap(_.toolResults))
              .as(TurnOutput.FinalText(Msg.assistant("done")))
        )
        agent <- ReActAgent.make(
          client = model,
          toolkit = toolkit,
          toolResultProcessor = new ToolResultEvictor(store).processor()
        )
        events   <- agent.streamEvents(Msg.user("q")).runCollect
        observed <- input.get
        history  <- agent.history
        files    <- saved.get
        results = history.flatMap(_.toolResults)
      yield assertTrue(
        files.size == 1,
        files.values.toSet == Set(large),
        results == observed,
        results.size == 1,
        results.forall(result =>
          result.text.length < 5000 && result.text.contains("A" * 2000) && result.text.endsWith(
            "Z" * 2000
          ) && result.text.contains("read_file") && result.state == ToolResultState.Success
        ),
        results.forall(_.metadata.contains("eviction_cache_key")),
        events.collect { case event: AgentEvent.ToolResultDeltaEvent => event.delta } == results
          .map(_.text),
        events.collect { case event: AgentEvent.ToolResultEndEvent => event.metadata } == results
          .map(_.metadata)
      )
    },
    test(
      "evictsImmediateReasoningInputAndAgentStateUsingDefaultLocalPath with explicit workspace trust"
    ) {
      for
        directory <- temporaryDirectory
        store     <- ToolResultStore.local(directory, WorkspaceTrust.HostControlled)
        client    <- LlmClient.mock(Chunk(firstTurn, TurnOutput.FinalText(Msg.assistant("done"))))
        agent <- ReActAgent.make(
          client = client,
          toolkit = toolkit,
          toolResultProcessor = new ToolResultEvictor(store, "Test Agent").processor()
        )
        result <- agent.run(Msg.user("q"))
        block <- ZIO
          .fromOption(result.history.flatMap(_.toolResults).headOption)
          .orElseFail(AgentError.InvalidMessage("missing evicted observation"))
        stored <- ZIO
          .fromOption(block.metadata.get("eviction_path").flatMap(_.asString))
          .orElseFail(AgentError.InvalidMessage("missing eviction path"))
        content <- ZIO.attemptBlocking(Files.readString(Path.of(stored)))
      yield assertTrue(
        content == large,
        Path.of(stored).startsWith(directory.resolve("large_tool_results")),
        block.text.contains(stored),
        block.metadata.contains("eviction_cache_key"),
        !EvictionConfig().excludedTools.contains("grep_files"),
        !EvictionConfig().excludedTools.contains("glob_files")
      )
    },
    test("persistence failure keeps full output in runtime history and the next model request") {
      val store = new ToolResultStore:
        def persist(key: String, content: String): IO[AgentError, String] =
          ZIO.fail(AgentError.ToolExecutionError("store", "disk full"))
      for
        input <- Ref.make(Chunk.empty[ContentBlock.ToolResult])
        model = LlmClient.mockEffectful((history, _, _) =>
          if history.flatMap(_.toolResults).isEmpty then ZIO.succeed(firstTurn)
          else
            input
              .set(history.flatMap(_.toolResults))
              .as(TurnOutput.FinalText(Msg.assistant("done")))
        )
        agent <- ReActAgent.make(
          client = model,
          toolkit = toolkit,
          toolResultProcessor = new ToolResultEvictor(store).processor()
        )
        result   <- agent.run(Msg.user("q"))
        observed <- input.get
      yield assertTrue(
        result.reply.text == "done",
        observed.map(_.text) == Chunk(large),
        result.history.flatMap(_.toolResults) == observed,
        observed.forall(_.metadata.isEmpty)
      )
    },
    test("cancelling persistence retains completed raw output without a second model call") {
      for
        entered  <- Promise.make[Nothing, Unit]
        closed   <- Ref.make(false)
        requests <- Ref.make(0)
        store = new ToolResultStore:
          def persist(key: String, content: String): IO[AgentError, String] =
            (entered.succeed(()) *> ZIO.never).ensuring(closed.set(true))
        model = LlmClient.mockEffectful((_, _, _) => requests.update(_ + 1).as(firstTurn))
        agent <- ReActAgent.make(
          client = model,
          toolkit = toolkit,
          toolResultProcessor = new ToolResultEvictor(store).processor()
        )
        fiber    <- agent.run(Msg.user("q")).forkScoped
        _        <- entered.await
        _        <- agent.interrupt
        result   <- fiber.join
        count    <- requests.get
        released <- closed.get
      yield assertTrue(
        released,
        count == 1,
        result.history.flatMap(_.toolResults).map(result => (result.text, result.state)) == Chunk(
          (large, ToolResultState.Success)
        ),
        result.history.count(_.generateReason.contains("interrupted")) == 1
      )
    },
    test("reused call IDs retain separate previews and cache entries in completed turns") {
      for
        requests   <- Ref.make(0)
        executions <- Ref.make(0)
        saved      <- Ref.make(Map.empty[String, String])
        store = new ToolResultStore:
          def persist(key: String, content: String): IO[AgentError, String] =
            saved.update(_.updated(key, content)).as(s"large_tool_results/$key.txt")
        changing = Tool.make(ToolDef("lookup", "Read output", Json.Obj()))(_ =>
          executions.getAndUpdate(_ + 1).map(index => if index == 0 then large else "B" * 80001)
        )
        model = LlmClient.mockEffectful((_, _, _) =>
          requests
            .getAndUpdate(_ + 1)
            .map(index =>
              if index < 2 then firstTurn else TurnOutput.FinalText(Msg.assistant("done"))
            )
        )
        agent <- ReActAgent.make(
          client = model,
          toolkit = Toolkit(Map("lookup" -> changing)),
          toolResultProcessor = new ToolResultEvictor(store).processor()
        )
        result <- agent.run(Msg.user("q"))
        files  <- saved.get
        results = result.history.flatMap(_.toolResults)
      yield assertTrue(
        files.values.toSet == Set(large, "B" * 80001),
        results.size == 2,
        results.map(_.id) == Chunk("call", "call"),
        results.flatMap(_.metadata.get("eviction_cache_key")).distinct.size == 2,
        results.headOption.exists(_.text.endsWith("Z" * 2000)),
        results.lastOption.exists(_.text.endsWith("B" * 2000))
      )
    }
  ) @@ TestAspect.timeout(20.seconds)
