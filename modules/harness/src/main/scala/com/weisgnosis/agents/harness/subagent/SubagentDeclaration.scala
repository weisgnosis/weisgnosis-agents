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
import com.weisgnosis.agents.core.tool.Toolkit
import zio.*
import zio.json.*

/**
 * Inline, local child configuration. Omitted overrides inherit host configuration; endpoints,
 * credentials and live capabilities are not declaration data. The derived codec describes data,
 * not validation: factory validates before retaining any scoped installation callbacks.
 */
final case class SubagentDeclaration(
    name: String,
    description: String,
    systemPrompt: String,
    tools: SubagentToolSelection = SubagentToolSelection.AllAvailable,
    model: Option[String] = None,
    temperature: Option[Double] = None,
    maxIterations: Option[Int] = None,
    skills: SubagentSkillSelection = SubagentSkillSelection.AllAvailable
) derives JsonCodec:
  def validated: Either[AgentError, SubagentDeclaration] =
    val validTools = tools match
      case SubagentToolSelection.AllAvailable => true
      case SubagentToolSelection.Only(names)  => names.forall(!_.isBlank)
    val validSkills = skills match
      case SubagentSkillSelection.AllAvailable => true
      case SubagentSkillSelection.Only(ids)    => ids.forall(!_.isBlank)
    if name.isBlank || description.isBlank then
      Left(AgentError.InvalidConfiguration("Subagent name and description must be nonblank"))
    else if model.exists(_.isBlank) then
      Left(AgentError.InvalidConfiguration("Subagent model override must be nonblank"))
    else if temperature.exists(value => !value.isFinite) then
      Left(AgentError.InvalidConfiguration("Subagent temperature must be finite"))
    else if maxIterations.exists(_ <= 0) then
      Left(AgentError.InvalidConfiguration("Subagent maxIterations must be positive"))
    else if !validTools then
      Left(AgentError.InvalidConfiguration("Subagent selected tool names must be nonblank"))
    else if !validSkills then
      Left(AgentError.InvalidConfiguration("Subagent selected skill IDs must be nonblank"))
    else Right(this)

  /**
   * Pure composition through the supplied descendant capability. Installation remains lazy and
   * scoped per build; Plan tools and reviewers are host dependencies. The declaration can narrow
   * tool and skill sets but cannot discard ancestor static, Deny or Plan restrictions. It creates
   * no session or directory membership and does not resolve model names through an external registry.
   */
  def factory(
      construction: SubagentConstruction,
      parentConfig: AgentConfig,
      parentModel: LlmConfig,
      inherited: Toolkit,
      initialize: ZIO[Scope, AgentError, SubagentPermissions],
      register: (SubagentConstruction, SubagentPermissions) => ZIO[Scope, AgentError, Toolkit],
      client: SubagentSpawner[AgentResult] => ZIO[Scope, AgentError, LlmClient]
  ): Either[AgentError, SubagentFactory] =
    validated.flatMap: declaration =>
      construction.factory(
        parentConfig.copy(
          name = declaration.name,
          description = declaration.description,
          systemPrompt = SubagentDeclaration.prompt(declaration.systemPrompt),
          maxIterations = declaration.maxIterations.getOrElse(parentConfig.maxIterations)
        ),
        parentModel.copy(
          model = declaration.model.getOrElse(parentModel.model),
          temperature = declaration.temperature.getOrElse(parentModel.temperature)
        ),
        inherited,
        SubagentToolBoundary(declaration.tools),
        initialize,
        register,
        client,
        declaration.skills
      )

object SubagentDeclaration:
  /** Explicit opt-in preset; compiling it installs nothing in the parent agent. */
  val generalPurpose: SubagentDeclaration = SubagentDeclaration(
    "general-purpose",
    "General-purpose subagent for delegated tasks",
    "You are a capable general-purpose subagent."
  )

  private def prompt(body: String): String =
    val role = "Work on the delegated task and return your findings to the delegating agent."
    if body.isBlank then role else body.stripTrailing() + "\n\n" + role
