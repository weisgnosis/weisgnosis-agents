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
import com.weisgnosis.agents.core.event.ModelStreamContext
import com.weisgnosis.agents.core.model.{Role, ToolHistory}
import com.weisgnosis.agents.core.tool.{ToolDef, ToolGroup}
import zio.Chunk
import zio.json.JsonCodec
import zio.json.ast.Json

/** Serializable before-acting data. Decoding does not restore tools or grant execution authority. */
final case class PendingActing(
    reason: String,
    context: ModelStreamContext,
    assistantPosition: Int,
    admittedTools: Chunk[ToolDef],
    toolGroups: Option[Chunk[ToolGroup]],
    responseFormat: Option[Json]
) derives JsonCodec

object PendingActing:
  private[agent] def validate(
      result: AgentResult
  ): Either[AgentError.InvalidConfiguration, PendingActing] =
    val invalid = AgentError.InvalidConfiguration("Invalid before-acting checkpoint")
    result.state match
      case AgentRunState.Suspended(pending) =>
        val prefix   = result.history.take(pending.assistantPosition)
        val analysis = ToolHistory.inspect(result.history)
        Either.cond(
          pending.reason.trim.nonEmpty &&
            pending.context.replyId.trim.nonEmpty && pending.context.requestId.trim.nonEmpty &&
            result.iterationCount > 0 && pending.assistantPosition >= 0 &&
            pending.assistantPosition == result.history.size - 1 &&
            result.history.lastOption.contains(result.reply) &&
            result.reply.role == Role.Assistant && result.reply.toolUses.nonEmpty &&
            ToolHistory.inspect(prefix).isComplete && analysis.unmatched.isEmpty &&
            analysis.pending.forall(_.position == pending.assistantPosition) &&
            pending.admittedTools.map(_.name).distinct.size == pending.admittedTools.size,
          pending,
          invalid
        )
      case _ => Left(invalid)
