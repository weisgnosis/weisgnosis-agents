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

import com.weisgnosis.agents.core.agent.{AgentConfig, AgentResult, ReActAgent}
import com.weisgnosis.agents.core.client.{LlmClient, LlmConfig}
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.model.Msg
import com.weisgnosis.agents.core.tool.{ToolAuthorization, ToolAuthorizer, ToolEventPort, Toolkit}
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
    ) => ZIO[Scope, AgentError, (Toolkit, ToolAuthorizer)],
    client: SubagentSpawner[AgentResult] => ZIO[Scope, AgentError, LlmClient],
    parentDenials: Chunk[UIO[PermissionEngine]]
):
  def narrowed(child: SubagentToolBoundary): SubagentFactory =
    new SubagentFactory(
      config,
      model,
      inherited,
      boundary.narrow(child),
      prepare,
      client,
      parentDenials
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
      parentDenials :+ readParentPolicy
    )

  /** The caller must retain this Scope for the returned runtime's resource lifetime. */
  def build(child: SubagentSpawner[AgentResult]): ZIO[Scope, AgentError, ReActAgent] =
    for
      prepared <- ZIO.suspendSucceed(prepare(child, boundary, parentDenials))
      (additions, authorizer) = prepared
      toolkit <- ZIO.fromEither(boundary.assemble(inherited, additions))
      llm     <- ZIO.suspendSucceed(client(child))
      runtime <- ReActAgent.make(
        config = config,
        toolkit = toolkit,
        client = llm,
        llmConfig = model,
        toolAuthorizer = parentDenials.foldLeft(authorizer)(SubagentFactory.inheritDenials)
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
      authorizer: ToolAuthorizer
  ): Either[AgentError, SubagentFactory] =
    configured(
      config,
      model,
      inherited,
      parentBoundary.narrow(childBoundary),
      (child, _, _) => ZIO.suspendSucceed(register(child)).map(toolkit => (toolkit, authorizer)),
      client,
      Chunk.empty
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
      ) => ZIO[Scope, AgentError, (Toolkit, ToolAuthorizer)],
      client: SubagentSpawner[AgentResult] => ZIO[Scope, AgentError, LlmClient],
      parentDenials: Chunk[UIO[PermissionEngine]]
  ): Either[AgentError, SubagentFactory] =
    config.validated.map(valid =>
      new SubagentFactory(valid, model, inherited, boundary, prepare, client, parentDenials)
    )

  private def inheritDenials(
      authorizer: ToolAuthorizer,
      readParentPolicy: UIO[PermissionEngine]
  ): ToolAuthorizer =
    ToolAuthorizer: request =>
      def parentDenies(input: Json): UIO[Boolean] =
        ZIO
          .suspendSucceed(readParentPolicy)
          .map(_.evaluate(request.call.name, input) == PermissionBehavior.Deny)

      parentDenies(request.call.input).flatMap:
        case true => ZIO.succeed(ToolAuthorization.Denied("Tool execution denied by parent policy"))
        case false =>
          ZIO
            .suspendSucceed(authorizer.authorize(request))
            .flatMap:
              case approved @ ToolAuthorization.Approved(input) =>
                parentDenies(input).map:
                  case true => ToolAuthorization.Denied("Approved input is denied by parent policy")
                  case false => approved
              case denied: ToolAuthorization.Denied => ZIO.succeed(denied)
