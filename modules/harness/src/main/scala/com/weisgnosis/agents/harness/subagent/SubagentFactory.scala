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
import com.weisgnosis.agents.core.tool.{ToolAuthorizer, Toolkit}
import zio.*

/** Builds fresh child runtimes only after applying their final static tool boundary. */
final class SubagentFactory private (
    config: AgentConfig,
    model: LlmConfig,
    inherited: Toolkit,
    val boundary: SubagentToolBoundary,
    register: SubagentSpawner[AgentResult] => ZIO[Scope, AgentError, Toolkit],
    client: SubagentSpawner[AgentResult] => ZIO[Scope, AgentError, LlmClient],
    authorizer: ToolAuthorizer
):
  def narrowed(child: SubagentToolBoundary): SubagentFactory =
    new SubagentFactory(
      config,
      model,
      inherited,
      boundary.narrow(child),
      register,
      client,
      authorizer
    )

  /** The caller must retain this Scope for the returned runtime's resource lifetime. */
  def build(child: SubagentSpawner[AgentResult]): ZIO[Scope, AgentError, ReActAgent] =
    for
      additions <- ZIO.suspendSucceed(register(child))
      toolkit   <- ZIO.fromEither(boundary.assemble(inherited, additions))
      llm       <- ZIO.suspendSucceed(client(child))
      runtime <- ReActAgent.make(
        config = config,
        toolkit = toolkit,
        client = llm,
        llmConfig = model,
        toolAuthorizer = authorizer
      )
    yield runtime

  def spawn(
      parent: SubagentSpawner[AgentResult],
      input: Msg
  ): ZIO[Scope, AgentError, TaskAdmission[AgentResult]] =
    parent.spawn(child => build(child).flatMap(_.run(input)))

object SubagentFactory:
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
    config.validated.map(valid =>
      new SubagentFactory(
        valid,
        model,
        inherited,
        parentBoundary.narrow(childBoundary),
        register,
        client,
        authorizer
      )
    )
