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
import com.weisgnosis.agents.core.model.*
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

object ModelInputTransformSpec extends ZIOSpecDefault:
  private val context: ModelStreamContext = ModelStreamContext("reply", "request")
  def spec: Spec[TestEnvironment & Scope, Any] = suite("ModelInputTransformSpec")(
    test("identity retains supplied history and construction suspends callback execution") {
      val history = Chunk(Msg.user("q"))
      for
        calls <- Ref.make(0)
        transform = ModelInputTransform((_, value) => calls.update(_ + 1).as(value))
        effect    = transform(context, history)
        before    <- calls.get
        result    <- ZIO.scoped(effect)
        unchanged <- ZIO.scoped(ModelInputTransform.identity(context, history))
        after     <- calls.get
      yield assertTrue(before == 0, after == 1, result == history, unchanged == history)
    },
    test(
      "changed views preserve ordered systems and complete occurrences but may omit closed pairs"
    ) {
      val call     = ContentBlock.ToolUse("call", "lookup", Json.Obj())
      val system   = Msg.system("system")
      val original = Chunk(system, Msg.assistant(Chunk(call)), Msg.tool("call", "lookup", "result"))
      val view     = Chunk(system, Msg.user("projected input"))
      val malformed = Chunk(Msg.assistant(Chunk(call)))
      assertTrue(
        ModelInputTransform.validate(original, view) == Right(view),
        ModelInputTransform.validate(original, Chunk(system, Msg.assistant(Chunk(call)))).isLeft,
        ModelInputTransform.validate(original, Chunk(Msg.user("no system"))).isLeft,
        ModelInputTransform.validate(malformed, malformed) == Right(malformed)
      )
    },
    test("typed input errors round-trip and cannot carry provider rejection into Auto fallback") {
      val error = AgentError.ModelInputError("failed")
      for checked <- typeCheck("""
        import com.weisgnosis.agents.core.agent.ModelInputTransform
        import com.weisgnosis.agents.core.error.AgentError
        import zio.*
        ModelInputTransform((_, _) => ZIO.fail(AgentError.ModelError(400, "bad")))
      """)
      yield assertTrue(
        error.toJson.fromJson[AgentError.ModelInputError] == Right(error),
        checked.isLeft
      )
    }
  )
