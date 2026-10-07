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

import com.weisgnosis.agents.core.agent.{AgentRunState, ReActAgent}
import com.weisgnosis.agents.core.client.{LlmClient, LlmConfig, TurnOutput}
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.event.{AgentEvent, ModelStreamContext}
import com.weisgnosis.agents.core.model.{Msg, ToolResultState}
import com.weisgnosis.agents.core.tool.ToolDef
import zio.*
import zio.test.*

object LifecycleLoggingSpec extends ZIOSpecDefault:
  def spec: Spec[TestEnvironment, Any] = suite("LifecycleLogging")(
    test("allowlisted lifecycle logs exclude payloads and keep annotations local"):
      val context = Some(ModelStreamContext("reply-runtime", "request-runtime"))
      val secret  = "secret-payload"
      val events = Chunk(
        AgentEvent.ModelRequestStartEvent(secret, 1, Some(secret), context),
        AgentEvent.ToolResultStartEvent(secret, secret, Some(secret), context = context),
        AgentEvent
          .ToolResultEndEvent(secret, secret, secret, ToolResultState.Error, context = context),
        AgentEvent.TextBlockDeltaEvent(secret, 0, Some(secret), context),
        AgentEvent.AgentEndEvent("reply-runtime", Some(secret))
      )
      for
        _       <- ZIO.foreachDiscard(events)(LifecycleLogging.observe)
        model   <- LlmClient.mock(Chunk(TurnOutput.FinalText(Msg.assistant(secret))))
        agent   <- ReActAgent.builder().model(model).build
        result  <- ZIO.scoped(LifecycleLogging.run(agent, Msg.user(secret)))
        entries <- ZTestLogger.logOutput
        correlated = entries.filter(entry =>
          Set("model_start", "tool_start", "tool_end").contains(
            entry.message()
          ) && entry.annotations.get("reply_id").contains("reply-runtime")
        )
        endings  = entries.filter(_.message() == "agent_end")
        terminal = entries.filter(_.message() == "agent_result")
      yield assertTrue(
        result.state == AgentRunState.Completed,
        result.reply.getTextContent == secret,
        correlated.size == 3,
        correlated.forall(_.annotations.get("request_id").contains("request-runtime")),
        correlated.forall(_.annotations.get("reply_id").contains("reply-runtime")),
        endings.forall(!_.annotations.contains("request_id")),
        terminal.exists(_.annotations.get("run_state").contains("completed")),
        terminal.forall(_.spans.exists(_.label == "agent-turn")),
        terminal.forall(_.annotations.get("agent_id").contains(agent.agentId)),
        terminal.forall(_.annotations.contains("invocation_id")),
        entries
          .filter(entry =>
            entry.logLevel == LogLevel.Info && entry.trace.toString.contains("LifecycleLogging.")
          )
          .forall(entry =>
            !entry.message().contains(secret) && !entry.annotations.values
              .exists(_.contains(secret))
          ),
        entries
          .filter(entry =>
            entry.logLevel == LogLevel.Info && entry.trace.toString.contains("LifecycleLogging.")
          )
          .forall(_.cause.isEmpty)
      )
    ,
    test("failure retains its cause and cancellation waits for model cleanup"):
      val failure = AgentError.ModelError(503, "private-provider-error")
      for
        entered <- Promise.make[Nothing, Unit]
        cleanup <- Promise.make[Nothing, Unit]
        release <- Promise.make[Nothing, Unit]
        client = new LlmClient:
          def streamTurn(
              history: Chunk[Msg],
              tools: Chunk[ToolDef],
              config: LlmConfig,
              eventHub: Hub[AgentEvent],
              context: ModelStreamContext
          ): ZIO[Scope, AgentError, TurnOutput] =
            ZIO.acquireRelease(entered.succeed(()))(_ =>
              cleanup.succeed(()) *> release.await
            ) *> ZIO.never
        agent <- ReActAgent.builder().model(client).build
        exit <- ZIO.scoped:
          for
            run      <- LifecycleLogging.run(agent, Msg.user("cancel")).forkScoped
            _        <- entered.await
            stopping <- run.interrupt.forkScoped
            _        <- cleanup.await
            pending  <- stopping.poll
            _        <- release.succeed(())
            stopped  <- stopping.join
          yield (pending, stopped)
        failing = new LlmClient:
          def streamTurn(
              history: Chunk[Msg],
              tools: Chunk[ToolDef],
              config: LlmConfig,
              eventHub: Hub[AgentEvent],
              context: ModelStreamContext
          ): ZIO[Scope, AgentError, TurnOutput] = ZIO.fail(failure)
        failedAgent <- ReActAgent.builder().model(failing).build
        failed      <- ZIO.scoped(LifecycleLogging.run(failedAgent, Msg.user("fail"))).exit
        entries     <- ZTestLogger.logOutput
      yield assertTrue(
        exit._1.isEmpty,
        exit._2.isInterrupted,
        failed.causeOption.exists(_.failures.contains(failure)),
        entries.exists(entry =>
          entry.message() == "agent_exit" && entry.annotations
            .get("run_state")
            .contains("failed") && entry.annotations
            .get("agent_id")
            .contains(failedAgent.agentId) && entry.annotations.contains("invocation_id")
        ),
        entries
          .filter(entry =>
            entry.logLevel == LogLevel.Info && entry.trace.toString.contains("LifecycleLogging.")
          )
          .forall(entry => !entry.message().contains(failure.message) && entry.cause.isEmpty)
      )
  )
