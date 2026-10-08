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
import java.util.UUID
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

object PostgresSubagentTerminalStoreSpec extends ZIOSpecDefault:
  import PostgresFixture.*

  private enum ConsumptionTool:
    case Inspection, Wait

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
            third = SubagentTerminalRecord(
              SubagentTaskId(new UUID(0L, 3L)),
              SubagentTerminalOutcome.Completed("concurrent publication")
            )
            acknowledgements <- ZIO
              .foreachPar(Chunk(terminal, reopened))(_.acknowledge(firstId))
              .zipPar(terminal.publish(third))
              .map(_._1)
            stamp         <- reopened.acknowledgement(firstId)
            undelivered   <- reopened.pending
            exactAfterAck <- reopened.read(firstId)
            isolatedStamp <- SubagentTerminalStore.make(second, other).acknowledgement(firstId)
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
            isolatedStamp.isEmpty
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
              SubagentTerminalOutcome.Completed("Reopened reminder result")
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
              Chunk(ConsumptionTool.Inspection, ConsumptionTool.Wait)
            ): mode =>
              for
                consumptionId <- Random.nextUUID.map(SubagentTaskId(_))
                failureId     <- Random.nextUUID.map(SubagentTaskId(_))
                consumptionRecord = SubagentTerminalRecord(
                  consumptionId,
                  SubagentTerminalOutcome.Completed("Fresh consumption result")
                )
                failedConsumption = SubagentTerminalRecord(
                  failureId,
                  SubagentTerminalOutcome.Failed(
                    Chunk("Selected failed output"),
                    SubagentTaskDefects.Absent
                  )
                )
                selectedRecords = mode match
                  case ConsumptionTool.Inspection => Chunk(consumptionRecord)
                  case ConsumptionTool.Wait       => Chunk(failedConsumption, consumptionRecord)
                consumptionCall = ContentBlock.ToolUse(
                  "consumed-consumption",
                  mode match
                    case ConsumptionTool.Inspection => "agent_task"
                    case ConsumptionTool.Wait       => "wait_async_results",
                  mode match
                    case ConsumptionTool.Inspection =>
                      Json.Obj("task_id" -> Json.Str(consumptionId.value.toString))
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
                        ZIO
                          .foreachDiscard(selectedRecords)(terminal.publish(_))
                          .as(
                            TurnOutput.ToolCalls(Msg.assistant(Chunk(consumptionCall)))
                          )
                      case _ =>
                        ZIO.succeed(TurnOutput.FinalText(Msg.assistant("Task output accepted")))
                )
                consumptionMiddleware <- SubagentDeliveryMiddleware.make(fresh)
                consumptionAgent <- consumptionMiddleware
                  .configure(
                    ReActAgent
                      .builder()
                      .client(consumptionClient)
                      .toolkit(consumptionMiddleware.tools()),
                    priorLocalToolResultObserver = LocalToolResultObserver(observation =>
                      ZIO
                        .foreach(selectedRecords)(record => reopened.acknowledgement(record.id))
                        .flatMap(stamp =>
                          consumptionBeforeStamp.set(stamp) *> consumptionSnapshots
                            .update(_ :+ observation)
                        )
                        .mapError(error => AgentError.CompletionObservationError(error.message))
                    )
                  )
                  .build
                inspected <- consumptionAgent.run(Msg.user("Consume the selected task output"))
                consumptionStamp <- ZIO.foreach(selectedRecords)(record =>
                  reopened.acknowledgement(record.id)
                )
                consumptionContinued <- consumptionAgent.run(Msg.user("Continue after consumption"))
                consumptionStampAgain <- ZIO.foreach(selectedRecords)(record =>
                  terminal.acknowledgement(record.id)
                )
                consumptionPending <- terminal.pending
                consumptionExact <- ZIO.foreach(selectedRecords)(record =>
                  SubagentTerminalStore.make(second, target).read(record.id)
                )
                consumptionModels   <- consumptionInputs.get
                consumptionObserved <- consumptionSnapshots.get
                beforeInspectionAck <- consumptionBeforeStamp.get
              yield assertTrue(
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
                consumptionExact == selectedRecords.map(Some(_))
              )
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
