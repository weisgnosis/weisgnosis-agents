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
import zio.json.*
import zio.json.ast.Json

/** Explicit host-declared worker identity; never inferred from task IDs or display names. */
opaque type SubagentWorkerId = String

object SubagentWorkerId:
  val MaxUtf8Bytes: Int = 1024

  def from(value: String): Either[AgentError.InvalidConfiguration, SubagentWorkerId] =
    Either.cond(
      !value.isBlank && JsonBoundary.validUnicode(Json.Str(value)) &&
        value.getBytes(StandardCharsets.UTF_8).length <= MaxUtf8Bytes,
      value,
      AgentError.InvalidConfiguration(
        "Worker identity must be nonblank valid Unicode within 1024 UTF-8 bytes"
      )
    )

  given JsonCodec[SubagentWorkerId] = JsonCodec.string.transformOrFail(
    value => from(value).left.map(_.message),
    identity => identity
  )

  extension (identity: SubagentWorkerId) def value: String = identity
