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
import com.weisgnosis.agents.core.model.{ContentBlock, Msg, ToolResultState}
import com.weisgnosis.agents.core.tool.{Toolkit, ToolResultPayload, ToolResultProcessor}
import com.weisgnosis.agents.harness.storage.*
import java.util.UUID
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

object SubagentTaskOutputToolSpec extends ZIOSpecDefault:
  private val id: SubagentTaskId = SubagentTaskId(new UUID(0L, 1L))
  private val fullText: String   = "exact λ😀\n" * 3000

  private def address(owner: String): IO[AgentError, StorageAddress] =
    ZIO.fromEither(StorageAddress.from(Chunk("task-output", owner), "terminal"))

  private def input(taskId: SubagentTaskId, fields: (String, Json)*): Json =
    Json.Obj(Chunk("task_id" -> Json.Str(taskId.value.toString)) ++ Chunk.fromIterable(fields))

  private def registry(store: SubagentTerminalStore): ZIO[Scope, AgentError, SubagentTaskRegistry] =
    SubagentTaskRegistry.openWith(
      (_, _, _) => ZIO.fail(AgentError.InvalidConfiguration("Read-only fixture cannot launch")),
      8,
      Some(store)
    )

  private enum WaitCase:
    case Default, Clamped, Zero, Negative, Poll
  private enum Receipt:
    case Stored, Failed

  private def accepted(
      effect: ZIO[Scope, AgentError, SubagentTaskSubmission]
  ): ZIO[Scope, AgentError, SubagentTaskId] =
    effect.flatMap:
      case SubagentTaskSubmission.Accepted(value) => ZIO.succeed(value)
      case other =>
        ZIO.fail(AgentError.InvalidConfiguration(s"Unexpected fixture admission: $other"))

  private def timerStarted: UIO[Unit] = TestClock.sleeps.repeatUntil(_.nonEmpty).unit

  private def waitCase(mode: WaitCase): ZIO[TestEnvironment & Scope, AgentError, TestResult] =
    ZIO.scoped:
      for
        supervisor <- TaskSupervisor.open[AgentResult]
        finish     <- Promise.make[Nothing, Unit]
        reply = AgentResult(Msg.assistant("joined output"), Chunk.empty, 1)
        owner <- SubagentTaskRegistry.openWith(
          (_, _, _) => supervisor.attach(finish.await.as(reply)),
          4
        )
        taskId <- accepted(owner.submit(Msg.user("work")))
        tools = Toolkit.single(SubagentTaskOutputTool.make(owner))
        request = mode match
          case WaitCase.Default  => input(taskId)
          case WaitCase.Clamped  => input(taskId, "timeout" -> Json.Num(Long.MaxValue))
          case WaitCase.Zero     => input(taskId, "timeout" -> Json.Num(0))
          case WaitCase.Negative => input(taskId, "timeout" -> Json.Num(-1))
          case WaitCase.Poll     => input(taskId, "block" -> Json.Bool(false))
        observed <- mode match
          case WaitCase.Default | WaitCase.Clamped =>
            for
              waiting <- tools.executeSafe("task_output", request).forkScoped
              _       <- timerStarted
              _ <- TestClock.adjust(
                Duration.fromMillis(if mode == WaitCase.Default then 29999L else 599999L)
              )
              early  <- waiting.poll
              _      <- TestClock.adjust(1.millis)
              output <- waiting.join
            yield (output, early.isEmpty)
          case _ => tools.executeSafe("task_output", request).map((_, true))
        (output, bounded)                  = observed
        expected: SubagentTaskOutputResult = SubagentTaskOutputResult.Running(taskId.value.toString)
        stillRunning <- owner.inspect(taskId)
        ownershipChecks <-
          if mode == WaitCase.Default then
            for
              cancelling  <- tools.executeSafe("task_output", input(taskId)).forkScoped
              _           <- timerStarted
              cancelled   <- cancelling.interrupt
              afterCancel <- owner.inspect(taskId)
              missing     <- tools.executeSafe("task_output", input(id))
              invalid <- ZIO.foreach(
                Chunk(
                  Json.Obj("task_id"      -> Json.Str("1-1-1-1-1")),
                  input(taskId, "block"   -> Json.Str("false")),
                  input(taskId, "timeout" -> Json.Num(BigDecimal("0.5"))),
                  input(taskId, "extra"   -> Json.Bool(true))
                )
              )(tools.executeSafe("task_output", _))
            yield assertTrue(
              cancelled.isInterrupted,
              afterCancel == stillRunning,
              missing.state == ToolResultState.Error,
              invalid.forall(_.state == ToolResultState.Error)
            )
          else ZIO.succeed(assertTrue(true))
        _     <- finish.succeed(())
        _     <- owner.await(taskId)
        ready <- tools.executeSafe("task_output", input(taskId, "timeout" -> Json.Num(0)))
        readyExpected: SubagentTaskOutputResult = SubagentTaskOutputResult.Terminal(
          taskId.value.toString,
          None,
          SubagentTerminalOutcome.Completed("joined output")
        )
      yield assertTrue(
        bounded,
        output.state == ToolResultState.Success,
        output.output == expected.toJson,
        stillRunning.contains(SubagentTaskSnapshot(taskId, SubagentTaskState.Running)),
        ready.output == readyExpected.toJson
      ) && ownershipChecks

  private def publicationCase(
      receipt: Receipt
  ): ZIO[TestEnvironment & Scope, AgentError, TestResult] =
    ZIO.scoped:
      for
        backend      <- SubagentTerminalStoreSpec.fixture
        target       <- address("publication")
        writing      <- Promise.make[Nothing, Unit]
        releaseWrite <- Promise.make[Nothing, Unit]
        readEntered  <- Promise.make[Nothing, Unit]
        releaseRead  <- Promise.make[Nothing, Unit]
        heldRead     <- Ref.make(false)
        held = new VersionedJsonStore:
          def read(address: StorageAddress): IO[AgentError, Option[VersionedJson]] =
            heldRead.get.flatMap(enabled =>
              if enabled then readEntered.succeed(()) *> releaseRead.await else ZIO.unit
            ) *> backend.store.read(address)
          def compareAndSet(
              address: StorageAddress,
              expected: ExpectedRevision,
              value: Json
          ): IO[AgentError, ConditionalWrite] =
            writing.succeed(()) *> releaseWrite.await *> (receipt match
              case Receipt.Stored => backend.store.compareAndSet(address, expected, value)
              case Receipt.Failed => ZIO.fail(AgentError.StorageError("publication unavailable")))
        terminal = SubagentTerminalStore.make(held, target)
        supervisor <- TaskSupervisor.open[AgentResult]
        result = AgentResult(Msg.assistant(fullText), Chunk.empty, 1)
        owner <- ZIO.uninterruptibleMask: restore =>
          restore(
            SubagentTaskRegistry.openWith(
              (_, _, _) => supervisor.attach(ZIO.succeed(result)),
              4,
              Some(terminal)
            )
          ).flatMap: acquired =>
            ZIO.addFinalizer(releaseWrite.succeed(()) *> releaseRead.succeed(()).unit).as(acquired)
        taskId <- accepted(owner.submit(Msg.user("work")))
        _      <- writing.await
        joined <- owner.await(taskId)
        tools = Toolkit.single(SubagentTaskOutputTool.make(owner))
        zero        <- tools.executeSafe("task_output", input(taskId, "timeout" -> Json.Num(0)))
        nonblocking <- tools.executeSafe("task_output", input(taskId, "block" -> Json.Bool(false)))
        waiting <- ZIO.uninterruptibleMask: restore =>
          for
            worker <- restore(tools.executeSafe("task_output", input(taskId))).forkScoped
            _      <- ZIO.addFinalizer(releaseWrite.succeed(()) *> releaseRead.succeed(()).unit)
          yield worker
        _ <- timerStarted
        _ <- heldRead.set(receipt == Receipt.Stored)
        _ <- releaseWrite.succeed(())
        removed <- receipt match
          case Receipt.Stored =>
            readEntered.await *> owner.forgetCompleted(taskId)
          case Receipt.Failed => ZIO.succeed(SubagentTaskRemoval.Unpublished)
        _        <- releaseRead.succeed(())
        observed <- waiting.join
        stored   <- backend.store.read(target)
        pending: SubagentTaskOutputResult = SubagentTaskOutputResult.PublicationPending(
          taskId.value.toString
        )
        complete: SubagentTaskOutputResult = SubagentTaskOutputResult.Terminal(
          taskId.value.toString,
          None,
          SubagentTerminalOutcome.Completed(fullText)
        )
      yield assertTrue(
        joined.contains(Exit.succeed(result)),
        zero.output == pending.toJson,
        nonblocking.output == pending.toJson,
        receipt match
          case Receipt.Stored =>
            observed.state == ToolResultState.Success && observed.output == complete.toJson && removed == SubagentTaskRemoval.Removed && stored.nonEmpty
          case Receipt.Failed =>
            observed.state == ToolResultState.Error && observed.output == "Execution failed for 'task_output': publication unavailable" && stored.isEmpty
      )

  def spec: Spec[TestEnvironment & Scope, Any] = suite("SubagentTaskOutputToolSpec")(
    test(
      "full terminal outcomes reach actual ReAct processing with successful retrieval states; retrieval does not acknowledge"
    ):
      ZIO
        .foreach(
          Chunk(
            SubagentTerminalOutcome.Completed(fullText),
            SubagentTerminalOutcome.Suspended(fullText),
            SubagentTerminalOutcome.Stopped("host stopped"),
            SubagentTerminalOutcome
              .Interrupted(Chunk("interrupted child"), SubagentTaskDefects.Present),
            SubagentTerminalOutcome.Failed(Chunk("exact failure λ"), SubagentTaskDefects.Absent)
          )
        ): outcome =>
          ZIO.scoped:
            for
              backend <- SubagentTerminalStoreSpec.fixture
              target  <- address("full")
              worker  <- ZIO.fromEither(SubagentWorkerId.from("known-host-worker"))
              terminal = SubagentTerminalStore.make(backend.store, target)
              record   = SubagentTerminalRecord(id, outcome, Some(worker))
              _     <- terminal.publish(record)
              owner <- registry(terminal)
              tools = Toolkit.single(SubagentTaskOutputTool.make(owner))
              raw  <- tools.executeSafe("task_output", input(id, "timeout" -> Json.Num(0)))
              seen <- Ref.make(Chunk.empty[ContentBlock.ToolResult])
              client = LlmClient.mockEffectful((history, _, _) =>
                ZIO.succeed(
                  if history.flatMap(_.toolResults).isEmpty then
                    TurnOutput.ToolCalls(
                      Msg.assistant(Chunk(ContentBlock.ToolUse("output", "task_output", input(id))))
                    )
                  else TurnOutput.FinalText(Msg.assistant("done"))
                )
              )
              agent <- ReActAgent
                .builder()
                .client(client)
                .toolkit(tools)
                .toolResultProcessor(
                  ToolResultProcessor(result =>
                    seen
                      .update(_ :+ result)
                      .as(ToolResultPayload(Chunk(ContentBlock.Text("processed full observation"))))
                  )
                )
                .build
              completed <- agent.run(Msg.user("retrieve full output"))
              observed  <- seen.get
              pending   <- terminal.pending
              expected: SubagentTaskOutputResult = SubagentTaskOutputResult
                .Terminal(id.value.toString, Some(worker), outcome)
              state = ToolResultState.Success
            yield assertTrue(
              raw.output == expected.toJson,
              raw.state == state,
              observed.map(_.outputText) == Chunk(expected.toJson),
              observed.map(_.state) == Chunk(state),
              completed.history.flatMap(_.toolResults).map(_.outputText) == Chunk(
                "processed full observation"
              ),
              completed.history.flatMap(_.toolResults).map(_.state) == Chunk(state),
              pending == Chunk(record)
            )
        .map(_.foldLeft(assertTrue(true))(_ && _))
    ,
    test(
      "millisecond defaults, clamps and zero checks preserve task ownership and reject unknown or malformed requests"
    ):
      ZIO
        .foreach(
          Chunk(WaitCase.Default, WaitCase.Clamped, WaitCase.Zero, WaitCase.Negative, WaitCase.Poll)
        )(waitCase)
        .map(_.foldLeft(assertTrue(true))(_ && _))
    ,
    test(
      "joined tasks expose publication pending without waiting; captured results survive forgetting and receipt failures remain errors"
    ):
      ZIO
        .foreach(Chunk(Receipt.Stored, Receipt.Failed))(publicationCase)
        .map(_.foldLeft(assertTrue(true))(_ && _))
  )
