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
import scala.compiletime.testing.typeCheckErrors
import zio.*
import zio.json.*
import zio.test.*

object PostReasoningHookSpec extends ZIOSpecDefault:
  def spec: Spec[TestEnvironment & Scope, Any] = suite("PostReasoningHookSpec")(
    test("reasoning decisions and hook errors retain their typed JSON representation") {
      val decisions = Chunk(
        ReasoningDecision.Keep,
        ReasoningDecision.Discard,
        ReasoningDecision.Replace(Msg.assistant("replacement"))
      )
      val error = AgentError.ReasoningHookError("rejected")
      assertTrue(
        decisions.forall(value => value.toJson.fromJson[ReasoningDecision] == Right(value)),
        error.toJson.fromJson[AgentError.ReasoningHookError] == Right(error),
        error.getMessage == "rejected"
      )
    },
    test("default keep is total and supplied policy execution remains suspended") {
      val context = ModelStreamContext("reply", "request")
      val message = Msg.assistant("observed")
      for
        seen <- Ref.make(Chunk.empty[(ModelStreamContext, Msg)])
        hook = PostReasoningHook((identity, reply) =>
          seen.update(_ :+ (identity, reply)).as(ReasoningDecision.Discard)
        )
        effect = hook(context, message)
        before    <- seen.get
        decision  <- effect
        after     <- seen.get
        unchanged <- PostReasoningHook.keep(context, message)
      yield assertTrue(
        before.isEmpty,
        decision == ReasoningDecision.Discard,
        after == Chunk(context -> message),
        unchanged == ReasoningDecision.Keep
      )
    },
    test("provider errors cannot enter the reasoning-hook failure channel") {
      val errors = typeCheckErrors("""
        import com.weisgnosis.agents.core.agent.PostReasoningHook
        import com.weisgnosis.agents.core.error.AgentError
        import zio.ZIO
        PostReasoningHook((_, _) => ZIO.fail(AgentError.ModelError(400, "not a provider rejection")))
      """)
      assertTrue(errors.nonEmpty)
    }
  )
