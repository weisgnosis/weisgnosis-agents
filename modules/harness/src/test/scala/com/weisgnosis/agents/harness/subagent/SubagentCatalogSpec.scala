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

import com.weisgnosis.agents.core.agent.{AgentConfig, AgentResult, ReActAgent, ToolExecution}
import com.weisgnosis.agents.core.client.{LlmClient, LlmConfig, TurnOutput}
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.model.{ContentBlock, Msg, Role, ToolResultState}
import com.weisgnosis.agents.core.tool.{Tool, ToolDef, ToolExecutionResult, Toolkit}
import com.weisgnosis.agents.harness.permission.*
import com.weisgnosis.agents.harness.plan.*
import com.weisgnosis.agents.harness.storage.StorageAddress
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

object SubagentCatalogSpec extends ZIOSpecDefault:
  private enum Selection:
    case Catalog, SessionCatalog, Named

  private def accepted[A](
      effect: ZIO[Scope, AgentError, TaskResourceAdmission[A]]
  ): ZIO[Scope, AgentError, A] = effect.flatMap:
    case TaskResourceAdmission.Accepted(resource) => ZIO.succeed(resource.value)
    case TaskResourceAdmission.Closed =>
      ZIO.fail(AgentError.InvalidConfiguration("Fixture owner closed"))

  private def entry(
      parent: SubagentSpawner[AgentResult],
      name: String,
      client: ZIO[Scope, AgentError, LlmClient],
      additions: Toolkit = Toolkit.empty
  ): IO[AgentError, SubagentEntry] =
    val construction = SubagentConstruction.root(
      parent,
      SubagentToolBoundary(denied = Set("hidden")),
      ZIO.succeed(PermissionEngine()),
      ZIO.succeed(PlanMode.Build)
    )
    val initialize = for
      requests <- PermissionRequests.make
      plan     <- PlanSession.make
      authorizer <- InteractivePermissionAuthorizer.make(
        requests,
        request => requests.resolve(request.requestId, ConfirmResult(false)).unit,
        PermissionEngine(Chunk(PermissionRule("*", PermissionBehavior.Allow)))
      )
    yield SubagentPermissions(authorizer, plan.control, Map.empty)
    ZIO
      .fromEither(
        SubagentDeclaration(name, s"Tasks for $name", s"You are $name.").factory(
          construction,
          AgentConfig(),
          LlmConfig(name + "-model"),
          Toolkit.empty,
          initialize,
          (_, _) => ZIO.succeed(additions),
          _ => client
        )
      )
      .map(SubagentEntry(name, s"Tasks for $name", _))

  private def spawn(name: String, prompt: String): Json =
    Json.Obj("agent_id" -> Json.Str(name), "prompt" -> Json.Str(prompt))

  private def task(name: String, id: String): Json =
    Json.Obj("agent_id" -> Json.Str(name), "task_id" -> Json.Str(id))

  private def observation(output: String): IO[AgentError, SubagentCatalogToolResult] =
    ZIO
      .fromEither(output.dropWhile(_ != '{').fromJson[SubagentCatalogToolResult])
      .mapError(AgentError.InvalidConfiguration(_))

  private def observedTask(output: String): IO[AgentError, (String, String, SubagentToolState)] =
    observation(output).flatMap:
      case SubagentCatalogToolResult.Agent(name, SubagentToolResult.Task(id, state)) =>
        ZIO.succeed((name, id, state))
      case other => ZIO.fail(AgentError.InvalidConfiguration(s"Expected catalog task: $other"))

  private def atLimit(
      parent: SubagentSpawner[AgentResult],
      run: SubagentSpawner[AgentResult] => ZIO[Scope, AgentError, Unit]
  ): ZIO[Scope, AgentError, AgentResult] =
    if parent.depth == SubagentSpawner.MaximumDepth then
      run(parent).as(AgentResult(Msg.assistant("depth checked"), Chunk.empty, 0))
    else
      parent
        .spawn(child => atLimit(child, run))
        .flatMap:
          case TaskAdmission.Accepted(task) => task.await.flatMap(exit => exit)
          case TaskAdmission.Closed =>
            ZIO.fail(AgentError.InvalidConfiguration("Closed test owner"))

  def spec: Spec[TestEnvironment & Scope, Any] = suite("SubagentCatalogSpec")(
    test(
      "declaration eligibility precedes allocation while hidden children remain explicitly selectable"
    ):
      for checks <- ZIO.foreach(
          Chunk(Selection.Catalog, Selection.SessionCatalog, Selection.Named)
        )(selection =>
          ZIO.scoped:
            for
              supervisor <- TaskSupervisor.open[AgentResult]
              parent     <- SubagentSpawner.root(supervisor)
              builds     <- Ref.make(Chunk.empty[String])
              claims     <- Ref.make(0)
              client = (name: String) =>
                builds
                  .update(_ :+ name)
                  .as(
                    LlmClient.mockFunction((_, _) => TurnOutput.FinalText(Msg.assistant("done")))
                  )
              declared <- ZIO.foreach(
                Chunk(
                  ("primary", "mode: primary"),
                  ("secret", "hidden: true"),
                  ("worker", "mode: subagent")
                )
              ): (name, fields) =>
                for
                  declaration <- SubagentDeclarationParser.parse(
                    name,
                    s"---\ndescription: Tasks\n$fields\n---\nBody"
                  )
                  configured <- entry(parent, name, client(name))
                  registered <- ZIO.fromEither(
                    SubagentEntry.fromDeclaration(declaration, configured.factory)
                  )
                yield registered
              plain <- entry(parent, "plain", client("plain"))
              entries = declared :+ plain
              installed <- selection match
                case Selection.Catalog =>
                  SubagentCatalog
                    .open(parent, entries)
                    .map(catalog =>
                      (SubagentTools.forCatalog(catalog), catalog.lookup("primary").isEmpty)
                    )
                case Selection.SessionCatalog =>
                  accepted(SubagentSessionCatalog.open(parent, entries)).flatMap(catalog =>
                    catalog
                      .lookup("primary")
                      .map(value => (SubagentTools.forSessionCatalog(catalog), value.isEmpty))
                  )
                case Selection.Named =>
                  for
                    backend <- SubagentTerminalStoreSpec.fixture
                    store <- ZIO.fromEither(
                      SubagentNamedSessionStore.make(backend.store, Chunk("mode-test"))
                    )
                    authority = new SubagentNamedSessions.Authority:
                      def acquire(
                          key: SubagentNamedSessionKey,
                          address: StorageAddress
                      ): ZIO[Scope, AgentError, Unit] = claims.update(_ + 1)
                    sessions <- accepted(
                      SubagentNamedSessions.open(parent, "parent", entries, store, authority)
                    )
                    refused <- sessions.getOrOpen("primary", "same").either
                  yield (SubagentTools.forNamedSessions(sessions), refused.isLeft)
              (tools, unallocated) = installed
              before <- builds.get
              input = (name: String) =>
                selection match
                  case Selection.Named =>
                    Json.Obj(
                      "agent_id" -> Json.Str(name),
                      "label"    -> Json.Str("same"),
                      "prompt"   -> Json.Str("work")
                    )
                  case _ => spawn(name, "work")
              invoke = selection match
                case Selection.SessionCatalog => "agent_send"
                case _                        => "agent_spawn"
              rejected       <- tools.executeSafe(invoke, input("primary"))
              afterReject    <- builds.get
              rejectedClaims <- claims.get
              outputs <- ZIO.foreach(Chunk("secret", "worker", "plain"))(name =>
                tools.executeSafe(invoke, input(name))
              )
              built <- builds.get
              descriptions = tools.definitions.map(_.description).mkString("\n")
            yield assertTrue(
              rejected.state == ToolResultState.Error,
              unallocated,
              rejected.output.contains("PRIMARY-only"),
              before == afterReject,
              rejectedClaims == 0,
              outputs.forall(_.state == ToolResultState.Success),
              built.sorted == Chunk("plain", "secret", "worker"),
              descriptions.contains("worker: Tasks"),
              descriptions.contains("plain: Tasks for plain"),
              !descriptions.contains("secret: Tasks"),
              !descriptions.contains("primary: Tasks")
            )
        )
      yield checks.foldLeft(assertTrue(true))(_ && _)
    ,
    test(
      "a parent selects declared children with fresh histories and the final inherited boundary"
    ) {
      for
        supervisor <- TaskSupervisor.open[AgentResult]
        parent     <- SubagentSpawner.root(supervisor)
        seen       <- Ref.make(Chunk.empty[(String, Chunk[String], Set[String], String)])
        executed   <- Ref.make(Chunk.empty[String])
        entries <- ZIO.foreach(Chunk("left", "right"))(name =>
          entry(
            parent,
            name,
            ZIO.succeed(LlmClient.mockEffectful((history, definitions, _) =>
              val prompts = history.filter(_.role == Role.User).map(_.text)
              if history.lastOption.exists(_.role == Role.User) then
                seen
                  .update(
                    _ :+ (
                      name,
                      prompts,
                      definitions.map(_.name).toSet,
                      history.filter(_.role == Role.System).map(_.text).mkString
                    )
                  )
                  .as(
                    TurnOutput.ToolCalls(
                      Msg.assistant(
                        Chunk(
                          ContentBlock.ToolUse("read", "read", Json.Obj()),
                          ContentBlock.ToolUse("hidden", "hidden", Json.Obj())
                        )
                      )
                    )
                  )
              else
                ZIO.succeed(TurnOutput.FinalText(Msg.assistant(name + ":" + prompts.mkString("|"))))
            )),
            Chunk("read", "hidden")
              .map(tool =>
                Tool.make(
                  ToolDef(tool, "Record execution", Json.Obj())
                )(_ => executed.update(_ :+ s"$name:$tool").as("done"))
              )
              .foldLeft(Toolkit.empty)(_.updated(_))
          )
        )
        catalog <- SubagentCatalog.open(parent, entries)
        tools = SubagentTools.forCatalog(catalog)
        model <- LlmClient.mock(
          Chunk(
            TurnOutput.ToolCalls(
              Msg.assistant(
                Chunk(
                  ContentBlock.ToolUse("one", "agent_spawn", spawn("left", "one")),
                  ContentBlock.ToolUse("other", "agent_spawn", spawn("right", "other")),
                  ContentBlock.ToolUse("two", "agent_spawn", spawn("left", "two"))
                )
              )
            ),
            TurnOutput.FinalText(Msg.assistant("parent finished"))
          )
        )
        runtime <- ReActAgent.make(
          AgentConfig(toolExecution = ToolExecution.Sequential),
          tools,
          model,
          LlmConfig("parent")
        )
        result <- runtime.run(Msg.user("delegate"))
        outputs = result.history.flatMap(_.toolResults)
        tasks        <- ZIO.foreach(outputs)(output => observedTask(output.outputText))
        observations <- seen.get
        actions      <- executed.get
      yield assertTrue(
        result.reply.text == "parent finished",
        outputs.forall(_.state == ToolResultState.Success),
        tasks.map(value => (value._1, value._3)) == Chunk(
          "left"  -> SubagentToolState.Completed(SubagentToolText("left:one", 0)),
          "right" -> SubagentToolState.Completed(SubagentToolText("right:other", 0)),
          "left"  -> SubagentToolState.Completed(SubagentToolText("left:two", 0))
        ),
        tasks.map(_._2).toSet.size == 3,
        observations.map(_._2) == Chunk(Chunk("one"), Chunk("other"), Chunk("two")),
        observations.forall { case (name, _, definitions, prompt) =>
          definitions == Set("read") && prompt.contains(s"You are $name.")
        },
        actions == Chunk("left:read", "right:read", "left:read"),
        tools.tools.keySet == Set("agent_spawn", "agent_task", "agent_cancel"),
        tools.definitions.forall(definition =>
          definition.description.contains("left: Tasks for left") &&
            definition.description.contains(
              "right: Tasks for right"
            ) && definition.isConcurrencySafe &&
            definition.isReadOnly == (definition.name == "agent_task")
        )
      )
    },
    test(
      "validation and strict routing prevent acquisition while retained tasks remain entry-local"
    ) {
      val unknownTask = "00000000-0000-0000-0000-000000000001"
      for
        supervisor <- TaskSupervisor.open[AgentResult]
        parent     <- SubagentSpawner.root(supervisor)
        acquired   <- Ref.make(0)
        entries <- ZIO.foreach(Chunk("left", "right"))(name =>
          entry(
            parent,
            name,
            acquired
              .update(_ + 1)
              .as(
                LlmClient.mockEffectful((history, _, _) =>
                  if history.lastOption.exists(_.text == "fail") then
                    ZIO.fail(AgentError.InvalidMessage("child failed"))
                  else ZIO.succeed(TurnOutput.FinalText(Msg.assistant(name)))
                )
              )
          )
        )
        invalid <- ZIO.foreach(
          Chunk(
            entries ++ entries,
            entries.map(_.copy(name = " ")),
            entries.map(_.copy(description = ""))
          )
        )(values => SubagentCatalog.open(parent, values).exit)
        invalidCapacity <- SubagentCatalog.open(parent, entries, 0).exit
        catalog         <- SubagentCatalog.open(parent, entries, 1)
        tools = SubagentTools.forCatalog(catalog)
        malformed = Chunk(
          "agent_spawn" -> Json.Obj("agent_id" -> Json.Str("left"), "prompt" -> Json.Num(1)),
          "agent_spawn" -> Json
            .Obj("agent_id" -> Json.Str("left"), "agent_id" -> Json.Str("right")),
          "agent_spawn" -> Json.Obj(
            "agent_id" -> Json.Str("left"),
            "prompt"   -> Json.Str("work"),
            "parent"   -> Json.Str("injected")
          ),
          "agent_task" -> Json
            .Obj("session_id" -> Json.Str("left"), "task_id" -> Json.Str(unknownTask)),
          "agent_cancel" -> task("left", "1-1-1-1-1")
        )
        rejected <- ZIO.foreach(malformed)((name, input) => tools.executeSafe(name, input))
        missing  <- tools.executeSafe("agent_spawn", spawn("outside", "work"))
        missingObservation <- observation(missing.output)
        closedOwner        <- Scope.make
        _                  <- closedOwner.close(Exit.unit)
        closedCatalog      <- closedOwner.extend(SubagentCatalog.open(parent, entries))
        closed <- SubagentTools
          .forCatalog(closedCatalog)
          .executeSafe("agent_spawn", spawn("left", "work"))
        depthFailure <- Ref.make(Option.empty[ToolExecutionResult])
        _ <- atLimit(
          parent,
          deepest =>
            SubagentCatalog
              .open(deepest, entries)
              .flatMap(catalog =>
                SubagentTools
                  .forCatalog(catalog)
                  .executeSafe("agent_spawn", spawn("left", "too deep"))
                  .flatMap(result => depthFailure.set(Some(result)))
              )
        )
        depthRejected <- depthFailure.get
        depthPayload = depthRejected.flatMap(result =>
          result.output.dropWhile(_ != '{').fromJson[Json].toOption
        )
        untouched       <- acquired.get
        first           <- tools.executeSafe("agent_spawn", spawn("left", "work"))
        left            <- observedTask(first.output)
        full            <- tools.executeSafe("agent_spawn", spawn("left", "again"))
        fullObservation <- observation(full.output)
        failed          <- tools.executeSafe("agent_spawn", spawn("right", "fail"))
        right           <- observedTask(failed.output)
        inspected       <- tools.executeSafe("agent_task", task("right", right._2))
        retained        <- observedTask(inspected.output)
        crossed <- ZIO.foreach(Chunk("agent_task", "agent_cancel"))(name =>
          tools.executeSafe(name, task("right", left._2))
        )
        foreign <- ZIO.foreach(crossed)(value => observedTask(value.output))
        count   <- acquired.get
      yield assertTrue(
        invalid.forall(_.isFailure),
        invalidCapacity.isFailure,
        rejected.forall(_.state == ToolResultState.Error),
        untouched == 0,
        missing.state == ToolResultState.Error,
        missingObservation == SubagentCatalogToolResult.Missing("outside"),
        closed.state == ToolResultState.Error && closed.output.contains("Closed"),
        depthRejected.exists(_.state == ToolResultState.Error),
        depthPayload.exists {
          case Json.Obj(Chunk((_, Json.Obj(fields)))) =>
            fields.contains("agentId" -> Json.Str("left"))
          case _ => false
        },
        full.state == ToolResultState.Error,
        fullObservation == SubagentCatalogToolResult
          .Agent("left", SubagentToolResult.Rejected(SubagentToolRejection.Capacity)),
        failed.state == ToolResultState.Error,
        right._1 == "right" && right._3 == SubagentToolState.Failed(
          SubagentToolText("child failed", 0)
        ),
        inspected.state == ToolResultState.Success,
        retained == right,
        crossed.forall(_.state == ToolResultState.Error),
        foreign.forall(_ == ("right", left._2, SubagentToolState.Missing)),
        count == 2
      )
    },
    test(
      "promotion remains selectable and catalog closure joins both entries' cleanup in parallel"
    ) {
      for
        supervisor    <- TaskSupervisor.open[AgentResult]
        parent        <- SubagentSpawner.root(supervisor)
        owner         <- ZIO.acquireRelease(Scope.make)(_.close(Exit.unit))
        leftEntered   <- Promise.make[Nothing, Unit]
        rightEntered  <- Promise.make[Nothing, Unit]
        leftCleaning  <- Promise.make[Nothing, Unit]
        rightCleaning <- Promise.make[Nothing, Unit]
        release       <- Promise.make[Nothing, Unit]
        entries <- ZIO.foreach(
          Chunk(("left", leftEntered, leftCleaning), ("right", rightEntered, rightCleaning))
        ) { case (name, entered, cleaning) =>
          entry(
            parent,
            name,
            ZIO.addFinalizer(cleaning.succeed(()) *> release.await) *>
              ZIO.succeed(LlmClient.mockEffectful((_, _, _) => entered.succeed(()) *> ZIO.never))
          )
        }
        catalog <- owner.extend(SubagentCatalog.open(parent, entries))
        tools = SubagentTools.forCatalog(catalog)
        result <- (for
          leftSpawn     <- tools.executeSafe("agent_spawn", spawn("left", "work")).forkScoped
          _             <- leftEntered.await
          _             <- TestClock.adjust(30.seconds)
          promoted      <- leftSpawn.join.flatMap(value => observedTask(value.output))
          inspected     <- tools.executeSafe("agent_task", task("left", promoted._2))
          running       <- observedTask(inspected.output)
          rightSpawn    <- tools.executeSafe("agent_spawn", spawn("right", "work")).forkScoped
          _             <- rightEntered.await
          closing       <- owner.close(Exit.unit).forkScoped
          _             <- leftCleaning.await *> rightCleaning.await
          cancelling    <- tools.executeSafe("agent_cancel", task("left", promoted._2)).forkScoped
          pendingClose  <- closing.poll
          pendingCancel <- cancelling.poll
          _             <- release.succeed(())
          _             <- closing.join
          cancelled     <- cancelling.join.flatMap(value => observedTask(value.output))
          _             <- rightSpawn.join
          closed        <- tools.executeSafe("agent_spawn", spawn("right", "later"))
        yield assertTrue(
          promoted._3 == SubagentToolState.Promoted,
          running == ("left", promoted._2, SubagentToolState.Running),
          pendingClose.isEmpty,
          pendingCancel.isEmpty,
          cancelled == ("left", promoted._2, SubagentToolState.Interrupted),
          closed.state == ToolResultState.Error && closed.output.contains("Closed")
        ))
          .ensuring(release.succeed(()))
      yield result
    }
  ) @@ TestAspect.timeout(20.seconds)
