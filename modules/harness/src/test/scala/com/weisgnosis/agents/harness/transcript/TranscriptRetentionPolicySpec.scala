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
package com.weisgnosis.agents.harness.transcript

import com.weisgnosis.agents.harness.memory.RetentionDays
import java.time.Instant
import zio.*
import zio.json.*
import zio.test.*

object TranscriptRetentionPolicySpec extends ZIOSpecDefault:
  def spec: Spec[TestEnvironment, Any] = suite("TranscriptRetentionPolicySpec")(
    test("session default is 180 days and the derived policy preserves validated day counts") {
      val policy = TranscriptRetentionPolicy.Default
      assertTrue(
        RetentionDays.SessionDefault.value == 180,
        policy == TranscriptRetentionPolicy.PruneAfter(RetentionDays.SessionDefault),
        policy.toJson.fromJson[TranscriptRetentionPolicy] == Right(policy),
        "{\"PruneAfter\":{\"days\":0}}".fromJson[TranscriptRetentionPolicy].isLeft,
        "{\"PruneAfter\":{\"days\":\"180\"}}".fromJson[TranscriptRetentionPolicy].isLeft
      )
    },
    test("fixed-day subtraction preserves nanoseconds across a calendar transition") {
      for
        days <- ZIO.fromEither(RetentionDays.fromInt(1))
        cutoff = TranscriptRetentionPolicy
          .PruneAfter(days)
          .cutoff(Instant.parse("2026-03-29T12:00:00.123456789Z"))
      yield assertTrue(cutoff == Right(Some(Instant.parse("2026-03-28T12:00:00.123456789Z"))))
    },
    test("instant underflow is typed while KeepAll and maximum valid inputs remain total") {
      for maximum <- ZIO.fromEither(RetentionDays.fromInt(Int.MaxValue))
      yield assertTrue(
        TranscriptRetentionPolicy.Default.cutoff(Instant.MIN).isLeft,
        TranscriptRetentionPolicy.KeepAll.cutoff(Instant.MIN) == Right(None),
        TranscriptRetentionPolicy.PruneAfter(maximum).cutoff(Instant.MAX).isRight
      )
    },
    test("disabled and evaluated outcomes remain distinct through their public codec") {
      val results = Chunk(
        TranscriptRetentionResult.Disabled,
        TranscriptRetentionResult.Evaluated(Instant.EPOCH, TranscriptPruneResult.Missing),
        TranscriptRetentionResult
          .Evaluated(Instant.EPOCH, TranscriptPruneResult.Retained(Instant.EPOCH)),
        TranscriptRetentionResult.Evaluated(
          Instant.EPOCH,
          TranscriptPruneResult.Pruned(Instant.MIN, 0)
        )
      )
      assertTrue(
        results.forall(result => result.toJson.fromJson[TranscriptRetentionResult] == Right(result))
      )
    }
  )
