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
package com.weisgnosis.agents.harness.permission

import com.weisgnosis.agents.core.agent.{AgentConfig, AgentRunState, ReActAgent, ToolExecution}
import com.weisgnosis.agents.core.client.{LlmClient, TurnOutput}
import com.weisgnosis.agents.core.event.AgentEvent
import com.weisgnosis.agents.core.model.*
import com.weisgnosis.agents.core.tool.*
import zio.*
import zio.json.ast.Json
import zio.test.*

object InteractivePermissionIntegrationSpec extends ZIOSpecDefault:
  private val original: Json = Json.Obj("command" -> Json.Str("read original"))
  private val modified: Json = Json.Obj("command" -> Json.Str("read approved"))
  private val call: ContentBlock.ToolUse =
    ContentBlock.ToolUse("call", "read", original, Some("old raw arguments"))
  private def toolkit(run: Json => UIO[String]): Toolkit =
    Toolkit(Map("read" -> Tool.make(ToolDef("read", "Read", Json.Obj()))(run)))

  def spec: Spec[TestEnvironment & Scope, Any] = suite("InteractivePermissionIntegrationSpec")(
    test("approval resumes the same tool phase with modified input and no extra reasoning request") {
      for
        requests    <- PermissionRequests.make
        published   <- Queue.unbounded[ToolAuthorizationRequest]
        modelCalls  <- Ref.make(0)
        executed    <- Ref.make(Chunk.empty[Json])
        nextHistory <- Ref.make(Chunk.empty[Msg])
        authorizer <- InteractivePermissionAuthorizer.make(
          requests,
          value => published.offer(value).unit
        )
        model = LlmClient.mockEffectful((history, _, _) =>
          modelCalls
            .getAndUpdate(_ + 1)
            .flatMap(index =>
              if index == 0 then ZIO.succeed(TurnOutput.ToolCalls(Msg.assistant(Chunk(call))))
              else nextHistory.set(history).as(TurnOutput.FinalText(Msg.assistant("done")))
            )
        )
        agent <- ReActAgent.make(
          client = model,
          toolkit = toolkit(input => executed.update(_ :+ input).as("ok")),
          toolAuthorizer = authorizer
        )
        fiber           <- agent.run(Msg.user("q")).forkScoped
        prompt          <- published.take
        before          <- modelCalls.get
        beforeExecution <- executed.get
        persisted       <- agent.history
        accepted        <- requests.resolve(prompt.requestId, ConfirmResult(true, Some(modified)))
        result          <- fiber.join
        after           <- modelCalls.get
        inputs          <- executed.get
        next            <- nextHistory.get
      yield assertTrue(
        before == 1,
        beforeExecution.isEmpty,
        persisted.flatMap(_.toolUses) == Chunk(call),
        accepted,
        after == 2,
        inputs == Chunk(modified),
        result.state == AgentRunState.Completed,
        next.flatMap(_.toolUses).map(_.input) == Chunk(modified),
        next
          .flatMap(_.toolUses)
          .forall(value =>
            value.content.isEmpty && value.metadata
              .get("agentscope_original_tool_input")
              .contains(original)
          )
      )
    },
    test("custom and default declines publish complete denied observations without execution") {
      for
        executions <- Ref.make(0)
        results <- ZIO.foreach(Chunk(Option("custom decline"), Option.empty[String]))(reason =>
          for
            requests <- PermissionRequests.make
            authorizer <- InteractivePermissionAuthorizer.make(
              requests,
              value => requests.resolve(value.requestId, ConfirmResult(false, reason = reason)).unit
            )
            model <- LlmClient.mock(
              Chunk(
                TurnOutput.ToolCalls(Msg.assistant(Chunk(call))),
                TurnOutput.FinalText(Msg.assistant("done"))
              )
            )
            agent <- ReActAgent.make(
              client = model,
              toolkit = toolkit(_ => executions.update(_ + 1).as("wrong")),
              toolAuthorizer = authorizer
            )
            events  <- agent.streamEvents(Msg.user("q")).runCollect
            history <- agent.history
          yield (history.flatMap(_.toolResults), events)
        )
        count <- executions.get
      yield assertTrue(
        count == 0,
        results.map(_._1.map(_.text)) == Chunk(
          Chunk("custom decline"),
          Chunk("Tool execution declined")
        ),
        results.forall { case (observations, events) =>
          observations.map(_.state) == Chunk(ToolResultState.Denied) &&
          events.collect {
            case _: AgentEvent.ToolResultStartEvent => "start"
            case _: AgentEvent.ToolResultDeltaEvent => "delta"
            case _: AgentEvent.ToolResultEndEvent   => "end"
          } == Chunk("start", "delta", "end") &&
          events.collect { case event: AgentEvent.ToolResultDeltaEvent =>
            event.callId -> event.delta
          } ==
            observations.map(value => value.id -> value.text) &&
            events.collect { case event: AgentEvent.ToolResultEndEvent =>
              event.callId -> event.state
            } ==
            Chunk(call.id -> ToolResultState.Denied)
        }
      )
    },
    test(
      "parallel reused call IDs resolve independently and rewrite their own original arguments"
    ) {
      val other = call.copy(input = Json.Obj("command" -> Json.Str("other original")))
      for
        requests  <- PermissionRequests.make
        published <- Queue.unbounded[ToolAuthorizationRequest]
        executed  <- Queue.unbounded[Json]
        authorizer <- InteractivePermissionAuthorizer.make(
          requests,
          value => published.offer(value).unit
        )
        model <- LlmClient.mock(
          Chunk(
            TurnOutput.ToolCalls(Msg.assistant(Chunk(ContentBlock.Text("tools"), call, other))),
            TurnOutput.FinalText(Msg.assistant("done"))
          )
        )
        agent <- ReActAgent.make(
          client = model,
          toolkit = toolkit(input => executed.offer(input).as("ok")),
          toolAuthorizer = authorizer
        )
        fiber  <- agent.run(Msg.user("q")).forkScoped
        first  <- published.take
        second <- published.take
        replacement = Json.Obj("approvedOriginal" -> second.call.input)
        _           <- requests.resolve(second.requestId, ConfirmResult(true, Some(replacement)))
        secondInput <- executed.take
        _           <- requests.resolve(first.requestId, ConfirmResult(true))
        firstInput  <- executed.take
        result      <- fiber.join
        rewritten = result.history.flatMap(_.toolUses)
        expected = Chunk(call, other).map(value =>
          if value.input == second.call.input then
            value.copy(
              input = replacement,
              content = None,
              metadata = Map("agentscope_original_tool_input" -> value.input)
            )
          else value
        )
      yield assertTrue(
        first.requestId != second.requestId,
        first.context == second.context,
        secondInput == replacement,
        firstInput == first.call.input,
        rewritten == expected,
        result.history.flatMap(_.toolResults).size == 2
      )
    },
    test("interrupting a pending approval retains completed siblings and rejects late resolution") {
      val waiting = call.copy(id = "waiting")
      for
        requests   <- PermissionRequests.make
        published  <- Queue.unbounded[ToolAuthorizationRequest]
        executions <- Ref.make(0)
        authorizer <- InteractivePermissionAuthorizer.make(
          requests,
          value =>
            if value.call.id == "call" then
              requests.resolve(value.requestId, ConfirmResult(true)).unit
            else published.offer(value).unit
        )
        model <- LlmClient.mock(Chunk(TurnOutput.ToolCalls(Msg.assistant(Chunk(call, waiting)))))
        agent <- ReActAgent.make(
          config = AgentConfig(toolExecution = ToolExecution.Sequential),
          client = model,
          toolkit = toolkit(_ => executions.update(_ + 1).as("ok")),
          toolAuthorizer = authorizer
        )
        fiber   <- agent.run(Msg.user("q")).forkScoped
        prompt  <- published.take
        _       <- agent.interrupt
        result  <- fiber.join
        late    <- requests.resolve(prompt.requestId, ConfirmResult(true))
        pending <- requests.pendingIds
        count   <- executions.get
      yield assertTrue(
        result.state == AgentRunState.Interrupted,
        !late,
        pending.isEmpty,
        count == 1,
        result.history.flatMap(_.toolResults).map(value => value.id -> value.state) == Chunk(
          "call"    -> ToolResultState.Success,
          "waiting" -> ToolResultState.Error
        )
      )
    },
    test("confirmed rules persist across later runs of the installed authorizer") {
      val rule = PermissionRule("read", PermissionBehavior.Allow)
      for
        requests   <- PermissionRequests.make
        prompts    <- Ref.make(0)
        executions <- Ref.make(0)
        authorizer <- InteractivePermissionAuthorizer.make(
          requests,
          value =>
            prompts.update(_ + 1) *>
              requests.resolve(value.requestId, ConfirmResult(true, newRules = Chunk(rule))).unit
        )
        model <- LlmClient.mock(
          Chunk(
            TurnOutput.ToolCalls(Msg.assistant(Chunk(call))),
            TurnOutput.FinalText(Msg.assistant("first")),
            TurnOutput.ToolCalls(Msg.assistant(Chunk(call))),
            TurnOutput.FinalText(Msg.assistant("second"))
          )
        )
        agent <- ReActAgent.make(
          client = model,
          toolkit = toolkit(_ => executions.update(_ + 1).as("ok")),
          toolAuthorizer = authorizer
        )
        _        <- agent.run(Msg.user("first"))
        _        <- agent.run(Msg.user("second"))
        count    <- prompts.get
        executed <- executions.get
        policy   <- authorizer.policy
      yield assertTrue(count == 1, executed == 2, policy.rules == Chunk(rule))
    },
    test("independently confirmed rules survive a signed-input denial at the core boundary") {
      val rule = PermissionRule("future", PermissionBehavior.Allow)
      for
        requests   <- PermissionRequests.make
        executions <- Ref.make(0)
        authorizer <- InteractivePermissionAuthorizer.make(
          requests,
          value =>
            requests.resolve(value.requestId, ConfirmResult(true, Some(modified), Chunk(rule))).unit
        )
        model <- LlmClient.mock(
          Chunk(
            TurnOutput.ToolCalls(Msg.assistant(Chunk(call.withThoughtSignature("signature")))),
            TurnOutput.FinalText(Msg.assistant("done"))
          )
        )
        agent <- ReActAgent.make(
          client = model,
          toolkit = toolkit(_ => executions.update(_ + 1).as("wrong")),
          toolAuthorizer = authorizer
        )
        result <- agent.run(Msg.user("q"))
        policy <- authorizer.policy
        count  <- executions.get
      yield assertTrue(
        count == 0,
        result.history.flatMap(_.toolResults).map(_.state) == Chunk(ToolResultState.Denied),
        policy.rules == Chunk(rule)
      )
    }
  )
