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
package com.weisgnosis.agents.harness.plan

import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.harness.eviction.WorkspaceTrust
import com.weisgnosis.agents.harness.storage.{WorkspaceLease, WorkspaceOwner}
import java.nio.file.Path
import zio.*

/** A single PLAN.md in an existing host-controlled directory, with scoped cooperative ownership. */
object LocalPlanDraftStore:
  def open(directory: Path, trust: WorkspaceTrust): ZIO[Scope, AgentError, PlanDraftStore] =
    PlanDraftFiles.open(directory, trust).flatMap(owned)

  def inWorkspace(
      workspace: Path,
      trust: WorkspaceTrust,
      directory: PlanDirectory = PlanDirectory.Default
  ): ZIO[Scope, AgentError, PlanDraftStore] =
    PlanDraftFiles.inWorkspace(workspace, trust, directory).flatMap(owned)

  private def owned(files: PlanDraftFiles): ZIO[Scope, AgentError, PlanDraftStore] =
    WorkspaceLease.initialize:
      for
        lease <- WorkspaceLease.open(files.directory, WorkspaceOwner.Plan)
        store = new PlanDraftStore:
          override val location: Option[PlanLocation]       = Some(files.location)
          def read: IO[AgentError, Option[PlanDraft]]       = lease.protect(files.read)
          def write(draft: PlanDraft): IO[AgentError, Unit] = lease.protect(files.write(draft))
        _ <- store.read
      yield store
