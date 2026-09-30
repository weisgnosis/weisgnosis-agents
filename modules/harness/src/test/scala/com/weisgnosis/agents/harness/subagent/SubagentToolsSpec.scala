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

import com.weisgnosis.agents.core.agent.{AgentConfig, AgentResult, ReActAgent}
import com.weisgnosis.agents.core.client.{LlmClient, LlmConfig, TurnOutput}
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.model.{ContentBlock, Msg, ToolResultState}
import com.weisgnosis.agents.core.tool.{Tool, ToolAuthorizer, ToolDef, Toolkit}
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

object SubagentToolsSpec extends ZIOSpecDefault:
  private val input: Json = Json.Obj("prompt" -> Json.Str("child work"))

  private def factory(
      register: SubagentSpawner[AgentResult] => ZIO[Scope, AgentError, Toolkit],
      client: SubagentSpawner[AgentResult] => ZIO[Scope, AgentError, LlmClient] = _ =>
        LlmClient.mock(Chunk(TurnOutput.FinalText(Msg.assistant("done"))))
  ): IO[AgentError, SubagentFactory] =
    ZIO.fromEither(
      SubagentFactory.make(
        AgentConfig(name = "tool-child"),
        LlmConfig("scripted-child"),
        Toolkit.empty,
        SubagentToolBoundary(denied = Set("forbidden")),
        SubagentToolBoundary(),
        register,
        client,
        ToolAuthorizer.allow
      )
    )

  private def registry(
      configured: SubagentFactory,
      capacity: Int = 8
  ): ZIO[Scope, AgentError, SubagentTaskRegistry] =
    for
      supervisor <- TaskSupervisor.open[AgentResult]
      root       <- SubagentSpawner.root(supervisor)
      tasks      <- SubagentTaskRegistry.open(root, configured, capacity)
    yield tasks

  private def taskInput(id: String): Json = Json.Obj("task_id" -> Json.Str(id))

  private def observation(output: String): IO[AgentError, (String, SubagentToolState)] =
    ZIO
      .fromEither(output.dropWhile(_ != '{').fromJson[SubagentToolResult])
      .mapError(AgentError.InvalidConfiguration(_))
      .flatMap:
        case SubagentToolResult.Task(id, state) => ZIO.succeed((id, state))
        case other =>
          ZIO.fail(AgentError.InvalidConfiguration(s"Expected task observation: $other"))

  def spec: Spec[TestEnvironment & Scope, Any] = suite("SubagentToolsSpec")(
    test("a parent ReAct delegates to the fixed filtered child and receives its retained result") {
      for
        executions <- Ref.make(0)
        seen       <- Ref.make(Chunk.empty[Set[String]])
        forbidden = Tool.make(ToolDef("forbidden", "Excluded child tool", Json.Obj()))(_ =>
          executions.update(_ + 1).as("executed")
        )
        configured <- factory(
          _ => ZIO.succeed(Toolkit(Map("forbidden" -> forbidden))),
          _ =>
            ZIO.succeed(
              LlmClient.mockEffectful((history, tools, _) =>
                seen
                  .update(_ :+ tools.map(_.name).toSet)
                  .as(
                    if history.exists(_.toolUses.nonEmpty) then
                      TurnOutput.FinalText(Msg.assistant("child done"))
                    else
                      TurnOutput.ToolCalls(
                        Msg.assistant(
                          Chunk(ContentBlock.ToolUse("forbidden-call", "forbidden", Json.Obj()))
                        )
                      )
                  )
              )
            )
        )
        tasks <- registry(configured)
        tools = SubagentTools.make(tasks)
        client <- LlmClient.mock(
          Chunk(
            TurnOutput.ToolCalls(
              Msg.assistant(Chunk(ContentBlock.ToolUse("spawn", "agent_spawn", input)))
            ),
            TurnOutput.FinalText(Msg.assistant("parent done"))
          )
        )
        parent <- ReActAgent.make(AgentConfig(), tools, client, LlmConfig("scripted-parent"))
        result <- parent.run(Msg.user("delegate"))
        output <- ZIO
          .fromOption(result.history.flatMap(_.toolResults).headOption)
          .orElseFail(AgentError.InvalidConfiguration("Missing spawn observation"))
        observed  <- observation(output.outputText)
        inspected <- tools.executeSafe("agent_task", taskInput(observed._1))
        readBack  <- observation(inspected.output)
        count     <- executions.get
        schemas   <- seen.get
        tracked   <- tasks.list
      yield assertTrue(
        result.reply.text == "parent done",
        output.state == ToolResultState.Success,
        observed._2 == SubagentToolState.Completed(SubagentToolText("child done", 0)),
        readBack == observed,
        count == 0,
        schemas.nonEmpty && schemas.forall(_.isEmpty),
        tracked.size == 1,
        result.history.indexWhere(_.toolUses.exists(_.id == "spawn")) < result.history.indexWhere(
          _.toolResults.nonEmpty
        )
      )
    },
    test(
      "promotion retains one execution and inspection and cancellation never call the model again"
    ) {
      for
        entered  <- Promise.make[Nothing, Unit]
        cleaning <- Promise.make[Nothing, Unit]
        release  <- Promise.make[Nothing, Unit]
        calls    <- Ref.make(0)
        configured <- factory(
          _ => ZIO.addFinalizer(cleaning.succeed(()) *> release.await).as(Toolkit.empty),
          _ =>
            ZIO.succeed(
              LlmClient.mockEffectful((_, _, _) =>
                calls.update(_ + 1) *> entered.succeed(()) *> ZIO.never
              )
            )
        )
        tasks <- registry(configured)
        tools = SubagentTools.make(tasks)
        spawning   <- tools.executeSafe("agent_spawn", input).forkScoped
        _          <- entered.await
        _          <- TestClock.adjust(30.seconds)
        spawned    <- spawning.join
        promoted   <- observation(spawned.output)
        inspected  <- tools.executeSafe("agent_task", taskInput(promoted._1))
        running    <- observation(inspected.output)
        cancelling <- tools.executeSafe("agent_cancel", taskInput(promoted._1)).forkScoped
        _          <- cleaning.await
        pending    <- cancelling.poll
        _          <- release.succeed(())
        cancelled  <- cancelling.join
        terminal   <- observation(cancelled.output)
        again      <- tools.executeSafe("agent_task", taskInput(promoted._1))
        retained   <- observation(again.output)
        count      <- calls.get
      yield assertTrue(
        promoted._2 == SubagentToolState.Promoted,
        running == (promoted._1, SubagentToolState.Running),
        pending.isEmpty,
        cancelled.state == ToolResultState.Success,
        terminal == (promoted._1, SubagentToolState.Interrupted),
        retained == terminal,
        count == 1
      )
    },
    test(
      "strict schemas and inputs reject extra authority fields and unknown task identifiers without work"
    ) {
      val id = "00000000-0000-0000-0000-000000000001"
      val malformed = Chunk(
        "agent_spawn"  -> Json.Obj(),
        "agent_spawn"  -> Json.Str("work"),
        "agent_spawn"  -> Json.Obj("prompt" -> Json.Null),
        "agent_spawn"  -> Json.Obj("prompt" -> Json.Str("a"), "prompt" -> Json.Str("b")),
        "agent_spawn"  -> Json.Obj("prompt" -> Json.Str("a"), "depth" -> Json.Num(0)),
        "agent_task"   -> Json.Obj("task_id" -> Json.Num(1)),
        "agent_task"   -> Json.Obj("task_id" -> Json.Str(id), "task_id" -> Json.Str(id)),
        "agent_task"   -> Json.Obj("task_id" -> Json.Str(id), "parent" -> Json.Str("other")),
        "agent_cancel" -> Json.Obj("task_id" -> Json.Str("1-1-1-1-1")),
        "agent_cancel" -> Json.Obj("task_id" -> Json.Str(id), "force" -> Json.Bool(true)),
        "agent_cancel" -> Json.Obj("task_id" -> Json.Str(id), "task_id" -> Json.Str(id))
      )
      for
        calls      <- Ref.make(0)
        configured <- factory(_ => calls.update(_ + 1).as(Toolkit.empty))
        tasks      <- registry(configured)
        tools = SubagentTools.make(tasks)
        failures <- ZIO.foreach(malformed)((name, raw) => tools.executeSafe(name, raw))
        unknown <- ZIO.foreach(Chunk("agent_task", "agent_cancel"))(name =>
          tools.executeSafe(name, taskInput(id))
        )
        missing <- ZIO.foreach(unknown)(result => observation(result.output))
        count   <- calls.get
        tracked <- tasks.list
      yield assertTrue(
        failures.forall(_.state == ToolResultState.Error),
        unknown.forall(_.state == ToolResultState.Error),
        missing.forall(_ == (id, SubagentToolState.Missing)),
        count == 0 && tracked.isEmpty,
        tools.tools.keySet == Set("agent_spawn", "agent_task", "agent_cancel"),
        tools.definitions.forall(definition =>
          definition.isReadOnly == (definition.name == "agent_task")
        ),
        tools.definitions.forall(_.isConcurrencySafe),
        tools.definitions.forall(
          _.parametersSchema.asObject.exists(
            _.get("additionalProperties").contains(Json.Bool(false))
          )
        )
      )
    },
    test(
      "child failures and interruption are identified Error observations while retained status stays inspectable"
    ) {
      val failure   = AgentError.InvalidConfiguration("failure " + "x" * 5000)
      val defect    = new IllegalStateException("private defect detail must not leak")
      val longReply = "h" * 1996 + "\uD83D\uDE80" + "m" * 2000 + "\uD83D\uDE80" + "t" * 1997
      val cases: Chunk[
        (ZIO[Scope, AgentError, Toolkit], ToolResultState, SubagentToolState => Boolean)
      ] = Chunk(
        (
          ZIO.succeed(Toolkit.empty),
          ToolResultState.Success,
          {
            case SubagentToolState.Completed(reply) =>
              reply.text == ("h" * 1996 + "\n...\n" + "t" * 1997) && reply.omittedCharacters == 2004
            case _ => false
          }
        ),
        (
          ZIO.fail(failure),
          ToolResultState.Error,
          {
            case SubagentToolState.Failed(message) =>
              message.text.length <= SubagentTools.MaximumTextCharacters && message.omittedCharacters > 0
            case _ => false
          }
        ),
        (
          ZIO.die(defect),
          ToolResultState.Error,
          {
            case SubagentToolState.Failed(message) =>
              message.text == "Task failed with an internal defect"
            case _ => false
          }
        ),
        (ZIO.interrupt, ToolResultState.Error, _ == SubagentToolState.Interrupted)
      )
      ZIO
        .foreach(cases) { case (registration, expected, valid) =>
          for
            configured <- factory(
              _ => registration,
              _ => LlmClient.mock(Chunk(TurnOutput.FinalText(Msg.assistant(longReply))))
            )
            tasks <- registry(configured, 1)
            tools = SubagentTools.make(tasks)
            spawned   <- tools.executeSafe("agent_spawn", input)
            observed  <- observation(spawned.output)
            inspected <- tools.executeSafe("agent_task", taskInput(observed._1))
            retained  <- observation(inspected.output)
            rejected  <- tools.executeSafe("agent_spawn", input)
          yield assertTrue(
            spawned.state == expected,
            valid(observed._2),
            retained == observed,
            inspected.state == ToolResultState.Success,
            rejected.state == ToolResultState.Error,
            rejected.output.contains("Capacity"),
            !spawned.output.contains("private defect detail"),
            !spawned.output.contains("SubagentToolsSpec.scala")
          )
        }
        .map(results => results.reduceOption(_ && _).getOrElse(assertTrue(false)))
    },
    test(
      "interrupting the active spawn tool waits for child cleanup and preserves caller interruption"
    ) {
      for
        entered  <- Promise.make[Nothing, Unit]
        cleaning <- Promise.make[Nothing, Unit]
        release  <- Promise.make[Nothing, Unit]
        configured <- factory(_ =>
          ZIO.addFinalizer(cleaning.succeed(()) *> release.await) *> entered.succeed(
            ()
          ) *> ZIO.never
        )
        tasks <- registry(configured)
        tools = SubagentTools.make(tasks)
        spawning <- tools.executeSafe("agent_spawn", input).forkScoped
        _        <- entered.await
        stopping <- spawning.interrupt.forkScoped
        _        <- cleaning.await
        pending  <- stopping.poll
        _        <- release.succeed(())
        exit     <- stopping.join
        tracked  <- tasks.list
      yield assertTrue(
        pending.isEmpty,
        exit.isInterrupted,
        tracked.size == 1,
        tracked.forall {
          case SubagentTaskSnapshot(_, SubagentTaskState.Completed(result)) => result.isInterrupted
          case _                                                            => false
        }
      )
    }
  )
