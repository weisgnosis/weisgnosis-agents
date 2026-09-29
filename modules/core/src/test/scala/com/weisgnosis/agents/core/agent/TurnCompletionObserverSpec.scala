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
import com.weisgnosis.agents.core.model.Msg
import zio.*
import zio.json.*
import zio.test.*

object TurnCompletionObserverSpec extends ZIOSpecDefault:
  private val result: AgentResult = AgentResult(Msg.assistant("done"), Chunk(Msg.user("q")), 1)
  private val completion: TurnCompletion =
    TurnCompletion("agent", result.history, Exit.succeed(result))

  def spec: Spec[TestEnvironment & Scope, Any] = suite("TurnCompletionObserverSpec")(
    test("construction is lazy and the caller closes observer resources") {
      for
        observed <- Ref.make(Chunk.empty[TurnCompletion])
        released <- Ref.make(false)
        observer = TurnCompletionObserver(value =>
          ZIO.addFinalizer(released.set(true)) *> observed.update(_ :+ value)
        )
        effect = observer.observe(completion)
        before <- observed.get
        _      <- ZIO.scoped(effect)
        after  <- observed.get
        closed <- released.get
      yield assertTrue(before.isEmpty, after == Chunk(completion), closed)
    },
    test("ignore accepts original success or failure without rewriting completion data") {
      val failed = completion.copy(outcome = Exit.fail(AgentError.ModelError(503, "unavailable")))
      ZIO
        .scoped(
          TurnCompletionObserver.ignore.observe(completion) *> TurnCompletionObserver.ignore
            .observe(failed)
        )
        .as(assertTrue(failed.history == completion.history))
    },
    test("observation errors preserve wire identity and cannot be provider fallback errors") {
      val error = AgentError.CompletionObservationError("persist failed")
      for invalid <- typeCheck("""
          import com.weisgnosis.agents.core.agent.TurnCompletionObserver
          import com.weisgnosis.agents.core.error.AgentError
          import zio.*
          TurnCompletionObserver(_ => ZIO.fail(AgentError.ModelError(400, "bad")))
        """)
      yield assertTrue(
        error.toJson.fromJson[AgentError.CompletionObservationError] == Right(error),
        invalid.isLeft
      )
    }
  )
