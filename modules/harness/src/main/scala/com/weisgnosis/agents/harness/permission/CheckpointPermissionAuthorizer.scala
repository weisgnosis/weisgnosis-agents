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
import com.weisgnosis.agents.core.tool.{
  AdmittedToolAccess,
  ToolAuthorization,
  ToolAuthorizationRequest,
  ToolAuthorizer
}
import zio.*

/** Nonblocking permission decisions; the runtime checkpoints deferred calls after joining the batch. */
final class CheckpointPermissionAuthorizer private (
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
    for
      tool <- ZIO.scoped(
        ZIO.suspendSucceed(
          toolGate.evaluate(request.call.name, request.call.input, admittedAccess)
        )
      )
      interceptor <- ZIO.scoped(
        ZIO.suspendSucceed(
          interceptorGate.evaluate(request.call.name, request.call.input, admittedAccess)
        )
      )
      // Sample after both gate scopes and their finalizers; policy storage remains host-owned.
      policy <- currentPolicy
    yield policy.authorize(request.requestId, request.call, tool, interceptor) match
      case PermissionOutcome.Approved(call)    => ToolAuthorization.Approved(call.input)
      case PermissionOutcome.Denied(_, reason) => ToolAuthorization.Denied(reason)
      case PermissionOutcome.AwaitingApproval(_) =>
        ToolAuthorization.Deferred("Awaiting host confirmation")

object CheckpointPermissionAuthorizer:
  def apply(
      currentPolicy: UIO[PermissionEngine],
      toolGate: PermissionGate = PermissionGate.abstain,
      interceptorGate: PermissionGate = PermissionGate.abstain
  ): CheckpointPermissionAuthorizer =
    new CheckpointPermissionAuthorizer(currentPolicy, toolGate, interceptorGate)
