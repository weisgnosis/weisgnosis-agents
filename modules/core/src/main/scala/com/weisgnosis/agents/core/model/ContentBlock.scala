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

import zio.{Chunk, Random, UIO, ZIO}
import zio.json.*
import zio.json.ast.Json

/**
 * Sealed algebraic data type representing individual content blocks within a message.
 *
 * Corresponds to `io.agentscope.core.message.ContentBlock` in AgentScope Java.
 */
@jsonDiscriminator("type")
sealed trait ContentBlock

object ContentBlock:

  @jsonHint("text")
  final case class Text(
      text: String
  ) extends ContentBlock

  object Text:
    given JsonCodec[Text] = taggedCodec("text", PayloadCodecs.text)

  @jsonHint("thinking")
  final case class Thinking(
      thinking: String,
      metadata: Map[String, Json] = Map.empty
  ) extends ContentBlock:
    def thought: String                = thinking
    def signature: Option[String]      = metadata.get("thoughtSignature").flatMap(_.asString)
    def reasoningDetails: Option[Json] = metadata.get("reasoningDetails")

  object Thinking:
    given JsonCodec[Thinking] = taggedCodec("thinking", PayloadCodecs.thinking)

  @jsonHint("tool_use")
  final case class ToolUse(
      id: String,
      name: String,
      input: Json,
      content: Option[String] = None,
      metadata: Map[String, Json] = Map.empty,
      state: ToolCallState = ToolCallState.Pending
  ) extends ContentBlock:
    def isServerTool: Boolean = serverTool(metadata)
    def thoughtSignature: Option[String] =
      metadata.get("thoughtSignature").flatMap(_.asString)
    def withServerTool(isServer: Boolean): ToolUse =
      copy(metadata = serverToolMetadata(metadata, isServer))
    def withState(value: ToolCallState): ToolUse = copy(state = value)
    def withThoughtSignature(sig: String): ToolUse =
      copy(metadata = metadata.updated("thoughtSignature", Json.Str(sig)))

  object ToolUse:
    given JsonCodec[ToolUse] = taggedCodec("tool_use", PayloadCodecs.toolUse)

  @jsonHint("tool_result")
  final case class ToolResult(
      id: String,
      name: Option[String] = None,
      output: Chunk[ContentBlock] = Chunk.empty,
      state: ToolResultState = ToolResultState.Success,
      metadata: Map[String, Json] = Map.empty
  ) extends ContentBlock:
    def isServerTool: Boolean =
      serverTool(metadata)
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
    given JsonCodec[ToolResult] = taggedCodec("tool_result", PayloadCodecs.toolResult)

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

  @jsonHint("image")
  final case class Image(
      source: MediaSource,
      id: Option[String] = None,
      name: Option[String] = None
  ) extends ContentBlock

  object Image:
    given JsonCodec[Image] = taggedCodec("image", PayloadCodecs.image)

    def url(url: String): Image                        = Image(MediaSource.url(url))
    def base64(mediaType: String, data: String): Image = Image(MediaSource.base64(mediaType, data))

  @jsonHint("audio")
  final case class Audio(
      source: MediaSource,
      id: Option[String] = None,
      name: Option[String] = None
  ) extends ContentBlock

  object Audio:
    given JsonCodec[Audio] = taggedCodec("audio", PayloadCodecs.audio)

    def url(url: String): Audio                        = Audio(MediaSource.url(url))
    def base64(mediaType: String, data: String): Audio = Audio(MediaSource.base64(mediaType, data))

  @jsonHint("video")
  final case class Video(
      source: MediaSource,
      id: Option[String] = None,
      name: Option[String] = None
  ) extends ContentBlock

  object Video:
    given JsonCodec[Video] = taggedCodec("video", PayloadCodecs.video)

    def url(url: String): Video                        = Video(MediaSource.url(url))
    def base64(mediaType: String, data: String): Video = Video(MediaSource.base64(mediaType, data))

  @jsonHint("data")
  final case class Data(
      source: MediaSource,
      id: Option[String] = None,
      name: Option[String] = None
  ) extends ContentBlock:
    /** Pure construction preserves optional identity; allocation occurs only when this effect runs. */
    def identified: UIO[Data] = id match
      case Some(_) => ZIO.succeed(this)
      case None    => Random.nextUUID.map(value => copy(id = Some(value.toString)))

  object Data:
    given JsonCodec[Data] = taggedCodec("data", PayloadCodecs.data)

    def url(url: String, name: Option[String] = None): Data =
      Data(MediaSource.url(url), name = name)
    def base64(mediaType: String, data: String, name: Option[String] = None): Data =
      Data(MediaSource.base64(mediaType, data), name = name)

  @jsonHint("hint")
  final case class Hint(
      id: Option[String] = None,
      hint: String,
      source: Option[String] = None
  ) extends ContentBlock

  object Hint:
    given JsonCodec[Hint] = taggedCodec("hint", PayloadCodecs.hint)

  @jsonHint("custom")
  final case class Custom(
      kind: String,
      payload: Json
  ) extends ContentBlock

  object Custom:
    given JsonCodec[Custom] = taggedCodec("custom", PayloadCodecs.custom)

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

  private def serverTool(metadata: Map[String, Json]): Boolean =
    metadata
      .get("serverTool")
      .orElse(metadata.get("agentscope_server_tool"))
      .flatMap(_.asBoolean)
      .getOrElse(false)

  private def serverToolMetadata(metadata: Map[String, Json], value: Boolean): Map[String, Json] =
    val updated = metadata.updated("agentscope_server_tool", Json.Bool(value))
    if metadata.contains("serverTool") then updated.updated("serverTool", Json.Bool(value))
    else updated

  final private case class ToolResultPayload(
      id: String,
      name: Option[String],
      output: Chunk[ContentBlock],
      state: ToolResultState,
      metadata: Option[Map[String, Json]]
  ) derives JsonCodec

  private object PayloadCodecs:
    given text: JsonCodec[Text] = DeriveJsonCodec.gen[Text]
    given thinking: JsonCodec[Thinking] = JsonCodec(
      DeriveJsonEncoder.gen[Thinking],
      summon[JsonDecoder[Json]].mapOrFail(json =>
        for
          obj      <- json.asObject.toRight("Thinking must be a JSON object")
          value    <- JsonFields.optional[String](obj, "thinking").map(_.getOrElse(""))
          metadata <- JsonFields.metadata(obj)
        yield Thinking(value, metadata)
      )
    )
    given toolUse: JsonCodec[ToolUse] = JsonCodec(
      DeriveJsonEncoder.gen[ToolUse],
      summon[JsonDecoder[Json]].mapOrFail(json =>
        for
          obj      <- json.asObject.toRight("ToolUse must be a JSON object")
          id       <- obj.get("id").toRight("Missing 'id' in ToolUse").flatMap(_.as[String])
          name     <- obj.get("name").toRight("Missing 'name' in ToolUse").flatMap(_.as[String])
          input    <- JsonFields.optional[Json](obj, "input").map(_.getOrElse(Json.Obj()))
          content  <- JsonFields.optional[String](obj, "content")
          metadata <- JsonFields.metadata(obj)
          state <- JsonFields
            .optional[ToolCallState](obj, "state")
            .map(_.getOrElse(ToolCallState.Pending))
        yield ToolUse(id, name, input, content, metadata, state)
      )
    )
    given toolResult: JsonCodec[ToolResult] = JsonCodec(
      summon[JsonEncoder[ToolResultPayload]].contramap(result =>
        ToolResultPayload(
          result.id,
          result.name,
          result.output,
          result.state,
          Option.when(result.metadata.nonEmpty)(result.metadata)
        )
      ),
      summon[JsonDecoder[Json]].mapOrFail(json =>
        for
          obj    <- json.asObject.toRight("ToolResult must be a JSON object")
          id     <- obj.get("id").toRight("Missing 'id' in ToolResult").flatMap(_.as[String])
          name   <- JsonFields.optional[String](obj, "name")
          output <- decodeOutput(obj.get("output"))
          state <- JsonFields
            .optional[ToolResultState](obj, "state")
            .map(_.getOrElse(ToolResultState.Success))
          metadata <- JsonFields.metadata(obj)
        yield ToolResult(id, name, output, state, metadata)
      )
    )
    given image: JsonCodec[Image]   = DeriveJsonCodec.gen[Image]
    given audio: JsonCodec[Audio]   = DeriveJsonCodec.gen[Audio]
    given video: JsonCodec[Video]   = DeriveJsonCodec.gen[Video]
    given data: JsonCodec[Data]     = DeriveJsonCodec.gen[Data]
    given hint: JsonCodec[Hint]     = DeriveJsonCodec.gen[Hint]
    given custom: JsonCodec[Custom] = DeriveJsonCodec.gen[Custom]

  private lazy val wireCodec: JsonCodec[ContentBlock] =
    import PayloadCodecs.given
    DeriveJsonCodec.gen[ContentBlock]

  private def blockDecoder[A](
      payload: => JsonDecoder[A],
      expected: Option[String]
  ): JsonDecoder[A] =
    summon[JsonDecoder[Json]].mapOrFail {
      case obj: Json.Obj =>
        val tags = obj.fields.collect { case ("type", value) => value }.toList
        tags match
          case Nil if expected.nonEmpty                         => payload.fromJsonAST(obj)
          case List(Json.Str(tag)) if expected.forall(_ == tag) => payload.fromJsonAST(obj)
          case _ => Left("Missing, duplicate, or invalid content discriminator")
      case _ => Left("Expected a content block object")
    }

  private def taggedCodec[A <: ContentBlock](tag: String, payload: JsonCodec[A]): JsonCodec[A] =
    JsonCodec(
      JsonEncoder.suspend(wireCodec.encoder).contramap[A](identity),
      blockDecoder(payload.decoder, Some(tag))
    )

  given JsonCodec[ContentBlock] = JsonCodec(
    JsonEncoder.suspend(wireCodec.encoder),
    blockDecoder(wireCodec.decoder, None)
  )
