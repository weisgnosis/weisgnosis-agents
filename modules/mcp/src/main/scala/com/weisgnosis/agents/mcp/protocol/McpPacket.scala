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

/** A wire frame preserves March 2025 batch boundaries rather than flattening server requests. */
enum McpPacket:
  case Single(message: McpMessage)
  case Batch(members: Chunk[McpBatchMember])
  case InvalidRequest

/** Invalid request members require an error reply; malformed response members never do. */
enum McpBatchMember:
  case Message(value: McpMessage)
  case InvalidRequest

object McpPacket:
  val maxMembers: Int = 64

  def decode(
      wire: String,
      version: McpProtocolVersion
  ): Either[AgentError.DeserializationError, McpPacket] =
    JsonBoundary
      .decode(wire)
      .left
      .map(AgentError.DeserializationError(_))
      .flatMap:
        case Json.Arr(values) if version == McpProtocolVersion.V20250326 =>
          if values.size > maxMembers then
            Left(AgentError.DeserializationError("MCP batch exceeds member limit"))
          else if values.isEmpty then Right(McpPacket.InvalidRequest)
          else
            values
              .foldLeft[Either[AgentError.DeserializationError, Chunk[McpBatchMember]]](
                Right(Chunk.empty)
              ): (acc, value) =>
                for
                  members <- acc
                  member <- McpMessage.fromJson(value) match
                    case Right(McpMessage.Request(_, "initialize", _)) =>
                      Left(AgentError.DeserializationError("MCP initialize must not be batched"))
                    case Right(message) => Right(McpBatchMember.Message(message))
                    case Left(error) if responseShaped(value) => Left(error)
                    case Left(_) => Right(McpBatchMember.InvalidRequest)
                yield members :+ member
              .flatMap: members =>
                val responses = members.exists:
                  case McpBatchMember.Message(_: McpMessage.Success | _: McpMessage.Failure) => true
                  case _ => false
                val requests = members.exists:
                  case McpBatchMember.Message(_: McpMessage.Success | _: McpMessage.Failure) =>
                    false
                  case _ => true
                if responses && requests then
                  Left(AgentError.DeserializationError("MCP batch mixes requests and responses"))
                else Right(McpPacket.Batch(members))
        case _: Json.Arr =>
          Left(AgentError.DeserializationError("MCP batches require revision 2025-03-26"))
        case value => McpMessage.fromJson(value).map(McpPacket.Single(_))

  def encode(
      packet: McpPacket,
      version: McpProtocolVersion
  ): Either[AgentError.InvalidMessage, String] =
    toJson(packet, version).map(JsonBoundary.render)

  def toJson(
      packet: McpPacket,
      version: McpProtocolVersion
  ): Either[AgentError.InvalidMessage, Json] = packet match
    case McpPacket.Single(message) => McpMessage.toJson(message)
    case _ if version != McpProtocolVersion.V20250326 =>
      Left(AgentError.InvalidMessage("MCP batches require revision 2025-03-26"))
    case McpPacket.InvalidRequest => Right(invalidResponse)
    case McpPacket.Batch(members) =>
      if members.isEmpty || members.size > maxMembers then
        Left(AgentError.InvalidMessage("MCP batch requires between 1 and 64 members"))
      else
        members
          .foldLeft[Either[AgentError.InvalidMessage, Chunk[Json]]](Right(Chunk.empty)):
            (acc, member) =>
              for
                values <- acc
                value <- member match
                  case McpBatchMember.InvalidRequest => Right(invalidResponse)
                  case McpBatchMember.Message(McpMessage.Request(_, "initialize", _)) =>
                    Left(AgentError.InvalidMessage("MCP initialize must not be batched"))
                  case McpBatchMember.Message(message) => McpMessage.toJson(message)
              yield values :+ value
          .map(Json.Arr(_))

  private[mcp] def validateResponse(packet: McpPacket): Either[AgentError.InvalidMessage, Unit] =
    packet match
      case McpPacket.Single(_) | McpPacket.InvalidRequest => Right(())
      case McpPacket.Batch(members) =>
        Either.cond(
          members.nonEmpty && members.size <= maxMembers && members.forall:
            case McpBatchMember.InvalidRequest                                         => true
            case McpBatchMember.Message(_: McpMessage.Success | _: McpMessage.Failure) => true
            case _                                                                     => false
          ,
          (),
          AgentError.InvalidMessage("Grouped sends contain only bounded server-request responses")
        )

  def single(packet: McpPacket): Either[AgentError.InvalidMessage, McpMessage] = packet match
    case McpPacket.Single(message) => Right(message)
    case _ =>
      Left(AgentError.InvalidMessage("Batch-aware transport consumers must use incomingPackets"))

  private def responseShaped(value: Json): Boolean = value match
    case Json.Obj(fields) => fields.exists((key, _) => key == "result" || key == "error")
    case _                => false

  private val invalidResponse: Json.Obj = Json.Obj(
    "jsonrpc" -> Json.Str("2.0"),
    "id"      -> Json.Null,
    "error"   -> Json.Obj("code" -> Json.Num(-32600), "message" -> Json.Str("Invalid Request"))
  )
