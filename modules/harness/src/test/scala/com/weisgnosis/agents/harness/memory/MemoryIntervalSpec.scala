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

object MemoryIntervalSpec extends ZIOSpecDefault:
  def spec: Spec[TestEnvironment & Scope, Any] = suite("MemoryIntervalSpec")(
    test("preserves exact finite nanoseconds and the default maintenance duration") {
      val values = List(1.nanosecond, 30.minutes, Duration.fromNanos(Long.MaxValue - 1))
      assertTrue(
        values.forall(value => MemoryInterval.fromDuration(value).map(_.duration) == Right(value)),
        MemoryInterval.DefaultMaintenance.duration == 30.minutes
      )
    },
    test("rejects zero, negative, infinite and overflowing duration values without throwing") {
      val values = List(
        Duration.Zero,
        java.time.Duration.ofNanos(-1),
        Duration.Infinity,
        java.time.Duration.ofSeconds(Long.MaxValue)
      )
      assertTrue(values.forall(value => MemoryInterval.fromDuration(value).isLeft))
    },
    test("codec validation cannot introduce an invalid interval") {
      for interval <- ZIO.fromEither(MemoryInterval.fromDuration(5.seconds))
      yield assertTrue(
        interval.toJson.fromJson[MemoryInterval] == Right(interval),
        List("0", "-1", "1.5", "1e100", "true", "[]", "\"5\"", Long.MaxValue.toString)
          .forall(_.fromJson[MemoryInterval].isLeft)
      )
    }
  )
