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
package com.weisgnosis.agents.core.client

import com.weisgnosis.agents.core.agent.{AgentConfig, ReActAgent, ToolExecution}
import com.weisgnosis.agents.core.model.{ContentBlock, GenerateReason, Msg, ToolResultState}
import com.weisgnosis.agents.core.tool.{Tool, ToolDef, Toolkit}
import zio.*
import zio.json.ast.Json
import zio.test.*

object ScriptedLlmClientIntegrationSpec extends ZIOSpecDefault:

  def spec: Spec[TestEnvironment & Scope, Any] =
    suite("ScriptedLlmClient public agent integration")(
      test("executes a real tool after persisting its call and records the next model observation"):
        val call = ContentBlock.ToolUse("lookup-1", "lookup", Json.Obj())
        for
          ready     <- Promise.make[Nothing, ReActAgent]
          execution <- Ref.make(0)
          atEntry   <- Ref.make(Chunk.empty[Msg])
          lookup = Tool.make(ToolDef("lookup", "Read release evidence", Json.Obj()))(_ =>
            ready.await.flatMap(_.history).flatMap(atEntry.set) *>
              execution.update(_ + 1).as("verified evidence")
          )
          client <- ScriptedLlmClient.make(
            Chunk(
              TurnOutput.ToolCalls(Msg.assistant(Chunk(call))),
              TurnOutput.FinalText(Msg.assistant("Release checked"))
            ),
            maxRecordedRequests = 2
          )
          agent <- ReActAgent.make(
            client = client,
            toolkit = Toolkit.single(lookup),
            llmConfig = LlmConfig(
              "scripted-release-model",
              maxTokens = Some(128),
              temperaturePolicy = TemperaturePolicy.Omit
            )
          )
          _         <- ready.succeed(agent)
          result    <- agent.run(Msg.user("Check the release"))
          requests  <- client.requests
          persisted <- atEntry.get
          calls     <- execution.get
          remaining <- client.remaining
          _         <- client.verifyConsumed
          second = requests.lift(1).map(_.input)
        yield assertTrue(
          calls == 1,
          persisted.flatMap(_.toolUses).map(_.id) == Chunk(call.id),
          persisted.flatMap(_.toolResults).isEmpty,
          result.reply.text == "Release checked",
          requests.size == 2,
          requests.headOption.exists(_.input.history.map(_.text) == Chunk("Check the release")),
          second.exists(_.history.flatMap(_.toolUses).map(_.id) == Chunk(call.id)),
          second.exists(
            _.history
              .flatMap(_.toolResults)
              .exists(result =>
                result.id == call.id && result.state == ToolResultState.Success &&
                  result.text == "verified evidence"
              )
          ),
          requests.forall(_.input.model == "scripted-release-model"),
          requests.forall(_.input.settings.maxTokens.contains(128)),
          requests.forall(_.input.settings.configuredTemperature.isEmpty),
          requests.forall(_.input.tools.map(_.name) == Chunk("lookup")),
          requests.map(_.context.requestId).distinct.size == 2,
          requests.map(_.context.replyId).distinct.size == 1,
          remaining == 0
        )
      ,
      test(
        "fiber interruption joins tool cleanup and the same agent continues with complete history"
      ):
        ZIO.scoped:
          val completed = ContentBlock.ToolUse("completed-1", "fast", Json.Obj())
          val pending   = ContentBlock.ToolUse("pending-1", "slow", Json.Obj())
          for
            entered        <- Promise.make[Nothing, Unit]
            cleanupStarted <- Promise.make[Nothing, Unit]
            releaseCleanup <- Promise.make[Nothing, Unit]
            cleaned        <- Ref.make(false)
            executions     <- Ref.make(0)
            fast = Tool.make(ToolDef("fast", "Complete before cancellation", Json.Obj()))(_ =>
              executions.update(_ + 1).as("preserved result")
            )
            slow = Tool.make(ToolDef("slow", "Own a scoped resource", Json.Obj()))(_ =>
              ZIO.scoped(
                ZIO.acquireRelease(ZIO.unit)(_ =>
                  cleanupStarted.succeed(()) *> releaseCleanup.await *> cleaned.set(true)
                ) *> entered.succeed(()) *> ZIO.never
              )
            )
            client <- ScriptedLlmClient.make(
              Chunk(
                TurnOutput.ToolCalls(Msg.assistant(Chunk(completed, pending))),
                TurnOutput.FinalText(Msg.assistant("Continued after cancellation"))
              ),
              maxRecordedRequests = 2
            )
            agent <- ReActAgent.make(
              config = AgentConfig(toolExecution = ToolExecution.Sequential),
              client = client,
              toolkit = Toolkit.single(fast).updated(slow)
            )
            worker <- ZIO.uninterruptibleMask: restore =>
              for
                fiber <- restore(agent.run(Msg.user("Start work"))).forkScoped
                _     <- ZIO.addFinalizer(releaseCleanup.succeed(()).unit)
              yield fiber
            _ <- entered.await
            cancelling <- ZIO.uninterruptibleMask: restore =>
              for
                fiber <- restore(worker.interrupt).forkScoped
                _     <- ZIO.addFinalizer(releaseCleanup.succeed(()).unit)
              yield fiber
            _                  <- cleanupStarted.await
            held               <- cancelling.poll
            cleanedBefore      <- cleaned.get
            requestsBefore     <- client.requests
            _                  <- releaseCleanup.succeed(())
            exit               <- cancelling.join
            history            <- agent.history
            cleanedAfter       <- cleaned.get
            remainingAfterStop <- client.remaining
            continued          <- agent.run(Msg.user("Continue"))
            requestsAfter      <- client.requests
            executed           <- executions.get
            _                  <- client.verifyConsumed
            observations = history.flatMap(_.toolResults)
          yield assertTrue(
            held.isEmpty,
            !cleanedBefore,
            cleanedAfter,
            exit.isInterrupted,
            exit.causeOption.exists(cause => cause.failures.isEmpty && cause.defects.isEmpty),
            requestsBefore.size == 1,
            remainingAfterStop == 1,
            observations.map(_.id) == Chunk(completed.id, pending.id),
            observations.map(_.state) == Chunk(ToolResultState.Success, ToolResultState.Error),
            observations.headOption.exists(_.text == "preserved result"),
            history.count(_.generationReason == GenerateReason.Interrupted) == 1,
            history.lastOption.exists(
              _.text == "I noticed that you have interrupted me. What can I do for you?"
            ),
            continued.reply.text == "Continued after cancellation",
            executed == 1,
            requestsAfter
              .lift(1)
              .exists(request =>
                request.input.history.take(history.size) == history &&
                  request.input.history.size == history.size + 1 &&
                  request.input.history.lastOption.exists(_.text == "Continue")
              )
          )
    ) @@ TestAspect.timeout(20.seconds)
