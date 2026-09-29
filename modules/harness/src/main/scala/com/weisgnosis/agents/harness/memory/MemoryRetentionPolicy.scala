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
import java.time.LocalDate
import zio.json.*
import zio.json.ast.Json

opaque type RetentionDays = Int

object RetentionDays:
  val DailyDefault: RetentionDays = 90

  def fromInt(value: Int): Either[AgentError.InvalidConfiguration, RetentionDays] =
    if value > 0 then Right(value)
    else Left(AgentError.InvalidConfiguration("Retention days must be positive"))

  given JsonCodec[RetentionDays] = summon[JsonCodec[Json]].transformOrFail(
    {
      case Json.Num(value) =>
        val days = BigDecimal(value)
        if days.isWhole && days > 0 && days <= Int.MaxValue then Right(days.toInt)
        else Left("Retention days must be a positive Int")
      case _ => Left("Retention days must be a JSON number")
    },
    value => Json.Num(value)
  )

  extension (days: RetentionDays) def value: Int = days

/** Calendar-day retention; session expiry is a separate capability. */
enum MemoryRetentionPolicy derives JsonCodec:
  case KeepAll
  case ArchiveAfter(days: RetentionDays)

  def cutoff(today: LocalDate): Either[AgentError.InvalidConfiguration, Option[LocalDate]] =
    this match
      case KeepAll => Right(None)
      case ArchiveAfter(days) =>
        val epochDay = today.toEpochDay - days.value.toLong
        if epochDay < LocalDate.MIN.toEpochDay then
          Left(
            AgentError.InvalidConfiguration(
              "Retention cutoff precedes the supported calendar range"
            )
          )
        else Right(Some(LocalDate.ofEpochDay(epochDay)))

object MemoryRetentionPolicy:
  val DailyDefault: MemoryRetentionPolicy = ArchiveAfter(RetentionDays.DailyDefault)
