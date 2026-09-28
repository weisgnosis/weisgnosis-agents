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
import zio.test.*

/** AgentEventStreamTest identifier contracts; payload adapters and emission are separate concerns. */
object AgentEventTypeSpec extends ZIOSpecDefault:
  private val canonical: Chunk[(String, AgentEventType)] = Chunk(
    "AGENT_START"                -> AgentEventType.AgentStart,
    "AGENT_END"                  -> AgentEventType.AgentEnd,
    "AGENT_RESULT"               -> AgentEventType.AgentResult,
    "MODEL_CALL_START"           -> AgentEventType.ModelCallStart,
    "MODEL_CALL_END"             -> AgentEventType.ModelCallEnd,
    "TEXT_BLOCK_START"           -> AgentEventType.TextBlockStart,
    "TEXT_BLOCK_DELTA"           -> AgentEventType.TextBlockDelta,
    "TEXT_BLOCK_END"             -> AgentEventType.TextBlockEnd,
    "THINKING_BLOCK_START"       -> AgentEventType.ThinkingBlockStart,
    "THINKING_BLOCK_DELTA"       -> AgentEventType.ThinkingBlockDelta,
    "THINKING_BLOCK_END"         -> AgentEventType.ThinkingBlockEnd,
    "DATA_BLOCK_START"           -> AgentEventType.DataBlockStart,
    "DATA_BLOCK_DELTA"           -> AgentEventType.DataBlockDelta,
    "DATA_BLOCK_END"             -> AgentEventType.DataBlockEnd,
    "TOOL_CALL_START"            -> AgentEventType.ToolCallStart,
    "TOOL_CALL_DELTA"            -> AgentEventType.ToolCallDelta,
    "TOOL_CALL_END"              -> AgentEventType.ToolCallEnd,
    "TOOL_RESULT_START"          -> AgentEventType.ToolResultStart,
    "TOOL_RESULT_TEXT_DELTA"     -> AgentEventType.ToolResultTextDelta,
    "TOOL_RESULT_DATA_DELTA"     -> AgentEventType.ToolResultDataDelta,
    "TOOL_RESULT_END"            -> AgentEventType.ToolResultEnd,
    "EXCEED_MAX_ITERS"           -> AgentEventType.ExceedMaxIters,
    "REQUIRE_USER_CONFIRM"       -> AgentEventType.RequireUserConfirm,
    "REQUIRE_EXTERNAL_EXECUTION" -> AgentEventType.RequireExternalExecution,
    "USER_CONFIRM_RESULT"        -> AgentEventType.UserConfirmResult,
    "EXTERNAL_EXECUTION_RESULT"  -> AgentEventType.ExternalExecutionResult,
    "REQUEST_STOP"               -> AgentEventType.RequestStop,
    "SUBAGENT_EXPOSED"           -> AgentEventType.SubagentExposed,
    "HINT_BLOCK"                 -> AgentEventType.HintBlock,
    "ALL_TOOLS_DENIED"           -> AgentEventType.AllToolsDenied,
    "CUSTOM"                     -> AgentEventType.Custom
  )

  private def aliasMatches(alias: String, expected: AgentEventType): Boolean =
    AgentEventType.fromValue(alias).contains(expected) &&
      alias.toJson
        .fromJson[AgentEventType]
        .contains(expected) && expected.toJson == expected.wireName.toJson

  def spec: Spec[TestEnvironment, Any] = suite("AgentEventTypeSpec")(
    test("runStartedAlias") {
      assertTrue(aliasMatches("RUN_STARTED", AgentEventType.AgentStart))
    },
    test("runFinishedAlias") {
      assertTrue(aliasMatches("RUN_FINISHED", AgentEventType.AgentEnd))
    },
    test("modelCallAliases") {
      assertTrue(
        aliasMatches("MODEL_CALL_STARTED", AgentEventType.ModelCallStart),
        aliasMatches("MODEL_CALL_ENDED", AgentEventType.ModelCallEnd)
      )
    },
    test("binaryBlockAliases") {
      assertTrue(
        aliasMatches("BINARY_BLOCK_START", AgentEventType.DataBlockStart),
        aliasMatches("BINARY_BLOCK_DELTA", AgentEventType.DataBlockDelta),
        aliasMatches("BINARY_BLOCK_END", AgentEventType.DataBlockEnd)
      )
    },
    test("toolResultBinaryDeltaAlias") {
      assertTrue(aliasMatches("TOOL_RESULT_BINARY_DELTA", AgentEventType.ToolResultDataDelta))
    },
    test("javaNativeNamesRoundTrip") {
      assertTrue(
        canonical.size == 31,
        canonical.map(_._2).toSet == AgentEventType.values.toSet,
        canonical.forall { case (name, expected) =>
          AgentEventType.fromValue(name).contains(expected) && name.toJson
            .fromJson[AgentEventType]
            .contains(expected) && expected.toJson == name.toJson
        }
      )
    },
    test("fromValueRejectsUnknown") {
      val invalid = Chunk("NOT_A_TYPE", "agent_start", " AGENT_START", "AGENT_START ", "")
      assertTrue(
        invalid.forall(value =>
          AgentEventType
            .fromValue(value)
            .left
            .exists(_.isInstanceOf[AgentError.DeserializationError]) && value.toJson
            .fromJson[AgentEventType]
            .isLeft
        )
      )
    },
    test("fromValueRejectsNull") {
      // JSON null is the compatibility boundary; Scala domain values do not use null references.
      assertTrue(
        "null".fromJson[AgentEventType].isLeft,
        "42".fromJson[AgentEventType].isLeft,
        "{}".fromJson[AgentEventType].isLeft
      )
    },
    test("allPythonValuesResolve") {
      val upstreamNames = Chunk(
        "RUN_STARTED",
        "RUN_FINISHED",
        "MODEL_CALL_STARTED",
        "MODEL_CALL_ENDED",
        "TEXT_BLOCK_START",
        "TEXT_BLOCK_DELTA",
        "TEXT_BLOCK_END",
        "THINKING_BLOCK_START",
        "THINKING_BLOCK_DELTA",
        "THINKING_BLOCK_END",
        "BINARY_BLOCK_START",
        "BINARY_BLOCK_DELTA",
        "BINARY_BLOCK_END",
        "TOOL_CALL_START",
        "TOOL_CALL_DELTA",
        "TOOL_CALL_END",
        "TOOL_RESULT_START",
        "TOOL_RESULT_TEXT_DELTA",
        "TOOL_RESULT_BINARY_DELTA",
        "TOOL_RESULT_END",
        "EXCEED_MAX_ITERS",
        "REQUIRE_USER_CONFIRM",
        "REQUIRE_EXTERNAL_EXECUTION",
        "USER_CONFIRM_RESULT",
        "EXTERNAL_EXECUTION_RESULT"
      )
      assertTrue(
        upstreamNames.size == 25,
        upstreamNames.forall(AgentEventType.fromValue(_).isRight)
      )
    },
    test("reply and thread aliases preserve canonical encoding") {
      assertTrue(
        aliasMatches("REPLY_START", AgentEventType.AgentStart),
        aliasMatches("REPLY_END", AgentEventType.AgentEnd),
        aliasMatches("THREAD_EXPOSED", AgentEventType.SubagentExposed)
      )
    }
  )
