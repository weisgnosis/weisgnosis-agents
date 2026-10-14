/*
 * Copyright 2024-2026 WeisGnosis (https://weisgnosis.com)
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations.
 */
package com.weisgnosis.agents.examples

import com.weisgnosis.agents.core.client.{LlmClient, LlmConfig, TurnOutput}
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.event.{EventPublisher, ModelStreamContext}
import com.weisgnosis.agents.core.model.Msg
import com.weisgnosis.agents.core.tool.ToolDef
import zio.*

/** Host-side public-call decoration; private capability-based failover authority is not forwarded. */
object AuditedLlmClient:
  enum Outcome:
    case Returned, Failed, Cancelled

  /** Returned describes a client observation, not validated runtime completion. */
  final case class Observation(context: ModelStreamContext, elapsed: Duration, outcome: Outcome)

  /**
   * Observation follows cleanup and must terminate: onExit runs it uninterruptibly. Observer defects
   * compose with a failed exit or replace success; no payloads or causes enter the audit record.
   */
  def wrap(client: LlmClient)(observe: Observation => UIO[Unit]): LlmClient =
    new LlmClient:
      def streamTurn(
          history: Chunk[Msg],
          tools: Chunk[ToolDef],
          config: LlmConfig,
          eventHub: EventPublisher,
          context: ModelStreamContext
      ): ZIO[Scope, AgentError, TurnOutput] =
        for
          started <- Clock.nanoTime
          output <- ZIO
            .scoped(
              ZIO.suspendSucceed(client.streamTurn(history, tools, config, eventHub, context))
            )
            .onExit: exit =>
              for
                finished <- Clock.nanoTime
                outcome = exit match
                  case Exit.Success(_)                                => Outcome.Returned
                  case Exit.Failure(cause) if cause.isInterruptedOnly => Outcome.Cancelled
                  case Exit.Failure(_)                                => Outcome.Failed
                _ <- ZIO.suspendSucceed(
                  observe(Observation(context, (finished - started).max(0L).nanos, outcome))
                )
              yield ()
        yield output
