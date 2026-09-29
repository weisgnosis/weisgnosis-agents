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
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, LinkOption, Path, StandardOpenOption}
import zio.*

/** Cooperative ownership; an unfinished lease is never removed by a competing acquisition. */
final private[memory] class DailyMemoryLease private (
    path: Path,
    token: String,
    gate: Semaphore,
    lifetime: Ref[DailyMemoryLease.Lifetime]
):
  /** Once admitted, filesystem work finishes before cancellation releases ownership. */
  def protect[A](operation: IO[AgentError, A]): IO[AgentError, A] =
    ZIO.checkInterruptible: status =>
      gate.withPermit:
        ZIO.uninterruptible:
          for
            state <- lifetime.get
            _ <- ZIO
              .fail(AgentError.MemoryStorageError("Daily memory store scope is closed"))
              .when(state == DailyMemoryLease.Lifetime.Closed)
            _      <- verify
            result <- operation
            _      <- ZIO.allowInterrupt.when(status.isInterruptible)
          yield result

  private def verify: IO[AgentError, Unit] =
    for
      regular <- DailyMemoryLease.io(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
      _ <- ZIO
        .fail(AgentError.MemoryStorageError("Daily memory ownership file is unavailable"))
        .unless(regular)
      actual <- DailyMemoryLease.io(Files.readString(path, StandardCharsets.UTF_8))
      _ <- ZIO
        .fail(AgentError.MemoryStorageError("Daily memory ownership token changed"))
        .unless(actual == token)
    yield ()

  private def close: UIO[Unit] =
    gate
      .withPermit(
        lifetime.set(DailyMemoryLease.Lifetime.Closed) *> verify *>
          DailyMemoryLease.io(Files.delete(path))
      )
      .orDie

private[memory] object DailyMemoryLease:
  private enum Lifetime:
    case Open, Closed

  /** directory is the already validated canonical memory directory of a trusted workspace. */
  def open(directory: Path): ZIO[Scope, AgentError, DailyMemoryLease] =
    for
      gate     <- Semaphore.make(1)
      lifetime <- Ref.make(Lifetime.Open)
      token    <- Random.nextUUID.map(_.toString)
      path = directory.resolve(".daily-writer.lease")
      lease <- ZIO.acquireRelease(
        io(
          Files.writeString(
            path,
            token,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE
          )
        ).as(new DailyMemoryLease(path, token, gate, lifetime))
      )(_.close)
    yield lease

  private def io[A](operation: => A): IO[AgentError, A] =
    ZIO
      .attemptBlocking(operation)
      .mapError(error =>
        AgentError.MemoryStorageError(Option(error.getMessage).getOrElse(error.toString))
      )
