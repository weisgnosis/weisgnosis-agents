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
package com.weisgnosis.agents.storage.postgresql

import com.weisgnosis.agents.core.agent.{AgentConfig, AgentResult, ReActAgent}
import com.weisgnosis.agents.core.client.{LlmClient, LlmConfig, TurnOutput}
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.model.{ContentBlock, Msg, ToolResultState}
import com.weisgnosis.agents.core.tool.{ToolAuthorizer, Toolkit}
import com.weisgnosis.agents.harness.subagent.*
import java.util.UUID
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

object PostgresSubagentTerminalStoreSpec extends ZIOSpecDefault:
  import PostgresFixture.*

  private val firstId: SubagentTaskId  = SubagentTaskId(new UUID(0L, 1L))
  private val secondId: SubagentTaskId = SubagentTaskId(new UUID(0L, 2L))

  private def accepted(
      admission: TaskAdmission[AgentResult]
  ): IO[AgentError, SupervisedTask[AgentResult]] =
    admission match
      case TaskAdmission.Accepted(task) => ZIO.succeed(task)
      case TaskAdmission.Closed =>
        ZIO.fail(AgentError.InvalidConfiguration("Fixture supervisor closed"))

  private def submitted(
      registry: SubagentTaskRegistry,
      prompt: String
  ): ZIO[Scope, AgentError, SubagentTaskId] =
    registry
      .submit(Msg.user(prompt))
      .flatMap:
        case SubagentTaskSubmission.Accepted(id) => ZIO.succeed(id)
        case other =>
          ZIO.fail(AgentError.InvalidConfiguration(s"Unexpected fixture admission: $other"))

  private def query(
      registry: SubagentTaskRegistry,
      calls: Chunk[ContentBlock.ToolUse]
  ): ZIO[Scope, AgentError, AgentResult] =
    for
      toolkit <- ZIO.fromEither(
        SubagentTools.make(registry) ++ Toolkit.single(SubagentTaskListTool.make(registry))
      )
      client <- LlmClient.mock(
        Chunk(
          TurnOutput.ToolCalls(Msg.assistant(calls)),
          TurnOutput.FinalText(Msg.assistant("inspected"))
        )
      )
      parent <- ReActAgent.make(client = client, toolkit = toolkit)
      result <- parent.run(Msg.user("Inspect previously completed work"))
    yield result

  def spec: Spec[TestEnvironment, Any] = suite("PostgresSubagentTerminalStoreSpec")(
    test(
      "joined terminal observations reopen exactly across PostgreSQL adapters with owner isolation and idempotent concurrent publication"
    ):
      fixture: (_, backend, name) =>
        ZIO.scoped:
          for
            secondSource <- dataSource(name + "-reopened")
            second       <- store(secondSource)
            target       <- address(name, "owner-a")
            other        <- address(name, "owner-b")
            terminal = SubagentTerminalStore.make(backend, target)
            reopened = SubagentTerminalStore.make(second, target)
            supervisor <- TaskSupervisor.open[AgentResult]
            cleanup    <- Promise.make[Nothing, Unit]
            release    <- Promise.make[Nothing, Unit]
            reply = Msg.assistant(" exact output \n" + "λé" * 3000)
            admission <- supervisor.attach(
              ZIO.acquireRelease(ZIO.unit)(_ => cleanup.succeed(()).unit *> release.await) *>
                ZIO.succeed(AgentResult(reply, Chunk(reply), 1))
            )
            task <- accepted(admission)
            publishing <- ZIO.uninterruptible:
              for
                fiber <- terminal.publishJoined(firstId, task).interruptible.forkScoped
                _     <- ZIO.addFinalizer(release.succeed(()).unit)
              yield fiber
            _       <- cleanup.await
            before  <- reopened.read(firstId)
            pending <- publishing.poll
            _       <- release.succeed(())
            receipt <- publishing.join
            exact   <- reopened.read(firstId)
            failedAdmission <- supervisor.attach(
              ZIO.acquireRelease(ZIO.unit)(_ =>
                ZIO.die(new IllegalStateException("Task cleanup defect"))
              ) *>
                ZIO.fail(AgentError.StorageError("Execution failed exactly"))
            )
            failedTask <- accepted(failedAdmission)
            publications <- ZIO.foreachPar(Chunk(terminal, reopened))(
              _.publishJoined(secondId, failedTask)
            )
            recovered <- reopened.list
            isolated  <- SubagentTerminalStore.make(second, other).list
          yield assertTrue(
            before.isEmpty,
            pending.isEmpty,
            receipt match
              case SubagentTerminalPublication.Stored(_)        => true
              case SubagentTerminalPublication.AlreadyStored(_) => false
            ,
            exact.contains(
              SubagentTerminalRecord(firstId, SubagentTerminalOutcome.Completed(reply.text))
            ),
            recovered == Chunk(
              SubagentTerminalRecord(firstId, SubagentTerminalOutcome.Completed(reply.text)),
              SubagentTerminalRecord(
                secondId,
                SubagentTerminalOutcome.Failed(
                  Chunk("Execution failed exactly"),
                  SubagentTaskDefects.Present
                )
              )
            ),
            publications.count(value =>
              value match
                case SubagentTerminalPublication.Stored(_)        => true
                case SubagentTerminalPublication.AlreadyStored(_) => false
            ) == 1,
            publications.count(value =>
              value match
                case SubagentTerminalPublication.AlreadyStored(_) => true
                case SubagentTerminalPublication.Stored(_)        => false
            ) == 1,
            isolated.isEmpty
          )
    ,
    test(
      "automatic durable publication survives owner closure and existing ReAct task tools restore exact bounded observations from PostgreSQL"
    ):
      fixture: (_, backend, name) =>
        ZIO.scoped:
          for
            target <- address(name, "registry-owner")
            other  <- address(name, "different-owner")
            terminal = SubagentTerminalStore.make(backend, target)
            childCalls <- Ref.make(0)
            text    = " exact persisted output \n" + "λé" * 3000
            failure = AgentError.ModelError(503, "Exact child model failure")
            childClient = LlmClient.mockEffectful((history, _, _) =>
              childCalls.update(_ + 1) *> (
                if history.lastOption.exists(_.text == "fail") then ZIO.fail(failure)
                else ZIO.succeed(TurnOutput.FinalText(Msg.assistant(text)))
              )
            )
            factory <- ZIO.fromEither(
              SubagentFactory.make(
                AgentConfig(),
                LlmConfig("scripted"),
                Toolkit.empty,
                SubagentToolBoundary(),
                SubagentToolBoundary(),
                _ => ZIO.succeed(Toolkit.empty),
                _ => ZIO.succeed(childClient),
                ToolAuthorizer.allow
              )
            )
            ids <- ZIO.scoped:
              for
                supervisor <- TaskSupervisor.open[AgentResult]
                parent     <- SubagentSpawner.root(supervisor)
                original   <- SubagentTaskRegistry.openDurable(parent, factory, terminal)
                success    <- submitted(original, "succeed")
                failed     <- submitted(original, "fail")
                receipts   <- original.flushPublications
              yield (success, failed, receipts)
            (success, failed, receipts) = ids
            secondSource <- dataSource(name + "-fresh-registry")
            second       <- store(secondSource)
            reopened = SubagentTerminalStore.make(second, target)
            exact        <- reopened.read(success)
            failedRecord <- reopened.read(failed)
            supervisor   <- TaskSupervisor.open[AgentResult]
            parent       <- SubagentSpawner.root(supervisor)
            fresh        <- SubagentTaskRegistry.openDurable(parent, factory, reopened)
            unrelated <- SubagentTaskRegistry.openDurable(
              parent,
              factory,
              SubagentTerminalStore.make(second, other)
            )
            liveBefore <- fresh.list
            result <- query(
              fresh,
              Chunk(
                ContentBlock.ToolUse(
                  "success",
                  "agent_task",
                  Json.Obj("task_id" -> Json.Str(success.value.toString))
                ),
                ContentBlock.ToolUse(
                  "failure",
                  "agent_task",
                  Json.Obj("task_id" -> Json.Str(failed.value.toString))
                ),
                ContentBlock.ToolUse(
                  "completed",
                  "task_list",
                  Json.Obj("status_filter" -> Json.Str("completed"))
                ),
                ContentBlock.ToolUse(
                  "failed",
                  "task_list",
                  Json.Obj("status_filter" -> Json.Str("failed"))
                )
              )
            )
            observations = result.history.flatMap(_.toolResults)
            taskResults <- ZIO.foreach(
              observations.filter(value => value.id == "success" || value.id == "failure")
            )(value =>
              ZIO
                .fromEither(
                  value.outputText
                    .fromJson[SubagentToolResult]
                    .left
                    .map(error =>
                      AgentError.DeserializationError(
                        s"Tool ${value.id} (${value.state}) output ${value.outputText.take(1000)}: $error"
                      )
                    )
                )
                .map(value.id -> _)
            )
            pages <- ZIO.foreach(
              observations.filter(value => value.id == "completed" || value.id == "failed")
            )(value =>
              ZIO
                .fromEither(
                  value.outputText
                    .fromJson[TaskListPage]
                    .left
                    .map(error =>
                      AgentError.DeserializationError(
                        s"Tool ${value.id} (${value.state}) output ${value.outputText.take(1000)}: $error"
                      )
                    )
                )
                .map(value.id -> _)
            )
            foreign <- query(
              unrelated,
              Chunk(
                ContentBlock.ToolUse(
                  "foreign",
                  "agent_task",
                  Json.Obj("task_id" -> Json.Str(success.value.toString))
                )
              )
            )
            foreignResults = foreign.history.flatMap(_.toolResults)
            missing = SubagentToolResult.Task(success.value.toString, SubagentToolState.Missing)
            calls     <- childCalls.get
            liveAfter <- fresh.list
          yield assertTrue(
            receipts.map(_.id).toSet == Set(success, failed),
            exact.contains(
              SubagentTerminalRecord(success, SubagentTerminalOutcome.Completed(text))
            ),
            failedRecord.contains(
              SubagentTerminalRecord(
                failed,
                SubagentTerminalOutcome.Failed(
                  Chunk(failure.message),
                  SubagentTaskDefects.Absent
                )
              )
            ),
            liveBefore.isEmpty,
            liveAfter.isEmpty,
            calls == 2,
            observations.size == 4,
            observations.forall(_.state == ToolResultState.Success),
            taskResults.toMap
              .get("success")
              .exists:
                case SubagentToolResult.Task(id, SubagentToolState.Completed(preview)) =>
                  id == success.value.toString && preview.text == text.take(1997) + "\n...\n" + text
                    .takeRight(1998) &&
                  preview.omittedCharacters == text.length - 3995
                case _ => false
            ,
            taskResults.toMap
              .get("failure")
              .contains(
                SubagentToolResult.Task(
                  failed.value.toString,
                  SubagentToolState.Failed(SubagentToolText(failure.message, 0))
                )
              ),
            pages.toMap
              .get("completed")
              .contains(
                TaskListPage(
                  Chunk(ListedTask(success.value.toString, ListedTaskStatus.Completed)),
                  1,
                  None
                )
              ),
            pages.toMap
              .get("failed")
              .contains(
                TaskListPage(
                  Chunk(ListedTask(failed.value.toString, ListedTaskStatus.Failed)),
                  1,
                  None
                )
              ),
            foreignResults.size == 1,
            foreignResults.forall(result =>
              result.id == "foreign" && result.state == ToolResultState.Error &&
                result.outputText == s"Execution failed for 'agent_task': ${missing.toJson}"
            )
          )
  ) @@ TestAspect.withLiveRandom @@ TestAspect.withLiveSystem @@ TestAspect.withLiveClock @@ TestAspect
    .timeout(30.seconds) @@ TestAspect.ifEnvSet("WEISGNOSIS_POSTGRES_URL")
