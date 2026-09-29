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
package com.weisgnosis.agents.harness.memory

import com.weisgnosis.agents.core.error.AgentError
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.LocalDate
import zio.*
import zio.json.*

/** A canonical SHA-256 content identity, never a filesystem path. */
opaque type MemoryFingerprint = String

object MemoryFingerprint:
  def fromString(value: String): Either[AgentError.InvalidConfiguration, MemoryFingerprint] =
    if value.matches("[0-9a-f]{64}") then Right(value)
    else
      Left(
        AgentError.InvalidConfiguration(
          "Memory fingerprint must be 64 lowercase hexadecimal digits"
        )
      )

  def of(content: String): IO[AgentError, MemoryFingerprint] =
    ZIO
      .fail(AgentError.MemoryStorageError("Memory content contains an unpaired UTF-16 surrogate"))
      .unless(content.indices.forall: index =>
        val character = content.charAt(index)
        if Character.isHighSurrogate(character) then
          content.lift(index + 1).exists(Character.isLowSurrogate)
        else if Character.isLowSurrogate(character) then
          content.lift(index - 1).exists(Character.isHighSurrogate)
        else true) *> ZIO
      .attempt:
        MessageDigest
          .getInstance("SHA-256")
          .digest(content.getBytes(StandardCharsets.UTF_8))
          .iterator
          .map(byte => f"${byte & 0xff}%02x")
          .mkString
      .mapError(error => AgentError.MemoryStorageError(error.toString))

  given JsonCodec[MemoryFingerprint] = JsonCodec.string.transformOrFail(
    value => fromString(value).left.map(_.message),
    identity
  )

  extension (fingerprint: MemoryFingerprint) def value: String = fingerprint

final case class DailyMemoryArchive(day: LocalDate, fingerprint: MemoryFingerprint)
    derives JsonCodec

/** Archive only acknowledged exact content, retaining pending data regardless of age. */
trait DailyMemoryRetention:
  def archiveBefore(cutoff: LocalDate): IO[AgentError, Chunk[DailyMemoryArchive]]
  def archives: IO[AgentError, Chunk[DailyMemoryArchive]]
  def readArchive(archive: DailyMemoryArchive): IO[AgentError, Option[String]]
