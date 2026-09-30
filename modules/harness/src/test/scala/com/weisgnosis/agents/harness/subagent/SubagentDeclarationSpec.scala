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
import com.weisgnosis.agents.core.event.{AgentEvent, ModelStreamContext}
import com.weisgnosis.agents.core.model.{ContentBlock, Msg, Role, ToolResultState}
import com.weisgnosis.agents.core.skill.{SkillCatalog, SkillDefinition}
import com.weisgnosis.agents.core.tool.{Tool, ToolDef, Toolkit}
import com.weisgnosis.agents.harness.permission.*
import com.weisgnosis.agents.harness.plan.*
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

object SubagentDeclarationSpec extends ZIOSpecDefault:
  private val allow: PermissionEngine = PermissionEngine(
    Chunk(PermissionRule("*", PermissionBehavior.Allow))
  )
  private val parentConfig: AgentConfig = AgentConfig(
    systemPrompt = "Parent-only instructions",
    maxIterations = 4,
    timeout = 2.minutes,
    emptyThinkingRetryLimit = 1,
    toolExecution = ToolExecution.Sequential
  )
  private val parentModel: LlmConfig = LlmConfig(
    "parent-model",
    "https://host.invalid/model",
    Some("host-secret"),
    0.4,
    Some(300)
  )
  private val declared: SubagentDeclaration = SubagentDeclaration(
    "reviewer",
    "Reviews delegated work",
    "Review carefully."
  )

  private def permissions(plan: PlanSession): UIO[SubagentPermissions] =
    for
      requests <- PermissionRequests.make
      authorizer <- InteractivePermissionAuthorizer.make(
        requests,
        request => requests.resolve(request.requestId, ConfirmResult(false)).unit,
        allow
      )
    yield SubagentPermissions(authorizer, plan.control, Map("write" -> PlanToolAccess.Mutation))

  private def tools(names: Chunk[String], executed: Ref[Chunk[String]]): Toolkit =
    Toolkit(
      names
        .map(name =>
          name -> Tool.make(ToolDef(name, "Record execution", Json.Obj()))(_ =>
            executed.update(_ :+ name).as("done")
          )
        )
        .toMap
    )

  private def client(
      observe: (Chunk[Msg], Chunk[ToolDef], LlmConfig) => UIO[Unit],
      calls: Chunk[String]
  ): LlmClient = new LlmClient:
    def streamTurn(
        history: Chunk[Msg],
        definitions: Chunk[ToolDef],
        config: LlmConfig,
        events: Hub[AgentEvent],
        context: ModelStreamContext
    ): ZIO[Scope, AgentError, TurnOutput] =
      observe(history, definitions, config).as(
        if history.lastOption.exists(_.role == Role.User) then
          TurnOutput.ToolCalls(
            Msg.assistant(calls.map(name => ContentBlock.ToolUse(name, name, Json.Obj())))
          )
        else TurnOutput.FinalText(Msg.assistant("finished"))
      )

  private def openSession(
      parent: SubagentSpawner[AgentResult],
      factory: SubagentFactory
  ): ZIO[Scope, AgentError, TaskResource[SubagentSession]] =
    SubagentSession
      .open(parent, factory)
      .flatMap:
        case TaskResourceAdmission.Accepted(resource) => ZIO.succeed(resource)
        case TaskResourceAdmission.Closed =>
          ZIO.fail(AgentError.InvalidConfiguration("Closed test owner"))

  private def turn(
      session: SubagentSession,
      input: String
  ): ZIO[Scope, AgentError, (SubagentTaskId, AgentResult)] =
    session
      .send(Msg.user(input))
      .flatMap:
        case SubagentTaskSubmission.Accepted(id) =>
          session.executions
            .await(id)
            .flatMap:
              case Some(exit) => exit.map(result => (id, result))
              case None       => ZIO.fail(AgentError.InvalidConfiguration("Missing test execution"))
        case other =>
          ZIO.fail(AgentError.InvalidConfiguration(s"Unexpected test admission: $other"))

  def spec: Spec[TestEnvironment & Scope, Any] = suite("SubagentDeclarationSpec")(
    test("validation rejects invalid declarations and configuration before installation") {
      for
        supervisor <- TaskSupervisor.open[AgentResult]
        parent     <- SubagentSpawner.root(supervisor)
        acquired   <- Ref.make(0)
        construction = SubagentConstruction.root(
          parent,
          SubagentToolBoundary(),
          ZIO.succeed(PermissionEngine()),
          ZIO.succeed(PlanMode.Build)
        )
        initialize = acquired.update(_ + 1) *> PlanSession.make.flatMap(permissions)
        compile = (value: SubagentDeclaration, config: AgentConfig) =>
          value.factory(
            construction,
            config,
            parentModel,
            Toolkit.empty,
            initialize,
            (_, _) => acquired.update(_ + 1).as(Toolkit.empty),
            _ => acquired.update(_ + 1) *> LlmClient.mock(Chunk.empty)
          )
        invalid = Chunk(
          declared.copy(name = " "),
          declared.copy(description = ""),
          declared.copy(model = Some(" ")),
          declared.copy(temperature = Some(Double.NaN)),
          declared.copy(temperature = Some(Double.PositiveInfinity)),
          declared.copy(maxIterations = Some(0)),
          declared.copy(tools = SubagentToolSelection.Only(Set(""))),
          declared.copy(skills = SubagentSkillSelection.Only(Set(" "))),
          declared.copy(skills = SubagentSkillSelection.Only(Set("unknown")))
        ).map(compile(_, parentConfig))
        valid         = compile(declared, parentConfig)
        invalidParent = compile(declared, parentConfig.copy(timeout = Duration.Zero))
        roundTrip = declared.copy(
          tools = SubagentToolSelection.Only(Set.empty),
          skills = SubagentSkillSelection.Only(Set("guide"))
        )
        untouched <- acquired.get
      yield assertTrue(
        invalid.forall(_.isLeft),
        invalidParent.isLeft,
        valid.isRight,
        untouched == 0,
        roundTrip.toJson.fromJson[SubagentDeclaration] == Right(roundTrip),
        """{"name":"reviewer","description":"Reviews delegated work","systemPrompt":"Review carefully."}"""
          .fromJson[SubagentDeclaration] == Right(declared)
      )
    },
    test("parsed skill declarations install only the selected parent catalogue") {
      val ids = Chunk("first", "second")
      val cases = Chunk(
        ""                  -> ids.toSet,
        "skills: [first]\n" -> Set("first"),
        "skills: []\n"      -> Set.empty[String]
      )
      ZIO
        .foreach(cases) { case (header, expected) =>
          ZIO.scoped:
            for
              supervisor <- TaskSupervisor.open[AgentResult]
              parent     <- SubagentSpawner.root(supervisor)
              catalog <- ZIO.fromEither(
                SkillCatalog.make(ids.map(id => SkillDefinition(id, s"Guide $id", s"Content $id")))
              )
              declaration <- SubagentDeclarationParser.parse(
                "selected",
                "---\ndescription: Selected guides\n" + header + "---\nInstructions"
              )
              construction = SubagentConstruction.root(
                parent,
                SubagentToolBoundary(),
                ZIO.succeed(PermissionEngine()),
                ZIO.succeed(PlanMode.Build),
                catalog
              )
              factory <- ZIO.fromEither(
                declaration.factory(
                  construction,
                  parentConfig,
                  parentModel,
                  Toolkit.empty,
                  PlanSession.make.flatMap(permissions),
                  (_, _) => ZIO.succeed(Toolkit.empty),
                  _ =>
                    LlmClient.mock(
                      Chunk(
                        TurnOutput.ToolCalls(
                          Msg.assistant(
                            ids.map(id =>
                              ContentBlock.ToolUse(
                                id,
                                SkillCatalog.LoadToolName,
                                Json.Obj("skillId" -> Json.Str(id), "path" -> Json.Str("SKILL.md"))
                              )
                            )
                          )
                        ),
                        TurnOutput.FinalText(Msg.assistant("done"))
                      )
                    )
                )
              )
              runtime <- factory.build(parent)
              result  <- runtime.run(Msg.user("load guides"))
              observations = result.history.flatMap(_.toolResults)
            yield assertTrue(
              runtime.skills.definitions.map(_.id).toSet == expected,
              observations.map(_.state) == ids.map(id =>
                if expected.contains(id) then ToolResultState.Success else ToolResultState.Error
              ),
              observations.filter(_.state == ToolResultState.Success).map(_.outputText) ==
                ids.filter(expected.contains).map(id => s"Content $id"),
              catalog.definitions.map(_.id) == ids
            )
        }
        .map(_.foldLeft(assertTrue(true))(_ && _))
    },
    test(
      "declared and general-purpose builds compose actual prompts models execution settings and final tool selection"
    ) {
      ZIO
        .foreach(
          Chunk(
            (SubagentDeclaration.generalPurpose, Set("read", "write"), parentModel, 2),
            (
              declared.copy(
                tools = SubagentToolSelection.Only(Set("read", "hidden")),
                model = Some("child-model"),
                temperature = Some(0.2),
                maxIterations = Some(1)
              ),
              Set("read"),
              parentModel.copy(model = "child-model", temperature = 0.2),
              1
            ),
            (
              declared.copy(
                systemPrompt = "",
                tools = SubagentToolSelection.Only(Set.empty),
                maxIterations = Some(1)
              ),
              Set.empty[String],
              parentModel,
              1
            )
          )
        ) { case (declaration, expectedTools, expectedModel, iterations) =>
          ZIO.scoped:
            for
              supervisor <- TaskSupervisor.open[AgentResult]
              parent     <- SubagentSpawner.root(supervisor)
              executed   <- Ref.make(Chunk.empty[String])
              observed   <- Ref.make(Chunk.empty[(Chunk[Msg], Set[String], LlmConfig)])
              construction = SubagentConstruction.root(
                parent,
                SubagentToolBoundary(denied = Set("hidden")),
                ZIO.succeed(PermissionEngine()),
                ZIO.succeed(PlanMode.Build)
              )
              inherited = tools(Chunk("read", "hidden"), executed)
              factory <- ZIO.fromEither(
                declaration.factory(
                  construction,
                  parentConfig,
                  parentModel,
                  inherited,
                  PlanSession.make.flatMap(permissions),
                  (_, _) => ZIO.succeed(tools(Chunk("write"), executed)),
                  _ =>
                    ZIO.succeed(
                      client(
                        (history, definitions, config) =>
                          observed.update(_ :+ (history, definitions.map(_.name).toSet, config)),
                        Chunk("read", "write", "hidden")
                      )
                    )
                )
              )
              runtime      <- factory.build(parent)
              result       <- runtime.run(Msg.user("delegate"))
              actual       <- executed.get
              observations <- observed.get
            yield assertTrue(
              observations.nonEmpty,
              observations.forall { case (_, names, model) =>
                names == expectedTools && model == expectedModel
              },
              observations.headOption.exists(
                _._1.exists(message =>
                  message.role == Role.System &&
                    message.text.contains(declaration.systemPrompt) && message.text
                      .contains("delegating agent") &&
                    !message.text.contains("Parent-only")
                )
              ),
              actual.toSet == expectedTools,
              result.iterationCount == iterations,
              runtime.config.name == declaration.name,
              runtime.config.description == declaration.description,
              runtime.config.timeout == parentConfig.timeout,
              runtime.config.emptyThinkingRetryLimit == parentConfig.emptyThinkingRetryLimit,
              runtime.config.toolExecution == parentConfig.toolExecution,
              inherited.tools.keySet == Set("read", "hidden")
            )
        }
        .map(results => results.foldLeft(assertTrue(true))(_ && _))
    },
    test(
      "a declared session keeps scoped installation live policy independent Plan and history across turns"
    ) {
      for
        supervisor <- TaskSupervisor.open[AgentResult]
        parent     <- SubagentSpawner.root(supervisor)
        policy     <- Ref.make(PermissionEngine())
        mode       <- Ref.make(PlanMode.Build)
        plan       <- PlanSession.make
        installed  <- Ref.make(0)
        released   <- Ref.make(0)
        executed   <- Ref.make(Chunk.empty[String])
        construction = SubagentConstruction.root(
          parent,
          SubagentToolBoundary(),
          policy.get,
          mode.get
        )
        factory <- ZIO.fromEither(
          declared.factory(
            construction,
            parentConfig,
            parentModel,
            Toolkit.empty,
            ZIO.acquireRelease(installed.update(_ + 1))(_ => released.update(_ + 1)) *> permissions(
              plan
            ),
            (_, _) => ZIO.succeed(tools(Chunk("write"), executed)),
            _ => ZIO.succeed(client((_, _, _) => ZIO.unit, Chunk("write")))
          )
        )
        before   <- installed.get
        resource <- openSession(parent, factory)
        first    <- turn(resource.value, "first")
        _ <- policy.set(PermissionEngine(Chunk(PermissionRule("write", PermissionBehavior.Deny))))
        denied       <- turn(resource.value, "denied")
        _            <- policy.set(PermissionEngine()) *> mode.set(PlanMode.Plan)
        ancestorPlan <- turn(resource.value, "ancestor plan")
        _            <- plan.control.enter *> mode.set(PlanMode.Build)
        ownPlan      <- turn(resource.value, "own plan")
        draft        <- ZIO.fromEither(PlanDraft.fromString("Reviewed child plan"))
        _            <- plan.control.replaceDraft(draft)
        review       <- plan.control.requestReview
        _            <- plan.reviewer.resolve(review, PlanReviewDecision.Approve)
        last         <- turn(resource.value, "last")
        turns = Chunk(first, denied, ancestorPlan, ownPlan, last)
        executions <- executed.get
        during     <- installed.get.zip(released.get)
        _          <- resource.close
        after      <- released.get
      yield assertTrue(
        before == 0,
        during == (1, 0),
        after == 1,
        turns.map(_._1).toSet.size == 5,
        turns.map(_._2.history.flatMap(_.toolResults).lastOption.map(_.state)) == Chunk(
          Some(ToolResultState.Success),
          Some(ToolResultState.Denied),
          Some(ToolResultState.Denied),
          Some(ToolResultState.Denied),
          Some(ToolResultState.Success)
        ),
        executions == Chunk("write", "write"),
        last._2.history.filter(_.role == Role.User).map(_.text) ==
          Chunk("first", "denied", "ancestor plan", "own plan", "last")
      )
    }
  )
