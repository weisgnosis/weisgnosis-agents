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
import com.weisgnosis.agents.core.model.{ContentBlock, Msg}
import com.weisgnosis.agents.core.tool.ToolDef
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
  )
