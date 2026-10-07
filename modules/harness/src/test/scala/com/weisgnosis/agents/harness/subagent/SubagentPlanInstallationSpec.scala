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
import zio.json.*
import zio.json.ast.Json
import zio.test.*

object SubagentPlanInstallationSpec extends ZIOSpecDefault:
  private val policy: PermissionEngine = PermissionEngine(
    Chunk(PermissionRule("*", PermissionBehavior.Allow))
  )
  private val config: AgentConfig = AgentConfig(toolExecution = ToolExecution.Sequential)
  private val model: LlmConfig    = LlmConfig("child-plan")
  private val draftText: String   = "Inspect the selected child workspace."

  final private case class Installed(
      permissions: SubagentPermissions,
      session: PlanSession,
      writes: Ref[Chunk[PlanDraft]],
      reviews: Ref[Chunk[PlanDraft]]
  )

  private def installed(
      name: String,
      initial: PlanMode,
      overrides: Map[String, PlanToolAccess] = Map.empty
  ): IO[AgentError, Installed] =
    for
      configuredLocation <- ZIO.fromEither(PlanLocation.fromString(s"$name/PLAN.md"))
      stored             <- Ref.make(Option.empty[PlanDraft])
      writes             <- Ref.make(Chunk.empty[PlanDraft])
      reviews            <- Ref.make(Chunk.empty[PlanDraft])
      session <- PlanSession.stored(new PlanDraftStore:
        override val location: Option[PlanLocation] = Some(configuredLocation)
        def read: IO[AgentError, Option[PlanDraft]] = stored.get
        def write(draft: PlanDraft): IO[AgentError, Unit] =
          stored.set(Some(draft)) *> writes.update(_ :+ draft))
      _ <- initial match
        case PlanMode.Plan => ZIO.unit
        case PlanMode.Build =>
          ZIO.scoped:
            for
              draft  <- ZIO.fromEither(PlanDraft.fromString("Previously approved child draft"))
              _      <- session.control.replaceDraft(draft)
              ticket <- session.control.requestReview
              _      <- session.reviewer.resolve(ticket, PlanReviewDecision.Approve)
            yield ()
      requests <- PermissionRequests.make
      authorizer <- InteractivePermissionAuthorizer.make(
        requests,
        request => requests.resolve(request.requestId, ConfirmResult(false)).unit,
        policy
      )
      publisher = PlanReviewPublisher(request =>
        reviews.update(_ :+ request.review.draft) *> session.reviewer
          .resolve(request.review, PlanReviewDecision.Approve)
          .unit
      )
    yield Installed(
      SubagentPermissions(
        authorizer,
        session.control,
        Map(
          "shell"  -> PlanToolAccess.PosixReadCommand,
          "mutate" -> PlanToolAccess.Mutation
        ) ++ overrides,
        planInstallation = SubagentPlanInstallation.Enabled(publisher)
      ),
      session,
      writes,
      reviews
    )

  private def tools(executed: Ref[Chunk[String]]): Toolkit = Chunk("shell", "mutate")
    .map(name =>
      Tool.make(ToolDef(name, name, Json.Obj()))(_ => executed.update(_ :+ name).as("executed"))
    )
    .foldLeft(Toolkit.empty)(_.updated(_))

  private def call(id: String, name: String, input: Json = Json.Obj()): ContentBlock.ToolUse =
    ContentBlock.ToolUse(id, name, input)

  private def write: ContentBlock.ToolUse =
    call("write", "plan_write", Json.Obj("content" -> Json.Str(draftText)))

  private def run(
      factory: SubagentFactory,
      root: SubagentSpawner[AgentResult]
  ): ZIO[Scope, AgentError, AgentResult] =
    factory
      .spawn(root, Msg.user("delegate"))
      .flatMap:
        case TaskAdmission.Accepted(task) => task.await.flatMap(exit => exit)
        case TaskAdmission.Closed => ZIO.fail(AgentError.InvalidConfiguration("Closed test owner"))

  private def namedSend(
      catalog: SubagentSessionCatalog,
      name: String,
      prompt: String
  ): IO[AgentError, AgentResult] =
    for
      observation <- SubagentTools
        .forSessionCatalog(catalog)
        .executeSafe(
          "agent_send",
          Json.Obj("agent_id" -> Json.Str(name), "prompt" -> Json.Str(prompt))
        )
      decoded <- ZIO
        .fromEither(observation.output.dropWhile(_ != '{').fromJson[SubagentCatalogToolResult])
        .mapError(AgentError.InvalidConfiguration(_))
      taskId <- decoded match
        case SubagentCatalogToolResult.Agent(
              `name`,
              SubagentToolResult.Task(id, SubagentToolState.Completed(_))
            ) =>
          ZIO.succeed(id)
        case other => ZIO.fail(AgentError.InvalidConfiguration(s"Unexpected selected task: $other"))
      selected <- catalog
        .lookup(name)
        .someOrFail(AgentError.InvalidConfiguration("Missing selected session"))
      tasks <- selected._2.executions.list
      result <- tasks.find(_.id.value.toString == taskId).map(_.state) match
        case Some(SubagentTaskState.Completed(Exit.Success(value))) => ZIO.succeed(value)
        case other => ZIO.fail(AgentError.InvalidConfiguration(s"Missing completed task: $other"))
    yield result

  def spec: Spec[TestEnvironment & Scope, Any] = suite("SubagentPlanInstallationSpec")(
    test(
      "declared and general-purpose children install local Plan capabilities within final boundaries"
    ):
      for
        supervisor <- TaskSupervisor.open[AgentResult]
        root       <- SubagentSpawner.root(supervisor)
        parent     <- Ref.make(PlanMode.Plan)
        construction = SubagentConstruction.root(
          root,
          SubagentToolBoundary(),
          ZIO.succeed(policy),
          parent.get
        )
        checks <- ZIO.foreach(
          Chunk(
            (
              SubagentDeclaration("worker", "Worker", "Investigate"),
              Map.empty[String, PlanToolAccess]
            ),
            (SubagentDeclaration.generalPurpose, Map.empty[String, PlanToolAccess]),
            (
              SubagentDeclaration("restricted", "Restricted", "Investigate"),
              Map("plan_write" -> PlanToolAccess.Mutation)
            )
          )
        ): (declaration, overrides) =>
          for
            instances <- Ref.make(Chunk.empty[Installed])
            executed  <- Ref.make(Chunk.empty[String])
            requests  <- Ref.make(Chunk.empty[(Chunk[Msg], Chunk[ToolDef])])
            factory <- ZIO.fromEither(
              declaration.factory(
                construction,
                config,
                model,
                Toolkit.empty,
                installed(declaration.name, PlanMode.Plan, overrides)
                  .tap(value => instances.update(_ :+ value))
                  .map(_.permissions),
                (_, _) => ZIO.succeed(tools(executed)),
                _ =>
                  ZIO.succeed(
                    LlmClient.mockEffectful((history, definitions, _) =>
                      requests
                        .modify(previous => previous.size -> (previous :+ (history, definitions)))
                        .map(index =>
                          if index == 0 then
                            TurnOutput.ToolCalls(
                              Msg.assistant(
                                Chunk(
                                  call("enter", "plan_enter"),
                                  write,
                                  call("exit", "plan_exit"),
                                  call("read", "shell", Json.Obj("command" -> Json.Str("ls -la"))),
                                  call(
                                    "unsafe",
                                    "shell",
                                    Json.Obj("command" -> Json.Str("rm file"))
                                  ),
                                  call("mutation", "mutate")
                                )
                              )
                            )
                          else TurnOutput.FinalText(Msg.assistant("done"))
                        )
                    )
                  )
              )
            )
            result    <- run(factory, root)
            children  <- instances.get
            writes    <- ZIO.foreach(children)(_.writes.get)
            reviews   <- ZIO.foreach(children)(_.reviews.get)
            snapshots <- ZIO.foreach(children)(_.session.control.snapshot)
            observed  <- requests.get
            ran       <- executed.get
          yield assertTrue(
            children.size == 1,
            observed.forall(value => PlanTools.access.keySet.subsetOf(value._2.map(_.name).toSet)),
            observed.forall(_._1.exists(_.text.contains(s"${declaration.name}/PLAN.md"))),
            observed.headOption.exists(_._1.exists(_.text.contains("PLAN MODE is active"))),
            !result.history.exists(_.text.contains("PLAN MODE is active")),
            ran == Chunk("shell"),
            result.history.flatMap(_.toolResults).map(_.state) ==
              (if overrides.isEmpty then
                 Chunk(
                   ToolResultState.Success,
                   ToolResultState.Success,
                   ToolResultState.Success,
                   ToolResultState.Success,
                   ToolResultState.Denied,
                   ToolResultState.Denied
                 )
               else
                 Chunk(
                   ToolResultState.Success,
                   ToolResultState.Denied,
                   ToolResultState.Error,
                   ToolResultState.Success,
                   ToolResultState.Denied,
                   ToolResultState.Denied
                 )),
            writes.flatten.map(_.content) == (if overrides.isEmpty then Chunk(draftText)
                                              else Chunk.empty),
            reviews.flatten.map(_.content) == (if overrides.isEmpty then Chunk(draftText)
                                               else Chunk.empty),
            snapshots.forall(
              _.mode == (if overrides.isEmpty then PlanMode.Build else PlanMode.Plan)
            ),
            overrides.nonEmpty || observed.lastOption.exists(
              _._1.exists(message =>
                message.text.contains("local approval") && !message.text.contains(
                  "Execute the reviewed plan"
                )
              )
            )
          )
        registrations <- Ref.make(0)
        clients       <- Ref.make(0)
        guard <- ZIO.fromEither(
          construction.factory(
            config,
            model,
            Toolkit.empty,
            SubagentToolBoundary(),
            installed("guard", PlanMode.Plan).map(_.permissions),
            (_, _) =>
              registrations
                .update(_ + 1)
                .as(
                  Toolkit.single(
                    Tool.make(
                      ToolDef("plan_enter", "Conflicting host tool", Json.Obj())
                    )(_ => ZIO.succeed("unused"))
                  )
                ),
            _ => clients.update(_ + 1) *> LlmClient.mock(Chunk.empty)
          )
        )
        excluded <- guard.narrowed(SubagentToolBoundary(denied = Set("plan_exit"))).build(root).exit
        before   <- registrations.get
        collision   <- guard.build(root).exit
        after       <- registrations.get
        clientCount <- clients.get
      yield checks.foldLeft(
        assertTrue(
          excluded.causeOption
            .flatMap(_.failureOption)
            .contains(
              AgentError.InvalidConfiguration(
                "Required child runtime control 'plan_exit' is excluded by the tool boundary"
              )
            ),
          before == 0,
          collision.causeOption
            .flatMap(_.failureOption)
            .contains(
              AgentError.ToolExecutionError(
                "plan_enter",
                "Tool collision: 'plan_enter' is already registered"
              )
            ),
          after == 1,
          clientCount == 0
        )
      )(_ && _)
    ,
    test(
      "named sends reuse one child while live ancestor Plan and local review remain independent"
    ):
      for
        supervisor <- TaskSupervisor.open[AgentResult]
        root       <- SubagentSpawner.root(supervisor)
        parent     <- Ref.make(PlanMode.Build)
        construction = SubagentConstruction.root(
          root,
          SubagentToolBoundary(),
          ZIO.succeed(policy),
          parent.get
        )
        children    <- Ref.make(Chunk.empty[Installed])
        allocations <- Ref.make(0)
        executed    <- Ref.make(Chunk.empty[String])
        requests    <- Ref.make(Chunk.empty[Chunk[Msg]])
        factory <- ZIO.fromEither(
          SubagentDeclaration.generalPurpose.factory(
            construction,
            config,
            model,
            Toolkit.empty,
            installed("retained", PlanMode.Build)
              .tap(value => children.update(_ :+ value))
              .map(_.permissions),
            (_, _) => ZIO.succeed(tools(executed)),
            _ =>
              allocations
                .update(_ + 1)
                .as(
                  LlmClient.mockEffectful((history, _, _) =>
                    requests
                      .modify(previous => previous.size -> (previous :+ history))
                      .map(index =>
                        if index % 2 == 1 then TurnOutput.FinalText(Msg.assistant("done"))
                        else
                          TurnOutput.ToolCalls(
                            Msg.assistant(
                              (if index == 6 then Chunk(write, call("exit", "plan_exit"))
                               else Chunk.empty) :+ call(s"mutate-$index", "mutate")
                            )
                          )
                      )
                  )
                )
          )
        )
        admission <- SubagentSessionCatalog.open(
          root,
          Chunk(SubagentEntry("worker", "Worker", factory))
        )
        catalog <- admission match
          case TaskResourceAdmission.Accepted(resource) => ZIO.succeed(resource.value)
          case TaskResourceAdmission.Closed =>
            ZIO.fail(AgentError.InvalidConfiguration("Closed test catalog"))
        original <- catalog.lookup("worker")
        first    <- namedSend(catalog, "worker", "first")
        _        <- parent.set(PlanMode.Plan)
        second   <- namedSend(catalog, "worker", "second")
        child <- children.get.flatMap(values =>
          ZIO.fromOption(values.headOption).orElseFail(AgentError.InvalidConfiguration("No child"))
        )
        inheritedOnly    <- child.session.control.snapshot
        _                <- parent.set(PlanMode.Build)
        _                <- child.session.control.enter
        third            <- namedSend(catalog, "worker", "third")
        _                <- parent.set(PlanMode.Plan)
        fourth           <- namedSend(catalog, "worker", "fourth")
        stillIndependent <- child.session.control.snapshot
        _                <- parent.set(PlanMode.Build)
        fifth            <- namedSend(catalog, "worker", "fifth")
        reused           <- catalog.lookup("worker")
        count            <- allocations.get
        instances        <- children.get
        seen             <- requests.get
        ran              <- executed.get
      yield assertTrue(
        original.map(_._1) == reused.map(_._1),
        count == 1,
        instances.size == 1,
        Chunk(first, second, third, fourth, fifth).map(
          _.history.flatMap(_.toolResults).lastOption.map(_.state)
        ) ==
          Chunk(
            ToolResultState.Success,
            ToolResultState.Denied,
            ToolResultState.Denied,
            ToolResultState.Denied,
            ToolResultState.Success
          ).map(Some(_)),
        inheritedOnly.mode == PlanMode.Build,
        stillIndependent.mode == PlanMode.Build,
        fourth.history.flatMap(_.toolResults).takeRight(3).map(_.state) == Chunk(
          ToolResultState.Success,
          ToolResultState.Success,
          ToolResultState.Denied
        ),
        seen
          .lift(2)
          .exists(
            _.exists(message =>
              message.text.contains("ancestor requires read-only") && message.text
                .contains("local approval")
            )
          ),
        seen.lift(4).exists(_.exists(_.text.contains("PLAN MODE is active"))),
        fifth.history.filter(_.role == Role.User).map(_.text) == Chunk(
          "first",
          "second",
          "third",
          "fourth",
          "fifth"
        ),
        !fifth.history.exists(_.text.contains("<system-reminder>")),
        ran == Chunk("mutate", "mutate")
      )
  ) @@ TestAspect.timeout(20.seconds)
