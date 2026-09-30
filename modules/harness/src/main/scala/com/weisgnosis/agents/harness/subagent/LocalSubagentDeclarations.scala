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
package com.weisgnosis.agents.harness.subagent

import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.tool.PathSafety
import com.weisgnosis.agents.harness.eviction.WorkspaceTrust
import java.nio.ByteBuffer
import java.nio.charset.{CodingErrorAction, StandardCharsets}
import java.nio.file.{Files, LinkOption, Path}
import java.nio.file.attribute.BasicFileAttributes
import zio.*
import zio.json.*

/** Every directory child counts toward maxEntries; selected document bytes are bounded individually. */
final case class LocalSubagentDeclarationLimits(
    maxEntries: Int = 1024,
    maxDeclarations: Int = 64,
    maxDocumentBytes: Int = 512000,
    parser: SubagentDeclarationLimits = SubagentDeclarationLimits()
) derives JsonCodec

/**
 * Read-only, nonrecursive discovery from explicitly host-controlled ancestors. Exact lowercase .md
 * entries are sorted by filename; selected symlinks/nonregular entries fail the entire load.
 * Canonical screening is advisory, not an atomic sandbox. Bounded blocking operations finish before
 * resource closure; cancellation has no deadline or protection against a hostile/network filesystem.
 */
object LocalSubagentDeclarations:
  def load(
      directory: Path,
      trust: WorkspaceTrust,
      limits: LocalSubagentDeclarationLimits = LocalSubagentDeclarationLimits()
  ): IO[AgentError, Chunk[SubagentDeclaration]] = trust match
    case WorkspaceTrust.Untrusted => deny("Untrusted declaration directories are unsupported")
    case WorkspaceTrust.HostControlled =>
      for
        _ <- ZIO
          .fail(
            invalid("all limits must be positive and maxDocumentBytes must be below Int.MaxValue")
          )
          .when(
            limits.maxEntries <= 0 || limits.maxDeclarations <= 0 || limits.maxDocumentBytes <= 0 ||
              limits.maxDocumentBytes == Int.MaxValue || limits.parser.maxDocumentCharacters <= 0 ||
              limits.parser.maxHeaderCharacters <= 0
          )
        root         <- canonicalDirectory(directory)
        paths        <- discover(root, limits)
        declarations <- ZIO.foreach(paths)(path => read(root, path, limits))
      yield declarations

  private def canonicalDirectory(directory: Path): IO[AgentError, Path] =
    for
      absolute <- io("resolve directory", directory)(directory.toAbsolutePath)
      _ <- deny("Parent traversal is not permitted").when(
        (0 until absolute.getNameCount).exists(index => absolute.getName(index).toString == "..")
      )
      _ <- deny("Sensitive declaration directory").when(PathSafety.isSensitivePath(absolute))
      attributes <- io("inspect directory", absolute)(
        Files.readAttributes(absolute, classOf[BasicFileAttributes], LinkOption.NOFOLLOW_LINKS)
      )
      _ <- deny("Declaration directory must be a non-symlink directory").unless(
        attributes.isDirectory
      )
      canonical <- io("canonicalize directory", absolute)(absolute.toRealPath())
      _ <- deny("Sensitive canonical declaration directory").when(
        PathSafety.isSensitivePath(canonical)
      )
    yield canonical

  private def discover(
      root: Path,
      limits: LocalSubagentDeclarationLimits
  ): IO[AgentError, Chunk[Path]] =
    ZIO.scoped:
      for
        stream <- ZIO.acquireRelease(io("open directory", root)(Files.newDirectoryStream(root)))(
          stream => io("close directory", root)(stream.close()).orDie
        )
        iterator <- io("iterate directory", root)(stream.iterator())
        paths    <- scan(root, iterator, limits, 0, Chunk.empty)
      yield paths.sortBy(fileName)

  private def scan(
      root: Path,
      iterator: java.util.Iterator[Path],
      limits: LocalSubagentDeclarationLimits,
      count: Int,
      selected: Chunk[Path]
  ): IO[AgentError, Chunk[Path]] =
    io("scan directory", root)(if iterator.hasNext then Some(iterator.next()) else None).flatMap:
      case None => ZIO.succeed(selected)
      case Some(_) if count >= limits.maxEntries =>
        ZIO.fail(invalid("directory entry limit exceeded"))
      case Some(path) if fileName(path).endsWith(".md") =>
        if selected.size >= limits.maxDeclarations then
          ZIO.fail(invalid("declaration count limit exceeded"))
        else scan(root, iterator, limits, count + 1, selected :+ path)
      case Some(_) => scan(root, iterator, limits, count + 1, selected)

  private def read(
      root: Path,
      path: Path,
      limits: LocalSubagentDeclarationLimits
  ): IO[AgentError, SubagentDeclaration] =
    for
      _ <- deny(s"Sensitive declaration '${fileName(path).take(80)}'")
        .when(PathSafety.isSensitivePath(path))
      attributes <- io("inspect declaration", path)(
        Files.readAttributes(path, classOf[BasicFileAttributes], LinkOption.NOFOLLOW_LINKS)
      )
      _ <- deny(
        s"Selected declaration '${fileName(path).take(80)}' must be a non-symlink regular file"
      ).unless(attributes.isRegularFile)
      canonical <- io("canonicalize declaration", path)(path.toRealPath())
      _ <- deny(
        s"Declaration '${fileName(path).take(80)}' must remain directly inside its canonical directory"
      ).unless(
        Option(canonical.getParent).contains(root) && !PathSafety.isSensitivePath(canonical)
      )
      bytes <- ZIO.scoped:
        ZIO
          .acquireRelease(io("open declaration", path)(Files.newInputStream(canonical)))(stream =>
            io("close declaration", path)(stream.close()).orDie
          )
          .flatMap(stream =>
            io("read declaration", path)(stream.readNBytes(limits.maxDocumentBytes + 1))
          )
      _ <- ZIO
        .fail(invalid(s"document byte limit exceeded for '${fileName(path).take(80)}'"))
        .when(bytes.length > limits.maxDocumentBytes)
      text <- io("decode UTF-8 declaration", path)(
        StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes))
          .toString
      )
      declaration <- SubagentDeclarationParser
        .parse(fileName(path).dropRight(3), text, limits.parser)
        .mapError(error =>
          invalid(s"parse '${fileName(path).take(80)}': ${error.message.take(200)}")
        )
    yield declaration

  private def fileName(path: Path): String = Option(path.getFileName).fold("<root>")(_.toString)

  private def invalid(message: String): AgentError.InvalidConfiguration =
    AgentError.InvalidConfiguration("Local subagent declarations: " + message)

  private def deny(message: String): IO[AgentError, Nothing] =
    ZIO.fail(AgentError.PermissionDenied("subagent_declarations", message))

  private def io[A](operation: String, path: Path)(effect: => A): IO[AgentError, A] =
    ZIO
      .attemptBlocking(effect)
      .uninterruptible
      .mapError(error =>
        invalid(
          s"$operation '${fileName(path).take(80)}' (${error.getClass.getSimpleName.take(80)})"
        )
      )
