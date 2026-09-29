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
import com.weisgnosis.agents.core.event.{AgentEvent, ModelStreamContext}
import com.weisgnosis.agents.core.model.*
import com.weisgnosis.agents.core.tool.*
import zio.*
import zio.json.ast.Json
import zio.test.*

object ReActAgentModelInputSpec extends ZIOSpecDefault:
  private val canonical: Chunk[Msg]    = Chunk(Msg.system("system"), Msg.user("canonical"))
  private val view: Chunk[Msg]         = Chunk(Msg.system("system"), Msg.user("request only"))
  private val prepare: HistoryPreparer = HistoryPreparer(_ => ZIO.succeed(canonical))
  private val finished: LlmClient =
    LlmClient.mockFunction((_, _) => TurnOutput.FinalText(Msg.assistant("done")))

  def spec: Spec[TestEnvironment & Scope, Any] = suite("ReActAgentModelInputSpec")(
    test(
      "projection sees committed preparation and closes before correlated dispatch without changing history"
    ) {
      for
        agentRef         <- Ref.make(Option.empty[ReActAgent])
        observed         <- Ref.make(Chunk.empty[Msg])
        committed        <- Ref.make(Chunk.empty[Msg])
        contexts         <- Ref.make(Chunk.empty[ModelStreamContext])
        closed           <- Ref.make(false)
        closedAtDispatch <- Ref.make(false)
        transform = ModelInputTransform((context, history) =>
          for
            _ <- observed.set(history)
            _ <- contexts.update(_ :+ context)
            agent <- agentRef.get.flatMap(value =>
              ZIO
                .fromOption(value)
                .orElseFail(AgentError.ModelInputError("missing test agent"))
            )
            state <- agent.history
            _     <- committed.set(state)
            _     <- ZIO.addFinalizer(closed.set(true))
          yield view
        )
        input <- Ref.make(Chunk.empty[Msg])
        client = LlmClient.mockEffectful((history, _, _) =>
          input.set(history) *> closed.get
            .flatMap(closedAtDispatch.set)
            .as(TurnOutput.FinalText(Msg.assistant("done")))
        )
        agent <- ReActAgent
          .builder()
          .model(client)
          .initialHistory(Chunk(Msg.system("system")))
          .historyPreparer(prepare)
          .modelInputTransform(transform)
          .build
        _            <- agentRef.set(Some(agent))
        events       <- agent.streamEvents(Msg.user("q")).runCollect
        finalHistory <- agent.history
        prepared     <- observed.get
        stored       <- committed.get
        sent         <- input.get
        released     <- closedAtDispatch.get
        ids          <- contexts.get
      yield assertTrue(
        prepared == canonical,
        stored == canonical,
        sent == view,
        released,
        finalHistory == canonical :+ Msg.assistant("done"),
        events.collect { case event: AgentEvent.ModelRequestStartEvent =>
          event.context
        }.flatten == ids,
        events.collect { case event: AgentEvent.AgentResultEvent => event.result.text } == Chunk(
          "done"
        )
      )
    },
    test("invalid projected pairs or system rewrites fail after cleanup before request events") {
      val call = ContentBlock.ToolUse("call", "lookup", Json.Obj())
      val invalid = Chunk(
        Chunk(Msg.system("system"), Msg.assistant(Chunk(call))),
        Chunk(Msg.user("missing system"))
      )
      ZIO
        .foreach(invalid): candidate =>
          for
            requests <- Ref.make(0)
            events   <- Ref.make(Chunk.empty[AgentEvent])
            closed   <- Ref.make(false)
            client = LlmClient.mockEffectful((_, _, _) =>
              requests.update(_ + 1).as(TurnOutput.FinalText(Msg.assistant("unexpected")))
            )
            agent <- ReActAgent.make(
              client = client,
              initialHistory = canonical,
              modelInputTransform =
                ModelInputTransform((_, _) => ZIO.addFinalizer(closed.set(true)).as(candidate))
            )
            exit <- agent
              .streamEvents(Chunk.empty)
              .tap(event => events.update(_ :+ event))
              .runDrain
              .either
            history  <- agent.history
            count    <- requests.get
            seen     <- events.get
            released <- closed.get
          yield assertTrue(
            exit.left.exists(_.isInstanceOf[AgentError.ModelInputError]),
            count == 0,
            released,
            history == canonical,
            !seen.exists(_.isInstanceOf[AgentEvent.ModelRequestStartEvent])
          )
        .map(results => results.foldLeft(assertTrue(true))(_ && _))
    },
    test("typed transform failure does not trigger native structured-output fallback") {
      for
        transforms <- Ref.make(0)
        calls      <- Ref.make(0)
        client = LlmClient.mockEffectful((_, _, _) =>
          calls.update(_ + 1).as(TurnOutput.FinalText(Msg.assistant("7")))
        )
        agent <- ReActAgent.make(
          client = client,
          modelInputTransform = ModelInputTransform((_, _) =>
            transforms.update(_ + 1) *> ZIO.fail(
              AgentError.ModelInputError("status 400 in host transformation")
            )
          )
        )
        result      <- agent.runStructured[Int](Msg.user("q"), Json.Obj()).either
        transformed <- transforms.get
        requested   <- calls.get
      yield assertTrue(
        result.left.exists(_.isInstanceOf[AgentError.ModelInputError]),
        transformed == 1,
        requested == 0
      )
    },
    test(
      "a shorter request view cannot hide a new interrupted call or overwrite its completed same-ID predecessor"
    ) {
      val call = ContentBlock.ToolUse("same", "lookup", Json.Obj())
      val original = Chunk(
        Msg.user("old"),
        Msg.assistant(Chunk(call)),
        Msg.tool("same", "lookup", "completed earlier")
      )
      for
        entered <- Promise.make[Nothing, Unit]
        client  <- LlmClient.mock(Chunk(TurnOutput.ToolCalls(Msg.assistant(Chunk(call)))))
        toolkit = Toolkit(
          Map(
            "lookup" -> Tool.make(ToolDef("lookup", "Lookup", Json.Obj()))(_ =>
              entered.succeed(()) *> ZIO.never
            )
          )
        )
        agent <- ReActAgent.make(
          client = client,
          toolkit = toolkit,
          initialHistory = original,
          modelInputTransform = ModelInputTransform((_, history) =>
            ZIO.succeed(history.filter(message => message.role == Role.User))
          )
        )
        fiber  <- agent.run(Msg.user("new")).forkScoped
        _      <- entered.await
        _      <- agent.interrupt
        result <- fiber.join
      yield assertTrue(
        result.history.take(original.size) == original,
        result.history.flatMap(_.toolResults).map(_.state) == Chunk(
          ToolResultState.Success,
          ToolResultState.Error
        ),
        result.history.count(_.generateReason.contains("interrupted")) == 1,
        ToolHistory.inspect(result.history).isComplete
      )
    },
    test("cooperative cancellation preserves prepared canonical history and recovers once") {
      for
        entered <- Promise.make[Nothing, Unit]
        closed  <- Ref.make(false)
        agent <- ReActAgent.make(
          client = finished,
          initialHistory = Chunk(Msg.system("system")),
          historyPreparer = prepare,
          modelInputTransform = ModelInputTransform((_, _) =>
            ZIO.addFinalizer(closed.set(true)) *> entered.succeed(()) *> ZIO.never
          )
        )
        fiber    <- agent.run(Msg.user("q")).forkScoped
        _        <- entered.await
        _        <- agent.interrupt
        result   <- fiber.join
        released <- closed.get
      yield assertTrue(
        released,
        result.state == AgentRunState.Interrupted,
        result.history.take(canonical.size) == canonical,
        result.history.count(_.generateReason.contains("interrupted")) == 1
      )
    },
    test("fiber cancellation preserves canonical history and closes projection resources") {
      for
        entered <- Promise.make[Nothing, Unit]
        closed  <- Ref.make(false)
        agent <- ReActAgent.make(
          client = finished,
          initialHistory = canonical,
          modelInputTransform = ModelInputTransform((_, _) =>
            ZIO.addFinalizer(closed.set(true)) *> entered.succeed(()) *> ZIO.never
          )
        )
        fiber    <- agent.run(Chunk.empty).forkScoped
        _        <- entered.await
        exit     <- fiber.interrupt
        history  <- agent.history
        released <- closed.get
      yield assertTrue(
        exit.isInterrupted,
        released,
        history.take(canonical.size) == canonical,
        history.count(_.generateReason.contains("interrupted")) == 1
      )
    },
    test("projection timeout remains typed and completes cleanup before returning") {
      for
        entered <- Promise.make[Nothing, Unit]
        closed  <- Ref.make(false)
        agent <- ReActAgent.make(
          config = AgentConfig(timeout = 1.second),
          client = finished,
          initialHistory = canonical,
          modelInputTransform = ModelInputTransform((_, _) =>
            ZIO.addFinalizer(closed.set(true)) *> entered.succeed(()) *> ZIO.never
          )
        )
        fiber    <- agent.run(Chunk.empty).either.forkScoped
        _        <- entered.await
        _        <- TestClock.adjust(1.second)
        result   <- fiber.join
        history  <- agent.history
        released <- closed.get
      yield assertTrue(
        result.left.exists(_.isInstanceOf[AgentError.TimeoutError]),
        released,
        history.take(canonical.size) == canonical,
        history.count(_.generateReason.contains("interrupted")) == 1
      )
    },
    test(
      "cooperative cancellation propagates projection finalizer defects without false recovery"
    ) {
      val defect = new IllegalStateException("projection cleanup defect")
      for
        entered <- Promise.make[Nothing, Unit]
        agent <- ReActAgent.make(
          client = finished,
          initialHistory = canonical,
          modelInputTransform = ModelInputTransform((_, _) =>
            ZIO.addFinalizer(ZIO.die(defect)) *> entered.succeed(()) *> ZIO.never
          )
        )
        fiber   <- agent.run(Chunk.empty).exit.forkScoped
        _       <- entered.await
        _       <- agent.interrupt
        exit    <- fiber.join
        history <- agent.history
      yield assertTrue(exit.causeOption.exists(_.defects.contains(defect)), history == canonical)
    },
    test("thinking retries project each request without dropping canonical reasoning or usage") {
      for
        inputs <- Ref.make(Chunk.empty[Chunk[Msg]])
        calls  <- Ref.make(0)
        client = LlmClient.mockEffectful((history, _, _) =>
          inputs.update(_ :+ history) *> calls
            .getAndUpdate(_ + 1)
            .map:
              case 0 =>
                TurnOutput.ThinkingOnly(
                  Msg.assistant(
                    Chunk(ContentBlock.Thinking("private reasoning")),
                    usage = Some(ChatUsage(1, 2))
                  )
                )
              case _ =>
                TurnOutput.FinalText(
                  Msg.assistant(Chunk(ContentBlock.Text("done")), usage = Some(ChatUsage(3, 4)))
                )
        )
        agent <- ReActAgent.make(
          config = AgentConfig(maxIterations = 2, emptyThinkingRetryLimit = 1),
          client = client,
          modelInputTransform =
            ModelInputTransform((_, history) => ZIO.succeed(history.filter(_.thinking.isEmpty)))
        )
        result <- agent.run(Msg.user("q"))
        views  <- inputs.get
      yield assertTrue(
        result.iterationCount == 2,
        result.reply.text == "done",
        result.usage.contains(ChatUsage(4, 6)),
        views.size == 2,
        views.flatten.forall(_.thinking.isEmpty),
        result.history.exists(_.thinking.nonEmpty),
        views.lastOption.exists(
          _.exists(_.metadata.get(Msg.MetadataReminderKind).contains(Json.Str("empty_response")))
        )
      )
    },
    test(
      "native fallback projects both attempts with new request identities and no duplicate input"
    ) {
      for
        contexts <- Ref.make(Chunk.empty[ModelStreamContext])
        calls    <- Ref.make(0)
        client = LlmClient.mockEffectful((_, _, _) =>
          calls
            .getAndUpdate(_ + 1)
            .flatMap:
              case 0 => ZIO.fail(AgentError.ModelError(400, "unsupported native format"))
              case _ => ZIO.succeed(TurnOutput.FinalText(Msg.assistant("7")))
        )
        agent <- ReActAgent.make(
          client = client,
          modelInputTransform =
            ModelInputTransform((context, history) => contexts.update(_ :+ context).as(history))
        )
        result <- agent.runStructured[Int](Msg.user("q"), Json.Obj())
        ids    <- contexts.get
      yield assertTrue(
        result._2 == 7,
        ids.size == 2,
        ids.map(_.requestId).distinct.size == 2,
        result._1.history.count(_.role == Role.User) == 1
      )
    }
  ) @@ TestAspect.timeout(20.seconds)
