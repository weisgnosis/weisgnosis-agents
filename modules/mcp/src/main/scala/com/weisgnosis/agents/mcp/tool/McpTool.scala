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
import com.weisgnosis.agents.core.tool.{
  Tool,
  ToolDef,
  ToolExecutionResult,
  ToolFixedArguments,
  ToolInvocation,
  ToolResultPayload
}
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
      definition: ToolDef,
      metadata: McpToolMetadata = McpToolMetadata.Omit
  ): Tool[Any, Json.Obj, McpCallResult] =
    bind(client, remoteName, McpToolBinding(definition, metadata))

  /** Borrow a complete host binding; presets merge after authorization and do not grant authority. */
  def bind(
      client: McpClient,
      remoteName: String,
      binding: McpToolBinding
  ): Tool[Any, Json.Obj, McpCallResult] =
    new Tool[Any, Json.Obj, McpCallResult]:
      def definition: ToolDef = binding.definition
      override def inputValidationSchema: Json =
        binding.inputValidationSchema.getOrElse(definition.parametersSchema)
      override def fixedArguments: ToolFixedArguments = binding.fixedArguments

      def decodeInput(raw: Json): Either[String, Json.Obj] =
        fixedArguments
          .prepare(raw)
          .left
          .map(_.message)
          .flatMap(JsonBoundary.validate)
          .flatMap:
            case value: Json.Obj => Right(value)
            case _               => Left("MCP tool arguments must be a JSON object")

      def execute(input: Json.Obj): IO[AgentError, McpCallResult] =
        executeWithContext(input, ToolInvocation.Detached)

      override def executeWithContext(
          input: Json.Obj,
          invocation: ToolInvocation
      ): IO[AgentError, McpCallResult] =
        ZIO
          .fromEither(binding.presets.merge(input))
          .flatMap(arguments =>
            requestMetadata(client, binding.metadata, invocation)
              .flatMap(meta => client.callTool(remoteName, Some(arguments), meta))
          )

      override def encodeResult(out: McpCallResult): ToolExecutionResult = project(out)

      def encodeOutput(out: McpCallResult): String = encodeResult(out).output

  private def requestMetadata(
      client: McpClient,
      policy: McpToolMetadata,
      invocation: ToolInvocation
  ): IO[AgentError, Option[Json.Obj]] = policy match
    case McpToolMetadata.Omit => ZIO.none
    case McpToolMetadata.Propagate(resolve) =>
      client.toolMetadataPropagation.flatMap:
        case McpToolMetadataPropagation.Omit => ZIO.none
        case McpToolMetadataPropagation.Allow =>
          val identity = invocation match
            case ToolInvocation.Detached => McpToolIdentity.Detached
            case ToolInvocation.Active(context, call, ordinal, _) =>
              McpToolIdentity.Active(context.replyId, context.requestId, call.id, ordinal)
          ZIO
            .suspendSucceed(resolve(identity))
            .flatMap: value =>
              ZIO
                .fromEither(
                  JsonBoundary.validate(value).left.map(AgentError.InvalidMessage(_))
                )
                .as(Some(identity match
                  case McpToolIdentity.Detached => value
                  case McpToolIdentity.Active(_, _, callId, _) =>
                    Json.Obj(
                      value.fields.filterNot(_._1 == "io.agentscope/toolCallId") :+
                        ("io.agentscope/toolCallId" -> Json.Str(callId))
                    )))

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
        result.content.flatMap(content => blocks(content.block)),
        Map("mcp" -> metadata)
      ),
      state
    )

  /** Resource metadata indexes remote content; visible companions do not add metadata entries. */
  private def blocks(value: McpContentBlock): Chunk[ContentBlock] = value match
    case McpContentBlock.Resource(resource) =>
      val visible = resource.body match
        case McpResourceBody.Text(text)           => ContentBlock.Text(text)
        case McpResourceBody.TextAndBlob(text, _) => ContentBlock.Text(text)
        case McpResourceBody.Blob(blob) =>
          resource.mimeType match
            case Some(mimeType) if mimeType.startsWith("image/") =>
              ContentBlock.Image.base64(mimeType, blob)
            case mimeType =>
              ContentBlock.Text(
                s"[Binary resource: ${mimeType.getOrElse("unknown MIME type")}, URI: ${resource.uri}]"
              )
      Chunk(visible, block(value))
    case _ => Chunk(block(value))

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
