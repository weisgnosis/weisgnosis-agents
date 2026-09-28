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

import java.io.IOException
import java.nio.file.{Files, Path}
import java.util.Locale
import scala.jdk.CollectionConverters.*

/** Path and command safety utilities enforcing invariant INV-10 (Sensitive Path Canonicalization)
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

  def expandTilde(path: String): String =
    if path.startsWith("~") then
      val home = Option(System.getProperty("user.home")).getOrElse("")
      home + path.substring(1)
    else path

  private def isDangerousAbsolute(
    path: Path,
    dangerousFiles: Set[String],
    dangerousDirectories: Set[String]
  ): Boolean =
    val fileName = Option(path.getFileName).map(_.toString.toLowerCase(Locale.ROOT)).getOrElse("")
    if dangerousFiles.contains(fileName) then true
    else
      val normalizedStr = path.toString.replace('\\', '/').toLowerCase(Locale.ROOT)
      val matchesRelativeFile = dangerousFiles.exists(df => df.contains("/") && normalizedStr.endsWith(df))
      if matchesRelativeFile then true
      else
        val segments = path.iterator().asScala.map(_.toString.toLowerCase(Locale.ROOT)).toSet
        segments.exists(dangerousDirectories.contains)

  /** Evaluates whether a file path targets sensitive credential, shell, or repository configuration.
    * Resolves canonical real paths via `toRealPath()` to prevent symlink traversal escapes.
    */
  def isDangerousPath(
    filePath: String,
    dangerousFiles: Set[String] = DefaultDangerousFiles,
    dangerousDirectories: Set[String] = DefaultDangerousDirectories
  ): Boolean =
    if filePath == null || filePath.trim.isEmpty then false
    else
      try
        val absolute = Path.of(expandTilde(filePath)).toAbsolutePath.normalize()
        if isDangerousAbsolute(absolute, dangerousFiles, dangerousDirectories) then true
        else
          try
            val resolved = if Files.exists(absolute) then absolute.toRealPath() else absolute
            if resolved != absolute && isDangerousAbsolute(resolved, dangerousFiles, dangerousDirectories) then true
            else false
          catch
            case _: IOException => false
      catch
        case _: Exception => false

  /** Evaluates whether a shell command contains destructive or privilege-escalating fragments.
    */
  def isDangerousCommand(command: String): Boolean =
    if command == null || command.trim.isEmpty then false
    else
      val lower = command.toLowerCase(Locale.ROOT)
      DangerousCommands.exists(lower.contains)
