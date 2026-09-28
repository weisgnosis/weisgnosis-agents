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
package com.weisgnosis.agents.core.model

import zio.Chunk
import zio.json.*
import zio.json.ast.Json

/**
 * Sealed algebraic data type representing individual content blocks within a message.
 *
 * Corresponds to `io.agentscope.core.message.ContentBlock` in AgentScope Java.
 */
sealed trait ContentBlock

object ContentBlock:

  final case class Text(
      text: String
  ) extends ContentBlock derives JsonCodec

  final case class Thinking(
      thinking: String,
      metadata: Map[String, Json] = Map.empty
  ) extends ContentBlock derives JsonCodec:
    def thought: String                = thinking
    def signature: Option[String]      = metadata.get("thoughtSignature").flatMap(_.asString)
    def reasoningDetails: Option[Json] = metadata.get("reasoningDetails")

  final case class ToolUse(
      id: String,
      name: String,
      input: Json,
      content: Option[String] = None,
      metadata: Map[String, Json] = Map.empty
  ) extends ContentBlock derives JsonCodec:
    def isServerTool: Boolean =
      metadata.get("agentscope_server_tool").flatMap(_.asBoolean).getOrElse(false)
    def thoughtSignature: Option[String] =
      metadata.get("thoughtSignature").flatMap(_.asString)
    def withServerTool(isServer: Boolean): ToolUse =
      copy(metadata = metadata.updated("agentscope_server_tool", Json.Bool(isServer)))
    def withThoughtSignature(sig: String): ToolUse =
      copy(metadata = metadata.updated("thoughtSignature", Json.Str(sig)))

  final case class ToolResult(
      id: String,
      name: Option[String] = None,
      output: Chunk[ContentBlock] = Chunk.empty,
      state: ToolResultState = ToolResultState.Success,
      metadata: Map[String, Json] = Map.empty
  ) extends ContentBlock:
    def isServerTool: Boolean =
      metadata.get("agentscope_server_tool").flatMap(_.asBoolean).getOrElse(false)
    def isSuspended: Boolean =
      metadata.get("agentscope_suspended").flatMap(_.asBoolean).getOrElse(false)
    def toolUseId: String = id
    def outputText: String =
      output.collect { case ContentBlock.Text(t) => t }.mkString("\n")
    def text: String = outputText
    def withState(newState: ToolResultState): ToolResult =
      copy(state = newState)
    def withIdAndName(newId: String, newName: String): ToolResult =
      copy(id = newId, name = Some(newName))

  object ToolResult:
    def text(
        id: String,
        name: String,
        text: String,
        state: ToolResultState = ToolResultState.Success,
        metadata: Map[String, Json] = Map.empty
    ): ToolResult =
      ToolResult(
        id = id,
        name = Some(name),
        output = Chunk(ContentBlock.Text(text)),
        state = state,
        metadata = metadata
      )

    def error(errorMessage: String): ToolResult =
      ToolResult(
        id = "",
        name = None,
        output = Chunk(ContentBlock.Text(s"Error: $errorMessage")),
        state = ToolResultState.Error
      )

    def error(id: String, errorMessage: String): ToolResult =
      ToolResult(
        id = id,
        name = None,
        output = Chunk(ContentBlock.Text(s"[ERROR] $errorMessage")),
        state = ToolResultState.Error
      )

    def suspended(toolUseId: String, toolName: String, reason: Option[String] = None): ToolResult =
      ToolResult(
        id = toolUseId,
        name = Some(toolName),
        output = Chunk(ContentBlock.Text(reason.getOrElse("[Awaiting external execution]"))),
        state = ToolResultState.Running,
        metadata = Map("agentscope_suspended" -> Json.Bool(true))
      )

  final case class Image(
      source: MediaSource,
      id: Option[String] = None,
      name: Option[String] = None
  ) extends ContentBlock derives JsonCodec

  object Image:
    def url(url: String): Image                        = Image(MediaSource.url(url))
    def base64(mediaType: String, data: String): Image = Image(MediaSource.base64(mediaType, data))

  final case class Audio(
      source: MediaSource,
      id: Option[String] = None,
      name: Option[String] = None
  ) extends ContentBlock derives JsonCodec

  object Audio:
    def url(url: String): Audio                        = Audio(MediaSource.url(url))
    def base64(mediaType: String, data: String): Audio = Audio(MediaSource.base64(mediaType, data))

  final case class Video(
      source: MediaSource,
      id: Option[String] = None,
      name: Option[String] = None
  ) extends ContentBlock derives JsonCodec

  object Video:
    def url(url: String): Video                        = Video(MediaSource.url(url))
    def base64(mediaType: String, data: String): Video = Video(MediaSource.base64(mediaType, data))

  final case class Data(
      source: MediaSource,
      id: Option[String] = None,
      name: Option[String] = None
  ) extends ContentBlock derives JsonCodec

  object Data:
    def url(url: String, name: Option[String] = None): Data =
      Data(MediaSource.url(url), name = name)
    def base64(mediaType: String, data: String, name: Option[String] = None): Data =
      Data(MediaSource.base64(mediaType, data), name = name)

  final case class Hint(
      id: Option[String] = None,
      hint: String,
      source: Option[String] = None
  ) extends ContentBlock derives JsonCodec

  final case class Custom(
      kind: String,
      payload: Json
  ) extends ContentBlock derives JsonCodec

  private def decodeOutput(jsonOpt: Option[Json]): Either[String, Chunk[ContentBlock]] =
    jsonOpt match
      case None | Some(Json.Null) => Right(Chunk.empty)
      case Some(Json.Str(str))    => Right(Chunk(ContentBlock.Text(str)))
      case Some(Json.Arr(elements)) =>
        val results = elements.map(el => el.as[ContentBlock])
        val lefts   = results.collect { case Left(err) => err }
        if lefts.nonEmpty then Left(lefts.mkString("; "))
        else Right(Chunk.fromIterable(results.collect { case Right(b) => b }))
      case Some(other) =>
        Left(s"Invalid output field: expected string or block array, got: $other")

  given JsonCodec[ToolResult] = summon[JsonCodec[Json]].transformOrFail(
    json =>
      for
        obj <- json.asObject.toRight("ToolResult must be a JSON object")
        id  <- obj.get("id").toRight("Missing 'id' in ToolResult").flatMap(_.as[String])
        name = obj.get("name").flatMap(_.as[String].toOption)
        output <- decodeOutput(obj.get("output"))
        state = obj
          .get("state")
          .flatMap(_.as[ToolResultState].toOption)
          .getOrElse(ToolResultState.Success)
        metadata = obj
          .get("metadata")
          .flatMap(_.as[Map[String, Json]].toOption)
          .getOrElse(Map.empty)
      yield ToolResult(id, name, output, state, metadata),
    tr =>
      val baseFields: Chunk[(String, Json)] = Chunk(
        "type"   -> Json.Str("tool_result"),
        "id"     -> Json.Str(tr.id),
        "state"  -> tr.state.toJsonAST.getOrElse(Json.Str("success")),
        "output" -> Json.Arr(tr.output.map(b => b.toJsonAST.getOrElse(Json.Null)))
      )
      val nameField = tr.name.map(n => "name" -> Json.Str(n))
      val metaField =
        if tr.metadata.nonEmpty then Some("metadata" -> Json.Obj(Chunk.fromIterable(tr.metadata)))
        else None
      Json.Obj(baseFields ++ Chunk.fromIterable(nameField) ++ Chunk.fromIterable(metaField))
  )

  given JsonCodec[ContentBlock] = summon[JsonCodec[Json]].transformOrFail(
    json =>
      val obj = json.asObject.getOrElse(Json.Obj.empty)
      obj.get("type").flatMap(_.asString) match
        case Some("text") => json.as[Text]
        case Some("thinking") =>
          val thinkingText = obj.get("thinking").flatMap(_.asString).getOrElse("")
          val meta =
            obj.get("metadata").flatMap(_.as[Map[String, Json]].toOption).getOrElse(Map.empty)
          Right(Thinking(thinkingText, meta))
        case Some("tool_use")    => json.as[ToolUse]
        case Some("tool_result") => json.as[ToolResult]
        case Some("image")       => json.as[Image]
        case Some("audio")       => json.as[Audio]
        case Some("video")       => json.as[Video]
        case Some("data")        => json.as[Data]
        case Some("hint")        => json.as[Hint]
        case Some("custom")      => json.as[Custom]
        case other => Left(s"Unknown content block type: ${other.getOrElse("missing type field")}"),
    {
      case b: Text =>
        b.toJsonAST.fold(
          _ => Json.Obj("type" -> Json.Str("text"), "text" -> Json.Str(b.text)),
          ast => ast.mapObject(_.add("type", Json.Str("text")))
        )
      case b: Thinking =>
        b.toJsonAST.fold(
          _ => Json.Obj("type" -> Json.Str("thinking"), "thinking" -> Json.Str(b.thinking)),
          ast => ast.mapObject(_.add("type", Json.Str("thinking")))
        )
      case b: ToolUse =>
        b.toJsonAST.fold(
          _ =>
            Json.Obj(
              "type" -> Json.Str("tool_use"),
              "id"   -> Json.Str(b.id),
              "name" -> Json.Str(b.name)
            ),
          ast => ast.mapObject(_.add("type", Json.Str("tool_use")))
        )
      case b: ToolResult =>
        b.toJsonAST.fold(
          _ => Json.Obj("type" -> Json.Str("tool_result"), "id" -> Json.Str(b.id)),
          ast => ast.mapObject(_.add("type", Json.Str("tool_result")))
        )
      case b: Image =>
        b.toJsonAST.fold(
          _ => Json.Obj("type" -> Json.Str("image")),
          ast => ast.mapObject(_.add("type", Json.Str("image")))
        )
      case b: Audio =>
        b.toJsonAST.fold(
          _ => Json.Obj("type" -> Json.Str("audio")),
          ast => ast.mapObject(_.add("type", Json.Str("audio")))
        )
      case b: Video =>
        b.toJsonAST.fold(
          _ => Json.Obj("type" -> Json.Str("video")),
          ast => ast.mapObject(_.add("type", Json.Str("video")))
        )
      case b: Data =>
        b.toJsonAST.fold(
          _ => Json.Obj("type" -> Json.Str("data")),
          ast => ast.mapObject(_.add("type", Json.Str("data")))
        )
      case b: Hint =>
        b.toJsonAST.fold(
          _ => Json.Obj("type" -> Json.Str("hint"), "hint" -> Json.Str(b.hint)),
          ast => ast.mapObject(_.add("type", Json.Str("hint")))
        )
      case b: Custom =>
        b.toJsonAST.fold(
          _ =>
            Json.Obj(
              "type"    -> Json.Str("custom"),
              "kind"    -> Json.Str(b.kind),
              "payload" -> b.payload
            ),
          ast => ast.mapObject(_.add("type", Json.Str("custom")))
        )
    }
  )
