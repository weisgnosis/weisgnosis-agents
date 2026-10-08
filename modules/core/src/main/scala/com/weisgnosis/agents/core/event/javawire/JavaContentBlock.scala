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
package com.weisgnosis.agents.core.event.javawire

import com.weisgnosis.agents.core.model.{ToolCallState, ToolResultState}
import com.weisgnosis.agents.core.json.JsonBoundary
import zio.Chunk
import zio.json.*
import zio.json.ast.Json

@jsonDiscriminator("type")
enum JavaContentSource derives JsonCodec:
  @jsonHint("url") case Url(url: String, @jsonField("mime_type") mimeType: Option[String] = None)
  @jsonHint("base64") case Base64(@jsonField("media_type") mediaType: String, data: String)

/** Pinned message content DTOs, independent of canonical Scala ContentBlock serialization. */
sealed trait JavaContentBlock

object JavaContentBlock:
  final case class Text(text: String) extends JavaContentBlock
  final case class Thinking(thinking: String, metadata: Option[Map[String, Json]] = None)
      extends JavaContentBlock
  final case class Image(
      source: JavaContentSource,
      minPixels: Option[Int] = None,
      maxPixels: Option[Int] = None
  ) extends JavaContentBlock
  final case class Audio(source: JavaContentSource) extends JavaContentBlock
  final case class Video(
      source: JavaContentSource,
      fps: Option[BigDecimal] = None,
      maxFrames: Option[Int] = None,
      minPixels: Option[Int] = None,
      maxPixels: Option[Int] = None,
      totalPixels: Option[Int] = None
  ) extends JavaContentBlock

  /** Unlike the Java convenience constructor, callers must explicitly supply the data identity. */
  final case class Data(source: JavaContentSource, id: String, name: Option[String] = None)
      extends JavaContentBlock
  final case class Hint(id: Option[String], hint: Option[String], source: Option[String])
      extends JavaContentBlock
  final case class ToolUse(
      id: Option[String],
      name: Option[String],
      input: Map[String, Json],
      content: Option[String] = None,
      metadata: Map[String, Json] = Map.empty,
      state: ToolCallState = ToolCallState.Pending
  ) extends JavaContentBlock
  final case class ToolResult(
      id: Option[String],
      name: Option[String],
      output: Chunk[JavaContentBlock] = Chunk.empty,
      metadata: Map[String, Json] = Map.empty,
      state: ToolResultState = ToolResultState.Running
  ) extends JavaContentBlock

  final private case class TextWire(text: String) derives JsonCodec
  final private case class ThinkingWire(thinking: String, metadata: Option[Map[String, Json]])
      derives JsonCodec
  final private case class ImageWire(
      source: JavaContentSource,
      @jsonAliases("min_pixels") minPixels: Option[Int],
      @jsonAliases("max_pixels") maxPixels: Option[Int]
  ) derives JsonCodec
  final private case class AudioWire(source: JavaContentSource) derives JsonCodec
  final private case class VideoWire(
      source: JavaContentSource,
      fps: Option[BigDecimal],
      @jsonAliases("max_frames") maxFrames: Option[Int],
      @jsonAliases("min_pixels") minPixels: Option[Int],
      @jsonAliases("max_pixels") maxPixels: Option[Int],
      @jsonAliases("total_pixels") totalPixels: Option[Int]
  ) derives JsonCodec
  final private case class DataWire(source: JavaContentSource, id: String, name: Option[String])
      derives JsonCodec
  final private case class HintWire(
      id: Option[String],
      hint: Option[String],
      source: Option[String]
  ) derives JsonCodec
  final private case class ToolUseWire(
      id: Option[String],
      name: Option[String],
      input: Map[String, Json] = Map.empty,
      content: Option[String],
      metadata: Map[String, Json] = Map.empty,
      state: ToolCallState = ToolCallState.Pending
  ) derives JsonCodec
  final private case class ToolResultWire(
      id: Option[String],
      name: Option[String],
      output: Chunk[JavaContentBlock] = Chunk.empty,
      metadata: Map[String, Json] = Map.empty,
      state: ToolResultState = ToolResultState.Running
  ) derives JsonCodec

  private def decode(value: Json): Either[String, JavaContentBlock] =
    JsonBoundary
      .validate(value)
      .flatMap: checked =>
        val text = JsonBoundary.render(checked)
        checked match
          case Json.Obj(fields) =>
            fields.find(_._1 == "type").map(_._2) match
              case Some(Json.Str("text")) => text.fromJson[TextWire].map(value => Text(value.text))
              case Some(Json.Str("thinking")) =>
                text.fromJson[ThinkingWire].map(value => Thinking(value.thinking, value.metadata))
              case Some(Json.Str("image")) =>
                text
                  .fromJson[ImageWire]
                  .map(value => Image(value.source, value.minPixels, value.maxPixels))
              case Some(Json.Str("audio")) =>
                text.fromJson[AudioWire].map(value => Audio(value.source))
              case Some(Json.Str("video")) =>
                text
                  .fromJson[VideoWire]
                  .map(value =>
                    Video(
                      value.source,
                      value.fps,
                      value.maxFrames,
                      value.minPixels,
                      value.maxPixels,
                      value.totalPixels
                    )
                  )
              case Some(Json.Str("data")) =>
                text
                  .fromJson[DataWire]
                  .flatMap(value =>
                    Either.cond(
                      value.id.nonEmpty,
                      Data(value.source, value.id, value.name),
                      "Java data identity must be explicit and nonempty"
                    )
                  )
              case Some(Json.Str("hint")) =>
                text.fromJson[HintWire].map(value => Hint(value.id, value.hint, value.source))
              case Some(Json.Str("tool_use")) =>
                text
                  .fromJson[ToolUseWire]
                  .map(value =>
                    ToolUse(
                      value.id,
                      value.name,
                      value.input,
                      value.content,
                      value.metadata,
                      value.state
                    )
                  )
              case Some(Json.Str("tool_result")) =>
                text
                  .fromJson[ToolResultWire]
                  .map(value =>
                    ToolResult(value.id, value.name, value.output, value.metadata, value.state)
                  )
              case _ => Left("Unknown or missing Java content type")
          case _ => Left("Java content must be an object")

  private def nullable(value: Option[String]): Json       = value.fold[Json](Json.Null)(Json.Str(_))
  private def objectValue(value: Map[String, Json]): Json = Json.Obj(Chunk.fromIterable(value))
  private def sourceValue(value: JavaContentSource): Json = value match
    case JavaContentSource.Url(url, mimeType) =>
      Json.Obj(
        Chunk("type" -> Json.Str("url"), "url" -> Json.Str(url)) ++
          Chunk.fromIterable(mimeType.map(value => "mime_type" -> Json.Str(value)))
      )
    case JavaContentSource.Base64(mediaType, data) =>
      Json.Obj(
        "type"       -> Json.Str("base64"),
        "media_type" -> Json.Str(mediaType),
        "data"       -> Json.Str(data)
      )

  // Explicit AST encoding implements class-specific Jackson omission/null/getter rules.
  private def encode(value: JavaContentBlock): Json =
    val tagged: (String, Chunk[(String, Json)]) = value match
      case Text(text) => ("text", Chunk("text" -> Json.Str(text)))
      case Thinking(thinking, metadata) =>
        (
          "thinking",
          Chunk(
            "thinking" -> Json.Str(thinking),
            "metadata" -> metadata.fold[Json](Json.Null)(objectValue)
          )
        )
      case Image(source, minPixels, maxPixels) =>
        (
          "image",
          Chunk("source" -> sourceValue(source)) ++ optionalNumbers(
            Chunk(
              "minPixels" -> minPixels.map(BigDecimal(_)),
              "maxPixels" -> maxPixels.map(BigDecimal(_))
            )
          )
        )
      case Audio(source) => ("audio", Chunk("source" -> sourceValue(source)))
      case Video(source, fps, maxFrames, minPixels, maxPixels, totalPixels) =>
        (
          "video",
          Chunk("source" -> sourceValue(source)) ++ optionalNumbers(
            Chunk(
              "fps"         -> fps,
              "maxFrames"   -> maxFrames.map(BigDecimal(_)),
              "minPixels"   -> minPixels.map(BigDecimal(_)),
              "maxPixels"   -> maxPixels.map(BigDecimal(_)),
              "totalPixels" -> totalPixels.map(BigDecimal(_))
            )
          )
        )
      case Data(source, id, name) =>
        (
          "data",
          Chunk("source" -> sourceValue(source), "id" -> Json.Str(id)) ++
            Chunk.fromIterable(name.map(value => "name" -> Json.Str(value)))
        )
      case Hint(id, hint, source) =>
        (
          "hint",
          Chunk("id" -> nullable(id), "hint" -> nullable(hint), "source" -> nullable(source))
        )
      case ToolUse(id, name, input, content, metadata, state) =>
        val encodedState = state match
          case ToolCallState.Pending   => "pending"
          case ToolCallState.Asking    => "asking"
          case ToolCallState.Allowed   => "allowed"
          case ToolCallState.Submitted => "submitted"
          case ToolCallState.Finished  => "finished"
        (
          "tool_use",
          Chunk(
            "id"       -> nullable(id),
            "name"     -> nullable(name),
            "input"    -> objectValue(input),
            "content"  -> nullable(content),
            "metadata" -> objectValue(metadata),
            "state"    -> Json.Str(encodedState)
          )
        )
      case ToolResult(id, name, output, metadata, state) =>
        val encodedState = state match
          case ToolResultState.Success     => "success"
          case ToolResultState.Error       => "error"
          case ToolResultState.Interrupted => "interrupted"
          case ToolResultState.Denied      => "denied"
          case ToolResultState.Running     => "running"
        (
          "tool_result",
          Chunk(
            "id"       -> nullable(id),
            "name"     -> nullable(name),
            "output"   -> Json.Arr(output.map(encode)),
            "metadata" -> objectValue(metadata),
            "state"    -> Json.Str(encodedState)
          )
        )
    val (tag, fields) = tagged
    Json.Obj(Chunk("type" -> Json.Str(tag)) ++ fields)

  private def optionalNumbers(values: Chunk[(String, Option[BigDecimal])]): Chunk[(String, Json)] =
    values.flatMap((name, value) =>
      Chunk.fromIterable(value.map(number => name -> Json.Num(number)))
    )

  private def decodeToolUse(value: Json): Either[String, ToolUse] =
    decode(value).flatMap:
      case result: ToolUse => Right(result)
      case _               => Left("Expected Java tool_use content")

  private def decodeToolResult(value: Json): Either[String, ToolResult] =
    decode(value).flatMap:
      case result: ToolResult => Right(result)
      case _                  => Left("Expected Java tool_result content")

  given JsonCodec[JavaContentBlock] = JsonCodec[Json].transformOrFail(decode, encode)
  given JsonCodec[ToolUse]          = JsonCodec[Json].transformOrFail(decodeToolUse, encode)
  given JsonCodec[ToolResult]       = JsonCodec[Json].transformOrFail(decodeToolResult, encode)
