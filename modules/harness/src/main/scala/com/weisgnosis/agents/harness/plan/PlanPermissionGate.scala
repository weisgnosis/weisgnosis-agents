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
package com.weisgnosis.agents.harness.plan

import com.weisgnosis.agents.harness.permission.{PermissionBehavior, PermissionGate}
import zio.*
import zio.json.ast.Json

/** Admission restriction only; mode changes do not revoke already admitted execution. */
object PlanPermissionGate:
  def make(mode: UIO[PlanMode], access: Map[String, PlanToolAccess]): PermissionGate =
    PermissionGate: (name, input) =>
      mode.map:
        case PlanMode.Build => None
        case PlanMode.Plan  => inspect(access.get(name), input)

  private def inspect(access: Option[PlanToolAccess], input: Json): Option[PermissionBehavior] =
    access match
      case Some(PlanToolAccess.ReadOnly | PlanToolAccess.PlanControl) => None
      case Some(PlanToolAccess.RequestExecution) => Some(PermissionBehavior.Ask)
      case Some(PlanToolAccess.PosixReadCommand) =>
        PlanShellInspection.inspectInput(input).fold(_ => Some(PermissionBehavior.Deny), _ => None)
      case Some(PlanToolAccess.Mutation) | None => Some(PermissionBehavior.Deny)
