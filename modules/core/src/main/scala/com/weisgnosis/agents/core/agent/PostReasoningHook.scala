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
import com.weisgnosis.agents.core.model.Msg
import zio.*
import zio.json.*

/** A host decision applied before a completed model message enters conversation state. */
enum ReasoningDecision derives JsonCodec:
  case Keep, Discard
  case Replace(message: Msg)
  case Suspend(reason: String)

/** Scoped host policy for completed reasoning; interrupted and token-truncated model output bypasses this hook. */
trait PostReasoningHook:
  def apply(
      context: ModelStreamContext,
      message: Msg
  ): ZIO[Scope, AgentError.ReasoningHookError, ReasoningDecision]

object PostReasoningHook:
  val keep: PostReasoningHook = PostReasoningHook((_, _) => ZIO.succeed(ReasoningDecision.Keep))

  def apply(
      decide: (ModelStreamContext, Msg) => ZIO[
        Scope,
        AgentError.ReasoningHookError,
        ReasoningDecision
      ]
  ): PostReasoningHook = new PostReasoningHook:
    def apply(
        context: ModelStreamContext,
        message: Msg
    ): ZIO[Scope, AgentError.ReasoningHookError, ReasoningDecision] =
      ZIO.suspendSucceed(decide(context, message))
