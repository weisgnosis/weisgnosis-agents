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
package com.weisgnosis.agents.harness.memory

import zio.*
import zio.json.*
import zio.stm.TPromise
import zio.test.*

object MemoryTaskTicketSpec extends ZIOSpecDefault:
  def spec: Spec[TestEnvironment & Scope, Any] = suite("MemoryTaskTicketSpec")(
    test("conversation tokens allocate fresh effectful identities") {
      for
        first  <- MemoryConversationToken.make
        second <- MemoryConversationToken.make
      yield assertTrue(first != second)
    },
    test("interrupting a ticket waiter preserves its eventual outcome") {
      for
        completion <- TPromise.make[Nothing, MemoryTaskOutcome].commit
        ticket = new MemoryTaskTicket(completion)
        waiter    <- ticket.await.forkScoped
        cancelled <- waiter.interrupt
        _         <- completion.succeed(MemoryTaskOutcome.Superseded).commit
        outcome   <- ticket.await
      yield assertTrue(cancelled.isInterrupted, outcome == MemoryTaskOutcome.Superseded)
    },
    test("rejection decisions round trip independently of runtime ticket data") {
      assertTrue(
        MemoryTaskRejection.values.forall(value =>
          value.toJson.fromJson[MemoryTaskRejection] == Right(value)
        )
      )
    }
  )
