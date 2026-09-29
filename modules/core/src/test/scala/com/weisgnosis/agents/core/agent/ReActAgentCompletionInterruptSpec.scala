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
import com.weisgnosis.agents.core.tool.{Tool, ToolDef, Toolkit}
import zio.*
import zio.json.ast.Json
import zio.test.*

object ReActAgentCompletionInterruptSpec extends ZIOSpecDefault:
  private val client: LlmClient = LlmClient.mockFunction((_, _) =>
    TurnOutput.ToolCalls(Msg.assistant(Chunk(ContentBlock.ToolUse("call", "wait", Json.Obj()))))
  )

  private def waitingTools(entered: Promise[Nothing, Unit], cleanup: UIO[Unit]): Toolkit =
    val tool = new Tool[Any, Json, String]:
      def definition: ToolDef = ToolDef("wait", "Wait for cancellation", Json.Obj())
      def decodeInput(raw: Json): Either[String, Json] = Right(raw)
      def encodeOutput(value: String): String          = value
      def execute(input: Json): ZIO[Any, Throwable, String] =
        (entered.succeed(()) *> ZIO.never).ensuring(cleanup)
    Toolkit(Map("wait" -> tool))

  private def recovered(history: Chunk[Msg]): Boolean =
    ToolHistory.inspect(history).isComplete && history
      .flatMap(_.toolResults)
      .exists(_.state == ToolResultState.Error) && history.lastOption.exists(
      _.generateReason.contains("interrupted")
    )

  def spec: Spec[TestEnvironment & Scope, Any] = suite("ReActAgentCompletionInterruptSpec")(
    test("external cancellation observes recovered canonical history after tool cleanup") {
      for
        entered  <- Promise.make[Nothing, Unit]
        cleaned  <- Ref.make(false)
        observed <- Ref.make(Chunk.empty[(TurnCompletion, Boolean)])
        agent <- ReActAgent.make(
          client = client,
          toolkit = waitingTools(entered, cleaned.set(true)),
          completionObserver = TurnCompletionObserver(value =>
            cleaned.get.flatMap(done => observed.update(_ :+ (value -> done)))
          )
        )
        fiber   <- agent.run(Msg.user("q")).forkScoped
        _       <- entered.await
        exit    <- fiber.interrupt
        history <- agent.history
        seen    <- observed.get
      yield assertTrue(
        exit.isInterrupted,
        seen.size == 1,
        recovered(history),
        seen.forall { case (value, done) =>
          done && value.history == history && value.outcome.isInterrupted
        }
      )
    },
    test("cooperative interruption observes the returned recovery result once") {
      for
        entered <- Promise.make[Nothing, Unit]
        seen    <- Ref.make(Chunk.empty[TurnCompletion])
        agent <- ReActAgent.make(
          client = client,
          toolkit = waitingTools(entered, ZIO.unit),
          completionObserver = TurnCompletionObserver(value => seen.update(_ :+ value))
        )
        fiber    <- agent.run(Msg.user("q")).forkScoped
        _        <- entered.await
        _        <- agent.interrupt
        result   <- fiber.join
        observed <- seen.get
      yield assertTrue(
        result.isInterrupted,
        recovered(result.history),
        observed.size == 1,
        observed.forall(_.outcome == Exit.succeed(result))
      )
    },
    test("timeout observes recovered state and preserves the typed timeout cause") {
      for
        entered <- Promise.make[Nothing, Unit]
        seen    <- Ref.make(Chunk.empty[TurnCompletion])
        agent <- ReActAgent.make(
          config = AgentConfig(timeout = 5.seconds),
          client = client,
          toolkit = waitingTools(entered, ZIO.unit),
          completionObserver = TurnCompletionObserver(value => seen.update(_ :+ value))
        )
        fiber    <- agent.run(Msg.user("q")).forkScoped
        _        <- entered.await
        _        <- TestClock.adjust(5.seconds)
        exit     <- fiber.await
        observed <- seen.get
      yield assertTrue(
        exit.causeOption.exists(_.failureOption.exists(_.isInstanceOf[AgentError.TimeoutError])),
        observed.size == 1,
        observed.forall(value =>
          recovered(value.history) && value.outcome.causeOption == exit.causeOption
        )
      )
    },
    test("mixed interruption and tool cleanup defect remain in the original observed cause") {
      val defect = new IllegalStateException("tool cleanup defect")
      for
        entered <- Promise.make[Nothing, Unit]
        seen    <- Ref.make(Chunk.empty[TurnCompletion])
        agent <- ReActAgent.make(
          client = client,
          toolkit = waitingTools(entered, ZIO.die(defect)),
          completionObserver = TurnCompletionObserver(value => seen.update(_ :+ value))
        )
        fiber    <- agent.run(Msg.user("q")).forkScoped
        _        <- entered.await
        exit     <- fiber.interrupt
        history  <- agent.history
        observed <- seen.get
      yield assertTrue(
        exit.isInterrupted,
        exit.causeOption.exists(_.defects.contains(defect)),
        observed.size == 1,
        observed.forall(value =>
          value.history == history && value.outcome.causeOption.exists(cause =>
            cause.isInterrupted && cause.defects.contains(defect)
          )
        )
      )
    },
    test("observer failure after cancellation adds its cause without erasing interruption") {
      val failure = AgentError.CompletionObservationError("write failed")
      for
        entered <- Promise.make[Nothing, Unit]
        calls   <- Ref.make(0)
        agent <- ReActAgent.make(
          client = client,
          toolkit = waitingTools(entered, ZIO.unit),
          completionObserver = TurnCompletionObserver(_ => calls.update(_ + 1) *> ZIO.fail(failure))
        )
        fiber <- agent.run(Msg.user("q")).forkScoped
        _     <- entered.await
        exit  <- fiber.interrupt
        count <- calls.get
      yield assertTrue(
        exit.isInterrupted,
        exit.causeOption.exists(_.failures.contains(failure)),
        count == 1
      )
    },
    test("required observation finishes before cancellation completes") {
      for
        entered   <- Promise.make[Nothing, Unit]
        observing <- Promise.make[Nothing, Unit]
        release   <- Promise.make[Nothing, Unit]
        cleaned   <- Ref.make(false)
        agent <- ReActAgent.make(
          client = client,
          toolkit = waitingTools(entered, ZIO.unit),
          completionObserver = TurnCompletionObserver(_ =>
            ZIO.addFinalizer(cleaned.set(true)) *> observing.succeed(()) *> release.await
          )
        )
        fiber        <- agent.run(Msg.user("q")).forkScoped
        _            <- entered.await
        cancellation <- fiber.interrupt.forkScoped
        _            <- observing.await
        pending      <- cancellation.poll
        _            <- release.succeed(())
        exit         <- cancellation.join
        done         <- cleaned.get
      yield assertTrue(pending.isEmpty, exit.isInterrupted, done)
    },
    test(
      "early ordinary stream termination observes the interrupted invocation before scope release"
    ) {
      for
        entered <- Promise.make[Nothing, Unit]
        seen    <- Ref.make(Chunk.empty[TurnCompletion])
        agent <- ReActAgent.make(
          client = client,
          toolkit = waitingTools(entered, ZIO.unit),
          completionObserver = TurnCompletionObserver(value => seen.update(_ :+ value))
        )
        _ <- agent
          .streamEvents(Msg.user("q"))
          .takeUntil(_.isInstanceOf[AgentEvent.ToolResultStartEvent])
          .runDrain
        observed <- seen.get
        history  <- agent.history
      yield assertTrue(
        observed.size == 1,
        observed.forall(value => value.history == history && value.outcome.isInterrupted),
        ToolHistory.inspect(history).isComplete
      )
    },
    test("cancellation during successful observation waits for cleanup and remains interruption") {
      for
        entered <- Promise.make[Nothing, Unit]
        release <- Promise.make[Nothing, Unit]
        cleaned <- Ref.make(false)
        seen    <- Ref.make(Chunk.empty[TurnCompletion])
        agent <- ReActAgent.make(
          client = LlmClient.mockFunction((_, _) => TurnOutput.FinalText(Msg.assistant("done"))),
          completionObserver = TurnCompletionObserver(value =>
            seen.update(_ :+ value) *> ZIO.addFinalizer(cleaned.set(true)) *> entered.succeed(
              ()
            ) *> release.await
          )
        )
        fiber        <- agent.run(Msg.user("q")).forkScoped
        _            <- entered.await
        cancellation <- fiber.interrupt.forkScoped
        _            <- TestClock.adjust(0.seconds)
        pending      <- cancellation.poll
        _            <- release.succeed(())
        exit         <- cancellation.join
        done         <- cleaned.get
        observed     <- seen.get
      yield assertTrue(
        pending.isEmpty,
        exit.isInterrupted,
        done,
        observed.size == 1,
        observed.forall(_.outcome.isSuccess)
      )
    },
    test(
      "an early stream consumer cannot receive a later observer failure from the abandoned queue"
    ) {
      for
        calls <- Ref.make(0)
        agent <- ReActAgent.make(
          client = LlmClient.mockEffectful((_, _, _) => ZIO.never),
          completionObserver = TurnCompletionObserver(_ =>
            calls.update(_ + 1) *> ZIO.fail(AgentError.CompletionObservationError("storage failed"))
          )
        )
        exit  <- agent.streamEvents(Msg.user("q")).take(1).runDrain.exit
        count <- calls.get
      yield assertTrue(exit.isSuccess, count == 1)
    },
    test("early structured stream termination invokes its completion observer once") {
      for
        seen <- Ref.make(Chunk.empty[TurnCompletion])
        waiting = LlmClient.mockEffectful((_, _, _) => ZIO.never)
        agent <- ReActAgent.make(
          client = waiting,
          completionObserver = TurnCompletionObserver(value => seen.update(_ :+ value))
        )
        _ <- agent
          .streamStructuredEvents[Int](
            Chunk(Msg.user("q")),
            Json.Obj("type" -> Json.Str("integer"))
          )
          .take(1)
          .runDrain
        observed <- seen.get
      yield assertTrue(observed.size == 1, observed.forall(_.outcome.isInterrupted))
    }
  )
