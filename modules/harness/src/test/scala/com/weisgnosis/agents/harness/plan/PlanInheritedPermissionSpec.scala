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

import com.weisgnosis.agents.core.agent.{AgentConfig, AgentResult, ToolExecution}
import com.weisgnosis.agents.core.client.{LlmClient, LlmConfig, TurnOutput}
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.model.{ContentBlock, Msg, ToolResultState}
import com.weisgnosis.agents.core.tool.{Tool, ToolAuthorizer, ToolDef, Toolkit}
import com.weisgnosis.agents.harness.permission.*
import com.weisgnosis.agents.harness.subagent.*
import zio.*
import zio.json.ast.Json
import zio.test.*

object PlanInheritedPermissionSpec extends ZIOSpecDefault:
  private val allow: PermissionEngine = PermissionEngine(
    Chunk(PermissionRule("*", PermissionBehavior.Allow))
  )
  private val access: Map[String, PlanToolAccess] = PlanTools.access ++ Map(
    "read"              -> PlanToolAccess.ReadOnly,
    "write"             -> PlanToolAccess.Mutation,
    "inspect"           -> PlanToolAccess.PosixReadCommand,
    "request_execution" -> PlanToolAccess.RequestExecution
  )

  private def probes(names: Chunk[String], executed: Ref[Chunk[(String, Json)]]): Toolkit =
    names
      .map(name =>
        Tool.make(ToolDef(name, "Record admitted execution", Json.Obj()))(input =>
          executed.update(_ :+ (name, input)).as("executed")
        )
      )
      .foldLeft(Toolkit.empty)(_.updated(_))

  private def start(
      calls: Chunk[ContentBlock.ToolUse],
      toolkit: Toolkit,
      authorizer: ToolAuthorizer
  ): ZIO[Scope, AgentError, SupervisedTask[AgentResult]] =
    for
      supervisor <- TaskSupervisor.open[AgentResult]
      root       <- SubagentSpawner.root(supervisor)
      factory <- ZIO.fromEither(
        SubagentFactory.make(
          AgentConfig(toolExecution = ToolExecution.Sequential),
          LlmConfig("child-plan-fixture"),
          toolkit,
          SubagentToolBoundary(),
          SubagentToolBoundary(),
          _ => ZIO.succeed(Toolkit.empty),
          _ =>
            LlmClient.mock(
              Chunk(
                TurnOutput.ToolCalls(Msg.assistant(calls)),
                TurnOutput.FinalText(Msg.assistant("done"))
              )
            ),
          authorizer
        )
      )
      admitted <- factory.spawn(root, Msg.user("perform the delegated work"))
      task <- admitted match
        case TaskAdmission.Accepted(task) => ZIO.succeed(task)
        case TaskAdmission.Closed =>
          ZIO.fail(AgentError.InvalidConfiguration("Unexpected closed supervisor"))
    yield task

  def spec: Spec[TestEnvironment & Scope, Any] = suite("PlanInheritedPermissionSpec")(
    test(
      "a planning parent restricts a Build child without taking ownership of its plan controls"
    ) {
      val names = Chunk("read", "write", "unknown", "request_execution", "plan_enter", "plan_write")
      val calls = names.map(name =>
        ContentBlock.ToolUse(
          name,
          name,
          if name == "plan_write" then Json.Obj("content" -> Json.Str("Child-owned draft"))
          else Json.Obj()
        )
      )
      for
        parent       <- PlanSession.make
        child        <- PlanSession.make
        _            <- parent.control.enter
        parentBefore <- parent.control.snapshot
        executed     <- Ref.make(Chunk.empty[(String, Json)])
        samples      <- Ref.make(0)
        prompts      <- Ref.make(0)
        requests     <- PermissionRequests.make
        gate = PlanPermissionGate.inherited(
          samples.update(_ + 1) *> parent.control.mode,
          samples.update(_ + 1) *> child.control.mode,
          access
        )
        before <- samples.get
        authorizer <- InteractivePermissionAuthorizer.make(
          requests,
          request =>
            prompts.update(_ + 1) *> requests
              .resolve(request.requestId, ConfirmResult(confirmed = true))
              .unit,
          allow,
          interceptorGate = gate
        )
        toolkit <- ZIO.fromEither(
          probes(names.take(4), executed) ++ PlanTools.make(
            child.control,
            PlanReviewPublisher(_ => ZIO.unit)
          )
        )
        task        <- start(calls, toolkit, authorizer)
        result      <- task.await
        actual      <- executed.get
        asked       <- prompts.get
        count       <- samples.get
        parentAfter <- parent.control.snapshot
        childAfter  <- child.control.snapshot
      yield assertTrue(
        before == 0,
        count == 2 * (calls.size + 1),
        asked == 1,
        actual.map(_._1) == Chunk("read", "request_execution"),
        parentAfter == parentBefore,
        childAfter == PlanSessionSnapshot.Planning(
          PlanDraft.fromString("Child-owned draft").toOption
        ),
        result.exists(
          _.history.flatMap(_.toolResults).map(_.state) == Chunk(
            ToolResultState.Success,
            ToolResultState.Denied,
            ToolResultState.Denied,
            ToolResultState.Success,
            ToolResultState.Success,
            ToolResultState.Success
          )
        )
      )
    },
    test(
      "a Build parent cannot release a planning child before that child's exact review is approved"
    ) {
      val calls = Chunk(
        ContentBlock.ToolUse("before", "write", Json.Obj()),
        ContentBlock.ToolUse("review", "plan_exit", Json.Obj()),
        ContentBlock.ToolUse("after", "write", Json.Obj())
      )
      for
        parent       <- PlanSession.make
        child        <- PlanSession.make
        draft        <- ZIO.fromEither(PlanDraft.fromString("Exact child plan"))
        _            <- child.control.enter *> child.control.replaceDraft(draft)
        parentBefore <- parent.control.snapshot
        executed     <- Ref.make(Chunk.empty[(String, Json)])
        published    <- Promise.make[Nothing, PlanReviewRequest]
        requests     <- PermissionRequests.make
        authorizer <- InteractivePermissionAuthorizer.make(
          requests,
          _ => ZIO.unit,
          allow,
          interceptorGate =
            PlanPermissionGate.inherited(parent.control.mode, child.control.mode, access)
        )
        toolkit <- ZIO.fromEither(
          probes(Chunk("write"), executed) ++ PlanTools.make(
            child.control,
            PlanReviewPublisher(request => published.succeed(request).unit)
          )
        )
        task          <- start(calls, toolkit, authorizer)
        review        <- published.await
        wrong         <- parent.reviewer.resolve(review.review, PlanReviewDecision.Approve)
        before        <- executed.get
        stillPlanning <- child.control.mode
        approved      <- child.reviewer.resolve(review.review, PlanReviewDecision.Approve)
        result        <- task.await
        after         <- executed.get
        parentAfter   <- parent.control.snapshot
        childAfter    <- child.control.snapshot
      yield assertTrue(
        wrong == PlanReviewResolution.NotCurrent,
        before.isEmpty,
        stillPlanning == PlanMode.Plan,
        approved == PlanReviewResolution.Applied,
        after.map(_._1) == Chunk("write"),
        parentAfter == parentBefore,
        childAfter == PlanSessionSnapshot.Build(Some(ApprovedPlan(draft, review.review.revision))),
        result.exists(
          _.history.flatMap(_.toolResults).map(_.state) == Chunk(
            ToolResultState.Denied,
            ToolResultState.Success,
            ToolResultState.Success
          )
        )
      )
    },
    test("live parent activation and modified confirmation are rechecked before child execution") {
      val cases = Chunk(
        ("write", PlanMode.Plan, Json.Obj(), ToolResultState.Denied),
        (
          "inspect",
          PlanMode.Plan,
          Json.Obj("command" -> Json.Str("cat file > output")),
          ToolResultState.Denied
        ),
        ("inspect", PlanMode.Plan, Json.Obj("command" -> Json.Str("pwd")), ToolResultState.Success),
        ("write", PlanMode.Build, Json.Obj(), ToolResultState.Success)
      )
      ZIO
        .foreach(cases) { case (name, mode, replacement, expected) =>
          for
            parent    <- PlanSession.make
            child     <- PlanSession.make
            executed  <- Ref.make(Chunk.empty[(String, Json)])
            requests  <- PermissionRequests.make
            published <- Promise.make[Nothing, String]
            authorizer <- InteractivePermissionAuthorizer.make(
              requests,
              request => published.succeed(request.requestId).unit,
              interceptorGate =
                PlanPermissionGate.inherited(parent.control.mode, child.control.mode, access)
            )
            task <- start(
              Chunk(ContentBlock.ToolUse("attempt", name, Json.Obj("command" -> Json.Str("pwd")))),
              probes(Chunk(name), executed),
              authorizer
            )
            requestId <- published.await
            _         <- if mode == PlanMode.Plan then parent.control.enter else ZIO.unit
            resolved <- requests.resolve(
              requestId,
              ConfirmResult(confirmed = true, modifiedInput = Some(replacement))
            )
            result    <- task.await
            actual    <- executed.get
            childMode <- child.control.mode
          yield resolved && childMode == PlanMode.Build &&
            actual == (if expected == ToolResultState.Success then Chunk(name -> replacement)
                       else Chunk.empty) &&
            result.exists(_.history.flatMap(_.toolResults).map(_.state) == Chunk(expected))
        }
        .map(results => assertTrue(results.forall(identity)))
    }
  )
