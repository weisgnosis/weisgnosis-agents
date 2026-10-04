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

import com.weisgnosis.agents.core.event.ModelStreamContext
import com.weisgnosis.agents.core.model.{ContentBlock, Msg, ToolResultState}
import com.weisgnosis.agents.core.tool.{ToolExecutionResult, ToolResultPayload}
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

object SuppliedToolResultSpec extends ZIOSpecDefault:
  def spec: Spec[TestEnvironment, Any] = suite("SuppliedToolResult")(
    test("checked augmentation preserves history and rejects ambiguous or unfinished observations"):
      val call = ContentBlock.ToolUse("same", "read", Json.Obj())
      val reply = Msg.assistant(
        Chunk(
          call,
          call.withServerTool(true),
          ContentBlock.ToolResult.text("same", "read", "inline"),
          call
        )
      )
      val pending =
        PendingActing("review", ModelStreamContext("reply", "request"), 0, Chunk.empty, None, None)
      val checkpoint = AgentResult(reply, Chunk(reply), 1, AgentRunState.Suspended(pending))
      val supplied   = SuppliedToolResult(2, ToolExecutionResult("provided"))
      val malformed = SuppliedToolResult(
        0,
        ToolExecutionResult(
          ToolResultPayload(
            Chunk.empty,
            Map("invalid" -> Json.Obj("x" -> Json.Num(1), "x" -> Json.Num(2)))
          ),
          ToolResultState.Success
        )
      )
      val invalid = Chunk(
        Chunk(supplied, supplied),
        Chunk(supplied.copy(callOrdinal = -1)),
        Chunk(supplied.copy(callOrdinal = 1)),
        Chunk(supplied.copy(callOrdinal = 3)),
        Chunk(supplied.copy(result = ToolExecutionResult("unfinished", ToolResultState.Running))),
        Chunk(supplied.copy(result = ToolExecutionResult("denied", ToolResultState.Denied))),
        Chunk(malformed)
      )
      val accepted = PendingActing.withResults(checkpoint, Chunk(supplied))
      assertTrue(
        supplied.toJson.fromJson[SuppliedToolResult] == Right(supplied),
        accepted.exists(result =>
          result.history == checkpoint.history && result.reply == reply &&
            result.toJson.fromJson[AgentResult] == Right(result)
        ),
        accepted.flatMap(PendingActing.withResults(_, Chunk(supplied))).isLeft,
        PendingActing
          .withResults(
            checkpoint,
            Chunk(supplied.copy(result = ToolExecutionResult("failed", ToolResultState.Error)))
          )
          .isRight,
        invalid.forall(values => PendingActing.withResults(checkpoint, values).isLeft),
        invalid.forall(values =>
          PendingActing
            .validate(
              checkpoint.copy(
                state = AgentRunState.Suspended(pending.copy(suppliedResults = values))
              )
            )
            .isLeft
        ),
        PendingActing
          .withResults(checkpoint.copy(state = AgentRunState.Completed), Chunk(supplied))
          .isLeft
      )
  )
