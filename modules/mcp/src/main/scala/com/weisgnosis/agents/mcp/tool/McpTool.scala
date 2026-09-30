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

import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.json.JsonBoundary
import com.weisgnosis.agents.core.model.{ContentBlock, ToolResultState}
import com.weisgnosis.agents.core.tool.{Tool, ToolDef, ToolExecutionResult, ToolResultPayload}
import com.weisgnosis.agents.mcp.client.McpClient
import com.weisgnosis.agents.mcp.protocol.*
import zio.*
import zio.json.ast.Json

/**
 * Explicit host binding to one remote tool on a borrowed initialized client. The host supplies all
 * local definition and scheduling authority; remote annotations and metadata remain observation
 * data. Binding performs no discovery, acquisition or ownership transfer. Typed output preservation
 * does not supply provider-specific multimodal serialization.
 */
object McpTool:
  def bind(
      client: McpClient,
      remoteName: String,
      definition: ToolDef
  ): Tool[Any, Json.Obj, McpCallResult] =
    val hostDefinition = definition
    new Tool[Any, Json.Obj, McpCallResult]:
      def definition: ToolDef = hostDefinition

      def decodeInput(raw: Json): Either[String, Json.Obj] =
        JsonBoundary
          .validate(raw)
          .flatMap:
            case value: Json.Obj => Right(value)
            case _               => Left("MCP tool arguments must be a JSON object")

      def execute(input: Json.Obj): IO[AgentError, McpCallResult] =
        ZIO.suspendSucceed(client.callTool(remoteName, Some(input)))

      override def encodeResult(out: McpCallResult): ToolExecutionResult = project(out)

      def encodeOutput(out: McpCallResult): String = encodeResult(out).output

  /** Domain observation grouping, not an MCP wire encoder or round-trip representation. */
  private def project(result: McpCallResult): ToolExecutionResult =
    val metadata = obj(
      "structuredContent" -> result.structuredContent,
      "meta"              -> result.meta,
      "extensions"        -> Some(result.extensions),
      "content"           -> Some(Json.Arr(result.content.map(contentMetadata)))
    )
    val state = result.outcome match
      case McpCallOutcome.Success => ToolResultState.Success
      case McpCallOutcome.Error   => ToolResultState.Error
    ToolExecutionResult(
      ToolResultPayload(
        result.content.map(content => block(content.block)),
        Map("mcp" -> metadata)
      ),
      state
    )

  private def block(value: McpContentBlock): ContentBlock = value match
    case McpContentBlock.Text(text)            => ContentBlock.Text(text)
    case McpContentBlock.Image(data, mimeType) => ContentBlock.Image.base64(mimeType, data)
    case McpContentBlock.Audio(data, mimeType) => ContentBlock.Audio.base64(mimeType, data)
    case McpContentBlock.ResourceLink(link) =>
      ContentBlock.Custom(
        "mcp/resource_link",
        obj(
          "uri"         -> Some(Json.Str(link.uri)),
          "name"        -> Some(Json.Str(link.name)),
          "title"       -> link.title.map(Json.Str(_)),
          "description" -> link.description.map(Json.Str(_)),
          "mimeType"    -> link.mimeType.map(Json.Str(_)),
          "size"        -> link.size.map(Json.Num(_))
        )
      )
    case McpContentBlock.Resource(resource) =>
      val body = resource.body match
        case McpResourceBody.Text(text) => Json.Obj("text" -> Json.Str(text))
        case McpResourceBody.Blob(blob) => Json.Obj("blob" -> Json.Str(blob))
        case McpResourceBody.TextAndBlob(text, blob) =>
          Json.Obj("text" -> Json.Str(text), "blob" -> Json.Str(blob))
      ContentBlock.Custom(
        "mcp/resource",
        obj(
          "uri"        -> Some(Json.Str(resource.uri)),
          "body"       -> Some(body),
          "mimeType"   -> resource.mimeType.map(Json.Str(_)),
          "meta"       -> resource.meta,
          "extensions" -> Some(resource.extensions)
        )
      )

  private def contentMetadata(content: McpContent): Json.Obj =
    obj(
      "annotations" -> content.annotations.map(annotations),
      "meta"        -> content.meta,
      "extensions"  -> Some(content.extensions)
    )

  private def annotations(value: McpAnnotations): Json.Obj =
    obj(
      "audience" -> value.audience.map(roles =>
        Json.Arr(roles.map {
          case McpAudience.User      => Json.Str("user")
          case McpAudience.Assistant => Json.Str("assistant")
        })
      ),
      "priority"     -> value.priority.map(Json.Num(_)),
      "lastModified" -> value.lastModified.map(Json.Str(_)),
      "extensions"   -> Some(value.extensions)
    )

  private def obj(fields: (String, Option[Json])*): Json.Obj =
    Json.Obj(Chunk.fromIterable(fields.collect { case (name, Some(value)) => name -> value }))
