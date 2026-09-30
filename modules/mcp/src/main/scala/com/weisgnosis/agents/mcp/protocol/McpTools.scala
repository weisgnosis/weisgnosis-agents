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

/** Untrusted remote hints, never local tool permissions or execution guarantees. */
final case class McpToolAnnotations(
    title: Option[String],
    readOnlyHint: Option[Boolean],
    destructiveHint: Option[Boolean],
    idempotentHint: Option[Boolean],
    openWorldHint: Option[Boolean],
    extensions: Json.Obj
)

final case class McpToolDefinition(
    name: String,
    inputSchema: Json.Obj,
    title: Option[String],
    description: Option[String],
    outputSchema: Option[Json.Obj],
    annotations: Option[McpToolAnnotations],
    meta: Option[Json.Obj],
    extensions: Json.Obj
)

final case class McpToolsPage(
    tools: Chunk[McpToolDefinition],
    nextCursor: Option[String],
    meta: Option[Json.Obj],
    extensions: Json.Obj
)

/**
 * Pure 2025-06-18 tool requests and one-page list projection. Schema checks cover only root type,
 * properties object, and required string-array shape; schema ASTs are retained without evaluation
 * or reference resolution. No implicit codec bypasses checked parsing. Pagination, call results,
 * transport, and installation into a local Toolkit remain separate concerns.
 */
object McpTools:
  def listRequest(
      id: McpRequestId,
      cursor: Option[String] = None,
      meta: Option[Json.Obj] = None
  ): Either[AgentError.InvalidMessage, McpMessage.Request] =
    request(id, "tools/list", field("cursor", cursor.map(Json.Str(_))) ++ field("_meta", meta))

  def callRequest(
      id: McpRequestId,
      name: String,
      arguments: Option[Json.Obj] = None,
      meta: Option[Json.Obj] = None
  ): Either[AgentError.InvalidMessage, McpMessage.Request] =
    request(
      id,
      "tools/call",
      Chunk("name" -> Json.Str(name)) ++
        field("arguments", arguments) ++ field("_meta", meta)
    )

  /** Retains server ordering and duplicate names; this is not local registration or deduplication. */
  def readListResult(value: Json.Obj): Either[AgentError.DeserializationError, McpToolsPage] =
    (for
      _      <- JsonBoundary.validate(value)
      tools  <- required(value, "tools").flatMap(toolList)
      cursor <- optional(value, "nextCursor", string(_, "nextCursor"))
      meta   <- optional(value, "_meta", objectValue(_, "_meta"))
    yield McpToolsPage(
      tools,
      cursor,
      meta,
      extensions(value, Set("tools", "nextCursor", "_meta"))
    )).left.map(AgentError.DeserializationError(_))

  private def request(
      id: McpRequestId,
      method: String,
      fields: Chunk[(String, Json)]
  ): Either[AgentError.InvalidMessage, McpMessage.Request] =
    val message: McpMessage.Request =
      McpMessage.Request(id, method, Option.when(fields.nonEmpty)(Json.Obj(fields)))
    McpMessage.toJson(message).map(_ => message)

  private def toolList(value: Json): Either[String, Chunk[McpToolDefinition]] = value match
    case Json.Arr(values) =>
      values.foldLeft[Either[String, Chunk[McpToolDefinition]]](Right(Chunk.empty))(
        (parsed, next) =>
          for
            tools <- parsed
            tool  <- toolDefinition(next)
          yield tools :+ tool
      )
    case _ => Left("tools must be an array")

  private def toolDefinition(value: Json): Either[String, McpToolDefinition] =
    for
      fields      <- objectValue(value, "tool")
      name        <- required(fields, "name").flatMap(string(_, "tool.name"))
      input       <- required(fields, "inputSchema").flatMap(schema(_, "inputSchema"))
      title       <- optional(fields, "title", string(_, "tool.title"))
      description <- optional(fields, "description", string(_, "tool.description"))
      output      <- optional(fields, "outputSchema", schema(_, "outputSchema"))
      hints       <- optional(fields, "annotations", annotations)
      meta        <- optional(fields, "_meta", objectValue(_, "tool._meta"))
    yield McpToolDefinition(
      name,
      input,
      title,
      description,
      output,
      hints,
      meta,
      extensions(
        fields,
        Set("name", "inputSchema", "title", "description", "outputSchema", "annotations", "_meta")
      )
    )

  private def annotations(value: Json): Either[String, McpToolAnnotations] =
    for
      fields      <- objectValue(value, "annotations")
      title       <- optional(fields, "title", string(_, "annotations.title"))
      readOnly    <- optional(fields, "readOnlyHint", boolean(_, "readOnlyHint"))
      destructive <- optional(fields, "destructiveHint", boolean(_, "destructiveHint"))
      idempotent  <- optional(fields, "idempotentHint", boolean(_, "idempotentHint"))
      openWorld   <- optional(fields, "openWorldHint", boolean(_, "openWorldHint"))
    yield McpToolAnnotations(
      title,
      readOnly,
      destructive,
      idempotent,
      openWorld,
      extensions(
        fields,
        Set("title", "readOnlyHint", "destructiveHint", "idempotentHint", "openWorldHint")
      )
    )

  private def schema(value: Json, name: String): Either[String, Json.Obj] =
    for
      fields <- objectValue(value, name)
      _ <- Either.cond(
        fields.get("type").contains(Json.Str("object")),
        (),
        s"$name.type must be object"
      )
      _ <- optional(fields, "properties", objectValue(_, s"$name.properties"))
      _ <- optional(fields, "required", stringArray(_, s"$name.required"))
    yield fields

  private def stringArray(value: Json, name: String): Either[String, Unit] = value match
    case Json.Arr(values) if values.forall(_.asString.isDefined) => Right(())
    case _ => Left(s"$name must be an array of strings")

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

  private def boolean(value: Json, name: String): Either[String, Boolean] = value match
    case Json.Bool(flag) => Right(flag)
    case _               => Left(s"$name must be a boolean")

  private def field(name: String, value: Option[Json]): Chunk[(String, Json)] =
    Chunk.fromIterable(value.map(name -> _))
