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
import com.weisgnosis.agents.core.skill.{SkillCatalog, SkillDefinition}
import com.weisgnosis.agents.harness.eviction.WorkspaceTrust
import com.weisgnosis.agents.harness.memory.MemoryTestSupport.temporaryDirectory
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import zio.*
import zio.test.*
import zio.json.ast.Json

object LocalSkillSpec extends ZIOSpecDefault:
  private val body: String = "\r\n# Exact body\r\nText café\r\n"
  private val markdown: String =
    "---\r\nname: declared-id\r\ndescription: Read carefully\r\nactivateGroups: [unbound]\r\n---\r\n" + body

  private def write(path: Path, text: String): Task[Unit] =
    ZIO.attemptBlocking(Files.writeString(path, text, StandardCharsets.UTF_8)).unit

  private def denied(value: Either[AgentError, SkillDefinition]): Boolean =
    value.left.exists:
      case _: AgentError.PermissionDenied => true
      case _                              => false

  def spec: Spec[TestEnvironment & Scope, Any] = suite("LocalSkillSpec")(
    test(
      "suspended selected loading preserves declared identity and text without implicit discovery"
    ) {
      for
        root <- temporaryDirectory
        directory = root.resolve("different-directory-name")
        selected  = Set("references/a..b file.md", "empty.txt")
        cold      = LocalSkill.load(directory, WorkspaceTrust.HostControlled, selected)
        _ <- ZIO.attemptBlocking(Files.createDirectory(directory))
        _ <- ZIO.attemptBlocking(Files.createDirectory(directory.resolve("references")))
        _ <- write(directory.resolve("SKILL.md"), markdown)
        _ <- write(directory.resolve("references/a..b file.md"), "  Resource café\r\n")
        _ <- write(directory.resolve("empty.txt"), "")
        _ <- ZIO.attemptBlocking(
          Files.write(directory.resolve("ignored.bin"), Array(0xc3.toByte, 0x28.toByte))
        )
        loaded <- cold
        plain  <- LocalSkill.load(directory, WorkspaceTrust.HostControlled)
        unchanged <- ZIO.attemptBlocking(
          Files.readString(directory.resolve("SKILL.md"), StandardCharsets.UTF_8)
        )
        _ <- ZIO.attemptBlocking(Files.delete(directory.resolve("references/a..b file.md")))
      yield assertTrue(
        loaded == SkillDefinition(
          "declared-id",
          "Read carefully",
          body,
          Map("references/a..b file.md" -> "  Resource café\r\n", "empty.txt" -> ""),
          metadata = Json.Obj("activateGroups" -> Json.Arr(Json.Str("unbound")))
        ),
        plain == loaded.copy(resources = Map.empty),
        SkillCatalog.make(Chunk(loaded)).isRight,
        unchanged == markdown
      )
    },
    test("trust, traversal, sensitive paths and selected file shapes fail without partial values") {
      val invalidNames = Chunk(
        "",
        " ",
        "..",
        "../outside",
        "nested/../file",
        "./file",
        "/outside",
        "a//b",
        "a/",
        "a\\b",
        "C:/file",
        "a:b",
        "a\u0000b",
        "line\nfile",
        "SKILL.md",
        ".env.production",
        ".ssh/key",
        ".git/config"
      )
      for
        root      <- temporaryDirectory
        _         <- write(root.resolve("SKILL.md"), markdown)
        _         <- write(root.resolve("good.txt"), "Good")
        _         <- write(root.resolve("not-dir"), "File")
        _         <- ZIO.attemptBlocking(Files.createDirectory(root.resolve("directory.txt")))
        untrusted <- LocalSkill.load(root.resolve("absent"), WorkspaceTrust.Untrusted).either
        traversal <- LocalSkill
          .load(root.resolve("..").resolve("missing"), WorkspaceTrust.HostControlled)
          .either
        sensitive <- LocalSkill.load(root.resolve(".git"), WorkspaceTrust.HostControlled).either
        notDirectory <- LocalSkill
          .load(root.resolve("good.txt"), WorkspaceTrust.HostControlled)
          .either
        invalid <- ZIO.foreach(invalidNames)(name =>
          LocalSkill.load(root, WorkspaceTrust.HostControlled, Set(name)).either
        )
        shapes <- ZIO.foreach(Chunk("missing.txt", "directory.txt", "not-dir/child"))(name =>
          LocalSkill.load(root, WorkspaceTrust.HostControlled, Set("good.txt", name)).either
        )
        _                 <- ZIO.attemptBlocking(Files.delete(root.resolve("SKILL.md")))
        missing           <- LocalSkill.load(root, WorkspaceTrust.HostControlled).either
        _                 <- ZIO.attemptBlocking(Files.createDirectory(root.resolve("SKILL.md")))
        directoryDocument <- LocalSkill.load(root, WorkspaceTrust.HostControlled).either
      yield assertTrue(
        denied(untrusted),
        denied(traversal),
        denied(sensitive),
        denied(notDirectory),
        invalid.forall(_.isLeft),
        shapes.forall(_.isLeft),
        missing.isLeft,
        directoryDocument.isLeft
      )
    },
    test("byte, resource-count, parser and UTF-8 limits fail the whole selected load") {
      val resource = "é"
      val exact = LocalSkillLimits(
        maxResources = 1,
        maxDocumentBytes = markdown.getBytes(StandardCharsets.UTF_8).length,
        maxResourceBytes = resource.getBytes(StandardCharsets.UTF_8).length
      )
      val invalidLimits = Chunk(
        exact.copy(maxResources = 0),
        exact.copy(maxDocumentBytes = 0),
        exact.copy(maxResourceBytes = -1),
        exact.copy(maxDocumentBytes = Int.MaxValue),
        exact.copy(maxResourceBytes = Int.MaxValue),
        exact.copy(parser = SkillMarkdownLimits(maxHeaderCharacters = 0)),
        exact.copy(parser = SkillMarkdownLimits(maxMetadataDepth = 0)),
        exact.copy(maxDocumentBytes = exact.maxDocumentBytes - 1),
        exact.copy(maxResourceBytes = exact.maxResourceBytes - 1),
        exact.copy(parser = SkillMarkdownLimits(maxDocumentCharacters = markdown.length - 1))
      )
      for
        root <- temporaryDirectory
        beforeIo <- LocalSkill
          .load(
            root.resolve("missing"),
            WorkspaceTrust.HostControlled,
            limits = exact.copy(parser = SkillMarkdownLimits(maxMetadataDepth = 0))
          )
          .either
        _        <- write(root.resolve("SKILL.md"), markdown)
        _        <- write(root.resolve("resource.txt"), resource)
        _        <- write(root.resolve("other.txt"), "x")
        accepted <- LocalSkill.load(root, WorkspaceTrust.HostControlled, Set("resource.txt"), exact)
        invalid <- ZIO.foreach(invalidLimits)(limits =>
          LocalSkill.load(root, WorkspaceTrust.HostControlled, Set("resource.txt"), limits).either
        )
        tooMany <- LocalSkill
          .load(root, WorkspaceTrust.HostControlled, Set("resource.txt", "other.txt"), exact)
          .either
        _ <- ZIO.attemptBlocking(
          Files.write(root.resolve("resource.txt"), Array(0xc3.toByte, 0x28.toByte))
        )
        invalidResource <- LocalSkill
          .load(root, WorkspaceTrust.HostControlled, Set("resource.txt"), exact)
          .either
        _ <- ZIO.attemptBlocking(
          Files.write(
            root.resolve("SKILL.md"),
            markdown.getBytes(StandardCharsets.UTF_8) ++ Array(0xc3.toByte, 0x28.toByte)
          )
        )
        invalidDocument <- LocalSkill.load(root, WorkspaceTrust.HostControlled).either
        _ <- write(
          root.resolve("SKILL.md"),
          "---\nname: guide\ndescription: 123\n---\nBody"
        )
        invalidProfile <- LocalSkill.load(root, WorkspaceTrust.HostControlled).either
      yield assertTrue(
        accepted.resources == Map("resource.txt" -> resource),
        beforeIo == Left(
          AgentError.InvalidConfiguration(
            "Local skill: limits must be positive and byte limits must be below Int.MaxValue"
          )
        ),
        invalid.forall(_.isLeft),
        tooMany.isLeft,
        invalidResource.isLeft,
        invalidDocument.isLeft,
        invalidProfile.isLeft
      )
    }
  )
