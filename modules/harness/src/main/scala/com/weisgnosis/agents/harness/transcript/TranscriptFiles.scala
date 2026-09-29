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

final private[transcript] class TranscriptFiles private (val directory: Path):
  import TranscriptFiles.*
  private val target = directory.resolve("transcript.jsonl")

  def read: IO[AgentError, String] =
    validateLocation *> existingFile(target, directory).flatMap:
      case None       => ZIO.succeed("")
      case Some(path) => io(Files.readString(path, StandardCharsets.UTF_8))

  def write(content: String): IO[AgentError, Unit] = ZIO.scoped:
    for
      _ <- validateLocation
      temporary <- ZIO.acquireRelease(
        io(Files.createTempFile(directory, ".transcript-pending-", ".tmp"))
      )(cleanup)
      _ <- io(
        Files.writeString(temporary, content, StandardCharsets.UTF_8, StandardOpenOption.WRITE)
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
      deny("Transcript directory changed").unless(actual == directory).unit
    )

private[transcript] object TranscriptFiles:
  def open(directory: Path, trust: WorkspaceTrust): IO[AgentError, TranscriptFiles] = trust match
    case WorkspaceTrust.Untrusted =>
      deny("Untrusted transcript storage requires a secure platform backend")
    case WorkspaceTrust.HostControlled =>
      for
        absolute <- io(directory.toAbsolutePath)
        _ <- deny("Parent traversal is not permitted").when(
          (0 until absolute.getNameCount).exists(index => absolute.getName(index).toString == "..")
        )
        canonical <- canonicalDirectory(absolute)
        _ <- deny("Filesystem roots cannot own transcripts").when(
          Option(canonical.getParent).isEmpty
        )
      yield new TranscriptFiles(canonical)

  private def canonicalDirectory(path: Path): IO[AgentError, Path] =
    for
      _ <- deny("Sensitive transcript location").when(PathSafety.isSensitivePath(path))
      attributes <- io(
        Files.readAttributes(path, classOf[BasicFileAttributes], LinkOption.NOFOLLOW_LINKS)
      )
      _ <- deny("Transcript requires a non-symlink directory").unless(attributes.isDirectory)
      canonical <- io(path.toRealPath())
      _ <- deny("Sensitive canonical transcript location").when(
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
            _ <- deny("Transcript target must be a non-symlink regular file").unless(
              attributes.isRegularFile
            )
            canonical <- io(path.toRealPath())
            _ <- deny("Transcript target escapes its directory").unless(
              canonical.getParent == parent && !PathSafety.isSensitivePath(canonical)
            )
          yield Some(canonical)

  private def cleanup(path: Path): UIO[Unit] =
    io(Files.deleteIfExists(path)).unit.catchAll(error =>
      ZIO.logWarningCause(s"Could not remove temporary transcript at $path", Cause.fail(error))
    )

  private def deny(message: String): IO[AgentError, Nothing] =
    ZIO.fail(AgentError.PermissionDenied("transcript_store", message))

  private def storageError(error: Throwable): AgentError.MemoryStorageError =
    AgentError.MemoryStorageError(Option(error.getMessage).getOrElse(error.toString))

  private def io[A](operation: => A): IO[AgentError, A] =
    ZIO.attemptBlocking(operation).mapError(storageError)
