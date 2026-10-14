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
import com.weisgnosis.agents.core.event.{AgentEvent, EventAdmission, EventDeliveryBudget}
import com.weisgnosis.agents.core.model.{ContentBlock, Msg, ToolResultState}
import com.weisgnosis.agents.core.tool.*
import zio.json.ast.Json
import zio.*
import zio.test.*

object ReActAgentDeliverySpec extends ZIOSpecDefault:
  private def budget: IO[AgentError, EventDeliveryBudget] =
    ZIO.fromEither(EventDeliveryBudget.fromLimits(2, 8192L, 4096L))

  def spec: Spec[TestEnvironment & Scope, Any] =
    suite("bounded public diagnostic delivery")(
      test("overflow waits for joined execution and preserves both outcome authorities"):
        ZIO
          .foreach(Chunk(false, true)): executionFails =>
            ZIO.scoped:
              for
                limits    <- budget
                published <- Promise.make[Nothing, Unit]
                release   <- Promise.make[Nothing, Unit]
                cleaned   <- Ref.make(false)
                defect = new IllegalStateException("model cleanup")
                receipt <- Ref.make[EventAdmission](EventAdmission.Closed)
                client = LlmClient.mockEffectful((_, _, publisher) =>
                  (publisher.publish(AgentEvent.HintBlockEvent("x" * 5000)).flatMap(receipt.set) *>
                    publisher.publishAll(Chunk.fill(8)(AgentEvent.HintBlockEvent("progress"))) *>
                    published.succeed(()) *> release.await *>
                    (if executionFails then
                       ZIO.fail(AgentError.ModelError(503, "model unavailable"))
                     else ZIO.succeed(TurnOutput.FinalText(Msg.assistant("done")))))
                    .ensuring(cleaned.set(true) *> ZIO.when(executionFails)(ZIO.die(defect)))
                )
                agent <- ReActAgent
                  .make(client = client, config = AgentConfig(eventDeliveryBudget = limits))
                invocation <- agent.prepareRun(Msg.user("work"))
                reader     <- invocation.streamWithResult.runCollect.exit.forkScoped
                _          <- published.await
                pending    <- reader.poll
                _          <- release.succeed(())
                execution  <- invocation.termination
                delivered  <- reader.join
                history    <- agent.history
                cleanup    <- cleaned.get
                admission  <- receipt.get
              yield assertTrue(
                pending.isEmpty,
                admission.isInstanceOf[EventAdmission.Rejected],
                cleanup,
                execution.isFailure == executionFails,
                delivered.causeOption
                  .exists(_.failures.exists(_.isInstanceOf[AgentError.EventDeliveryError])),
                delivered.causeOption.exists(
                  _.failures.exists(_.isInstanceOf[AgentError.ModelError])
                ) == executionFails,
                delivered.causeOption.exists(_.defects.contains(defect)) == executionFails,
                delivered.causeOption.exists(
                  _.failures.lastOption.exists(_.isInstanceOf[AgentError.EventDeliveryError])
                ),
                !executionFails || delivered.causeOption
                  .exists(_.failures.headOption.exists(_.isInstanceOf[AgentError.ModelError])),
                executionFails || history.exists(_.getTextContent == "done"),
                !history.exists(_.metadata.contains("agentscope_generate_reason"))
              )
          .map(results => results.foldLeft(assertTrue(true))(_ && _))
      ,
      test("overflowing tool progress preserves the completed result and next model input"):
        ZIO.scoped:
          for
            limits    <- budget
            turns     <- Ref.make(0)
            calls     <- Ref.make(0)
            nextInput <- Ref.make(Chunk.empty[Msg])
            lease     <- Ref.make(Option.empty[ToolEventPort])
            tool = new Tool[Any, Json, String]:
              def definition: ToolDef = ToolDef("inspect", "Inspect data", Json.Obj())
              def decodeInput(raw: Json): Either[String, Json] = Right(raw)
              def encodeOutput(value: String): String          = value
              def execute(input: Json): Task[String]           = ZIO.succeed("detached")
              override def executeWithContext(
                  input: Json,
                  invocation: ToolInvocation
              ): Task[String] =
                invocation match
                  case ToolInvocation.Detached => execute(input)
                  case ToolInvocation.Active(_, _, _, events) =>
                    calls.update(_ + 1) *> lease.set(Some(events)) *>
                      events.publish(AgentEvent.HintBlockEvent("x" * 5000)).as("verified")
            client = LlmClient.mockEffectful((history, _, _) =>
              turns
                .updateAndGet(_ + 1)
                .flatMap:
                  case 1 =>
                    ZIO.succeed(
                      TurnOutput.ToolCalls(
                        Msg.assistant(
                          Chunk(
                            ContentBlock.ToolUse("call", "inspect", Json.Obj())
                          )
                        )
                      )
                    )
                  case _ => nextInput.set(history).as(TurnOutput.FinalText(Msg.assistant("done")))
            )
            agent <- ReActAgent.make(
              client = client,
              toolkit = Toolkit.single(tool),
              config = AgentConfig(eventDeliveryBudget = limits)
            )
            delivery <- agent.streamWithResult(Msg.user("work")).runCollect.exit
            history  <- agent.history
            input    <- nextInput.get
            count    <- calls.get
            port     <- lease.get
            late     <- ZIO.foreach(port)(_.publish(AgentEvent.HintBlockEvent("late")))
          yield assertTrue(
            delivery.causeOption.exists(
              _.failures.exists(_.isInstanceOf[AgentError.EventDeliveryError])
            ),
            count == 1,
            input
              .flatMap(_.toolResults)
              .exists(result =>
                result.text == "verified" && result.state == ToolResultState.Success
              ),
            history.flatMap(_.toolResults).map(_.text) == Chunk("verified"),
            history.lastOption.exists(_.getTextContent == "done"),
            !history.exists(_.metadata.contains("agentscope_generate_reason")),
            late.contains(ToolEventDelivery.Closed)
          )
      ,
      test("an early ordinary reader joins active cleanup even after diagnostic overflow"):
        ZIO.scoped:
          for
            limits    <- budget
            published <- Promise.make[Nothing, Unit]
            cleaned   <- Ref.make(false)
            client = LlmClient.mockEffectful((_, _, publisher) =>
              (publisher.publish(AgentEvent.HintBlockEvent("x" * 5000)) *>
                published.succeed(()) *> ZIO.never).ensuring(cleaned.set(true))
            )
            agent <- ReActAgent.make(
              client = client,
              config = AgentConfig(eventDeliveryBudget = limits)
            )
            prefix <- agent
              .streamWithResult(Msg.user("work"))
              .tap(_ => published.await)
              .take(1)
              .runCollect
            cleanup <- cleaned.get
          yield assertTrue(prefix.size == 1, cleanup)
      ,
      test("resumed execution commits its tool result despite diagnostic overflow"):
        ZIO.scoped:
          for
            limits <- budget
            turns  <- Ref.make(0)
            calls  <- Ref.make(0)
            tool = Tool.make(ToolDef("inspect", "Inspect data", Json.Obj()))(_ =>
              calls.update(_ + 1).as("verified")
            )
            client = LlmClient.mockEffectful((_, _, publisher) =>
              turns
                .updateAndGet(_ + 1)
                .flatMap:
                  case 1 =>
                    ZIO.succeed(
                      TurnOutput.ToolCalls(
                        Msg.assistant(
                          Chunk(
                            ContentBlock.ToolUse("call", "inspect", Json.Obj())
                          )
                        )
                      )
                    )
                  case _ =>
                    publisher
                      .publish(AgentEvent.HintBlockEvent("x" * 5000))
                      .as(TurnOutput.FinalText(Msg.assistant("done")))
            )
            pause = PostReasoningHook((_, message) =>
              ZIO.succeed(
                if message.toolUses.nonEmpty then ReasoningDecision.Suspend("review")
                else ReasoningDecision.Keep
              )
            )
            agent <- ReActAgent.make(
              client = client,
              toolkit = Toolkit.single(tool),
              postReasoningHook = pause,
              config = AgentConfig(eventDeliveryBudget = limits)
            )
            suspended <- agent.run(Msg.user("work"))
            delivery  <- agent.streamResumeWithResult().runCollect.exit
            history   <- agent.history
            count     <- calls.get
          yield assertTrue(
            suspended.isSuspended,
            delivery.causeOption.exists(
              _.failures.exists(_.isInstanceOf[AgentError.EventDeliveryError])
            ),
            count == 1,
            history.flatMap(_.toolResults).map(_.text) == Chunk("verified"),
            history.lastOption.exists(_.getTextContent == "done")
          )
      ,
      test("structured and event-only streams retain canonical completion on overflow"):
        ZIO.scoped:
          for
            limits <- budget
            client = LlmClient.mockEffectful((_, _, publisher) =>
              publisher
                .publish(AgentEvent.HintBlockEvent("x" * 5000))
                .as(
                  TurnOutput.ToolCalls(
                    Msg.assistant(
                      Chunk(
                        ContentBlock.ToolUse(
                          "format",
                          "generate_response",
                          Json.Obj("response" -> Json.Num(7))
                        )
                      )
                    )
                  )
                )
            )
            agent <- ReActAgent.make(
              client = client,
              config = AgentConfig(eventDeliveryBudget = limits)
            )
            delivery <- agent
              .streamStructuredWithResult[Int](
                Chunk(Msg.user("number")),
                Json.Obj("type" -> Json.Str("integer")),
                StructuredMode.SyntheticTool
              )
              .runCollect
              .exit
            history <- agent.history
            textClient = LlmClient.mockEffectful((_, _, publisher) =>
              publisher
                .publish(AgentEvent.HintBlockEvent("x" * 5000))
                .as(TurnOutput.FinalText(Msg.assistant("done")))
            )
            textAgent <- ReActAgent.make(
              client = textClient,
              config = AgentConfig(eventDeliveryBudget = limits)
            )
            events      <- textAgent.streamEvents(Msg.user("work")).runCollect.exit
            textHistory <- textAgent.history
          yield assertTrue(
            delivery.causeOption.exists(
              _.failures.exists(_.isInstanceOf[AgentError.EventDeliveryError])
            ),
            history.lastOption.exists(_.getStructuredData[Int] == Right(7)),
            events.causeOption.exists(
              _.failures.exists(_.isInstanceOf[AgentError.EventDeliveryError])
            ),
            textHistory.lastOption.exists(_.getTextContent == "done")
          )
      ,
      test("cancelling an overflowing attached reader joins active model cleanup"):
        ZIO.scoped:
          for
            limits    <- budget
            published <- Promise.make[Nothing, Unit]
            cleaned   <- Ref.make(false)
            client = LlmClient.mockEffectful((_, _, publisher) =>
              (publisher.publishAll(Chunk.fill(8)(AgentEvent.HintBlockEvent("progress"))) *>
                published.succeed(()) *> ZIO.never).ensuring(cleaned.set(true))
            )
            agent <- ReActAgent.make(
              client = client,
              config = AgentConfig(eventDeliveryBudget = limits)
            )
            invocation <- agent.prepareRun(Msg.user("work"))
            reader     <- invocation.streamWithResult.tap(_ => ZIO.never).runCollect.forkScoped
            _          <- published.await
            readerExit <- reader.interrupt
            execution  <- invocation.termination
            cleanup    <- cleaned.get
          yield assertTrue(readerExit.isInterrupted, execution.isInterrupted, cleanup)
    ) @@ TestAspect.timeout(10.seconds)
