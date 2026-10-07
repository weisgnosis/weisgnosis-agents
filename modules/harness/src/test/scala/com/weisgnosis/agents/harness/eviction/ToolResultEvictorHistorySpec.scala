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

import com.weisgnosis.agents.core.agent.{ModelInputTransform, ReActAgent}
import com.weisgnosis.agents.core.client.{LlmClient, TurnOutput}
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.event.AgentEvent
import com.weisgnosis.agents.core.model.*
import com.weisgnosis.agents.core.tool.*
import java.nio.file.{Files, Path}
import com.weisgnosis.agents.harness.memory.{CompactorConfig, ConversationCompactor, TokenCounter}
import zio.*
import zio.json.ast.Json
import zio.test.*

object ToolResultEvictorHistorySpec extends ZIOSpecDefault:
  private val large: String              = "start" + "x" * 80000 + "tail"
  private val call: ContentBlock.ToolUse = ContentBlock.ToolUse("call", "lookup", Json.Obj())
  private val original: Chunk[Msg] = Chunk(
    Msg.system("system"),
    Msg.assistant(Chunk(call)),
    Msg.tool("call", "lookup", large),
    Msg.assistant(
      Chunk(call.withServerTool(true), ContentBlock.ToolResult.text("call", "lookup", large))
    )
  )
  private def memoryStore(saved: Ref[Map[String, String]]): ToolResultStore = new ToolResultStore:
    def persist(key: String, content: String): IO[AgentError, String] =
      saved.update(_.updated(key, content)).as(s"large_tool_results/$key.txt")
    def resolve(key: String): IO[AgentError, Option[StoredToolResult]] =
      saved.get.map(
        _.get(key).map(content => StoredToolResult(content, s"large_tool_results/$key.txt"))
      )

  def spec: Spec[TestEnvironment & Scope, Any] = suite("ToolResultEvictorHistorySpec")(
    test(
      "supplied local and inline observations are evicted before model input without replaying tool events"
    ) {
      for
        saved    <- Ref.make(Map.empty[String, String])
        observed <- Ref.make(Chunk.empty[Msg])
        client = LlmClient.mockEffectful((history, _, _) =>
          observed.set(history).as(TurnOutput.FinalText(Msg.assistant("done")))
        )
        agent <- ReActAgent.make(
          client = client,
          initialHistory = original,
          historyPreparer = new ToolResultEvictor(memoryStore(saved)).preparer()
        )
        events  <- agent.streamEvents(Msg.user("q")).runCollect
        history <- agent.history
        input   <- observed.get
        files   <- saved.get
      yield assertTrue(
        files.values.toSet == Set(large),
        input.flatMap(_.toolResults).size == 2,
        input.flatMap(_.toolResults).forall(_.text.length < 5000),
        history.flatMap(_.toolResults) == input.flatMap(_.toolResults),
        history.filter(_.role == Role.System) == original.filter(_.role == Role.System),
        ToolHistory.inspect(history).isComplete,
        !events.exists(_.isInstanceOf[AgentEvent.ToolResultStartEvent])
      )
    },
    test("evictsCanonicalAgentStateWhenReasoningHookRemovedLargeResult") {
      for
        directory <- EvictionTestSupport.temporaryDirectory
        store     <- ToolResultStore.local(directory, WorkspaceTrust.HostControlled)
        observed  <- Ref.make(Chunk.empty[Msg])
        projected <- Ref.make(Chunk.empty[Msg])
        client = LlmClient.mockEffectful((history, _, _) =>
          observed.set(history).as(TurnOutput.FinalText(Msg.assistant("done")))
        )
        agent <- ReActAgent.make(
          client = client,
          initialHistory = original,
          historyPreparer = new ToolResultEvictor(store).preparer(),
          modelInputTransform = ModelInputTransform((_, history) =>
            val view =
              history.filter(message => message.toolUses.isEmpty && message.role != Role.Tool)
            projected.set(view).as(view)
          )
        )
        result   <- agent.run(Msg.user("q"))
        input    <- observed.get
        expected <- projected.get
        path <- ZIO
          .fromOption(
            result.history
              .flatMap(_.toolResults)
              .headOption
              .flatMap(_.metadata.get("eviction_path"))
              .flatMap(_.asString)
          )
          .orElseFail(AgentError.InvalidMessage("missing eviction path"))
        content <- ZIO.attemptBlocking(Files.readString(Path.of(path)))
      yield assertTrue(
        input == expected,
        input.flatMap(_.toolResults).isEmpty,
        input.flatMap(_.toolUses).isEmpty,
        content == large,
        result.history.flatMap(_.toolResults).size == 2,
        result.history.flatMap(_.toolResults).forall(_.text.length < 5000),
        ToolHistory.inspect(result.history).isComplete
      )
    },
    test(
      "newly completed observations stay canonical while successive model views omit their pairs"
    ) {
      for
        saved      <- Ref.make(Map.empty[String, String])
        input      <- Ref.make(Chunk.empty[Chunk[Msg]])
        calls      <- Ref.make(0)
        executions <- Ref.make(0)
        evictor = new ToolResultEvictor(memoryStore(saved))
        client = LlmClient.mockEffectful((history, _, _) =>
          input.update(_ :+ history) *> calls
            .getAndUpdate(_ + 1)
            .map:
              case 0 => TurnOutput.ToolCalls(Msg.assistant(Chunk(call)))
              case 1 =>
                TurnOutput.ThinkingOnly(Msg.assistant(Chunk(ContentBlock.Thinking("thinking"))))
              case _ => TurnOutput.FinalText(Msg.assistant("done"))
        )
        toolkit = Toolkit.single(
          Tool.make(ToolDef("lookup", "Lookup", Json.Obj()))(_ =>
            executions.update(_ + 1).as(large)
          )
        )
        agent <- ReActAgent.make(
          client = client,
          toolkit = toolkit,
          historyPreparer = evictor.preparer(),
          toolResultProcessor = evictor.processor(),
          modelInputTransform = ModelInputTransform((_, history) =>
            ZIO.succeed(
              history.filter(message => message.toolUses.isEmpty && message.role != Role.Tool)
            )
          )
        )
        result <- agent.run(Msg.user("q"))
        views  <- input.get
        count  <- executions.get
        files  <- saved.get
      yield assertTrue(
        views.size == 3,
        views.flatten.flatMap(_.toolResults).isEmpty,
        views.flatten.flatMap(_.toolUses).isEmpty,
        count == 1,
        result.history.flatMap(_.toolUses).size == 1,
        result.history.flatMap(_.toolResults).size == 1,
        result.history.flatMap(_.toolResults).forall(_.text.length < 5000),
        files.values.toSet == Set(large),
        ToolHistory.inspect(result.history).isComplete
      )
    },
    test(
      "request-only metadata and observation-state rewrites never replace canonical eviction state"
    ) {
      for
        saved <- Ref.make(Map.empty[String, String])
        input <- Ref.make(Chunk.empty[Msg])
        client = LlmClient.mockEffectful((history, _, _) =>
          input.set(history).as(TurnOutput.FinalText(Msg.assistant("done")))
        )
        agent <- ReActAgent.make(
          client = client,
          initialHistory = original,
          historyPreparer = new ToolResultEvictor(memoryStore(saved)).preparer(),
          modelInputTransform = ModelInputTransform((_, history) =>
            ZIO.succeed(
              history.map(
                _.mapToolResults(result =>
                  result.copy(
                    state = ToolResultState.Error,
                    metadata = result.metadata.updated("request_note", Json.Str("view only"))
                  )
                )
              )
            )
          )
        )
        result <- agent.run(Msg.user("q"))
        sent   <- input.get
      yield assertTrue(
        sent
          .flatMap(_.toolResults)
          .forall(r => r.state == ToolResultState.Error && r.metadata.contains("request_note")),
        result.history
          .flatMap(_.toolResults)
          .forall(r => r.state == ToolResultState.Success && !r.metadata.contains("request_note")),
        result.history.flatMap(_.toolResults).map(_.text) == sent.flatMap(_.toolResults).map(_.text)
      )
    },
    test("storage failure leaves supplied observations intact and permits model dispatch") {
      val store = new ToolResultStore:
        def persist(key: String, content: String): IO[AgentError, String] =
          ZIO.fail(AgentError.ToolExecutionError("store", "failed"))
        def resolve(key: String): IO[AgentError, Option[StoredToolResult]] =
          ZIO.fail(AgentError.ToolExecutionError("store", "failed"))
      for
        client <- LlmClient.mock(Chunk(TurnOutput.FinalText(Msg.assistant("done"))))
        agent <- ReActAgent.make(
          client = client,
          initialHistory = original,
          historyPreparer = new ToolResultEvictor(store).preparer()
        )
        result <- agent.run(Msg.user("q"))
      yield assertTrue(result.reply.text == "done", result.history.take(original.size) == original)
    },
    test(
      "cancelling supplied-history eviction retains raw canonical history without a model request"
    ) {
      for
        entered  <- Promise.make[Nothing, Unit]
        closed   <- Ref.make(false)
        requests <- Ref.make(0)
        store = new ToolResultStore:
          def persist(key: String, content: String): IO[AgentError, String] =
            (entered.succeed(()) *> ZIO.never).ensuring(closed.set(true))
          def resolve(key: String): IO[AgentError, Option[StoredToolResult]] = ZIO.none
        client = LlmClient.mockEffectful((_, _, _) =>
          requests.update(_ + 1).as(TurnOutput.FinalText(Msg.assistant("unexpected")))
        )
        agent <- ReActAgent.make(
          client = client,
          initialHistory = original,
          historyPreparer = new ToolResultEvictor(store).preparer()
        )
        fiber    <- agent.run(Msg.user("q")).forkScoped
        _        <- entered.await
        _        <- agent.interrupt
        result   <- fiber.join
        count    <- requests.get
        released <- closed.get
      yield assertTrue(
        count == 0,
        released,
        result.history.take(original.size) == original,
        result.history.count(_.generateReason.contains("interrupted")) == 1
      )
    },
    test("eviction precedes compaction pruning and commits the composed history once") {
      val counter = new TokenCounter:
        def count(message: Msg): Long = 1L
      for
        saved              <- Ref.make(Map.empty[String, String])
        agentRef           <- Ref.make(Option.empty[ReActAgent])
        summaryInput       <- Ref.make(Chunk.empty[Msg])
        savedAtSummary     <- Ref.make(false)
        canonicalAtSummary <- Ref.make(Chunk.empty[Msg])
        compactor = new ConversationCompactor(
          history =>
            for
              _ <- summaryInput.set(history)
              _ <- saved.get.map(_.values.toSet == Set(large)).flatMap(savedAtSummary.set)
              agent <- agentRef.get.flatMap(value =>
                ZIO
                  .fromOption(value)
                  .orElseFail(AgentError.InvalidConfiguration("missing test agent"))
              )
              canonical <- agent.history
              _         <- canonicalAtSummary.set(canonical)
            yield "summary",
          CompactorConfig(
            triggerTokens = 3,
            keepTokens = 1,
            preserveTailMessages = 1,
            protectTokens = 0,
            maxPruneOutputChars = 10000
          ),
          counter
        )
        initial = original ++ Chunk.fill(8)(Msg.assistant("old"))
        client <- LlmClient.mock(Chunk(TurnOutput.FinalText(Msg.assistant("done"))))
        agent <- ReActAgent.make(
          client = client,
          initialHistory = initial,
          historyPreparer =
            new ToolResultEvictor(memoryStore(saved)).preparer().andThen(compactor.preparer)
        )
        _          <- agentRef.set(Some(agent))
        result     <- agent.run(Msg.user("q"))
        summarized <- summaryInput.get
        persisted  <- savedAtSummary.get
        canonical  <- canonicalAtSummary.get
      yield assertTrue(
        persisted,
        canonical.take(initial.size) == initial,
        summarized.flatMap(_.toolResults).size == 2,
        summarized
          .flatMap(_.toolResults)
          .forall(r => r.text.length < 5000 && r.text.contains("read_file")),
        result.history.size < initial.size,
        ToolHistory.inspect(result.history).isComplete,
        result.history.count(ConversationCompactor.isSummary) == 1
      )
    }
  ) @@ TestAspect.timeout(20.seconds)
