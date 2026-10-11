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
  AgentResult,
  LocalToolResultObserver,
  PersistedLocalToolObservation,
  ReActAgent
}
import com.weisgnosis.agents.core.client.{LlmClient, TurnOutput}
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.model.{ContentBlock, Msg, ToolResultState}
import com.weisgnosis.agents.core.tool.{
  Toolkit,
  ToolExecutionResult,
  ToolResultPayload,
  ToolResultProcessor
}
import com.weisgnosis.agents.harness.storage.*
import com.weisgnosis.agents.harness.eviction.{StoredToolResult, ToolResultEvictor, ToolResultStore}
import java.time.Instant
import java.util.UUID
import java.nio.charset.StandardCharsets
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

  private enum StoredCase:
    case Pending, Running, BecomesTerminal, Disappears

  private enum WaitCase:
    case Default, Clamped, Zero, Negative, Poll
  private enum Receipt:
    case Stored, Failed

  private enum Consumption:
    case Exact, MetadataOnly, TimestampChanged, Redacted, Offloaded, PriorFailure, Uncertain,
      CapacityFits, Capacity

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
          (_, _, _) => TestClock.adjust(2.seconds) *> supervisor.attach(finish.await.as(reply)),
          4
        )
        createdAt <- Clock.instant
        taskId    <- accepted(owner.submit(Msg.user("work")))
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
        (output, bounded) = observed
        expected: SubagentTaskOutputResult = SubagentTaskOutputResult.Running(
          taskId.value.toString,
          Some(createdAt)
        )
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
        retained <-
          if mode == WaitCase.Default then
            for
              selection <- ZIO.fromEither(SubagentTaskSelection.selected(Chunk(taskId)))
              captured  <- owner.captureWait(selection)
              removed   <- owner.forgetCompleted(taskId)
              metadata  <- ZIO.foreach(captured)(_.checkedMetadata)
            yield removed == SubagentTaskRemoval.Removed && metadata.forall(
              _.exists(_.createdAt == createdAt)
            )
          else ZIO.succeed(true)
        readyExpected: SubagentTaskOutputResult = SubagentTaskOutputResult.Terminal(
          taskId.value.toString,
          None,
          SubagentTerminalOutcome.Completed("joined output"),
          Some(createdAt)
        )
      yield assertTrue(
        bounded,
        retained,
        output.state == ToolResultState.Success,
        output.output == expected.toJson,
        stillRunning.contains(SubagentTaskSnapshot(taskId, SubagentTaskState.Running)),
        ready.output == readyExpected.toJson
      ) && ownershipChecks

  private def publicationCase(
      receipt: Receipt
  ): ZIO[TestEnvironment & Scope, AgentError, TestResult] =
    ZIO.scoped:
      val admittedAt = Instant.parse("2026-10-08T12:00:00Z")
      for
        _            <- TestClock.setTime(admittedAt)
        backend      <- SubagentTerminalStoreSpec.fixture
        target       <- address("publication")
        writing      <- Promise.make[Nothing, Unit]
        releaseWrite <- Promise.make[Nothing, Unit]
        readEntered  <- Promise.make[Nothing, Unit]
        releaseRead  <- Promise.make[Nothing, Unit]
        heldRead     <- Ref.make(false)
        writes       <- Ref.make(0)
        held = new VersionedJsonStore:
          def read(address: StorageAddress): IO[AgentError, Option[VersionedJson]] =
            backend.store
              .read(address)
              .flatMap: value =>
                heldRead.get
                  .flatMap(enabled =>
                    if enabled && value.exists(_.value.toJson.contains("\"Terminal\"")) then
                      readEntered.succeed(()) *> releaseRead.await
                    else ZIO.unit
                  )
                  .as(value)
          def compareAndSet(
              address: StorageAddress,
              expected: ExpectedRevision,
              value: Json
          ): IO[AgentError, ConditionalWrite] =
            writes
              .updateAndGet(_ + 1)
              .flatMap:
                case 3 =>
                  writing.succeed(()) *> releaseWrite.await *> (receipt match
                    case Receipt.Stored => backend.store.compareAndSet(address, expected, value)
                    case Receipt.Failed =>
                      ZIO.fail(AgentError.StorageError("publication unavailable")))
                case _ => backend.store.compareAndSet(address, expected, value)
        terminal = SubagentTerminalStore.make(held, target)
        supervisor <- TaskSupervisor.open[AgentResult]
        result = AgentResult(Msg.assistant(fullText), Chunk.empty, 1)
        owner <- ZIO.uninterruptibleMask: restore =>
          restore(
            SubagentTaskRegistry.openPreparedInput[Msg](
              identity,
              (_, _, _, beforeStart) => supervisor.attach(beforeStart.as(result)),
              4,
              Some(terminal)
            )
          ).flatMap: acquired =>
            ZIO.addFinalizer(releaseWrite.succeed(()) *> releaseRead.succeed(()).unit).as(acquired)
        createdAt <- Clock.instant
        taskId    <- accepted(owner.submit(Msg.user("work")))
        _         <- writing.await
        joined    <- owner.await(taskId)
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
        _         <- releaseRead.succeed(())
        observed  <- waiting.join
        stored    <- backend.store.read(target)
        published <- terminal.read(taskId)
        pending: SubagentTaskOutputResult = SubagentTaskOutputResult.PublicationPending(
          taskId.value.toString,
          Some(admittedAt)
        )
        complete: SubagentTaskOutputResult = SubagentTaskOutputResult.Terminal(
          taskId.value.toString,
          None,
          SubagentTerminalOutcome.Completed(fullText),
          Some(admittedAt)
        )
      yield assertTrue(
        createdAt == admittedAt,
        joined.contains(Exit.succeed(result)),
        zero.output == pending.toJson,
        nonblocking.output == pending.toJson,
        receipt match
          case Receipt.Stored =>
            observed.state == ToolResultState.Success && observed.output == complete.toJson && removed == SubagentTaskRemoval.Removed && stored.nonEmpty
          case Receipt.Failed =>
            observed.state == ToolResultState.Error && observed.output == "Execution failed for 'task_output': publication unavailable" && stored.nonEmpty && published.isEmpty
      )

  private def consumptionCase(
      mode: Consumption
  ): ZIO[TestEnvironment & Scope, AgentError, TestResult] =
    ZIO.scoped:
      for
        backend <- SubagentTerminalStoreSpec.fixture
        target  <- address("consumption")
        terminal = SubagentTerminalStore.make(backend.store, target)
        acknowledgements <- Ref.make(0)
        tracked = new SubagentTerminalStore:
          def read(id: SubagentTaskId): IO[AgentError, Option[SubagentTerminalRecord]] =
            terminal.read(id)
          def list: IO[AgentError, Chunk[SubagentTerminalRecord]] = terminal.list
          def publish(record: SubagentTerminalRecord): IO[AgentError, SubagentTerminalPublication] =
            terminal.publish(record)
          override def markChecked(
              id: SubagentTaskId
          ): IO[AgentError, Option[SubagentTaskMetadata]] = terminal.markChecked(id)
          override def pending: IO[AgentError, Chunk[SubagentTerminalRecord]] = terminal.pending
          override def acknowledgement(
              id: SubagentTaskId
          ): IO[AgentError, Option[SubagentTerminalAcknowledgement]] = terminal.acknowledgement(id)
          override def acknowledge(
              id: SubagentTaskId
          ): IO[AgentError, SubagentTerminalAcknowledgeResult] =
            acknowledgements.update(_ + 1) *> terminal
              .acknowledge(id)
              .flatMap: receipt =>
                if mode == Consumption.Uncertain then
                  ZIO.fail(AgentError.StorageError("Acknowledgement receipt lost"))
                else ZIO.succeed(receipt)
        owner   <- registry(tracked)
        adapter <- SubagentDeliveryMiddleware.make(owner)
        calls   <- Ref.make(0)
        text = mode match
          case Consumption.CapacityFits => "\n" * 300000
          case Consumption.Capacity     => "\n" * 400000
          case Consumption.Offloaded    => fullText * 5
          case _                        => fullText
        createdAt <- Clock.instant
        record = SubagentTerminalRecord(id, SubagentTerminalOutcome.Completed(text))
        expected: SubagentTaskOutputResult = SubagentTaskOutputResult.Terminal(
          id.value.toString,
          None,
          record.outcome,
          Some(createdAt)
        )
        ordinary         <- Ref.make(Option.empty[String])
        capacityEvidence <- Ref.make(Option.empty[(Int, Int)])
        client = LlmClient.mockEffectful((_, _, _) =>
          calls
            .updateAndGet(_ + 1)
            .flatMap:
              case 1 =>
                for
                  _ <- terminal.admit(id)
                  _ <- terminal.publish(record)
                  raw <- Toolkit
                    .single(SubagentTaskOutputTool.make(owner))
                    .executeSafe("task_output", input(id, "timeout" -> Json.Num(0)))
                  _      <- ordinary.set(Some(raw.output))
                  stored <- backend.store.read(target)
                  receipt = ToolExecutionResult(expected.toJson).payload
                    .applyTo(ContentBlock.ToolResult("output", Some("task_output")))
                  _ <- capacityEvidence.set(
                    stored.map(value =>
                      (
                        value.value.toJson.getBytes(StandardCharsets.UTF_8).length,
                        receipt.toJson.getBytes(StandardCharsets.UTF_8).length
                      )
                    )
                  )
                yield TurnOutput.ToolCalls(
                  Msg.assistant(Chunk(ContentBlock.ToolUse("output", "task_output", input(id))))
                )
              case _ =>
                ZIO.fail(AgentError.ModelError(500, "Unexpected additional reasoning request"))
        )
        observed  <- Ref.make(Chunk.empty[PersistedLocalToolObservation])
        beforeAck <- Ref.make(Chunk.empty[Option[SubagentTerminalAcknowledgement]])
        prior = LocalToolResultObserver(observation =>
          terminal
            .acknowledgement(id)
            .mapError(error => AgentError.CompletionObservationError(error.message))
            .flatMap(stamp =>
              observed.update(_ :+ observation) *> beforeAck.update(_ :+ stamp) *>
                (if mode == Consumption.PriorFailure then
                   ZIO.fail(AgentError.CompletionObservationError("Prior output observer failed"))
                 else ZIO.unit)
            )
        )
        cached <- Ref.make(Map.empty[String, String])
        cache = new ToolResultStore:
          def resolve(key: String): IO[AgentError, Option[StoredToolResult]] =
            cached.get.map(
              _.get(key).map(text => StoredToolResult(text, s"large_tool_results/$key.txt"))
            )
          def persist(key: String, content: String): IO[AgentError, String] =
            cached.update(_.updated(key, content)).as(s"large_tool_results/$key.txt")
        processor = mode match
          case Consumption.Offloaded => new ToolResultEvictor(cache).processor()
          case _ =>
            ToolResultProcessor(result =>
              ZIO.succeed(mode match
                case Consumption.TimestampChanged =>
                  val changed: SubagentTaskOutputResult = SubagentTaskOutputResult.Terminal(
                    id.value.toString,
                    None,
                    record.outcome,
                    Some(createdAt.plusSeconds(1L))
                  )
                  ToolResultPayload(Chunk(ContentBlock.Text(changed.toJson)))
                case Consumption.Redacted => ToolResultPayload(Chunk(ContentBlock.Text("redacted")))
                case Consumption.MetadataOnly =>
                  ToolResultPayload(
                    result.output,
                    result.metadata.updated("diagnostic", Json.Bool(true))
                  )
                case _ => ToolResultPayload.fromResult(result))
            )
        agent <- adapter
          .configure(
            ReActAgent
              .builder()
              .client(client)
              .toolkit(adapter.tools().updated(adapter.taskOutputTool))
              .toolResultProcessor(processor)
              .maxIterations(1)
              .localToolResultObserver(prior)
          )
          .build
        completed   <- agent.run(Msg.user("retrieve and persist output")).exit
        history     <- agent.history
        stamp       <- terminal.acknowledgement(id)
        attempts    <- acknowledgements.get
        seen        <- observed.get
        priorStamps <- beforeAck.get
        raw         <- ordinary.get
        requests    <- calls.get
        measured    <- capacityEvidence.get
        files       <- cached.get
        persisted = history.flatMap(_.toolResults)
        succeeded = Set(Consumption.Exact, Consumption.MetadataOnly, Consumption.CapacityFits)
          .contains(mode)
        uncertain = mode == Consumption.Uncertain
        expectedFailure = mode match
          case Consumption.PriorFailure =>
            Some(AgentError.CompletionObservationError("Prior output observer failed"))
          case Consumption.Uncertain =>
            Some(AgentError.CompletionObservationError("Acknowledgement receipt lost"))
          case _ => None
        failureMatches = (expectedFailure, completed) match
          case (Some(error), Exit.Failure(cause)) =>
            cause.failures == List(error) && cause.defects.isEmpty && !cause.isInterrupted
          case (None, Exit.Success(_)) => true
          case _                       => false
        receiptMatches =
          if succeeded || uncertain then
            stamp.exists:
              case SubagentTerminalAcknowledgement.Acknowledged(_) => true
              case _                                               => false
          else stamp.contains(SubagentTerminalAcknowledgement.Pending)
        rawMatches = raw.contains(expected.toJson)
        originalMatches = mode == Consumption.Capacity || seen.map(_.original.outputText) == Chunk(
          expected.toJson
        )
        persistedMatches = mode match
          case Consumption.Capacity =>
            persisted.exists(result =>
              result.state == ToolResultState.Error && result.outputText.contains(
                "Task consumption capture exceeds its bounded capacity"
              )
            )
          case Consumption.TimestampChanged =>
            val changed: SubagentTaskOutputResult = SubagentTaskOutputResult.Terminal(
              id.value.toString,
              None,
              record.outcome,
              Some(createdAt.plusSeconds(1L))
            )
            persisted.map(_.outputText) == Chunk(changed.toJson)
          case Consumption.Redacted => persisted.map(_.outputText) == Chunk("redacted")
          case Consumption.Offloaded =>
            files.values.toSet == Set(expected.toJson) && persisted.exists(result =>
              result.outputText != expected.toJson && result.metadata.contains("eviction_cache_key")
            )
          case _ => persisted.map(_.outputText) == Chunk(expected.toJson)
        capacityMatches = measured.exists:
          case (storedBytes, receiptBytes) =>
            storedBytes <= 1048576 && (mode match
              case Consumption.Capacity     => receiptBytes > 1048576
              case Consumption.CapacityFits => receiptBytes < 1048576
              case _                        => true)
      yield assertTrue(
        requests == 1,
        capacityMatches,
        rawMatches,
        failureMatches,
        attempts == (if succeeded || uncertain then 1 else 0),
        receiptMatches,
        priorStamps == Chunk(Some(SubagentTerminalAcknowledgement.Pending)),
        persistedMatches,
        originalMatches
      )

  private def metadataAdmissionChecks: ZIO[TestEnvironment & Scope, AgentError, TestResult] =
    ZIO.scoped:
      for
        entered  <- Promise.make[Nothing, Unit]
        hold     <- Ref.make(false)
        cleanups <- Ref.make(0)
        reads    <- Ref.make(0)
        record = SubagentTerminalRecord(id, SubagentTerminalOutcome.Completed("stored"))
        store = new SubagentTerminalStore:
          def list: IO[AgentError, Chunk[SubagentTerminalRecord]] = ZIO.succeed(Chunk(record))
          def read(taskId: SubagentTaskId): IO[AgentError, Option[SubagentTerminalRecord]] =
            reads.update(_ + 1).as(Some(record))
          def publish(value: SubagentTerminalRecord): IO[AgentError, SubagentTerminalPublication] =
            ZIO.fail(AgentError.StorageError("Read-only check fixture"))
          override def markChecked(
              taskId: SubagentTaskId
          ): IO[AgentError, Option[SubagentTaskMetadata]] =
            hold.get.flatMap:
              case false => ZIO.fail(AgentError.StorageError("Check unavailable"))
              case true  => (entered.succeed(()) *> ZIO.never).ensuring(cleanups.update(_ + 1))
        owner <- registry(store)
        tools = Toolkit.single(SubagentTaskOutputTool.make(owner))
        rejected  <- tools.executeSafe("task_output", input(id))
        _         <- hold.set(true)
        waiting   <- tools.executeSafe("task_output", input(id)).forkScoped
        _         <- entered.await
        timers    <- TestClock.sleeps
        cancelled <- waiting.interrupt
        cleaned   <- cleanups.get
        accessed  <- reads.get
      yield assertTrue(
        rejected.state == ToolResultState.Error,
        rejected.output == "Execution failed for 'task_output': Check unavailable",
        timers.isEmpty,
        cancelled.isInterrupted,
        cleaned == 1,
        accessed == 0
      )

  private def storedCase(mode: StoredCase): ZIO[TestEnvironment & Scope, AgentError, TestResult] =
    ZIO.scoped:
      for
        _       <- TestClock.setTime(Instant.parse("2026-10-08T00:00:00Z"))
        backend <- SubagentTerminalStoreSpec.fixture
        target  <- address("stored-live")
        terminal = SubagentTerminalStore.make(backend.store, target)
        worker    <- ZIO.fromEither(SubagentWorkerId.from("stored worker"))
        createdAt <- Clock.instant
        admitted  <- terminal.admit(id, Some(worker))
        identity <- admitted match
          case SubagentTaskAdmissionResult.Created(value) => ZIO.succeed(value)
          case other => ZIO.fail(AgentError.StorageError(s"Expected stored admission: $other"))
        _ <- mode match
          case StoredCase.Running => terminal.markRunning(identity)
          case _                  => ZIO.unit
        entered          <- Promise.make[Nothing, Unit]
        release          <- Promise.make[Nothing, Unit]
        acknowledgements <- Ref.make(0)
        checked = new SubagentTerminalStore:
          def read(id: SubagentTaskId): IO[AgentError, Option[SubagentTerminalRecord]] =
            terminal.read(id)
          def list: IO[AgentError, Chunk[SubagentTerminalRecord]] = terminal.list
          def publish(record: SubagentTerminalRecord): IO[AgentError, SubagentTerminalPublication] =
            terminal.publish(record)
          override def pending: IO[AgentError, Chunk[SubagentTerminalRecord]] = terminal.pending
          override def acknowledge(
              id: SubagentTaskId
          ): IO[AgentError, SubagentTerminalAcknowledgeResult] =
            acknowledgements.update(_ + 1) *> terminal.acknowledge(id)
          override def taskRecordSnapshot: IO[AgentError, Option[Chunk[SubagentTaskRecord]]] =
            terminal.taskRecordSnapshot
          override def markChecked(
              id: SubagentTaskId
          ): IO[AgentError, Option[SubagentTaskMetadata]] =
            terminal
              .markChecked(id)
              .flatMap(metadata =>
                mode match
                  case StoredCase.BecomesTerminal | StoredCase.Disappears =>
                    entered.succeed(()) *> release.await.as(metadata)
                  case _ => ZIO.succeed(metadata)
              )
        executions    <- Ref.make(0)
        registryScope <- ZIO.acquireRelease(Scope.make)(_.close(Exit.unit))
        owner <- registryScope.extend(
          SubagentTaskRegistry.openWith(
            (_, _, _) =>
              executions.update(_ + 1) *> ZIO.fail(
                AgentError.InvalidConfiguration("Stored data cannot execute")
              ),
            8,
            Some(checked)
          )
        )
        _ <- ZIO.addFinalizer(release.succeed(()).unit)
        tools = Toolkit.single(SubagentTaskOutputTool.make(owner))
        timersBefore <- TestClock.sleeps
        observed <- mode match
          case StoredCase.BecomesTerminal | StoredCase.Disappears =>
            for
              waiting <- tools.executeSafe("task_output", input(id)).forkScoped
              _       <- entered.await
              _       <- registryScope.close(Exit.unit)
              _ <- mode match
                case StoredCase.BecomesTerminal =>
                  terminal
                    .publish(
                      SubagentTerminalRecord(
                        id,
                        SubagentTerminalOutcome.Completed(fullText),
                        Some(worker)
                      )
                    )
                    .unit
                case _ => backend.values.update(_ - target)
              _      <- release.succeed(())
              result <- waiting.join
            yield Chunk(result)
          case _ =>
            ZIO.foreach(
              Chunk(
                input(id),
                input(id, "block"   -> Json.Bool(false)),
                input(id, "timeout" -> Json.Num(0))
              )
            )(tools.executeSafe("task_output", _))
        timers       <- TestClock.sleeps
        childEffects <- executions.get
        expected: SubagentTaskOutputResult = mode match
          case StoredCase.BecomesTerminal =>
            SubagentTaskOutputResult.Terminal(
              id.value.toString,
              Some(worker),
              SubagentTerminalOutcome.Completed(fullText),
              Some(createdAt)
            )
          case StoredCase.Running =>
            SubagentTaskOutputResult.NotLocallyWaitable(
              id.value.toString,
              SubagentStoredTaskState.Running,
              Some(worker),
              Some(createdAt)
            )
          case _ =>
            SubagentTaskOutputResult.NotLocallyWaitable(
              id.value.toString,
              SubagentStoredTaskState.Pending,
              Some(worker),
              Some(createdAt)
            )
        bound <- mode match
          case StoredCase.Pending | StoredCase.Running =>
            for
              adapter  <- SubagentDeliveryMiddleware.make(owner)
              requests <- Ref.make(0)
              client = LlmClient.mockEffectful((_, _, _) =>
                requests
                  .update(_ + 1)
                  .as(
                    TurnOutput.ToolCalls(
                      Msg.assistant(
                        Chunk(
                          ContentBlock.ToolUse(
                            "inspect",
                            "agent_task",
                            Json.Obj("task_id" -> Json.Str(id.value.toString))
                          ),
                          ContentBlock.ToolUse("output", "task_output", input(id))
                        )
                      )
                    )
                  )
              )
              agent <- adapter
                .configure(
                  ReActAgent
                    .builder()
                    .client(client)
                    .toolkit(adapter.tools().updated(adapter.taskOutputTool))
                    .maxIterations(1)
                )
                .build
              completed <- agent.run(Msg.user("observe stored data only"))
              count     <- requests.get
              record    <- terminal.taskRecord(id)
              results = completed.history.flatMap(_.toolResults)
            yield count == 1 && results.size == 2 && results.forall(
              _.state == ToolResultState.Success
            ) && results.find(_.id == "output").exists(_.outputText == expected.toJson) && record
              .exists(_.terminal.isEmpty)
          case _ => ZIO.succeed(true)
        ackAttempts <- acknowledgements.get
      yield assertTrue(
        ackAttempts == 0,
        bound,
        timers == timersBefore,
        childEffects == 0,
        mode match
          case StoredCase.Disappears =>
            observed.forall(result =>
              result.state == ToolResultState.Error && result.output == "Execution failed for 'task_output': Captured stored task is no longer visible in its selected store"
            )
          case _ =>
            observed.forall(result =>
              result.state == ToolResultState.Success && result.output == expected.toJson
            )
      )

  def spec: Spec[TestEnvironment & Scope, Any] = suite("SubagentTaskOutputToolSpec")(
    test(
      "stored nonterminal output never fabricates waiters; checked snapshots expose later terminal truth and disappearances"
    ):
      ZIO
        .foreach(
          Chunk(
            StoredCase.Pending,
            StoredCase.Running,
            StoredCase.BecomesTerminal,
            StoredCase.Disappears
          )
        )(storedCase)
        .map(_.foldLeft(assertTrue(true))(_ && _))
    ,
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
              .Interrupted(
                Chunk(SubagentTaskFailure("interrupted child")),
                SubagentTaskDefects.Present
              ),
            SubagentTerminalOutcome
              .Failed(Chunk(SubagentTaskFailure("exact failure λ")), SubagentTaskDefects.Absent)
          )
        ): outcome =>
          ZIO.scoped:
            for
              backend <- SubagentTerminalStoreSpec.fixture
              target  <- address("full")
              worker  <- ZIO.fromEither(SubagentWorkerId.from("known-host-worker"))
              terminal   = SubagentTerminalStore.make(backend.store, target)
              record     = SubagentTerminalRecord(id, outcome, Some(worker))
              admittedAt = Instant.parse("2026-10-08T12:00:00Z")
              _ <- TestClock.setTime(admittedAt)
              createdAt <- outcome match
                case SubagentTerminalOutcome.Completed(_) | SubagentTerminalOutcome.Suspended(_) =>
                  terminal.admit(id, Some(worker)).as(Some(admittedAt))
                case _ => ZIO.none
              _     <- terminal.publish(record)
              _     <- TestClock.adjust(3.seconds)
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
              checked   <- terminal.taskRecord(id)
              expected: SubagentTaskOutputResult = SubagentTaskOutputResult
                .Terminal(id.value.toString, Some(worker), outcome, createdAt)
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
              pending == Chunk(record),
              checked.flatMap(_.metadata) == createdAt
                .map(at => SubagentTaskMetadata(at, at.plusSeconds(3L), at))
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
        .zipWith(metadataAdmissionChecks)(_ && _)
    ,
    test(
      "joined tasks expose publication pending without waiting; captured results survive forgetting and receipt failures remain errors"
    ):
      ZIO
        .foreach(Chunk(Receipt.Stored, Receipt.Failed))(publicationCase)
        .map(_.foldLeft(assertTrue(true))(_ && _))
    ,
    test(
      "explicitly bound full-output consumption acknowledges only unchanged persisted results once within receipt capacity"
    ):
      ZIO
        .foreach(
          Chunk(
            Consumption.Exact,
            Consumption.MetadataOnly,
            Consumption.TimestampChanged,
            Consumption.Redacted,
            Consumption.Offloaded,
            Consumption.PriorFailure,
            Consumption.Uncertain,
            Consumption.CapacityFits,
            Consumption.Capacity
          )
        )(consumptionCase)
        .map(_.foldLeft(assertTrue(true))(_ && _))
  )
