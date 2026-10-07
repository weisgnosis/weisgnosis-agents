/*
 * Copyright 2024-2026 WeisGnosis (https://weisgnosis.com)
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations.
 */
package com.weisgnosis.agents.examples

import com.weisgnosis.agents.core.client.{LlmClient, LlmConfig, TurnOutput}
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.event.{AgentEvent, ModelStreamContext}
import com.weisgnosis.agents.core.model.Msg
import com.weisgnosis.agents.core.tool.ToolDef
import zio.json.ast.Json
import zio.*
import zio.test.*

object AuditedLlmClientSpec extends ZIOSpecDefault:
  private enum Response:
    case Returned, CooperativeInterruption, Failure, Defect, ObserverDefect

  private val context: ModelStreamContext = ModelStreamContext("reply", "request")
  private val output: TurnOutput          = TurnOutput.FinalText(Msg.assistant("answer"))

  def spec: Spec[TestEnvironment & Scope, Any] = suite("AuditedLlmClientSpec")(
    test(
      "one lazy delegated call preserves output and causes and is observed after request cleanup"
    ) {
      ZIO
        .foreach(Chunk.fromIterable(Response.values)): response =>
          ZIO.scoped:
            for
              calls    <- Ref.make(0)
              released <- Ref.make(false)
              records  <- Ref.make(Chunk.empty[(AuditedLlmClient.Observation, Boolean)])
              hub      <- ZIO.acquireRelease(Hub.unbounded[AgentEvent])(_.shutdown)
              captured <- Ref.make(
                Chunk.empty[
                  (Chunk[Msg], Chunk[ToolDef], LlmConfig, Hub[AgentEvent], ModelStreamContext)
                ]
              )
              history        = Chunk(Msg.user("request content"))
              tools          = Chunk(ToolDef("selected", "Selected tool", Json.Obj()))
              config         = LlmConfig("selected-model", maxTokens = Some(77))
              failure        = AgentError.ModelError(0, "expected failure")
              defect         = new IllegalStateException("expected defect")
              observerDefect = new IllegalStateException("expected observer defect")
              partial        = TurnOutput.Interrupted(Msg.assistant("partial"))
              client = new LlmClient:
                def streamTurn(
                    input: Chunk[Msg],
                    definitions: Chunk[ToolDef],
                    selected: LlmConfig,
                    events: Hub[AgentEvent],
                    correlation: ModelStreamContext
                ): ZIO[Scope, AgentError, TurnOutput] =
                  ZIO.acquireRelease(
                    calls.update(_ + 1) *>
                      captured.update(_ :+ ((input, definitions, selected, events, correlation)))
                  )(_ => released.set(true)) *> (response match
                    case Response.Returned                          => ZIO.succeed(output)
                    case Response.CooperativeInterruption           => ZIO.succeed(partial)
                    case Response.Failure | Response.ObserverDefect => ZIO.fail(failure)
                    case Response.Defect                            => ZIO.die(defect))
              wrapped = AuditedLlmClient.wrap(client)(record =>
                released.get.flatMap(closed => records.update(_ :+ (record -> closed))) *>
                  (response match
                    case Response.ObserverDefect => ZIO.die(observerDefect)
                    case _                       => ZIO.unit)
              )
              lazyCalls <- calls.get
              result <- wrapped
                .streamTurn(history, tools, config, hub, context)
                .exit
              invoked   <- calls.get
              observed  <- records.get
              delegated <- captured.get
              expected = response match
                case Response.Returned | Response.CooperativeInterruption =>
                  AuditedLlmClient.Outcome.Returned
                case _ => AuditedLlmClient.Outcome.Failed
            yield assertTrue(
              lazyCalls == 0,
              invoked == 1,
              delegated == Chunk((history, tools, config, hub, context)),
              observed == Chunk(
                AuditedLlmClient.Observation(context, Duration.Zero, expected) -> true
              ),
              response match
                case Response.Returned                => result == Exit.succeed(output)
                case Response.CooperativeInterruption => result == Exit.succeed(partial)
                case Response.ObserverDefect =>
                  result.causeOption.exists(cause =>
                    cause.failureOption.contains(failure) && cause.defects == Chunk(observerDefect)
                  )
                case Response.Failure =>
                  result.causeOption.flatMap(_.failureOption).contains(failure)
                case Response.Defect => result.causeOption.exists(_.defects == Chunk(defect))
            )
        .map(_.foldLeft(assertTrue(true))(_ && _))
    },
    test("cancelled calls join held cleanup before one timed observation and never retry") {
      ZIO.scoped:
        for
          calls          <- Ref.make(0)
          records        <- Ref.make(Chunk.empty[AuditedLlmClient.Observation])
          entered        <- Promise.make[Nothing, Unit]
          cleanupStarted <- Promise.make[Nothing, Unit]
          releaseCleanup <- Promise.make[Nothing, Unit]
          hub            <- ZIO.acquireRelease(Hub.unbounded[AgentEvent])(_.shutdown)
          client = LlmClient.mockEffectful((_, _, _) =>
            ZIO.acquireRelease(calls.update(_ + 1))(_ =>
              cleanupStarted.succeed(()).unit *> releaseCleanup.await
            ) *>
              entered.succeed(()) *> ZIO.never
          )
          wrapped = AuditedLlmClient.wrap(client)(record => records.update(_ :+ record))
          running <- wrapped
            .streamTurn(Chunk.empty, Chunk.empty, LlmConfig("test"), hub, context)
            .forkScoped
          _        <- entered.await
          stopping <- running.interrupt.forkScoped
          _        <- cleanupStarted.await
          before   <- records.get
          pending  <- running.poll
          _        <- TestClock.adjust(5.seconds)
          _        <- releaseCleanup.succeed(())
          exit     <- stopping.join
          observed <- records.get
          invoked  <- calls.get
        yield assertTrue(
          before.isEmpty,
          pending.isEmpty,
          exit.isInterrupted,
          invoked == 1,
          observed == Chunk(
            AuditedLlmClient.Observation(context, 5.seconds, AuditedLlmClient.Outcome.Cancelled)
          )
        )
    }
  )
