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

import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.json.JsonBoundary
import java.nio.charset.StandardCharsets
import zio.Chunk
import zio.json.ast.Json

/** Descriptive host-selected identities, never execution, routing or approval authority. */
final class SubagentLifecycleMetadata private (
    val parentSessionId: String,
    val childSessionId: String,
    val agentId: String,
    val label: Option[String]
)

object SubagentLifecycleMetadata:
  def from(
      parentSessionId: String,
      childSessionId: String,
      agentId: String,
      label: Option[String] = None
  ): Either[AgentError.InvalidConfiguration, SubagentLifecycleMetadata] =
    val values = Chunk(parentSessionId, childSessionId, agentId) ++ Chunk.fromIterable(label)
    Either.cond(
      values.forall(value =>
        !value.isBlank &&
          value.getBytes(StandardCharsets.UTF_8).length <= 1024
      ) &&
        JsonBoundary.validUnicode(Json.Arr(values.map(Json.Str(_)))),
      new SubagentLifecycleMetadata(parentSessionId, childSessionId, agentId, label),
      AgentError.InvalidConfiguration(
        "Subagent lifecycle identities must be nonblank valid Unicode, at most 1024 UTF-8 bytes each"
      )
    )

  def fromNamed(
      key: SubagentNamedSessionKey,
      id: SubagentSessionId
  ): Either[AgentError.InvalidConfiguration, SubagentLifecycleMetadata] =
    from(key.parentSessionId, id.value.toString, key.agentId, Some(key.label))

/** Named-session emission is opt-in; the actual registered session ID is bound before publication. */
enum SubagentLifecycleEvents:
  case Disabled, Enabled
