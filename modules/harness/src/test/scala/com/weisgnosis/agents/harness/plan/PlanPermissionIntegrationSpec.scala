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
package com.weisgnosis.agents.harness.plan

import com.weisgnosis.agents.core.agent.{AgentConfig, ReActAgent, ToolExecution}
import com.weisgnosis.agents.core.client.{LlmClient, TurnOutput}
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.event.AgentEvent
import com.weisgnosis.agents.core.model.*
import com.weisgnosis.agents.core.tool.*
import com.weisgnosis.agents.harness.permission.*
import zio.*
import zio.json.ast.Json
import zio.test.*

object PlanPermissionIntegrationSpec extends ZIOSpecDefault:
  private val allow: PermissionEngine = PermissionEngine(
    Chunk(PermissionRule("*", PermissionBehavior.Allow))
  )
  private val input: Json = Json.Obj("command" -> Json.Str("pwd"))

  private def toolkit(names: Chunk[String], run: (String, Json) => UIO[String]): Toolkit =
    Toolkit(
      names
        .map(name =>
          name -> Tool.make(ToolDef(name, "Host capability", Json.Obj()))(value => run(name, value))
        )
        .toMap
    )

  private def agent(
      calls: Chunk[ContentBlock.ToolUse],
      tools: Toolkit,
      authorizer: ToolAuthorizer
  ): IO[AgentError, ReActAgent] =
    for
      client <- LlmClient.mock(
        Chunk(
          TurnOutput.ToolCalls(Msg.assistant(calls)),
          TurnOutput.FinalText(Msg.assistant("done"))
        )
      )
      agent <- ReActAgent.make(
        client = client,
        toolkit = tools,
        toolAuthorizer = authorizer,
        config = AgentConfig(toolExecution = ToolExecution.Sequential)
      )
    yield agent

  def spec: Spec[TestEnvironment & Scope, Any] = suite("PlanPermissionIntegrationSpec")(
    test(
      "active plan restrictions persist and stream denied observations while forwarding read and limited plan capabilities"
    ) {
      val names = Chunk("read", "write", "plan_write", "agent_send")
      val access = Map(
        "read"       -> PlanToolAccess.ReadOnly,
        "write"      -> PlanToolAccess.Mutation,
        "plan_write" -> PlanToolAccess.PlanControl
      )
      for
        requests <- PermissionRequests.make
        executed <- Ref.make(Chunk.empty[String])
        prompts  <- Ref.make(0)
        authorizer <- InteractivePermissionAuthorizer.make(
          requests,
          _ => prompts.update(_ + 1),
          allow,
          interceptorGate = PlanPermissionGate.make(ZIO.succeed(PlanMode.Plan), access)
        )
        calls = names.zipWithIndex.map((name, index) =>
          ContentBlock.ToolUse(index.toString, name, Json.Obj())
        )
        runtime <- agent(
          calls,
          toolkit(names, (name, _) => executed.update(_ :+ name).as("ok")),
          authorizer
        )
        events  <- runtime.streamEvents(Msg.user("investigate")).runCollect
        history <- runtime.history
        actual  <- executed.get
        asked   <- prompts.get
        results = history.flatMap(_.toolResults)
        denied  = results.filter(_.state == ToolResultState.Denied)
      yield assertTrue(
        actual == Chunk("read", "plan_write"),
        asked == 0,
        results.map(_.state) == Chunk(
          ToolResultState.Success,
          ToolResultState.Denied,
          ToolResultState.Success,
          ToolResultState.Denied
        ),
        denied.map(_.id) == Chunk("1", "3"),
        denied.forall(result =>
          events.collect {
            case event: AgentEvent.ToolResultStartEvent if event.callId == result.id => "start"
            case event: AgentEvent.ToolResultDeltaEvent if event.callId == result.id => "delta"
            case event: AgentEvent.ToolResultEndEvent
                if event.callId == result.id && event.state == ToolResultState.Denied =>
              "end"
          } == Chunk("start", "delta", "end")
        )
      )
    },
    test("inactive plan restrictions allow underlying policy to admit mutation") {
      for
        requests <- PermissionRequests.make
        executed <- Ref.make(0)
        authorizer <- InteractivePermissionAuthorizer.make(
          requests,
          _ => ZIO.unit,
          allow,
          interceptorGate = PlanPermissionGate.make(
            ZIO.succeed(PlanMode.Build),
            Map("write" -> PlanToolAccess.Mutation)
          )
        )
        runtime <- agent(
          Chunk(ContentBlock.ToolUse("write", "write", Json.Obj())),
          toolkit(Chunk("write"), (_, _) => executed.update(_ + 1).as("ok")),
          authorizer
        )
        _       <- runtime.run(Msg.user("execute"))
        count   <- executed.get
        history <- runtime.history
      yield assertTrue(
        count == 1,
        history.flatMap(_.toolResults).map(_.state) == Chunk(ToolResultState.Success)
      )
    },
    test(
      "confirmed replacement commands and extra execution fields are inspected again before dispatch"
    ) {
      val replacements = Chunk(
        Json.Obj("command" -> Json.Str("cat file > output")) -> ToolResultState.Denied,
        Json.Obj(
          "command" -> Json.Str("pwd"),
          "cwd"     -> Json.Str("elsewhere")
        )                                                 -> ToolResultState.Denied,
        Json.Obj("command" -> Json.Str("head -n 2 file")) -> ToolResultState.Success
      )
      for outcomes <- ZIO.foreach(replacements): (replacement, expected) =>
          for
            requests <- PermissionRequests.make
            executed <- Ref.make(Chunk.empty[Json])
            authorizer <- InteractivePermissionAuthorizer.make(
              requests,
              request =>
                requests.resolve(request.requestId, ConfirmResult(true, Some(replacement))).unit,
              interceptorGate = PlanPermissionGate.make(
                ZIO.succeed(PlanMode.Plan),
                Map("inspect" -> PlanToolAccess.PosixReadCommand)
              )
            )
            runtime <- agent(
              Chunk(
                ContentBlock.ToolUse("inspect", "inspect", input, Some("untrusted raw rm file"))
              ),
              toolkit(Chunk("inspect"), (_, value) => executed.update(_ :+ value).as("ok")),
              authorizer
            )
            _       <- runtime.run(Msg.user("inspect"))
            history <- runtime.history
            actual  <- executed.get
          yield history.flatMap(_.toolResults).map(_.state) == Chunk(expected) &&
            actual == (if expected == ToolResultState.Success then Chunk(replacement)
                       else Chunk.empty)
      yield assertTrue(outcomes.forall(identity))
    },
    test("entering Plan while approval is suspended denies the formerly eligible mutation") {
      for
        mode      <- Ref.make(PlanMode.Build)
        requests  <- PermissionRequests.make
        published <- Queue.unbounded[ToolAuthorizationRequest]
        executed  <- Ref.make(0)
        authorizer <- InteractivePermissionAuthorizer.make(
          requests,
          request => published.offer(request).unit,
          interceptorGate =
            PlanPermissionGate.make(mode.get, Map("write" -> PlanToolAccess.Mutation))
        )
        runtime <- agent(
          Chunk(ContentBlock.ToolUse("write", "write", Json.Obj())),
          toolkit(Chunk("write"), (_, _) => executed.update(_ + 1).as("wrong")),
          authorizer
        )
        fiber   <- runtime.run(Msg.user("write")).forkScoped
        request <- published.take
        _       <- mode.set(PlanMode.Plan)
        _       <- requests.resolve(request.requestId, ConfirmResult(true))
        _       <- fiber.join
        count   <- executed.get
        history <- runtime.history
      yield assertTrue(
        count == 0,
        history.flatMap(_.toolResults).map(_.state) == Chunk(ToolResultState.Denied)
      )
    },
    test(
      "requesting execution forces confirmation despite Allow rules and does not itself change mode"
    ) {
      for outcomes <- ZIO.foreach(Chunk(ConfirmResult(false), ConfirmResult(true))): confirmation =>
          for
            mode      <- Ref.make(PlanMode.Plan)
            requests  <- PermissionRequests.make
            published <- Queue.unbounded[ToolAuthorizationRequest]
            executed  <- Ref.make(0)
            authorizer <- InteractivePermissionAuthorizer.make(
              requests,
              request => published.offer(request).unit,
              allow,
              interceptorGate = PlanPermissionGate.make(
                mode.get,
                Map("plan_exit" -> PlanToolAccess.RequestExecution)
              )
            )
            runtime <- agent(
              Chunk(ContentBlock.ToolUse("exit", "plan_exit", Json.Obj())),
              toolkit(
                Chunk("plan_exit"),
                (_, _) => executed.update(_ + 1).as("host request received")
              ),
              authorizer
            )
            fiber   <- runtime.run(Msg.user("request execution")).forkScoped
            request <- published.take
            before  <- executed.get
            _       <- requests.resolve(request.requestId, confirmation)
            _       <- fiber.join
            count   <- executed.get
            current <- mode.get
          yield before == 0 && count == (if confirmation.confirmed then 1
                                         else 0) && current == PlanMode.Plan
      yield assertTrue(outcomes.forall(identity))
    },
    test("mode changes constrain the next admission without cancelling an already admitted tool") {
      for
        mode     <- Ref.make(PlanMode.Build)
        requests <- PermissionRequests.make
        entered  <- Promise.make[Nothing, Unit]
        release  <- Promise.make[Nothing, Unit]
        executed <- Ref.make(0)
        authorizer <- InteractivePermissionAuthorizer.make(
          requests,
          _ => ZIO.unit,
          allow,
          interceptorGate =
            PlanPermissionGate.make(mode.get, Map("write" -> PlanToolAccess.Mutation))
        )
        runtime <- agent(
          Chunk(
            ContentBlock.ToolUse("first", "write", Json.Obj()),
            ContentBlock.ToolUse("next", "write", Json.Obj())
          ),
          toolkit(
            Chunk("write"),
            (_, _) => executed.update(_ + 1) *> entered.succeed(()) *> release.await.as("completed")
          ),
          authorizer
        )
        fiber   <- runtime.run(Msg.user("work")).forkScoped
        _       <- entered.await
        _       <- mode.set(PlanMode.Plan)
        _       <- release.succeed(())
        _       <- fiber.join
        count   <- executed.get
        history <- runtime.history
      yield assertTrue(
        count == 1,
        history.flatMap(_.toolResults).map(_.state) == Chunk(
          ToolResultState.Success,
          ToolResultState.Denied
        )
      )
    }
  )
