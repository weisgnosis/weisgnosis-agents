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
import zio.*
import zio.json.ast.Json

/** A safety gate inspects authoritative arguments, never cached raw content or model control metadata. */
trait PermissionGate:
  def evaluate(
      toolName: String,
      input: Json
  ): ZIO[Scope, AgentError.PermissionDenied, Option[PermissionBehavior]]

  /**
   * Evaluate both votes in separate scopes, including after a Deny vote; None abstains.
   * Failed evaluation or cleanup stops composition and preserves its cause.
   */
  def and(that: PermissionGate): PermissionGate =
    PermissionGate: (name, input) =>
      for
        first  <- ZIO.scoped(ZIO.suspendSucceed(evaluate(name, input)))
        second <- ZIO.scoped(ZIO.suspendSucceed(that.evaluate(name, input)))
      yield (first, second) match
        case (Some(left), Some(right)) => Some(PermissionBehavior.combine(left, right))
        case (Some(left), None)        => Some(left)
        case (None, Some(right))       => Some(right)
        case (None, None)              => None

object PermissionGate:
  val abstain: PermissionGate = PermissionGate((_, _) => ZIO.none)

  def apply(
      check: (String, Json) => ZIO[Scope, AgentError.PermissionDenied, Option[PermissionBehavior]]
  ): PermissionGate = new PermissionGate:
    def evaluate(
        toolName: String,
        input: Json
    ): ZIO[Scope, AgentError.PermissionDenied, Option[PermissionBehavior]] =
      ZIO.suspendSucceed(check(toolName, input))
