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

import com.weisgnosis.agents.core.error.AgentError
import zio.*
import zio.test.*

object SubagentInboxSpec extends ZIOSpecDefault:
  def spec: Spec[TestEnvironment & Scope, Any] = suite("SubagentInboxSpec")(
    test(
      "captured arrival latches survive host draining; clearing rearms, closure fails pending observers"
    ):
      ZIO.scoped:
        for
          signal  <- SubagentInboxSignal.open
          before  <- signal.snapshot
          _       <- signal.setPresence(SubagentInboxPresence.Pending)
          _       <- signal.setPresence(SubagentInboxPresence.Empty)
          arrived <- before.awaitArrival.exit
          after   <- signal.snapshot
          waiting <- after.awaitArrival.forkScoped
          pending <- waiting.poll
          _       <- signal.close
          closed  <- waiting.await
          escaped <- signal.snapshot.either
          update  <- signal.setPresence(SubagentInboxPresence.Pending).either
        yield assertTrue(
          before.presence == SubagentInboxPresence.Empty,
          arrived == Exit.unit,
          after.presence == SubagentInboxPresence.Empty,
          pending.isEmpty,
          closed match
            case Exit.Failure(cause) =>
              cause.failures == List(
                AgentError.InvalidConfiguration("Async inbox signal is closed")
              ) && !cause.isInterrupted
            case _ => false,
          escaped == Left(AgentError.InvalidConfiguration("Async inbox signal is closed")),
          update == Left(AgentError.InvalidConfiguration("Async inbox signal is closed"))
        )
  )
