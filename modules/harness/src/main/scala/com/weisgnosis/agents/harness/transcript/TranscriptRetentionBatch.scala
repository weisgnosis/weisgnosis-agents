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
import java.time.Instant
import zio.*
import zio.json.*

final case class TranscriptTargetResult(id: TranscriptTargetId, result: TranscriptPruneResult)
    derives JsonCodec

enum TranscriptBatchResult derives JsonCodec:
  case Disabled
  case Evaluated(cutoff: Instant, targets: Chunk[TranscriptTargetResult])

/** Runtime plan; replaying execute repeats effects and does not provide exactly-once delivery. */
final private[harness] class PreparedTranscriptPruning private[transcript] (
    val execute: IO[AgentError, TranscriptBatchResult]
)

/** Ordered explicit capabilities; no filesystem discovery or per-target failure suppression. */
final class TranscriptRetentionBatch private (
    targets: Chunk[TranscriptRetentionTarget],
    policy: TranscriptRetentionPolicy
):
  def run: IO[AgentError, TranscriptBatchResult] = prepare.flatMap(_.execute)

  private[harness] def prepare: IO[AgentError, PreparedTranscriptPruning] =
    val cutoff = policy match
      case TranscriptRetentionPolicy.KeepAll => ZIO.none
      case TranscriptRetentionPolicy.PruneAfter(_) =>
        Clock.instant.flatMap(now => ZIO.fromEither(policy.cutoff(now)))
    cutoff.map:
      case None => new PreparedTranscriptPruning(ZIO.succeed(TranscriptBatchResult.Disabled))
      case Some(before) =>
        new PreparedTranscriptPruning(
          ZIO
            .foreach(targets)(target =>
              target.prune(before).map(TranscriptTargetResult(target.id, _))
            )
            .map(TranscriptBatchResult.Evaluated(before, _))
        )

object TranscriptRetentionBatch:
  def make(
      targets: Chunk[TranscriptRetentionTarget],
      policy: TranscriptRetentionPolicy = TranscriptRetentionPolicy.Default
  ): IO[AgentError, TranscriptRetentionBatch] =
    if targets.map(_.id).distinct.size != targets.size then
      ZIO.fail(
        AgentError.InvalidConfiguration("Transcript target IDs must be unique within a batch")
      )
    else ZIO.succeed(new TranscriptRetentionBatch(targets, policy))
