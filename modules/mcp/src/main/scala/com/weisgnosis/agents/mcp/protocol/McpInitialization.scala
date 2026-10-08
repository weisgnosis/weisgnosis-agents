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
import zio.json.*
import zio.json.ast.Json

/** Implementation identity is wire data; no protocol negotiation or runtime authority is implied. */
final case class McpImplementation(name: String, version: String, title: Option[String] = None)
    derives JsonCodec

final case class McpToolCapabilities(listChanged: Option[Boolean], extensions: Json.Obj)

final case class McpResourceCapabilities(
    subscribe: Option[Boolean],
    listChanged: Option[Boolean],
    extensions: Json.Obj
)

final case class McpServerCapabilities(
    tools: Option[McpToolCapabilities],
    extensions: Json.Obj,
    resources: Option[McpResourceCapabilities] = None
)

final case class McpInitializationResult(
    serverInfo: McpImplementation,
    capabilities: McpServerCapabilities,
    instructions: Option[String],
    meta: Option[Json.Obj],
    protocolVersion: McpProtocolVersion = McpProtocolVersion.V20250618
)

/**
 * Pure helpers for an explicitly selected initialized lifecycle. They implement no connection state
 * or automatic version downgrade, and do not apply to the 2026-07-28 lifecycle. Result parsing is a checked
 * projection: unknown outer/serverInfo fields are ignored, while capability extensions are retained.
 */
object McpInitialization:
  val protocolVersion: String = "2025-06-18"

  /** Advertise no optional client capabilities and validate the caller-supplied correlation ID. */
  def request(
      id: McpRequestId,
      clientInfo: McpImplementation,
      version: McpProtocolVersion = McpProtocolVersion.V20250618
  ): Either[AgentError.InvalidMessage, McpMessage.Request] =
    val identity = Json.Obj(
      Chunk("name" -> Json.Str(clientInfo.name), "version" -> Json.Str(clientInfo.version)) ++
        Chunk.fromIterable(clientInfo.title.map(title => "title" -> Json.Str(title)))
    )
    val message: McpMessage.Request = McpMessage.Request(
      id,
      "initialize",
      Some(
        Json.Obj(
          "protocolVersion" -> Json.Str(version.wire),
          "capabilities"    -> Json.Obj(),
          "clientInfo"      -> identity
        )
      )
    )
    McpMessage.toJson(message).map(_ => message)

  val initialized: McpMessage.Notification = McpMessage.Notification("notifications/initialized")

  /** Known optional fields reject JSON null; duplicates are rejected throughout the input AST. */
  def readResult(
      value: Json.Obj,
      selected: McpProtocolVersion = McpProtocolVersion.V20250618
  ): Either[AgentError.DeserializationError, McpInitializationResult] =
    readResultWith(
      value,
      wire =>
        Either.cond(
          wire == selected.wire,
          selected,
          s"Expected MCP protocol version ${selected.wire}"
        )
    )

  /** Validate the complete initialization payload before selecting an explicitly supported revision. */
  def readSupportedResult(
      value: Json.Obj,
      supported: McpSupportedVersions
  ): Either[AgentError.DeserializationError, McpInitializationResult] =
    readResultWith(
      value,
      wire =>
        supported.versions.find(_.wire == wire).toRight("Unsupported MCP server protocol revision")
    )

  private def readResultWith(
      value: Json.Obj,
      select: String => Either[String, McpProtocolVersion]
  ): Either[AgentError.DeserializationError, McpInitializationResult] =
    (for
      _            <- JsonBoundary.validate(value)
      wire         <- required(value, "protocolVersion").flatMap(string(_, "protocolVersion"))
      selected     <- select(wire)
      server       <- required(value, "serverInfo").flatMap(implementation)
      capabilities <- required(value, "capabilities").flatMap(serverCapabilities)
      instructions <- optional(value, "instructions", string(_, "instructions"))
      meta         <- optional(value, "_meta", objectValue(_, "_meta"))
    yield McpInitializationResult(server, capabilities, instructions, meta, selected)).left
      .map(AgentError.DeserializationError(_))

  private def implementation(value: Json): Either[String, McpImplementation] =
    for
      fields  <- objectValue(value, "serverInfo")
      name    <- required(fields, "name").flatMap(string(_, "serverInfo.name"))
      version <- required(fields, "version").flatMap(string(_, "serverInfo.version"))
      title   <- optional(fields, "title", string(_, "serverInfo.title"))
    yield McpImplementation(name, version, title)

  private def serverCapabilities(value: Json): Either[String, McpServerCapabilities] =
    for
      fields    <- objectValue(value, "capabilities")
      tools     <- optional(fields, "tools", toolCapabilities)
      resources <- optional(fields, "resources", resourceCapabilities)
    yield McpServerCapabilities(
      tools,
      Json.Obj(fields.fields.filterNot(entry => Set("tools", "resources").contains(entry._1))),
      resources
    )

  private def resourceCapabilities(value: Json): Either[String, McpResourceCapabilities] =
    def flag(raw: Json): Either[String, Boolean] = raw match
      case Json.Bool(value) => Right(value)
      case _                => Left("Resource capability flags must be booleans")
    for
      fields      <- objectValue(value, "capabilities.resources")
      subscribe   <- optional(fields, "subscribe", flag)
      listChanged <- optional(fields, "listChanged", flag)
    yield McpResourceCapabilities(
      subscribe,
      listChanged,
      Json.Obj(fields.fields.filterNot(entry => Set("subscribe", "listChanged").contains(entry._1)))
    )

  private def toolCapabilities(value: Json): Either[String, McpToolCapabilities] =
    for
      fields      <- objectValue(value, "capabilities.tools")
      listChanged <- optional(fields, "listChanged", boolean)
    yield McpToolCapabilities(listChanged, extensions(fields, "listChanged"))

  private def extensions(value: Json.Obj, known: String): Json.Obj = value match
    case Json.Obj(fields) => Json.Obj(fields.filterNot(_._1 == known))

  private def required(value: Json.Obj, name: String): Either[String, Json] =
    value.get(name).toRight(s"Missing required field: $name")

  private def optional[A](
      value: Json.Obj,
      name: String,
      decode: Json => Either[String, A]
  ): Either[String, Option[A]] = value.get(name) match
    case None        => Right(None)
    case Some(field) => decode(field).map(Some(_))

  private def objectValue(value: Json, name: String): Either[String, Json.Obj] = value match
    case fields: Json.Obj => Right(fields)
    case _                => Left(s"$name must be a JSON object")

  private def string(value: Json, name: String): Either[String, String] = value match
    case Json.Str(text) => Right(text)
    case _              => Left(s"$name must be a string")

  private def boolean(value: Json): Either[String, Boolean] = value match
    case Json.Bool(flag) => Right(flag)
    case _               => Left("capabilities.tools.listChanged must be a boolean")
