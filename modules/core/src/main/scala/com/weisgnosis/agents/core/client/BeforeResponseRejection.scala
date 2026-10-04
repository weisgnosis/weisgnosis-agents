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

/** Adapter-owned evidence of an eligible rejection before any successful response frame. */
final private[core] class BeforeResponseRejection private[client] (
    val original: AgentError.ModelError
)

private[core] object BeforeResponseRejection:
  /** Public calls expose domain failures without losing compound causes during translation. */
  private[client] def expose[R, A](
      effect: ZIO[R, AgentError | BeforeResponseRejection, A]
  ): ZIO[R, AgentError, A] =
    ZIO.uninterruptibleMask: restore =>
      restore(effect).mapError:
        case rejection: BeforeResponseRejection => rejection.original
        case error: AgentError                  => error
