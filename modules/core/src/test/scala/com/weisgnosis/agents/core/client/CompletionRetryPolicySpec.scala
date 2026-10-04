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
package com.weisgnosis.agents.core.client

import zio.*
import zio.test.*

object CompletionRetryPolicySpec extends ZIOSpecDefault:
  def spec: Spec[TestEnvironment, Any] = suite("CompletionRetryPolicySpec")(
    test("checks total attempt counts and finite nonnegative delays without duration overflow") {
      val valid = Chunk((1, Duration.Zero), (3, 1.second), (Int.MaxValue, 1.nanosecond))
      val invalid = Chunk(
        (0, Duration.Zero),
        (-1, Duration.Zero),
        (1, java.time.Duration.ofNanos(-1)),
        (1, Duration.Infinity),
        (1, java.time.Duration.ofSeconds(Long.MaxValue))
      )
      assertTrue(
        CompletionRetryPolicy.disabled == CompletionRetryPolicy.Disabled,
        valid.forall((attempts, delay) =>
          CompletionRetryPolicy.make(attempts, delay).exists {
            case policy: CompletionRetryPolicy.HttpStatusRetries =>
              policy.maxAttempts == attempts && policy.delay == delay
            case _ => false
          }
        ),
        invalid.forall((attempts, delay) => CompletionRetryPolicy.make(attempts, delay).isLeft)
      )
    }
  )
