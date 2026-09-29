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
import com.weisgnosis.agents.harness.transcript.{TranscriptBatchResult, TranscriptRetentionBatch}
import zio.*
import zio.json.*

final case class MaintenanceResult(
    memory: MemoryMaintenanceResult,
    transcripts: TranscriptBatchResult
) derives JsonCodec

/** Sequential phases with observable partial progress; the owner queue serializes scheduled runs. */
final class MemoryMaintenanceCoordinator(
    memory: MemoryMaintenance,
    transcripts: TranscriptRetentionBatch
):
  def run: IO[AgentError, MaintenanceResult] =
    for
      prepared         <- transcripts.prepare
      memoryResult     <- memory.run
      transcriptResult <- prepared.execute
    yield MaintenanceResult(memoryResult, transcriptResult)
