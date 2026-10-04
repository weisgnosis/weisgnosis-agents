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

import com.weisgnosis.agents.core.error.AgentError
import zio.*

/** Host-owned retries of HTTP rejections, never arbitrary model or network failures. */
sealed trait CompletionRetryPolicy

object CompletionRetryPolicy:
  case object Disabled extends CompletionRetryPolicy

  /** Total attempts include the initial request; delay starts only after response cleanup. */
  final class HttpStatusRetries private[CompletionRetryPolicy] (
      val maxAttempts: Int,
      val delay: Duration
  ) extends CompletionRetryPolicy

  val disabled: CompletionRetryPolicy = Disabled

  def make(
      maxAttempts: Int,
      delay: Duration = Duration.Zero
  ): Either[AgentError.InvalidConfiguration, CompletionRetryPolicy] =
    Either.cond(
      maxAttempts >= 1 && delay >= Duration.Zero && delay < Duration.Infinity,
      new HttpStatusRetries(maxAttempts, delay),
      AgentError.InvalidConfiguration(
        "Retry attempts must be positive and delay finite and nonnegative"
      )
    )
