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
import zio.*
import zio.json.*

/**
 * Policy controlling tool concurrency during the acting phase.
 */
enum ToolExecution derives JsonCodec:
  case Sequential
  case Parallel

/**
 * Execution parameters and behavioral bounds for a ReActAgent.
 */
final case class AgentConfig(
    name: String = "assistant",
    description: String = "",
    systemPrompt: String = "",
    maxIterations: Int = 20,
    timeout: Duration = 5.minutes,
    emptyThinkingRetryLimit: Int = 2,
    toolExecution: ToolExecution = ToolExecution.Parallel
) derives JsonCodec:

  /** Parity convenience check for parallel tool execution. */
  def streamParallelTools: Boolean = toolExecution == ToolExecution.Parallel

  def validated: Either[AgentError, AgentConfig] =
    if maxIterations <= 0 then
      Left(AgentError.InvalidConfiguration("maxIterations must be positive"))
    else if emptyThinkingRetryLimit < 0 then
      Left(AgentError.InvalidConfiguration("emptyThinkingRetryLimit must be nonnegative"))
    else if timeout <= Duration.Zero then
      Left(AgentError.InvalidConfiguration("timeout must be positive"))
    else Right(this)
