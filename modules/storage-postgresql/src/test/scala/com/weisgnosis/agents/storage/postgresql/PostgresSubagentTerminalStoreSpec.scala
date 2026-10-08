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

import com.weisgnosis.agents.core.agent.{
  AgentConfig,
  AgentResult,
  LocalToolResultObserver,
  PersistedLocalToolObservation,
  ReActAgent
}
import com.weisgnosis.agents.core.client.{LlmClient, LlmConfig, TurnOutput}
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.model.{ContentBlock, Msg, ToolResultState}
import com.weisgnosis.agents.core.tool.{ToolAuthorizer, Toolkit}
import com.weisgnosis.agents.harness.subagent.*
import java.time.Instant
import java.util.UUID
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

object PostgresSubagentTerminalStoreSpec extends ZIOSpecDefault:
  import PostgresFixture.*

  private enum ConsumptionTool:
    case Inspection, Wait, Catalog, TaskOutput

  private val firstId: SubagentTaskId  = SubagentTaskId(new UUID(0L, 1L))
  private val secondId: SubagentTaskId = SubagentTaskId(new UUID(0L, 2L))

  private def observedClock(at: Instant): UIO[Clock] = ZIO.clock.map: underlying =>
    new Clock:
      export underlying.{
        currentTime,
        currentDateTime,
        javaClock,
        localDateTime,
        nanoTime,
        scheduler,
        sleep
      }
      def instant(using Trace): UIO[Instant] = ZIO.succeed(at)

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
            workerId     <- ZIO.fromEither(SubagentWorkerId.from("worker-λ"))
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
                fiber <- terminal
                  .publishJoined(firstId, task, Some(workerId))
                  .interruptible
                  .forkScoped
                _ <- ZIO.addFinalizer(release.succeed(()).unit)
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
            third = SubagentTerminalRecord(
              SubagentTaskId(new UUID(0L, 3L)),
              SubagentTerminalOutcome.Completed("concurrent publication")
            )
            acknowledgements <- ZIO
              .foreachPar(Chunk(terminal, reopened))(_.acknowledge(firstId))
              .zipPar(terminal.publish(third))
              .map(_._1)
            stamp            <- reopened.acknowledgement(firstId)
            undelivered      <- reopened.pending
            exactAfterAck    <- reopened.read(firstId)
            isolatedStamp    <- SubagentTerminalStore.make(second, other).acknowledgement(firstId)
            admissionAddress <- address(name, "admission-owner")
            admitting = SubagentTerminalStore.make(backend, admissionAddress)
            observing = SubagentTerminalStore.make(second, admissionAddress)
            admitted <- admitting.admit(firstId, Some(workerId))
            identity <- admitted match
              case SubagentTaskAdmissionResult.Created(value) => ZIO.succeed(value)
              case SubagentTaskAdmissionResult.Existing(_) =>
                ZIO.fail(AgentError.StorageError("Fresh fixture admission already exists"))
            admittedRecord      <- observing.taskRecord(firstId)
            terminalBeforeStart <- observing.read(firstId)
            _                   <- admitting.markRunning(identity)
            runningRecord       <- observing.taskRecord(firstId)
            checkedAt = identity.createdAt.plusSeconds(10L)
            checkClock      <- observedClock(checkedAt)
            checkedMetadata <- observing.markChecked(firstId).withClock(checkClock)
            reopenedCheck   <- admitting.taskRecord(firstId)
            _ <- admitting.publish(
              SubagentTerminalRecord(
                firstId,
                SubagentTerminalOutcome.Completed("admitted output"),
                Some(workerId)
              )
            )
            terminalRecord     <- observing.taskRecord(firstId)
            _                  <- admitting.acknowledge(firstId)
            acknowledgedRecord <- observing.taskRecord(firstId)
            duplicateAdmission <- observing.admit(firstId, Some(workerId))
          yield assertTrue(
            before.isEmpty,
            pending.isEmpty,
            receipt match
              case SubagentTerminalPublication.Stored(_)        => true
              case SubagentTerminalPublication.AlreadyStored(_) => false
            ,
            exact.contains(
              SubagentTerminalRecord(
                firstId,
                SubagentTerminalOutcome.Completed(reply.text),
                Some(workerId)
              )
            ),
            recovered == Chunk(
              SubagentTerminalRecord(
                firstId,
                SubagentTerminalOutcome.Completed(reply.text),
                Some(workerId)
              ),
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
            isolated.isEmpty,
            acknowledgements.count(receipt =>
              receipt match
                case SubagentTerminalAcknowledgeResult.Acknowledged(_) => true
                case _                                                 => false
            ) == 1,
            acknowledgements.count(receipt =>
              receipt match
                case SubagentTerminalAcknowledgeResult.AlreadyAcknowledged(_) => true
                case _                                                        => false
            ) == 1,
            acknowledgements.forall(receipt =>
              val at = receipt match
                case SubagentTerminalAcknowledgeResult.Acknowledged(value)        => Some(value)
                case SubagentTerminalAcknowledgeResult.AlreadyAcknowledged(value) => Some(value)
                case SubagentTerminalAcknowledgeResult.Missing                    => None
              at.exists(value =>
                stamp.contains(SubagentTerminalAcknowledgement.Acknowledged(value))
              )
            ),
            undelivered.map(_.id).toSet == Set(secondId, third.id),
            exactAfterAck == exact,
            isolatedStamp.isEmpty,
            terminalBeforeStart.isEmpty,
            admittedRecord.exists(record =>
              record.state == SubagentTaskRecordState.Pending &&
                record.workerId.contains(workerId) && record.metadata.exists(
                  _.createdAt == identity.createdAt
                )
            ),
            runningRecord.exists(record =>
              record.state == SubagentTaskRecordState.Running &&
                record.metadata.exists(_.createdAt == identity.createdAt)
            ),
            checkedMetadata == reopenedCheck.flatMap(_.metadata),
            reopenedCheck.exists(record =>
              runningRecord.exists(previous =>
                record == previous.copy(metadata =
                  previous.metadata.map(_.copy(lastCheckedAt = checkedAt))
                )
              )
            ),
            terminalRecord.exists(record =>
              record.terminal.contains(
                SubagentTerminalRecord(
                  firstId,
                  SubagentTerminalOutcome.Completed("admitted output"),
                  Some(workerId)
                )
              ) &&
                record.metadata.exists(metadata =>
                  metadata.createdAt == identity.createdAt &&
                    metadata.lastCheckedAt == checkedAt
                )
            ),
            acknowledgedRecord.flatMap(_.metadata) == terminalRecord.flatMap(_.metadata),
            acknowledgedRecord.exists(_.state match
              case SubagentTaskRecordState
                    .Terminal(_, SubagentTerminalAcknowledgement.Acknowledged(_)) =>
                true
              case _ => false),
            duplicateAdmission match
              case SubagentTaskAdmissionResult.Existing(record) =>
                acknowledgedRecord.contains(record)
              case SubagentTaskAdmissionResult.Created(_) => false
          )
    ,
    test(
      "automatic durable publication survives owner closure and existing ReAct task tools restore exact bounded observations from PostgreSQL"
    ):
      fixture: (_, backend, name) =>
        ZIO.scoped:
          for
            workerId <- ZIO.fromEither(SubagentWorkerId.from("worker<&\"' λ"))
            target   <- address(name, "registry-owner")
            other    <- address(name, "different-owner")
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
                original <- SubagentTaskRegistry.openDurable(
                  parent,
                  factory,
                  terminal,
                  workerId = Some(workerId)
                )
                success  <- submitted(original, "succeed")
                failed   <- submitted(original, "fail")
                receipts <- original.flushPublications
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
            calls                 <- childCalls.get
            liveAfter             <- fresh.list
            delivered             <- Ref.make(Chunk.empty[SubagentTerminalRecord])
            pendingDuringDelivery <- Ref.make(Chunk.empty[SubagentTerminalRecord])
            deliveryReceipts <- SubagentTerminalDelivery.deliverPending(fresh): batch =>
              reopened.pending.flatMap(pending =>
                delivered.set(batch) *> pendingDuringDelivery.set(pending)
              )
            deliveredRecords     <- delivered.get
            unacknowledgedDuring <- pendingDuringDelivery.get
            pendingAfterDelivery <- terminal.pending
            storedAfterDelivery  <- terminal.list
            reminderId           <- Random.nextUUID.map(SubagentTaskId(_))
            reminderRecord = SubagentTerminalRecord(
              reminderId,
              SubagentTerminalOutcome.Completed("Reopened reminder result"),
              Some(workerId)
            )
            _                <- terminal.publish(reminderRecord)
            requestHistories <- Ref.make(Chunk.empty[Chunk[Msg]])
            pendingAtRequest <- Ref.make(Chunk.empty[Chunk[SubagentTerminalRecord]])
            reminderClient = LlmClient.mockEffectful((history, _, _) =>
              terminal.pending
                .flatMap(pending =>
                  requestHistories.update(_ :+ history) *> pendingAtRequest.update(_ :+ pending)
                )
                .as(TurnOutput.FinalText(Msg.assistant("Reminder accepted")))
            )
            middleware    <- SubagentDeliveryMiddleware.make(fresh)
            reminderAgent <- middleware.configure(ReActAgent.builder().client(reminderClient)).build
            firstReminder <- reminderAgent.run(Msg.user("Consume pending task output"))
            firstStamp    <- terminal.acknowledgement(reminderId)
            secondReminder       <- reminderAgent.run(Msg.user("Continue without redelivery"))
            secondStamp          <- terminal.acknowledgement(reminderId)
            reminderInputs       <- requestHistories.get
            pendingInputs        <- pendingAtRequest.get
            pendingAfterReminder <- terminal.pending
            exactAfterReminder   <- terminal.read(reminderId)
            firstMessages  = firstReminder.history.filter(_.reminderKind.contains("task_delivery"))
            secondMessages = secondReminder.history.filter(_.reminderKind.contains("task_delivery"))
            consumptionChecks <- ZIO.foreach(
              Chunk(
                ConsumptionTool.Inspection,
                ConsumptionTool.Wait,
                ConsumptionTool.Catalog,
                ConsumptionTool.TaskOutput
              )
            ): mode =>
              for
                consumptionTarget <- mode match
                  case ConsumptionTool.Catalog => address(name, "routed-consumption")
                  case _                       => ZIO.succeed(target)
                consumptionStore    = SubagentTerminalStore.make(backend, consumptionTarget)
                consumptionReopened = SubagentTerminalStore.make(second, consumptionTarget)
                routedCatalog <- mode match
                  case ConsumptionTool.Catalog =>
                    SubagentCatalog
                      .open(
                        parent,
                        Chunk(
                          SubagentEntry(
                            "selected",
                            "Explicit routed task partition",
                            factory,
                            Some(SubagentTerminalBinding(consumptionStore, Some(workerId)))
                          )
                        )
                      )
                      .map(Some(_))
                  case _ => ZIO.none
                consumptionId <- Random.nextUUID.map(SubagentTaskId(_))
                failureId     <- Random.nextUUID.map(SubagentTaskId(_))
                consumptionCreatedAt = Instant.parse("2026-10-08T00:00:00Z")
                consumptionCheckedAt = consumptionCreatedAt.plusSeconds(60L)
                creationClock         <- observedClock(consumptionCreatedAt)
                consumptionCheckClock <- observedClock(consumptionCheckedAt)
                consumptionRecord = SubagentTerminalRecord(
                  consumptionId,
                  SubagentTerminalOutcome.Completed(
                    if mode == ConsumptionTool.TaskOutput then "Full task output λ😀\n" * 1000
                    else "Fresh consumption result"
                  )
                )
                failedConsumption = SubagentTerminalRecord(
                  failureId,
                  SubagentTerminalOutcome.Failed(
                    Chunk("Selected failed output"),
                    SubagentTaskDefects.Absent
                  )
                )
                selectedRecords = mode match
                  case ConsumptionTool.Inspection | ConsumptionTool.Catalog |
                      ConsumptionTool.TaskOutput =>
                    Chunk(consumptionRecord)
                  case ConsumptionTool.Wait => Chunk(failedConsumption, consumptionRecord)
                consumptionCall = ContentBlock.ToolUse(
                  "consumed-consumption",
                  mode match
                    case ConsumptionTool.Inspection | ConsumptionTool.Catalog => "agent_task"
                    case ConsumptionTool.Wait       => "wait_async_results"
                    case ConsumptionTool.TaskOutput => "task_output",
                  mode match
                    case ConsumptionTool.Inspection | ConsumptionTool.TaskOutput =>
                      Json.Obj("task_id" -> Json.Str(consumptionId.value.toString))
                    case ConsumptionTool.Catalog =>
                      Json.Obj(
                        "agent_id" -> Json.Str("selected"),
                        "task_id"  -> Json.Str(consumptionId.value.toString)
                      )
                    case ConsumptionTool.Wait =>
                      Json.Obj(
                        "task_ids" -> Json.Str(
                          selectedRecords.map(_.id.value.toString).mkString(",")
                        )
                      )
                )
                consumptionExpected: SubagentToolResult = SubagentToolResult.Task(
                  consumptionId.value.toString,
                  SubagentToolState.Completed(SubagentToolText("Fresh consumption result", 0))
                )
                expectedJson = mode match
                  case ConsumptionTool.Inspection => consumptionExpected.toJson
                  case ConsumptionTool.TaskOutput =>
                    val output: SubagentTaskOutputResult = SubagentTaskOutputResult.Terminal(
                      consumptionId.value.toString,
                      consumptionRecord.workerId,
                      consumptionRecord.outcome,
                      Some(consumptionCreatedAt)
                    )
                    output.toJson
                  case ConsumptionTool.Catalog =>
                    val wrapped: SubagentCatalogToolResult =
                      SubagentCatalogToolResult.Agent("selected", consumptionExpected)
                    wrapped.toJson
                  case ConsumptionTool.Wait =>
                    val result: SubagentWaitToolResult = SubagentWaitToolResult.Completed(
                      Chunk(
                        SubagentToolResult.Task(
                          failureId.value.toString,
                          SubagentToolState.Failed(SubagentToolText("Selected failed output", 0))
                        ),
                        consumptionExpected
                      )
                    )
                    result.toJson
                consumptionRequests  <- Ref.make(0)
                consumptionInputs    <- Ref.make(Chunk.empty[Chunk[Msg]])
                consumptionSnapshots <- Ref.make(Chunk.empty[PersistedLocalToolObservation])
                consumptionBeforeStamp <- Ref.make(
                  Chunk.empty[Option[SubagentTerminalAcknowledgement]]
                )
                consumptionClient = LlmClient.mockEffectful((history, _, _) =>
                  consumptionInputs.update(_ :+ history) *> consumptionRequests
                    .getAndUpdate(_ + 1)
                    .flatMap:
                      case 0 =>
                        (if mode == ConsumptionTool.TaskOutput then
                           consumptionStore.admit(consumptionId).withClock(creationClock).unit
                         else ZIO.unit) *> ZIO
                          .foreachDiscard(selectedRecords)(consumptionStore.publish(_))
                          .as(
                            TurnOutput.ToolCalls(Msg.assistant(Chunk(consumptionCall)))
                          )
                      case _ =>
                        ZIO.succeed(TurnOutput.FinalText(Msg.assistant("Task output accepted")))
                )
                consumptionInbox <- SubagentInboxSignal.open
                consumptionMiddleware <- mode match
                  case ConsumptionTool.Inspection | ConsumptionTool.Catalog |
                      ConsumptionTool.TaskOutput =>
                    SubagentDeliveryMiddleware.make(fresh)
                  case ConsumptionTool.Wait =>
                    SubagentDeliveryMiddleware.open(fresh, consumptionInbox)
                consumptionToolkit = mode match
                  case ConsumptionTool.TaskOutput =>
                    consumptionMiddleware.tools().updated(consumptionMiddleware.taskOutputTool)
                  case _ =>
                    routedCatalog.fold(consumptionMiddleware.tools())(
                      consumptionMiddleware.toolsForCatalog(_)
                    )
                consumptionAgent <- consumptionMiddleware
                  .configure(
                    ReActAgent
                      .builder()
                      .client(consumptionClient)
                      .toolkit(consumptionToolkit),
                    priorLocalToolResultObserver = LocalToolResultObserver(observation =>
                      ZIO
                        .foreach(selectedRecords)(record =>
                          consumptionReopened.acknowledgement(record.id)
                        )
                        .flatMap(stamp =>
                          consumptionBeforeStamp.set(stamp) *> consumptionSnapshots
                            .update(_ :+ observation)
                        )
                        .mapError(error => AgentError.CompletionObservationError(error.message))
                    )
                  )
                  .build
                inspected <-
                  val run = consumptionAgent.run(Msg.user("Consume the selected task output"))
                  if mode == ConsumptionTool.TaskOutput then run.withClock(consumptionCheckClock)
                  else run
                consumptionStamp <- ZIO.foreach(selectedRecords)(record =>
                  consumptionReopened.acknowledgement(record.id)
                )
                consumptionContinued <- consumptionAgent.run(Msg.user("Continue after consumption"))
                consumptionStampAgain <- ZIO.foreach(selectedRecords)(record =>
                  consumptionStore.acknowledgement(record.id)
                )
                consumptionPending <- consumptionStore.pending
                consumptionExact <- ZIO.foreach(selectedRecords)(record =>
                  SubagentTerminalStore.make(second, consumptionTarget).read(record.id)
                )
                unrelatedStamps <- mode match
                  case ConsumptionTool.Catalog =>
                    ZIO.foreach(selectedRecords)(record => terminal.acknowledgement(record.id))
                  case _ => ZIO.succeed(Chunk.empty)
                consumptionModels   <- consumptionInputs.get
                consumptionObserved <- consumptionSnapshots.get
                beforeInspectionAck <- consumptionBeforeStamp.get
                checkedConsumption  <- consumptionReopened.taskRecord(consumptionId)
              yield assertTrue(
                unrelatedStamps.forall(_.isEmpty),
                beforeInspectionAck == selectedRecords.map(_ =>
                  Some(SubagentTerminalAcknowledgement.Pending)
                ),
                consumptionObserved.map(_.call) == Chunk(consumptionCall),
                consumptionObserved.forall(observation =>
                  observation.persisted.state == ToolResultState.Success &&
                    observation.persisted.outputText == expectedJson &&
                    observation.history.flatMap(_.toolResults).contains(observation.persisted)
                ),
                consumptionModels.size == 3,
                consumptionModels.forall(history =>
                  !history.exists(_.reminderKind.contains("task_delivery"))
                ),
                consumptionModels
                  .drop(1)
                  .forall(history =>
                    history.flatMap(_.toolResults).exists(_.outputText == expectedJson)
                  ),
                inspected.history.flatMap(_.toolResults).map(_.outputText) ==
                  Chunk(expectedJson),
                consumptionContinued.history.flatMap(_.toolResults) ==
                  inspected.history.flatMap(_.toolResults),
                consumptionStamp.forall(_.exists:
                  case SubagentTerminalAcknowledgement.Acknowledged(_) => true
                  case _                                               => false),
                consumptionStamp == consumptionStampAgain,
                consumptionPending.isEmpty,
                consumptionExact == selectedRecords.map(Some(_)),
                mode != ConsumptionTool.TaskOutput || checkedConsumption.exists(
                  _.metadata.exists(metadata =>
                    metadata.createdAt == consumptionCreatedAt && metadata.lastCheckedAt == consumptionCheckedAt
                  )
                )
              )
          yield assertTrue(
            receipts.map(_.id).toSet == Set(success, failed),
            exact.contains(
              SubagentTerminalRecord(
                success,
                SubagentTerminalOutcome.Completed(text),
                Some(workerId)
              )
            ),
            failedRecord.contains(
              SubagentTerminalRecord(
                failed,
                SubagentTerminalOutcome.Failed(
                  Chunk(failure.message),
                  SubagentTaskDefects.Absent
                ),
                Some(workerId)
              )
            ),
            liveBefore.isEmpty,
            liveAfter.isEmpty,
            calls == 2,
            deliveredRecords == unacknowledgedDuring,
            deliveredRecords.map(_.id).toSet == Set(success, failed),
            deliveryReceipts.map(_.id) == deliveredRecords.map(_.id),
            deliveryReceipts.forall(_.acknowledgement match
              case SubagentTerminalAcknowledgeResult.Acknowledged(_) => true
              case _                                                 => false),
            pendingAfterDelivery.isEmpty,
            storedAfterDelivery == deliveredRecords,
            firstMessages.size == 1,
            firstMessages == secondMessages,
            reminderInputs.size == 2,
            reminderInputs.forall(history =>
              history.filter(_.reminderKind.contains("task_delivery")) == firstMessages
            ),
            firstMessages.forall(_.text.contains(reminderRecord.id.value.toString)),
            firstMessages.forall(_.text.contains("agent=\"worker&lt;&amp;&quot;&apos; λ\"")),
            pendingInputs == Chunk(Chunk(reminderRecord), Chunk.empty),
            firstStamp.exists:
              case SubagentTerminalAcknowledgement.Acknowledged(_) => true
              case _                                               => false
            ,
            firstStamp == secondStamp,
            pendingAfterReminder.isEmpty,
            exactAfterReminder.contains(reminderRecord),
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
          ) && consumptionChecks.foldLeft(assertTrue(true))(_ && _)
  ) @@ TestAspect.withLiveRandom @@ TestAspect.withLiveSystem @@ TestAspect.withLiveClock @@ TestAspect
    .timeout(30.seconds) @@ TestAspect.ifEnvSet("WEISGNOSIS_POSTGRES_URL")
