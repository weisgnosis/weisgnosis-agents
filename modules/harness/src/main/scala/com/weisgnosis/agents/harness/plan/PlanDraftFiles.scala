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
import com.weisgnosis.agents.core.tool.PathSafety
import com.weisgnosis.agents.harness.eviction.WorkspaceTrust
import java.nio.charset.StandardCharsets
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{
  Files,
  LinkOption,
  NoSuchFileException,
  Path,
  StandardCopyOption,
  StandardOpenOption
}
import zio.*

final private[plan] class PlanDraftFiles private (val directory: Path, val location: PlanLocation):
  import PlanDraftFiles.*
  private val target = directory.resolve(FileName)

  def read: IO[AgentError, Option[PlanDraft]] =
    validateLocation *> existingFile(target, directory).flatMap:
      case None => ZIO.none
      case Some(path) =>
        io(Files.readString(path, StandardCharsets.UTF_8)).flatMap(content =>
          ZIO
            .fromEither(
              PlanDraft
                .fromString(content)
                .left
                .map(error => AgentError.PlanStorageError(error.message))
            )
            .map(Some(_))
        )

  def write(draft: PlanDraft): IO[AgentError, Unit] = ZIO.scoped:
    for
      _ <- validateLocation
      temporary <- ZIO.acquireRelease(
        io(Files.createTempFile(directory, ".plan-pending-", ".tmp"))
      )(cleanup)
      _ <- io(
        Files.writeString(
          temporary,
          draft.content,
          StandardCharsets.UTF_8,
          StandardOpenOption.WRITE
        )
      )
      _ <- existingFile(target, directory)
      _ <- io(
        Files.move(
          temporary,
          target,
          StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING
        )
      )
    yield ()

  private def validateLocation: IO[AgentError, Unit] =
    canonicalDirectory(directory).flatMap(actual =>
      deny("Plan directory changed").unless(actual == directory).unit
    )

private[plan] object PlanDraftFiles:
  private val FileName: String = "PLAN.md"

  def open(directory: Path, trust: WorkspaceTrust): IO[AgentError, PlanDraftFiles] = trust match
    case WorkspaceTrust.Untrusted =>
      deny("Untrusted plan storage requires a secure platform backend")
    case WorkspaceTrust.HostControlled =>
      for
        absolute <- io(directory.toAbsolutePath)
        _ <- deny("Parent traversal is not permitted").when(
          (0 until absolute.getNameCount).exists(index => absolute.getName(index).toString == "..")
        )
        canonical <- canonicalDirectory(absolute)
        _ <- deny("Filesystem roots cannot own plans").when(
          Option(canonical.getParent).isEmpty
        )
        location <- ZIO.fromEither(PlanLocation.fromString(canonical.resolve(FileName).toString))
      yield new PlanDraftFiles(canonical, location)

  def inWorkspace(
      workspace: Path,
      trust: WorkspaceTrust,
      directory: PlanDirectory
  ): IO[AgentError, PlanDraftFiles] =
    for
      root <- open(workspace, trust)
      candidate <- open(
        directory.segments.foldLeft(root.directory)((parent, segment) => parent.resolve(segment)),
        trust
      )
      _ <- validateDescendant(root.directory, candidate.directory)
    yield candidate

  /** Both arguments are already canonical directories; string-prefix checks are insufficient. */
  private[plan] def validateDescendant(workspace: Path, candidate: Path): IO[AgentError, Unit] =
    deny("Plan directory must remain strictly inside its configured workspace")
      .unless(candidate != workspace && candidate.startsWith(workspace))
      .unit

  private def canonicalDirectory(path: Path): IO[AgentError, Path] =
    for
      _ <- deny("Sensitive plan location").when(PathSafety.isSensitivePath(path))
      attributes <- io(
        Files.readAttributes(path, classOf[BasicFileAttributes], LinkOption.NOFOLLOW_LINKS)
      )
      _         <- deny("Plan requires a non-symlink directory").unless(attributes.isDirectory)
      canonical <- io(path.toRealPath())
      _ <- deny("Sensitive canonical plan location").when(
        PathSafety.isSensitivePath(canonical)
      )
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
            _ <- deny("Plan target must be a non-symlink regular file").unless(
              attributes.isRegularFile
            )
            canonical <- io(path.toRealPath())
            _ <- deny("Plan target escapes its directory").unless(
              canonical.getParent == parent && !PathSafety.isSensitivePath(canonical)
            )
          yield Some(canonical)

  private def cleanup(path: Path): UIO[Unit] =
    io(Files.deleteIfExists(path)).unit.catchAll(error =>
      ZIO.logWarningCause(s"Could not remove temporary plan at $path", Cause.fail(error))
    )

  private def deny(message: String): IO[AgentError, Nothing] =
    ZIO.fail(AgentError.PermissionDenied("plan_store", message))

  private def storageError(error: Throwable): AgentError.PlanStorageError =
    AgentError.PlanStorageError(Option(error.getMessage).getOrElse(error.toString))

  private def io[A](operation: => A): IO[AgentError, A] =
    ZIO.attemptBlocking(operation).mapError(storageError)
