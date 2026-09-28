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

import com.weisgnosis.agents.core.model.ContentBlock
import zio.Chunk
import zio.json.ast.Json

/** Immutable policy state; evaluation and durable resumption never invoke the model. */
final case class PermissionEngine(rules: Chunk[PermissionRule] = Chunk.empty):
  def evaluate(
      toolName: String,
      input: Json,
      toolGate: Option[PermissionBehavior] = None,
      interceptorGate: Option[PermissionBehavior] = None
  ): PermissionBehavior =
    val decisions = rules.filter(_.matches(toolName, input)).map(_.behavior) ++
      Chunk.fromIterable(toolGate) ++ Chunk.fromIterable(interceptorGate)
    decisions.reduceOption(PermissionBehavior.combine).getOrElse(PermissionBehavior.Ask)

  def authorize(
      requestId: String,
      call: ContentBlock.ToolUse,
      toolGate: Option[PermissionBehavior] = None,
      interceptorGate: Option[PermissionBehavior] = None
  ): PermissionOutcome =
    evaluate(call.name, call.input, toolGate, interceptorGate) match
      case PermissionBehavior.Allow => PermissionOutcome.Approved(call)
      case PermissionBehavior.Deny =>
        PermissionOutcome.Denied(call.name, "Tool execution denied by policy")
      case PermissionBehavior.Ask | PermissionBehavior.Passthrough =>
        PermissionOutcome.AwaitingApproval(PendingApproval(requestId, call))

  def resume(
      pending: PendingApproval,
      confirmation: ConfirmResult
  ): (PermissionEngine, PermissionOutcome) =
    if !confirmation.confirmed then
      (
        this,
        PermissionOutcome.Denied(
          pending.call.name,
          confirmation.reason.getOrElse("Tool execution declined")
        )
      )
    else
      val updated = copy(rules = rules ++ confirmation.newRules)
      val call = pending.call.copy(input = confirmation.modifiedInput.getOrElse(pending.call.input))
      if updated.rules.exists(rule =>
          rule.behavior == PermissionBehavior.Deny && rule.matches(call.name, call.input)
        )
      then (updated, PermissionOutcome.Denied(call.name, "Confirmed input is denied by policy"))
      else (updated, PermissionOutcome.Approved(call))
