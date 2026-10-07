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

final case class McpResourceDefinition(
    uri: String,
    name: String,
    title: Option[String],
    description: Option[String],
    mimeType: Option[String],
    size: Option[BigDecimal],
    annotations: Option[McpAnnotations],
    meta: Option[Json.Obj],
    extensions: Json.Obj
)

final case class McpResourceTemplate(
    uriTemplate: String,
    name: String,
    title: Option[String],
    description: Option[String],
    mimeType: Option[String],
    annotations: Option[McpAnnotations],
    meta: Option[Json.Obj],
    extensions: Json.Obj
)

final case class McpResourcePage[+A](
    items: Chunk[A],
    nextCursor: Option[String],
    meta: Option[Json.Obj],
    extensions: Json.Obj
)

final case class McpReadResourceResult(
    contents: Chunk[McpResourceContents],
    meta: Option[Json.Obj],
    extensions: Json.Obj
)

enum McpResourceChange:
  case ListChanged(meta: Option[Json.Obj])
  case Updated(uri: String, meta: Option[Json.Obj])

/**
 * Checked resource wire projections, not codecs: unknown fields survive and duplicate fields fail
 * throughout the AST. URIs, URI templates, binary bodies and timestamps remain opaque remote data.
 * Reading never resolves local paths, downloads URLs, expands templates, or grants host authority.
 */
object McpResources:
  def listRequest(
      id: McpRequestId,
      cursor: Option[String] = None,
      meta: Option[Json.Obj] = None
  ): Either[AgentError.InvalidMessage, McpMessage.Request] =
    request(id, "resources/list", field("cursor", cursor.map(Json.Str(_))) ++ field("_meta", meta))

  def templatesRequest(
      id: McpRequestId,
      cursor: Option[String] = None,
      meta: Option[Json.Obj] = None
  ): Either[AgentError.InvalidMessage, McpMessage.Request] =
    request(
      id,
      "resources/templates/list",
      field("cursor", cursor.map(Json.Str(_))) ++ field("_meta", meta)
    )

  def readRequest(
      id: McpRequestId,
      uri: String,
      meta: Option[Json.Obj] = None
  ): Either[AgentError.InvalidMessage, McpMessage.Request] =
    uriRequest(id, "resources/read", uri, meta)

  def subscribeRequest(
      id: McpRequestId,
      uri: String,
      meta: Option[Json.Obj] = None
  ): Either[AgentError.InvalidMessage, McpMessage.Request] =
    uriRequest(id, "resources/subscribe", uri, meta)

  def unsubscribeRequest(
      id: McpRequestId,
      uri: String,
      meta: Option[Json.Obj] = None
  ): Either[AgentError.InvalidMessage, McpMessage.Request] =
    uriRequest(id, "resources/unsubscribe", uri, meta)

  def readListResult(
      value: Json.Obj
  ): Either[AgentError.DeserializationError, McpResourcePage[McpResourceDefinition]] =
    page(value, "resources", definition)

  def readTemplatesResult(
      value: Json.Obj
  ): Either[AgentError.DeserializationError, McpResourcePage[McpResourceTemplate]] =
    page(value, "resourceTemplates", template)

  def readResult(value: Json.Obj): Either[AgentError.DeserializationError, McpReadResourceResult] =
    checked(value):
      for
        contents <- required(value, "contents").flatMap(
          array(_, "contents", McpCallResult.resourceContents)
        )
        meta <- optional(value, "_meta", objectValue(_, "_meta"))
      yield McpReadResourceResult(contents, meta, extensions(value, Set("contents", "_meta")))

  /** Unknown notifications are ignored; recognized malformed notifications fail their observer. */
  def readChange(
      notification: McpMessage.Notification
  ): Either[AgentError.DeserializationError, Option[McpResourceChange]] =
    notification.method match
      case "notifications/resources/list_changed" | "notifications/resources/updated" =>
        val params = notification.params.getOrElse(Json.Obj())
        checked(params):
          for
            meta <- optional(params, "_meta", objectValue(_, "_meta"))
            change <- notification.method match
              case "notifications/resources/updated" =>
                required(params, "uri")
                  .flatMap(string(_, "uri"))
                  .map(McpResourceChange.Updated(_, meta))
              case _ => Right(McpResourceChange.ListChanged(meta))
          yield Some(change)
      case _ => Right(None)

  private def uriRequest(
      id: McpRequestId,
      method: String,
      uri: String,
      meta: Option[Json.Obj]
  ): Either[AgentError.InvalidMessage, McpMessage.Request] =
    request(id, method, Chunk("uri" -> Json.Str(uri)) ++ field("_meta", meta))

  private def request(
      id: McpRequestId,
      method: String,
      fields: Chunk[(String, Json)]
  ): Either[AgentError.InvalidMessage, McpMessage.Request] =
    val message: McpMessage.Request =
      McpMessage.Request(id, method, Option.when(fields.nonEmpty)(Json.Obj(fields)))
    McpMessage.toJson(message).map(_ => message)

  private def page[A](
      value: Json.Obj,
      key: String,
      decode: Json => Either[String, A]
  ): Either[AgentError.DeserializationError, McpResourcePage[A]] =
    checked(value):
      for
        items  <- required(value, key).flatMap(array(_, key, decode))
        cursor <- optional(value, "nextCursor", string(_, "nextCursor"))
        meta   <- optional(value, "_meta", objectValue(_, "_meta"))
      yield McpResourcePage(items, cursor, meta, extensions(value, Set(key, "nextCursor", "_meta")))

  private def definition(value: Json): Either[String, McpResourceDefinition] =
    for
      fields      <- objectValue(value, "resource")
      uri         <- required(fields, "uri").flatMap(string(_, "uri"))
      name        <- required(fields, "name").flatMap(string(_, "name"))
      title       <- optional(fields, "title", string(_, "title"))
      description <- optional(fields, "description", string(_, "description"))
      mime        <- optional(fields, "mimeType", string(_, "mimeType"))
      size        <- optional(fields, "size", integralSize)
      annotations <- optional(fields, "annotations", McpCallResult.readAnnotations)
      meta        <- optional(fields, "_meta", objectValue(_, "_meta"))
    yield McpResourceDefinition(
      uri,
      name,
      title,
      description,
      mime,
      size,
      annotations,
      meta,
      extensions(
        fields,
        Set("uri", "name", "title", "description", "mimeType", "size", "annotations", "_meta")
      )
    )

  private def template(value: Json): Either[String, McpResourceTemplate] =
    for
      fields      <- objectValue(value, "resource template")
      uri         <- required(fields, "uriTemplate").flatMap(string(_, "uriTemplate"))
      name        <- required(fields, "name").flatMap(string(_, "name"))
      title       <- optional(fields, "title", string(_, "title"))
      description <- optional(fields, "description", string(_, "description"))
      mime        <- optional(fields, "mimeType", string(_, "mimeType"))
      annotations <- optional(fields, "annotations", McpCallResult.readAnnotations)
      meta        <- optional(fields, "_meta", objectValue(_, "_meta"))
    yield McpResourceTemplate(
      uri,
      name,
      title,
      description,
      mime,
      annotations,
      meta,
      extensions(
        fields,
        Set("uriTemplate", "name", "title", "description", "mimeType", "annotations", "_meta")
      )
    )

  private def integralSize(value: Json): Either[String, BigDecimal] = value match
    case Json.Num(number)
        if number.signum >= 0 &&
          (number.signum == 0 || number.scale <= 0 || number.stripTrailingZeros.scale <= 0) =>
      Right(BigDecimal(number))
    case _ => Left("size must be a nonnegative integer")

  private def checked[A](value: Json.Obj)(
      decode: => Either[String, A]
  ): Either[AgentError.DeserializationError, A] =
    JsonBoundary.validate(value).flatMap(_ => decode).left.map(AgentError.DeserializationError(_))

  private def array[A](
      value: Json,
      name: String,
      decode: Json => Either[String, A]
  ): Either[String, Chunk[A]] = value match
    case Json.Arr(values) =>
      values.foldLeft[Either[String, Chunk[A]]](Right(Chunk.empty))((items, raw) =>
        for
          accumulated <- items
          item        <- decode(raw)
        yield accumulated :+ item
      )
    case _ => Left(s"$name must be an array")

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
    case _             => Left(s"$name must be an object")

  private def string(value: Json, name: String): Either[String, String] = value match
    case Json.Str(text) => Right(text)
    case _              => Left(s"$name must be a string")

  private def extensions(value: Json.Obj, known: Set[String]): Json.Obj =
    Json.Obj(value.fields.filterNot(entry => known.contains(entry._1)))

  private def field(name: String, value: Option[Json]): Chunk[(String, Json)] =
    Chunk.fromIterable(value.map(name -> _))
