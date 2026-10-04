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

import com.weisgnosis.agents.core.agent.{AgentConfig, AgentResult, ModelInputTransform, ReActAgent}
import com.weisgnosis.agents.core.client.{LlmClient, LlmConfig}
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.model.Msg
import com.weisgnosis.agents.core.skill.{SkillCatalog, SkillLibrary}
import com.weisgnosis.agents.core.tool.{
  AdmittedToolAccess,
  ToolAuthorization,
  ToolAuthorizationRequest,
  ToolAuthorizer,
  ToolEventPort,
  ToolGroups,
  ToolGroupScope,
  Toolkit
}
import com.weisgnosis.agents.harness.permission.{PermissionBehavior, PermissionEngine}
import zio.*
import zio.json.ast.Json

/** Builds fresh child runtimes only after applying their final static tool boundary. */
final class SubagentFactory private (
    config: AgentConfig,
    model: LlmConfig,
    inherited: Toolkit,
    val boundary: SubagentToolBoundary,
    prepare: (
        SubagentSpawner[AgentResult],
        SubagentToolBoundary,
        Chunk[UIO[PermissionEngine]]
    ) => ZIO[Scope, AgentError, SubagentFactory.Prepared],
    client: SubagentSpawner[AgentResult] => ZIO[Scope, AgentError, LlmClient],
    parentDenials: Chunk[UIO[PermissionEngine]],
    skills: SkillLibrary,
    toolGroups: Option[ToolGroups]
):
  def narrowed(child: SubagentToolBoundary): SubagentFactory =
    new SubagentFactory(
      config,
      model,
      inherited,
      boundary.narrow(child),
      prepare,
      client,
      parentDenials,
      skills,
      toolGroups
    )

  /**
   * Applies live parent denials before child authorization and again to its approved input.
   * Parent Allow/Ask decisions and approval authority are never inherited. The final policy read
   * is a decision snapshot, not atomic execution revocation or durable historical deny accumulation.
   * Confirmed child rules remain installed even if this final parent check denies execution.
   */
  def inheritingParentDenials(readParentPolicy: UIO[PermissionEngine]): SubagentFactory =
    new SubagentFactory(
      config,
      model,
      inherited,
      boundary,
      prepare,
      client,
      parentDenials :+ readParentPolicy,
      skills,
      toolGroups
    )

  /** The caller must retain this Scope for the returned runtime's resource lifetime. */
  def build(child: SubagentSpawner[AgentResult]): ZIO[Scope, AgentError, ReActAgent] =
    for
      _ <- ZIO.fromEither(
        SubagentFactory.validateRuntimeControls(boundary, skills.catalog, toolGroups)
      )
      prepared <- ZIO.suspendSucceed(prepare(child, boundary, parentDenials))
      toolkit  <- ZIO.fromEither(boundary.assemble(inherited, prepared.toolkit))
      _ <- ZIO.fromEither(
        SubagentFactory.checkControlCollisions(toolkit, skills.catalog, toolGroups)
      )
      llm <- ZIO.suspendSucceed(client(child))
      runtime <- ReActAgent.make(
        config = config,
        toolkit = toolkit,
        client = llm,
        llmConfig = model,
        toolAuthorizer =
          parentDenials.foldLeft(prepared.authorizer)(SubagentFactory.inheritDenials),
        modelInputTransform = prepared.modelInputTransform,
        skills = skills,
        toolGroups = toolGroups
      )
    yield runtime

  def spawn(
      parent: SubagentSpawner[AgentResult],
      input: Msg
  ): ZIO[Scope, AgentError, TaskAdmission[AgentResult]] =
    parent.spawn(child => build(child).flatMap(_.run(input)))

  private[subagent] def spawn(
      parent: SubagentSpawner[AgentResult],
      input: Msg,
      id: SubagentTaskId,
      events: Option[ToolEventPort]
  ): ZIO[Scope, AgentError, TaskAdmission[AgentResult]] =
    parent.spawn(child =>
      build(child).flatMap(runtime => SubagentFactory.run(runtime, input, id, events))
    )

object SubagentFactory:
  final private[subagent] case class Prepared(
      toolkit: Toolkit,
      authorizer: ToolAuthorizer,
      modelInputTransform: ModelInputTransform = ModelInputTransform.identity
  )

  private[subagent] def run(
      runtime: ReActAgent,
      input: Msg,
      id: SubagentTaskId,
      events: Option[ToolEventPort]
  ): ZIO[Scope, AgentError, AgentResult] = events match
    case None              => runtime.run(input)
    case Some(destination) => runtime.runForwarding(input, destination, id.value.toString)

  def make(
      config: AgentConfig,
      model: LlmConfig,
      inherited: Toolkit,
      parentBoundary: SubagentToolBoundary,
      childBoundary: SubagentToolBoundary,
      register: SubagentSpawner[AgentResult] => ZIO[Scope, AgentError, Toolkit],
      client: SubagentSpawner[AgentResult] => ZIO[Scope, AgentError, LlmClient],
      authorizer: ToolAuthorizer,
      skills: SkillCatalog | SkillLibrary = SkillCatalog.empty,
      toolGroups: Option[ToolGroups] = None
  ): Either[AgentError, SubagentFactory] =
    configured(
      config,
      model,
      inherited,
      parentBoundary.narrow(childBoundary),
      (child, _, _) =>
        ZIO.suspendSucceed(register(child)).map(toolkit => Prepared(toolkit, authorizer)),
      client,
      Chunk.empty,
      skills,
      toolGroups
    )

  private[subagent] def configured(
      config: AgentConfig,
      model: LlmConfig,
      inherited: Toolkit,
      boundary: SubagentToolBoundary,
      prepare: (
          SubagentSpawner[AgentResult],
          SubagentToolBoundary,
          Chunk[UIO[PermissionEngine]]
      ) => ZIO[Scope, AgentError, Prepared],
      client: SubagentSpawner[AgentResult] => ZIO[Scope, AgentError, LlmClient],
      parentDenials: Chunk[UIO[PermissionEngine]],
      skills: SkillCatalog | SkillLibrary = SkillCatalog.empty,
      toolGroups: Option[ToolGroups] = None
  ): Either[AgentError, SubagentFactory] =
    for
      valid <- config.validated
      library = SkillLibrary.from(skills)
      _ <- validateRuntimeControls(boundary, library.catalog, toolGroups)
    yield new SubagentFactory(
      valid,
      model,
      inherited,
      boundary,
      prepare,
      client,
      parentDenials,
      library,
      toolGroups
    )

  private def runtimeControlNames(skills: SkillCatalog, groups: Option[ToolGroups]): Chunk[String] =
    val loader = if skills.isEmpty then Chunk.empty else Chunk(SkillCatalog.LoadToolName)
    val reset =
      if groups.exists(_.groups.exists(_.scope == ToolGroupScope.Meta)) then
        Chunk("reset_equipped_tools")
      else Chunk.empty
    loader ++ reset

  private def validateRuntimeControls(
      boundary: SubagentToolBoundary,
      skills: SkillCatalog,
      groups: Option[ToolGroups]
  ): Either[AgentError, Unit] =
    for
      _ <- skills.validateGroups(groups.getOrElse(ToolGroups.empty))
      _ <- validateRequiredControls(boundary, runtimeControlNames(skills, groups))
    yield ()

  private[subagent] def validateRequiredControls(
      boundary: SubagentToolBoundary,
      names: Chunk[String]
  ): Either[AgentError, Unit] =
    names.foldLeft[Either[AgentError, Unit]](Right(()))((checked, name) =>
      checked.flatMap(_ =>
        Either.cond(
          boundary.selection.contains(name) && !boundary.denied.contains(name),
          (),
          AgentError.InvalidConfiguration(
            s"Required child runtime control '$name' is excluded by the tool boundary"
          )
        )
      )
    )

  private def checkControlCollisions(
      toolkit: Toolkit,
      skills: SkillCatalog,
      groups: Option[ToolGroups]
  ): Either[AgentError, Unit] =
    runtimeControlNames(skills, groups).foldLeft[Either[AgentError, Unit]](Right(()))(
      (checked, name) =>
        checked.flatMap(_ =>
          Either.cond(
            !toolkit.contains(name),
            (),
            AgentError.ToolExecutionError(name, s"Tool collision: '$name' is already registered")
          )
        )
    )

  private def inheritDenials(
      authorizer: ToolAuthorizer,
      readParentPolicy: UIO[PermissionEngine]
  ): ToolAuthorizer =
    new ToolAuthorizer:
      def authorize(
          request: ToolAuthorizationRequest
      ): ZIO[Scope, AgentError.PermissionDenied, ToolAuthorization] = authorize(request, None)

      override def authorize(
          request: ToolAuthorizationRequest,
          admittedAccess: Option[AdmittedToolAccess]
      ): ZIO[Scope, AgentError.PermissionDenied, ToolAuthorization] =
        def parentDenies(input: Json): UIO[Boolean] =
          ZIO
            .suspendSucceed(readParentPolicy)
            .map(_.evaluate(request.call.name, input) == PermissionBehavior.Deny)

        parentDenies(request.call.input).flatMap:
          case true =>
            ZIO.succeed(ToolAuthorization.Denied("Tool execution denied by parent policy"))
          case false =>
            ZIO
              .suspendSucceed(authorizer.authorize(request, admittedAccess))
              .flatMap:
                case approved @ ToolAuthorization.Approved(input) =>
                  parentDenies(input).map:
                    case true =>
                      ToolAuthorization.Denied("Approved input is denied by parent policy")
                    case false => approved
                case denied: ToolAuthorization.Denied => ZIO.succeed(denied)
