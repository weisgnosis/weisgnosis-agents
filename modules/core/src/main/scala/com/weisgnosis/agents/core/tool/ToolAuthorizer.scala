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
package com.weisgnosis.agents.core.tool

import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.event.ModelStreamContext
import com.weisgnosis.agents.core.model.ContentBlock
import scala.annotation.unused
import zio.*
import zio.json.*
import zio.json.ast.Json

/** Runtime-assigned identity distinguishes repeated call IDs and concurrent approval requests. */
final case class ToolAuthorizationRequest(
    requestId: String,
    context: ModelStreamContext,
    call: ContentBlock.ToolUse,
    callOrdinal: Option[Int] = None
) derives JsonCodec

enum ToolAuthorization derives JsonCodec:
  case Approved(input: Json)
  case Denied(reason: String)
  case Deferred(reason: String)

/** Transient access metadata from the admitted host tool, never from request JSON. */
enum AdmittedToolAccess:
  case ReadOnly, Mutating

/** A scoped decision before local execution; call identity and execution remain runtime-owned. */
trait ToolAuthorizer:
  def authorize(
      request: ToolAuthorizationRequest
  ): ZIO[Scope, AgentError.PermissionDenied, ToolAuthorization]

  /** Existing implementations keep their single-argument decision unless they opt into this view. */
  def authorize(
      request: ToolAuthorizationRequest,
      @unused admittedAccess: Option[AdmittedToolAccess]
  ): ZIO[Scope, AgentError.PermissionDenied, ToolAuthorization] = authorize(request)

object ToolAuthorizer:
  val allow: ToolAuthorizer =
    ToolAuthorizer(request => ZIO.succeed(ToolAuthorization.Approved(request.call.input)))

  def apply(
      decide: ToolAuthorizationRequest => ZIO[Scope, AgentError.PermissionDenied, ToolAuthorization]
  ): ToolAuthorizer = new ToolAuthorizer:
    def authorize(
        request: ToolAuthorizationRequest
    ): ZIO[Scope, AgentError.PermissionDenied, ToolAuthorization] =
      ZIO.suspendSucceed(decide(request))
