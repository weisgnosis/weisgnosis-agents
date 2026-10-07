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
package com.weisgnosis.agents.harness.storage

import com.weisgnosis.agents.core.error.AgentError
import java.nio.charset.StandardCharsets
import zio.*
import zio.json.*
import zio.json.ast.Json

/** An exact namespace and key, bounded to 512 UTF-8 bytes each after namespace serialization. */
final class StorageAddress private (val namespace: Chunk[String], val key: String):
  def serializedNamespace: String = namespace.mkString(StorageAddress.separator)

  override def equals(other: Any): Boolean = other match
    case address: StorageAddress => namespace == address.namespace && key == address.key
    case _                       => false

  override def hashCode(): Int = (namespace, key).hashCode()

object StorageAddress:
  private val separator: String = "\u001f"
  final private case class Wire(namespace: Chunk[String], key: String) derives JsonCodec

  def from(
      namespace: Chunk[String],
      key: String
  ): Either[AgentError.InvalidConfiguration, StorageAddress] =
    val serialized = namespace.mkString(separator)
    if namespace.isEmpty || !namespace.forall(validComponent) || !validComponent(key) then
      Left(
        AgentError.InvalidConfiguration(
          "Storage namespace and key must be nonblank valid Unicode without NUL or U+001F"
        )
      )
    else if utf8Size(serialized) > 512 || utf8Size(key) > 512 then
      Left(
        AgentError.InvalidConfiguration(
          "Serialized storage namespace and key must each fit in 512 UTF-8 bytes"
        )
      )
    else Right(new StorageAddress(namespace, key))

  /** Checked decoding prevents serialized addresses from bypassing namespace and byte bounds. */
  given JsonCodec[StorageAddress] = JsonCodec[Wire].transformOrFail(
    wire => from(wire.namespace, wire.key).left.map(_.message),
    address => Wire(address.namespace, address.key)
  )

  private def utf8Size(value: String): Int = value.getBytes(StandardCharsets.UTF_8).length

  private def validComponent(value: String): Boolean =
    !value.isBlank && !value.contains('\u0000') && !value.contains(separator) &&
      value.zipWithIndex.forall: (character, index) =>
        if Character.isHighSurrogate(character) then
          value.lift(index + 1).exists(Character.isLowSurrogate)
        else if Character.isLowSurrogate(character) then
          value.lift(index - 1).exists(Character.isHighSurrogate)
        else true

/** Positive, never-reused revision within one address's persistent lifetime. */
opaque type StorageRevision = Long

object StorageRevision:
  def fromLong(value: Long): Either[AgentError.InvalidConfiguration, StorageRevision] =
    Either.cond(
      value > 0L,
      value,
      AgentError.InvalidConfiguration("Storage revision must be positive")
    )

  extension (revision: StorageRevision) def value: Long = revision

  /** Numeric shape validation rejects coercion from strings and out-of-range integer values. */
  given JsonCodec[StorageRevision] = JsonCodec[Json].transformOrFail(
    {
      case Json.Num(value) =>
        BigDecimal(value).toBigIntExact
          .filter(number => number > 0 && number.isValidLong)
          .map(_.toLong)
          .toRight("Storage revision must be a positive signed 64-bit integer")
      case _ => Left("Storage revision must be a JSON number")
    },
    revision => Json.Num(revision)
  )

enum ExpectedRevision derives JsonCodec:
  case Absent
  case AtRevision(value: StorageRevision)

enum ConditionalWrite derives JsonCodec:
  case Applied(revision: StorageRevision)
  case Conflict

final case class VersionedJson(value: Json, revision: StorageRevision) derives JsonCodec

/**
 * Atomic per-address JSON publication. Conflict changes nothing; successful writes advance a
 * positive revision without reuse. Invalid JSON and revision exhaustion fail without publication.
 * An expected Long.MaxValue revision is exhausted and fails even if it would otherwise conflict.
 * Cancellation or a lost acknowledgement does not prove that an admitted write did not commit.
 * Backend deletion or replacement lies outside this capability and invalidates revision guarantees.
 */
trait VersionedJsonStore:
  def read(address: StorageAddress): IO[AgentError, Option[VersionedJson]]
  def compareAndSet(
      address: StorageAddress,
      expected: ExpectedRevision,
      value: Json
  ): IO[AgentError, ConditionalWrite]
