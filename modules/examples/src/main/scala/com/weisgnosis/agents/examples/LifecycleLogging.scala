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

import com.weisgnosis.agents.core.agent.{AgentResult, AgentRunState, AgentRunUpdate, ReActAgent}
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.event.{AgentEvent, ModelStreamContext}
import com.weisgnosis.agents.core.model.Msg
import zio.*

/** Payload-free lifecycle logging for runtime-origin events; hosts configure logging and export. */
object LifecycleLogging:
  def observe(event: AgentEvent): UIO[Unit] = event match
    case AgentEvent.AgentStartEvent(_, replyId, agentId, _) =>
      ZIO.logAnnotate("reply_id", replyId):
        ZIO.logAnnotate("agent_id", agentId)(ZIO.logInfo("agent_start"))
    case AgentEvent.AgentEndEvent(replyId, _) =>
      ZIO.logAnnotate("reply_id", replyId)(ZIO.logInfo("agent_end"))
    case AgentEvent.ModelRequestStartEvent(_, _, _, context) =>
      correlated("model_start", context)
    case AgentEvent.ModelResponseEndEvent(_, _, _, _, context) =>
      correlated("model_end", context)
    case AgentEvent.ToolResultStartEvent(_, _, _, _, context) =>
      correlated("tool_start", context)
    case AgentEvent.ToolResultEndEvent(_, _, _, _, _, _, context) =>
      correlated("tool_end", context)
    case _ => ZIO.unit

  def run(agent: ReActAgent, input: Msg): ZIO[Scope, AgentError, AgentResult] =
    Random.nextUUID.flatMap: invocationId =>
      ZIO.logAnnotate("invocation_id", invocationId.toString):
        ZIO.logAnnotate("agent_id", agent.agentId):
          ZIO.logSpan("agent-turn"):
            agent
              .streamWithResult(input)
              .tap:
                case AgentRunUpdate.Event(event) => observe(event)
                case AgentRunUpdate.Result(_)    => ZIO.unit
              .runFold(Option.empty[AgentResult]):
                case (result, AgentRunUpdate.Event(_))  => result
                case (_, AgentRunUpdate.Result(result)) => Some(result)
              .flatMap(result =>
                ZIO
                  .fromOption(result)
                  .orElseFail(AgentError.InvalidMessage("Missing terminal agent result"))
              )
              .tap(result =>
                ZIO.logAnnotate("run_state", stateLabel(result.state))(ZIO.logInfo("agent_result"))
              )
              .onExit:
                case Exit.Failure(cause) =>
                  val status = if cause.isInterruptedOnly then "cancelled" else "failed"
                  ZIO.logAnnotate("run_state", status)(ZIO.logInfo("agent_exit"))
                case Exit.Success(_) => ZIO.unit

  private def correlated(message: String, context: Option[ModelStreamContext]): UIO[Unit] =
    context match
      case Some(value) =>
        ZIO.logAnnotate("reply_id", value.replyId):
          ZIO.logAnnotate("request_id", value.requestId)(ZIO.logInfo(message))
      case None => ZIO.logInfo(message)

  private def stateLabel(state: AgentRunState): String = state match
    case AgentRunState.Completed    => "completed"
    case AgentRunState.Interrupted  => "interrupted"
    case AgentRunState.Suspended(_) => "suspended"
    case AgentRunState.Stopped(_)   => "stopped"
