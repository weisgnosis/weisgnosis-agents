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
import com.weisgnosis.agents.harness.storage.{WorkspaceLease, WorkspaceOwner}
import java.nio.file.Path
import zio.*

/** A single transcript in an existing host-controlled directory; every operation owns the same lease. */
object LocalTranscriptStore:
  def open(directory: Path, trust: WorkspaceTrust): ZIO[Scope, AgentError, TranscriptStore] =
    for
      files <- TranscriptFiles.open(directory, trust)
      lease <- WorkspaceLease.open(files.directory, WorkspaceOwner.Transcript)
      store = new TranscriptStore:
        private def state: IO[AgentError, TranscriptState] =
          files.read.flatMap(text => ZIO.fromEither(TranscriptLog.decode(text)))
        def entries: IO[AgentError, Chunk[TranscriptEntry]] = lease.protect(state.map(_.entries))
        def append(drafts: Chunk[TranscriptDraft]): IO[AgentError, Chunk[TranscriptEntry]] =
          lease.protect:
            for
              before     <- state
              instant    <- Clock.instant
              candidates <- ZIO.foreach(drafts)(draft => Random.nextUUID.map(id => draft -> id))
              after      <- ZIO.fromEither(before.append(candidates, instant))
              appended = after.entries.drop(before.entries.size)
              _ <- files.write(TranscriptLog.encode(after)).unless(appended.isEmpty)
            yield appended
      _ <- store.entries
    yield store
