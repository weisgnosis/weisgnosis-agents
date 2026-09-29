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
import com.weisgnosis.agents.core.event.ModelStreamContext
import zio.*

/** Request-scoped replacement of visible text fragments before assembly and event publication. */
trait ModelTextTransform:
  def transform(
      context: ModelStreamContext,
      fragment: LlmChunk.TextDelta
  ): ZIO[Scope, AgentError.TextTransformationError, String]

  /** Composes replacements in order, preserving the original block index and request identity. */
  final def andThen(next: ModelTextTransform): ModelTextTransform =
    ModelTextTransform: (context, fragment) =>
      transform(context, fragment).flatMap(text =>
        next.transform(context, fragment.copy(delta = text))
      )

object ModelTextTransform:
  val identity: ModelTextTransform =
    ModelTextTransform((_, fragment) => ZIO.succeed(fragment.delta))

  /** Suspends the supplied callback; resources and child fibers belong to the model request scope. */
  def apply(
      run: (ModelStreamContext, LlmChunk.TextDelta) => ZIO[
        Scope,
        AgentError.TextTransformationError,
        String
      ]
  ): ModelTextTransform = new ModelTextTransform:
    def transform(
        context: ModelStreamContext,
        fragment: LlmChunk.TextDelta
    ): ZIO[Scope, AgentError.TextTransformationError, String] =
      ZIO.suspendSucceed(run(context, fragment))
