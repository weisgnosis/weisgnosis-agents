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
package com.weisgnosis.agents.harness.subagent

import com.weisgnosis.agents.core.agent.{AgentResult, AgentRunState, PendingActing}
import com.weisgnosis.agents.core.event.ModelStreamContext
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.model.Msg
import zio.*
import zio.json.*
import zio.test.*

object SubagentTerminalOutcomeSpec extends ZIOSpecDefault:
  def spec: Spec[TestEnvironment, Any] = suite("SubagentTerminalOutcomeSpec")(
    test(
      "terminal data preserves exact text and cancellation alongside typed failures and defects"
    ):
      val reply  = Msg.assistant(" exact output \n" + "x" * 5000)
      val result = AgentResult(reply, Chunk(reply), 1)
      val pending =
        PendingActing("review", ModelStreamContext("reply", "request"), 0, Chunk.empty, None, None)
      val failure   = AgentError.StorageError("task failure")
      val defect    = new IllegalStateException("cleanup defect")
      val failed    = Cause.fail(failure) ++ Cause.die(defect)
      val cancelled = failed ++ Cause.interrupt(FiberId.None)
      val cases =
        Chunk(
          Exit.succeed(result) -> SubagentTerminalOutcome.Completed(reply.text),
          Exit.succeed(
            result.copy(state = AgentRunState.Suspended(pending))
          ) -> SubagentTerminalOutcome.Suspended(reply.text),
          Exit.succeed(
            result.copy(state = AgentRunState.Stopped("limit"))
          ) -> SubagentTerminalOutcome.Stopped("limit"),
          Exit.succeed(result.copy(state = AgentRunState.Interrupted)) -> SubagentTerminalOutcome
            .Interrupted(Chunk.empty, SubagentTaskDefects.Absent),
          Exit.failCause(failed) -> SubagentTerminalOutcome
            .Failed(Chunk(failure.message), SubagentTaskDefects.Present),
          Exit.failCause(cancelled) -> SubagentTerminalOutcome.Interrupted(
            Chunk(failure.message),
            SubagentTaskDefects.Present
          )
        )
      assertTrue(cases.forall: (exit, expected) =>
        val observed = SubagentTerminalOutcome.fromExit(exit)
        observed == expected && observed.toJson.fromJson[SubagentTerminalOutcome] == Right(
          expected
        ))
  )
