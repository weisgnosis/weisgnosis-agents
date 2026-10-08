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

import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.model.ContentBlock
import com.weisgnosis.agents.core.tool.{
  AdmittedToolAccess,
  ToolAuthorization,
  ToolAuthorizationRequest,
  ToolAuthorizer,
  ToolPreflight
}
import zio.*

/** Interactive approval within one invocation; authenticated delivery and durable state belong to the host. */
final class InteractivePermissionAuthorizer private (
    policyRef: Ref[PermissionEngine],
    requests: PermissionRequests,
    publish: PermissionAdvisory => UIO[Unit],
    suggestions: PermissionSuggestions,
    toolGate: PermissionGate,
    interceptorGate: PermissionGate
) extends ToolAuthorizer:
  def policy: UIO[PermissionEngine] = policyRef.get

  /**
   * Add a live gate while retaining this instance's policy and confirmation authority.
   * The original view is unchanged. Allow is an affirmative vote, not an abstention.
   */
  def withAdditionalGate(gate: PermissionGate): InteractivePermissionAuthorizer =
    new InteractivePermissionAuthorizer(
      policyRef,
      requests,
      publish,
      suggestions,
      toolGate,
      interceptorGate.and(gate)
    )

  /** Rule denials and Explore access restrictions are stable; live votes still require authorization. */
  override def preflight(
      request: ToolAuthorizationRequest,
      admittedAccess: Option[AdmittedToolAccess]
  ): ZIO[Scope, AgentError.PermissionDenied, ToolPreflight] =
    policyRef.get.map: current =>
      val stablePolicy = current.mode match
        case PermissionMode.Explore => current
        case _                      => current.copy(mode = PermissionMode.Default)
      stablePolicy.authorize(request.requestId, request.call, admittedAccess = admittedAccess) match
        case PermissionOutcome.Denied(_, reason) => ToolPreflight.Denied(reason)
        case _                                   => ToolPreflight.Continue

  def authorize(
      request: ToolAuthorizationRequest
  ): ZIO[Scope, AgentError.PermissionDenied, ToolAuthorization] = authorize(request, None)

  override def authorize(
      request: ToolAuthorizationRequest,
      admittedAccess: Option[AdmittedToolAccess]
  ): ZIO[Scope, AgentError.PermissionDenied, ToolAuthorization] =
    for
      gates   <- evaluateGates(request.call, admittedAccess)
      current <- policyRef.get
      decision <- current.authorize(
        request.requestId,
        request.call,
        gates.tool,
        gates.interceptor,
        admittedAccess
      ) match
        case PermissionOutcome.Approved(call) => ZIO.succeed(ToolAuthorization.Approved(call.input))
        case PermissionOutcome.Denied(_, reason) => ZIO.succeed(ToolAuthorization.Denied(reason))
        case PermissionOutcome.AwaitingApproval(pending) =>
          confirm(request, pending, admittedAccess)
    yield decision

  private def confirm(
      request: ToolAuthorizationRequest,
      pending: PendingApproval,
      admittedAccess: Option[AdmittedToolAccess]
  ): ZIO[Scope, AgentError.PermissionDenied, ToolAuthorization] =
    for
      advisory <- PermissionAdvisory.prepare(request, suggestions)
      confirmation <- requests
        .awaitApproval(pending)(_ => ZIO.suspendSucceed(publish(advisory)))
        .mapError {
          case denied: AgentError.PermissionDenied => denied
          case error => AgentError.PermissionDenied(request.call.name, error.message)
        }
      decision <-
        if !confirmation.confirmed then
          ZIO.succeed(
            ToolAuthorization.Denied(confirmation.reason.getOrElse("Tool execution declined"))
          )
        else
          val approvedCall =
            pending.call.copy(input = confirmation.modifiedInput.getOrElse(pending.call.input))
          for
            gates <- evaluateGates(approvedCall, admittedAccess)
            decision <- policyRef.modify { current =>
              val (updated, resumed) = current.resume(pending, confirmation)
              val outcome = resumed match
                case PermissionOutcome.Approved(call) =>
                  if updated.evaluate(
                      call.name,
                      call.input,
                      gates.tool,
                      gates.interceptor,
                      admittedAccess
                    ) == PermissionBehavior.Deny
                  then ToolAuthorization.Denied("Confirmed input is denied by a permission gate")
                  else ToolAuthorization.Approved(call.input)
                case PermissionOutcome.Denied(_, reason) => ToolAuthorization.Denied(reason)
                case PermissionOutcome.AwaitingApproval(_) =>
                  ToolAuthorization.Denied("Confirmation did not resolve the pending approval")
              (outcome, updated)
            }
          yield decision
    yield decision

  private def evaluateGates(
      call: ContentBlock.ToolUse,
      admittedAccess: Option[AdmittedToolAccess]
  ): IO[AgentError.PermissionDenied, GateDecisions] =
    for
      tool <- ZIO.scoped(
        ZIO.suspendSucceed(toolGate.evaluate(call.name, call.input, admittedAccess))
      )
      interceptor <- ZIO.scoped(
        ZIO.suspendSucceed(interceptorGate.evaluate(call.name, call.input, admittedAccess))
      )
    yield GateDecisions(tool, interceptor)

  final private case class GateDecisions(
      tool: Option[PermissionBehavior],
      interceptor: Option[PermissionBehavior]
  )

object InteractivePermissionAuthorizer:
  def make(
      requests: PermissionRequests,
      publish: ToolAuthorizationRequest => UIO[Unit],
      initialPolicy: PermissionEngine = PermissionEngine(),
      toolGate: PermissionGate = PermissionGate.abstain,
      interceptorGate: PermissionGate = PermissionGate.abstain
  ): UIO[InteractivePermissionAuthorizer] =
    Ref
      .make(initialPolicy)
      .map(
        new InteractivePermissionAuthorizer(
          _,
          requests,
          advisory => publish(advisory.request),
          PermissionSuggestions.none,
          toolGate,
          interceptorGate
        )
      )

  /** Provider cleanup finishes before Promise registration; advice is installed only by confirmation. */
  def makeWithAdvisories(
      requests: PermissionRequests,
      publish: PermissionAdvisory => UIO[Unit],
      initialPolicy: PermissionEngine = PermissionEngine(),
      toolGate: PermissionGate = PermissionGate.abstain,
      interceptorGate: PermissionGate = PermissionGate.abstain,
      suggestions: PermissionSuggestions = PermissionSuggestions.default
  ): UIO[InteractivePermissionAuthorizer] =
    Ref
      .make(initialPolicy)
      .map(
        new InteractivePermissionAuthorizer(
          _,
          requests,
          publish,
          suggestions,
          toolGate,
          interceptorGate
        )
      )
