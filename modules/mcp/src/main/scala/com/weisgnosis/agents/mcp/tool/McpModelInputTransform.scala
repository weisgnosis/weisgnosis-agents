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
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.event.ModelStreamContext
import com.weisgnosis.agents.core.json.JsonBoundary
import com.weisgnosis.agents.core.model.{ContentBlock, Msg}
import zio.*
import zio.json.ast.Json

/**
 * Explicit request-only descriptions of custom tool content. Every kind and payload is retained
 * as literal JSON text; tags grant no authority. Canonical history and typed media stay unchanged.
 * Image output still requires an endpoint profile that supports it; audio is not adapted here.
 */
object McpModelInputTransform:
  /** Install through ReActAgent's modelInputTransform argument or builder slot, with the prior hook. */
  def apply(base: ModelInputTransform = ModelInputTransform.identity): ModelInputTransform =
    val selected = ModelInputTransform.withRequest(base): (_, request) =>
      project(request.history).map(ModelInputProjection(_, request.settings))
    new ModelInputTransform:
      def apply(
          context: ModelStreamContext,
          history: Chunk[Msg]
      ): ZIO[Scope, AgentError.ModelInputError, Chunk[Msg]] =
        ZIO.suspendSucceed(base(context, history)).flatMap(project)

      override def projectRequest(
          context: ModelStreamContext,
          request: ModelRequestInput
      ): ZIO[Scope, AgentError.ModelInputError, ModelInputProjection] =
        selected.projectRequest(context, request)

  private def project(history: Chunk[Msg]): IO[AgentError.ModelInputError, Chunk[Msg]] =
    ZIO.foreach(history): message =>
      ZIO
        .foreach(message.content):
          case result: ContentBlock.ToolResult =>
            ZIO
              .foreach(result.output):
                case ContentBlock.Custom(kind, payload) =>
                  val description = Json.Obj("kind" -> Json.Str(kind), "payload" -> payload)
                  ZIO
                    .fromEither(
                      JsonBoundary.validate(description).left.map(AgentError.ModelInputError(_))
                    )
                    .filterOrFail(JsonBoundary.validUnicode)(
                      AgentError.ModelInputError("Custom tool content contains malformed Unicode")
                    )
                    .map(value =>
                      ContentBlock.Text("Custom tool content: " + JsonBoundary.render(value))
                    )
                case other => ZIO.succeed(other)
              .map(output => result.copy(output = output))
          case other => ZIO.succeed(other)
        .flatMap(content =>
          ZIO.fromEither(
            message
              .withContent(content)
              .left
              .map(error => AgentError.ModelInputError(error.message))
          )
        )
