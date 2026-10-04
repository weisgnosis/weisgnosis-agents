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

import com.weisgnosis.agents.core.model.{ChatUsage, Msg}
import zio.Chunk
import zio.json.*

enum AgentRunState derives JsonCodec:
  case Completed, Suspended, Interrupted
  case Stopped(reason: String)

/**
 * Result of executing a ReAct agent run.
 */
final case class AgentResult(
    reply: Msg,
    history: Chunk[Msg],
    iterationCount: Int,
    state: AgentRunState = AgentRunState.Completed,
    usage: Option[ChatUsage] = None
) derives JsonCodec:
  def isSuspended: Boolean   = state == AgentRunState.Suspended
  def isInterrupted: Boolean = state == AgentRunState.Interrupted
