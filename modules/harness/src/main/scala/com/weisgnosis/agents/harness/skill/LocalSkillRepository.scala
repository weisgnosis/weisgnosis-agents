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
package com.weisgnosis.agents.harness.skill

import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.skill.SkillCatalog
import com.weisgnosis.agents.harness.eviction.WorkspaceTrust
import java.nio.file.{Files, LinkOption, NoSuchFileException, Path}
import java.nio.file.attribute.BasicFileAttributes
import zio.*
import zio.json.JsonCodec

/** All immediate entries count; selected skill documents retain their individual local bounds. */
final case class LocalSkillRepositoryLimits(
    maxEntries: Int = 4096,
    maxSkills: Int = 256,
    local: LocalSkillLimits = LocalSkillLimits()
) derives JsonCodec

/**
 * Cold, read-only discovery of immediate skill directories under one host-controlled root.
 * Declared IDs remain unchanged and duplicates fail the entire snapshot. Resources are not loaded.
 * Canonical checks are advisory; joined blocking operations have no cancellation deadline.
 */
object LocalSkillRepository:
  def load(
      directory: Path,
      trust: WorkspaceTrust,
      limits: LocalSkillRepositoryLimits = LocalSkillRepositoryLimits()
  ): IO[AgentError, SkillCatalog] = trust match
    case WorkspaceTrust.Untrusted => deny("Untrusted repository directories are unsupported")
    case WorkspaceTrust.HostControlled =>
      for
        _ <- ZIO
          .fail(invalid("entry and skill limits must be positive"))
          .when(limits.maxEntries <= 0 || limits.maxSkills <= 0)
        _       <- ZIO.fromEither(LocalSkill.validate(Set.empty, limits.local))
        root    <- LocalSkill.canonicalDirectory(directory)
        entries <- discover(root, limits.maxEntries)
        selected <- ZIO.foldLeft(entries)(Chunk.empty[Path])((selected, entry) =>
          candidate(root, entry).flatMap:
            case None => ZIO.succeed(selected)
            case Some(_) if selected.size >= limits.maxSkills =>
              ZIO.fail(invalid("skill count limit exceeded"))
            case Some(path) => ZIO.succeed(selected :+ path)
        )
        definitions <- ZIO.foreach(selected)(path =>
          LocalSkill.load(path, trust, limits = limits.local)
        )
        catalog <- ZIO.fromEither(SkillCatalog.make(definitions))
      yield catalog

  private def discover(root: Path, maxEntries: Int): IO[AgentError, Chunk[Path]] =
    ZIO.scoped:
      for
        stream <- ZIO.acquireRelease(io("open directory", root)(Files.newDirectoryStream(root)))(
          stream => io("close directory", root)(stream.close()).orDie
        )
        iterator <- io("iterate directory", root)(stream.iterator())
        entries  <- scan(root, iterator, maxEntries, Chunk.empty)
      yield entries.sortBy(fileName)

  private def scan(
      root: Path,
      iterator: java.util.Iterator[Path],
      maximum: Int,
      entries: Chunk[Path]
  ): IO[AgentError, Chunk[Path]] =
    io("scan directory", root)(if iterator.hasNext then Some(iterator.next()) else None).flatMap:
      case None                               => ZIO.succeed(entries)
      case Some(_) if entries.size >= maximum => ZIO.fail(invalid("directory entry limit exceeded"))
      case Some(path)                         => scan(root, iterator, maximum, entries :+ path)

  private def candidate(root: Path, entry: Path): IO[AgentError, Option[Path]] =
    for
      attributes <- io("inspect entry", entry)(
        Files.readAttributes(entry, classOf[BasicFileAttributes], LinkOption.NOFOLLOW_LINKS)
      )
      _ <- deny("Repository entries must not be symbolic links").when(attributes.isSymbolicLink)
      selected <-
        if !attributes.isDirectory then ZIO.none
        else
          for
            canonical <- LocalSkill.canonicalDirectory(entry)
            _ <- deny("Skill directory must remain directly inside its repository").unless(
              canonical == entry && Option(canonical.getParent).contains(root)
            )
            document <- io("resolve skill document", canonical)(canonical.resolve("SKILL.md"))
            metadata <- documentAttributes(document)
            result <- metadata match
              case None                               => ZIO.none
              case Some(value) if value.isRegularFile => ZIO.some(canonical)
              case Some(_) => deny("Selected SKILL.md must be a non-symlink regular file")
          yield result
    yield selected

  private def documentAttributes(path: Path): IO[AgentError, Option[BasicFileAttributes]] =
    ZIO.uninterruptible:
      ZIO
        .attemptBlocking[Option[BasicFileAttributes]](
          Some(Files.readAttributes(path, classOf[BasicFileAttributes], LinkOption.NOFOLLOW_LINKS))
        )
        .catchSome { case _: NoSuchFileException => ZIO.none }
        .mapError(error => failure("inspect skill document", path, error))

  private def fileName(path: Path): String = Option(path.getFileName).fold("<root>")(_.toString)

  private def invalid(message: String): AgentError.InvalidConfiguration =
    AgentError.InvalidConfiguration("Local skill repository: " + message)

  private def deny(message: String): IO[AgentError, Nothing] =
    ZIO.fail(AgentError.PermissionDenied("local_skill_repository", message))

  private def failure(operation: String, path: Path, error: Throwable): AgentError =
    invalid(s"$operation '${fileName(path).take(80)}' (${error.getClass.getSimpleName.take(80)})")

  private def io[A](operation: String, path: Path)(effect: => A): IO[AgentError, A] =
    ZIO.uninterruptible:
      ZIO.attemptBlocking(effect).mapError(error => failure(operation, path, error))
