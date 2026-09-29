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
package com.weisgnosis.agents.core.client

import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.event.ModelStreamContext
import zio.*
import zio.test.*
import scala.compiletime.testing.typeCheckErrors

object ModelTextTransformSpec extends ZIOSpecDefault:
  private val context: ModelStreamContext  = ModelStreamContext("reply", "request")
  private val fragment: LlmChunk.TextDelta = LlmChunk.TextDelta("value", 3)

  def spec: Spec[TestEnvironment & Scope, Any] = suite("ModelTextTransformSpec")(
    test("identity preserves the exact text fragment") {
      ModelTextTransform.identity
        .transform(context, fragment)
        .map(result => assertTrue(result == "value"))
    },
    test("composition is suspended and preserves request identity and block index") {
      for
        seen <- Ref.make(Chunk.empty[(ModelStreamContext, LlmChunk.TextDelta)])
        first = ModelTextTransform((identity, input) =>
          seen.update(_ :+ (identity, input)).as("first")
        )
        second = ModelTextTransform((identity, input) =>
          seen.update(_ :+ (identity, input)).as(input.delta + " second")
        )
        effect = first.andThen(second).transform(context, fragment)
        before <- seen.get
        result <- effect
        after  <- seen.get
      yield assertTrue(
        before.isEmpty,
        result == "first second",
        after == Chunk(context -> fragment, context -> fragment.copy(delta = "first"))
      )
    },
    test("provider rejection errors cannot inhabit the transformation callback contract") {
      val errors = typeCheckErrors("""
        import com.weisgnosis.agents.core.client.ModelTextTransform
        import com.weisgnosis.agents.core.error.AgentError
        import zio.ZIO
        ModelTextTransform((_, _) => ZIO.fail(AgentError.ModelError(400, "provider-like failure")))
      """)
      assertTrue(errors.nonEmpty)
    },
    test("a failed stage preserves its typed error and never invokes the next stage") {
      val error = AgentError.TextTransformationError("transform rejected text")
      for
        invoked <- Ref.make(false)
        first  = ModelTextTransform((_, _) => ZIO.fail(error))
        second = ModelTextTransform((_, _) => invoked.set(true).as("unexpected"))
        result <- first.andThen(second).transform(context, fragment).either
        called <- invoked.get
      yield assertTrue(result == Left(error), !called)
    }
  )
