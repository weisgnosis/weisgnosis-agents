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
import zio.*
import zio.json.JsonCodec
import zio.json.ast.Json

/** A positive finite interval smaller than the signed monotonic-clock range. */
opaque type MemoryInterval = Long

object MemoryInterval:
  val DefaultMaintenance: MemoryInterval = 30.minutes.toNanos

  def fromDuration(duration: Duration): Either[AgentError.InvalidConfiguration, MemoryInterval] =
    val nanos = BigInt(duration.getSeconds) * 1000000000 + duration.getNano
    if nanos > 0 && nanos < Long.MaxValue then Right(nanos.toLong)
    else
      Left(
        AgentError.InvalidConfiguration(
          "Memory interval must be positive and smaller than Long.MaxValue nanoseconds"
        )
      )

  // Numeric shape validation prevents the primitive decoder's numeric-string coercion.
  given JsonCodec[MemoryInterval] = summon[JsonCodec[Json]].transformOrFail(
    {
      case Json.Num(value) =>
        val nanos = BigDecimal(value)
        if nanos > 0 && nanos < BigDecimal(Long.MaxValue) && nanos.isWhole then Right(nanos.toLong)
        else Left("Memory interval must be a positive finite integer number of nanoseconds")
      case _ => Left("Memory interval must be a JSON number")
    },
    interval => Json.Num(interval)
  )

  extension (interval: MemoryInterval)
    def duration: Duration          = Duration.fromNanos(interval)
    private[memory] def nanos: Long = interval
