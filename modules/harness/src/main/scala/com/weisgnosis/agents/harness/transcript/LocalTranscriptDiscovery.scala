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
import java.nio.file.{Files, LinkOption, NoSuchFileException, Path}
import java.nio.file.attribute.BasicFileAttributes
import zio.*
import zio.json.JsonCodec

final case class LocalTranscriptDiscoveryLimits(maxEntries: Int = 4096, maxTargets: Int = 256)
    derives JsonCodec

/**
 * Cold discovery of direct transcript directories. Selection grants retention capability, not
 * proof that a session is inactive. Hosts own logical retirement and trusted filesystem ancestors;
 * canonical checks are advisory, and joined blocking operations have no cancellation deadline.
 */
object LocalTranscriptDiscovery:
  def discover(
      directory: Path,
      trust: WorkspaceTrust,
      limits: LocalTranscriptDiscoveryLimits = LocalTranscriptDiscoveryLimits()
  ): IO[AgentError, Chunk[TranscriptRetentionTarget]] = trust match
    case WorkspaceTrust.Untrusted => deny("Untrusted transcript discovery is unsupported")
    case WorkspaceTrust.HostControlled =>
      for
        _ <- ZIO
          .fail(invalid("entry and target limits must be positive"))
          .when(
            limits.maxEntries <= 0 || limits.maxTargets <= 0
          )
        root    <- TranscriptFiles.open(directory, trust).map(_.directory)
        entries <- enumerate(root, limits.maxEntries)
        targets <- ZIO.foldLeft(entries)(Chunk.empty[TranscriptRetentionTarget]):
          (targets, entry) =>
            candidate(root, entry, trust).flatMap:
              case None => ZIO.succeed(targets)
              case Some(_) if targets.size >= limits.maxTargets =>
                ZIO.fail(invalid("target count limit exceeded"))
              case Some(target) => ZIO.succeed(targets :+ target)
      yield targets

  private def enumerate(root: Path, maximum: Int): IO[AgentError, Chunk[Path]] = ZIO.scoped:
    for
      stream <- ZIO.acquireRelease(io(Files.newDirectoryStream(root)))(stream =>
        io(stream.close()).orDie
      )
      iterator <- io(stream.iterator())
      entries  <- scan(iterator, maximum, Chunk.empty)
      sorted   <- io(entries.sortBy(path => path.getFileName.toString))
    yield sorted

  private def scan(
      iterator: java.util.Iterator[Path],
      maximum: Int,
      entries: Chunk[Path]
  ): IO[AgentError, Chunk[Path]] =
    io(if iterator.hasNext then Some(iterator.next()) else None).flatMap:
      case None                               => ZIO.succeed(entries)
      case Some(_) if entries.size >= maximum => ZIO.fail(invalid("directory entry limit exceeded"))
      case Some(path)                         => scan(iterator, maximum, entries :+ path)

  private def candidate(
      root: Path,
      entry: Path,
      trust: WorkspaceTrust
  ): IO[AgentError, Option[TranscriptRetentionTarget]] =
    for
      _ <- deny("Sensitive transcript discovery entry").when(PathSafety.isSensitivePath(entry))
      attributes <- io(
        Files.readAttributes(entry, classOf[BasicFileAttributes], LinkOption.NOFOLLOW_LINKS)
      )
      _ <- deny("Transcript discovery entries must be regular files or non-symlink directories")
        .unless(
          !attributes.isSymbolicLink && (attributes.isDirectory || attributes.isRegularFile)
        )
      selected <-
        if !attributes.isDirectory then ZIO.none
        else
          for
            files <- TranscriptFiles.open(entry, trust)
            canonical = files.directory
            _ <- deny("Transcript directory must retain its identity directly inside the root")
              .unless(
                canonical == entry && Option(canonical.getParent).contains(root)
              )
            payload  <- io(canonical.resolve("transcript.jsonl"))
            metadata <- payloadAttributes(payload)
            target <- metadata match
              case None => ZIO.none
              case Some(value) =>
                for
                  _ <- deny("Transcript payload must be a non-symlink regular file").unless(
                    !value.isSymbolicLink && value.isRegularFile
                  )
                  actual <- io(payload.toRealPath())
                  _ <- deny("Transcript payload must retain its identity inside its directory")
                    .unless(
                      actual == payload && Option(actual.getParent).contains(canonical) &&
                        !PathSafety.isSensitivePath(actual)
                    )
                  name <- io(canonical.getFileName.toString)
                  id   <- ZIO.fromEither(TranscriptTargetId.fromString(name))
                yield Some(TranscriptRetentionTarget.local(id, canonical, trust))
          yield target
    yield selected

  private def payloadAttributes(path: Path): IO[AgentError, Option[BasicFileAttributes]] =
    ZIO.uninterruptible:
      ZIO
        .attemptBlocking[Option[BasicFileAttributes]](
          Some(Files.readAttributes(path, classOf[BasicFileAttributes], LinkOption.NOFOLLOW_LINKS))
        )
        .catchSome { case _: NoSuchFileException => ZIO.none }
        .mapError(storageError)

  private def invalid(message: String): AgentError.InvalidConfiguration =
    AgentError.InvalidConfiguration(s"Invalid transcript discovery: $message")

  private def deny(message: String): IO[AgentError, Nothing] =
    ZIO.fail(AgentError.PermissionDenied("transcript_discovery", message))

  private def storageError(error: Throwable): AgentError.MemoryStorageError =
    AgentError.MemoryStorageError(Option(error.getMessage).getOrElse(error.toString))

  private def io[A](operation: => A): IO[AgentError, A] =
    ZIO.uninterruptible(ZIO.attemptBlocking(operation).mapError(storageError))
