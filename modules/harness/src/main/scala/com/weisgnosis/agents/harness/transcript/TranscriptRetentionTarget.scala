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

import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.harness.eviction.WorkspaceTrust
import java.nio.file.Path
import java.time.Instant
import zio.*
import zio.json.*

/** Exact host correlation, never a path, credential or declaration that a session is inactive. */
opaque type TranscriptTargetId = String

object TranscriptTargetId:
  def fromString(value: String): Either[AgentError.InvalidConfiguration, TranscriptTargetId] =
    if value.isBlank then
      Left(AgentError.InvalidConfiguration("Transcript target ID must not be blank"))
    else Right(value)

  given JsonCodec[TranscriptTargetId] = JsonCodec.string.transformOrFail(
    value => fromString(value).left.map(_.message),
    identity
  )

  extension (id: TranscriptTargetId) def value: String = id

/** Runtime capability whose implementation owns its pruning resources. */
final class TranscriptRetentionTarget private (
    val id: TranscriptTargetId,
    operation: Instant => IO[AgentError, TranscriptPruneResult]
):
  def prune(before: Instant): IO[AgentError, TranscriptPruneResult] =
    ZIO.suspendSucceed(operation(before))

object TranscriptRetentionTarget:
  def apply(id: TranscriptTargetId)(
      operation: Instant => IO[AgentError, TranscriptPruneResult]
  ): TranscriptRetentionTarget =
    new TranscriptRetentionTarget(id, operation)

  def local(
      id: TranscriptTargetId,
      directory: Path,
      trust: WorkspaceTrust
  ): TranscriptRetentionTarget =
    apply(id)(cutoff => LocalTranscriptRetention.prune(directory, trust, cutoff))
