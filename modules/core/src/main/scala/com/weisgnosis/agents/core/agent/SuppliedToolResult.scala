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
package com.weisgnosis.agents.core.agent

import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.json.JsonBoundary
import com.weisgnosis.agents.core.model.ToolResultState
import com.weisgnosis.agents.core.tool.ToolExecutionResult
import zio.json.*

/** A host-supplied execution observation, bound to an original tool-use ordinal, not a call ID. */
final case class SuppliedToolResult(callOrdinal: Int, result: ToolExecutionResult) derives JsonCodec

object SuppliedToolResult:
  private[agents] def validate(
      value: SuppliedToolResult
  ): Either[AgentError.InvalidConfiguration, Unit] =
    for
      _ <- Either.cond(
        value.callOrdinal >= 0 &&
          (value.result.state == ToolResultState.Success || value.result.state == ToolResultState.Error),
        (),
        AgentError.InvalidConfiguration(
          "Supplied results require a nonnegative ordinal and Success or Error state"
        )
      )
      _ <- value.result.toJsonAST
        .flatMap(JsonBoundary.validate)
        .left
        .map(_ => AgentError.InvalidConfiguration("Supplied result contains invalid JSON data"))
    yield ()
