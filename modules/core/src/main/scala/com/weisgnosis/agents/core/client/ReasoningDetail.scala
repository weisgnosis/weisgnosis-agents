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
package com.weisgnosis.agents.core.client

import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.json.JsonBoundary
import zio.Chunk
import zio.json.*
import zio.json.ast.Json

/** Checked ordered wire data and its plaintext projection; signatures and encrypted data stay opaque. */
final class ReasoningDetail private (val value: Json.Obj, val plaintext: String):
  override def equals(other: Any): Boolean = other match
    case that: ReasoningDetail => value == that.value
    case _                     => false

  override def hashCode(): Int = value.hashCode()

object ReasoningDetail:
  private val commonFields = Set("type", "id", "format", "index")
  private val invalid      = "Invalid reasoning detail"

  def make(value: Json.Obj): Either[AgentError.InvalidMessage, ReasoningDetail] =
    value.get("type") match
      case Some(Json.Str("reasoning.encrypted")) =>
        EncryptedReasoningDetail.make(value).map(_.toDetail)
      case Some(Json.Str("reasoning.text")) =>
        val signatureValid = value
          .get("signature")
          .forall:
            case Json.Null       => true
            case Json.Str(token) => !token.isBlank
            case _               => false
        Either
          .cond(signatureValid, (), AgentError.InvalidMessage(invalid))
          .flatMap(_ => plain(value, "text", commonFields ++ Set("text", "signature")))
      case Some(Json.Str("reasoning.summary")) =>
        plain(value, "summary", commonFields + "summary")
      case _ => Left(AgentError.InvalidMessage(invalid))

  private def plain(
      value: Json.Obj,
      field: String,
      allowed: Set[String]
  ): Either[AgentError.InvalidMessage, ReasoningDetail] =
    value.get(field) match
      case Some(Json.Str(text))
          if validCommon(value) &&
            value.fields.forall((key, _) => allowed.contains(key)) =>
        Right(new ReasoningDetail(value, text))
      case _ => Left(AgentError.InvalidMessage(invalid))

  private[client] def fromEncrypted(value: EncryptedReasoningDetail): ReasoningDetail =
    new ReasoningDetail(value.value, "")

  private[client] def validCommon(value: Json.Obj): Boolean =
    val validId = value
      .get("id")
      .forall:
        case Json.Null    => true
        case Json.Str(id) => !id.isBlank
        case _            => false
    val validFormat = value
      .get("format")
      .forall:
        case Json.Str(format) => !format.isBlank
        case _                => false
    val validIndex = value
      .get("index")
      .forall:
        case number: Json.Num => number.as[Int].exists(_ >= 0)
        case _                => false
    JsonBoundary.validate(value).isRight && validId && validFormat && validIndex

  /** The AST codec preserves explicit nulls and absent fields rather than normalizing a DTO. */
  given JsonCodec[ReasoningDetail] = JsonCodec[Json].transformOrFail(
    {
      case value: Json.Obj => make(value).left.map(_.message)
      case _               => Left(invalid)
    },
    _.value
  )

  private[client] def decodeArray(value: Json): Either[String, Chunk[ReasoningDetail]] = value match
    case Json.Arr(values) =>
      values.foldLeft[Either[String, Chunk[ReasoningDetail]]](Right(Chunk.empty)):
        (previous, entry) =>
          for
            accumulated <- previous
            detail <- entry match
              case obj: Json.Obj => make(obj).left.map(_.message)
              case _             => Left(invalid)
          yield accumulated :+ detail
    case _ => Left("Reasoning details must be an array")
