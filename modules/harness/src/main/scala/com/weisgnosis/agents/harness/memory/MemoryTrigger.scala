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
import zio.Duration
import zio.json.JsonCodec

enum MemoryTrigger derives JsonCodec:
  case Always, Never
  case Throttled(minGap: MemoryInterval)

object MemoryTrigger:
  def throttled(duration: Duration): Either[AgentError.InvalidConfiguration, MemoryTrigger] =
    if duration.isZero then Right(MemoryTrigger.Always)
    else MemoryInterval.fromDuration(duration).map(MemoryTrigger.Throttled.apply)

/** Fixed for one gate lifetime and persistent address; a changed policy needs a fresh address. */
final case class MemoryTriggerPolicy(
    flush: MemoryTrigger = MemoryTrigger.Always,
    maintenance: MemoryTrigger = MemoryTrigger.Throttled(MemoryInterval.DefaultMaintenance)
) derives JsonCodec

enum MemoryOperation derives JsonCodec:
  case Flush, Maintenance

enum MemoryScope derives JsonCodec:
  case User(identity: Option[String])
  case Session(identity: Option[String])
  case Agent, Global

object MemoryScope:
  private[memory] def normalized(scope: MemoryScope): MemoryScope = scope match
    case MemoryScope.User(identity)    => MemoryScope.User(identity.filterNot(_.isBlank))
    case MemoryScope.Session(identity) => MemoryScope.Session(identity.filterNot(_.isBlank))
    case MemoryScope.Agent | MemoryScope.Global => scope

enum MemoryTriggerDecision derives JsonCodec:
  case Admitted, Disabled, Throttled
