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

/** Correlation identity preserves string versus integer representation without floating-point loss. */
enum McpRequestId:
  case Text(value: String)
  case Number(value: BigInt)

/** A remote protocol error is data, distinct from failure to decode the envelope. */
final case class McpRpcError(code: BigInt, message: String, data: Option[Json] = None)

/** Common MCP JSON-RPC envelope only; method schemas and version negotiation belong above it. */
enum McpMessage:
  case Request(id: McpRequestId, method: String, params: Option[Json.Obj] = None)
  case Notification(method: String, params: Option[Json.Obj] = None)
  case Success(id: McpRequestId, result: Json.Obj)
  case Failure(id: Option[McpRequestId], error: McpRpcError)

/**
 * Explicit checked codecs reject unknown envelope/error fields and duplicate keys at every depth.
 * Payload extensions remain intact. Constructors may contain unvalidated ASTs: use encode/toJson
 * for outbound validation. No infallible implicit encoder bypasses that boundary. Integer numbers
 * use the parser-compatible signed 256-bit range and normalize mathematically; optional error
 * data distinguishes absence from JSON null. This bounds envelope integers, not payload size.
 */
object McpMessage:
  private type Fields = Map[String, Json]

  def decode(value: String): Either[AgentError.DeserializationError, McpMessage] =
    JsonBoundary.decode(value).flatMap(read).left.map(AgentError.DeserializationError(_))

  def fromJson(value: Json): Either[AgentError.DeserializationError, McpMessage] =
    JsonBoundary.validate(value).flatMap(read).left.map(AgentError.DeserializationError(_))

  def encode(message: McpMessage): Either[AgentError.InvalidMessage, String] =
    toJson(message).map(JsonBoundary.render)

  def toJson(message: McpMessage): Either[AgentError.InvalidMessage, Json.Obj] =
    (for
      _ <- validateNumbers(message)
      value = write(message)
      _ <- JsonBoundary.validate(value)
    yield value).left.map(AgentError.InvalidMessage(_))

  private def read(value: Json): Either[String, McpMessage] =
    for
      fields <- objectFields(value, "message")
      _ <- Either.cond(
        fields.get("jsonrpc").contains(Json.Str("2.0")),
        (),
        "jsonrpc must be exactly 2.0"
      )
      message <- (fields.get("method"), fields.get("result"), fields.get("error")) match
        case (Some(method), None, None) =>
          for
            _      <- knownFields(fields, Set("jsonrpc", "id", "method", "params"))
            name   <- string(method, "method")
            params <- optional(fields, "params", objectValue)
            id     <- optional(fields, "id", requestId)
          yield id.fold[McpMessage](Notification(name, params))(Request(_, name, params))
        case (None, Some(result), None) =>
          for
            _       <- knownFields(fields, Set("jsonrpc", "id", "result"))
            id      <- required(fields, "id").flatMap(requestId)
            payload <- objectValue(result)
          yield Success(id, payload)
        case (None, None, Some(error)) =>
          for
            _       <- knownFields(fields, Set("jsonrpc", "id", "error"))
            id      <- optional(fields, "id", requestId)
            payload <- rpcError(error)
          yield Failure(id, payload)
        case _ => Left("Expected exactly one of method, result or error")
    yield message

  private def rpcError(value: Json): Either[String, McpRpcError] =
    for
      fields  <- objectFields(value, "error")
      _       <- knownFields(fields, Set("code", "message", "data"))
      code    <- required(fields, "code").flatMap(integer(_, "error.code"))
      message <- required(fields, "message").flatMap(string(_, "error.message"))
    yield McpRpcError(code, message, fields.get("data"))

  private def requestId(value: Json): Either[String, McpRequestId] = value match
    case Json.Str(text)   => Right(McpRequestId.Text(text))
    case number: Json.Num => integer(number, "id").map(McpRequestId.Number(_))
    case _                => Left("id must be a string or integer")

  private def integer(value: Json, name: String): Either[String, BigInt] = value match
    case Json.Num(number) if number.signum == 0 => Right(BigInt(0))
    case Json.Num(number)                       =>
      // Inspect decimal expansion before allocating a BigInt; subtraction must not overflow Int.
      if number.precision.toLong - number.scale.toLong > 77L then
        Left(s"$name must be within the signed 256-bit integer range")
      else
        BigDecimal(number).toBigIntExact
          .toRight(s"$name must be an integer")
          .flatMap(boundedInteger(_, name))
    case _ => Left(s"$name must be an integer")

  private def boundedInteger(value: BigInt, name: String): Either[String, BigInt] =
    // The JSON parser accepts bitLength < 256, including -2^255 but excluding +2^255.
    Either.cond(
      value.bitLength < 256,
      value,
      s"$name must be within the signed 256-bit integer range"
    )

  private def validateId(id: McpRequestId): Either[String, Unit] = id match
    case McpRequestId.Text(_)       => Right(())
    case McpRequestId.Number(value) => boundedInteger(value, "id").map(_ => ())

  private def validateNumbers(message: McpMessage): Either[String, Unit] = message match
    case Request(id, _, _)  => validateId(id)
    case Notification(_, _) => Right(())
    case Success(id, _)     => validateId(id)
    case Failure(id, error) =>
      for
        _ <- id.fold[Either[String, Unit]](Right(()))(validateId)
        _ <- boundedInteger(error.code, "error.code")
      yield ()

  private def string(value: Json, name: String): Either[String, String] = value match
    case Json.Str(text) => Right(text)
    case _              => Left(s"$name must be a string")

  private def objectValue(value: Json): Either[String, Json.Obj] = value match
    case obj: Json.Obj => Right(obj)
    case _             => Left("Expected a JSON object payload")

  private def objectFields(value: Json, name: String): Either[String, Fields] = value match
    case Json.Obj(fields) => Right(fields.toMap)
    case _                => Left(s"$name must be a JSON object")

  private def required(fields: Fields, name: String): Either[String, Json] =
    fields.get(name).toRight(s"Missing required field: $name")

  private def optional[A](
      fields: Fields,
      name: String,
      decode: Json => Either[String, A]
  ): Either[String, Option[A]] =
    fields.get(name) match
      case None        => Right(None)
      case Some(value) => decode(value).map(Some(_))

  private def knownFields(fields: Fields, allowed: Set[String]): Either[String, Unit] =
    Either.cond(fields.keySet.subsetOf(allowed), (), "Unknown JSON-RPC envelope or error field")

  private def write(message: McpMessage): Json.Obj =
    val fields: Chunk[(String, Json)] = message match
      case Request(id, method, params) =>
        Chunk("id" -> writeId(id), "method" -> Json.Str(method)) ++ optionalField("params", params)
      case Notification(method, params) =>
        Chunk("method" -> Json.Str(method)) ++ optionalField("params", params)
      case Success(id, result) => Chunk("id" -> writeId(id), "result" -> result)
      case Failure(id, error) =>
        optionalField("id", id.map(writeId)) :+ ("error" -> Json.Obj(
          Chunk("code" -> Json.Num(BigDecimal(error.code)), "message" -> Json.Str(error.message)) ++
            optionalField("data", error.data)
        ))
    Json.Obj(Chunk("jsonrpc" -> Json.Str("2.0")) ++ fields)

  private def writeId(id: McpRequestId): Json = id match
    case McpRequestId.Text(value)   => Json.Str(value)
    case McpRequestId.Number(value) => Json.Num(BigDecimal(value))

  private def optionalField(name: String, value: Option[Json]): Chunk[(String, Json)] =
    Chunk.fromIterable(value.map(name -> _))
