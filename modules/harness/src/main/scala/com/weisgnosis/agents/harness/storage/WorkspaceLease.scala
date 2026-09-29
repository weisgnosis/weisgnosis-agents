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
package com.weisgnosis.agents.harness.storage

import com.weisgnosis.agents.core.error.AgentError
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, LinkOption, Path, StandardOpenOption}
import zio.*

private[harness] enum WorkspaceOwner:
  case Memory, Transcript, Plan

  def failure(message: String): AgentError = this match
    case Plan                => AgentError.PlanStorageError(message)
    case Memory | Transcript => AgentError.MemoryStorageError(message)

/** Cooperative ownership; an unfinished lease is never removed by a competing acquisition. */
final private[harness] class WorkspaceLease private (
    path: Path,
    token: String,
    owner: WorkspaceOwner,
    gate: Semaphore,
    lifetime: Ref[WorkspaceLease.Lifetime]
):
  /** Once admitted, filesystem work finishes before cancellation releases ownership. */
  def protect[A](operation: IO[AgentError, A]): IO[AgentError, A] =
    ZIO.checkInterruptible: status =>
      gate.withPermit:
        ZIO.uninterruptible:
          for
            state <- lifetime.get
            _ <- ZIO
              .fail(owner.failure("Workspace storage store scope is closed"))
              .when(state == WorkspaceLease.Lifetime.Closed)
            _      <- verify
            result <- operation
            _      <- ZIO.allowInterrupt.when(status.isInterruptible)
          yield result

  private def verify: IO[AgentError, Unit] =
    for
      regular <- io(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
      _ <- ZIO
        .fail(owner.failure("Workspace storage ownership file is unavailable"))
        .unless(regular)
      actual <- io(Files.readString(path, StandardCharsets.UTF_8))
      _ <- ZIO
        .fail(owner.failure("Workspace storage ownership token changed"))
        .unless(actual == token)
    yield ()

  private def io[A](operation: => A): IO[AgentError, A] = WorkspaceLease.io(operation, owner)

  private def close: UIO[Unit] =
    gate
      .withPermit(
        lifetime.set(WorkspaceLease.Lifetime.Closed) *> verify *>
          io(Files.delete(path))
      )
      .orDie

private[harness] object WorkspaceLease:
  private enum Lifetime:
    case Open, Closed

  /** directory is the already validated canonical storage directory of a trusted workspace. */
  def open(directory: Path, owner: WorkspaceOwner): ZIO[Scope, AgentError, WorkspaceLease] =
    for
      gate     <- Semaphore.make(1)
      lifetime <- Ref.make(Lifetime.Open)
      token    <- Random.nextUUID.map(_.toString)
      path = directory.resolve(owner match
        case WorkspaceOwner.Memory     => ".daily-writer.lease"
        case WorkspaceOwner.Transcript => ".transcript-writer.lease"
        case WorkspaceOwner.Plan       => ".plan-writer.lease")
      lease <- ZIO.acquireRelease(
        io(
          Files.writeString(
            path,
            token,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE
          ),
          owner
        ).as(new WorkspaceLease(path, token, owner, gate, lifetime))
      )(_.close)
    yield lease

  private def io[A](operation: => A, owner: WorkspaceOwner): IO[AgentError, A] =
    ZIO
      .attemptBlocking(operation)
      .mapError(error => owner.failure(Option(error.getMessage).getOrElse(error.toString)))
