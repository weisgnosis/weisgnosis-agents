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
import com.weisgnosis.agents.harness.eviction.WorkspaceTrust
import com.weisgnosis.agents.harness.memory.MemoryTestSupport.temporaryDirectory
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import zio.*
import zio.test.*

object LocalSubagentDeclarationsSpec extends ZIOSpecDefault:
  private val content: String = "\uFEFF---\ndescription: Review\n---\nBody λ\uFEFF\n"

  private def write(path: Path, text: String): Task[Unit] =
    ZIO.attemptBlocking(Files.writeString(path, text, StandardCharsets.UTF_8)).unit

  private def permission(exit: Exit[AgentError, Chunk[SubagentDeclaration]]): Boolean =
    exit.causeOption.exists(_.failureOption.exists(_.isInstanceOf[AgentError.PermissionDenied]))

  def spec: Spec[TestEnvironment & Scope, Any] = suite("LocalSubagentDeclarationsSpec")(
    test(
      "suspended discovery loads sorted direct lowercase Markdown declarations without installing capabilities"
    ) {
      for
        root <- temporaryDirectory
        directory = root.resolve("agents")
        cold      = LocalSubagentDeclarations.load(directory, WorkspaceTrust.HostControlled)
        _ <- ZIO.attemptBlocking(Files.createDirectory(directory))
        _ <- write(directory.resolve("zeta.md"), content)
        _ <- write(
          directory.resolve("alpha.md"),
          "\uFEFF---\r\ndescription: Alpha\r\nmodel: small\r\ntemperature: 0.25\r\nsteps: 3\r\ntools: []\r\n---\r\n\r\n# Exact body\r\n"
        )
        _      <- write(directory.resolve("ignored.MD"), "not a declaration")
        _      <- write(directory.resolve("notes.txt"), "not a declaration")
        nested <- ZIO.attemptBlocking(Files.createDirectory(directory.resolve("nested")))
        _      <- write(nested.resolve("invalid.md"), "invalid nested declaration")
        loaded <- cold
        unchanged <- ZIO.attemptBlocking(
          Files.readString(directory.resolve("zeta.md"), StandardCharsets.UTF_8)
        )
      yield assertTrue(
        loaded == Chunk(
          SubagentDeclaration(
            "alpha",
            "Alpha",
            "\r\n# Exact body\r\n",
            SubagentToolSelection.Only(Set.empty),
            Some("small"),
            Some(0.25),
            Some(3)
          ),
          SubagentDeclaration("zeta", "Review", "Body λ\uFEFF\n")
        ),
        unchanged == content
      )
    },
    test(
      "untrusted, traversing, sensitive, missing and selected nonregular paths fail the entire load"
    ) {
      for
        root      <- temporaryDirectory
        directory <- ZIO.attemptBlocking(Files.createDirectory(root.resolve("agents")))
        _         <- write(directory.resolve("valid.md"), content)
        untrusted <- LocalSubagentDeclarations
          .load(root.resolve("absent"), WorkspaceTrust.Untrusted)
          .exit
        traversing <- LocalSubagentDeclarations
          .load(directory.resolve("..").resolve("agents"), WorkspaceTrust.HostControlled)
          .exit
        sensitive <- ZIO.attemptBlocking(Files.createDirectory(root.resolve(".git")))
        denied    <- LocalSubagentDeclarations.load(sensitive, WorkspaceTrust.HostControlled).exit
        missing <- LocalSubagentDeclarations
          .load(root.resolve("missing"), WorkspaceTrust.HostControlled)
          .exit
        notDirectory <- LocalSubagentDeclarations
          .load(directory.resolve("valid.md"), WorkspaceTrust.HostControlled)
          .exit
        selectedDirectory <- ZIO.attemptBlocking(
          Files.createDirectory(directory.resolve("nested.md"))
        )
        shape <- LocalSubagentDeclarations.load(directory, WorkspaceTrust.HostControlled).exit
        _     <- ZIO.attemptBlocking(Files.delete(selectedDirectory))
        sensitiveFile = directory.resolve(".env.md")
        _        <- write(sensitiveFile, content)
        filename <- LocalSubagentDeclarations.load(directory, WorkspaceTrust.HostControlled).exit
        _        <- ZIO.attemptBlocking(Files.delete(sensitiveFile))
        _ <- write(directory.resolve("broken.md"), "---\ndescription: bad\nworkspace: /host\n---\n")
        malformed <- LocalSubagentDeclarations.load(directory, WorkspaceTrust.HostControlled).exit
        _         <- ZIO.attemptBlocking(Files.delete(directory.resolve("broken.md")))
        recovered <- LocalSubagentDeclarations.load(directory, WorkspaceTrust.HostControlled)
      yield assertTrue(
        permission(untrusted),
        permission(traversing),
        permission(denied),
        permission(notDirectory),
        permission(shape),
        permission(filename),
        missing.isFailure,
        malformed.causeOption.exists(
          _.failureOption.exists(error =>
            error.message.contains("broken.md") && error.message.length < 400
          )
        ),
        recovered.map(_.name) == Chunk("valid")
      )
    },
    test(
      "all-child scan, document and byte limits fail instead of truncating and strict UTF-8 releases files"
    ) {
      val byteCount = content.getBytes(StandardCharsets.UTF_8).length
      val exact = LocalSubagentDeclarationLimits(
        maxEntries = 3,
        maxDeclarations = 2,
        maxDocumentBytes = byteCount
      )
      val invalid = Chunk(
        exact.copy(maxEntries = 2),
        exact.copy(maxDeclarations = 1),
        exact.copy(maxDocumentBytes = byteCount - 1),
        exact.copy(parser = SubagentDeclarationLimits(maxDocumentCharacters = content.length - 1)),
        exact.copy(maxEntries = 0),
        exact.copy(maxDeclarations = -1),
        exact.copy(maxDocumentBytes = Int.MaxValue),
        exact.copy(parser = SubagentDeclarationLimits(maxHeaderCharacters = 0))
      )
      for
        root      <- temporaryDirectory
        directory <- ZIO.attemptBlocking(Files.createDirectory(root.resolve("agents")))
        _         <- write(directory.resolve("a.md"), content)
        _         <- write(directory.resolve("b.md"), content)
        _         <- write(directory.resolve("counted.txt"), "ignored but counted")
        accepted  <- LocalSubagentDeclarations.load(directory, WorkspaceTrust.HostControlled, exact)
        failures <- ZIO.foreach(invalid)(limits =>
          LocalSubagentDeclarations.load(directory, WorkspaceTrust.HostControlled, limits).exit
        )
        renamed  <- ZIO.attemptBlocking(Files.move(directory, root.resolve("released")))
        restored <- ZIO.attemptBlocking(Files.move(renamed, directory))
        _ <- ZIO.attemptBlocking(
          Files.write(restored.resolve("b.md"), Array(0xc3.toByte, 0x28.toByte))
        )
        utf8      <- LocalSubagentDeclarations.load(restored, WorkspaceTrust.HostControlled).exit
        _         <- ZIO.attemptBlocking(Files.delete(restored.resolve("b.md")))
        _         <- write(restored.resolve("b.md"), content)
        recovered <- LocalSubagentDeclarations.load(restored, WorkspaceTrust.HostControlled, exact)
        beforeScan <- LocalSubagentDeclarations
          .load(root.resolve("absent"), WorkspaceTrust.HostControlled, exact.copy(maxEntries = 0))
          .exit
      yield assertTrue(
        accepted.map(_.name) == Chunk("a", "b"),
        failures.forall(
          _.causeOption.exists(cause => cause.failureOption.nonEmpty && cause.defects.isEmpty)
        ),
        utf8.causeOption.exists(
          _.failureOption.exists(error =>
            error.message.contains("decode UTF-8") && error.message.contains("b.md")
          )
        ),
        recovered == accepted,
        beforeScan.causeOption.exists(
          _.failureOption.exists(_.message.contains("limits must be positive"))
        )
      )
    }
  )
