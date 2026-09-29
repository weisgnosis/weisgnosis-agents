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
import com.weisgnosis.agents.core.tool.PathSafety
import com.weisgnosis.agents.harness.eviction.WorkspaceTrust
import java.nio.charset.StandardCharsets
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{
  FileAlreadyExistsException,
  Files,
  LinkOption,
  NoSuchFileException,
  Path,
  StandardCopyOption,
  StandardOpenOption
}
import java.time.LocalDate
import zio.*

/** Scoped, exclusive daily-ledger ownership for a host-controlled local workspace. */
object LocalDailyMemoryStore:
  /** The workspace and every ancestor must be protected against uncoordinated mutation. */
  def open(workspace: Path, trust: WorkspaceTrust): ZIO[Scope, AgentError, DailyMemoryStore] =
    trust match
      case WorkspaceTrust.Untrusted =>
        ZIO.fail(
          AgentError.PermissionDenied(
            "daily_memory_store",
            "Untrusted memory storage requires a secure platform backend"
          )
        )
      case WorkspaceTrust.HostControlled =>
        for
          absolute <- io(workspace.toAbsolutePath)
          _        <- rejectTraversal(absolute)
          root     <- directory(absolute)
          _ <- deny("Filesystem roots cannot own daily memory").when(Option(root.getParent).isEmpty)
          target = root.resolve("memory")
          _ <- ZIO
            .attemptBlocking(Files.createDirectory(target))
            .catchSome { case _: FileAlreadyExistsException => ZIO.succeed(target) }
            .mapError(storageError)
          memory <- directory(target)
          _      <- deny("Memory directory escapes its workspace").unless(memory.getParent == root)
          lease  <- DailyMemoryLease.open(memory)
        yield new DailyMemoryStore:
          private def owned[A](operation: IO[AgentError, A]): IO[AgentError, A] =
            lease.protect:
              for
                actualRoot   <- directory(root)
                actualMemory <- directory(memory)
                _ <- deny("Daily memory location changed").unless(
                  actualRoot == root && actualMemory == memory
                )
                value <- operation
              yield value

          def readCurated: IO[AgentError, Option[String]] = owned(
            read(root.resolve("MEMORY.md"), root)
          )

          def readDaily(day: LocalDate): IO[AgentError, Option[String]] = owned(
            read(memory.resolve(s"$day.md"), memory)
          )

          def appendDaily(day: LocalDate, entry: String): IO[AgentError, Unit] = owned:
            if entry.isEmpty then ZIO.unit
            else
              ZIO.scoped:
                val target = memory.resolve(s"$day.md")
                for
                  previous <- read(target, memory)
                  temporary <- ZIO.acquireRelease(
                    io(Files.createTempFile(memory, ".daily-pending-", ".tmp"))
                  )(cleanup)
                  _ <- io(
                    Files.writeString(
                      temporary,
                      previous.getOrElse("") + entry,
                      StandardCharsets.UTF_8,
                      StandardOpenOption.WRITE
                    )
                  )
                  _ <- existingFile(target, memory)
                  _ <- io(
                    Files.move(
                      temporary,
                      target,
                      StandardCopyOption.ATOMIC_MOVE,
                      StandardCopyOption.REPLACE_EXISTING
                    )
                  )
                yield ()

  private def rejectTraversal(path: Path): IO[AgentError, Unit] =
    deny("Parent traversal is not permitted")
      .when(
        (0 until path.getNameCount).exists(index => path.getName(index).toString == "..")
      )
      .unit

  private def directory(path: Path): IO[AgentError, Path] =
    for
      _ <- deny("Sensitive daily memory location").when(PathSafety.isSensitivePath(path))
      attributes <- io(
        Files.readAttributes(path, classOf[BasicFileAttributes], LinkOption.NOFOLLOW_LINKS)
      )
      _ <- deny("Daily memory requires a non-symlink directory").unless(attributes.isDirectory)
      canonical <- io(path.toRealPath())
      _ <- deny("Sensitive canonical memory location").when(PathSafety.isSensitivePath(canonical))
    yield canonical

  private def existingFile(path: Path, parent: Path): IO[AgentError, Option[Path]] =
    ZIO
      .attemptBlocking(
        Files.readAttributes(path, classOf[BasicFileAttributes], LinkOption.NOFOLLOW_LINKS)
      )
      .map(Some(_))
      .catchSome { case _: NoSuchFileException => ZIO.none }
      .mapError(storageError)
      .flatMap:
        case None => ZIO.none
        case Some(attributes) =>
          for
            _ <- deny("Memory target must be a non-symlink regular file").unless(
              attributes.isRegularFile
            )
            canonical <- io(path.toRealPath())
            _ <- deny("Memory target escapes its directory").unless(
              canonical.getParent == parent && !PathSafety.isSensitivePath(canonical)
            )
          yield Some(canonical)

  private def read(path: Path, parent: Path): IO[AgentError, Option[String]] =
    existingFile(path, parent).flatMap:
      case None            => ZIO.none
      case Some(canonical) => io(Files.readString(canonical, StandardCharsets.UTF_8)).map(Some(_))

  private def cleanup(path: Path): UIO[Unit] =
    io(Files.deleteIfExists(path)).unit.catchAll(error =>
      ZIO.logWarningCause(s"Could not remove temporary daily memory at $path", Cause.fail(error))
    )

  private def deny(message: String): IO[AgentError, Nothing] =
    ZIO.fail(AgentError.PermissionDenied("daily_memory_store", message))

  private def storageError(error: Throwable): AgentError.MemoryStorageError =
    AgentError.MemoryStorageError(Option(error.getMessage).getOrElse(error.toString))

  private def io[A](operation: => A): IO[AgentError, A] =
    ZIO.attemptBlocking(operation).mapError(storageError)
