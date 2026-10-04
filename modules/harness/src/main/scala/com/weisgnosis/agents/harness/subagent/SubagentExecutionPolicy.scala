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
package com.weisgnosis.agents.harness.subagent

import com.weisgnosis.agents.core.error.AgentError
import zio.*
import zio.json.JsonCodec
import zio.json.ast.Json

/** Host-selected wait budget; cancellation cleanup can continue beyond this deadline. */
opaque type SubagentSyncTimeout = Long

object SubagentSyncTimeout:
  private val maximumNanos: Long = 10.minutes.toNanos

  def fromDuration(
      duration: Duration
  ): Either[AgentError.InvalidConfiguration, SubagentSyncTimeout] =
    checked(BigInt(duration.getSeconds) * 1000000000 + duration.getNano)

  private def checked(nanos: BigInt): Either[AgentError.InvalidConfiguration, SubagentSyncTimeout] =
    if nanos > 0 && nanos <= maximumNanos then Right(nanos.toLong)
    else
      Left(
        AgentError.InvalidConfiguration(
          "Synchronous subagent timeout must be positive and at most ten minutes"
        )
      )

  // A JSON integer represents nanoseconds; decoding re-enters the checked boundary.
  given JsonCodec[SubagentSyncTimeout] = summon[JsonCodec[Json]].transformOrFail(
    {
      case Json.Num(value) =>
        val nanos = BigDecimal(value)
        if nanos > 0 && nanos <= BigDecimal(maximumNanos) && nanos.isWhole then Right(nanos.toLong)
        else
          Left(
            "Synchronous subagent timeout must be a positive integer number of nanoseconds at most ten minutes"
          )
      case _ => Left("Synchronous subagent timeout must be a JSON number")
    },
    timeout => Json.Num(timeout)
  )

  extension (timeout: SubagentSyncTimeout) def duration: Duration = Duration.fromNanos(timeout)

/** Fixed host policy, independent of model arguments and retained task ownership. */
enum SubagentExecutionPolicy derives JsonCodec:
  case PromoteAfter30Seconds
  case Synchronous(timeout: SubagentSyncTimeout)
