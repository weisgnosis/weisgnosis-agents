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
package com.weisgnosis.agents.core.event

import com.weisgnosis.agents.core.error.AgentError
import zio.Chunk
import zio.json.*

/** Protocol event names for compatibility boundaries; values do not imply runtime emitter support. */
enum AgentEventType(val wireName: String):
  case AgentStart               extends AgentEventType("AGENT_START")
  case AgentEnd                 extends AgentEventType("AGENT_END")
  case AgentResult              extends AgentEventType("AGENT_RESULT")
  case ModelCallStart           extends AgentEventType("MODEL_CALL_START")
  case ModelCallEnd             extends AgentEventType("MODEL_CALL_END")
  case TextBlockStart           extends AgentEventType("TEXT_BLOCK_START")
  case TextBlockDelta           extends AgentEventType("TEXT_BLOCK_DELTA")
  case TextBlockEnd             extends AgentEventType("TEXT_BLOCK_END")
  case ThinkingBlockStart       extends AgentEventType("THINKING_BLOCK_START")
  case ThinkingBlockDelta       extends AgentEventType("THINKING_BLOCK_DELTA")
  case ThinkingBlockEnd         extends AgentEventType("THINKING_BLOCK_END")
  case DataBlockStart           extends AgentEventType("DATA_BLOCK_START")
  case DataBlockDelta           extends AgentEventType("DATA_BLOCK_DELTA")
  case DataBlockEnd             extends AgentEventType("DATA_BLOCK_END")
  case ToolCallStart            extends AgentEventType("TOOL_CALL_START")
  case ToolCallDelta            extends AgentEventType("TOOL_CALL_DELTA")
  case ToolCallEnd              extends AgentEventType("TOOL_CALL_END")
  case ToolResultStart          extends AgentEventType("TOOL_RESULT_START")
  case ToolResultTextDelta      extends AgentEventType("TOOL_RESULT_TEXT_DELTA")
  case ToolResultDataDelta      extends AgentEventType("TOOL_RESULT_DATA_DELTA")
  case ToolResultEnd            extends AgentEventType("TOOL_RESULT_END")
  case ExceedMaxIters           extends AgentEventType("EXCEED_MAX_ITERS")
  case RequireUserConfirm       extends AgentEventType("REQUIRE_USER_CONFIRM")
  case RequireExternalExecution extends AgentEventType("REQUIRE_EXTERNAL_EXECUTION")
  case UserConfirmResult        extends AgentEventType("USER_CONFIRM_RESULT")
  case ExternalExecutionResult  extends AgentEventType("EXTERNAL_EXECUTION_RESULT")
  case RequestStop              extends AgentEventType("REQUEST_STOP")
  case SubagentExposed          extends AgentEventType("SUBAGENT_EXPOSED")
  case HintBlock                extends AgentEventType("HINT_BLOCK")
  case AllToolsDenied           extends AgentEventType("ALL_TOOLS_DENIED")
  case Custom                   extends AgentEventType("CUSTOM")

object AgentEventType:
  private val aliases: Map[String, AgentEventType] = Map(
    "RUN_STARTED"              -> AgentEventType.AgentStart,
    "REPLY_START"              -> AgentEventType.AgentStart,
    "RUN_FINISHED"             -> AgentEventType.AgentEnd,
    "REPLY_END"                -> AgentEventType.AgentEnd,
    "MODEL_CALL_STARTED"       -> AgentEventType.ModelCallStart,
    "MODEL_CALL_ENDED"         -> AgentEventType.ModelCallEnd,
    "BINARY_BLOCK_START"       -> AgentEventType.DataBlockStart,
    "BINARY_BLOCK_DELTA"       -> AgentEventType.DataBlockDelta,
    "BINARY_BLOCK_END"         -> AgentEventType.DataBlockEnd,
    "TOOL_RESULT_BINARY_DELTA" -> AgentEventType.ToolResultDataDelta,
    "THREAD_EXPOSED"           -> AgentEventType.SubagentExposed
  )

  private val byName: Map[String, AgentEventType] =
    Chunk.fromArray(AgentEventType.values).map(value => value.wireName -> value).toMap ++ aliases

  def fromValue(value: String): Either[AgentError, AgentEventType] =
    byName.get(value).toRight(AgentError.DeserializationError(s"Unknown event type: $value"))

  // Alias-aware decoding requires a custom codec; encoding always uses the canonical name.
  given JsonCodec[AgentEventType] = JsonCodec.string.transformOrFail(
    value => fromValue(value).left.map(_.message),
    _.wireName
  )
