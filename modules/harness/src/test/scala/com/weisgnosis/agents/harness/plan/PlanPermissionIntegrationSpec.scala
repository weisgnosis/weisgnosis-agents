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

import com.weisgnosis.agents.core.agent.{AgentConfig, AgentResult, ReActAgent, ToolExecution}
import com.weisgnosis.agents.core.client.{LlmClient, LlmConfig, TurnOutput}
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.event.AgentEvent
import com.weisgnosis.agents.core.model.*
import com.weisgnosis.agents.core.skill.{SkillCatalog, SkillDefinition}
import com.weisgnosis.agents.core.tool.*
import com.weisgnosis.agents.harness.permission.*
import com.weisgnosis.agents.harness.subagent.*
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

object PlanPermissionIntegrationSpec extends ZIOSpecDefault:
  private enum SkillAdmission:
    case RuntimeAccess, HostMutation, PolicyDenied

  private val allow: PermissionEngine = PermissionEngine(
    Chunk(PermissionRule("*", PermissionBehavior.Allow))
  )
  private val input: Json = Json.Obj("command" -> Json.Str("pwd"))

  private def observeAccess(
      observed: Ref[Chunk[(Json, Option[AdmittedToolAccess])]]
  ): PermissionGate =
    new PermissionGate:
      def evaluate(name: String, input: Json): UIO[Option[PermissionBehavior]] =
        evaluate(name, input, None)
      override def evaluate(
          name: String,
          input: Json,
          admitted: Option[AdmittedToolAccess]
      ): UIO[Option[PermissionBehavior]] =
        observed.update(_ :+ (input -> admitted)).as(None)

  /** Deliberately disagree with definition flags to check the actual admitted Tool accessor. */
  private def overriddenTool(
      name: String,
      access: AdmittedToolAccess,
      executed: Ref[Chunk[String]],
      before: UIO[Unit] = ZIO.unit
  ): Tool[Any, Json, String] =
    new Tool[Any, Json, String]:
      def definition: ToolDef =
        ToolDef(name, "Host tool", Json.Obj(), isReadOnly = access == AdmittedToolAccess.Mutating)
      override def isReadOnly: Boolean                 = access == AdmittedToolAccess.ReadOnly
      def decodeInput(raw: Json): Either[String, Json] = Right(raw)
      def execute(input: Json): UIO[String] =
        before *> executed.update(_ :+ name).as("ok")
      def encodeOutput(out: String): String = out

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
      "Plan admits explicitly classified subagent controls while children retain mutation denial"
    ):
      for outcomes <- ZIO.foreach(Chunk("agent_spawn", "agent_send")): invocation =>
          ZIO.scoped:
            for
              supervisor  <- TaskSupervisor.open[AgentResult]
              root        <- SubagentSpawner.root(supervisor)
              executed    <- Ref.make(Chunk.empty[String])
              childStates <- Ref.make(Chunk.empty[ToolResultState])
              construction = SubagentConstruction.root(
                root,
                SubagentToolBoundary(),
                ZIO.succeed(allow),
                ZIO.succeed(PlanMode.Plan)
              )
              configured <- ZIO.fromEither(
                construction.factory(
                  AgentConfig(toolExecution = ToolExecution.Sequential),
                  LlmConfig("plan-child"),
                  toolkit(Chunk("read", "write"), (name, _) => executed.update(_ :+ name).as("ok")),
                  SubagentToolBoundary(),
                  for
                    requests <- PermissionRequests.make
                    plan     <- PlanSession.make
                    authorizer <- InteractivePermissionAuthorizer
                      .make(requests, _ => ZIO.unit, allow)
                  yield SubagentPermissions(
                    authorizer,
                    plan.control,
                    Map("read" -> PlanToolAccess.ReadOnly, "write" -> PlanToolAccess.Mutation)
                  ),
                  (_, _) => ZIO.succeed(Toolkit.empty),
                  _ =>
                    ZIO.succeed(LlmClient.mockEffectful((history, _, _) =>
                      val results = history.flatMap(_.toolResults)
                      if results.isEmpty then
                        ZIO.succeed(
                          TurnOutput.ToolCalls(
                            Msg.assistant(
                              Chunk(
                                ContentBlock.ToolUse("read", "read", Json.Obj()),
                                ContentBlock.ToolUse("write", "write", Json.Obj())
                              )
                            )
                          )
                        )
                      else
                        childStates
                          .set(results.map(_.state))
                          .as(TurnOutput.FinalText(Msg.assistant("done")))
                    ))
                )
              )
              controls <- invocation match
                case "agent_spawn" =>
                  SubagentTaskRegistry.open(root, configured).map(SubagentTools.make)
                case _ =>
                  SubagentSession
                    .open(root, configured)
                    .flatMap:
                      case TaskResourceAdmission.Accepted(resource) =>
                        ZIO.succeed(SubagentTools.forSession(resource.value))
                      case TaskResourceAdmission.Closed =>
                        ZIO.fail(AgentError.InvalidConfiguration("Unexpected closed session"))
              requests   <- PermissionRequests.make
              authorizer <- InteractivePermissionAuthorizer.make(requests, _ => ZIO.unit, allow)
              access = controls.tools.keys.map(_ -> PlanToolAccess.PlanControl).toMap
              guarded = authorizer.withAdditionalGate(
                PlanPermissionGate.make(ZIO.succeed(PlanMode.Plan), access)
              )
              model = LlmClient.mockEffectful((history, _, _) =>
                val results = history.flatMap(_.toolResults)
                val next = results.size match
                  case 0 =>
                    ZIO.succeed(
                      Some(
                        ContentBlock.ToolUse(
                          "invoke",
                          invocation,
                          Json.Obj("prompt" -> Json.Str("investigate"))
                        )
                      )
                    )
                  case 1 | 2 =>
                    ZIO
                      .fromEither(
                        results.lastOption
                          .toRight("Missing task result")
                          .flatMap(result =>
                            result.text.dropWhile(_ != '{').fromJson[SubagentToolResult]
                          )
                      )
                      .mapError(AgentError.InvalidConfiguration(_))
                      .flatMap:
                        case SubagentToolResult.Task(id, _) =>
                          val name = if results.size == 1 then "agent_task" else "agent_cancel"
                          ZIO.succeed(
                            Some(
                              ContentBlock.ToolUse(name, name, Json.Obj("task_id" -> Json.Str(id)))
                            )
                          )
                        case other =>
                          ZIO.fail(
                            AgentError.InvalidConfiguration(s"Unexpected task result: $other")
                          )
                  case _ => ZIO.none
                next.map:
                  case Some(call) => TurnOutput.ToolCalls(Msg.assistant(Chunk(call)))
                  case None       => TurnOutput.FinalText(Msg.assistant("finished"))
              )
              parent <- ReActAgent.make(
                client = model,
                toolkit = controls,
                toolAuthorizer = guarded
              )
              result <- parent.run(Msg.user("delegate investigation"))
              observations <- ZIO.foreach(result.history.flatMap(_.toolResults))(result =>
                ZIO
                  .fromEither(result.text.dropWhile(_ != '{').fromJson[SubagentToolResult])
                  .mapError(AgentError.InvalidConfiguration(_))
              )
              actual <- executed.get
              states <- childStates.get
            yield assertTrue(
              actual == Chunk("read"),
              states == Chunk(ToolResultState.Success, ToolResultState.Denied),
              result.history.flatMap(_.toolResults).map(_.state) == Chunk.fill(3)(
                ToolResultState.Success
              ),
              observations.size == 3,
              observations.forall {
                case SubagentToolResult.Task(_, SubagentToolState.Completed(_)) => true
                case _                                                          => false
              },
              observations.collect { case SubagentToolResult.Task(id, _) => id }.distinct.size == 1
            )
      yield outcomes.foldLeft(assertTrue(true))(_ && _)
    ,
    test(
      "Plan admits the installed skill reader while retaining host denials and actual tool access"
    ) {
      for outcomes <- ZIO.foreach(Chunk.fromIterable(SkillAdmission.values)): scenario =>
          for
            requests        <- PermissionRequests.make
            mode            <- Ref.make(PlanMode.Plan)
            executed        <- Ref.make(Chunk.empty[String])
            deniedPersisted <- Promise.make[Nothing, Unit]
            observed        <- Ref.make(Chunk.empty[Chunk[String]])
            groups <- ZIO.fromEither(
              ToolGroups.empty.register(
                ToolGroup(
                  "skill",
                  "Skill tools",
                  ToolGroupActivation.Inactive,
                  tools = Set("read", "write")
                )
              )
            )
            skills <- ZIO.fromEither(
              SkillCatalog.make(
                Chunk(
                  SkillDefinition("guide", "Guide", "Instructions", activateGroups = Set("skill"))
                )
              )
            )
            base <- ZIO.fromEither(
              Toolkit(
                overriddenTool(
                  "read",
                  AdmittedToolAccess.ReadOnly,
                  executed,
                  deniedPersisted.await
                ),
                overriddenTool("write", AdmittedToolAccess.Mutating, executed)
              )
            )
            hostAccess = scenario match
              case SkillAdmission.HostMutation =>
                Map(SkillCatalog.LoadToolName -> PlanToolAccess.Mutation)
              case _ => Map.empty[String, PlanToolAccess]
            policy = scenario match
              case SkillAdmission.PolicyDenied =>
                PermissionEngine(Chunk(PermissionRule("*", PermissionBehavior.Deny)))
              case _ => allow
            authorizer <- InteractivePermissionAuthorizer.make(requests, _ => ZIO.unit, policy)
            guarded = authorizer.withAdditionalGate(
              PlanPermissionGate.withAdmittedTools(mode.get, hostAccess).and(PermissionGate.abstain)
            )
            model = LlmClient.mockEffectful((_, definitions, _) =>
              observed.modify(current => current.size -> (current :+ definitions.map(_.name))).map {
                case 0 =>
                  TurnOutput.ToolCalls(
                    Msg.assistant(
                      Chunk(
                        ContentBlock.ToolUse(
                          "load",
                          SkillCatalog.LoadToolName,
                          Json.Obj("skillId" -> Json.Str("guide"), "path" -> Json.Str("SKILL.md"))
                        )
                      )
                    )
                  )
                case 1 if scenario == SkillAdmission.RuntimeAccess =>
                  TurnOutput.ToolCalls(
                    Msg.assistant(
                      Chunk(
                        ContentBlock.ToolUse("read", "read", Json.Obj()),
                        ContentBlock.ToolUse(
                          "write",
                          "write",
                          Json.Obj("isReadOnly" -> Json.Bool(true)),
                          metadata = Map("isReadOnly" -> Json.Bool(true))
                        )
                      )
                    )
                  )
                case _ => TurnOutput.FinalText(Msg.assistant("done"))
              }
            )
            runtime <- ReActAgent.make(
              client = model,
              toolkit = base,
              toolGroups = Some(groups),
              skills = skills,
              toolAuthorizer = guarded,
              toolResultProcessor = ToolResultProcessor(result =>
                (if result.id == "write" then deniedPersisted.succeed(()).unit else ZIO.unit)
                  .as(ToolResultPayload.fromResult(result))
              )
            )
            result      <- runtime.run(Msg.user("investigate the skill"))
            actual      <- executed.get
            definitions <- observed.get
            expected = scenario match
              case SkillAdmission.RuntimeAccess =>
                Chunk(
                  "load"  -> ToolResultState.Success,
                  "read"  -> ToolResultState.Success,
                  "write" -> ToolResultState.Denied
                )
              case _ => Chunk("load" -> ToolResultState.Denied)
          yield assertTrue(
            result.history
              .flatMap(_.toolResults)
              .map(value => value.id -> value.state)
              .sortBy(_._1) == expected,
            actual == (if scenario == SkillAdmission.RuntimeAccess then Chunk("read")
                       else Chunk.empty),
            definitions.headOption.contains(
              Chunk(SkillCatalog.LoadToolName, "reset_equipped_tools")
            ),
            definitions
              .drop(1)
              .forall(names =>
                names.contains("write") == (scenario == SkillAdmission.RuntimeAccess)
              )
          )
      yield outcomes.foldLeft(assertTrue(true))(_ && _)
    },
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
            admitted <- Ref.make(Chunk.empty[(Json, Option[AdmittedToolAccess])])
            authorizer <- InteractivePermissionAuthorizer.make(
              requests,
              request =>
                requests.resolve(request.requestId, ConfirmResult(true, Some(replacement))).unit,
              toolGate = observeAccess(admitted),
              interceptorGate = PlanPermissionGate.withAdmittedTools(
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
            _        <- runtime.run(Msg.user("inspect"))
            history  <- runtime.history
            actual   <- executed.get
            evidence <- admitted.get
          yield history.flatMap(_.toolResults).map(_.state) == Chunk(expected) &&
            evidence == Chunk(
              input       -> Some(AdmittedToolAccess.Mutating),
              replacement -> Some(AdmittedToolAccess.Mutating)
            ) &&
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
          interceptorGate = PlanPermissionGate.withAdmittedTools(mode.get)
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
