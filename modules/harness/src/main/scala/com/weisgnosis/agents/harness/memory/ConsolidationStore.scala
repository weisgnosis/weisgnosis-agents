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
package com.weisgnosis.agents.harness.memory

import com.weisgnosis.agents.core.error.AgentError
import java.time.LocalDate
import zio.*
import zio.json.JsonCodec

/** The complete pending content for one daily ledger in an immutable snapshot. */
final case class DailyMemory(day: LocalDate, content: String) derives JsonCodec

/** Revision covers curated content and all daily inputs; each day must occur at most once. */
final case class ConsolidationSnapshot[Revision](
    revision: Revision,
    curated: String,
    pending: Chunk[DailyMemory]
) derives JsonCodec

enum ConsolidationCommit derives JsonCodec:
  case Applied, Conflict

/**
 * Curated-write capability with no daily-write operations. Commit must atomically replace curated
 * content and acknowledge the exact snapshot's daily inputs, or change nothing on Conflict.
 * Snapshots are atomic reads; every curated/daily/progress mutation advances a never-reused revision.
 */
trait ConsolidationStore:
  type Revision
  def snapshot: IO[AgentError, ConsolidationSnapshot[Revision]]
  def commit(expected: Revision, content: String): IO[AgentError, ConsolidationCommit]
