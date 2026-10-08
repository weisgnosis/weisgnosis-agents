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
import com.weisgnosis.agents.core.json.JsonBoundary
import com.weisgnosis.agents.core.tool.ToolAuthorizationRequest
import zio.*
import zio.json.*
import zio.json.ast.Json

/** Host presentation data, never a permission vote or evidence of human confirmation. */
final case class PermissionAdvisory(
    request: ToolAuthorizationRequest,
    suggestedRules: Chunk[PermissionRule]
) derives JsonCodec

object PermissionAdvisory:
  private[permission] def prepare(
      request: ToolAuthorizationRequest,
      suggestions: PermissionSuggestions
  ): IO[AgentError.PermissionDenied, PermissionAdvisory] =
    ZIO
      .scoped(ZIO.suspendSucceed(suggestions.suggest(request)))
      .map(PermissionAdvisory(request, _))

/** Trusted host advice; callbacks inspect current prepared input and must bound their own work. */
trait PermissionSuggestions:
  def suggest(
      request: ToolAuthorizationRequest
  ): ZIO[Scope, AgentError.PermissionDenied, Chunk[PermissionRule]]

object PermissionSuggestions:
  /** A literal '*' would be a wildcard policy, so ambiguous names receive no default advice. */
  val default: PermissionSuggestions = PermissionSuggestions: request =>
    val name = request.call.name
    ZIO.succeed(
      if name == "*" || name.isBlank || !JsonBoundary.validUnicode(Json.Str(name)) then Chunk.empty
      else Chunk(PermissionRule(name, PermissionBehavior.Allow, source = "suggested"))
    )

  val none: PermissionSuggestions = PermissionSuggestions(_ => ZIO.succeed(Chunk.empty))

  def apply(
      callback: ToolAuthorizationRequest => ZIO[Scope, AgentError.PermissionDenied, Chunk[
        PermissionRule
      ]]
  ): PermissionSuggestions = new PermissionSuggestions:
    def suggest(
        request: ToolAuthorizationRequest
    ): ZIO[Scope, AgentError.PermissionDenied, Chunk[PermissionRule]] =
      ZIO.suspendSucceed(callback(request))
