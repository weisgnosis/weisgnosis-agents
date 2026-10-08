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

import com.weisgnosis.agents.core.agent.{
  AgentConfig,
  AgentResult,
  AgentRunState,
  PendingActing,
  ReActAgent
}
import com.weisgnosis.agents.core.client.{LlmClient, LlmConfig, TurnOutput}
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.event.ModelStreamContext
import com.weisgnosis.agents.core.model.{ContentBlock, Msg, ToolResultState}
import com.weisgnosis.agents.core.tool.Toolkit
import com.weisgnosis.agents.harness.storage.StorageAddress
import java.util.UUID
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

object SubagentTaskListToolSpec extends ZIOSpecDefault:
  private def accepted(
      registry: SubagentTaskRegistry,
      name: String
  ): ZIO[Scope, AgentError, SubagentTaskId] =
    registry
      .submit(Msg.user(name))
      .flatMap:
        case SubagentTaskSubmission.Accepted(id) => ZIO.succeed(id)
        case other => ZIO.fail(AgentError.InvalidConfiguration(s"Unexpected admission: $other"))

  private def page(toolkit: Toolkit, input: Json = Json.Obj()): IO[AgentError, TaskListPage] =
    toolkit
      .executeSafe("task_list", input)
      .flatMap: result =>
        if result.state == ToolResultState.Success then
          ZIO
            .fromEither(result.output.fromJson[TaskListPage])
            .mapError(AgentError.InvalidMessage(_))
        else ZIO.fail(AgentError.InvalidMessage(result.output))

  def spec: Spec[TestEnvironment & Scope, Any] = suite("SubagentTaskListToolSpec")(
    test(
      "persisted pending, running and terminal records remain visible through public task listing"
    ):
      for
        backend <- SubagentTerminalStoreSpec.fixture
        target  <- ZIO.fromEither(StorageAddress.from(Chunk("stored-views"), "records"))
        store      = SubagentTerminalStore.make(backend.store, target)
        pendingId  = SubagentTaskId(new UUID(0L, 81L))
        runningId  = SubagentTaskId(new UUID(0L, 82L))
        terminalId = SubagentTaskId(new UUID(0L, 83L))
        _        <- store.admit(pendingId)
        admitted <- store.admit(runningId)
        identity <- admitted match
          case SubagentTaskAdmissionResult.Created(value) => ZIO.succeed(value)
          case other =>
            ZIO.fail(AgentError.StorageError(s"Expected fresh running admission: $other"))
        _ <- store.markRunning(identity)
        _ <- store.publish(
          SubagentTerminalRecord(terminalId, SubagentTerminalOutcome.Completed("exact terminal"))
        )
        registry <- SubagentTaskRegistry.openWith(
          (_, _, _) => ZIO.fail(AgentError.InvalidConfiguration("Stored views cannot launch work")),
          8,
          Some(store)
        )
        observed <- registry.observations
        listed   <- page(Toolkit.single(SubagentTaskListTool.make(registry)))
        pending <- page(
          Toolkit.single(SubagentTaskListTool.make(registry)),
          Json.Obj("status_filter" -> Json.Str("pending"))
        )
        running <- page(
          Toolkit.single(SubagentTaskListTool.make(registry)),
          Json.Obj("status_filter" -> Json.Str("running"))
        )
        controls = SubagentTools.make(registry)
        inspected <- ZIO.foreach(Chunk(pendingId, runningId))(id =>
          controls.executeSafe("agent_task", Json.Obj("task_id" -> Json.Str(id.value.toString)))
        )
        expectedInspection: Chunk[SubagentToolResult] = Chunk(
          SubagentToolResult.Task(
            pendingId.value.toString,
            SubagentToolState.Stored(SubagentStoredTaskState.Pending)
          ),
          SubagentToolResult.Task(
            runningId.value.toString,
            SubagentToolState.Stored(SubagentStoredTaskState.Running)
          )
        )
        work          <- registry.waitWorkState
        selected      <- ZIO.fromEither(SubagentTaskSelection.selected(Chunk(pendingId, runningId)))
        rejected      <- SubagentTaskBarrier.await(registry, selected).either
        current       <- SubagentTaskBarrier.await(registry, SubagentTaskSelection.CurrentRunning)
        fallbackReads <- Ref.make(0)
        unsupported = new SubagentTerminalStore:
          def read(id: SubagentTaskId): IO[AgentError, Option[SubagentTerminalRecord]] =
            ZIO.fail(AgentError.StorageError("Terminal read unavailable"))
          def list: IO[AgentError, Chunk[SubagentTerminalRecord]] =
            fallbackReads.update(_ + 1) *> store.list
          def publish(record: SubagentTerminalRecord): IO[AgentError, SubagentTerminalPublication] =
            store.publish(record)
        legacy <- SubagentTaskRegistry.openWith(
          (_, _, _) => ZIO.fail(AgentError.InvalidConfiguration("No child")),
          8,
          Some(unsupported)
        )
        legacyObserved <- legacy.observations
        legacyOne      <- legacy.observe(terminalId).either
        legacyTool <- SubagentTools
          .make(legacy)
          .executeSafe("agent_task", Json.Obj("task_id" -> Json.Str(terminalId.value.toString)))
        supportedFailure = new SubagentTerminalStore:
          def read(id: SubagentTaskId): IO[AgentError, Option[SubagentTerminalRecord]] =
            store.read(id)
          def list: IO[AgentError, Chunk[SubagentTerminalRecord]] =
            fallbackReads.update(_ + 1) *> store.list
          def publish(record: SubagentTerminalRecord): IO[AgentError, SubagentTerminalPublication] =
            store.publish(record)
          override def taskRecordSnapshot: IO[AgentError, Option[Chunk[SubagentTaskRecord]]] =
            ZIO.fail(AgentError.StorageError("Snapshot unavailable"))
        failed <- SubagentTaskRegistry.openWith(
          (_, _, _) => ZIO.fail(AgentError.InvalidConfiguration("No child")),
          8,
          Some(supportedFailure)
        )
        before      <- fallbackReads.get
        unavailable <- failed.observations.either
        after       <- fallbackReads.get
      yield assertTrue(
        observed.map(_.id) == Chunk(pendingId, runningId, terminalId),
        listed.tasks.map(_.taskId) == Chunk(pendingId, runningId, terminalId).map(_.value.toString),
        listed.totalMatching == 3,
        listed.tasks.map(_.status) == Chunk(
          ListedTaskStatus.Pending,
          ListedTaskStatus.Running,
          ListedTaskStatus.Completed
        ),
        pending.tasks == Chunk(ListedTask(pendingId.value.toString, ListedTaskStatus.Pending)),
        running.tasks == Chunk(ListedTask(runningId.value.toString, ListedTaskStatus.Running)),
        inspected.map(_.output) == expectedInspection.map(_.toJson),
        inspected.forall(_.state == ToolResultState.Success),
        work == SubagentWaitWorkState.StoredUnresolved,
        rejected == Left(
          AgentError.InvalidConfiguration(
            s"Selected stored tasks are not locally waitable: ${pendingId.value}, ${runningId.value}"
          )
        ),
        current == SubagentTaskBarrierResult.Empty,
        legacyObserved.map(_.id) == Chunk(terminalId),
        legacyOne == Left(AgentError.StorageError("Terminal read unavailable")),
        legacyTool.state == ToolResultState.Error,
        legacyTool.output == "Execution failed for 'agent_task': Terminal read unavailable",
        unavailable == Left(AgentError.StorageError("Snapshot unavailable")),
        before == after
      )
    ,
    test("bounded ordered pages expose truthful terminal states without results or task mutation"):
      for
        supervisor <- TaskSupervisor.open[AgentResult]
        executions <- Ref.make(0)
        started    <- Queue.unbounded[Unit]
        pending = PendingActing(
          "approval",
          ModelStreamContext("reply", "request"),
          0,
          Chunk.empty,
          None,
          None
        )
        states = Chunk(
          "completed"   -> AgentRunState.Completed,
          "interrupted" -> AgentRunState.Interrupted,
          "stopped"     -> AgentRunState.Stopped("private reason"),
          "suspended"   -> AgentRunState.Suspended(pending)
        )
        registry <- SubagentTaskRegistry.openWith(
          (message, _, _) =>
            supervisor
              .attach(
                executions.update(_ + 1) *> started.offer(()) *> (message.text match
                  case "running" | "cancelled" => ZIO.never
                  case "failed" => ZIO.fail(AgentError.InvalidMessage("private failure"))
                  case other =>
                    ZIO
                      .fromOption(states.find(_._1 == other).map(_._2))
                      .orElseFail(AgentError.InvalidMessage(s"Unknown fixture: $other"))
                      .map(state =>
                        AgentResult(Msg.assistant("private result"), Chunk.empty, 1, state)
                      ))
              )
              .tap(_ => started.take),
          8
        )
        running <- accepted(registry, "running")
        terminal <- ZIO.foreach(states.map(_._1) :+ "failed"): name =>
          accepted(registry, name).tap(registry.await(_))
        cancelled <- accepted(registry, "cancelled")
        _         <- registry.cancel(cancelled)
        before    <- registry.list
        limits    <- ZIO.fromEither(TaskListLimits.make(2))
        toolkit = Toolkit.single(SubagentTaskListTool.make(registry, limits))
        pages <- ZIO.foreach(Chunk(0, 2, 4, 6))(offset =>
          page(toolkit, Json.Obj("offset" -> Json.Num(offset)))
        )
        filtered <- page(toolkit, Json.Obj("status_filter" -> Json.Str("cancelled")))
        all      <- page(toolkit, Json.Obj("status_filter" -> Json.Str("all")))
        empty    <- page(toolkit, Json.Obj("offset" -> Json.Num(Int.MaxValue)))
        after    <- registry.list
        count    <- executions.get
        expectedIds = (Chunk(running) ++ terminal :+ cancelled).map(_.value.toString)
      yield assertTrue(
        pages.flatMap(_.tasks).map(_.taskId) == expectedIds,
        pages.flatMap(_.tasks).map(_.status) == Chunk(
          ListedTaskStatus.Running,
          ListedTaskStatus.Completed,
          ListedTaskStatus.Cancelled,
          ListedTaskStatus.Stopped,
          ListedTaskStatus.Suspended,
          ListedTaskStatus.Failed,
          ListedTaskStatus.Cancelled
        ),
        pages.map(_.nextOffset) == Chunk(Some(2), Some(4), Some(6), None),
        pages.forall(_.totalMatching == 7),
        filtered.tasks.map(_.taskId) == (terminal
          .drop(1)
          .take(1)
          .map(_.value.toString) :+ cancelled.value.toString),
        all == TaskListPage(
          expectedIds
            .take(2)
            .zip(Chunk(ListedTaskStatus.Running, ListedTaskStatus.Completed))
            .map((id, status) => ListedTask(id, status)),
          7,
          Some(2)
        ),
        empty == TaskListPage(Chunk.empty, 7, None),
        before == after,
        count == 7,
        pages.forall(value => !value.toJson.contains("private"))
      )
    ,
    test(
      "checked limits and strict input reject invalid requests while empty and valid filters succeed"
    ):
      for
        supervisor <- TaskSupervisor.open[AgentResult]
        registry   <- SubagentTaskRegistry.openWith((_, _, _) => supervisor.attach(ZIO.never), 1)
        toolkit = Toolkit.single(SubagentTaskListTool.make(registry))
        invalid = Chunk(
          Json.Obj("status_filter" -> Json.Str("unknown")),
          Json.Obj("offset"        -> Json.Num(-1)),
          Json.Obj("offset"        -> Json.Num(BigDecimal("0.5"))),
          Json.Obj("session"       -> Json.Str("another-owner")),
          Json.Obj("offset"        -> Json.Num(0), "offset" -> Json.Num(1))
        )
        rejected <- ZIO.foreach(invalid)(toolkit.executeSafe("task_list", _))
        empty    <- page(toolkit)
        filters <- ZIO.foreach(Chunk.fromIterable(ListedTaskStatus.values))(status =>
          page(toolkit, Json.Obj("status_filter" -> Json.Str(status.wireName)))
        )
      yield assertTrue(
        rejected.forall(_.state == ToolResultState.Error),
        empty == TaskListPage(Chunk.empty, 0, None),
        filters.forall(_ == empty),
        Chunk(0, -1, 257, Int.MaxValue).forall(TaskListLimits.make(_).isLeft),
        Chunk(1, 64, 256).forall(TaskListLimits.make(_).isRight)
      )
    ,
    test("a new parent conversation discovers retained tasks only from its host-selected registry"):
      for
        supervisor <- TaskSupervisor.open[AgentResult]
        executions <- Ref.make(0)
        open = SubagentTaskRegistry.openWith(
          (_, _, _) =>
            supervisor.attach(
              executions
                .update(_ + 1)
                .as(AgentResult(Msg.assistant("private child"), Chunk.empty, 1))
            ),
          2
        )
        selected  <- open
        unrelated <- open
        retained  <- accepted(selected, "earlier conversation")
        _         <- selected.await(retained)
        hidden    <- accepted(unrelated, "other owner")
        _         <- unrelated.await(hidden)
        client <- LlmClient.mock(
          Chunk(
            TurnOutput.ToolCalls(
              Msg.assistant(Chunk(ContentBlock.ToolUse("list", "task_list", Json.Obj())))
            ),
            TurnOutput.FinalText(Msg.assistant("listed"))
          )
        )
        parent <- ReActAgent.make(
          AgentConfig(),
          Toolkit.single(SubagentTaskListTool.make(selected)),
          client,
          LlmConfig("scripted")
        )
        result <- parent.run(Msg.user("Find my earlier tasks"))
        observations = result.history.flatMap(_.toolResults)
        decoded <- ZIO.foreach(observations)(observation =>
          ZIO
            .fromEither(observation.outputText.fromJson[TaskListPage])
            .mapError(AgentError.InvalidMessage(_))
        )
        count      <- executions.get
        deliveries <- selected.pendingDeliveries
      yield assertTrue(
        result.state == AgentRunState.Completed,
        observations.size == 1,
        observations.forall(_.state == ToolResultState.Success),
        decoded.flatMap(_.tasks).map(_.taskId) == Chunk(retained.value.toString),
        !decoded.toJson.contains(hidden.value.toString),
        count == 2,
        deliveries.map(_.id) == Chunk(retained),
        result.history.indexWhere(_.toolUses.exists(_.id == "list")) < result.history.indexWhere(
          _.toolResults.nonEmpty
        )
      )
  )
