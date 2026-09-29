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

import java.time.LocalDate
import zio.*
import zio.json.*
import zio.test.*

object MemoryRetentionPolicySpec extends ZIOSpecDefault:
  def spec: Spec[TestEnvironment, Any] = suite("MemoryRetentionPolicySpec")(
    test("daily default is ninety positive calendar days with a strict typed numeric codec") {
      assertTrue(
        RetentionDays.DailyDefault.value == 90,
        MemoryRetentionPolicy.DailyDefault == MemoryRetentionPolicy.ArchiveAfter(
          RetentionDays.DailyDefault
        ),
        MemoryRetentionPolicy.DailyDefault.toJson.fromJson[MemoryRetentionPolicy] == Right(
          MemoryRetentionPolicy.DailyDefault
        ),
        List("0", "-1", "1.5", "2147483648", "\"90\"", "true", "{}").forall(
          _.fromJson[RetentionDays].isLeft
        ),
        List(0, -5, Int.MinValue).forall(value => RetentionDays.fromInt(value).isLeft),
        RetentionDays.fromInt(Int.MaxValue).isRight
      )
    },
    test("calendar cutoff handles leap days and remains exclusive") {
      for days <- ZIO.fromEither(RetentionDays.fromInt(1))
      yield assertTrue(
        MemoryRetentionPolicy.ArchiveAfter(days).cutoff(LocalDate.of(2024, 3, 1)) ==
          Right(Some(LocalDate.of(2024, 2, 29)))
      )
    },
    test("underflow is typed and KeepAll does not need a representable earlier date") {
      assertTrue(
        MemoryRetentionPolicy.DailyDefault.cutoff(LocalDate.MIN).isLeft,
        MemoryRetentionPolicy.KeepAll.cutoff(LocalDate.MIN) == Right(None)
      )
    },
    test("immutable policy replacement cannot mutate an earlier setting") {
      for
        days <- ZIO.fromEither(RetentionDays.fromInt(30))
        first  = MemoryRetentionPolicy.DailyDefault
        second = MemoryRetentionPolicy.ArchiveAfter(days)
      yield assertTrue(
        first == MemoryRetentionPolicy.ArchiveAfter(RetentionDays.DailyDefault),
        second.cutoff(LocalDate.of(2026, 4, 1)) == Right(Some(LocalDate.of(2026, 3, 2)))
      )
    }
  )
