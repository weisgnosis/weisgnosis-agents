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
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, LinkOption, Path, StandardOpenOption}

/** Persists a content-addressed result and returns a path usable by read_file. */
trait ToolResultStore:
  def persist(key: String, content: String): IO[AgentError, String]

object ToolResultStore:
  def local(workspace: Path): IO[AgentError, ToolResultStore] =
    for
      root <- io(workspace.toRealPath())
      _ <- ZIO
        .fail(AgentError.PermissionDenied("tool_result_store", "Sensitive workspace path"))
        .when(sensitive(root))
    yield new ToolResultStore:
      def persist(key: String, content: String): IO[AgentError, String] =
        if key.isEmpty || !key.forall(c => c.isLetterOrDigit || c == '-' || c == '_') then
          ZIO.fail(AgentError.PermissionDenied("tool_result_store", "Invalid storage key"))
        else
          for
            directory <- safeDirectory(root)
            target = directory.resolve(key + ".txt")
            exists <- io(Files.exists(target, LinkOption.NOFOLLOW_LINKS))
            path <-
              if exists then
                for
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
                yield canonical
              else
                io(
                  Files.writeString(
                    target,
                    content,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE
                  )
                )
            canonical <- io(path.toRealPath())
          yield canonical.toString

  private def safeDirectory(root: Path): IO[AgentError, Path] =
    val target = root.resolve("large_tool_results")
    for
      exists <- io(Files.exists(target, LinkOption.NOFOLLOW_LINKS))
      _      <- if exists then ZIO.unit else io(Files.createDirectory(target)).unit
      link   <- io(Files.isSymbolicLink(target))
      real   <- io(target.toRealPath())
      _ <- ZIO
        .fail(
          AgentError.PermissionDenied("tool_result_store", "Result directory escapes the workspace")
        )
        .when(link || real.getParent != root || sensitive(real))
    yield real

  private def sensitive(path: Path): Boolean = PathSafety.isSensitivePath(path)

  private def io[A](operation: => A): IO[AgentError, A] =
    ZIO
      .attemptBlocking(operation)
      .mapError(error =>
        AgentError.ToolExecutionError(
          "tool_result_store",
          Option(error.getMessage).getOrElse(error.toString)
        )
      )
