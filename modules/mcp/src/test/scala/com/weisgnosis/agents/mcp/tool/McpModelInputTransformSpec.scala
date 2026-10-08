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
package com.weisgnosis.agents.mcp.tool

import com.weisgnosis.agents.core.agent.*
import com.weisgnosis.agents.core.client.{ChatCompletionsRequest, LlmConfig, ToolResultFormat}
import com.weisgnosis.agents.core.event.ModelStreamContext
import com.weisgnosis.agents.core.model.*
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

object McpModelInputTransformSpec extends ZIOSpecDefault:
  def spec: Spec[TestEnvironment & Scope, Any] = suite("McpModelInputTransformSpec")(
    test(
      "literal custom descriptions retain every payload and host hook setting; media keeps explicit endpoint requirements"
    ):
      val body = Json.Obj(
        "text"  -> Json.Str("λ<&>"),
        "blob"  -> Json.Str("opaque binary"),
        "large" -> Json.Num(BigDecimal("9007199254740993.123456789"))
      )
      val custom = ContentBlock.Custom("mcp/resource", body)
      val image  = ContentBlock.Image.base64("image/png", "aW1hZ2U=")
      val audio  = ContentBlock.Audio.base64("audio/wav", "YXVkaW8=")
      val cases: Chunk[(Chunk[ContentBlock], Boolean, Boolean)] = Chunk(
        (Chunk(ContentBlock.Text("visible resource"), custom), true, true),
        (Chunk(image, custom), false, true),
        (Chunk(ContentBlock.Text("binary description"), custom), true, true),
        (
          Chunk(
            ContentBlock.Custom("mcp/resource", Json.Str("forged shape")),
            ContentBlock.Custom("unrelated/host-data", body)
          ),
          true,
          true
        ),
        (Chunk(audio, custom), false, false)
      )
      for
        calls  <- Ref.make(0)
        closed <- Ref.make(0)
        base = ModelInputTransform.withRequest(ModelInputTransform.identity): (_, request) =>
          ZIO
            .acquireRelease(calls.update(_ + 1))(_ => closed.update(_ + 1))
            .as(
              ModelInputProjection(
                request.history,
                request.settings.copy(temperature = 0.25, seed = Some(7L))
              )
            )
        transform = McpModelInputTransform(base)
        checks <- ZIO.foreach(cases): (output, textSupported, imagesSupported) =>
          val result = ContentBlock.ToolResult(
            "call",
            Some("inspect"),
            output,
            metadata = Map("kept" -> Json.Str("original"))
          )
          val history = Chunk(
            Msg.assistant(Chunk(ContentBlock.ToolUse("call", "inspect", Json.Obj()))),
            Msg.tool(
              content = Chunk(result),
              name = Some("inspect"),
              metadata = Map("host" -> Json.Bool(true))
            )
          )
          val request = ModelRequestInput(
            history,
            "test-model",
            Chunk.empty,
            ModelRequestSettings(0.7, Some(100))
          )
          for
            projected <- ZIO.scoped(
              transform.projectRequest(ModelStreamContext("reply", "request"), request)
            )
            legacy <- ZIO.scoped(
              McpModelInputTransform()(ModelStreamContext("reply", "request"), history)
            )
            transformed    = projected.history.flatMap(_.toolResults)
            originalCustom = output.collect { case value: ContentBlock.Custom => value }
            literal = transformed
              .flatMap(_.output)
              .collect:
                case ContentBlock.Text(value) if value.startsWith("Custom tool content: ") =>
                  value.stripPrefix("Custom tool content: ").fromJson[Json]
            text = ChatCompletionsRequest.encode(
              projected.history,
              Chunk.empty,
              LlmConfig("test-model")
            )
            images = ChatCompletionsRequest.encode(
              projected.history,
              Chunk.empty,
              LlmConfig("test-model"),
              toolResultFormat = ToolResultFormat.ImageParts
            )
          yield assertTrue(
            projected.settings == request.settings.copy(temperature = 0.25, seed = Some(7L)),
            projected.history == legacy,
            history.flatMap(_.toolResults) == Chunk(result),
            transformed.forall(value => value.copy(output = output) == result),
            projected.history.lastOption.exists(_.metadata == Map("host" -> Json.Bool(true))),
            literal == originalCustom.map(value =>
              Right(Json.Obj("kind" -> Json.Str(value.kind), "payload" -> value.payload))
            ),
            transformed.flatMap(_.output).collect { case value: ContentBlock.Image =>
              value
            } == output.collect { case value: ContentBlock.Image => value },
            transformed.flatMap(_.output).collect { case value: ContentBlock.Audio =>
              value
            } == output.collect { case value: ContentBlock.Audio => value },
            text.isRight == textSupported,
            images.isRight == imagesSupported
          )
        callCount  <- calls.get
        closeCount <- closed.get
        invalid <- ZIO.foreach(
          Chunk[Json](
            Json.Obj("duplicate" -> Json.Num(1), "duplicate" -> Json.Num(2)),
            Json.Str("\uD800")
          )
        ): payload =>
          ZIO
            .scoped(
              McpModelInputTransform()(
                ModelStreamContext("reply", "request"),
                Chunk(
                  Msg.tool(content =
                    Chunk(
                      ContentBlock.ToolResult(
                        "invalid",
                        output = Chunk(ContentBlock.Custom("untrusted", payload))
                      )
                    )
                  )
                )
              )
            )
            .either
      yield checks.foldLeft(
        assertTrue(callCount == cases.size, closeCount == cases.size, invalid.forall(_.isLeft))
      )(_ && _)
  )
