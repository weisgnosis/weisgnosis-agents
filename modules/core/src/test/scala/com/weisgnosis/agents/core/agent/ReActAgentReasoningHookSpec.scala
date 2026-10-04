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

import com.weisgnosis.agents.core.client.*
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.event.{AgentEvent, ModelStreamContext}
import com.weisgnosis.agents.core.model.*
import com.weisgnosis.agents.core.tool.{Tool, ToolDef, Toolkit}
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

object ReActAgentReasoningHookSpec extends ZIOSpecDefault:
  private val schema: Json           = Json.Obj("type" -> Json.Str("integer"))
  private val firstUsage: ChatUsage  = ChatUsage(10, 2, time = 0.25)
  private val lastUsage: ChatUsage   = ChatUsage(20, 3, time = 0.5)
  private val forgedUsage: ChatUsage = ChatUsage(900, 800, time = 99)
  private val lookup: ContentBlock.ToolUse =
    ContentBlock.ToolUse("lookup-call", "lookup", Json.Obj())

  private def capture(id: String, value: Int, usage: ChatUsage): TurnOutput =
    TurnOutput.ToolCalls(
      Msg
        .assistant(
          Chunk(
            ContentBlock
              .ToolUse(id, StructuredOutput.ToolName, Json.Obj("response" -> Json.Num(value)))
          )
        )
        .withUsage(usage)
    )

  def spec: Spec[TestEnvironment & Scope, Any] = suite("ReActAgentReasoningHookSpec")(
    test("replacement controls the committed answer and preserves model-owned canonical usage") {
      for
        forged <- ZIO.fromEither(forgedUsage.toJsonAST)
        client <- LlmClient.mock(
          Chunk(TurnOutput.FinalText(Msg.assistant("original").withUsage(firstUsage)))
        )
        hook = PostReasoningHook((_, _) =>
          ZIO.succeed(
            ReasoningDecision.Replace(
              Msg
                .assistant("replacement")
                .withUsage(forgedUsage)
                .withMetadata(Msg.MetadataChatUsage, forged)
            )
          )
        )
        agent  <- ReActAgent.builder().model(client).postReasoningHook(hook).build
        result <- agent.run(Msg.user("question"))
      yield assertTrue(
        result.reply.text == "replacement",
        result.reply.usage.contains(firstUsage),
        result.reply.chatUsage.contains(firstUsage),
        !result.reply.metadata.contains(Msg.MetadataChatUsage),
        result.usage.contains(firstUsage),
        result.history.forall(_.text != "original")
      )
    },
    test("replacement cannot mint absent usage and preserves valid legacy usage metadata") {
      for
        forged <- ZIO.fromEither(forgedUsage.toJsonAST)
        legacy <- ZIO.fromEither(firstUsage.toJsonAST)
        outcomes <- ZIO.foreach(
          Chunk(
            Msg.assistant("raw"),
            Msg.assistant("raw").withMetadata(Msg.MetadataChatUsage, legacy)
          )
        ) { original =>
          for
            client <- LlmClient.mock(Chunk(TurnOutput.FinalText(original)))
            agent <- ReActAgent.make(
              client = client,
              postReasoningHook = PostReasoningHook((_, _) =>
                ZIO.succeed(
                  ReasoningDecision.Replace(
                    Msg
                      .assistant("accepted")
                      .withUsage(forgedUsage)
                      .withMetadata(Msg.MetadataChatUsage, forged)
                  )
                )
              )
            )
            result <- agent.run(Msg.user("q"))
          yield (
            result.reply.chatUsage,
            result.usage,
            result.reply.metadata.get(Msg.MetadataChatUsage)
          )
        }
      yield assertTrue(
        outcomes == Chunk((None, None, None), (Some(firstUsage), Some(firstUsage), Some(legacy)))
      )
    },
    test(
      "testStructuredOutputNullReasoningMessage adapts removal to a bounded discard without executing it"
    ) {
      for
        decisions <- Ref.make(0)
        client <- LlmClient.mock(
          Chunk(capture("discarded", 1, firstUsage), capture("accepted", 7, lastUsage))
        )
        hook = PostReasoningHook((_, _) =>
          decisions
            .getAndUpdate(_ + 1)
            .map(index => if index == 0 then ReasoningDecision.Discard else ReasoningDecision.Keep)
        )
        agent <- ReActAgent.make(client = client, postReasoningHook = hook)
        result <- agent
          .runStructured[Int](Chunk(Msg.user("number")), schema, StructuredMode.SyntheticTool)
        calls <- decisions.get
      yield assertTrue(
        result._2 == 7,
        calls == 2,
        result._1.iterationCount == 2,
        result._1.usage.contains(firstUsage.combine(lastUsage)),
        result._1.reply.usage.contains(lastUsage),
        result._1.history.flatMap(_.toolUses).isEmpty,
        result._1.history.flatMap(_.toolResults).isEmpty,
        result._1.history.filter(_.role == Role.Assistant).map(_.text) == Chunk("7")
      )
    },
    test("replaced tool calls are persisted after hook cleanup and before dispatch") {
      for
        closed   <- Ref.make(false)
        observed <- Ref.make(Option.empty[(Boolean, Chunk[Msg])])
        agentRef <- Ref.make(Option.empty[ReActAgent])
        tool = Tool.make(ToolDef("lookup", "Read a value", Json.Obj()))(_ =>
          for
            agent <- agentRef.get.flatMap(value =>
              ZIO
                .fromOption(value)
                .orElseFail(AgentError.InvalidConfiguration("missing test agent"))
            )
            history  <- agent.history
            released <- closed.get
            _        <- observed.set(Some(released -> history))
          yield "7"
        )
        client <- LlmClient.mock(
          Chunk(
            TurnOutput.FinalText(Msg.assistant("replace me").withUsage(firstUsage)),
            TurnOutput.FinalText(Msg.assistant("done").withUsage(lastUsage))
          )
        )
        hook = PostReasoningHook((_, message) =>
          if message.text == "replace me" then
            ZIO
              .addFinalizer(closed.set(true))
              .as(ReasoningDecision.Replace(Msg.assistant(Chunk(lookup))))
          else ZIO.succeed(ReasoningDecision.Keep)
        )
        agent <- ReActAgent.make(
          client = client,
          toolkit = Toolkit(Map("lookup" -> tool)),
          postReasoningHook = hook
        )
        _          <- agentRef.set(Some(agent))
        result     <- agent.run(Msg.user("q"))
        atDispatch <- observed.get
      yield assertTrue(
        result.reply.text == "done",
        result.usage.contains(firstUsage.combine(lastUsage)),
        atDispatch.exists { case (released, history) =>
          released && history.lastOption.exists(_.toolUses == Chunk(lookup))
        },
        result.history.flatMap(_.toolResults).map(_.id) == Chunk("lookup-call"),
        result.history.forall(_.text != "replace me")
      )
    },
    test("discarding every tool response exhausts iterations without dispatch or dangling history") {
      for
        calls <- Ref.make(0)
        tool = Tool.make(ToolDef("lookup", "Read a value", Json.Obj()))(_ =>
          calls.update(_ + 1).as("7")
        )
        client = LlmClient.mockFunction((_, _) =>
          TurnOutput.ToolCalls(Msg.assistant(Chunk(lookup)).withUsage(firstUsage))
        )
        agent <- ReActAgent.make(
          config = AgentConfig(maxIterations = 2),
          client = client,
          toolkit = Toolkit(Map("lookup" -> tool)),
          postReasoningHook = PostReasoningHook((_, _) => ZIO.succeed(ReasoningDecision.Discard))
        )
        result     <- agent.run(Msg.user("q"))
        executions <- calls.get
      yield assertTrue(
        executions == 0,
        result.iterationCount == 2,
        result.reply.generateReason.contains("max_iterations"),
        result.history.flatMap(_.toolUses).isEmpty,
        result.usage.contains(firstUsage.combine(firstUsage))
      )
    },
    test("discard preserves the consumed thinking retry budget") {
      val thinking = TurnOutput.ThinkingOnly(Msg.assistant(Chunk(ContentBlock.Thinking("reason"))))
      for
        calls <- Ref.make(0)
        client <- LlmClient.mock(
          Chunk(
            thinking,
            TurnOutput.FinalText(Msg.assistant("discard")),
            thinking,
            TurnOutput.FinalText(Msg.assistant("must not run"))
          )
        )
        hook = PostReasoningHook((_, message) =>
          calls
            .update(_ + 1)
            .as(
              if message.text == "discard" then ReasoningDecision.Discard
              else ReasoningDecision.Keep
            )
        )
        agent <- ReActAgent.make(
          config = AgentConfig(emptyThinkingRetryLimit = 1),
          client = client,
          postReasoningHook = hook
        )
        result <- agent.run(Msg.user("q"))
        count  <- calls.get
      yield assertTrue(
        count == 3,
        result.iterationCount == 3,
        result.reply.generateReason.contains("empty_response"),
        result.history.count(_.reminderKind.contains("empty_response")) == 1,
        result.history.forall(_.text != "discard")
      )
    },
    test(
      "interrupted model output bypasses hooks and retains partial usage and call reconciliation"
    ) {
      for
        calls <- Ref.make(0)
        client <- LlmClient.mock(
          Chunk(TurnOutput.Interrupted(Msg.assistant(Chunk(lookup)).withUsage(firstUsage)))
        )
        agent <- ReActAgent.make(
          client = client,
          postReasoningHook =
            PostReasoningHook((_, _) => calls.update(_ + 1).as(ReasoningDecision.Discard))
        )
        result <- agent.run(Msg.user("q"))
        count  <- calls.get
      yield assertTrue(
        count == 0,
        result.state == AgentRunState.Interrupted,
        result.usage.contains(firstUsage),
        result.history
          .flatMap(_.toolResults)
          .exists(result => result.id == lookup.id && result.state == ToolResultState.Error)
      )
    },
    test("non-assistant replacements fail before persistence and cannot trigger Auto fallback") {
      for
        calls <- Ref.make(0)
        client = LlmClient.mockEffectful((_, _, _) =>
          calls.update(_ + 1).as(TurnOutput.FinalText(Msg.assistant("7")))
        )
        agent <- ReActAgent.make(
          client = client,
          postReasoningHook =
            PostReasoningHook((_, _) => ZIO.succeed(ReasoningDecision.Replace(Msg.user("7"))))
        )
        result  <- agent.runStructured[Int](Chunk(Msg.user("q")), schema).either
        history <- agent.history
        count   <- calls.get
      yield assertTrue(
        result.left.exists(_.isInstanceOf[AgentError.ReasoningHookError]),
        count == 1,
        history.map(_.text) == Chunk("q")
      )
    },
    test("hook errors retain provenance and never retry native structured output") {
      val error = AgentError.ReasoningHookError("host policy failed")
      for
        calls  <- Ref.make(0)
        closed <- Ref.make(false)
        client = LlmClient.mockEffectful((_, _, _) =>
          calls.update(_ + 1).as(TurnOutput.FinalText(Msg.assistant("7")))
        )
        agent <- ReActAgent.make(
          client = client,
          postReasoningHook =
            PostReasoningHook((_, _) => ZIO.addFinalizer(closed.set(true)) *> ZIO.fail(error))
        )
        result   <- agent.runStructured[Int](Chunk(Msg.user("q")), schema).either
        count    <- calls.get
        released <- closed.get
      yield assertTrue(result == Left(error), count == 1, released)
    },
    test(
      "cooperative cancellation closes a pending hook without persisting or executing its model tools"
    ) {
      for
        entered <- Promise.make[Nothing, Unit]
        closed  <- Ref.make(false)
        calls   <- Ref.make(0)
        tool = Tool.make(ToolDef("lookup", "Read a value", Json.Obj()))(_ =>
          calls.update(_ + 1).as("7")
        )
        client <- LlmClient.mock(
          Chunk(TurnOutput.ToolCalls(Msg.assistant(Chunk(lookup)).withUsage(firstUsage)))
        )
        hook = PostReasoningHook((_, _) =>
          ZIO.addFinalizer(closed.set(true)) *> entered.succeed(()) *> ZIO.never
        )
        agent <- ReActAgent.make(
          client = client,
          toolkit = Toolkit(Map("lookup" -> tool)),
          postReasoningHook = hook
        )
        fiber    <- agent.run(Msg.user("q")).forkScoped
        _        <- entered.await
        _        <- agent.interrupt
        result   <- fiber.join
        released <- closed.get
        count    <- calls.get
      yield assertTrue(
        released,
        count == 0,
        result.state == AgentRunState.Interrupted,
        result.usage.contains(firstUsage),
        result.history.flatMap(_.toolUses).isEmpty,
        result.history.flatMap(_.toolResults).isEmpty,
        result.history.count(_.generateReason.contains("interrupted")) == 1
      )
    },
    test("fiber interruption closes the hook and recovers history exactly once") {
      for
        entered <- Promise.make[Nothing, Unit]
        closed  <- Ref.make(false)
        client  <- LlmClient.mock(Chunk(TurnOutput.FinalText(Msg.assistant("raw"))))
        hook = PostReasoningHook((_, _) =>
          ZIO.addFinalizer(closed.set(true)) *> entered.succeed(()) *> ZIO.never
        )
        agent    <- ReActAgent.make(client = client, postReasoningHook = hook)
        fiber    <- agent.run(Msg.user("q")).forkScoped
        _        <- entered.await
        exit     <- fiber.interrupt
        released <- closed.get
        history  <- agent.history
      yield assertTrue(
        exit.isInterrupted,
        released,
        history.count(_.generateReason.contains("interrupted")) == 1,
        history.forall(_.text != "raw")
      )
    },
    test("hook execution remains inside the invocation timeout and releases its scope") {
      for
        entered <- Promise.make[Nothing, Unit]
        closed  <- Ref.make(false)
        client  <- LlmClient.mock(Chunk(TurnOutput.FinalText(Msg.assistant("raw"))))
        hook = PostReasoningHook((_, _) =>
          ZIO.addFinalizer(closed.set(true)) *> entered.succeed(()) *> ZIO.never
        )
        agent <- ReActAgent.make(
          config = AgentConfig(timeout = 1.second),
          client = client,
          postReasoningHook = hook
        )
        fiber    <- agent.run(Msg.user("q")).either.forkScoped
        _        <- entered.await
        _        <- TestClock.adjust(2.seconds)
        result   <- fiber.join
        released <- closed.get
      yield assertTrue(result.left.exists(_.isInstanceOf[AgentError.TimeoutError]), released)
    },
    test("invocation usage spans successful native turns and a later synthetic fallback") {
      for
        requests <- Ref.make(0)
        model = LlmClient.mockEffectful((_, _, _) =>
          requests.getAndUpdate(_ + 1).flatMap {
            case 0 =>
              ZIO.succeed(TurnOutput.ToolCalls(Msg.assistant(Chunk(lookup)).withUsage(firstUsage)))
            case 1 => ZIO.fail(AgentError.ModelError(400, "native format rejected"))
            case _ => ZIO.succeed(capture("capture", 7, lastUsage))
          }
        )
        tool = Tool.make(ToolDef("lookup", "Read a value", Json.Obj()))(_ => ZIO.succeed("7"))
        agent <- ReActAgent.make(
          client = model,
          toolkit = Toolkit(Map("lookup" -> tool)),
          llmConfig = LlmConfig(
            "configured",
            structuredOutputCapability = StructuredOutputCapability.Native()
          )
        )
        result <- agent.runStructured[Int](Chunk(Msg.user("q")), schema)
        count  <- requests.get
      yield assertTrue(
        result._2 == 7,
        count == 3,
        result._1.usage.contains(firstUsage.combine(lastUsage)),
        result._1.reply.usage.contains(lastUsage),
        result._1.history.flatMap(_.toolResults).map(_.id) == Chunk("lookup-call")
      )
    },
    test("synthetic history transfers canonical and legacy usage exactly once across mixed calls") {
      for
        legacy <- ZIO.fromEither(forgedUsage.toJsonAST)
        results <- ZIO.foreach(Chunk(None, Some(firstUsage))) { canonical =>
          val original = Msg
            .assistant(
              Chunk(
                lookup,
                ContentBlock.ToolUse(
                  "capture",
                  StructuredOutput.ToolName,
                  Json.Obj("response" -> Json.Num(7))
                )
              )
            )
            .withUsage(canonical)
            .withMetadata(Msg.MetadataChatUsage, legacy)
          for
            client <- LlmClient.mock(Chunk(TurnOutput.ToolCalls(original)))
            agent  <- ReActAgent.make(client = client)
            result <- agent
              .runStructured[Int](Chunk(Msg.user("number")), schema, StructuredMode.SyntheticTool)
          yield (result, canonical.getOrElse(forgedUsage))
        }
      yield assertTrue(results.forall { case ((result, value), expected) =>
        value == 7 && result.usage.contains(expected) && result.reply.chatUsage
          .contains(expected) &&
        result.history.flatMap(_.chatUsage) == Chunk(expected) &&
        result.history
          .filter(_.toolUses.nonEmpty)
          .forall(message =>
            message.usage.isEmpty && !message.metadata.contains(Msg.MetadataChatUsage)
          )
      })
    },
    test("model completion events use canonical-first legacy usage consistently with results") {
      for
        legacy <- ZIO.fromEither(firstUsage.toJsonAST)
        client <- LlmClient.mock(
          Chunk(
            TurnOutput.FinalText(
              Msg.assistant("answer").withMetadata(Msg.MetadataChatUsage, legacy)
            )
          )
        )
        agent  <- ReActAgent.make(client = client)
        events <- agent.streamEvents(Msg.user("q")).runCollect
      yield assertTrue(events.collect { case event: AgentEvent.ModelResponseEndEvent =>
        (event.completionTokens, event.totalTokens)
      } == Chunk((firstUsage.outputTokens, firstUsage.totalTokens)))
    },
    test("replacing model tools with final text prevents their execution and dangling history") {
      for
        calls <- Ref.make(0)
        tool = Tool.make(ToolDef("lookup", "Read a value", Json.Obj()))(_ =>
          calls.update(_ + 1).as("7")
        )
        client <- LlmClient.mock(Chunk(TurnOutput.ToolCalls(Msg.assistant(Chunk(lookup)))))
        agent <- ReActAgent.make(
          client = client,
          toolkit = Toolkit(Map("lookup" -> tool)),
          postReasoningHook = PostReasoningHook((_, _) =>
            ZIO.succeed(ReasoningDecision.Replace(Msg.assistant("accepted")))
          )
        )
        result <- agent.run(Msg.user("q"))
        count  <- calls.get
      yield assertTrue(
        count == 0,
        result.reply.text == "accepted",
        result.history.flatMap(_.toolUses).isEmpty,
        result.history.flatMap(_.toolResults).isEmpty
      )
    },
    test("a thinking-only replacement enters the normal bounded reminder path") {
      ZIO
        .foreach(
          Chunk(
            ContentBlock.Thinking("reason")                                          -> 2,
            ContentBlock.Thinking("", Map("thoughtSignature" -> Json.Str("opaque"))) -> 2,
            ContentBlock.Thinking(
              "",
              Map(
                "reasoningDetails" -> Json.Arr(
                  Json.Obj(
                    "type" -> Json.Str("reasoning.encrypted"),
                    "data" -> Json.Str("opaque")
                  )
                )
              )
            )                                                                -> 2,
            ContentBlock.Thinking("")                                        -> 1,
            ContentBlock.Thinking("", Map("reasoningDetails" -> Json.Arr())) -> 1
          )
        ) { case (replacement, expectedIterations) =>
          for
            client <- LlmClient.mock(
              Chunk(
                TurnOutput.FinalText(Msg.assistant("replace")),
                TurnOutput.FinalText(Msg.assistant("done"))
              )
            )
            hook = PostReasoningHook((_, message) =>
              ZIO.succeed(
                if message.text == "replace" then
                  ReasoningDecision.Replace(Msg.assistant(Chunk(replacement)))
                else ReasoningDecision.Keep
              )
            )
            agent  <- ReActAgent.make(client = client, postReasoningHook = hook)
            result <- agent.run(Msg.user("q"))
          yield assertTrue(
            result.reply.text == (if expectedIterations == 2 then "done" else ""),
            result.iterationCount == expectedIterations,
            result.history
              .count(_.reminderKind.contains("empty_response")) == expectedIterations - 1,
            result.history.exists(_.content.contains(replacement)),
            result.history.forall(_.text != "replace")
          )
        }
        .map(_.foldLeft(assertTrue(true))(_ && _))
    },
    test("usage excludes supplied history and resets between invocations") {
      for
        client <- LlmClient.mock(
          Chunk(
            TurnOutput.FinalText(Msg.assistant("one").withUsage(firstUsage)),
            TurnOutput.FinalText(Msg.assistant("two").withUsage(lastUsage))
          )
        )
        agent <- ReActAgent.make(
          client = client,
          initialHistory = Chunk(Msg.assistant("earlier").withUsage(forgedUsage))
        )
        first <- agent.run(
          Chunk(Msg.assistant("supplied").withUsage(forgedUsage), Msg.user("first"))
        )
        second <- agent.run(Msg.user("second"))
      yield assertTrue(first.usage.contains(firstUsage), second.usage.contains(lastUsage))
    },
    test(
      "hook identity matches model events while accepted replacement controls final publication"
    ) {
      for
        contexts <- Ref.make(Chunk.empty[ModelStreamContext])
        client   <- LlmClient.mock(Chunk(TurnOutput.FinalText(Msg.assistant("1"))))
        hook = PostReasoningHook((context, _) =>
          contexts.update(_ :+ context).as(ReasoningDecision.Replace(Msg.assistant("7")))
        )
        agent <- ReActAgent.make(client = client, postReasoningHook = hook)
        events <- agent
          .streamStructuredEvents[Int](Chunk(Msg.user("q")), schema, StructuredMode.Native)
          .runCollect
        seen    <- contexts.get
        history <- agent.history
      yield assertTrue(
        seen.map(Some(_)) == events.collect { case event: AgentEvent.ModelRequestStartEvent =>
          event.context
        },
        events.collect { case event: AgentEvent.AgentResultEvent =>
          event.result.getStructuredData[Int]
        } == Chunk(Right(7)),
        history.lastOption.exists(_.getStructuredData[Int] == Right(7))
      )
    }
  ) @@ TestAspect.timeout(20.seconds)
