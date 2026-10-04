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
import com.weisgnosis.agents.core.tool.{ToolDef, ToolExecutionResult}
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

object PendingActingSpec extends ZIOSpecDefault:
  def spec: Spec[TestEnvironment, Any] = suite("PendingActing")(
    test("round trips checkpoint data and rejects inconsistent occurrence boundaries"):
      val call   = ContentBlock.ToolUse("same", "read", Json.Obj())
      val reply  = Msg.assistant(Chunk(call))
      val prefix = Chunk(Msg.user("earlier"), reply, Msg.tool("same", "read", "done"))
      val pending = PendingActing(
        "review",
        ModelStreamContext("reply", "request"),
        prefix.size,
        Chunk(ToolDef("read", "Read", Json.Obj())),
        None,
        None
      )
      val result = AgentResult(reply, prefix :+ reply, 2, AgentRunState.Suspended(pending))
      def changed(value: PendingActing): AgentResult =
        result.copy(state = AgentRunState.Suspended(value))
      val inline = Msg.assistant(
        Chunk(call.withServerTool(true), ContentBlock.ToolResult.text("same", "read", "inline"))
      )
      val completeInline = result.copy(reply = inline, history = prefix :+ inline)
      val invalid = Chunk(
        result.copy(state = AgentRunState.Completed),
        result.copy(iterationCount = 0),
        result.copy(history = Chunk.empty),
        result.copy(reply = Msg.assistant("different")),
        result.copy(history = result.history :+ Msg.user("extra")),
        result.copy(
          history = prefix.dropRight(1) :+ reply,
          state = AgentRunState.Suspended(pending.copy(assistantPosition = prefix.size - 1))
        ),
        result.copy(
          history = Chunk(Msg.tool("orphan", "read", "bad"), reply),
          state = AgentRunState.Suspended(pending.copy(assistantPosition = 1))
        ),
        changed(pending.copy(reason = " ")),
        changed(pending.copy(context = pending.context.copy(requestId = ""))),
        changed(pending.copy(context = pending.context.copy(replyId = ""))),
        changed(pending.copy(assistantPosition = -1)),
        changed(pending.copy(admittedTools = pending.admittedTools ++ pending.admittedTools))
      )
      assertTrue(
        result.toJson.fromJson[AgentResult] == Right(result),
        PendingActing.validate(result) == Right(pending),
        PendingActing.validate(completeInline) == Right(pending),
        invalid.forall(PendingActing.validate(_).isLeft)
      )
    ,
    test("partial checkpoints bind completed results by ordinal and validate the entire tool tail"):
      val call   = ContentBlock.ToolUse("same", "read", Json.Obj())
      val inline = call.withServerTool(true)
      val reply = Msg.assistant(
        Chunk(
          call,
          inline,
          ContentBlock.ToolResult.text("same", "read", "inline"),
          call,
          call
        )
      )
      val denied = Msg.tool("same", "read", "declined", ToolResultState.Denied)
      val failed = Msg.tool("same", "read", "failed", ToolResultState.Error)
      val ledger = Chunk(CompletedToolCall(2, 2), CompletedToolCall(3, 3))
      val pending = PendingActing(
        "remaining approval",
        ModelStreamContext("reply", "request"),
        1,
        Chunk(ToolDef("read", "Read", Json.Obj())),
        None,
        None,
        stage = PendingActingStage.AwaitingApproval(ledger, None, Some(PendingActingStop(2, "")))
      )
      val result = AgentResult(
        reply,
        Chunk(Msg.user("review"), reply, denied, failed),
        1,
        AgentRunState.Suspended(pending)
      )
      def completed(values: Chunk[CompletedToolCall]): AgentResult = result.copy(state =
        AgentRunState.Suspended(
          pending.copy(stage = PendingActingStage.AwaitingApproval(values, None, None))
        )
      )
      def stopped(ordinal: Int): AgentResult = result.copy(state =
        AgentRunState.Suspended(
          pending.copy(stage =
            PendingActingStage.AwaitingApproval(
              ledger,
              None,
              Some(PendingActingStop(ordinal, "stop"))
            )
          )
        )
      )
      def firstObservation(message: Msg): AgentResult =
        result.copy(history = result.history.take(2) ++ Chunk(message, failed))
      val duplicate = Json.Obj("key" -> Json.Num(1), "key" -> Json.Num(2))
      val permission =
        PendingPermissionRequest(0, "approval", Json.Obj("prepared" -> Json.Str("input")))
      def requests(values: Chunk[PendingPermissionRequest]): AgentResult =
        result.copy(state = AgentRunState.Suspended(pending.copy(permissionRequests = values)))
      val malformed = Msg.tool(
        name = Some("read"),
        content = Chunk(
          ContentBlock.ToolResult(
            "same",
            Some("read"),
            Chunk(ContentBlock.Custom("data", duplicate))
          )
        )
      )
      val invalid = Chunk(
        completed(Chunk(CompletedToolCall(2, 2), CompletedToolCall(2, 3))),
        completed(Chunk(CompletedToolCall(2, 2), CompletedToolCall(3, 2))),
        completed(Chunk(CompletedToolCall(1, 2), CompletedToolCall(3, 3))),
        completed(Chunk(CompletedToolCall(4, 2), CompletedToolCall(3, 3))),
        completed(Chunk(CompletedToolCall(-1, 2), CompletedToolCall(3, 3))),
        completed(Chunk(CompletedToolCall(2, 4), CompletedToolCall(3, 3))),
        completed(ledger.take(1)),
        firstObservation(Msg.tool("other", "read", "unmatched")),
        firstObservation(Msg.tool("same", "other", "wrong name")),
        firstObservation(Msg.user("wrong role")),
        firstObservation(
          Msg.tool(name = Some("read"), content = Chunk(ContentBlock.Text("not a result")))
        ),
        firstObservation(malformed),
        result.copy(reply = Msg.assistant("different")),
        stopped(0),
        stopped(4)
      ) ++ Chunk(
        requests(Chunk(permission, permission)),
        requests(Chunk(permission.copy(callOrdinal = 1))),
        requests(Chunk(permission.copy(callOrdinal = 2))),
        requests(Chunk(permission.copy(callOrdinal = -1))),
        requests(Chunk(permission.copy(requestId = " "))),
        requests(Chunk(permission.copy(input = duplicate))),
        result.copy(
          history = result.history.take(2),
          state = AgentRunState.Suspended(
            pending.copy(
              stage = PendingActingStage.AwaitingApproval(Chunk.empty, None, None),
              permissionRequests = Chunk(
                permission,
                permission.copy(callOrdinal = 2),
                permission.copy(callOrdinal = 3, requestId = "other")
              )
            )
          )
        )
      ) ++ Chunk(ToolResultState.Running, ToolResultState.Interrupted).map(state =>
        firstObservation(Msg.tool("same", "read", "nonterminal", state))
      )
      val rejected = invalid.foldLeft(assertTrue(true))((checked, candidate) =>
        checked && assertTrue(PendingActing.pendingCalls(candidate).isLeft)
      )
      assertTrue(
        result.toJson.fromJson[AgentResult] == Right(result),
        PendingActing.validate(result) == Right(pending),
        PendingActing.pendingCalls(result) == Right(Chunk(PendingToolCall(0, call))),
        requests(Chunk(permission)).toJson.fromJson[AgentResult] == Right(
          requests(Chunk(permission))
        ),
        PendingActing.pendingCalls(requests(Chunk(permission))) == Right(
          Chunk(PendingToolCall(0, call))
        ),
        PendingActing
          .withResults(
            requests(Chunk(permission)),
            Chunk(SuppliedToolResult(0, ToolExecutionResult("external")))
          )
          .isRight,
        PendingActing.pendingCalls(stopped(1)) == Right(Chunk(PendingToolCall(0, call)))
      ) && rejected
  )
