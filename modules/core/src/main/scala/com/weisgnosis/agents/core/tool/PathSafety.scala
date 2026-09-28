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
package com.weisgnosis.agents.core.tool

import java.nio.file.{Files, LinkOption, Path}
import com.weisgnosis.agents.core.error.AgentError
import zio.*
import java.util.Locale

/**
 * Path and command safety utilities enforcing invariant INV-10 (Sensitive Path Canonicalization)
 * and matching AgentScope Java ToolDangerousPathConstants.
 */
object PathSafety:

  val DefaultDangerousFiles: Set[String] = Set(
    ".gitconfig",
    ".gitmodules",
    ".bashrc",
    ".bash_profile",
    ".zshrc",
    ".zprofile",
    ".profile",
    ".ssh/config",
    ".ssh/authorized_keys",
    ".netrc",
    ".npmrc",
    ".pypirc",
    ".env",
    ".envrc",
    ".env.local",
    ".env.development",
    ".env.development.local",
    ".env.test",
    ".env.test.local",
    ".env.staging",
    ".env.production",
    ".env.production.local"
  ).map(_.toLowerCase(Locale.ROOT))

  val DefaultDangerousDirectories: Set[String] = Set(
    ".git",
    ".vscode",
    ".idea",
    ".ssh"
  ).map(_.toLowerCase(Locale.ROOT))

  val DangerousCommands: Set[String] = Set(
    "rm -rf",
    "sudo rm",
    "dd",
    "mkfs",
    "fdisk",
    "format",
    "chmod 777",
    "chmod -R 777",
    "chown -R",
    "kill -9",
    "> /dev/"
  )

  def expandTilde(path: String): IO[AgentError, String] =
    if path == "~" || path.startsWith("~/") || path.startsWith("~\\") then
      zio.System
        .property("user.home")
        .mapError(error => AgentError.PermissionDenied("path", error.toString))
        .flatMap(home =>
          ZIO
            .fromOption(home)
            .orElseFail(AgentError.PermissionDenied("path", "Home directory is unavailable"))
        )
        .map(home => home + path.drop(1))
    else ZIO.succeed(path)

  /** Canonicalizes the nearest existing ancestor, including links above new files. */
  def canonicalPath(path: Path): IO[AgentError, Path] =
    ZIO.suspendSucceed {
      if containsParentTraversal(path) then
        ZIO.fail(AgentError.PermissionDenied("path", "Parent traversal is not permitted"))
      else
        io(Files.exists(path, LinkOption.NOFOLLOW_LINKS)).flatMap { exists =>
          if exists then io(path.toRealPath())
          else
            (Option(path.getParent), Option(path.getFileName)) match
              case (Some(parent), Some(name)) => canonicalPath(parent).map(_.resolve(name))
              case _ =>
                ZIO.fail(
                  AgentError.PermissionDenied("path", "Cannot resolve a filesystem ancestor")
                )
        }
    }

  private def containsParentTraversal(path: Path): Boolean =
    (0 until path.getNameCount).exists(index => path.getName(index).toString == "..")

  /** Pure lexical screening, reusable after canonicalization. */
  def isSensitivePath(
      path: Path,
      dangerousFiles: Set[String] = DefaultDangerousFiles,
      dangerousDirectories: Set[String] = DefaultDangerousDirectories
  ): Boolean =
    val normalized = path.toString.replace('\\', '/').toLowerCase(Locale.ROOT)
    val segments   = normalized.split('/').toList
    segments.exists(segment =>
      dangerousDirectories.contains(segment) || dangerousFiles.contains(segment) || segment
        .startsWith(".env.")
    ) ||
    dangerousFiles.exists(file => file.contains("/") && normalized.endsWith("/" + file))

  /** All filesystem reads and home-directory resolution are suspended and failures are explicit. */
  def isDangerousPath(
      filePath: String,
      dangerousFiles: Set[String] = DefaultDangerousFiles,
      dangerousDirectories: Set[String] = DefaultDangerousDirectories
  ): IO[AgentError, Boolean] =
    if filePath.trim.isEmpty then ZIO.succeed(false)
    else
      for
        expanded <- expandTilde(filePath)
        absolute <- io(Path.of(expanded).toAbsolutePath)
        result <-
          if containsParentTraversal(absolute) ||
            isSensitivePath(absolute, dangerousFiles, dangerousDirectories)
          then ZIO.succeed(true)
          else canonicalPath(absolute).map(isSensitivePath(_, dangerousFiles, dangerousDirectories))
      yield result

  def isDangerousCommand(command: String): Boolean =
    val lower = command.toLowerCase(Locale.ROOT)
    DangerousCommands.exists(lower.contains)

  private def io[A](operation: => A): IO[AgentError, A] =
    ZIO
      .attemptBlocking(operation)
      .mapError(error =>
        AgentError.PermissionDenied("path", Option(error.getMessage).getOrElse(error.toString))
      )
