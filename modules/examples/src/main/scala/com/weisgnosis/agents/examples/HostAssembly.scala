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
package com.weisgnosis.agents.examples

import com.weisgnosis.agents.core.agent.ReActAgent
import com.weisgnosis.agents.core.client.{LlmClient, LlmConfig}
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.model.Msg
import com.weisgnosis.agents.core.tool.{ToolAuthorizer, ToolDef, ToolGroups, Toolkit}
import com.weisgnosis.agents.harness.eviction.{EvictionConfig, ToolResultEvictor, ToolResultStore}
import com.weisgnosis.agents.harness.memory.{CompactorConfig, ConversationCompactor}
import com.weisgnosis.agents.harness.transcript.{
  AnonymousTranscriptPolicy,
  SessionTranscriptWriter,
  TranscriptStore
}
import com.weisgnosis.agents.mcp.client.{McpClient, McpClientManager}
import com.weisgnosis.agents.mcp.protocol.McpToolDefinition
import zio.*

/** Example-owned handles remain usable only inside the Scope supplied to open. */
final case class HostAssembly(agent: ReActAgent, clients: McpClientManager)

object HostAssembly:
  final case class Settings(
      name: String,
      model: LlmConfig,
      compaction: CompactorConfig,
      eviction: EvictionConfig,
      transcriptPolicy: AnonymousTranscriptPolicy,
      groups: ToolGroups = ToolGroups.empty
  )

  /** The host must supply a local read_file tool capable of retrieving persisted preview paths. */
  final case class Dependencies(
      model: LlmClient,
      authorizer: ToolAuthorizer,
      results: ToolResultStore,
      transcript: TranscriptStore,
      summarize: Chunk[Msg] => IO[AgentError, String],
      localTools: Toolkit
  )

  /** Remote annotations do not grant authority; the host explicitly selects each local definition. */
  final case class Registration(
      name: String,
      acquire: ZIO[Scope, AgentError, McpClient],
      maxPages: Int,
      select: McpToolDefinition => Either[AgentError, Option[ToolDef]]
  )

  /** A failed or interrupted assembly joins all acquired MCP cleanup before returning. */
  def open(
      settings: Settings,
      dependencies: Dependencies,
      registrations: Chunk[Registration]
  ): ZIO[Scope, AgentError, HostAssembly] =
    ZIO
      .acquireReleaseExit(Scope.make)((owned, exit) => owned.close(exit))
      .flatMap: owned =>
        owned
          .extend(assemble(settings, dependencies, registrations))
          .onExit:
            case Exit.Success(_)          => ZIO.unit
            case failed @ Exit.Failure(_) => owned.close(failed)

  private def assemble(
      settings: Settings,
      dependencies: Dependencies,
      registrations: Chunk[Registration]
  ): ZIO[Scope, AgentError, HostAssembly] =
    for
      manager <- McpClientManager.make(groups = settings.groups)
      _ <- ZIO.foreachDiscard(registrations)(registration =>
        manager.register(
          registration.name,
          registration.acquire,
          registration.maxPages,
          registration.select
        )
      )
      installation <- manager.installation
      toolkit      <- ZIO.fromEither(dependencies.localTools ++ installation.toolkit)
      writer  = new SessionTranscriptWriter(dependencies.transcript)
      evictor = new ToolResultEvictor(dependencies.results, settings.name)
      compactor = new ConversationCompactor(
        dependencies.summarize,
        settings.compaction,
        offload = writer.offload
      )
      agent <- ReActAgent
        .builder()
        .name(settings.name)
        .model(dependencies.model)
        .llmConfig(settings.model)
        .maxIterations(3)
        .timeout(1.minute)
        .toolkit(toolkit)
        .toolGroups(installation.groups)
        .toolAuthorizer(dependencies.authorizer)
        .historyPreparer(evictor.preparer(settings.eviction).andThen(compactor.preparer))
        .toolResultProcessor(evictor.processor(settings.eviction))
        .completionObserver(writer.completionObserver(settings.transcriptPolicy))
        .build
    yield HostAssembly(agent, manager)
