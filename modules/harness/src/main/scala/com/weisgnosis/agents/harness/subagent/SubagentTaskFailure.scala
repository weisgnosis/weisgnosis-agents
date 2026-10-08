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
import zio.json.*

/** Exact supplied failure data, not a reconstructed Throwable or an inferred causal chain. */
@jsonNoExtraFields
final case class SubagentTaskFailure(message: String, cause: Option[String] = None)
    derives JsonCodec:
  /** Derived presentation only; persisted message and cause remain separate exact fields. */
  def displayText: String = message + cause.fold("")(value => "\nCause: " + value)

object SubagentTaskFailure:
  def fromError(error: AgentError): SubagentTaskFailure = error match
    case AgentError.ToolExecutionError(_, message, cause) => SubagentTaskFailure(message, cause)
    case other                                            => SubagentTaskFailure(other.message)
