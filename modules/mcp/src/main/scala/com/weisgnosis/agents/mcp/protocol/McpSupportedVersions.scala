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
package com.weisgnosis.agents.mcp.protocol

import com.weisgnosis.agents.core.error.AgentError

/** Explicit supported revisions; membership does not imply an ordering or automatic fallback. */
final class McpSupportedVersions private (val versions: Set[McpProtocolVersion])

object McpSupportedVersions:
  def make(
      versions: Set[McpProtocolVersion]
  ): Either[AgentError.InvalidConfiguration, McpSupportedVersions] =
    Either.cond(
      versions.nonEmpty,
      new McpSupportedVersions(versions),
      AgentError.InvalidConfiguration("MCP supported revisions must be nonempty")
    )
