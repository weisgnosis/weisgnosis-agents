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
package com.weisgnosis.agents.harness.subagent

import com.weisgnosis.agents.core.agent.{AgentConfig, AgentResult, ToolExecution}
import com.weisgnosis.agents.core.client.{LlmClient, LlmConfig, TurnOutput}
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.model.{ContentBlock, Msg, Role, ToolResultState}
import com.weisgnosis.agents.core.tool.{Tool, ToolDef, Toolkit}
import com.weisgnosis.agents.harness.permission.*
import com.weisgnosis.agents.harness.plan.*
import zio.*
import zio.json.ast.Json
import zio.test.*

object SubagentConstructionSpec extends ZIOSpecDefault:
  private val allow: PermissionEngine = PermissionEngine(
    Chunk(PermissionRule("*", PermissionBehavior.Allow))
  )
  private val safe: Json    = Json.Obj("command" -> Json.Str("safe"))
  private val blocked: Json = Json.Obj("command" -> Json.Str("blocked"))

  private def permissions(
      policy: PermissionEngine,
      access: Map[String, PlanToolAccess]
  ): UIO[SubagentPermissions] =
    for
      requests <- PermissionRequests.make
      plan     <- PlanSession.make
      authorizer <- InteractivePermissionAuthorizer.make(
        requests,
        request => requests.resolve(request.requestId, ConfirmResult(false)).unit,
        policy
      )
    yield SubagentPermissions(authorizer, plan.control, access)

  private def factory(
      construction: SubagentConstruction,
      initialize: ZIO[Scope, AgentError, SubagentPermissions],
      register: (SubagentConstruction, SubagentPermissions) => ZIO[Scope, AgentError, Toolkit],
      client: SubagentSpawner[AgentResult] => ZIO[Scope, AgentError, LlmClient],
      boundary: SubagentToolBoundary = SubagentToolBoundary()
  ): IO[AgentError, SubagentFactory] =
    ZIO.fromEither(
      construction.factory(
        AgentConfig(toolExecution = ToolExecution.Sequential),
        LlmConfig("constructed-child"),
        Toolkit.empty,
        boundary,
        initialize,
        register,
        client
      )
    )

  private def probes(names: Chunk[String], executed: Ref[Chunk[(String, Json)]]): Toolkit =
    Toolkit(
      names
        .map(name =>
          name -> Tool.make(ToolDef(name, "Record admitted execution", Json.Obj()))(input =>
            executed.update(_ :+ (name, input)).as("executed")
          )
        )
        .toMap
    )

  private def scripted(calls: Chunk[ContentBlock.ToolUse]): UIO[LlmClient] =
    LlmClient.mock(
      Chunk(TurnOutput.ToolCalls(Msg.assistant(calls)), TurnOutput.FinalText(Msg.assistant("done")))
    )

  private def run(
      factory: SubagentFactory,
      parent: SubagentSpawner[AgentResult]
  ): ZIO[Scope, AgentError, AgentResult] =
    factory
      .spawn(parent, Msg.user("delegate"))
      .flatMap:
        case TaskAdmission.Accepted(task) => task.await.flatMap(exit => exit)
        case TaskAdmission.Closed =>
          ZIO.fail(AgentError.InvalidConfiguration("Unexpected closed task owner"))

  private def session(
      parent: SubagentSpawner[AgentResult],
      factory: SubagentFactory
  ): ZIO[Scope, AgentError, TaskResource[SubagentSession]] =
    SubagentSession
      .open(parent, factory)
      .flatMap:
        case TaskResourceAdmission.Accepted(resource) => ZIO.succeed(resource)
        case TaskResourceAdmission.Closed =>
          ZIO.fail(AgentError.InvalidConfiguration("Unexpected closed session owner"))

  private def turn(
      session: SubagentSession,
      prompt: String
  ): ZIO[Scope, AgentError, ToolResultState] =
    session
      .send(Msg.user(prompt))
      .flatMap:
        case SubagentTaskSubmission.Accepted(id) =>
          session.executions
            .await(id)
            .flatMap(value =>
              ZIO
                .fromOption(value)
                .orElseFail(AgentError.InvalidConfiguration("Missing execution"))
            )
            .flatMap(exit => exit)
            .flatMap(result =>
              ZIO
                .fromOption(result.history.flatMap(_.toolResults).lastOption.map(_.state))
                .orElseFail(AgentError.InvalidConfiguration("Missing tool result"))
            )
        case other => ZIO.fail(AgentError.InvalidConfiguration(s"Unexpected submission: $other"))

  def spec: Spec[TestEnvironment & Scope, Any] = suite("SubagentConstructionSpec")(
    test(
      "constructed children opt into admitted Plan access without overriding host or parent denials"
    ) {
      val cases = Chunk(
        (
          SubagentPlanAccess.ExplicitAccess,
          Map.empty[String, PlanToolAccess],
          ToolResultState.Denied
        ),
        (
          SubagentPlanAccess.AdmittedTools,
          Map.empty[String, PlanToolAccess],
          ToolResultState.Success
        ),
        (
          SubagentPlanAccess.AdmittedTools,
          Map("read" -> PlanToolAccess.Mutation),
          ToolResultState.Denied
        )
      )
      ZIO
        .foreach(cases) { case (admission, access, expectedRead) =>
          ZIO.scoped:
            for
              supervisor <- TaskSupervisor.open[AgentResult]
              root       <- SubagentSpawner.root(supervisor)
              parentMode <- Ref.make(PlanMode.Build)
              policy     <- Ref.make(PermissionEngine())
              executed   <- Ref.make(Chunk.empty[String])
              construction = SubagentConstruction.root(
                root,
                SubagentToolBoundary(),
                policy.get,
                parentMode.get
              )
              tools = Toolkit(
                Chunk("read", "write")
                  .map(name =>
                    name -> Tool.make(
                      ToolDef(name, "Child access probe", Json.Obj(), isReadOnly = name == "read")
                    )(_ => executed.update(_ :+ name).as("executed"))
                  )
                  .toMap
              )
              client = LlmClient.mockEffectful((history, _, _) =>
                ZIO.succeed(
                  if history.lastOption.exists(_.role == Role.User) then
                    val name = history.lastOption.fold("")(_.text)
                    TurnOutput.ToolCalls(
                      Msg.assistant(Chunk(ContentBlock.ToolUse(name, name, Json.Obj())))
                    )
                  else TurnOutput.FinalText(Msg.assistant("done"))
                )
              )
              configured <- factory(
                construction,
                permissions(allow, access).map(_.copy(planAccess = admission)),
                (_, _) => ZIO.succeed(tools),
                _ => ZIO.succeed(client)
              )
              resource   <- session(root, configured)
              buildRead  <- turn(resource.value, "read")
              buildWrite <- turn(resource.value, "write")
              _          <- parentMode.set(PlanMode.Plan)
              planRead   <- turn(resource.value, "read")
              planWrite  <- turn(resource.value, "write")
              _ <- policy.set(
                PermissionEngine(Chunk(PermissionRule("read", PermissionBehavior.Deny)))
              )
              denied <- turn(resource.value, "read")
              actual <- executed.get
            yield assertTrue(
              Chunk(buildRead, buildWrite, planRead, planWrite, denied) == Chunk(
                ToolResultState.Success,
                ToolResultState.Success,
                expectedRead,
                ToolResultState.Denied,
                ToolResultState.Denied
              ),
              actual == (Chunk("read", "write") ++
                (if expectedRead == ToolResultState.Success then Chunk("read") else Chunk.empty))
            )
        }
        .map(_.foldLeft(assertTrue(true))(_ && _))
    },
    test(
      "nested construction preserves narrowed boundaries and ancestor argument denials through factory derivatives"
    ) {
      val deniedNames = Chunk("root_hidden", "child_hidden", "later_hidden")
      val arguments =
        Chunk("root-blocked", "child-blocked", "late-blocked", "root-blocked-near-miss")
      val calls = arguments.map(value =>
        ContentBlock.ToolUse(value, "read", Json.Obj("command" -> Json.Str(value)))
      ) ++
        deniedNames.map(name => ContentBlock.ToolUse(name, name, safe))
      for
        supervisor <- TaskSupervisor.open[AgentResult]
        root       <- SubagentSpawner.root(supervisor)
        parentPolicy <- Ref.make(
          PermissionEngine(
            Chunk(PermissionRule("read", PermissionBehavior.Deny, Some("root-blocked")))
          )
        )
        laterPolicy <- Ref.make(
          PermissionEngine(
            Chunk(PermissionRule("read", PermissionBehavior.Deny, Some("late-blocked")))
          )
        )
        executed    <- Ref.make(Chunk.empty[(String, Json)])
        schemas     <- Ref.make(Chunk.empty[Set[String]])
        grandResult <- Ref.make(Option.empty[AgentResult])
        construction = SubagentConstruction.root(
          root,
          SubagentToolBoundary(denied = Set("root_hidden")),
          parentPolicy.get,
          ZIO.succeed(PlanMode.Build)
        )
        outer <- factory(
          construction,
          permissions(
            PermissionEngine(
              allow.rules :+ PermissionRule("read", PermissionBehavior.Deny, Some("child-blocked"))
            ),
            Map("delegate" -> PlanToolAccess.ReadOnly)
          ),
          (child, _) =>
            factory(
              child,
              permissions(allow, Map("read" -> PlanToolAccess.ReadOnly)),
              (_, _) => ZIO.succeed(probes(Chunk("read") ++ deniedNames, executed)),
              _ =>
                ZIO.succeed(
                  LlmClient.mockEffectful((history, tools, _) =>
                    schemas
                      .update(_ :+ tools.map(_.name).toSet)
                      .as(
                        if history.lastOption.exists(_.role == Role.User) then
                          TurnOutput.ToolCalls(Msg.assistant(calls))
                        else TurnOutput.FinalText(Msg.assistant("done"))
                      )
                  )
                )
            ).map(grandchild =>
              Toolkit(
                Map(
                  "delegate" -> Tool.make(ToolDef("delegate", "Run a nested child", Json.Obj()))(
                    _ =>
                      ZIO
                        .scoped(run(grandchild, child.spawner))
                        .tap(result => grandResult.set(Some(result)))
                        .as("delegated")
                  )
                )
              )
            ),
          _ => scripted(Chunk(ContentBlock.ToolUse("delegate", "delegate", safe))),
          SubagentToolBoundary(denied = Set("child_hidden"))
        )
        derived = outer
          .narrowed(SubagentToolBoundary(denied = Set("later_hidden")))
          .inheritingParentDenials(laterPolicy.get)
        result <- run(derived, root)
        grand  <- grandResult.get
        actual <- executed.get
        seen   <- schemas.get
      yield assertTrue(
        result.history.flatMap(_.toolResults).map(_.state) == Chunk(ToolResultState.Success),
        grand.exists(
          _.history.flatMap(_.toolResults).map(_.state) == Chunk(
            ToolResultState.Denied,
            ToolResultState.Denied,
            ToolResultState.Denied,
            ToolResultState.Success,
            ToolResultState.Error,
            ToolResultState.Error,
            ToolResultState.Error
          )
        ),
        actual == Chunk("read" -> Json.Obj("command" -> Json.Str("root-blocked-near-miss"))),
        seen.nonEmpty && seen.forall(_ == Set("read"))
      )
    },
    test(
      "a reused constructed child samples live ancestor policy and Plan and rechecks confirmed input after its own review"
    ) {
      for
        supervisor   <- TaskSupervisor.open[AgentResult]
        root         <- SubagentSpawner.root(supervisor)
        parentPolicy <- Ref.make(PermissionEngine())
        parentMode   <- Ref.make(PlanMode.Build)
        childPlan    <- Promise.make[Nothing, PlanSession]
        prompts      <- Ref.make(0)
        executed     <- Ref.make(Chunk.empty[(String, Json)])
        construction = SubagentConstruction.root(
          root,
          SubagentToolBoundary(),
          parentPolicy.get,
          parentMode.get
        )
        initialize = for
          requests <- PermissionRequests.make
          plan     <- PlanSession.make
          _        <- childPlan.succeed(plan)
          authorizer <- InteractivePermissionAuthorizer.make(
            requests,
            request =>
              prompts.update(_ + 1) *> requests
                .resolve(request.requestId, ConfirmResult(true, Some(blocked)))
                .unit,
            allow
          )
        yield SubagentPermissions(
          authorizer,
          plan.control,
          Map(
            "write"             -> PlanToolAccess.Mutation,
            "request_execution" -> PlanToolAccess.RequestExecution
          )
        )
        configured <- factory(
          construction,
          initialize,
          (_, _) => ZIO.succeed(probes(Chunk("write", "request_execution"), executed)),
          _ =>
            ZIO.succeed(
              LlmClient.mockEffectful((history, _, _) =>
                ZIO.succeed(
                  if history.lastOption.exists(_.role == Role.User) then
                    val prompt = history.lastOption.fold("")(_.text)
                    val name   = if prompt == "request" then "request_execution" else "write"
                    TurnOutput.ToolCalls(
                      Msg.assistant(Chunk(ContentBlock.ToolUse(prompt, name, safe)))
                    )
                  else TurnOutput.FinalText(Msg.assistant("done"))
                )
              )
            )
        )
        resource <- session(root, configured)
        first    <- turn(resource.value, "first")
        _ <- parentPolicy.set(
          PermissionEngine(Chunk(PermissionRule("write", PermissionBehavior.Deny)))
        )
        denied   <- turn(resource.value, "denied")
        _        <- parentPolicy.set(PermissionEngine()) *> parentMode.set(PlanMode.Plan)
        planning <- turn(resource.value, "planning")
        plan     <- childPlan.await
        draft    <- ZIO.fromEither(PlanDraft.fromString("Child-owned plan"))
        _ <- plan.control.enter *> plan.control.replaceDraft(draft) *> parentMode.set(
          PlanMode.Build
        )
        ownPlan  <- turn(resource.value, "own-plan")
        review   <- plan.control.requestReview
        approved <- plan.reviewer.resolve(review, PlanReviewDecision.Approve)
        _        <- parentMode.set(PlanMode.Plan)
        reviewed <- turn(resource.value, "reviewed")
        _ <- parentPolicy.set(
          PermissionEngine(
            Chunk(PermissionRule("request_execution", PermissionBehavior.Deny, Some("blocked")))
          )
        )
        modified  <- turn(resource.value, "request")
        _         <- parentMode.set(PlanMode.Build)
        last      <- turn(resource.value, "last")
        childMode <- plan.control.mode
        count     <- prompts.get
        actual    <- executed.get
      yield assertTrue(
        Chunk(first, denied, planning, ownPlan, reviewed, modified, last) == Chunk(
          ToolResultState.Success,
          ToolResultState.Denied,
          ToolResultState.Denied,
          ToolResultState.Denied,
          ToolResultState.Denied,
          ToolResultState.Denied,
          ToolResultState.Success
        ),
        approved == PlanReviewResolution.Applied,
        childMode == PlanMode.Build,
        count == 1,
        actual == Chunk("write" -> safe, "write" -> safe)
      )
    },
    test(
      "per-build permission resources are lazy isolated and joined on cancelled initialization or final toolkit rejection"
    ) {
      for
        supervisor <- TaskSupervisor.open[AgentResult]
        root       <- SubagentSpawner.root(supervisor)
        acquired   <- Ref.make(0)
        released   <- Ref.make(0)
        prompts    <- Ref.make(0)
        executed   <- Ref.make(Chunk.empty[(String, Json)])
        construction = SubagentConstruction.root(
          root,
          SubagentToolBoundary(),
          ZIO.succeed(PermissionEngine()),
          ZIO.succeed(PlanMode.Build)
        )
        initialize = ZIO.acquireRelease(acquired.update(_ + 1))(_ => released.update(_ + 1)) *> (for
          requests <- PermissionRequests.make
          plan     <- PlanSession.make
          authorizer <- InteractivePermissionAuthorizer.make(
            requests,
            request =>
              prompts.update(_ + 1) *> requests
                .resolve(
                  request.requestId,
                  ConfirmResult(
                    true,
                    newRules = Chunk(PermissionRule("read", PermissionBehavior.Allow))
                  )
                )
                .unit
          )
        yield SubagentPermissions(authorizer, plan.control, Map("read" -> PlanToolAccess.ReadOnly)))
        model = LlmClient.mockEffectful((history, _, _) =>
          ZIO.succeed(
            if history.lastOption.exists(_.role == Role.User) then
              TurnOutput.ToolCalls(Msg.assistant(Chunk(ContentBlock.ToolUse("read", "read", safe))))
            else TurnOutput.FinalText(Msg.assistant("done"))
          )
        )
        configured <- factory(
          construction,
          initialize,
          (_, _) => ZIO.succeed(probes(Chunk("read"), executed)),
          _ => ZIO.succeed(model)
        )
        before         <- acquired.get
        left           <- session(root, configured)
        right          <- session(root, configured)
        first          <- turn(left.value, "first")
        again          <- turn(left.value, "again")
        leftPrompts    <- prompts.get
        separate       <- turn(right.value, "separate")
        siblingPrompts <- prompts.get
        _              <- left.close
        oneReleased    <- released.get
        stillLive      <- turn(right.value, "still live")
        _              <- right.close
        entered        <- Promise.make[Nothing, Unit]
        cleaning       <- Promise.make[Nothing, Unit]
        release        <- Promise.make[Nothing, Unit]
        laterEffects   <- Ref.make(0)
        cancellingFactory <- factory(
          construction,
          ZIO.acquireRelease(entered.succeed(()))(_ =>
            cleaning.succeed(()) *> release.await
          ) *> ZIO.never,
          (_, _) => laterEffects.update(_ + 1).as(Toolkit.empty),
          _ => laterEffects.update(_ + 1).as(model)
        )
        initializing <- session(root, cancellingFactory).forkScoped
        _            <- entered.await
        stopping     <- initializing.interrupt.forkScoped
        _            <- cleaning.await
        pending      <- stopping.poll
        _            <- release.succeed(())
        cancelled    <- stopping.join
        readTool = Tool.make(ToolDef("read", "Read fixture", Json.Obj()))(_ => ZIO.succeed("done"))
        invalid <- factory(
          construction,
          initialize,
          (_, _) => ZIO.succeed(Toolkit(Map("mismatched" -> readTool))),
          _ => laterEffects.update(_ + 1).as(model)
        )
        rejected         <- run(invalid, root).exit
        builds           <- acquired.get
        cleanup          <- released.get
        forbiddenEffects <- laterEffects.get
      yield assertTrue(
        before == 0,
        Chunk(first, again, separate, stillLive).forall(_ == ToolResultState.Success),
        leftPrompts == 1 && siblingPrompts == 2,
        oneReleased == 1,
        pending.isEmpty && cancelled.isInterrupted,
        rejected.causeOption.exists(_.failures.exists {
          case _: AgentError.InvalidConfiguration => true
          case _                                  => false
        }),
        builds == 3 && cleanup == 3,
        forbiddenEffects == 0
      )
    }
  )
