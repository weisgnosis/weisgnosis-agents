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

import com.weisgnosis.agents.core.client.{ScriptedLlmClient, TurnOutput}
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.event.AgentEvent
import com.weisgnosis.agents.core.model.*
import com.weisgnosis.agents.core.tool.{Tool, ToolDef, Toolkit}
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

object StructuredRepairLifecycleSpec extends ZIOSpecDefault:
  final case class Answer(value: Int) derives JsonCodec

  private val input: Chunk[Msg] = Chunk(Msg.user("Read evidence and return a nonnegative answer"))
  private val schema: Json = Json.Obj(
    "type" -> Json.Str("object"),
    "properties" -> Json.Obj(
      "value" -> Json.Obj("type" -> Json.Str("integer"), "minimum" -> Json.Num(0))
    ),
    "required"             -> Json.Arr(Json.Str("value")),
    "additionalProperties" -> Json.Bool(false)
  )
  private val repair: StructuredRepairPolicy = StructuredRepairPolicy.OneOutputOnlyRequest
  private val invalid: TurnOutput            = TurnOutput.FinalText(Msg.assistant("{\"value\":-1}"))
  private val valid: TurnOutput              = TurnOutput.FinalText(Msg.assistant("{\"value\":1}"))

  private def guardedFork[R, E, A](
      effect: ZIO[R & Scope, E, A],
      release: Promise[Nothing, Unit]
  ): ZIO[R & Scope, Nothing, Fiber.Runtime[E, A]] =
    ZIO.uninterruptibleMask { restore =>
      for
        fiber <- restore(effect).forkScoped
        _     <- ZIO.addFinalizer(release.succeed(()).unit)
      yield fiber
    }

  def spec: Spec[TestEnvironment & Scope, Any] = suite("Structured repair lifecycle")(
    test("stream cancellation joins correction cleanup and preserves completed application work") {
      ZIO.scoped {
        for
          entered         <- Promise.make[Nothing, Unit]
          cleanupStarted  <- Promise.make[Nothing, Unit]
          release         <- Promise.make[Nothing, Unit]
          cleaned         <- Ref.make(false)
          executions      <- Ref.make(0)
          terminalResults <- Ref.make(0)
          tool = Tool.make(ToolDef("read_evidence", "Read evidence", Json.Obj()))(_ =>
            executions.update(_ + 1).as("checked evidence")
          )
          toolkit <- ZIO.fromEither(Toolkit.empty.register(tool))
          model <- ScriptedLlmClient.makeEffectful(
            Chunk(
              ZIO.succeed(
                TurnOutput.ToolCalls(
                  Msg.assistant(
                    content = Chunk(ContentBlock.ToolUse("evidence", "read_evidence", Json.Obj())),
                    name = None
                  )
                )
              ),
              ZIO.succeed(invalid),
              ZIO.acquireRelease(ZIO.unit)(_ =>
                cleanupStarted.succeed(()) *> release.await *> cleaned.set(true)
              ) *> entered.succeed(()) *> ZIO.never
            ),
            4
          )
          agent <- ReActAgent.make(client = model, toolkit = toolkit)
          worker <- guardedFork(
            agent
              .streamStructuredWithResult[Answer](input, schema, StructuredMode.Native, repair)
              .tap {
                case AgentRunUpdate.Result(_) => terminalResults.update(_ + 1)
                case AgentRunUpdate.Event(_: AgentEvent.AgentResultEvent) =>
                  terminalResults.update(_ + 1)
                case _ => ZIO.unit
              }
              .runDrain,
            release
          )
          _          <- entered.await
          cancelling <- guardedFork(worker.interrupt, release)
          _          <- cleanupStarted.await
          held       <- cancelling.poll
          before     <- cleaned.get
          _          <- release.succeed(())
          outcome    <- cancelling.join
          after      <- cleaned.get
          history    <- agent.history
          requests   <- model.requests
          count      <- executions.get
          published  <- terminalResults.get
          _          <- model.verifyConsumed
        yield assertTrue(
          held.isEmpty,
          !before,
          after,
          outcome.isInterrupted,
          published == 0,
          count == 1,
          requests.size == 3,
          requests.lastOption.exists(_.input.tools.isEmpty),
          history.flatMap(_.toolUses).map(_.id) == Chunk("evidence"),
          history
            .flatMap(_.toolResults)
            .exists(result =>
              result.id == "evidence" && result.state == ToolResultState.Success && result.text == "checked evidence"
            ),
          history.count(_.generateReason.contains("interrupted")) == 1,
          ToolHistory.inspect(history).isComplete
        )
      }
    },
    test(
      "correction uses the initial invocation deadline and joins cleanup before timeout returns"
    ) {
      ZIO.scoped {
        for
          initialEntered    <- Promise.make[Nothing, Unit]
          correctionEntered <- Promise.make[Nothing, Unit]
          cleanupStarted    <- Promise.make[Nothing, Unit]
          release           <- Promise.make[Nothing, Unit]
          cleaned           <- Ref.make(false)
          model <- ScriptedLlmClient.makeEffectful(
            Chunk(
              initialEntered.succeed(()) *> ZIO.sleep(4.seconds).as(invalid),
              ZIO.acquireRelease(ZIO.unit)(_ =>
                cleanupStarted.succeed(()) *> release.await *> cleaned.set(true)
              ) *> correctionEntered.succeed(()) *> ZIO.never
            ),
            3
          )
          agent <- ReActAgent.make(AgentConfig(timeout = 5.seconds), client = model)
          worker <- guardedFork(
            agent.runStructured[Answer](input, schema, StructuredMode.Native, repair),
            release
          )
          _          <- initialEntered.await
          _          <- TestClock.adjust(4.seconds)
          _          <- correctionEntered.await
          adjustment <- guardedFork(TestClock.adjust(1.second), release)
          _          <- cleanupStarted.await
          held       <- worker.poll
          before     <- cleaned.get
          _          <- release.succeed(())
          _          <- adjustment.join
          outcome    <- worker.await
          after      <- cleaned.get
          history    <- agent.history
          requests   <- model.requests
          _          <- model.verifyConsumed
        yield assertTrue(
          held.isEmpty,
          !before,
          after,
          outcome.causeOption.exists(_.failures.exists(_.isInstanceOf[AgentError.TimeoutError])),
          requests.size == 2,
          history.count(_.generateReason.contains("interrupted")) == 1,
          ToolHistory.inspect(history).isComplete
        )
      }
    },
    test("runtime iteration and empty-response limits cannot admit a formatting correction") {
      val cases = Chunk[TurnOutput](
        TurnOutput.ToolCalls(
          Msg.assistant(Chunk(ContentBlock.ToolUse("limit", "read_evidence", Json.Obj())))
        ),
        TurnOutput.ThinkingOnly(Msg.assistant(Chunk(ContentBlock.Thinking("internal reasoning"))))
      )
      ZIO
        .foreach(cases) { output =>
          for
            model <- ScriptedLlmClient.make(Chunk(output), 2)
            tool = Tool.make(ToolDef("read_evidence", "Read evidence", Json.Obj()))(_ =>
              ZIO.succeed("evidence")
            )
            agent <- ReActAgent.make(
              AgentConfig(maxIterations = 1, emptyThinkingRetryLimit = 0),
              client = model,
              toolkit = Toolkit.single(tool)
            )
            result <- agent
              .runStructured[Answer](input, schema, StructuredMode.Native, repair)
              .either
            requests <- model.requests
            _        <- model.verifyConsumed
          yield assertTrue(result.isLeft, requests.size == 1)
        }
        .map(_.foldLeft(assertTrue(true))(_ && _))
    },
    test("correction model failure preserves its complete failure cause without another request") {
      val failure = AgentError.ModelError(503, "Correction unavailable")
      val defect  = new IllegalStateException("Correction decoder defect")
      val cause   = Cause.fail(failure) ++ Cause.die(defect)
      for
        model <- ScriptedLlmClient.makeEffectful(
          Chunk(ZIO.succeed(invalid), ZIO.refailCause(cause)),
          3
        )
        completions <- Ref.make(Chunk.empty[Exit[AgentError, AgentResult]])
        agent <- ReActAgent.make(
          client = model,
          completionObserver =
            TurnCompletionObserver(value => completions.update(_ :+ value.outcome))
        )
        outcome  <- agent.runStructured[Answer](input, schema, StructuredMode.Native, repair).exit
        requests <- model.requests
        observed <- completions.get
        _        <- model.verifyConsumed
      yield assertTrue(
        outcome == Exit.failCause(cause),
        observed == Chunk(Exit.failCause(cause)),
        requests.size == 2
      )
    },
    test("host rejection of the initial invalid candidate never starts correction") {
      val rejection = AgentError.CompletionObservationError("Initial response observation rejected")
      for
        model        <- ScriptedLlmClient.make(Chunk(invalid, valid), 3)
        observations <- Ref.make(0)
        agent <- ReActAgent.make(
          client = model,
          modelResponseObserver =
            ModelResponseObserver(_ => observations.update(_ + 1) *> ZIO.fail(rejection))
        )
        outcome   <- agent.runStructured[Answer](input, schema, StructuredMode.Native, repair).exit
        requests  <- model.requests
        remaining <- model.remaining
        count     <- observations.get
      yield assertTrue(
        outcome == Exit.fail(rejection),
        requests.size == 1,
        remaining == 1,
        count == 1
      )
    },
    test(
      "provider interruption during correction appends one recovery and emits no terminal result"
    ) {
      for
        model <- ScriptedLlmClient.make(
          Chunk(invalid, TurnOutput.Interrupted(Msg.assistant("Partial correction"))),
          3
        )
        terminalResults <- Ref.make(0)
        agent           <- ReActAgent.make(client = model)
        outcome <- agent
          .streamStructuredWithResult[Answer](input, schema, StructuredMode.Native, repair)
          .tap {
            case AgentRunUpdate.Result(_) => terminalResults.update(_ + 1)
            case AgentRunUpdate.Event(_: AgentEvent.AgentResultEvent) =>
              terminalResults.update(_ + 1)
            case _ => ZIO.unit
          }
          .runDrain
          .exit
        history   <- agent.history
        requests  <- model.requests
        published <- terminalResults.get
        _         <- model.verifyConsumed
      yield assertTrue(
        outcome.causeOption.exists(_.failures.exists(_.isInstanceOf[AgentError.Interrupted])),
        published == 0,
        requests.size == 2,
        history.count(_.generateReason.contains("interrupted")) == 1,
        history.lastOption.exists(_.generateReason.contains("interrupted")),
        ToolHistory.inspect(history).isComplete
      )
    },
    test("host rejection of the correction response is terminal and never becomes another repair") {
      val rejection = AgentError.CompletionObservationError("Correction observation rejected")
      for
        model    <- ScriptedLlmClient.make(Chunk(invalid, valid), 3)
        observed <- Ref.make(0)
        agent <- ReActAgent.make(
          client = model,
          modelResponseObserver = ModelResponseObserver(response =>
            observed.update(_ + 1) *> ZIO
              .fail(rejection)
              .when(response.message.text == valid.message.text)
              .unit
          )
        )
        outcome  <- agent.runStructured[Answer](input, schema, StructuredMode.Native, repair).exit
        requests <- model.requests
        count    <- observed.get
        _        <- model.verifyConsumed
      yield assertTrue(outcome == Exit.fail(rejection), requests.size == 2, count == 2)
    }
  ) @@ TestAspect.timeout(20.seconds)
