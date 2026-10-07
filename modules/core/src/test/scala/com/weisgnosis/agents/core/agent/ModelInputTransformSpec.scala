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

import com.weisgnosis.agents.core.client.{ReasoningEffort, StopSequences}
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.event.ModelStreamContext
import com.weisgnosis.agents.core.model.*
import com.weisgnosis.agents.core.tool.ToolDef
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
    },
    test(
      "selected projection composes once while standalone calls retain the explicit legacy base"
    ):
      val history = Chunk(Msg.system("system"), Msg.user("question"))
      val tools =
        Chunk(ToolDef("lookup", "Host definition", Json.Obj("type" -> Json.Str("object"))))
      val request =
        ModelRequestInput(
          history,
          "selected-model",
          tools,
          ModelRequestSettings(
            0.7,
            Some(100),
            Some(0.9),
            Some(0.5),
            Some(0.3),
            Some(42L),
            Some(StopSequences.one("original")),
            Some(ReasoningEffort.Low)
          )
        )
      for
        calls <- Ref.make(Chunk.empty[String])
        seen  <- Ref.make(Chunk.empty[(ModelStreamContext, ModelRequestInput)])
        base = new ModelInputTransform:
          def apply(
              context: ModelStreamContext,
              canonicalHistory: Chunk[Msg]
          ): ZIO[Scope, AgentError.ModelInputError, Chunk[Msg]] =
            calls.update(_ :+ context.requestId).as(canonicalHistory :+ Msg.user("legacy"))
        first = ModelInputTransform.withRequest(base): (ctx, input) =>
          calls.update(_ :+ "first") *> seen
            .update(_ :+ (ctx -> input))
            .as(
              ModelInputProjection(
                input.history :+ Msg.user("first"),
                ModelRequestSettings(
                  0,
                  Some(11),
                  Some(0.4),
                  Some(-2),
                  Some(2),
                  Some(Long.MaxValue),
                  Some(StopSequences.one("projected")),
                  Some(ReasoningEffort.High)
                )
              )
            )
        second = ModelInputTransform.withRequest(first): (ctx, input) =>
          calls.update(_ :+ "second") *> seen
            .update(_ :+ (ctx -> input))
            .as(
              ModelInputProjection(
                input.history :+ Msg.user("second"),
                input.settings.copy(
                  maxTokens = None,
                  topP = None,
                  frequencyPenalty = None,
                  presencePenalty = None,
                  seed = None,
                  stopSequences = None,
                  reasoningEffort = None
                )
              )
            )
        standalone = second(context, history)
        selected   = second.projectRequest(context, request)
        cold        <- calls.get
        legacy      <- ZIO.scoped(standalone)
        afterLegacy <- calls.get
        result      <- ZIO.scoped(selected)
        observed    <- calls.get
        inputs      <- seen.get
      yield assertTrue(
        cold.isEmpty,
        legacy == history :+ Msg.user("legacy"),
        afterLegacy == Chunk("request"),
        observed == Chunk("request", "request", "first", "second"),
        inputs == Chunk(
          context -> request.copy(history = legacy),
          context -> request.copy(
            history = legacy :+ Msg.user("first"),
            settings = ModelRequestSettings(
              0,
              Some(11),
              Some(0.4),
              Some(-2),
              Some(2),
              Some(Long.MaxValue),
              Some(StopSequences.one("projected")),
              Some(ReasoningEffort.High)
            )
          )
        ),
        result == ModelInputProjection(
          legacy ++ Chunk(Msg.user("first"), Msg.user("second")),
          ModelRequestSettings(0, None)
        )
      )
    ,
    test("intermediate history and settings are checked before a later projection can run"):
      val history  = Chunk(Msg.system("system"), Msg.user("question"))
      val settings = ModelRequestSettings(0.7, Some(10))
      val request  = ModelRequestInput(history, "selected", Chunk.empty, settings)
      val cases = Chunk(
        ModelInputProjection(history, settings.copy(topP = Some(0)))                       -> true,
        ModelInputProjection(history, settings.copy(topP = Some(1)))                       -> true,
        ModelInputProjection(history, settings.copy(topP = Some(Double.NaN)))              -> false,
        ModelInputProjection(history, settings.copy(topP = Some(Double.PositiveInfinity))) -> false,
        ModelInputProjection(history, settings.copy(topP = Some(-0.1)))                    -> false,
        ModelInputProjection(history, settings.copy(topP = Some(1.1)))                     -> false,
        ModelInputProjection(history, ModelRequestSettings(0, None))                       -> true,
        ModelInputProjection(history, ModelRequestSettings(2, Some(Int.MaxValue)))         -> true,
        ModelInputProjection(history, settings.copy(temperature = Double.NaN))             -> false,
        ModelInputProjection(
          history,
          settings.copy(temperature = Double.PositiveInfinity)
        )                                                                  -> false,
        ModelInputProjection(history, settings.copy(temperature = -0.1))   -> false,
        ModelInputProjection(history, settings.copy(temperature = 2.1))    -> false,
        ModelInputProjection(history, settings.copy(maxTokens = Some(0)))  -> false,
        ModelInputProjection(history, settings.copy(maxTokens = Some(-1))) -> false,
        ModelInputProjection(Chunk(Msg.user("removed system")), settings)  -> false
      ) ++ Chunk(
        -2.0                    -> true,
        0.0                     -> true,
        2.0                     -> true,
        Double.NaN              -> false,
        Double.PositiveInfinity -> false,
        Double.NegativeInfinity -> false,
        -2.1                    -> false,
        2.1                     -> false
      ).flatMap((value, valid) =>
        Chunk(
          ModelInputProjection(history, settings.copy(frequencyPenalty = Some(value))) -> valid,
          ModelInputProjection(history, settings.copy(presencePenalty = Some(value)))  -> valid
        )
      )
      ZIO
        .foreach(cases): (candidate, valid) =>
          for
            calls <- Ref.make(0)
            base = ModelInputTransform.withRequest(ModelInputTransform.identity)((_, _) =>
              ZIO.succeed(candidate)
            )
            composed = ModelInputTransform.withRequest(base)((_, input) =>
              calls.update(_ + 1).as(ModelInputProjection(input.history, input.settings))
            )
            result <- ZIO.scoped(composed.projectRequest(context, request)).either
            count  <- calls.get
          yield assertTrue(
            ModelInputTransform.validateProjection(history, candidate).isRight == valid,
            result.isRight == valid,
            count == (if valid then 1 else 0)
          )
        .map(_.foldLeft(assertTrue(true))(_ && _))
  )
