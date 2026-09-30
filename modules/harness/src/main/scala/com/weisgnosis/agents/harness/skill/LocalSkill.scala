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
import com.weisgnosis.agents.core.skill.SkillDefinition
import com.weisgnosis.agents.core.tool.PathSafety
import com.weisgnosis.agents.harness.eviction.WorkspaceTrust
import java.nio.ByteBuffer
import java.nio.charset.{CodingErrorAction, StandardCharsets}
import java.nio.file.{Files, LinkOption, Path}
import java.nio.file.attribute.BasicFileAttributes
import zio.*
import zio.json.JsonCodec

/** Selected resources have individual byte bounds; count times byte limit bounds retained data. */
final case class LocalSkillLimits(
    maxResources: Int = 64,
    maxDocumentBytes: Int = 512000,
    maxResourceBytes: Int = 256000,
    parser: SkillMarkdownLimits = SkillMarkdownLimits()
) derives JsonCodec

/**
 * Reads one selected skill and explicitly named resources from host-controlled ancestors.
 * Checks are advisory against filesystem races, not an atomic sandbox. Bounded blocking calls
 * finish before their resource closes; cancellation has no deadline or hostile-filesystem guarantee.
 */
object LocalSkill:
  def load(
      directory: Path,
      trust: WorkspaceTrust,
      resources: Set[String] = Set.empty,
      limits: LocalSkillLimits = LocalSkillLimits()
  ): IO[AgentError, SkillDefinition] = trust match
    case WorkspaceTrust.Untrusted => deny("Untrusted skill directories are unsupported")
    case WorkspaceTrust.HostControlled =>
      for
        _        <- ZIO.fromEither(validate(resources, limits))
        root     <- canonicalDirectory(directory)
        document <- read(root, "SKILL.md", limits.maxDocumentBytes)
        skill <- SkillMarkdownParser
          .parse(document, limits.parser)
          .mapError(error => invalid(s"parse SKILL.md: ${error.message.take(200)}"))
        loaded <- ZIO.foreach(resources.toList.sorted)(name =>
          read(root, name, limits.maxResourceBytes).map(name -> _)
        )
      yield skill.copy(resources = loaded.toMap)

  private def validate(
      resources: Set[String],
      limits: LocalSkillLimits
  ): Either[AgentError, Unit] =
    if limits.maxResources <= 0 || limits.maxDocumentBytes <= 0 ||
      limits.maxResourceBytes <= 0 || limits.maxDocumentBytes == Int.MaxValue ||
      limits.maxResourceBytes == Int.MaxValue || limits.parser.maxDocumentCharacters <= 0 ||
      limits.parser.maxHeaderCharacters <= 0 || limits.parser.maxMetadataDepth <= 0
    then Left(invalid("limits must be positive and byte limits must be below Int.MaxValue"))
    else if resources.size > limits.maxResources then Left(invalid("resource count limit exceeded"))
    else if resources.exists(name => name == "SKILL.md" || !validResource(name)) then
      Left(invalid("selected resources must be portable relative identifiers other than SKILL.md"))
    else Right(())

  private def validResource(name: String): Boolean =
    !name.isBlank && !name.exists(character =>
      character == '\\' || character == ':' || Character.isISOControl(character)
    ) && name
      .split("/", -1)
      .forall(segment => !segment.isBlank && segment != "." && segment != "..")

  private def canonicalDirectory(directory: Path): IO[AgentError, Path] =
    for
      _ <- deny("Parent traversal is not permitted").when(
        (0 until directory.getNameCount).exists(index => directory.getName(index).toString == "..")
      )
      absolute <- io("resolve directory", directory)(directory.toAbsolutePath)
      _        <- deny("Sensitive skill directory").when(PathSafety.isSensitivePath(absolute))
      metadata <- attributes(absolute)
      _ <- deny("Skill directory must be a non-symlink directory").unless(metadata.isDirectory)
      canonical <- io("canonicalize directory", absolute)(absolute.toRealPath())
      _ <- deny("Sensitive canonical skill directory").when(PathSafety.isSensitivePath(canonical))
    yield canonical

  private def selectedFile(root: Path, parent: Path, segments: List[String]): IO[AgentError, Path] =
    segments match
      case Nil => ZIO.fail(invalid("selected resource path is empty"))
      case name :: remaining =>
        for
          path     <- io("resolve selected path", parent)(parent.resolve(name))
          _        <- deny("Sensitive selected skill path").when(PathSafety.isSensitivePath(path))
          metadata <- attributes(path)
          _ <- remaining match
            case Nil =>
              deny("Selected skill file must be a non-symlink regular file").unless(
                metadata.isRegularFile
              )
            case _ =>
              deny("Selected skill ancestor must be a non-symlink directory").unless(
                metadata.isDirectory
              )
          canonical <- io("canonicalize selected path", path)(path.toRealPath())
          _ <- deny("Selected skill path escapes its directory or is sensitive").unless(
            canonical != root && canonical.startsWith(root) && !PathSafety.isSensitivePath(
              canonical
            )
          )
          selected <- remaining match
            case Nil => ZIO.succeed(canonical)
            case _   => selectedFile(root, canonical, remaining)
        yield selected

  private def attributes(path: Path): IO[AgentError, BasicFileAttributes] =
    io("inspect path", path)(
      Files.readAttributes(path, classOf[BasicFileAttributes], LinkOption.NOFOLLOW_LINKS)
    )

  private def read(root: Path, name: String, maxBytes: Int): IO[AgentError, String] =
    for
      path <- selectedFile(root, root, name.split("/", -1).toList)
      bytes <- ZIO.scoped:
        ZIO
          .acquireRelease(io("open file", path)(Files.newInputStream(path)))(stream =>
            io("close file", path)(stream.close()).orDie
          )
          .flatMap(stream => io("read file", path)(stream.readNBytes(maxBytes + 1)))
      _ <- ZIO
        .fail(invalid(s"byte limit exceeded for '${name.take(80)}'"))
        .when(bytes.length > maxBytes)
      text <- io("decode UTF-8", path)(
        StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes))
          .toString
      )
    yield text

  private def invalid(message: String): AgentError.InvalidConfiguration =
    AgentError.InvalidConfiguration("Local skill: " + message)

  private def deny(message: String): IO[AgentError, Nothing] =
    ZIO.fail(AgentError.PermissionDenied("local_skill", message))

  private def io[A](operation: String, path: Path)(effect: => A): IO[AgentError, A] =
    ZIO.uninterruptible:
      ZIO
        .attemptBlocking(effect)
        .mapError(error =>
          val name = Option(path.getFileName).fold("<root>")(_.toString.take(80))
          invalid(s"$operation '$name' (${error.getClass.getSimpleName.take(80)})")
        )
