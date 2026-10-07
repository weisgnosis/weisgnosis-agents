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

import com.weisgnosis.agents.core.agent.{AgentConfig, AgentRunState, ReActAgent, ToolExecution}
import com.weisgnosis.agents.core.client.{LlmClient, TurnOutput}
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.model.*
import com.weisgnosis.agents.core.tool.*
import zio.*
import zio.json.ast.Json
import zio.test.*

object ConversationCompactorIntegrationSpec extends ZIOSpecDefault:
  private val counter: TokenCounter = new TokenCounter:
    def count(message: Msg): Long = 1L
  private val config: CompactorConfig =
    CompactorConfig(triggerTokens = 2, keepTokens = 2, preserveTailMessages = 2, protectTokens = 0)
  private def call(id: String): ContentBlock.ToolUse = ContentBlock.ToolUse(id, "read", Json.Obj())
  private def toolkit(run: UIO[String]): Toolkit =
    Toolkit.single(Tool.make(ToolDef("read", "Read", Json.Obj()))(_ => run))

  def spec: Spec[TestEnvironment & Scope, Any] = suite("ConversationCompactorIntegrationSpec")(
    test("compaction shrinking history cannot hide current in-flight calls during interruption") {
      for
        executions <- Ref.make(0)
        waiting    <- Promise.make[Nothing, Unit]
        compactor = new ConversationCompactor(_ => ZIO.succeed("summary"), config, counter)
        model <- LlmClient.mock(
          Chunk(TurnOutput.ToolCalls(Msg.assistant(Chunk(call("first"), call("second")))))
        )
        agent <- ReActAgent.make(
          config = AgentConfig(toolExecution = ToolExecution.Sequential),
          client = model,
          initialHistory = Chunk.fill(12)(Msg.assistant("old")),
          historyPreparer = compactor.preparer,
          toolkit = toolkit(
            executions
              .getAndUpdate(_ + 1)
              .flatMap(index =>
                if index == 0 then ZIO.succeed("completed") else waiting.succeed(()) *> ZIO.never
              )
          )
        )
        fiber  <- agent.run(Msg.user("q")).forkScoped
        _      <- waiting.await
        during <- agent.history
        _      <- agent.interrupt
        result <- fiber.join
        count  <- executions.get
      yield assertTrue(
        during.size < 12,
        count == 2,
        result.state == AgentRunState.Interrupted,
        result.history.flatMap(_.toolResults).map(value => value.id -> value.state) == Chunk(
          "first"  -> ToolResultState.Success,
          "second" -> ToolResultState.Error
        ),
        result.history.count(_.generateReason.contains("interrupted")) == 1,
        ToolHistory.inspect(result.history).isComplete
      )
    },
    test("summary failures remain preparation errors and cannot trigger native fallback") {
      val original = Chunk.fill(5)(Msg.assistant("old"))
      val input    = Msg.user("q")
      for
        summaries <- Ref.make(0)
        calls     <- Ref.make(0)
        compactor = new ConversationCompactor(
          _ => summaries.update(_ + 1) *> ZIO.fail(AgentError.ModelError(400, "summary rejected")),
          config,
          counter
        )
        model = LlmClient.mockEffectful((_, _, _) =>
          calls.update(_ + 1).as(TurnOutput.FinalText(Msg.assistant("7")))
        )
        agent <- ReActAgent.make(
          client = model,
          initialHistory = original,
          historyPreparer = compactor.preparer
        )
        result       <- agent.runStructured[Int](input, Json.Obj()).either
        history      <- agent.history
        summaryCalls <- summaries.get
        modelCalls   <- calls.get
      yield assertTrue(
        result == Left(AgentError.HistoryPreparationError("summary rejected")),
        summaryCalls == 1,
        modelCalls == 0,
        history == original :+ input
      )
    },
    test(
      "cancelling compaction hooks cleans up before recovery without committing partial history"
    ) {
      val original = Chunk.fill(5)(Msg.assistant("old"))
      val input    = Msg.user("q")
      for
        entered <- Promise.make[Nothing, Unit]
        closed  <- Ref.make(false)
        calls   <- Ref.make(0)
        compactor = new ConversationCompactor(
          _ => ZIO.succeed("summary"),
          config,
          counter,
          flush = _ => (entered.succeed(()) *> ZIO.never).ensuring(closed.set(true))
        )
        model = LlmClient.mockEffectful((_, _, _) =>
          calls.update(_ + 1).as(TurnOutput.FinalText(Msg.assistant("wrong")))
        )
        agent <- ReActAgent.make(
          client = model,
          initialHistory = original,
          historyPreparer = compactor.preparer
        )
        fiber    <- agent.run(input).forkScoped
        _        <- entered.await
        exit     <- fiber.interrupt
        history  <- agent.history
        released <- closed.get
        count    <- calls.get
      yield assertTrue(
        exit.isInterrupted,
        released,
        count == 0,
        history.take(original.size + 1) == original :+ input,
        history.count(_.generateReason.contains("interrupted")) == 1,
        !history.exists(ConversationCompactor.isSummary)
      )
    },
    test("each model request sees committed paired history and chained summaries remain singular") {
      for
        summaries <- Ref.make(0)
        requests  <- Ref.make(Chunk.empty[Chunk[Msg]])
        compactor = new ConversationCompactor(
          _ => summaries.updateAndGet(_ + 1).map(index => s"summary $index"),
          config,
          counter
        )
        model = LlmClient.mockEffectful((history, _, _) =>
          requests
            .update(_ :+ history)
            .as(
              if history.flatMap(_.toolResults).isEmpty then
                TurnOutput.ToolCalls(Msg.assistant(Chunk(call("one"))))
              else TurnOutput.FinalText(Msg.assistant("done"))
            )
        )
        agent <- ReActAgent.make(
          client = model,
          initialHistory = Chunk.fill(5)(Msg.assistant("old")),
          historyPreparer = compactor.preparer,
          toolkit = toolkit(ZIO.succeed("result"))
        )
        result   <- agent.run(Msg.user("q"))
        observed <- requests.get
        count    <- summaries.get
      yield assertTrue(
        count == 2,
        observed.size == 2,
        observed.forall(history => ToolHistory.inspect(history).isComplete),
        observed.forall(_.count(ConversationCompactor.isSummary) == 1),
        result.history.count(ConversationCompactor.isSummary) == 1,
        result.history.flatMap(_.toolResults).map(_.text) == Chunk("result")
      )
    },
    test("typed flush failure preserves original input without downstream fallback") {
      val original = Chunk.fill(5)(Msg.assistant("old"))
      val input    = Msg.user("q")
      for
        calls <- Ref.make(0)
        compactor = new ConversationCompactor(
          _ => ZIO.succeed("unused"),
          config,
          counter,
          flush = _ => ZIO.fail(AgentError.ModelError(500, "flush failed"))
        )
        client = LlmClient.mockEffectful((_, _, _) =>
          calls.update(_ + 1).as(TurnOutput.FinalText(Msg.assistant("wrong")))
        )
        agent <- ReActAgent.make(
          client = client,
          initialHistory = original,
          historyPreparer = compactor.preparer
        )
        result  <- agent.run(input).either
        history <- agent.history
        count   <- calls.get
      yield assertTrue(
        result == Left(AgentError.HistoryPreparationError("flush failed")),
        history == original :+ input,
        count == 0
      )
    },
    test("interruptedSummaryCompactionPropagatesWithoutCallingNext") {
      val original = Chunk.fill(5)(Msg.assistant("old"))
      val input    = Msg.user("q")
      for
        calls <- Ref.make(0)
        compactor = new ConversationCompactor(_ => ZIO.interrupt, config, counter)
        client = LlmClient.mockEffectful((_, _, _) =>
          calls.update(_ + 1).as(TurnOutput.FinalText(Msg.assistant("wrong")))
        )
        agent <- ReActAgent.make(
          client = client,
          initialHistory = original,
          historyPreparer = compactor.preparer
        )
        exit    <- agent.run(input).exit
        history <- agent.history
        count   <- calls.get
      yield assertTrue(
        exit.isInterrupted,
        count == 0,
        history.dropRight(1) == original :+ input,
        history.lastOption.flatMap(_.generateReason).contains("interrupted")
      )
    },
    test("asynchronousInterruptedSummaryCompactionPropagatesWithoutCallingNext") {
      val original = Chunk.fill(5)(Msg.assistant("old"))
      val input    = Msg.user("q")
      for
        entered <- Promise.make[Nothing, Unit]
        closed  <- Ref.make(false)
        calls   <- Ref.make(0)
        compactor = new ConversationCompactor(
          _ => (entered.succeed(()) *> ZIO.never).ensuring(closed.set(true)),
          config,
          counter
        )
        client = LlmClient.mockEffectful((_, _, _) =>
          calls.update(_ + 1).as(TurnOutput.FinalText(Msg.assistant("wrong")))
        )
        agent <- ReActAgent.make(
          client = client,
          initialHistory = original,
          historyPreparer = compactor.preparer
        )
        fiber    <- agent.run(input).forkScoped
        _        <- entered.await
        exit     <- fiber.interrupt
        history  <- agent.history
        released <- closed.get
        count    <- calls.get
      yield assertTrue(
        exit.isInterrupted,
        released,
        count == 0,
        history.dropRight(1) == original :+ input,
        history.lastOption.flatMap(_.generateReason).contains("interrupted")
      )
    },
    test("mixed summary interruption and cleanup defect propagate without downstream dispatch") {
      val original = Chunk.fill(5)(Msg.assistant("old"))
      val input    = Msg.user("q")
      val defect   = new IllegalStateException("summary cleanup failed")
      for
        calls <- Ref.make(0)
        compactor = new ConversationCompactor(
          _ => ZIO.interrupt.ensuring(ZIO.die(defect)),
          config,
          counter
        )
        client = LlmClient.mockEffectful((_, _, _) =>
          calls.update(_ + 1).as(TurnOutput.FinalText(Msg.assistant("wrong")))
        )
        agent <- ReActAgent.make(
          client = client,
          initialHistory = original,
          historyPreparer = compactor.preparer
        )
        exit    <- agent.run(input).exit
        history <- agent.history
        count   <- calls.get
      yield assertTrue(
        exit.isInterrupted,
        exit.causeOption.exists(_.defects.contains(defect)),
        history == original :+ input,
        count == 0
      )
    },
    test("interruptedDownstreamPropagatesWithoutRetryingNext") {
      val original = Chunk.fill(5)(Msg.assistant("old"))
      for
        requests <- Ref.make(Chunk.empty[Chunk[Msg]])
        compactor = new ConversationCompactor(_ => ZIO.succeed("summary"), config, counter)
        client = LlmClient.mockEffectful((history, _, _) =>
          requests.update(_ :+ history) *> ZIO.interrupt
        )
        agent <- ReActAgent.make(
          client = client,
          initialHistory = original,
          historyPreparer = compactor.preparer
        )
        exit     <- agent.run(Msg.user("q")).exit
        history  <- agent.history
        observed <- requests.get
      yield assertTrue(
        exit.isInterrupted,
        observed.size == 1,
        observed.headOption.contains(history.dropRight(1)),
        history.count(ConversationCompactor.isSummary) == 1,
        history.lastOption.flatMap(_.generateReason).contains("interrupted")
      )
    },
    test("concurrentSessionInterruptDoesNotAffectOtherSession with separate agent ownership") {
      val originalA = Chunk.fill(5)(Msg.assistant("session-a"))
      val originalB = Chunk.fill(5)(Msg.assistant("session-b"))
      for
        enteredA <- Promise.make[Nothing, Unit]
        enteredB <- Promise.make[Nothing, Unit]
        releaseB <- Promise.make[Nothing, Unit]
        closed   <- Ref.make(Set.empty[String])
        requests <- Ref.make(Chunk.empty[Chunk[Msg]])
        compactor = new ConversationCompactor(
          history =>
            if history.exists(_.text == "session-a") then
              (enteredA.succeed(()) *> ZIO.never).ensuring(closed.update(_ + "a"))
            else
              (enteredB
                .succeed(()) *> releaseB.await).as("summary-b").ensuring(closed.update(_ + "b"))
          ,
          config,
          counter
        )
        client = LlmClient.mockEffectful((history, _, _) =>
          requests.update(_ :+ history).as(TurnOutput.FinalText(Msg.assistant("done-b")))
        )
        agentA <- ReActAgent.make(
          client = client,
          initialHistory = originalA,
          historyPreparer = compactor.preparer
        )
        agentB <- ReActAgent.make(
          client = client,
          initialHistory = originalB,
          historyPreparer = compactor.preparer
        )
        fiberA   <- agentA.run(Msg.user("question-a")).forkScoped
        fiberB   <- agentB.run(Msg.user("question-b")).forkScoped
        _        <- enteredA.await *> enteredB.await
        _        <- agentA.interrupt
        resultA  <- fiberA.join
        _        <- releaseB.succeed(())
        resultB  <- fiberB.join
        observed <- requests.get
        released <- closed.get
      yield assertTrue(
        resultA.state == AgentRunState.Interrupted,
        resultA.history.dropRight(1) == originalA :+ Msg.user("question-a"),
        resultB.state == AgentRunState.Completed,
        resultB.reply.text == "done-b",
        !resultB.history.exists(_.generateReason.contains("interrupted")),
        observed.size == 1,
        observed.headOption.contains(resultB.history.dropRight(1)),
        released == Set("a", "b")
      )
    },
    test("modelStreamInterruptProducesOneCallAndInterruptedTerminalReason") {
      val input = Msg.user("q")
      for
        entered   <- Promise.make[Nothing, Unit]
        calls     <- Ref.make(0)
        summaries <- Ref.make(0)
        compactor = new ConversationCompactor(
          _ => summaries.update(_ + 1).as("unused"),
          config.copy(triggerTokens = 100),
          counter
        )
        client = LlmClient.mockEffectful((_, _, _) =>
          calls.update(_ + 1) *> entered.succeed(()) *> ZIO.never
        )
        agent        <- ReActAgent.make(client = client, historyPreparer = compactor.preparer)
        fiber        <- agent.run(input).forkScoped
        _            <- entered.await *> agent.interrupt
        result       <- fiber.join
        count        <- calls.get
        summaryCount <- summaries.get
      yield assertTrue(
        result.state == AgentRunState.Interrupted,
        result.reply.generateReason.contains("interrupted"),
        result.history.dropRight(1) == Chunk(input),
        count == 1,
        summaryCount == 0
      )
    },
    test("interruptingOneSessionDoesNotCancelConcurrentSession with separate agent ownership") {
      val inputA = Msg.user("question-a")
      val inputB = Msg.user("question-b")
      for
        enteredA <- Promise.make[Nothing, Unit]
        enteredB <- Promise.make[Nothing, Unit]
        releaseB <- Promise.make[Nothing, Unit]
        requests <- Ref.make(Chunk.empty[Chunk[Msg]])
        closed   <- Ref.make(Set.empty[String])
        compactor = new ConversationCompactor(
          _ => ZIO.succeed("unused"),
          config.copy(triggerTokens = 100),
          counter
        )
        client = LlmClient.mockEffectful((history, _, _) =>
          requests.update(_ :+ history) *>
            (if history.contains(inputA) then
               (enteredA.succeed(()) *> ZIO.never).ensuring(closed.update(_ + "a"))
             else
               (enteredB.succeed(()) *> releaseB.await)
                 .as(TurnOutput.FinalText(Msg.assistant("done-b")))
                 .ensuring(closed.update(_ + "b")))
        )
        agentA   <- ReActAgent.make(client = client, historyPreparer = compactor.preparer)
        agentB   <- ReActAgent.make(client = client, historyPreparer = compactor.preparer)
        fiberA   <- agentA.run(inputA).forkScoped
        fiberB   <- agentB.run(inputB).forkScoped
        _        <- enteredA.await *> enteredB.await
        _        <- agentA.interrupt
        resultA  <- fiberA.join
        _        <- releaseB.succeed(())
        resultB  <- fiberB.join
        observed <- requests.get
        released <- closed.get
      yield assertTrue(
        resultA.state == AgentRunState.Interrupted,
        resultA.reply.generateReason.contains("interrupted"),
        resultA.history.dropRight(1) == Chunk(inputA),
        resultB.state == AgentRunState.Completed,
        resultB.history == Chunk(inputB, Msg.assistant("done-b")),
        observed.size == 2,
        observed.toSet == Set(Chunk(inputA), Chunk(inputB)),
        released == Set("a", "b")
      )
    }
  )
