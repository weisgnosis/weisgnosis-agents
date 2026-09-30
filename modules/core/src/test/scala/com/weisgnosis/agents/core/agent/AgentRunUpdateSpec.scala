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
import com.weisgnosis.agents.core.model.{ChatUsage, ContentBlock, Msg, Role}
import com.weisgnosis.agents.core.tool.{Tool, ToolDef, Toolkit}
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

object AgentRunUpdateSpec extends ZIOSpecDefault:
  final private case class Answer(value: Int) derives JsonCodec
  private val schema: Json = Json.Obj(
    "type"       -> Json.Str("object"),
    "properties" -> Json.Obj("value" -> Json.Obj("type" -> Json.Str("integer"))),
    "required"   -> Json.Arr(Json.Str("value"))
  )
  private enum Failure:
    case Decode, Observe
  private enum Consumer:
    case Early, Cancelled

  private def events[A](updates: Chunk[AgentRunUpdate[A]]): Chunk[AgentEvent] =
    updates.collect { case AgentRunUpdate.Event(event) => event }

  private def results[A](updates: Chunk[AgentRunUpdate[A]]): Chunk[A] =
    updates.collect { case AgentRunUpdate.Result(result) => result }

  def spec: Spec[TestEnvironment & Scope, Any] = suite("AgentRunUpdateSpec")(
    test("one ordinary execution emits its complete result after events and scoped cleanup") {
      val firstUsage = ChatUsage(inputTokens = 2, outputTokens = 3)
      val lastUsage  = ChatUsage(inputTokens = 5, outputTokens = 7)
      for
        calls                 <- Ref.make(0)
        cleaned               <- Ref.make(0)
        observed              <- Ref.make(0)
        observerClosed        <- Ref.make(0)
        deliveredAfterCleanup <- Ref.make(false)
        model = LlmClient.mockEffectful((history, _, _) =>
          ZIO
            .acquireRelease(calls.update(_ + 1))(_ => cleaned.update(_ + 1))
            .as(
              if history.lastOption.exists(_.role == Role.User) then
                TurnOutput.ToolCalls(
                  Msg
                    .assistant(Chunk(ContentBlock.ToolUse("echo", "echo", Json.Obj())))
                    .withUsage(firstUsage)
                )
              else TurnOutput.FinalText(Msg.assistant("done").withUsage(lastUsage))
            )
        )
        tools = Toolkit(
          Map("echo" -> Tool.make(ToolDef("echo", "Echo", Json.Obj()))(_ => ZIO.succeed("echoed")))
        )
        agent <- ReActAgent.make(
          client = model,
          toolkit = tools,
          completionObserver = TurnCompletionObserver(_ =>
            ZIO.acquireRelease(observed.update(_ + 1))(_ => observerClosed.update(_ + 1))
          )
        )
        updates <- agent
          .streamWithResult(Msg.user("work"))
          .tap {
            case AgentRunUpdate.Event(_) => ZIO.unit
            case AgentRunUpdate.Result(_) =>
              cleaned.get.zip(observerClosed.get).flatMap { case (models, observers) =>
                deliveredAfterCleanup.set(models == 2 && observers == 1)
              }
          }
          .runCollect
        history      <- agent.history
        count        <- calls.get
        observations <- observed.get
        released     <- deliveredAfterCleanup.get
        completed = results(updates)
        published = events(updates)
        entered <- Promise.make[Nothing, Unit]
        interruptedAgent <- ReActAgent.make(client =
          LlmClient.mockEffectful((_, _, _) => entered.succeed(()) *> ZIO.never)
        )
        consuming <- interruptedAgent
          .streamWithResult(Msg.user("interrupted work"))
          .runCollect
          .forkScoped
        _                  <- entered.await
        _                  <- interruptedAgent.interrupt
        interruptedUpdates <- consuming.join
        recoveredHistory   <- interruptedAgent.history
        interruptedResults = results(interruptedUpdates)
      yield assertTrue(
        count == 2,
        observations == 1,
        released,
        completed.size == 1,
        completed.forall(result =>
          result.history == history && result.iterationCount == 2 &&
            result.state == AgentRunState.Completed && result.usage.contains(
              firstUsage.combine(lastUsage)
            ) &&
            result.reply.usage
              .contains(lastUsage) && result.history.flatMap(_.toolResults).size == 1
        ),
        published.collect { case event: AgentEvent.AgentResultEvent => event.result } == completed
          .map(_.reply),
        published.lastOption.exists(_.isInstanceOf[AgentEvent.AgentEndEvent]),
        updates.lastOption.exists {
          case AgentRunUpdate.Result(_) => true
          case _                        => false
        },
        interruptedResults.size == 1,
        interruptedResults.forall(result =>
          result.state == AgentRunState.Interrupted &&
            result.history == recoveredHistory && result.history.exists(
              _.text == "interrupted work"
            ) &&
            result.history.count(_.generateReason.contains("interrupted")) == 1
        ),
        interruptedUpdates.takeRight(2) match
          case Chunk(AgentRunUpdate.Event(_: AgentEvent.AgentEndEvent), AgentRunUpdate.Result(_)) =>
            true
          case _ => false
      )
    },
    test(
      "structured streams retain the decoded value and preserve terminal validation and compound observation causes"
    ) {
      val observerError = AgentError.CompletionObservationError("observer failed")
      val defect        = new IllegalStateException("observer cleanup defect")
      val cause         = Cause.fail(observerError) ++ Cause.die(defect)
      for
        calls <- Ref.make(0)
        agent <- ReActAgent.make(client =
          LlmClient.mockEffectful((_, _, _) =>
            calls.update(_ + 1).as(TurnOutput.FinalText(Msg.assistant("{\"value\":7}")))
          )
        )
        updates <- agent
          .streamStructuredWithResult[Answer](
            Chunk(Msg.user("answer")),
            schema,
            StructuredMode.Native
          )
          .runCollect
        history <- agent.history
        count   <- calls.get
        failures <- ZIO.foreach(Chunk(Failure.Decode, Failure.Observe))(mode =>
          for
            seen <- Ref.make(Chunk.empty[AgentRunUpdate[(AgentResult, Answer)]])
            failing <- ReActAgent.make(
              client = LlmClient.mockFunction((_, _) =>
                TurnOutput.FinalText(Msg.assistant(mode match
                  case Failure.Decode  => "{\"value\":\"wrong\"}"
                  case Failure.Observe => "{\"value\":7}"))
              ),
              completionObserver = mode match
                case Failure.Decode  => TurnCompletionObserver.ignore
                case Failure.Observe => TurnCompletionObserver(_ => ZIO.refailCause(cause))
            )
            exit <- failing
              .streamStructuredWithResult[Answer](
                Chunk(Msg.user("answer")),
                schema,
                StructuredMode.Native
              )
              .tap(update => seen.update(_ :+ update))
              .runDrain
              .exit
            actual <- seen.get
          yield assertTrue(
            results(actual).isEmpty,
            events(actual).lastOption.exists(_.isInstanceOf[AgentEvent.AgentEndEvent]),
            mode match
              case Failure.Decode =>
                exit.causeOption.exists(
                  _.failureOption.exists(_.isInstanceOf[AgentError.DeserializationError])
                )
              case Failure.Observe => exit.causeOption.contains(cause)
          )
        )
      yield assertTrue(
        count == 1,
        results(updates).size == 1,
        results(updates).forall { case (result, answer) =>
          answer == Answer(7) && result.history == history && result.reply
            .getStructuredData[Answer] == Right(answer)
        },
        events(updates).lastOption.exists(_.isInstanceOf[AgentEvent.AgentEndEvent])
      ) &&
        failures.foldLeft(assertTrue(true))(_ && _)
    },
    test("early completion and caller cancellation join owned cleanup without inventing a result") {
      ZIO
        .foreach(Chunk(Consumer.Early, Consumer.Cancelled))(mode =>
          ZIO.scoped:
            for
              entered      <- Promise.make[Nothing, Unit]
              cleaning     <- Promise.make[Nothing, Unit]
              release      <- Promise.make[Nothing, Unit]
              cleaned      <- Ref.make(false)
              observations <- Ref.make(Chunk.empty[TurnCompletion])
              seen         <- Ref.make(Chunk.empty[AgentRunUpdate[AgentResult]])
              agent <- ReActAgent.make(
                client = LlmClient.mockEffectful((_, _, hub) =>
                  ZIO.acquireRelease(ZIO.unit)(_ =>
                    cleaning.succeed(()) *> release.await *> cleaned.set(true)
                  ) *>
                    entered.succeed(()) *> hub.publish(
                      AgentEvent.TextBlockDeltaEvent("ready", 0)
                    ) *> ZIO.never
                ),
                completionObserver = TurnCompletionObserver(value =>
                  observations.update(_ :+ value) *> ZIO.fail(
                    AgentError.CompletionObservationError("abandoned observation")
                  )
                )
              )
              stream = agent
                .streamWithResult(Msg.user("work"))
                .tap(update => seen.update(_ :+ update))
              selected = mode match
                case Consumer.Early =>
                  stream.takeUntil {
                    case AgentRunUpdate.Event(_: AgentEvent.TextBlockDeltaEvent) => true
                    case _                                                       => false
                  }
                case Consumer.Cancelled => stream
              result <- (for
                running <- selected.runCollect.forkScoped
                _       <- entered.await
                stopping <- (mode match
                  case Consumer.Early     => running.await
                  case Consumer.Cancelled => running.interrupt
                ).forkScoped
                _        <- cleaning.await
                pending  <- stopping.poll
                _        <- release.succeed(())
                exit     <- stopping.join
                released <- cleaned.get
                actual   <- seen.get
                observed <- observations.get
              yield assertTrue(
                pending.isEmpty,
                released,
                results(actual).isEmpty,
                observed.size == 1,
                observed.forall(_.outcome.isInterrupted),
                mode match
                  case Consumer.Early     => exit.isSuccess
                  case Consumer.Cancelled => exit.isInterrupted
              )).ensuring(release.succeed(()))
            yield result
        )
        .map(_.foldLeft(assertTrue(true))(_ && _))
    }
  ) @@ TestAspect.timeout(20.seconds)
