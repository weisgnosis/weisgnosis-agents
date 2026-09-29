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

import com.weisgnosis.agents.harness.eviction.WorkspaceTrust
import com.weisgnosis.agents.harness.memory.MemoryTestSupport.{children, temporaryDirectory}
import java.time.Instant
import zio.*
import zio.json.*
import zio.test.*

object TranscriptRetentionTargetSpec extends ZIOSpecDefault:
  def spec: Spec[TestEnvironment & Scope, Any] = suite("TranscriptRetentionTargetSpec")(
    test("IDs reject blanks and preserve exact nonblank correlation values through codecs") {
      for id <- ZIO.fromEither(TranscriptTargetId.fromString(" target / not a path "))
      yield assertTrue(
        id.value == " target / not a path ",
        id.toJson.fromJson[TranscriptTargetId] == Right(id),
        List("", " ", "\t\n").forall(value => TranscriptTargetId.fromString(value).isLeft),
        "42".fromJson[TranscriptTargetId].isLeft
      )
    },
    test("pruning effects are lazy and repeated execution remains observable") {
      for
        id    <- ZIO.fromEither(TranscriptTargetId.fromString("target"))
        calls <- Ref.make(0)
        target = TranscriptRetentionTarget(id)(_ =>
          calls.update(_ + 1).as(TranscriptPruneResult.Missing)
        )
        effect = target.prune(Instant.EPOCH)
        before <- calls.get
        first  <- effect
        second <- effect
        count  <- calls.get
      yield assertTrue(
        before == 0,
        count == 2,
        first == TranscriptPruneResult.Missing,
        second == first
      )
    },
    test("local target construction does not acquire ownership or validate an unused directory") {
      for
        root <- temporaryDirectory
        id   <- ZIO.fromEither(TranscriptTargetId.fromString("local"))
        target = TranscriptRetentionTarget.local(
          id,
          root.resolve("missing"),
          WorkspaceTrust.Untrusted
        )
        before <- children(root)
        failed <- target.prune(Instant.EPOCH).exit
        after  <- children(root)
      yield assertTrue(before.isEmpty, after.isEmpty, failed.isFailure, target.id == id)
    }
  )
