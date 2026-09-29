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
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.model.*
import zio.*
import zio.json.ast.Json
import zio.test.*

object ReActAgentHistoryPreparerSpec extends ZIOSpecDefault:
  private val call: ContentBlock.ToolUse = ContentBlock.ToolUse("call", "read", Json.Obj())
  def spec: Spec[TestEnvironment & Scope, Any] = suite("ReActAgentHistoryPreparerSpec")(
    test("preparation closes its scope and commits canonical history before model dispatch") {
      val system   = Msg.system("system")
      val prepared = Chunk(system, Msg.user("summary"))
      for
        released          <- Ref.make(false)
        observed          <- Ref.make(Chunk.empty[Msg])
        canonical         <- Ref.make(Chunk.empty[Msg])
        releasedAtRequest <- Ref.make(false)
        agentRef          <- Ref.make(Option.empty[ReActAgent])
        model = LlmClient.mockEffectful((history, _, _) =>
          observed.set(history) *>
            released.get.flatMap(releasedAtRequest.set) *>
            agentRef.get
              .flatMap(ZIO.foreach(_)(_.history))
              .flatMap(value => canonical.set(Chunk.fromIterable(value).flatten))
              .as(TurnOutput.FinalText(Msg.assistant("done")))
        )
        agent <- ReActAgent
          .builder()
          .model(model)
          .initialHistory(Chunk(system, Msg.user("old")))
          .historyPreparer(HistoryPreparer(_ => ZIO.addFinalizer(released.set(true)).as(prepared)))
          .build
        _      <- agentRef.set(Some(agent))
        result <- agent.run(Msg.user("new"))
        seen   <- observed.get
        state  <- canonical.get
        closed <- releasedAtRequest.get
      yield assertTrue(
        seen == prepared,
        state == prepared,
        closed,
        result.history == prepared :+ Msg.assistant("done")
      )
    },
    test("cancelled preparation preserves history and each empty-input attempt gets one recovery") {
      for
        entered    <- Queue.unbounded[Unit]
        closed     <- Ref.make(0)
        modelCalls <- Ref.make(0)
        model = LlmClient.mockEffectful((_, _, _) =>
          modelCalls.update(_ + 1).as(TurnOutput.FinalText(Msg.assistant("wrong")))
        )
        agent <- ReActAgent.make(
          client = model,
          historyPreparer = HistoryPreparer(_ =>
            ZIO.addFinalizer(closed.update(_ + 1)) *> entered.offer(()) *> ZIO.never
          )
        )
        results <- ZIO.foreach(Chunk(Chunk(Msg.user("q")), Chunk.empty[Msg]))(input =>
          for
            fiber  <- agent.run(input).forkScoped
            _      <- entered.take
            _      <- agent.interrupt
            result <- fiber.join
          yield result
        )
        history  <- agent.history
        calls    <- modelCalls.get
        releases <- closed.get
      yield assertTrue(
        calls == 0,
        releases == 2,
        results.forall(_.state == AgentRunState.Interrupted),
        history.map(_.role) == Chunk(Role.User, Role.Assistant, Role.Assistant),
        history.count(_.generateReason.contains("interrupted")) == 2
      )
    },
    test("identity preparation never reconciles an old supplied dangling call on cancellation") {
      val old = Msg.assistant(Chunk(call))
      for
        entered <- Promise.make[Nothing, Unit]
        model = LlmClient.mockEffectful((_, _, _) => entered.succeed(()) *> ZIO.never)
        agent   <- ReActAgent.make(client = model, initialHistory = Chunk(old))
        fiber   <- agent.run(Chunk.empty).forkScoped
        _       <- entered.await
        exit    <- fiber.interrupt
        history <- agent.history
      yield assertTrue(
        exit.isInterrupted,
        history.headOption.contains(old),
        history.flatMap(_.toolResults).isEmpty,
        history.count(_.generateReason.contains("interrupted")) == 1
      )
    },
    test(
      "invalid rewritten pairing fails after cleanup without committing or requesting the model"
    ) {
      val original = Chunk(Msg.assistant(Chunk(call)), Msg.tool("call", "read", "done"))
      val input    = Msg.user("q")
      for
        calls  <- Ref.make(0)
        closed <- Ref.make(false)
        model = LlmClient.mockEffectful((_, _, _) =>
          calls.update(_ + 1).as(TurnOutput.FinalText(Msg.assistant("wrong")))
        )
        agent <- ReActAgent.make(
          client = model,
          initialHistory = original,
          historyPreparer = HistoryPreparer(_ =>
            ZIO.addFinalizer(closed.set(true)).as(Chunk(Msg.assistant(Chunk(call)), input))
          )
        )
        result   <- agent.run(input).either
        history  <- agent.history
        count    <- calls.get
        released <- closed.get
      yield assertTrue(
        result.left.exists(_.isInstanceOf[AgentError.HistoryPreparationError]),
        count == 0,
        released,
        history == original :+ input
      )
    },
    test("preparation failure cannot trigger native structured-output fallback") {
      for
        preparations <- Ref.make(0)
        calls        <- Ref.make(0)
        model = LlmClient.mockEffectful((_, _, _) =>
          calls.update(_ + 1).as(TurnOutput.FinalText(Msg.assistant("7")))
        )
        agent <- ReActAgent.make(
          client = model,
          historyPreparer = HistoryPreparer(_ =>
            preparations.update(_ + 1) *>
              ZIO.fail(
                AgentError.HistoryPreparationError("provider status 400 during summarization")
              )
          )
        )
        result    <- agent.runStructured[Int](Msg.user("q"), Json.Obj()).either
        prepared  <- preparations.get
        requested <- calls.get
        history   <- agent.history
      yield assertTrue(
        result.left.exists(_.isInstanceOf[AgentError.HistoryPreparationError]),
        prepared == 1,
        requested == 0,
        history == Chunk(Msg.user("q"))
      )
    },
    test("interrupted partial output after shrinking history is persisted and reconciled once") {
      val partial  = Msg.assistant(Chunk(ContentBlock.Thinking("partial"), call))
      val prepared = Chunk(Msg.user("summary"))
      for
        model <- LlmClient.mock(Chunk(TurnOutput.Interrupted(partial)))
        agent <- ReActAgent.make(
          client = model,
          initialHistory = Chunk.fill(12)(Msg.user("old")),
          historyPreparer = HistoryPreparer(_ => ZIO.succeed(prepared))
        )
        result <- agent.run(Msg.user("q"))
      yield assertTrue(
        result.state == AgentRunState.Interrupted,
        result.history.take(2) == prepared :+ partial,
        result.history.flatMap(_.toolResults).map(_.state) == Chunk(ToolResultState.Error),
        result.history.count(_.generateReason.contains("interrupted")) == 1
      )
    },
    test(
      "thinking retry and hook discard prepare each request without resetting usage or retry budget"
    ) {
      val thinking = TurnOutput.ThinkingOnly(
        Msg.assistant(Chunk(ContentBlock.Thinking("think")), usage = Some(ChatUsage(1, 1)))
      )
      for
        preparations <- Ref.make(0)
        hooks        <- Ref.make(0)
        model <- LlmClient.mock(
          Chunk(
            thinking,
            TurnOutput.FinalText(Msg.assistant("discard").withUsage(Some(ChatUsage(1, 1)))),
            thinking
          )
        )
        agent <- ReActAgent.make(
          config = AgentConfig(emptyThinkingRetryLimit = 1),
          client = model,
          historyPreparer = HistoryPreparer(history => preparations.update(_ + 1).as(history)),
          postReasoningHook = PostReasoningHook((_, _) =>
            hooks
              .getAndUpdate(_ + 1)
              .map(index =>
                if index == 1 then ReasoningDecision.Discard else ReasoningDecision.Keep
              )
          )
        )
        result <- agent.run(Msg.user("q"))
        count  <- preparations.get
      yield assertTrue(
        count == 3,
        result.reply.generateReason.contains("empty_response"),
        result.usage.exists(_.totalTokens == 6),
        !result.history.exists(_.text == "discard")
      )
    },
    test("native fallback owns fresh preparation progress without duplicating original input") {
      val response =
        ContentBlock.ToolUse("capture", "generate_response", Json.Obj("response" -> Json.Num(7)))
      for
        preparations <- Ref.make(0)
        calls        <- Ref.make(0)
        model = LlmClient.mockEffectful((_, _, _) =>
          calls
            .getAndUpdate(_ + 1)
            .flatMap(index =>
              if index == 0 then ZIO.fail(AgentError.ModelError(400, "native unsupported"))
              else ZIO.succeed(TurnOutput.ToolCalls(Msg.assistant(Chunk(response))))
            )
        )
        agent <- ReActAgent.make(
          client = model,
          historyPreparer = HistoryPreparer(history => preparations.update(_ + 1).as(history))
        )
        result    <- agent.runStructured[Int](Msg.user("q"), Json.Obj())
        prepared  <- preparations.get
        requested <- calls.get
      yield assertTrue(
        result._2 == 7,
        prepared == 2,
        requested == 2,
        result._1.history.count(_.text == "q") == 1
      )
    }
  )
