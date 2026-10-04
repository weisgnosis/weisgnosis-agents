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

import zio.*
import zio.json.*
import zio.test.*

object SubagentExecutionPolicySpec extends ZIOSpecDefault:
  def spec: Spec[TestEnvironment & Scope, Any] = suite("SubagentExecutionPolicySpec")(
    test("finite host budgets and nanosecond codecs re-enter the same checked boundary"):
      val valid   = Chunk(Duration.fromNanos(1), 30.seconds, 10.minutes)
      val invalid = Chunk(Duration.Zero, Duration.Infinity, 10.minutes.plusNanos(1))
      val invalidJson =
        Chunk("0", "-1", "600000000001", "1.5", "1e1000", "1e-1000", "\"1000\"", "null")
      val policies = valid.map(duration =>
        SubagentSyncTimeout.fromDuration(duration).map(SubagentExecutionPolicy.Synchronous(_))
      )
      assertTrue(
        invalid.forall(SubagentSyncTimeout.fromDuration(_).isLeft),
        invalidJson.forall(_.fromJson[SubagentSyncTimeout].isLeft),
        policies.forall(
          _.exists(policy => policy.toJson.fromJson[SubagentExecutionPolicy] == Right(policy))
        ),
        valid.forall(duration =>
          SubagentSyncTimeout.fromDuration(duration).exists(_.duration == duration)
        ),
        "1".fromJson[SubagentSyncTimeout].exists(_.duration == Duration.fromNanos(1)),
        "600000000000".fromJson[SubagentSyncTimeout].exists(_.duration == 10.minutes),
        """{"Synchronous":{"timeout":0}}""".fromJson[SubagentExecutionPolicy].isLeft,
        SubagentExecutionPolicy.PromoteAfter30Seconds.toJson.fromJson[SubagentExecutionPolicy] ==
          Right(SubagentExecutionPolicy.PromoteAfter30Seconds)
      )
  )
