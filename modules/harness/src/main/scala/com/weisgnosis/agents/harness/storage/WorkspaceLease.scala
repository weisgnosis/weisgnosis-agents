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
  case Memory, Transcript, Plan, VersionedJson

  def failure(message: String): AgentError = this match
    case Plan                => AgentError.PlanStorageError(message)
    case VersionedJson       => AgentError.StorageError(message)
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
            outcome <- (for
              state <- lifetime.get
              _ <- ZIO
                .fail(owner.failure("Workspace storage store scope is closed"))
                .when(state == WorkspaceLease.Lifetime.Closed)
              _     <- verify
              value <- operation
            yield value).exit
            cancellation <- ZIO.allowInterrupt.when(status.isInterruptible).exit
            result       <- outcome.zipWith(cancellation)((value, _) => value, _ ++ _)
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

  /**
   * Failed/interrupted construction unwinds before returning; successful resources remain owned
   * by the caller scope. A repeated child close does not replay its completed finalizers.
   */
  def initialize[A](initialization: ZIO[Scope, AgentError, A]): ZIO[Scope, AgentError, A] =
    ZIO.uninterruptibleMask: restore =>
      for
        child <- ZIO.acquireReleaseExit(Scope.make)((scope, exit) => scope.close(exit))
        value <- restore(child.extend(ZIO.suspendSucceed(initialization))).onExit:
          case failure @ Exit.Failure(_) => child.close(failure)
          case Exit.Success(_)           => ZIO.unit
      yield value

  /** directory is the already validated canonical storage directory of a trusted workspace. */
  def open(directory: Path, owner: WorkspaceOwner): ZIO[Scope, AgentError, WorkspaceLease] =
    openNamed(
      directory,
      owner,
      owner match
        case WorkspaceOwner.Memory        => ".daily-writer.lease"
        case WorkspaceOwner.Transcript    => ".transcript-writer.lease"
        case WorkspaceOwner.Plan          => ".plan-writer.lease"
        case WorkspaceOwner.VersionedJson => ".json-writer.lease"
    )

  /** directory is canonical and host-controlled; the checked name is one portable component. */
  def openNamed(
      directory: Path,
      owner: WorkspaceOwner,
      name: String
  ): ZIO[Scope, AgentError, WorkspaceLease] =
    for
      _        <- ZIO.fromEither(validateName(name, 255).left.map(owner.failure))
      gate     <- Semaphore.make(1)
      lifetime <- Ref.make(Lifetime.Open)
      token    <- Random.nextUUID.map(_.toString)
      path = directory.resolve(name)
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

  private[harness] def validateName(name: String, maxBytes: Int): Either[String, Unit] =
    val reserved =
      Set("CON", "PRN", "AUX", "NUL") ++ (1 to 9).flatMap(index => List(s"COM$index", s"LPT$index"))
    val stem = name.takeWhile(_ != '.').toUpperCase(java.util.Locale.ROOT)
    Either.cond(
      name.nonEmpty && name != "." && name != ".." &&
        name.forall(character =>
          character >= 'a' && character <= 'z' || character >= '0' && character <= '9' || ".-_"
            .contains(character)
        ) &&
        !name.endsWith(".") && !name.endsWith(" ") && !reserved.contains(stem) &&
        name.getBytes(StandardCharsets.UTF_8).length <= maxBytes,
      (),
      "Storage filename must be one bounded portable lowercase ASCII component"
    )

  private def io[A](operation: => A, owner: WorkspaceOwner): IO[AgentError, A] =
    ZIO
      .attemptBlocking(operation)
      .mapError(error => owner.failure(Option(error.getMessage).getOrElse(error.toString)))
