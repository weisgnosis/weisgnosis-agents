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
import com.weisgnosis.agents.core.event.AgentEvent
import com.weisgnosis.agents.core.model.{ContentBlock, Msg, Role, ToolResultState}
import com.weisgnosis.agents.core.tool.{ToolAuthorizer, Toolkit}
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

object SubagentSessionCatalogToolsSpec extends ZIOSpecDefault:
  private def entry(
      name: String,
      client: ZIO[Scope, AgentError, LlmClient]
  ): IO[AgentError, SubagentEntry] =
    ZIO
      .fromEither(
        SubagentFactory.make(
          AgentConfig(name = name),
          LlmConfig("scripted"),
          Toolkit.empty,
          SubagentToolBoundary(),
          SubagentToolBoundary(),
          _ => ZIO.succeed(Toolkit.empty),
          _ => client,
          ToolAuthorizer.allow
        )
      )
      .map(SubagentEntry(name, s"Tasks for $name", _))

  private def catalog(
      parent: SubagentSpawner[AgentResult],
      entries: Chunk[SubagentEntry]
  ): ZIO[Scope, AgentError, SubagentSessionCatalog] =
    SubagentSessionCatalog
      .open(parent, entries)
      .flatMap:
        case TaskResourceAdmission.Accepted(resource) => ZIO.succeed(resource.value)
        case TaskResourceAdmission.Closed =>
          ZIO.fail(AgentError.InvalidConfiguration("Closed test owner"))

  private def send(name: String, prompt: String): Json =
    Json.Obj("agent_id" -> Json.Str(name), "prompt" -> Json.Str(prompt))

  private def task(name: String, id: String): Json =
    Json.Obj("agent_id" -> Json.Str(name), "task_id" -> Json.Str(id))

  private def observation(text: String): IO[AgentError, SubagentCatalogToolResult] =
    ZIO
      .fromEither(text.dropWhile(_ != '{').fromJson[SubagentCatalogToolResult])
      .mapError(AgentError.InvalidConfiguration(_))

  private def observedTask(text: String): IO[AgentError, (String, String, SubagentToolState)] =
    observation(text).flatMap:
      case SubagentCatalogToolResult.Agent(name, SubagentToolResult.Task(id, state)) =>
        ZIO.succeed((name, id, state))
      case other => ZIO.fail(AgentError.InvalidConfiguration(s"Expected selected task: $other"))

  def spec: Spec[TestEnvironment & Scope, Any] = suite("SubagentSessionCatalogToolsSpec")(
    test("parent sends reuse named histories with fresh correlated execution identities") {
      for
        supervisor <- TaskSupervisor.open[AgentResult]
        root       <- SubagentSpawner.root(supervisor)
        builds     <- Ref.make(Chunk.empty[String])
        calls      <- Ref.make(0)
        entries <- ZIO.foreach(Chunk("alpha", "beta"))(name =>
          entry(
            name,
            builds
              .update(_ :+ name)
              .as(
                LlmClient.mockEffectful((history, _, _) =>
                  calls
                    .update(_ + 1)
                    .as(
                      TurnOutput.FinalText(
                        Msg.assistant(
                          history.filter(_.role == Role.User).map(_.text).mkString("|")
                        )
                      )
                    )
                )
              )
          )
        )
        installed <- catalog(root, entries)
        tools = SubagentTools.forSessionCatalog(installed)
        parent <- ReActAgent.make(
          client = LlmClient.mockFunction((history, _) =>
            val inputs = Chunk(send("alpha", "one"), send("alpha", "two"), send("beta", "other"))
            inputs.lift(history.flatMap(_.toolResults).size) match
              case Some(input) =>
                TurnOutput.ToolCalls(
                  Msg.assistant(
                    Chunk(
                      ContentBlock.ToolUse("repeated-model-id", "agent_send", input)
                    )
                  )
                )
              case None => TurnOutput.FinalText(Msg.assistant("done"))
          ),
          toolkit = tools
        )
        events  <- parent.streamEvents(Msg.user("work")).runCollect
        history <- parent.history
        observations <- ZIO.foreach(history.flatMap(_.toolResults))(result =>
          observedTask(result.text)
        )
        built <- builds.get
        count <- calls.get
        starts = events.collect { case event: AgentEvent.AgentStartEvent => event.source }.flatten
        ends   = events.collect { case event: AgentEvent.AgentEndEvent => event.source }.flatten
      yield assertTrue(
        tools.tools.keySet == Set("agent_send", "agent_task", "agent_cancel"),
        tools.definitions.forall(_.description.contains("alpha: Tasks for alpha")),
        built == Chunk("alpha", "beta"),
        count == 3,
        observations.map(_._1) == Chunk("alpha", "alpha", "beta"),
        observations.map(_._3) == Chunk("one", "one|two", "other").map(text =>
          SubagentToolState.Completed(SubagentToolText(text, 0))
        ),
        observations.map(_._2).distinct.size == 3,
        starts == observations.map(_._2),
        ends == starts,
        events.lastOption.exists {
          case event: AgentEvent.AgentEndEvent => event.source.isEmpty
          case _                               => false
        }
      )
    },
    test("strict selection, foreign task IDs and removal never create or restart sessions") {
      for
        supervisor <- TaskSupervisor.open[AgentResult]
        root       <- SubagentSpawner.root(supervisor)
        builds     <- Ref.make(0)
        calls      <- Ref.make(0)
        entries <- ZIO.foreach(Chunk("alpha", "beta"))(name =>
          entry(
            name,
            builds
              .update(_ + 1)
              .as(
                LlmClient.mockEffectful((_, _, _) =>
                  calls.update(_ + 1).as(TurnOutput.FinalText(Msg.assistant("ok")))
                )
              )
          )
        )
        installed <- catalog(root, entries)
        other     <- catalog(root, entries.take(1))
        tools      = SubagentTools.forSessionCatalog(installed)
        otherTools = SubagentTools.forSessionCatalog(other)
        invalid <- ZIO.foreach(
          Chunk(
            Json.Obj(
              "agent_id"   -> Json.Str("alpha"),
              "prompt"     -> Json.Str("x"),
              "session_id" -> Json.Str("extra")
            ),
            Json.Obj(
              "agent_id" -> Json.Str("alpha"),
              "agent_id" -> Json.Str("alpha"),
              "prompt"   -> Json.Str("x")
            ),
            Json.Obj("agent_id" -> Json.Str("alpha"), "prompt" -> Json.Num(1)),
            send("Alpha", "unknown")
          )
        )(tools.executeSafe("agent_send", _))
        before         <- calls.get
        sent           <- tools.executeSafe("agent_send", send("alpha", "work"))
        selected       <- observedTask(sent.output)
        foreignName    <- tools.executeSafe("agent_task", task("beta", selected._2))
        foreignCatalog <- otherTools.executeSafe("agent_cancel", task("alpha", selected._2))
        foreignStates <- ZIO.foreach(Chunk(foreignName, foreignCatalog))(result =>
          observedTask(result.output)
        )
        malformed    <- tools.executeSafe("agent_task", task("alpha", "not-a-uuid"))
        removed      <- installed.remove("alpha")
        afterRemoval <- tools.executeSafe("agent_send", send("alpha", "never recreate"))
        removedTask  <- tools.executeSafe("agent_task", task("alpha", selected._2))
        missing <- ZIO.foreach(Chunk(afterRemoval, removedTask))(result =>
          observation(result.output)
        )
        count <- calls.get
        built <- builds.get
      yield assertTrue(
        invalid.forall(_.state == ToolResultState.Error),
        before == 0,
        sent.state == ToolResultState.Success,
        foreignName.state == ToolResultState.Error,
        foreignCatalog.state == ToolResultState.Error,
        foreignStates.map(_._3) == Chunk.fill(2)(SubagentToolState.Missing),
        malformed.state == ToolResultState.Error,
        removed == SubagentSessionRemoval.Removed,
        afterRemoval.state == ToolResultState.Error,
        removedTask.state == ToolResultState.Error,
        missing == Chunk.fill(2)(SubagentCatalogToolResult.Missing("alpha")),
        count == 1,
        built == 3
      )
    },
    test("promoted named sends retain inspection and cancellation joins child cleanup") {
      val defect = new IllegalStateException("cancelled child cleanup")
      for
        supervisor <- TaskSupervisor.open[AgentResult]
        root       <- SubagentSpawner.root(supervisor)
        entered    <- Promise.make[Nothing, Unit]
        cleaning   <- Promise.make[Nothing, Unit]
        release    <- Promise.make[Nothing, Unit]
        calls      <- Ref.make(0)
        selected <- entry(
          "alpha",
          ZIO.succeed(
            LlmClient.mockEffectful((_, _, _) =>
              calls.update(_ + 1) *> ZIO.acquireRelease(entered.succeed(()))(_ =>
                cleaning.succeed(()) *> release.await *> ZIO.die(defect)
              ) *> ZIO.never
            )
          )
        )
        installed <- catalog(root, Chunk(selected))
        tools = SubagentTools.forSessionCatalog(installed)
        result <- ZIO.scoped {
          (for
            running      <- tools.executeSafe("agent_send", send("alpha", "work")).forkScoped
            _            <- entered.await
            _            <- TestClock.adjust(30.seconds)
            promoted     <- running.join
            selectedTask <- observedTask(promoted.output)
            inspected    <- tools.executeSafe("agent_task", task("alpha", selectedTask._2))
            runningTask  <- observedTask(inspected.output)
            cancelling <- tools
              .executeSafe("agent_cancel", task("alpha", selectedTask._2))
              .forkScoped
            _         <- cleaning.await
            pending   <- cancelling.poll
            _         <- release.succeed(())
            cancelled <- cancelling.join
            stopped   <- observedTask(cancelled.output)
            member <- installed
              .lookup("alpha")
              .someOrFail(AgentError.InvalidConfiguration("Missing member"))
            retained <- member._2.executions.list
            count    <- calls.get
          yield assertTrue(
            promoted.state == ToolResultState.Success,
            selectedTask._1 == "alpha",
            selectedTask._3 == SubagentToolState.Promoted,
            runningTask == ("alpha", selectedTask._2, SubagentToolState.Running),
            pending.isEmpty,
            cancelled.state == ToolResultState.Success,
            stopped == ("alpha", selectedTask._2, SubagentToolState.Interrupted),
            retained.exists(_.state match
              case SubagentTaskState.Completed(Exit.Failure(cause)) =>
                cause.isInterrupted && cause.defects == List(defect)
              case _ => false),
            count == 1
          )).ensuring(release.succeed(()).unit)
        }
      yield result
    }
  ) @@ TestAspect.timeout(20.seconds)
