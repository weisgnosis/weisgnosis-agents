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
import scala.jdk.CollectionConverters.*
import zio.*

private[memory] enum MemoryFile:
  case Curated, Checkpoint, Journal
  case Daily(day: LocalDate)

/** Fixed destinations and atomic single-file replacement inside a host-controlled workspace. */
final private[memory] class MemoryFiles private (root: Path, val directory: Path):
  import MemoryFiles.*

  def validateLocation: IO[AgentError, Unit] =
    for
      actualRoot   <- canonicalDirectory(root)
      actualMemory <- canonicalDirectory(directory)
      _ <- deny("Memory location changed").unless(actualRoot == root && actualMemory == directory)
    yield ()

  def read(file: MemoryFile): IO[AgentError, Option[String]] =
    val target = path(file)
    existingFile(target, target.getParent).flatMap:
      case None            => ZIO.none
      case Some(canonical) => io(Files.readString(canonical, StandardCharsets.UTF_8)).map(Some(_))

  def write(file: MemoryFile, content: String): IO[AgentError, Unit] =
    writeTarget(path(file), content)

  private def writeTarget(target: Path, content: String): IO[AgentError, Unit] = ZIO.scoped:
    for
      temporary <- ZIO.acquireRelease(
        io(Files.createTempFile(target.getParent, ".memory-pending-", ".tmp"))
      )(cleanup)
      _ <- io(
        Files.writeString(temporary, content, StandardCharsets.UTF_8, StandardOpenOption.WRITE)
      )
      _ <- existingFile(target, target.getParent)
      _ <- io(
        Files.move(
          temporary,
          target,
          StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING
        )
      )
    yield ()

  def removeJournal: IO[AgentError, Unit] =
    existingFile(path(MemoryFile.Journal), directory) *> io(Files.delete(path(MemoryFile.Journal)))

  def dailyContents: IO[AgentError, Map[LocalDate, String]] = ZIO.scoped:
    for
      stream <- ZIO.acquireRelease(io(Files.list(directory)))(value => io(value.close()).orDie)
      names  <- io(stream.iterator().asScala.map(_.getFileName.toString).toList)
      days <- ZIO.foreach(names.filter(_.endsWith(".md"))): name =>
        ZIO
          .attempt(LocalDate.parse(name.stripSuffix(".md")))
          .option
          .map(_.filter(day => s"$day.md" == name))
      contents <- ZIO.foreach(days.flatten): day =>
        read(MemoryFile.Daily(day))
          .someOrFail(AgentError.MemoryStorageError("Listed daily memory disappeared"))
          .map(day -> _)
    yield contents.toMap

  def readArchive(archive: DailyMemoryArchive): IO[AgentError, Option[String]] =
    archiveDirectory.flatMap:
      case None => ZIO.none
      case Some(parent) =>
        existingFile(archivePath(parent, archive), parent).flatMap:
          case None => ZIO.none
          case Some(target) =>
            for
              content     <- io(Files.readString(target, StandardCharsets.UTF_8))
              fingerprint <- MemoryFingerprint.of(content)
              _ <- ZIO
                .fail(
                  AgentError.MemoryStorageError("Archive content does not match its fingerprint")
                )
                .unless(fingerprint == archive.fingerprint)
            yield Some(content)

  def archives: IO[AgentError, Chunk[DailyMemoryArchive]] =
    archiveDirectory.flatMap:
      case None => ZIO.succeed(Chunk.empty)
      case Some(parent) =>
        ZIO.scoped:
          for
            stream <- ZIO.acquireRelease(io(Files.list(parent)))(value => io(value.close()).orDie)
            names  <- io(stream.iterator().asScala.map(_.getFileName.toString).toList)
            candidates <- ZIO.foreach(names): name =>
              name.stripSuffix(".md").split("_", -1).toList match
                case date :: fingerprint :: Nil if name.endsWith(".md") =>
                  ZIO
                    .attempt(LocalDate.parse(date))
                    .option
                    .map: parsed =>
                      for
                        day    <- parsed.filter(_.toString == date)
                        digest <- MemoryFingerprint.fromString(fingerprint).toOption
                      yield DailyMemoryArchive(day, digest)
                case _ => ZIO.none
            ordered = candidates.flatten.sortBy(archive =>
              (archive.day.toEpochDay, archive.fingerprint.value)
            )
            _ <- ZIO.foreachDiscard(ordered)(archive =>
              readArchive(archive).someOrFail(
                AgentError.MemoryStorageError("Listed archive disappeared")
              )
            )
          yield Chunk.fromIterable(ordered)

  /** Preflight the complete batch before publishing archives or changing acknowledgements. */
  def validateArchive(archive: DailyMemoryArchive, content: String): IO[AgentError, Unit] =
    for
      _     <- ensureArchiveDirectory
      daily <- read(MemoryFile.Daily(archive.day))
      _ <- ZIO
        .fail(AgentError.MemoryStorageError("Archive source changed"))
        .unless(daily.contains(content))
      previous <- readArchive(archive)
      _ <- ZIO
        .fail(AgentError.MemoryStorageError("Archive destination conflicts with source"))
        .when(previous.exists(_ != content))
    yield ()

  /** Publish and verify a copy before the caller removes acknowledgement and then the active file. */
  def publishArchive(archive: DailyMemoryArchive, content: String): IO[AgentError, Unit] =
    for
      _        <- validateArchive(archive, content)
      parent   <- ensureArchiveDirectory
      previous <- readArchive(archive)
      _        <- writeTarget(archivePath(parent, archive), content).when(previous.isEmpty)
      stored   <- readArchive(archive)
      _ <- ZIO
        .fail(AgentError.MemoryStorageError("Archive publication verification failed"))
        .unless(stored.contains(content))
    yield ()

  def removeArchivedDaily(archive: DailyMemoryArchive, content: String): IO[AgentError, Unit] =
    for
      _      <- validateArchive(archive, content)
      stored <- readArchive(archive)
      _ <- ZIO
        .fail(
          AgentError.MemoryStorageError("Verified archive required before removing daily memory")
        )
        .unless(stored.contains(content))
      _ <- io(Files.delete(path(MemoryFile.Daily(archive.day))))
    yield ()

  private def archiveDirectory: IO[AgentError, Option[Path]] =
    val target = directory.resolve("archive")
    ZIO
      .attemptBlocking(
        Files.readAttributes(target, classOf[BasicFileAttributes], LinkOption.NOFOLLOW_LINKS)
      )
      .map(Some(_))
      .catchSome { case _: NoSuchFileException => ZIO.none }
      .mapError(storageError)
      .flatMap:
        case None => ZIO.none
        case Some(_) =>
          canonicalDirectory(target).flatMap: canonical =>
            deny("Archive directory escapes memory ownership")
              .unless(canonical == target)
              .as(Some(canonical))

  private def ensureArchiveDirectory: IO[AgentError, Path] =
    archiveDirectory.flatMap:
      case Some(parent) => ZIO.succeed(parent)
      case None =>
        ZIO
          .attemptBlocking(Files.createDirectory(directory.resolve("archive")))
          .catchSome { case _: FileAlreadyExistsException =>
            ZIO.succeed(directory.resolve("archive"))
          }
          .mapError(storageError) *> archiveDirectory.someOrFail(
          AgentError.MemoryStorageError("Archive directory disappeared")
        )

  private def archivePath(parent: Path, archive: DailyMemoryArchive): Path =
    parent.resolve(s"${archive.day}_${archive.fingerprint.value}.md")

  private def path(file: MemoryFile): Path = file match
    case MemoryFile.Curated    => root.resolve("MEMORY.md")
    case MemoryFile.Checkpoint => directory.resolve(".consolidation_state")
    case MemoryFile.Journal    => directory.resolve(".consolidation_journal")
    case MemoryFile.Daily(day) => directory.resolve(s"$day.md")

private[memory] object MemoryFiles:
  def open(workspace: Path, trust: WorkspaceTrust): IO[AgentError, MemoryFiles] = trust match
    case WorkspaceTrust.Untrusted =>
      deny("Untrusted memory storage requires a secure platform backend")
    case WorkspaceTrust.HostControlled =>
      for
        absolute <- io(workspace.toAbsolutePath)
        _ <- deny("Parent traversal is not permitted").when(
          (0 until absolute.getNameCount).exists(index => absolute.getName(index).toString == "..")
        )
        root <- canonicalDirectory(absolute)
        _    <- deny("Filesystem roots cannot own memory").when(Option(root.getParent).isEmpty)
        target = root.resolve("memory")
        _ <- ZIO
          .attemptBlocking(Files.createDirectory(target))
          .catchSome { case _: FileAlreadyExistsException => ZIO.succeed(target) }
          .mapError(storageError)
        directory <- canonicalDirectory(target)
        _ <- deny("Memory directory escapes its workspace").unless(directory.getParent == root)
      yield new MemoryFiles(root, directory)

  private def canonicalDirectory(path: Path): IO[AgentError, Path] =
    for
      _ <- deny("Sensitive memory location").when(PathSafety.isSensitivePath(path))
      attributes <- io(
        Files.readAttributes(path, classOf[BasicFileAttributes], LinkOption.NOFOLLOW_LINKS)
      )
      _         <- deny("Memory requires a non-symlink directory").unless(attributes.isDirectory)
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

  private def cleanup(path: Path): UIO[Unit] =
    io(Files.deleteIfExists(path)).unit.catchAll(error =>
      ZIO.logWarningCause(s"Could not remove temporary memory at $path", Cause.fail(error))
    )

  private def deny(message: String): IO[AgentError, Nothing] =
    ZIO.fail(AgentError.PermissionDenied("memory_repository", message))

  private def storageError(error: Throwable): AgentError.MemoryStorageError =
    AgentError.MemoryStorageError(Option(error.getMessage).getOrElse(error.toString))

  private def io[A](operation: => A): IO[AgentError, A] =
    ZIO.attemptBlocking(operation).mapError(storageError)
