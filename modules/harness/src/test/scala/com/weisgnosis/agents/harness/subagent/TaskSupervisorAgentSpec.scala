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

import com.weisgnosis.agents.core.agent.{AgentResult, ReActAgent}
import com.weisgnosis.agents.core.client.{LlmClient, TurnOutput}
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.model.*
import com.weisgnosis.agents.core.tool.{Tool, ToolDef, Toolkit}
import zio.*
import zio.json.ast.Json
import zio.test.*

object TaskSupervisorAgentSpec extends ZIOSpecDefault:
  private def owner: ZIO[Scope, Nothing, Scope.Closeable] =
    ZIO.acquireRelease(Scope.make)(_.close(Exit.unit))

  private def accepted(
      admission: ZIO[Scope, Nothing, TaskAdmission[AgentResult]]
  ): ZIO[Scope, AgentError, SupervisedTask[AgentResult]] =
    admission.flatMap:
      case TaskAdmission.Accepted(task) => ZIO.succeed(task)
      case TaskAdmission.Closed =>
        ZIO.fail(AgentError.InvalidConfiguration("Unexpected closed supervisor"))

  private def runtime(
      entered: Promise[Nothing, Unit],
      release: Promise[Nothing, Unit],
      runs: Ref[Int],
      cleaned: Ref[Int]
  ): IO[AgentError, ReActAgent] =
    val tool = Tool.make(ToolDef("work", "Perform the owned child operation", Json.Obj()))(_ =>
      (runs.update(_ + 1) *> entered.succeed(()) *> release.await.as("observed"))
        .ensuring(cleaned.update(_ + 1))
    )
    for
      client <- LlmClient.mock(
        Chunk(
          TurnOutput.ToolCalls(
            Msg.assistant(Chunk(ContentBlock.ToolUse("child-call", "work", Json.Obj())))
          ),
          TurnOutput.FinalText(Msg.assistant("finished child"))
        )
      )
      agent <- ReActAgent.make(client = client, toolkit = Toolkit(Map("work" -> tool)))
    yield agent

  def spec: Spec[TestEnvironment & Scope, Any] = suite("TaskSupervisorAgentSpec")(
    test("attached parent cancellation reconciles actual child history before returning") {
      for
        supervisor <- TaskSupervisor.open[AgentResult]
        parent     <- owner
        entered    <- Promise.make[Nothing, Unit]
        release    <- Promise.make[Nothing, Unit]
        runs       <- Ref.make(0)
        cleaned    <- Ref.make(0)
        agent      <- runtime(entered, release, runs, cleaned)
        task       <- parent.extend(accepted(supervisor.attach(agent.run(Msg.user("run child")))))
        _          <- entered.await
        before     <- agent.history
        _          <- parent.close(Exit.unit)
        result     <- task.await
        history    <- agent.history
        count      <- cleaned.get
        executions <- runs.get
      yield assertTrue(
        before.flatMap(_.toolUses).map(_.id) == Chunk("child-call"),
        before.flatMap(_.toolResults).isEmpty,
        result.isInterrupted,
        history.flatMap(_.toolResults).map(_.id) == Chunk("child-call"),
        history.flatMap(_.toolResults).forall(_.state == ToolResultState.Error),
        history.lastOption.exists(_.generateReason.contains("interrupted")),
        count == 1,
        executions == 1
      )
    },
    test("promoted child completes the same tool and next model turn after parent closure") {
      for
        supervisor   <- TaskSupervisor.open[AgentResult]
        parent       <- owner
        entered      <- Promise.make[Nothing, Unit]
        release      <- Promise.make[Nothing, Unit]
        runs         <- Ref.make(0)
        cleaned      <- Ref.make(0)
        agent        <- runtime(entered, release, runs, cleaned)
        task         <- parent.extend(accepted(supervisor.attach(agent.run(Msg.user("run child")))))
        _            <- entered.await
        waiting      <- task.awaitOrPromote.forkScoped
        _            <- TestClock.adjust(30.seconds)
        outcome      <- waiting.join
        _            <- parent.close(Exit.unit)
        priorCleaned <- cleaned.get
        _            <- release.succeed(())
        result       <- task.await
        history      <- agent.history
        executions   <- runs.get
        finalCleaned <- cleaned.get
      yield assertTrue(
        outcome == TaskWait.Promoted,
        priorCleaned == 0,
        result.exists(_.reply.text == "finished child"),
        history.flatMap(_.toolResults).map(_.id) == Chunk("child-call"),
        history.flatMap(_.toolResults).forall(_.state == ToolResultState.Success),
        !history.exists(_.generateReason.contains("interrupted")),
        executions == 1,
        finalCleaned == 1
      )
    }
  )
