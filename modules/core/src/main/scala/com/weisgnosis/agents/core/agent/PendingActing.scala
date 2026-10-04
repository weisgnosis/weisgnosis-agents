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
package com.weisgnosis.agents.core.agent

import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.event.ModelStreamContext
import com.weisgnosis.agents.core.json.JsonBoundary
import com.weisgnosis.agents.core.model.{ContentBlock, Role, ToolHistory, ToolResultState}
import com.weisgnosis.agents.core.tool.{ToolDef, ToolGroup}
import zio.Chunk
import zio.json.*
import zio.json.ast.Json

final case class CompletedToolCall(callOrdinal: Int, historyPosition: Int) derives JsonCodec

final case class PendingToolCall(callOrdinal: Int, call: ContentBlock.ToolUse) derives JsonCodec

final case class PendingActingStop(callOrdinal: Int, reason: String) derives JsonCodec

/** Correlation data for a deferred occurrence, never evidence of human approval. */
final case class PendingPermissionRequest(callOrdinal: Int, requestId: String, input: Json)
    derives JsonCodec

enum PendingActingStage derives JsonCodec:
  case BeforeActing
  case AwaitingApproval(
      completed: Chunk[CompletedToolCall],
      latestToolGroups: Option[Chunk[ToolGroup]],
      stop: Option[PendingActingStop]
  )

/** Serializable acting data. Decoding does not restore tools or grant execution authority. */
final case class PendingActing(
    reason: String,
    context: ModelStreamContext,
    assistantPosition: Int,
    admittedTools: Chunk[ToolDef],
    toolGroups: Option[Chunk[ToolGroup]],
    responseFormat: Option[Json],
    suppliedResults: Chunk[SuppliedToolResult] = Chunk.empty,
    stage: PendingActingStage = PendingActingStage.BeforeActing,
    permissionRequests: Chunk[PendingPermissionRequest] = Chunk.empty
) derives JsonCodec

object PendingActing:
  /** Checked host-facing choices retain original occurrence identity across partial completion. */
  def pendingCalls(
      checkpoint: AgentResult
  ): Either[AgentError.InvalidConfiguration, Chunk[PendingToolCall]] =
    unresolved(checkpoint).map(_.map(value => PendingToolCall(value.ordinal, value.call)))

  /** Adds checked external observations without changing the saved assistant or its history. */
  def withResults(
      checkpoint: AgentResult,
      results: Chunk[SuppliedToolResult]
  ): Either[AgentError.InvalidConfiguration, AgentResult] =
    for
      pending <- validate(checkpoint)
      candidate = checkpoint.copy(state =
        AgentRunState.Suspended(
          pending.copy(suppliedResults = pending.suppliedResults ++ results)
        )
      )
      _ <- validate(candidate)
    yield candidate

  private[agents] def validate(
      result: AgentResult
  ): Either[AgentError.InvalidConfiguration, PendingActing] =
    checked(result).map(_._1)

  /** The shared checked occurrence projection never infers completion from repeated call IDs. */
  private[agents] def unresolved(
      result: AgentResult
  ): Either[AgentError.InvalidConfiguration, Chunk[ToolHistory.Occurrence]] =
    checked(result).map { case (pending, calls) =>
      val done = completed(pending).map(_.callOrdinal).toSet ++
        pending.suppliedResults.map(_.callOrdinal).toSet
      calls.filterNot(call => done.contains(call.ordinal))
    }

  private[agents] def completed(pending: PendingActing): Chunk[CompletedToolCall] =
    pending.stage match
      case PendingActingStage.BeforeActing                   => Chunk.empty
      case PendingActingStage.AwaitingApproval(values, _, _) => values

  private def checked(
      result: AgentResult
  ): Either[AgentError.InvalidConfiguration, (PendingActing, Chunk[ToolHistory.Occurrence])] =
    val invalid = AgentError.InvalidConfiguration("Invalid acting checkpoint")
    result.state match
      case AgentRunState.Suspended(pending) =>
        val prefix        = result.history.take(pending.assistantPosition)
        val analysis      = ToolHistory.inspect(result.history.take(pending.assistantPosition + 1))
        val localOrdinals = analysis.pending.map(_.ordinal).toSet
        val ledger        = completed(pending)
        val done          = ledger.map(_.callOrdinal).toSet
        val suppliedOrdinals = pending.suppliedResults.map(_.callOrdinal)
        for
          _ <- Either.cond(
            pending.reason.trim.nonEmpty &&
              pending.context.replyId.trim.nonEmpty && pending.context.requestId.trim.nonEmpty &&
              result.iterationCount > 0 && pending.assistantPosition >= 0 &&
              result.history.lift(pending.assistantPosition).contains(result.reply) &&
              result.reply.role == Role.Assistant && result.reply.toolUses.nonEmpty &&
              ToolHistory.inspect(prefix).isComplete && analysis.unmatched.isEmpty &&
              analysis.pending.forall(_.position == pending.assistantPosition) &&
              pending.admittedTools.map(_.name).distinct.size == pending.admittedTools.size,
            (),
            invalid
          )
          _ <- pending.stage match
            case PendingActingStage.BeforeActing =>
              Either.cond(
                pending.assistantPosition == result.history.size - 1 && pending.permissionRequests.isEmpty,
                (),
                invalid
              )
            case PendingActingStage.AwaitingApproval(_, _, stop) =>
              val tailPositions   = (pending.assistantPosition + 1 until result.history.size).toSet
              val completedInline = ToolHistory.completedInlineOrdinals(result.reply)
              val validStop = stop.forall(value =>
                done.contains(value.callOrdinal) || completedInline.contains(value.callOrdinal)
              )
              Either.cond(
                ledger.map(_.callOrdinal).distinct.size == ledger.size &&
                  ledger.map(_.historyPosition).distinct.size == ledger.size &&
                  ledger.map(_.historyPosition).toSet == tailPositions &&
                  done.subsetOf(localOrdinals) && (localOrdinals -- done).nonEmpty &&
                  validStop && ledger.forall(value =>
                    (
                      result.reply.toolUses.lift(value.callOrdinal),
                      result.history.lift(value.historyPosition)
                    ) match
                      case (Some(call), Some(message)) =>
                        message.role == Role.Tool && message.name.contains(call.name) &&
                        message.content.size == 1 && message.toolResults.headOption.exists(
                          observation =>
                            observation.id == call.id && observation.name.contains(call.name) &&
                              Set(
                                ToolResultState.Success,
                                ToolResultState.Error,
                                ToolResultState.Denied
                              ).contains(observation.state)
                        ) && message.toJsonAST.flatMap(JsonBoundary.validate).isRight
                      case _ => false
                  ),
                (),
                invalid
              )
          _ <- pending.suppliedResults.foldLeft[Either[AgentError.InvalidConfiguration, Unit]](
            Right(())
          )((checked, supplied) => checked.flatMap(_ => SuppliedToolResult.validate(supplied)))
          _ <- Either.cond(
            pending.permissionRequests.isEmpty ||
              (pending.permissionRequests
                .map(_.callOrdinal)
                .distinct
                .size == pending.permissionRequests.size &&
                pending.permissionRequests.map(_.callOrdinal).toSet == (localOrdinals -- done) &&
                pending.permissionRequests
                  .map(_.requestId)
                  .distinct
                  .size == pending.permissionRequests.size &&
                pending.permissionRequests.forall(request =>
                  request.requestId.trim.nonEmpty && JsonBoundary.validate(request.input).isRight
                )),
            (),
            AgentError.InvalidConfiguration("Invalid deferred permission requests")
          )
          _ <- Either.cond(
            suppliedOrdinals.distinct.size == suppliedOrdinals.size &&
              suppliedOrdinals.toSet.subsetOf(localOrdinals -- done),
            (),
            AgentError.InvalidConfiguration(
              "Supplied results must identify distinct pending local occurrences"
            )
          )
        yield (pending, analysis.pending)
      case _ => Left(invalid)
