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
package com.weisgnosis.agents.harness.permission

import com.weisgnosis.agents.core.agent.{AgentResult, PendingActing}
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.event.ModelStreamContext
import com.weisgnosis.agents.core.model.ContentBlock
import com.weisgnosis.agents.core.tool.{
  AdmittedToolAccess,
  ToolAuthorization,
  ToolAuthorizationRequest,
  ToolAuthorizer
}
import zio.*

/**
 * Checked confirmations for one saved acting batch. Authentication, persistence and replay
 * exclusion belong to the host; this value neither consumes confirmations nor mutates policy.
 */
final class ConfirmedBatchAuthorizer private (
    context: ModelStreamContext,
    decisions: Map[Int, (ContentBlock.ToolUse, Option[ConfirmResult])],
    val ruleUpdates: Chunk[PermissionRule],
    currentPolicy: UIO[PermissionEngine],
    toolGate: PermissionGate,
    interceptorGate: PermissionGate
) extends ToolAuthorizer:
  def authorize(
      request: ToolAuthorizationRequest
  ): ZIO[Scope, AgentError.PermissionDenied, ToolAuthorization] = authorize(request, None)

  override def authorize(
      request: ToolAuthorizationRequest,
      admittedAccess: Option[AdmittedToolAccess]
  ): ZIO[Scope, AgentError.PermissionDenied, ToolAuthorization] =
    ZIO.suspendSucceed:
      request.callOrdinal.flatMap(decisions.get) match
        case Some((original, confirmation))
            if request.context == context && request.call.id == original.id &&
              request.call.name == original.name =>
          confirmation match
            case None => ZIO.succeed(ToolAuthorization.Deferred("Awaiting host confirmation"))
            case Some(confirmed) =>
              if !confirmed.confirmed then
                ZIO.succeed(
                  ToolAuthorization.Denied(confirmed.reason.getOrElse("Tool execution declined"))
                )
              else
                // The runtime has already prepared fixed arguments; omitted edits retain that input.
                val input = confirmed.modifiedInput.getOrElse(request.call.input)
                for
                  tool <- ZIO.scoped(
                    ZIO.suspendSucceed(toolGate.evaluate(request.call.name, input, admittedAccess))
                  )
                  interceptor <- ZIO.scoped(
                    ZIO.suspendSucceed(
                      interceptorGate.evaluate(request.call.name, input, admittedAccess)
                    )
                  )
                  policy <- currentPolicy
                  updated = policy.copy(rules = policy.rules ++ ruleUpdates)
                yield
                  if updated.evaluate(
                      request.call.name,
                      input,
                      tool,
                      interceptor
                    ) == PermissionBehavior.Deny
                  then
                    ToolAuthorization.Denied(
                      "Confirmed input is denied by current policy or a permission gate"
                    )
                  else ToolAuthorization.Approved(input)
        case _ =>
          ZIO.succeed(
            ToolAuthorization.Denied("Authorization request does not match the confirmed batch")
          )

object ConfirmedBatchAuthorizer:
  private enum Coverage:
    case Complete, Partial

  def make(
      checkpoint: AgentResult,
      confirmations: Chunk[ConfirmedToolCall],
      currentPolicy: UIO[PermissionEngine],
      toolGate: PermissionGate = PermissionGate.abstain,
      interceptorGate: PermissionGate = PermissionGate.abstain
  ): IO[AgentError.InvalidConfiguration, ConfirmedBatchAuthorizer] =
    checked(checkpoint, confirmations, currentPolicy, toolGate, interceptorGate, Coverage.Complete)

  /** Missing decisions defer only matching unresolved occurrences; completed calls cannot be replayed. */
  def makePartial(
      checkpoint: AgentResult,
      confirmations: Chunk[ConfirmedToolCall],
      currentPolicy: UIO[PermissionEngine],
      toolGate: PermissionGate = PermissionGate.abstain,
      interceptorGate: PermissionGate = PermissionGate.abstain
  ): IO[AgentError.InvalidConfiguration, ConfirmedBatchAuthorizer] =
    checked(checkpoint, confirmations, currentPolicy, toolGate, interceptorGate, Coverage.Partial)

  private def checked(
      checkpoint: AgentResult,
      confirmations: Chunk[ConfirmedToolCall],
      currentPolicy: UIO[PermissionEngine],
      toolGate: PermissionGate,
      interceptorGate: PermissionGate,
      coverage: Coverage
  ): IO[AgentError.InvalidConfiguration, ConfirmedBatchAuthorizer] =
    ZIO.fromEither:
      for
        pending     <- PendingActing.validate(checkpoint)
        occurrences <- PendingActing.unresolved(checkpoint)
        ordinals = confirmations.map(_.callOrdinal)
        expected = occurrences.map(_.ordinal).toSet
        covered = coverage match
          case Coverage.Complete => ordinals.toSet == expected
          case Coverage.Partial  => ordinals.toSet.subsetOf(expected)
        _ <- Either.cond(
          ordinals.distinct.size == ordinals.size && covered,
          (),
          AgentError.InvalidConfiguration(
            "Confirmations do not match the required unresolved tool occurrences"
          )
        )
        byOrdinal = confirmations.map(value => value.callOrdinal -> value.confirmation).toMap
        decisions = occurrences
          .map(occurrence =>
            occurrence.ordinal -> (occurrence.call, byOrdinal.get(occurrence.ordinal))
          )
          .toMap
        updates = confirmations
          .sortBy(_.callOrdinal)
          .filter(_.confirmation.confirmed)
          .flatMap(_.confirmation.newRules)
      yield new ConfirmedBatchAuthorizer(
        pending.context,
        decisions,
        updates,
        currentPolicy,
        toolGate,
        interceptorGate
      )
