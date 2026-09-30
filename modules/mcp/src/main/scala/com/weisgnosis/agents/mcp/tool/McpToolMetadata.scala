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
package com.weisgnosis.agents.mcp.tool

import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.tool.ToolDef
import zio.IO
import zio.json.ast.Json

/** Correlation only: callId originates in model output; no arguments or runtime capabilities escape. */
enum McpToolIdentity:
  case Detached
  case Active(replyId: String, requestId: String, callId: String, callOrdinal: Int)

/** A live restriction on bound-tool propagation, not on explicit client metadata parameters. */
enum McpToolMetadataPropagation:
  case Allow, Omit

/**
 * Fixed host binding policy. Resolvers run only when both the binding and the invocation's client
 * restriction snapshot allow propagation. A later restriction change does not cancel that resolver.
 */
enum McpToolMetadata:
  case Omit
  case Propagate(resolve: McpToolIdentity => IO[AgentError, Json.Obj])

final case class McpToolBinding(
    definition: ToolDef,
    metadata: McpToolMetadata = McpToolMetadata.Omit
)
