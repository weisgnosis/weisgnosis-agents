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
package com.weisgnosis.agents.core.model

import com.weisgnosis.agents.core.error.AgentError
import zio.Chunk
import zio.json.*

/** Typed classification of generation metadata; runtime completion remains an independent authority. */
enum GenerateReason(val wireName: String, private val upstreamName: String):
  case ModelStop        extends GenerateReason("model_stop", "MODEL_STOP")
  case ToolCalls        extends GenerateReason("tool_calls", "TOOL_CALLS")
  case StructuredOutput extends GenerateReason("structured_output", "STRUCTURED_OUTPUT")
  case ToolSuspended    extends GenerateReason("tool_suspended", "TOOL_SUSPENDED")
  case ReasoningStopRequested
      extends GenerateReason("reasoning_stop_requested", "REASONING_STOP_REQUESTED")
  case ActingStopRequested extends GenerateReason("acting_stop_requested", "ACTING_STOP_REQUESTED")
  case PermissionAsking    extends GenerateReason("permission_asking", "PERMISSION_ASKING")
  case MiddlewareStopRequested
      extends GenerateReason("middleware_stop_requested", "MIDDLEWARE_STOP_REQUESTED")
  case AllToolsDenied   extends GenerateReason("all_tools_denied", "ALL_TOOLS_DENIED")
  case Interrupted      extends GenerateReason("interrupted", "INTERRUPTED")
  case MaxIterations    extends GenerateReason("max_iterations", "MAX_ITERATIONS")
  case ToolReturnDirect extends GenerateReason("tool_return_direct", "TOOL_RETURN_DIRECT")

object GenerateReason:
  private val byName: Map[String, GenerateReason] =
    Chunk
      .fromArray(GenerateReason.values)
      .flatMap(reason => Chunk(reason.wireName -> reason, reason.upstreamName -> reason))
      .toMap

  def fromValue(value: String): Either[AgentError, GenerateReason] =
    byName.get(value).toRight(AgentError.DeserializationError(s"Unknown generation reason: $value"))

  // Preserve established lowercase metadata output while accepting uppercase protocol input.
  given JsonCodec[GenerateReason] = JsonCodec.string.transformOrFail(
    value => fromValue(value).left.map(_.message),
    _.wireName
  )
