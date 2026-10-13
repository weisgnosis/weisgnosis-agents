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
import com.weisgnosis.agents.harness.storage.VersionedJsonStore
import com.weisgnosis.agents.harness.transcript.*
import com.weisgnosis.agents.mcp.client.{McpClient, McpClientManager}
import com.weisgnosis.agents.mcp.protocol.McpToolDefinition
import com.weisgnosis.agents.mcp.tool.McpModelInputTransform
import zio.*

/** Example-owned handles remain usable only inside the Scope supplied to open. */
final case class HostAssembly(agent: ReActAgent, clients: McpClientManager)

object HostAssembly:
  /** Catalog membership supplies a binding, never authorization or execution ownership. */
  enum TranscriptSource:
    case Provided(store: TranscriptStore)
    case Registered(
        backend: VersionedJsonStore,
        catalog: TranscriptSessionCatalog,
        id: TranscriptTargetId,
        limits: TranscriptSegmentLimits
    )

    private[examples] def resolve: IO[AgentError, TranscriptStore] = this match
      case Provided(store) => ZIO.succeed(store)
      case Registered(backend, catalog, id, limits) =>
        catalog.sessions.flatMap: sessions =>
          sessions.filter(_.id == id) match
            case Chunk(session) =>
              ZIO.fromEither(session.layout match
                case TranscriptLayout.Aggregate =>
                  VersionedTranscriptStore.make(backend, session.manifest, limits.maxAttempts)
                case TranscriptLayout.Segmented =>
                  SegmentedTranscriptStore.make(backend, session.manifest, limits))
            case _ =>
              ZIO.fail(
                AgentError.InvalidConfiguration(
                  "Registered transcript selection requires exactly one matching session"
                )
              )

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
      transcript: TranscriptSource,
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
      transcript <- dependencies.transcript.resolve
      manager    <- McpClientManager.make(groups = settings.groups)
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
      writer  = new SessionTranscriptWriter(transcript)
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
        .modelInputTransform(McpModelInputTransform())
        .historyPreparer(evictor.preparer(settings.eviction).andThen(compactor.preparer))
        .toolResultProcessor(evictor.processor(settings.eviction))
        .completionObserver(writer.completionObserver(settings.transcriptPolicy))
        .build
    yield HostAssembly(agent, manager)
