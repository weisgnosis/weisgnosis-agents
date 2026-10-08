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

import com.weisgnosis.agents.core.agent.*
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.event.{InvocationContext, ModelStreamContext}
import com.weisgnosis.agents.core.model.{ContentBlock, Msg, ToolResultState}
import com.weisgnosis.agents.core.tool.{ToolExecutionResult, ToolInvocation, Toolkit}
import java.nio.charset.StandardCharsets
import zio.*
import zio.json.*
import zio.json.ast.Json

/**
 * One parent agent's scoped, bounded delivery capture. Keep this scope alive through all configured
 * agent invocations. Closing joins admitted hooks, including their prior scoped callback cleanup.
 * Hooks must not reenter this adapter or await the same agent run lock. Escaped hooks fail instead
 * of reopening. Hosts bound callback/backend operations and own invocation cancellation.
 * Exact records remain in the host store; reminders and model-facing task tools expose previews.
 */
final class SubagentDeliveryMiddleware private (
    registry: SubagentTaskRegistry,
    state: Ref[SubagentDeliveryMiddleware.State],
    operations: Semaphore
):
  import SubagentDeliveryMiddleware.*

  /**
   * Replace all four builder hook slots using the explicitly supplied prior hooks. Existing builder
   * hooks are not inspected. Preparation and acceptance run after successful prior scoped cleanup;
   * completion releases matching ownership even when prior completion observation fails.
   */
  def configure(
      builder: ReActAgent.Builder,
      priorHistoryPreparer: HistoryPreparer = HistoryPreparer.identity,
      priorModelResponseObserver: ModelResponseObserver = ModelResponseObserver.ignore,
      priorCompletionObserver: TurnCompletionObserver = TurnCompletionObserver.ignore,
      priorLocalToolResultObserver: LocalToolResultObserver = LocalToolResultObserver.ignore
  ): ReActAgent.Builder =
    val preparer: HistoryPreparer = new HistoryPreparer:
      def prepare(history: Chunk[Msg]): ZIO[Scope, AgentError.HistoryPreparationError, Chunk[Msg]] =
        ZIO.fail(AgentError.HistoryPreparationError("Task delivery requires invocation context"))
      override def prepare(
          context: InvocationContext,
          history: Chunk[Msg]
      ): ZIO[Scope, AgentError.HistoryPreparationError, Chunk[Msg]] =
        operations.withPermit:
          (ensureOpen *> ZIO
            .scoped(priorHistoryPreparer.prepare(context, history))
            .flatMap(prepared => prepareDelivery(context, prepared))).mapError(error =>
            AgentError.HistoryPreparationError(error.message)
          )
    builder
      .historyPreparer(preparer)
      .modelResponseObserver(
        ModelResponseObserver(response =>
          operations.withPermit:
            ensureOpen.mapError(error => AgentError.CompletionObservationError(error.message)) *>
              ZIO.scoped(priorModelResponseObserver.observe(response)) *> accept(response)
        )
      )
      .localToolResultObserver(
        LocalToolResultObserver(observation =>
          operations.withPermit:
            ensureOpen.mapError(error => AgentError.CompletionObservationError(error.message)) *>
              ZIO.scoped(priorLocalToolResultObserver.observe(observation)) *> consumeInspection(
                observation
              )
        )
      )
      .completionObserver(
        TurnCompletionObserver(completion =>
          operations.withPermit:
            ensureOpen.mapError(error => AgentError.CompletionObservationError(error.message)) *>
              ZIO
                .scoped(priorCompletionObserver.observe(completion))
                .ensuring(
                  ZIO.foreachDiscard(completion.invocation)(release)
                )
        )
      )

  /**
   * Bound task inspection participates in this adapter's lifecycle and consumes unchanged persisted
   * observations. Spawn/cancel retain registry ownership and are not serialized by this adapter.
   * Ordinary, routed and session tool factories do not acquire this consumption capability.
   */
  def tools(
      policy: SubagentExecutionPolicy = SubagentExecutionPolicy.PromoteAfter30Seconds
  ): Toolkit =
    SubagentTools.consuming(
      registry,
      policy,
      new SubagentInspectionCapture:
        def inspect(id: SubagentTaskId, invocation: ToolInvocation)(
            read: => IO[AgentError, SubagentToolResult]
        ): IO[AgentError, SubagentToolResult] =
          operations.withPermit:
            ensureOpen *> (invocation match
              case ToolInvocation.Detached => ZIO.suspendSucceed(read)
              case ToolInvocation.Active(context, approved, ordinal, _) =>
                val key = InspectionKey(context, approved, ordinal)
                for
                  identityBytes <- prepareInspection(key, id)
                  result        <- ZIO.suspendSucceed(read)
                  _ <-
                    if isTerminal(result) then captureInspection(key, id, result, identityBytes)
                    else ZIO.unit
                yield result)
    )

  private def prepareInspection(key: InspectionKey, id: SubagentTaskId): IO[AgentError, Long] =
    val bytes = key.toJson.getBytes(StandardCharsets.UTF_8).length.toLong +
      id.value.toString.toJson.getBytes(StandardCharsets.UTF_8).length.toLong
    key.context.invocation match
      case None =>
        ZIO.fail(AgentError.InvalidConfiguration("Task inspection requires invocation context"))
      case Some(invocation) =>
        state
          .modify:
            case current @ State.Available(capture, owner, receipts, used) =>
              if owner.exists(_ != invocation) || key.call.name != "agent_task" || key.ordinal < 0
              then
                (
                  Left(
                    AgentError.InvalidConfiguration(
                      "Task inspection requires this adapter's active invocation"
                    )
                  ),
                  current
                )
              else if receipts.contains(key) then
                (
                  Left(
                    AgentError.InvalidConfiguration(
                      "Task inspection occurrence was already captured"
                    )
                  ),
                  current
                )
              else if receipts.size >= maximumInspections || bytes > maximumInspectionBytes - used
              then
                (
                  Left(
                    AgentError.StorageError("Task inspection capture exceeds its bounded capacity")
                  ),
                  current
                )
              else (Right(bytes), State.Available(capture, Some(invocation), receipts, used))
            case State.Closed => (Left(closed), State.Closed)
          .flatMap(ZIO.fromEither(_))

  private def captureInspection(
      key: InspectionKey,
      id: SubagentTaskId,
      result: SubagentToolResult,
      identityBytes: Long
  ): IO[AgentError, Unit] =
    val expected = ToolExecutionResult(result.toJson).payload
      .applyTo(ContentBlock.ToolResult(key.call.id, Some(key.call.name)))
    val bytes = identityBytes + expected.toJson.getBytes(StandardCharsets.UTF_8).length.toLong
    state
      .modify:
        case current @ State.Available(capture, owner, receipts, used) =>
          if receipts.contains(key) then
            (
              Left(
                AgentError.InvalidConfiguration("Task inspection occurrence was already captured")
              ),
              current
            )
          else if receipts.size >= maximumInspections || bytes > maximumInspectionBytes - used then
            (
              Left(AgentError.StorageError("Task inspection capture exceeds its bounded capacity")),
              current
            )
          else
            (
              Right(()),
              State.Available(
                capture,
                owner,
                receipts.updated(key, InspectionReceipt(id, expected, bytes)),
                used + bytes
              )
            )
        case State.Closed => (Left(closed), State.Closed)
      .flatMap(ZIO.fromEither(_))

  private def consumeInspection(
      observation: PersistedLocalToolObservation
  ): IO[AgentError.CompletionObservationError, Unit] =
    val key = InspectionKey(observation.context, observation.call, observation.callOrdinal)
    state
      .modify:
        case State.Available(capture, owner, receipts, used) =>
          val receipt = receipts.get(key)
          (
            receipt,
            State.Available(capture, owner, receipts - key, used - receipt.fold(0L)(_.bytes))
          )
        case State.Closed => (None, State.Closed)
      .flatMap:
        case Some(receipt)
            if observation.original == receipt.expected &&
              observation.original.state == ToolResultState.Success &&
              observation.persisted.id == observation.original.id &&
              observation.persisted.name == observation.original.name &&
              observation.persisted.state == observation.original.state &&
              observation.persisted.output == observation.original.output =>
          registry
            .acknowledgeTerminal(receipt.id)
            .flatMap:
              case SubagentTerminalAcknowledgeResult.Missing =>
                ZIO.fail(
                  AgentError.StorageError(
                    "Consumed terminal observation disappeared before acknowledgement"
                  )
                )
              case _ => ZIO.unit
            .mapError(error => AgentError.CompletionObservationError(error.message))
        case _ => ZIO.unit

  private def ensureOpen: IO[AgentError.InvalidConfiguration, Unit] = state.get.flatMap:
    case State.Closed => ZIO.fail(closed)
    case _            => ZIO.unit

  private def prepareDelivery(
      context: InvocationContext,
      history: Chunk[Msg]
  ): IO[AgentError, Chunk[Msg]] =
    for
      current <- state.get
      existing <- current match
        case State.Closed => ZIO.fail(closed)
        case State.Available(_, Some(owner), _, _) if owner != context =>
          ZIO.fail(
            AgentError.InvalidConfiguration(
              "Task delivery adapter belongs to another active invocation"
            )
          )
        case State.Available(capture, _, receipts, used) =>
          state.set(State.Available(capture, Some(context), receipts, used)).as(capture)
      capture <- existing match
        case Some(value) => ZIO.succeed(Some(value))
        case None =>
          registry.durablePendingDeliveries.flatMap: pending =>
            val selected = pending.take(maximumRecords)
            if selected.map(_.id).toSet.size != selected.size then
              ZIO.fail(
                AgentError.StorageError("Delivery snapshot contains duplicate terminal task IDs")
              )
            else if selected.isEmpty then ZIO.none
            else
              reminder(selected, pending.size).identified.flatMap: message =>
                val captured = Capture(message, selected)
                state
                  .update:
                    case State.Available(_, _, receipts, used) =>
                      State.Available(Some(captured), Some(context), receipts, used)
                    case State.Closed => State.Closed
                  .as(Some(captured))
    yield capture match
      case Some(value) if !history.contains(value.message) => history :+ value.message
      case _                                               => history

  private def accept(
      response: AcceptedModelResponse
  ): IO[AgentError.CompletionObservationError, Unit] =
    (for
      current <- state.get
      _ <- current match
        case State.Closed => ZIO.fail(closed)
        case State.Available(Some(capture), Some(owner), _, _)
            if response.context.invocation.contains(owner) &&
              response.request.history
                .contains(capture.message) && response.history.contains(capture.message) =>
          for
            _ <- ZIO.foreachDiscard(capture.records): record =>
              registry
                .acknowledgeTerminal(record.id)
                .flatMap:
                  case SubagentTerminalAcknowledgeResult.Missing =>
                    ZIO.fail(
                      AgentError.StorageError(
                        "Delivered terminal observation disappeared before acknowledgement"
                      )
                    )
                  case _ => ZIO.unit
            _ <- state.modify:
              case State.Available(Some(unchanged), Some(currentOwner), receipts, used)
                  if unchanged == capture && currentOwner == owner =>
                ((), State.Available(None, Some(owner), receipts, used))
              case other => ((), other)
          yield ()
        case _ => ZIO.unit
    yield ()).mapError(error => AgentError.CompletionObservationError(error.message))

  private def release(context: InvocationContext): UIO[Unit] =
    state.update:
      case State.Available(capture, owner, receipts, _) =>
        val retained = receipts.filterNot((key, _) => key.context.invocation.contains(context))
        State.Available(
          capture,
          owner.filterNot(_ == context),
          retained,
          retained.valuesIterator.map(_.bytes).sum
        )
      case State.Closed => State.Closed

  private[subagent] def close: UIO[Unit] = operations.withPermit(state.set(State.Closed))

object SubagentDeliveryMiddleware:
  private val maximumRecords: Int = 10
  private val closed: AgentError.InvalidConfiguration =
    AgentError.InvalidConfiguration("Task delivery adapter is closed")

  // Encoded retained key/task/result data is bounded independently of JVM object overhead.
  private val maximumInspections: Int      = 128
  private val maximumInspectionBytes: Long = 1024L * 1024L
  final private case class InspectionKey(
      context: ModelStreamContext,
      call: ContentBlock.ToolUse,
      ordinal: Int
  ) derives JsonCodec
  final private case class InspectionReceipt(
      id: SubagentTaskId,
      expected: ContentBlock.ToolResult,
      bytes: Long
  )

  private def isTerminal(result: SubagentToolResult): Boolean = result match
    case SubagentToolResult.Task(_, state) =>
      state match
        case SubagentToolState.Running | SubagentToolState.Promoted | SubagentToolState.Missing =>
          false
        case _ => true
    case _ => false

  final private case class Capture(message: Msg, records: Chunk[SubagentTerminalRecord])
  private enum State:
    case Available(
        capture: Option[Capture],
        owner: Option[InvocationContext],
        receipts: Map[InspectionKey, InspectionReceipt],
        inspectionBytes: Long
    )
    case Closed

  def make(registry: SubagentTaskRegistry): ZIO[Scope, Nothing, SubagentDeliveryMiddleware] =
    for
      state      <- Ref.make[State](State.Available(None, None, Map.empty, 0L))
      operations <- Semaphore.make(1)
      middleware <- ZIO.acquireRelease(
        ZIO.succeed(new SubagentDeliveryMiddleware(registry, state, operations))
      )(_.close)
    yield middleware

  private def escape(text: String): String =
    text
      .replace("&", "&amp;")
      .replace("<", "&lt;")
      .replace(">", "&gt;")
      .replace("\"", "&quot;")
      .replace("'", "&apos;")

  private def reminder(records: Chunk[SubagentTerminalRecord], total: Int): Msg =
    val noun = if total == 1 then "task has" else "tasks have"
    val blocks = records.map: record =>
      val (status, tag, text) = record.outcome match
        case SubagentTerminalOutcome.Completed(output) => ("completed", "task_result", output)
        case SubagentTerminalOutcome.Suspended(output) => ("suspended", "task_result", output)
        case SubagentTerminalOutcome.Stopped(reason)   => ("stopped", "task_error", reason)
        case SubagentTerminalOutcome.Interrupted(failures, SubagentTaskDefects.Absent)
            if failures.isEmpty =>
          ("cancelled", "task_error", "Task was cancelled before producing a result.")
        case SubagentTerminalOutcome.Interrupted(failures, defects) =>
          ("error", "task_error", "Task was interrupted; " + failureText(failures, defects))
        case SubagentTerminalOutcome.Failed(failures, defects) =>
          ("error", "task_error", failureText(failures, defects))
      val bounded = SubagentTools.preview(text)
      val omission =
        if bounded.omittedCharacters > 0 then
          "\n[Output truncated; the exact record remains available to the host.]"
        else ""
      val preview = escape(bounded.text + omission)
      s"<task id=\"${record.id.value}\" state=\"$status\">\n<$tag>\n$preview\n</$tag>\n</task>"
    val overflow =
      if total > records.size then
        s"\n... and ${total - records.size} more — call task_list() to inspect additional task previews.\n"
      else "\n"
    val text =
      s"<system-reminder>\n$total background subagent $noun completed since your last turn. Result previews are now part of your conversation history.\n\n${blocks.mkString("\n\n")}$overflow</system-reminder>"
    Msg
      .user(text)
      .withName("system")
      .withMetadata(
        Map(
          Msg.MetadataSynthetic    -> Json.Bool(true),
          Msg.MetadataReminderKind -> Json.Str("task_delivery")
        )
      )

  private def failureText(failures: Chunk[String], defects: SubagentTaskDefects): String =
    val explanation = failures.mkString("; ")
    defects match
      case SubagentTaskDefects.Present if explanation.nonEmpty =>
        explanation + "; task also reported an internal defect"
      case SubagentTaskDefects.Present => "Task failed with an internal defect"
      case SubagentTaskDefects.Absent if explanation.nonEmpty => explanation
      case SubagentTaskDefects.Absent => "Task failed without an error description"
