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
package com.weisgnosis.agents.mcp.protocol

import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.json.JsonBoundary
import zio.Chunk
import zio.json.ast.Json

enum McpCallOutcome:
  case Success, Error

enum McpAudience:
  case User, Assistant

enum McpResourceBody:
  case Text(value: String)
  case Blob(value: String)
  case TextAndBlob(text: String, blob: String)

final case class McpAnnotations(
    audience: Option[Chunk[McpAudience]],
    priority: Option[BigDecimal],
    lastModified: Option[String],
    extensions: Json.Obj
)

final case class McpResourceLink(
    uri: String,
    name: String,
    title: Option[String],
    description: Option[String],
    mimeType: Option[String],
    size: Option[BigDecimal]
)

final case class McpResourceContents(
    uri: String,
    body: McpResourceBody,
    mimeType: Option[String],
    meta: Option[Json.Obj],
    extensions: Json.Obj
)

enum McpContentBlock:
  case Text(text: String)
  case Image(data: String, mimeType: String)
  case Audio(data: String, mimeType: String)
  case ResourceLink(value: McpResourceLink)
  case Resource(value: McpResourceContents)

final case class McpContent(
    block: McpContentBlock,
    annotations: Option[McpAnnotations],
    meta: Option[Json.Obj],
    extensions: Json.Obj
)

final case class McpCallResult(
    content: Chunk[McpContent],
    structuredContent: Option[Json.Obj],
    outcome: McpCallOutcome,
    meta: Option[Json.Obj],
    extensions: Json.Obj
)

/**
 * Checked 2025-06-18 call-result projection, separate from JSON-RPC error envelopes. Remote strings
 * and annotations carry no local authority: URI, base64 and timestamp formats are not validated or
 * interpreted. isError absence and false both project to Success, so no round-trip codec is exposed.
 */
object McpCallResult:
  final private case class BlockProjection(block: McpContentBlock, fields: Set[String])

  def read(value: Json.Obj): Either[AgentError.DeserializationError, McpCallResult] =
    (for
      _          <- JsonBoundary.validate(value)
      content    <- required(value, "content").flatMap(array(_, "content", readContent))
      structured <- optional(value, "structuredContent", objectValue(_, "structuredContent"))
      outcome    <- optional(value, "isError", callOutcome)
      meta       <- optional(value, "_meta", objectValue(_, "_meta"))
    yield McpCallResult(
      content,
      structured,
      outcome.getOrElse(McpCallOutcome.Success),
      meta,
      extensions(value, Set("content", "structuredContent", "isError", "_meta"))
    )).left.map(AgentError.DeserializationError(_))

  private def readContent(value: Json): Either[String, McpContent] =
    for
      fields      <- objectValue(value, "content item")
      kind        <- required(fields, "type").flatMap(string(_, "content.type"))
      projection  <- block(fields, kind)
      annotations <- optional(fields, "annotations", readAnnotations)
      meta        <- optional(fields, "_meta", objectValue(_, "content._meta"))
    yield McpContent(
      projection.block,
      annotations,
      meta,
      extensions(fields, projection.fields ++ Set("type", "annotations", "_meta"))
    )

  private def block(fields: Json.Obj, kind: String): Either[String, BlockProjection] = kind match
    case "text" =>
      required(fields, "text")
        .flatMap(string(_, "text"))
        .map(text => BlockProjection(McpContentBlock.Text(text), Set("text")))
    case "image" => media(fields, McpContentBlock.Image(_, _))
    case "audio" => media(fields, McpContentBlock.Audio(_, _))
    case "resource_link" =>
      resourceLink(fields).map(link =>
        BlockProjection(
          McpContentBlock.ResourceLink(link),
          Set("uri", "name", "title", "description", "mimeType", "size")
        )
      )
    case "resource" =>
      required(fields, "resource")
        .flatMap(resourceContents)
        .map(resource => BlockProjection(McpContentBlock.Resource(resource), Set("resource")))
    case _ => Left(s"Unsupported content type: $kind")

  private def media(
      fields: Json.Obj,
      make: (String, String) => McpContentBlock
  ): Either[String, BlockProjection] =
    for
      data <- required(fields, "data").flatMap(string(_, "data"))
      mime <- required(fields, "mimeType").flatMap(string(_, "mimeType"))
    yield BlockProjection(make(data, mime), Set("data", "mimeType"))

  private def resourceLink(fields: Json.Obj): Either[String, McpResourceLink] =
    for
      uri         <- required(fields, "uri").flatMap(string(_, "uri"))
      name        <- required(fields, "name").flatMap(string(_, "name"))
      title       <- optional(fields, "title", string(_, "title"))
      description <- optional(fields, "description", string(_, "description"))
      mime        <- optional(fields, "mimeType", string(_, "mimeType"))
      size        <- optional(fields, "size", integralSize)
    yield McpResourceLink(uri, name, title, description, mime, size)

  private[mcp] def resourceContents(value: Json): Either[String, McpResourceContents] =
    for
      fields <- objectValue(value, "resource")
      uri    <- required(fields, "uri").flatMap(string(_, "resource.uri"))
      text   <- optional(fields, "text", string(_, "resource.text"))
      blob   <- optional(fields, "blob", string(_, "resource.blob"))
      body <- (text, blob) match
        case (Some(content), Some(binary)) => Right(McpResourceBody.TextAndBlob(content, binary))
        case (Some(content), None)         => Right(McpResourceBody.Text(content))
        case (None, Some(binary))          => Right(McpResourceBody.Blob(binary))
        case (None, None)                  => Left("resource requires text or blob")
      mime <- optional(fields, "mimeType", string(_, "resource.mimeType"))
      meta <- optional(fields, "_meta", objectValue(_, "resource._meta"))
    yield McpResourceContents(
      uri,
      body,
      mime,
      meta,
      extensions(fields, Set("uri", "text", "blob", "mimeType", "_meta"))
    )

  private[mcp] def readAnnotations(value: Json): Either[String, McpAnnotations] =
    for
      fields   <- objectValue(value, "annotations")
      audience <- optional(fields, "audience", array(_, "audience", role))
      priority <- optional(fields, "priority", priorityValue)
      modified <- optional(fields, "lastModified", string(_, "lastModified"))
    yield McpAnnotations(
      audience,
      priority,
      modified,
      extensions(fields, Set("audience", "priority", "lastModified"))
    )

  private def role(value: Json): Either[String, McpAudience] = value match
    case Json.Str("user")      => Right(McpAudience.User)
    case Json.Str("assistant") => Right(McpAudience.Assistant)
    case _                     => Left("audience entries must be user or assistant")

  private def priorityValue(value: Json): Either[String, BigDecimal] = value match
    case Json.Num(number)
        if number.signum >= 0 && number.compareTo(java.math.BigDecimal.ONE) <= 0 =>
      Right(BigDecimal(number))
    case _ => Left("priority must be a number between 0 and 1")

  private def integralSize(value: Json): Either[String, BigDecimal] = value match
    // Preserve compact decimal representation: never expand an exponent into a BigInt.
    case Json.Num(number)
        if number.signum == 0 || number.scale <= 0 || number.stripTrailingZeros.scale <= 0 =>
      Right(BigDecimal(number))
    case _ => Left("size must be an integer")

  private def callOutcome(value: Json): Either[String, McpCallOutcome] = value match
    case Json.Bool(false) => Right(McpCallOutcome.Success)
    case Json.Bool(true)  => Right(McpCallOutcome.Error)
    case _                => Left("isError must be a boolean")

  private def array[A](
      value: Json,
      name: String,
      decode: Json => Either[String, A]
  ): Either[String, Chunk[A]] = value match
    case Json.Arr(values) =>
      values.foldLeft[Either[String, Chunk[A]]](Right(Chunk.empty))((parsed, next) =>
        for
          items <- parsed
          item  <- decode(next)
        yield items :+ item
      )
    case _ => Left(s"$name must be an array")

  private def extensions(value: Json.Obj, known: Set[String]): Json.Obj = value match
    case Json.Obj(fields) => Json.Obj(fields.filterNot(entry => known.contains(entry._1)))

  private def required(value: Json.Obj, name: String): Either[String, Json] =
    value.get(name).toRight(s"Missing required field: $name")

  private def optional[A](
      value: Json.Obj,
      name: String,
      decode: Json => Either[String, A]
  ): Either[String, Option[A]] =
    value.get(name) match
      case None      => Right(None)
      case Some(raw) => decode(raw).map(Some(_))

  private def objectValue(value: Json, name: String): Either[String, Json.Obj] = value match
    case obj: Json.Obj => Right(obj)
    case _             => Left(s"$name must be a JSON object")

  private def string(value: Json, name: String): Either[String, String] = value match
    case Json.Str(text) => Right(text)
    case _              => Left(s"$name must be a string")
