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

object SubagentSpawnerAgentSpec extends ZIOSpecDefault:
  private enum Scenario:
    case DescendPastLimit, BlockAtLimit

  private def accepted(
      admission: ZIO[Scope, AgentError, TaskAdmission[AgentResult]]
  ): ZIO[Scope, AgentError, SupervisedTask[AgentResult]] =
    admission.flatMap:
      case TaskAdmission.Accepted(task) => ZIO.succeed(task)
      case TaskAdmission.Closed =>
        ZIO.fail(AgentError.InvalidConfiguration("Unexpected closed supervisor"))

  private def run(
      spawner: SubagentSpawner[AgentResult],
      scenario: Scenario,
      agents: Ref[Chunk[(Int, ReActAgent)]],
      entered: Promise[Nothing, Unit],
      cleaned: Ref[Chunk[Int]]
  ): ZIO[Scope, AgentError, AgentResult] =
    val tool =
      Tool.make(ToolDef("spawn_child", "Invoke the runtime child capability", Json.Obj())) { _ =>
        val action = scenario match
          case Scenario.BlockAtLimit if spawner.depth == SubagentSpawner.MaximumDepth =>
            entered.succeed(()) *> ZIO.never
          case _ =>
            ZIO.scoped(
              accepted(spawner.spawn(child => run(child, scenario, agents, entered, cleaned)))
                .flatMap(_.await.flatMap(exit => exit))
                .map(_.reply.text)
            )
        action.ensuring(cleaned.update(_ :+ spawner.depth))
      }
    for
      client <- LlmClient.mock(
        Chunk(
          TurnOutput.ToolCalls(
            Msg.assistant(
              Chunk(ContentBlock.ToolUse(s"depth-${spawner.depth}", "spawn_child", Json.Obj()))
            )
          ),
          TurnOutput.FinalText(Msg.assistant(s"finished depth ${spawner.depth}"))
        )
      )
      agent  <- ReActAgent.make(client = client, toolkit = Toolkit.single(tool))
      _      <- agents.update(_ :+ (spawner.depth, agent))
      result <- agent.run(Msg.user("delegate the next bounded child"))
    yield result

  def spec: Spec[TestEnvironment & Scope, Any] = suite("SubagentSpawnerAgentSpec")(
    test("fourth nested spawn becomes an Error observation without constructing a fourth agent") {
      for
        supervisor <- TaskSupervisor.open[AgentResult]
        root       <- SubagentSpawner.root(supervisor)
        agents     <- Ref.make(Chunk.empty[(Int, ReActAgent)])
        entered    <- Promise.make[Nothing, Unit]
        cleaned    <- Ref.make(Chunk.empty[Int])
        task <- accepted(
          root.spawn(child => run(child, Scenario.DescendPastLimit, agents, entered, cleaned))
        )
        result    <- task.await
        created   <- agents.get
        histories <- ZIO.foreach(created) { case (depth, agent) => agent.history.map(depth -> _) }
        leafResults   = histories.filter(_._1 == 3).flatMap(_._2.flatMap(_.toolResults))
        parentResults = histories.filter(_._1 < 3).flatMap(_._2.flatMap(_.toolResults))
        cleanup <- cleaned.get
      yield assertTrue(
        result.exists(_.reply.text == "finished depth 1"),
        created.map(_._1) == Chunk(1, 2, 3),
        leafResults.size == 1,
        leafResults.forall(result =>
          result.state == ToolResultState.Error && result.text.contains(
            "Maximum subagent spawn depth exceeded: 3"
          )
        ),
        parentResults.size == 2,
        parentResults.forall(_.state == ToolResultState.Success),
        cleanup.sorted == Chunk(1, 2, 3)
      )
    },
    test(
      "parent cancellation cascades through actual nested agents and completes every reconciliation"
    ) {
      for
        supervisor <- TaskSupervisor.open[AgentResult]
        parent     <- ZIO.acquireRelease(Scope.make)(_.close(Exit.unit))
        root       <- SubagentSpawner.root(supervisor)
        agents     <- Ref.make(Chunk.empty[(Int, ReActAgent)])
        entered    <- Promise.make[Nothing, Unit]
        cleaned    <- Ref.make(Chunk.empty[Int])
        task <- parent.extend(
          accepted(root.spawn(child => run(child, Scenario.BlockAtLimit, agents, entered, cleaned)))
        )
        _         <- entered.await
        _         <- parent.close(Exit.unit)
        result    <- task.await
        created   <- agents.get
        histories <- ZIO.foreach(created)(_._2.history)
        cleanup   <- cleaned.get
      yield assertTrue(
        result.isInterrupted,
        created.map(_._1) == Chunk(1, 2, 3),
        histories.forall(history =>
          history.flatMap(_.toolUses).size == 1 && history.flatMap(_.toolResults).size == 1
        ),
        histories.forall(_.flatMap(_.toolResults).forall(_.state == ToolResultState.Error)),
        histories.forall(_.lastOption.exists(_.generateReason.contains("interrupted"))),
        cleanup.sorted == Chunk(1, 2, 3)
      )
    }
  )
