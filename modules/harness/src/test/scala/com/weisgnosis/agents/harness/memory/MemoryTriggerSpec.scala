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

import zio.*
import zio.json.*
import zio.test.*

object MemoryTriggerSpec extends ZIOSpecDefault:
  def spec: Spec[TestEnvironment & Scope, Any] = suite("MemoryTriggerSpec")(
    test("zero throttling normalizes to always and positive gaps remain typed") {
      for positive <- ZIO.fromEither(MemoryTrigger.throttled(5.minutes))
      yield assertTrue(
        MemoryTrigger.throttled(Duration.Zero) == Right(MemoryTrigger.Always),
        positive match
          case MemoryTrigger.Throttled(gap) => gap.duration == 5.minutes
          case _                            => false
      )
    },
    test("negative and unbounded throttles are typed configuration failures") {
      assertTrue(
        MemoryTrigger.throttled(java.time.Duration.ofNanos(-1)).isLeft,
        MemoryTrigger.throttled(Duration.Infinity).isLeft
      )
    },
    test("defaults and immutable policy updates preserve independent operation settings") {
      val original = MemoryTriggerPolicy()
      val changed  = original.copy(flush = MemoryTrigger.Never)
      assertTrue(
        original.flush == MemoryTrigger.Always,
        original.maintenance == MemoryTrigger.Throttled(MemoryInterval.DefaultMaintenance),
        changed.flush == MemoryTrigger.Never,
        changed.maintenance == original.maintenance
      )
    },
    test("derived policies, identities, operations and decisions round trip") {
      val policy = MemoryTriggerPolicy(MemoryTrigger.Never)
      val scopes = List(
        MemoryScope.User(None),
        MemoryScope.User(Some("user:session")),
        MemoryScope.Session(Some("session")),
        MemoryScope.Agent,
        MemoryScope.Global
      )
      assertTrue(
        policy.toJson.fromJson[MemoryTriggerPolicy] == Right(policy),
        scopes.forall(value => value.toJson.fromJson[MemoryScope] == Right(value)),
        MemoryOperation.values.forall(value =>
          value.toJson.fromJson[MemoryOperation] == Right(value)
        ),
        MemoryTriggerDecision.values.forall(value =>
          value.toJson.fromJson[MemoryTriggerDecision] == Right(value)
        ),
        """{"Throttled":{"minGap":0}}""".fromJson[MemoryTrigger].isLeft
      )
    }
  )
