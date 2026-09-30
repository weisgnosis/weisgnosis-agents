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

import com.weisgnosis.agents.core.agent.{AgentConfig, AgentResult}
import com.weisgnosis.agents.core.client.{LlmClient, LlmConfig, TurnOutput}
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.model.{ContentBlock, Msg, ToolResultState}
import com.weisgnosis.agents.core.tool.{Tool, ToolAuthorization, ToolAuthorizer, ToolDef, Toolkit}
import zio.*
import zio.json.ast.Json
import zio.test.*

object SubagentFactorySpec extends ZIOSpecDefault:
  private val model: LlmConfig = LlmConfig("configured-child-model")
  private val config: AgentConfig =
    AgentConfig(name = "reader", systemPrompt = "Read the requested material")
  private val failure: AgentError = AgentError.InvalidConfiguration("fixture failure")

  private def toolkit(names: String*): Toolkit =
    Toolkit(
      names
        .map(name =>
          name -> Tool.make(ToolDef(name, "Factory fixture", Json.Obj()))(_ => ZIO.succeed(name))
        )
        .toMap
    )

  private def factory(
      inherited: Toolkit = Toolkit.empty,
      parent: SubagentToolBoundary = SubagentToolBoundary(),
      child: SubagentToolBoundary = SubagentToolBoundary(),
      register: SubagentSpawner[AgentResult] => ZIO[Scope, AgentError, Toolkit] = _ =>
        ZIO.succeed(Toolkit.empty),
      client: SubagentSpawner[AgentResult] => ZIO[Scope, AgentError, LlmClient] = _ =>
        LlmClient.mock(Chunk(TurnOutput.FinalText(Msg.assistant("done")))),
      authorizer: ToolAuthorizer = ToolAuthorizer.allow
  ): IO[AgentError, SubagentFactory] =
    ZIO.fromEither(
      SubagentFactory.make(config, model, inherited, parent, child, register, client, authorizer)
    )

  private def accepted(
      effect: ZIO[Scope, AgentError, TaskAdmission[AgentResult]]
  ): ZIO[Scope, AgentError, SupervisedTask[AgentResult]] =
    effect.flatMap:
      case TaskAdmission.Accepted(task) => ZIO.succeed(task)
      case TaskAdmission.Closed =>
        ZIO.fail(AgentError.InvalidConfiguration("Unexpected closed supervisor"))

  def spec: Spec[TestEnvironment & Scope, Any] = suite("SubagentFactorySpec")(
    test(
      "the model sees final filtering of inherited and automatic tools, with monotone factory narrowing"
    ) {
      val inherited  = toolkit("inherited", "execute")
      val registered = toolkit("read_file", "write_file")
      val cases = Chunk(
        (
          SubagentToolBoundary(),
          SubagentToolBoundary(),
          Set("inherited", "execute", "read_file", "write_file")
        ),
        (
          SubagentToolBoundary(),
          SubagentToolBoundary(SubagentToolSelection.Only(Set.empty)),
          Set.empty[String]
        ),
        (
          SubagentToolBoundary(),
          SubagentToolBoundary(SubagentToolSelection.Only(Set("read_file"))),
          Set("read_file")
        ),
        (
          SubagentToolBoundary(denied = Set("execute")),
          SubagentToolBoundary(SubagentToolSelection.Only(Set("execute"))),
          Set.empty[String]
        ),
        (
          SubagentToolBoundary(denied = Set("write_file")),
          SubagentToolBoundary(SubagentToolSelection.Only(Set("write_file"))),
          Set.empty[String]
        ),
        (
          SubagentToolBoundary(SubagentToolSelection.Only(Set("inherited", "read_file"))),
          SubagentToolBoundary(SubagentToolSelection.Only(Set("read_file", "write_file"))),
          Set("read_file")
        )
      )
      for
        supervisor <- TaskSupervisor.open[AgentResult]
        root       <- SubagentSpawner.root(supervisor)
        results <- ZIO.foreach(cases) { case (parent, child, expected) =>
          for
            seen       <- Ref.make(Chunk.empty[Set[String]])
            executions <- Ref.make(0)
            write = Tool.make(ToolDef("write_file", "Observe forbidden dispatch", Json.Obj()))(_ =>
              executions.update(_ + 1).as("written")
            )
            automatic = registered.copy(tools = registered.tools.updated("write_file", write))
            configured <- factory(
              inherited,
              parent,
              child,
              _ => ZIO.succeed(automatic),
              _ =>
                ZIO.succeed(
                  LlmClient.mockEffectful((history, tools, _) =>
                    seen
                      .update(_ :+ tools.map(_.name).toSet)
                      .as(
                        if history.exists(_.toolUses.nonEmpty) then
                          TurnOutput.FinalText(Msg.assistant("done"))
                        else
                          TurnOutput.ToolCalls(
                            Msg.assistant(
                              Chunk(ContentBlock.ToolUse("attempt-write", "write_file", Json.Obj()))
                            )
                          )
                      )
                  )
                )
            )
            task   <- accepted(configured.spawn(root, Msg.user("inspect tools")))
            result <- task.await
            derived <- configured
              .narrowed(
                SubagentToolBoundary(SubagentToolSelection.Only(Set("read_file", "execute")))
              )
              .narrowed(SubagentToolBoundary())
              .build(root)
            observed       <- seen.get
            executionCount <- executions.get
            expectedState =
              if expected.contains("write_file") then ToolResultState.Success
              else ToolResultState.Error
          yield result.exists(
            _.history.flatMap(_.toolResults).map(_.state) == Chunk(expectedState)
          ) &&
            executionCount == (if expected.contains("write_file") then 1 else 0) &&
            observed == Chunk(expected, expected) &&
            derived.toolkit.tools.keySet == expected.intersect(Set("read_file", "execute"))
        }
      yield assertTrue(
        results.forall(identity),
        inherited.tools.keySet == Set("inherited", "execute"),
        registered.tools.keySet == Set("read_file", "write_file")
      )
    },
    test(
      "each build has fresh state and preserves configuration and an effective injected authorizer"
    ) {
      for
        supervisor     <- TaskSupervisor.open[AgentResult]
        root           <- SubagentSpawner.root(supervisor)
        executions     <- Ref.make(0)
        authorizations <- Ref.make(0)
        authorizer = ToolAuthorizer(_ =>
          authorizations.update(_ + 1).as(ToolAuthorization.Denied("host decision"))
        )
        tool = Tool.make(ToolDef("write", "Must be authorized", Json.Obj()))(_ =>
          executions.update(_ + 1).as("written")
        )
        configured <- factory(
          inherited = Toolkit(Map("write" -> tool)),
          client = _ =>
            LlmClient.mock(
              Chunk(
                TurnOutput.ToolCalls(
                  Msg.assistant(Chunk(ContentBlock.ToolUse("call", "write", Json.Obj())))
                ),
                TurnOutput.FinalText(Msg.assistant("done"))
              )
            ),
          authorizer = authorizer
        )
        first              <- configured.build(root)
        second             <- configured.build(root)
        result             <- first.run(Msg.user("write"))
        secondHistory      <- second.history
        executionCount     <- executions.get
        authorizationCount <- authorizations.get
      yield assertTrue(
        first.agentId != second.agentId,
        first.config == config,
        first.llmConfig == model,
        first.toolAuthorizer eq authorizer,
        secondHistory.isEmpty,
        executionCount == 0,
        authorizationCount == 1,
        result.history.flatMap(_.toolResults).map(_.state) == Chunk(ToolResultState.Denied),
        result.history.flatMap(_.toolResults).forall(_.text.contains("host decision"))
      )
    },
    test(
      "construction and spawn effects are lazy, invalid configuration and closed admission acquire nothing"
    ) {
      for
        acquisitions <- Ref.make(0)
        owner        <- ZIO.acquireRelease(Scope.make)(_.close(Exit.unit))
        supervisor   <- owner.extend(TaskSupervisor.open[AgentResult])
        root         <- SubagentSpawner.root(supervisor)
        register = (_: SubagentSpawner[AgentResult]) => acquisitions.update(_ + 1).as(Toolkit.empty)
        client = (_: SubagentSpawner[AgentResult]) =>
          acquisitions.update(_ + 1) *> LlmClient.mock(Chunk.empty)
        invalid = SubagentFactory.make(
          config.copy(maxIterations = 0),
          model,
          Toolkit.empty,
          SubagentToolBoundary(),
          SubagentToolBoundary(),
          register,
          client,
          ToolAuthorizer.allow
        )
        configured <- factory(register = register, client = client)
        pending = configured.spawn(root, Msg.user("start"))
        before    <- acquisitions.get
        _         <- owner.close(Exit.unit)
        admission <- pending
        after     <- acquisitions.get
      yield assertTrue(invalid.isLeft, before == 0, after == 0, admission == TaskAdmission.Closed)
    },
    test("spawn supplies descendant authority and rejects depth four before factory acquisition") {
      for
        supervisor <- TaskSupervisor.open[AgentResult]
        root       <- SubagentSpawner.root(supervisor)
        seen       <- Ref.make(Chunk.empty[(Int, String)])
        configured <- factory(register =
          child => seen.update(_ :+ (child.depth, child.source)).as(Toolkit.empty)
        )
        first <- accepted(
          root.spawn(levelOne =>
            accepted(
              levelOne.spawn(levelTwo =>
                accepted(configured.spawn(levelTwo, Msg.user("third level")))
                  .flatMap(_.await.flatMap(exit => exit))
              )
            ).flatMap(_.await.flatMap(exit => exit))
          )
        )
        result <- first.await
        rejected <- accepted(
          root.spawn(levelOne =>
            accepted(
              levelOne.spawn(levelTwo =>
                accepted(
                  levelTwo.spawn(levelThree =>
                    configured
                      .spawn(levelThree, Msg.user("fourth level"))
                      .flatMap(_ => ZIO.fail(failure))
                  )
                ).flatMap(_.await.flatMap(exit => exit))
              )
            ).flatMap(_.await.flatMap(exit => exit))
          )
        )
        rejectedExit <- rejected.await
        observed     <- seen.get
      yield assertTrue(
        result.exists(_.reply.text == "done"),
        rejectedExit == Exit.fail(AgentError.SpawnDepthExceeded(3)),
        observed.size == 1,
        observed.forall { case (depth, source) =>
          depth == 3 && source.startsWith(root.source + "/") && source.split('/').length == 4
        }
      )
    },
    test(
      "scoped acquisition releases resources on success and each construction failure, before any invalid client acquisition"
    ) {
      ZIO
        .foreach(Chunk("registration", "assembly", "client", "success")) { stage =>
          for
            events     <- Ref.make(Chunk.empty[String])
            supervisor <- TaskSupervisor.open[AgentResult]
            root       <- SubagentSpawner.root(supervisor)
            configured <- factory(
              inherited = toolkit("collision"),
              register = _ =>
                ZIO.acquireRelease(events.update(_ :+ "register"))(_ =>
                  events.update(_ :+ "release-register")
                ) *>
                  (if stage == "registration" then ZIO.fail(failure)
                   else
                     ZIO.succeed(
                       if stage == "assembly" then toolkit("collision") else Toolkit.empty
                     )),
              client = _ =>
                ZIO.acquireRelease(events.update(_ :+ "client"))(_ =>
                  events.update(_ :+ "release-client")
                ) *>
                  (if stage == "client" then ZIO.fail(failure) else LlmClient.mock(Chunk.empty))
            )
            exit     <- ZIO.scoped(configured.build(root)).exit
            observed <- events.get
            expected =
              if Set("registration", "assembly").contains(stage) then
                Chunk("register", "release-register")
              else Chunk("register", "client", "release-client", "release-register")
          yield exit.isSuccess == (stage == "success") && observed == expected
        }
        .map(results => assertTrue(results.forall(identity)))
    },
    test(
      "cancelling registration, client acquisition or execution joins owned cleanup and remains interruption"
    ) {
      ZIO
        .foreach(Chunk("registration", "client", "execution")) { stage =>
          for
            supervisor <- TaskSupervisor.open[AgentResult]
            root       <- SubagentSpawner.root(supervisor)
            parent     <- ZIO.acquireRelease(Scope.make)(_.close(Exit.unit))
            entered    <- Promise.make[Nothing, Unit]
            cleaning   <- Promise.make[Nothing, Unit]
            release    <- Promise.make[Nothing, Unit]
            cleaned    <- Ref.make(Chunk.empty[String])
            configured <- factory(
              register = _ =>
                ZIO.acquireRelease(ZIO.unit)(_ =>
                  cleaning.succeed(()) *> release.await *> cleaned.update(_ :+ "register")
                ) *>
                  (if stage == "registration" then entered.succeed(()) *> ZIO.never
                   else ZIO.succeed(Toolkit.empty)),
              client = _ =>
                ZIO.acquireRelease(ZIO.unit)(_ => cleaned.update(_ :+ "client")) *>
                  (if stage == "client" then entered.succeed(()) *> ZIO.never
                   else
                     ZIO.succeed(
                       LlmClient.mockEffectful((_, _, _) => entered.succeed(()) *> ZIO.never)
                     ))
            )
            task          <- parent.extend(accepted(configured.spawn(root, Msg.user("start"))))
            _             <- entered.await
            closing       <- parent.close(Exit.unit).forkScoped
            _             <- cleaning.await
            beforeRelease <- closing.poll
            _             <- release.succeed(())
            _             <- closing.join
            exit          <- task.await
            observed      <- cleaned.get
          yield beforeRelease.isEmpty && exit.isInterrupted && observed == (if stage == "registration"
                                                                            then Chunk("register")
                                                                            else
                                                                              Chunk(
                                                                                "client",
                                                                                "register"
                                                                              ))
        }
        .map(results => assertTrue(results.forall(identity)))
    }
  )
