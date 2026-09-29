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
import zio.*

/**
 * One capability owns one transcript. Append atomically deduplicates, links, and publishes a batch.
 * Stable keys retain the first observation; anonymous drafts append on every invocation.
 * Returned entries are exactly the newly committed observations in their published order.
 */
trait TranscriptStore:
  def entries: IO[AgentError, Chunk[TranscriptEntry]]
  def append(drafts: Chunk[TranscriptDraft]): IO[AgentError, Chunk[TranscriptEntry]]

object TranscriptStore:
  /** Process-local authority; UUIDs and recording time are allocated outside the pure transition. */
  def inMemory: UIO[TranscriptStore] =
    Ref
      .make(TranscriptState.empty)
      .map: state =>
        new TranscriptStore:
          def entries: IO[AgentError, Chunk[TranscriptEntry]] = state.get.map(_.entries)
          def append(drafts: Chunk[TranscriptDraft]): IO[AgentError, Chunk[TranscriptEntry]] =
            if drafts.isEmpty then ZIO.succeed(Chunk.empty)
            else
              for
                instant    <- Clock.instant
                candidates <- ZIO.foreach(drafts)(draft => Random.nextUUID.map(id => draft -> id))
                result <- state.modify: current =>
                  current.append(candidates, instant) match
                    case Left(error) => Left(error) -> current
                    case Right(updated) =>
                      Right(updated.entries.drop(current.entries.size)) -> updated
                committed <- ZIO.fromEither(result)
              yield committed
