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
import zio.Chunk
import zio.json.*
import zio.json.ast.Json

/** One checked opaque protocol object; replay preserves the supplied AST without decoding data. */
final class EncryptedReasoningDetail private (val value: Json.Obj):
  def toDetail: ReasoningDetail = ReasoningDetail.fromEncrypted(this)

  override def equals(other: Any): Boolean = other match
    case that: EncryptedReasoningDetail => value == that.value
    case _                              => false

  override def hashCode(): Int = value.hashCode()

object EncryptedReasoningDetail:
  private val fields  = Set("type", "data", "id", "format", "index")
  private val invalid = "Invalid encrypted reasoning detail"

  def make(value: Json.Obj): Either[AgentError.InvalidMessage, EncryptedReasoningDetail] =
    val valid = ReasoningDetail.validCommon(value) &&
      value.fields.forall((key, _) => fields.contains(key)) &&
      value.get("type").contains(Json.Str("reasoning.encrypted")) &&
      value.get("data").flatMap(_.asString).exists(data => !data.isBlank)
    Either.cond(
      valid,
      new EncryptedReasoningDetail(value),
      AgentError.InvalidMessage(invalid)
    )

  /** Checked AST codec retains explicit null IDs and omitted fields without normalization. */
  given JsonCodec[EncryptedReasoningDetail] = JsonCodec[Json].transformOrFail(
    {
      case value: Json.Obj => make(value).left.map(_.message)
      case _               => Left(invalid)
    },
    _.value
  )

  private[client] def decodeArray(value: Json): Either[String, Chunk[EncryptedReasoningDetail]] =
    value match
      case Json.Arr(values) =>
        values.foldLeft[Either[String, Chunk[EncryptedReasoningDetail]]](Right(Chunk.empty)):
          (previous, entry) =>
            for
              accumulated <- previous
              detail <- entry match
                case obj: Json.Obj => make(obj).left.map(_.message)
                case _             => Left(invalid)
            yield accumulated :+ detail
      case _ => Left("Reasoning details must be an array")
