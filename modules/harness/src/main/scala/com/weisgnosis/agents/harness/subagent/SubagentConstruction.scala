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

import com.weisgnosis.agents.core.agent.{AgentConfig, AgentResult}
import com.weisgnosis.agents.core.client.{LlmClient, LlmConfig}
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.skill.SkillCatalog
import com.weisgnosis.agents.core.tool.{Toolkit, ToolGroups}
import com.weisgnosis.agents.harness.permission.{InteractivePermissionAuthorizer, PermissionEngine}
import com.weisgnosis.agents.harness.plan.{
  PlanMode,
  PlanPermissionGate,
  PlanSessionControl,
  PlanToolAccess
}
import zio.*
import zio.json.JsonCodec

/** Explicit host classifications remain the default; admitted-tool fallback requires opt-in. */
enum SubagentPlanAccess:
  case ExplicitAccess, AdmittedTools

/** Selection can narrow only the explicit parent catalogue; an empty Only set selects no skills. */
enum SubagentSkillSelection derives JsonCodec:
  case AllAvailable
  case Only(ids: Set[String])

/** Live child capabilities, without codecs or parent approval authority. */
final case class SubagentPermissions(
    authorizer: InteractivePermissionAuthorizer,
    plan: PlanSessionControl,
    access: Map[String, PlanToolAccess],
    planAccess: SubagentPlanAccess = SubagentPlanAccess.ExplicitAccess
)

/**
 * Descendant construction carrying live ancestor restrictions. Raw spawners and factories used
 * under another parent remain trusted host capabilities, outside this automatic construction path.
 * Effective Plan is the live OR of ancestor and child modes: ancestor Build removes only the
 * ancestor restriction, never the child's own Plan state. Neither Plan control is mutated.
 */
final class SubagentConstruction private (
    val spawner: SubagentSpawner[AgentResult],
    boundary: SubagentToolBoundary,
    parentDenials: Chunk[UIO[PermissionEngine]],
    parentMode: UIO[PlanMode],
    skills: SkillCatalog,
    toolGroups: Option[ToolGroups]
):
  /**
   * Initialize child permissions before registration in each build's Scope. Supply a fresh child
   * authorizer and Plan control per build when isolation is required. Registration receives the
   * derived descendant capability; no Plan tools, prompts or reviewer are installed automatically.
   */
  def factory(
      config: AgentConfig,
      model: LlmConfig,
      inherited: Toolkit,
      childBoundary: SubagentToolBoundary,
      initialize: ZIO[Scope, AgentError, SubagentPermissions],
      register: (SubagentConstruction, SubagentPermissions) => ZIO[Scope, AgentError, Toolkit],
      client: SubagentSpawner[AgentResult] => ZIO[Scope, AgentError, LlmClient],
      skillSelection: SubagentSkillSelection = SubagentSkillSelection.AllAvailable
  ): Either[AgentError, SubagentFactory] =
    val selected = skillSelection match
      case SubagentSkillSelection.AllAvailable => Right(skills)
      case SubagentSkillSelection.Only(ids)    => skills.select(ids)
    selected.flatMap: catalog =>
      SubagentFactory.configured(
        config,
        model,
        inherited,
        boundary.narrow(childBoundary),
        (child, effectiveBoundary, denials) =>
          for
            permissions <- ZIO.suspendSucceed(initialize)
            mode = parentMode.zipWith(ZIO.suspendSucceed(permissions.plan.mode)):
              case (PlanMode.Plan, _) | (_, PlanMode.Plan) => PlanMode.Plan
              case (PlanMode.Build, PlanMode.Build)        => PlanMode.Build
            descendants = new SubagentConstruction(
              child,
              effectiveBoundary,
              denials :+ permissions.authorizer.policy,
              mode,
              catalog,
              toolGroups
            )
            additions <- ZIO.suspendSucceed(register(descendants, permissions))
            gate = permissions.planAccess match
              case SubagentPlanAccess.ExplicitAccess =>
                PlanPermissionGate.make(mode, permissions.access)
              case SubagentPlanAccess.AdmittedTools =>
                PlanPermissionGate.withAdmittedTools(mode, permissions.access)
            authorizer = permissions.authorizer.withAdditionalGate(gate)
          yield (additions, authorizer),
        client,
        parentDenials,
        catalog,
        toolGroups
      )

object SubagentConstruction:
  /**
   * Trusted host entry point; construction suspends all policy/mode reads. Only live parent Deny
   * rules and restrictive Plan mode propagate, without copying approval state or accumulating
   * historical policy. Policy reads are decision snapshots, not atomic revocation.
   */
  def root(
      spawner: SubagentSpawner[AgentResult],
      boundary: SubagentToolBoundary,
      parentPolicy: UIO[PermissionEngine],
      parentMode: UIO[PlanMode],
      skills: SkillCatalog = SkillCatalog.empty,
      toolGroups: Option[ToolGroups] = None
  ): SubagentConstruction =
    new SubagentConstruction(spawner, boundary, Chunk(parentPolicy), parentMode, skills, toolGroups)
