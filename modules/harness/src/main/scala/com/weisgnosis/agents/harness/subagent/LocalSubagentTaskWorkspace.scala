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
import com.weisgnosis.agents.harness.storage.*
import java.nio.file.{FileAlreadyExistsException, Files, LinkOption, NoSuchFileException, Path}
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant
import scala.annotation.unused
import zio.*

/** Explicit local layout; discovery grants no execution, recovery or writer ownership. */
object LocalSubagentTaskWorkspace:
  final class Name private (val value: String)
  object Name:
    def from(value: String): Either[AgentError.InvalidConfiguration, Name] =
      WorkspaceLease
        .validateName(value, 123)
        .left
        .map(AgentError.InvalidConfiguration(_))
        .flatMap: _ =>
          Either.cond(
            value.headOption.exists(character =>
              character >= 'a' && character <= 'z' || character >= '0' && character <= '9'
            ),
            new Name(value),
            AgentError.InvalidConfiguration(
              "Workspace identities must begin with a lowercase letter or digit"
            )
          )

  final class RecentWindow private (val duration: Duration)
  object RecentWindow:
    def make(duration: Duration): Either[AgentError.InvalidConfiguration, RecentWindow] =
      Either.cond(
        !duration.isNegative && duration.compareTo(Duration.Infinity) < 0,
        new RecentWindow(duration),
        AgentError.InvalidConfiguration("Recent window must be finite and nonnegative")
      )

  /** File limits bound envelopes; each session retains SubagentTerminalStore.Limits.default. */
  final class Limits private (
      val maxEntries: Int,
      val maxFiles: Int,
      val maxBytes: Long,
      val maxRecords: Int,
      val file: LocalVersionedJsonStore.Limits
  )
  object Limits:
    val default: Limits =
      new Limits(2048, 256, 16L * 1024L * 1024L, 4096, LocalVersionedJsonStore.Limits.default)
    def make(
        maxEntries: Int,
        maxFiles: Int,
        maxBytes: Long,
        maxRecords: Int,
        file: LocalVersionedJsonStore.Limits = LocalVersionedJsonStore.Limits.default
    ): Either[AgentError.InvalidConfiguration, Limits] =
      Either.cond(
        maxEntries > 0 && maxFiles > 0 && maxFiles <= maxEntries && maxBytes > 0L && maxRecords > 0,
        new Limits(maxEntries, maxFiles, maxBytes, maxRecords, file),
        AgentError.InvalidConfiguration(
          "Workspace entry, file, byte and record limits must be positive; files cannot exceed entries"
        )
      )

  final case class Snapshot(
      agent: String,
      session: String,
      address: StorageAddress,
      revision: StorageRevision,
      observedModifiedAt: Instant,
      records: Chunk[SubagentTaskRecord]
  )

  /** Only this explicit entrypoint creates the owned agents/<agent>/tasks layout. */
  def openSession(
      root: Path,
      agent: Name,
      session: Name,
      trust: WorkspaceTrust,
      limits: Limits = Limits.default
  ): ZIO[Scope, AgentError, SubagentTerminalStore] =
    for
      directory <- layout(root, agent, trust, Access.Create).someOrFail(
        AgentError.StorageError("Workspace layout was not created")
      )
      address <- addressOf(agent, session)
      backend <- LocalVersionedJsonStore.open(
        directory.resolve(s"${session.value}.json"),
        address,
        trust,
        limits.file
      )
    yield SubagentTerminalStore.make(backend, address)

  /** One Clock sample and observational per-file mtimes; no global snapshot or implicit registration. */
  def listRecent(
      root: Path,
      agent: Name,
      recentWindow: RecentWindow,
      trust: WorkspaceTrust,
      limits: Limits = Limits.default
  ): IO[AgentError, Chunk[Snapshot]] =
    LocalVersionedJsonStore.boundedIO:
      for
        at        <- Clock.instant
        cutoff    <- io(at.minus(recentWindow.duration))
        directory <- layout(root, agent, trust, Access.ReadOnly)
        result <- directory match
          case None => ZIO.succeed(Chunk.empty)
          case Some(path) =>
            for
              files <- sessionFiles(path, limits)
              snapshots <- readFiles(
                files.toList,
                agent,
                trust,
                limits,
                cutoff,
                Chunk.empty,
                0L,
                0L
              )
            yield snapshots
      yield result

  private enum Access:
    case ReadOnly, Create

  private def addressOf(agent: Name, session: Name): IO[AgentError, StorageAddress] =
    ZIO.fromEither(StorageAddress.from(Chunk(agent.value, session.value), "tasks"))

  private def attributes(path: Path): IO[AgentError, Option[BasicFileAttributes]] =
    ZIO
      .attemptBlocking(
        Files.readAttributes(path, classOf[BasicFileAttributes], LinkOption.NOFOLLOW_LINKS)
      )
      .map(Some(_))
      .catchSome:
        case _: NoSuchFileException => ZIO.none
      .mapError(error =>
        AgentError.StorageError(Option(error.getMessage).getOrElse(error.toString))
      )

  private def checkedDirectory(path: Path): IO[AgentError, Path] =
    for
      _     <- deny("Sensitive workspace directory").when(PathSafety.isSensitivePath(path))
      found <- attributes(path).someOrFail(AgentError.StorageError("Workspace directory is absent"))
      _     <- deny("Workspace directory must be a non-symlink directory").unless(found.isDirectory)
      canonical <- io(path.toRealPath())
      _ <- deny("Sensitive canonical workspace directory").when(
        PathSafety.isSensitivePath(canonical)
      )
    yield canonical

  private def child(parent: Path, name: String, access: Access): IO[AgentError, Option[Path]] =
    val path = parent.resolve(name)
    for
      _ <- access match
        case Access.ReadOnly => ZIO.unit
        case Access.Create =>
          ZIO
            .attemptBlocking(Files.createDirectory(path))
            .unit
            .catchSome:
              case _: FileAlreadyExistsException => ZIO.unit
            .mapError(error =>
              AgentError.StorageError(Option(error.getMessage).getOrElse(error.toString))
            )
      found <- attributes(path)
      result <- found match
        case None => ZIO.none
        case Some(_) =>
          checkedDirectory(path).flatMap: canonical =>
            deny("Workspace directory escapes its parent")
              .unless(canonical == path)
              .as(Some(canonical))
    yield result

  private def layout(
      root: Path,
      agent: Name,
      trust: WorkspaceTrust,
      access: Access
  ): IO[AgentError, Option[Path]] =
    trust match
      case WorkspaceTrust.Untrusted =>
        deny("Local task workspaces require explicit host-controlled trust")
      case WorkspaceTrust.HostControlled =>
        for
          absolute <- io(root.toAbsolutePath)
          _ <- deny("Parent traversal is not permitted").when(
            (0 until absolute.getNameCount).exists(index =>
              absolute.getName(index).toString == ".."
            )
          )
          canonical <- checkedDirectory(absolute)
          tasks <- ZIO.foldLeft(List("agents", agent.value, "tasks"))(Option(canonical)):
            case (None, _)            => ZIO.none
            case (Some(parent), name) => child(parent, name, access)
        yield tasks

  private def sessionFiles(directory: Path, limits: Limits): IO[AgentError, Chunk[(Name, Path)]] =
    LocalVersionedJsonStore.managed(io(Files.newDirectoryStream(directory)))(stream =>
      io(stream.close())
    ): stream =>
      io(stream.iterator()).flatMap: cursor =>
        def consume(count: Int, files: Chunk[(Name, Path)]): IO[AgentError, Chunk[(Name, Path)]] =
          io(cursor.hasNext).flatMap:
            case false                              => ZIO.succeed(files.sortBy(_._1.value))
            case true if count >= limits.maxEntries => exceed("directory entry")
            case true =>
              for
                path <- io(cursor.next())
                name = path.getFileName.toString
                selected <-
                  if !name.endsWith(".json") then ZIO.succeed(files)
                  else if files.size >= limits.maxFiles then exceed("session file")
                  else
                    ZIO
                      .fromEither(Name.from(name.stripSuffix(".json")))
                      .map(session => files :+ (session -> path))
                result <- consume(count + 1, selected)
              yield result
        consume(0, Chunk.empty)

  private def readFiles(
      files: List[(Name, Path)],
      agent: Name,
      trust: WorkspaceTrust,
      limits: Limits,
      cutoff: Instant,
      collected: Chunk[Snapshot],
      bytes: Long,
      records: Long
  ): IO[AgentError, Chunk[Snapshot]] = files match
    case Nil => ZIO.succeed(collected)
    case (session, path) :: tail =>
      for
        address <- addressOf(agent, session)
        remaining <- ZIO.fromEither(
          LocalVersionedJsonStore.Limits.make(
            math.max(1L, math.min(limits.file.maxBytes.toLong, limits.maxBytes - bytes)).toInt
          )
        )
        observed <- LocalVersionedJsonStore.readSnapshot(
          path,
          address,
          trust,
          remaining,
          Some(cutoff)
        )
        result <- observed match
          case LocalVersionedJsonStore.SnapshotRead.Missing =>
            ZIO.fail(AgentError.StorageError("A selected workspace session file disappeared"))
          case LocalVersionedJsonStore.SnapshotRead.Older(_) =>
            readFiles(tail, agent, trust, limits, cutoff, collected, bytes, records)
          case LocalVersionedJsonStore.SnapshotRead.Found(snapshot) =>
            for
              decoded <- decode(address, snapshot.record)
              nextBytes   = bytes + snapshot.encodedBytes.toLong
              nextRecords = records + decoded.size.toLong
              _ <- exceed("aggregate byte").when(nextBytes > limits.maxBytes)
              _ <- exceed("aggregate record").when(nextRecords > limits.maxRecords.toLong)
              item = Snapshot(
                agent.value,
                session.value,
                address,
                snapshot.record.revision,
                snapshot.observedModifiedAt,
                decoded
              )
              value <- readFiles(
                tail,
                agent,
                trust,
                limits,
                cutoff,
                collected :+ item,
                nextBytes,
                nextRecords
              )
            yield value
      yield result

  private def decode(
      bound: StorageAddress,
      snapshot: VersionedJson
  ): IO[AgentError, Chunk[SubagentTaskRecord]] =
    val immutable = new VersionedJsonStore:
      def read(address: StorageAddress): IO[AgentError, Option[VersionedJson]] =
        if address == bound then ZIO.some(snapshot)
        else
          ZIO.fail(
            AgentError.StorageError("Read-only task snapshot is bound to a different partition")
          )
      def compareAndSet(
          @unused address: StorageAddress,
          @unused expected: ExpectedRevision,
          @unused value: zio.json.ast.Json
      ): IO[AgentError, ConditionalWrite] =
        ZIO.fail(AgentError.InvalidConfiguration("A read-only task snapshot cannot be mutated"))
    SubagentTerminalStore.make(immutable, bound).taskRecords

  private def exceed(kind: String): IO[AgentError, Nothing] =
    ZIO.fail(AgentError.StorageError(s"Local task workspace exceeds its $kind limit"))
  private def deny(message: String): IO[AgentError, Nothing] =
    ZIO.fail(AgentError.PermissionDenied("local_task_workspace", message))
  private def io[A](operation: => A): IO[AgentError, A] =
    ZIO
      .attemptBlocking(operation)
      .mapError(error =>
        AgentError.StorageError(Option(error.getMessage).getOrElse(error.toString))
      )
