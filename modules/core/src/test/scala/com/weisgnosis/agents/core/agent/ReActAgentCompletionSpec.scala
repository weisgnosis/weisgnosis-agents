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
import com.weisgnosis.agents.core.event.AgentEvent
import com.weisgnosis.agents.core.model.*
import zio.*
import zio.json.ast.Json
import zio.test.*

object ReActAgentCompletionSpec extends ZIOSpecDefault:
  private val schema: Json = Json.Obj("type" -> Json.Str("integer"))

  private def success: LlmClient =
    LlmClient.mockFunction((_, _) => TurnOutput.FinalText(Msg.assistant("done")))

  def spec: Spec[TestEnvironment & Scope, Any] = suite("ReActAgentCompletionSpec")(
    test("ordinary completion observes canonical history after model cleanup exactly once") {
      for
        released <- Ref.make(false)
        observed <- Ref.make(Chunk.empty[(TurnCompletion, Boolean)])
        client = LlmClient.mockEffectful((_, _, _) =>
          ZIO.addFinalizer(released.set(true)).as(TurnOutput.FinalText(Msg.assistant("done")))
        )
        observer = TurnCompletionObserver(value =>
          released.get.flatMap(closed => observed.update(_ :+ (value -> closed)))
        )
        agent   <- ReActAgent.make(client = client, completionObserver = observer)
        result  <- agent.run(Msg.user("question"))
        history <- agent.history
        seen    <- observed.get
      yield assertTrue(
        seen.size == 1,
        seen.forall { case (value, closed) =>
          closed && value.history == history && value.outcome == Exit.succeed(
            result
          ) && value.agentId == agent.agentId
        }
      )
    },
    test("native structured completion sees the finalized metadata and history") {
      for
        seen <- Ref.make(Chunk.empty[TurnCompletion])
        client = LlmClient.mockFunction((_, _) => TurnOutput.FinalText(Msg.assistant("7")))
        agent <- ReActAgent.make(
          client = client,
          completionObserver = TurnCompletionObserver(value => seen.update(_ :+ value))
        )
        result <- agent.runStructured[Int](Chunk(Msg.user("number")), schema, StructuredMode.Native)
        completions <- seen.get
      yield assertTrue(
        result._2 == 7,
        completions.size == 1,
        completions.forall(value =>
          value.history == result._1.history && value.outcome == Exit.succeed(result._1)
        ),
        result._1.reply.metadata.contains(StructuredOutput.MetadataStructuredOutput)
      )
    },
    test(
      "Auto fallback shares one completion boundary and observes only finalized synthetic history"
    ) {
      for
        calls <- Ref.make(0)
        seen  <- Ref.make(Chunk.empty[TurnCompletion])
        client = LlmClient.mockEffectful((_, _, _) =>
          calls
            .getAndUpdate(_ + 1)
            .flatMap:
              case 0 => ZIO.fail(AgentError.ModelError(400, "native rejected"))
              case _ =>
                ZIO.succeed(
                  TurnOutput.ToolCalls(
                    Msg.assistant(
                      Chunk(
                        ContentBlock.ToolUse(
                          "response",
                          StructuredOutput.ToolName,
                          Json.Obj("response" -> Json.Num(7))
                        )
                      )
                    )
                  )
                )
        )
        agent <- ReActAgent.make(
          client = client,
          completionObserver = TurnCompletionObserver(value => seen.update(_ :+ value))
        )
        result      <- agent.runStructured[Int](Chunk(Msg.user("number")), schema)
        completions <- seen.get
        count       <- calls.get
      yield assertTrue(
        count == 2,
        result._2 == 7,
        completions.size == 1,
        completions.forall(_.history == result._1.history),
        result._1.history.forall(_.toolUses.isEmpty)
      )
    },
    test(
      "observation failure suppresses the result event and never triggers Auto fallback or a second observation"
    ) {
      val failure = AgentError.CompletionObservationError("persist failed")
      for
        modelCalls    <- Ref.make(0)
        observerCalls <- Ref.make(0)
        events        <- Ref.make(Chunk.empty[AgentEvent])
        client = LlmClient.mockEffectful((_, _, _) =>
          modelCalls.update(_ + 1).as(TurnOutput.FinalText(Msg.assistant("7")))
        )
        agent <- ReActAgent.make(
          client = client,
          completionObserver =
            TurnCompletionObserver(_ => observerCalls.update(_ + 1) *> ZIO.fail(failure))
        )
        exit <- agent
          .streamStructuredEvents[Int](Chunk(Msg.user("number")), schema)
          .tap(event => events.update(_ :+ event))
          .runDrain
          .exit
        seen         <- events.get
        requests     <- modelCalls.get
        observations <- observerCalls.get
      yield assertTrue(
        exit.causeOption.exists(_.failures == List(failure)),
        requests == 1,
        observations == 1,
        !seen.exists(_.isInstanceOf[AgentEvent.AgentResultEvent]),
        seen.count(_.isInstanceOf[AgentEvent.AgentEndEvent]) == 1
      )
    },
    test("successful result publication waits for observation scope cleanup") {
      for
        entered <- Promise.make[Nothing, Unit]
        release <- Promise.make[Nothing, Unit]
        cleaned <- Ref.make(false)
        events  <- Ref.make(Chunk.empty[(AgentEvent, Boolean)])
        observer = TurnCompletionObserver(_ =>
          ZIO.addFinalizer(cleaned.set(true)) *> entered.succeed(()) *> release.await
        )
        agent <- ReActAgent.make(client = success, completionObserver = observer)
        fiber <- agent
          .streamEvents(Msg.user("q"))
          .tap(event => cleaned.get.flatMap(done => events.update(_ :+ (event -> done))))
          .runDrain
          .forkScoped
        _      <- entered.await
        before <- events.get
        _      <- release.succeed(())
        _      <- fiber.join
        after  <- events.get
        results = after.collect { case (value: AgentEvent.AgentResultEvent, done) => value -> done }
      yield assertTrue(
        !before.exists(_._1.isInstanceOf[AgentEvent.AgentResultEvent]),
        results.size == 1,
        results.forall(_._2),
        after.map(_._1).lastOption.exists(_.isInstanceOf[AgentEvent.AgentEndEvent])
      )
    },
    test("failed execution is observed once with canonical state and its original cause") {
      val failure = AgentError.ModelError(503, "offline")
      for
        seen <- Ref.make(Chunk.empty[TurnCompletion])
        client = LlmClient.mockEffectful((_, _, _) => ZIO.fail(failure))
        agent <- ReActAgent.make(
          client = client,
          completionObserver = TurnCompletionObserver(value => seen.update(_ :+ value))
        )
        exit     <- agent.run(Msg.user("q")).exit
        history  <- agent.history
        observed <- seen.get
      yield assertTrue(
        exit.causeOption.exists(_.failures == List(failure)),
        observed.size == 1,
        observed.forall(value =>
          value.history == history && value.outcome.causeOption.exists(_.failures == List(failure))
        )
      )
    },
    test("original and observer failures are retained in sequential cause order") {
      val original    = AgentError.ModelError(503, "offline")
      val observation = AgentError.CompletionObservationError("storage failed")
      for
        calls <- Ref.make(0)
        agent <- ReActAgent.make(
          client = LlmClient.mockEffectful((_, _, _) => ZIO.fail(original)),
          completionObserver =
            TurnCompletionObserver(_ => calls.update(_ + 1) *> ZIO.fail(observation))
        )
        exit  <- agent.run(Msg.user("q")).exit
        count <- calls.get
      yield assertTrue(
        exit.causeOption.contains(Cause.fail(original) ++ Cause.fail(observation)),
        count == 1
      )
    },
    test("observer cleanup defects are preserved and do not cause a second observation") {
      val defect = new IllegalStateException("observer cleanup defect")
      for
        count  <- Ref.make(0)
        events <- Ref.make(Chunk.empty[AgentEvent])
        agent <- ReActAgent.make(
          client = success,
          completionObserver = TurnCompletionObserver(_ =>
            count.update(_ + 1) *> ZIO.addFinalizer(ZIO.die(defect)).unit
          )
        )
        exit <- agent
          .streamEvents(Msg.user("q"))
          .tap(event => events.update(_ :+ event))
          .runDrain
          .exit
        observed  <- count.get
        published <- events.get
      yield assertTrue(
        exit.causeOption.exists(_.defects.contains(defect)),
        observed == 1,
        !published.exists(_.isInstanceOf[AgentEvent.AgentResultEvent])
      )
    },
    test(
      "run lock remains held through observer cleanup and cancelled queued calls are not observed"
    ) {
      for
        entered  <- Promise.make[Nothing, Unit]
        release  <- Promise.make[Nothing, Unit]
        observed <- Ref.make(0)
        agent <- ReActAgent
          .builder()
          .client(success)
          .completionObserver(
            TurnCompletionObserver(_ =>
              observed.update(_ + 1) *> ZIO.addFinalizer(entered.succeed(()) *> release.await).unit
            )
          )
          .build
        first     <- agent.run(Msg.user("first")).forkScoped
        _         <- entered.await
        queued    <- agent.run(Msg.user("queued")).forkScoped
        _         <- TestClock.adjust(0.seconds)
        pending   <- queued.poll
        cancelled <- queued.interrupt
        during    <- observed.get
        history   <- agent.history
        _         <- release.succeed(())
        _         <- first.join
      yield assertTrue(
        pending.isEmpty,
        cancelled.isInterrupted,
        during == 1,
        !history.exists(_.text == "queued")
      )
    }
  )
