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
package com.weisgnosis.agents.harness.eviction

import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.tool.PathSafety
import zio.*
import zio.json.JsonCodec
import java.nio.charset.StandardCharsets
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{
  FileAlreadyExistsException,
  Files,
  LinkOption,
  NoSuchFileException,
  Path,
  StandardOpenOption
}

/** Original stored content and a path supplied by the trusted storage backend. */
final case class StoredToolResult(content: String, path: String) derives JsonCodec

/** Persists and resolves content-addressed results without accepting caller-supplied paths. */
trait ToolResultStore:
  def persist(key: String, content: String): IO[AgentError, String]
  def resolve(key: String): IO[AgentError, Option[StoredToolResult]]

/** HostControlled requires OS-enforced protection against untrusted mutation of every ancestor. */
enum WorkspaceTrust derives JsonCodec:
  case HostControlled, Untrusted

object ToolResultStore:
  /**
   * Portable storage for host-controlled directories. Canonical checks are defense in depth;
   * they are not a sandbox against concurrent hostile filesystem mutation.
   */
  def local(
      workspace: Path,
      trust: WorkspaceTrust,
      location: ResultStorageLocation = ResultStorageLocation.WorkspaceDefault
  ): IO[AgentError, ToolResultStore] =
    trust match
      case WorkspaceTrust.Untrusted =>
        ZIO.fail(
          AgentError.PermissionDenied(
            "tool_result_store",
            "Untrusted workspace storage requires a secure platform-specific backend"
          )
        )
      case WorkspaceTrust.HostControlled =>
        for plan <- location match
            case ResultStorageLocation.WorkspaceDefault =>
              for
                root <- io(workspace.toRealPath())
                _ <- ZIO
                  .fail(
                    AgentError.PermissionDenied("tool_result_store", "Sensitive workspace path")
                  )
                  .when(sensitive(root))
              yield DirectoryPlan.Workspace(root)
            case ResultStorageLocation.AbsoluteDirectory(value) =>
              for
                path <- ZIO
                  .attempt(Path.of(value))
                  .mapError(error =>
                    AgentError.InvalidConfiguration(s"Invalid storage directory: ${error.toString}")
                  )
                _ <- ZIO
                  .fail(AgentError.InvalidConfiguration("Storage directory must be absolute"))
                  .unless(path.isAbsolute)
                _ <- ZIO
                  .fail(
                    AgentError
                      .PermissionDenied("tool_result_store", "Parent traversal is not permitted")
                  )
                  .when(
                    (0 until path.getNameCount)
                      .exists(index => path.getName(index).toString == "..")
                  )
                directory <- checkedAbsoluteDirectory(path)
              yield DirectoryPlan.Absolute(directory)
        yield new ToolResultStore:
          def persist(key: String, content: String): IO[AgentError, String] =
            if !validKey(key) then
              ZIO.fail(AgentError.PermissionDenied("tool_result_store", "Invalid storage key"))
            else
              ZIO.scoped:
                for
                  directory <- directoryForWrite(plan)
                  target = directory.resolve(key + ".txt")
                  temporary <- ZIO.acquireRelease(
                    io(Files.createTempFile(directory, ".pending-", ".tmp"))
                  )(cleanupTemporary)
                  _ <- io(
                    Files.writeString(
                      temporary,
                      content,
                      StandardCharsets.UTF_8,
                      StandardOpenOption.WRITE
                    )
                  ).unit
                  // Publish only a completed file; the link operation elects one writer atomically.
                  _ <- ZIO
                    .attemptBlocking(Files.createLink(target, temporary))
                    .catchSome { case _: FileAlreadyExistsException => ZIO.succeed(target) }
                    .mapError(storageError)
                  canonical <- io(target.toRealPath())
                  regular   <- io(Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS))
                  _ <- ZIO
                    .fail(
                      AgentError
                        .PermissionDenied("tool_result_store", "Unsafe existing result file")
                    )
                    .unless(regular && canonical.getParent == directory)
                  saved <- io(Files.readString(canonical, StandardCharsets.UTF_8))
                  _ <- ZIO
                    .fail(
                      AgentError.ToolExecutionError(
                        "tool_result_store",
                        "Content-addressed result collision"
                      )
                    )
                    .unless(saved == content)
                yield canonical.toString

          def resolve(key: String): IO[AgentError, Option[StoredToolResult]] =
            if !validKey(key) then
              ZIO.fail(AgentError.PermissionDenied("tool_result_store", "Invalid storage key"))
            else
              directoryForRead(plan).flatMap:
                case None => ZIO.none
                case Some(directory) =>
                  val target = directory.resolve(key + ".txt")
                  for
                    attributes <- ZIO
                      .attemptBlocking(
                        Files.readAttributes(
                          target,
                          classOf[BasicFileAttributes],
                          LinkOption.NOFOLLOW_LINKS
                        )
                      )
                      .map(Some(_))
                      .catchSome { case _: NoSuchFileException => ZIO.none }
                      .mapError(storageError)
                    result <- attributes match
                      case None => ZIO.none
                      case Some(value) =>
                        for
                          _ <- ZIO
                            .fail(
                              AgentError.PermissionDenied(
                                "tool_result_store",
                                "Unsafe existing result file"
                              )
                            )
                            .unless(value.isRegularFile)
                          canonical <- io(target.toRealPath())
                          _ <- ZIO
                            .fail(
                              AgentError.PermissionDenied(
                                "tool_result_store",
                                "Result file escapes its directory"
                              )
                            )
                            .unless(canonical.getParent == directory)
                          content <- io(Files.readString(canonical, StandardCharsets.UTF_8))
                        yield Some(StoredToolResult(content, canonical.toString))
                  yield result

  private enum DirectoryPlan:
    case Workspace(root: Path)
    case Absolute(directory: Path)

  private def directoryForWrite(plan: DirectoryPlan): IO[AgentError, Path] = plan match
    case DirectoryPlan.Workspace(root)     => safeDirectory(root)
    case DirectoryPlan.Absolute(directory) => retainedAbsoluteDirectory(directory)

  private def directoryForRead(plan: DirectoryPlan): IO[AgentError, Option[Path]] = plan match
    case DirectoryPlan.Workspace(root)     => existingDirectory(root)
    case DirectoryPlan.Absolute(directory) => retainedAbsoluteDirectory(directory).map(Some(_))

  private def checkedAbsoluteDirectory(path: Path): IO[AgentError, Path] =
    for
      _ <- ZIO
        .fail(AgentError.PermissionDenied("tool_result_store", "Sensitive storage directory"))
        .when(sensitive(path))
      attributes <- io(
        Files.readAttributes(path, classOf[BasicFileAttributes], LinkOption.NOFOLLOW_LINKS)
      )
      _ <- ZIO
        .fail(
          AgentError.PermissionDenied(
            "tool_result_store",
            "Storage requires an existing non-symlink directory"
          )
        )
        .unless(attributes.isDirectory)
      canonical <- io(path.toRealPath())
      _ <- ZIO
        .fail(AgentError.PermissionDenied("tool_result_store", "Unsafe storage directory"))
        .when(sensitive(canonical) || Option(canonical.getParent).isEmpty)
    yield canonical

  private def retainedAbsoluteDirectory(directory: Path): IO[AgentError, Path] =
    checkedAbsoluteDirectory(directory).flatMap: actual =>
      ZIO
        .fail(
          AgentError.PermissionDenied("tool_result_store", "Storage directory identity changed")
        )
        .unless(actual == directory)
        .as(actual)

  private def validKey(key: String): Boolean =
    key.nonEmpty && key.forall(c => c.isLetterOrDigit || c == '-' || c == '_')

  private def existingDirectory(root: Path): IO[AgentError, Option[Path]] =
    val target = root.resolve("large_tool_results")
    for
      link <- io(Files.isSymbolicLink(target))
      _ <- ZIO
        .fail(
          AgentError.PermissionDenied("tool_result_store", "Result directory is a symbolic link")
        )
        .when(link)
      real <- ZIO
        .attemptBlocking(target.toRealPath())
        .map(Some(_))
        .catchSome { case _: NoSuchFileException => ZIO.none }
        .mapError(storageError)
      directory <- io(Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS))
      _ <- ZIO
        .fail(
          AgentError.PermissionDenied("tool_result_store", "Result directory escapes the workspace")
        )
        .when(real.exists(path => !directory || path.getParent != root || sensitive(path)))
    yield real

  /** Cleanup is best-effort: a retained temporary link must not invalidate a published result. */
  private[eviction] def cleanupTemporary(path: Path): UIO[Unit] =
    io(Files.deleteIfExists(path)).unit.catchAll(error =>
      ZIO.logWarningCause(s"Could not remove temporary tool result at $path", Cause.fail(error))
    )

  private def safeDirectory(root: Path): IO[AgentError, Path] =
    val target = root.resolve("large_tool_results")
    for
      _    <- io(Files.createDirectories(target))
      link <- io(Files.isSymbolicLink(target))
      real <- io(target.toRealPath())
      _ <- ZIO
        .fail(
          AgentError.PermissionDenied("tool_result_store", "Result directory escapes the workspace")
        )
        .when(link || real.getParent != root || sensitive(real))
    yield real

  private def sensitive(path: Path): Boolean = PathSafety.isSensitivePath(path)

  private def storageError(error: Throwable): AgentError =
    AgentError.ToolExecutionError(
      "tool_result_store",
      Option(error.getMessage).getOrElse(error.toString)
    )

  private def io[A](operation: => A): IO[AgentError, A] =
    ZIO.attemptBlocking(operation).mapError(storageError)
