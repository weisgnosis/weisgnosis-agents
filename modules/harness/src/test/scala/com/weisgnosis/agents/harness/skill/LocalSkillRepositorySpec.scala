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
import com.weisgnosis.agents.harness.memory.MemoryTestSupport.temporaryDirectory
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import zio.*
import zio.json.ast.Json
import zio.test.*

object LocalSkillRepositorySpec extends ZIOSpecDefault:
  private def document(id: String, extra: String = ""): String =
    s"---\nname: $id\ndescription: Read carefully\n$extra---\n# Exact body\nText café\n"

  private def write(path: Path, text: String): Task[Unit] =
    ZIO.attemptBlocking(Files.writeString(path, text, StandardCharsets.UTF_8)).unit

  private def skill(root: Path, directory: String, id: String, extra: String = ""): Task[Path] =
    for
      path <- ZIO.attemptBlocking(Files.createDirectory(root.resolve(directory)))
      _    <- write(path.resolve("SKILL.md"), document(id, extra))
    yield path

  private def denied(result: Either[AgentError, SkillCatalog]): Boolean =
    result.left.exists:
      case _: AgentError.PermissionDenied => true
      case _                              => false

  def spec: Spec[TestEnvironment & Scope, Any] = suite("LocalSkillRepositorySpec")(
    test("opt-in text trees retain exact portable resources after filesystem release"):
      for
        root <- temporaryDirectory
        markdown = "\uFEFF" + document("selected").replace("Text café", "Text café \uFEFF")
        exact = LocalSkillRepositoryLimits(local =
          LocalSkillLimits(
            maxDocumentBytes = markdown.getBytes(StandardCharsets.UTF_8).length,
            parser = SkillMarkdownLimits(maxDocumentCharacters = markdown.length)
          )
        )
        repository = root.resolve("repository")
        cold = LocalSkillRepository.load(
          repository,
          WorkspaceTrust.HostControlled,
          limits = exact,
          resources = LocalSkillResources.TextTree(LocalSkillResourceLimits())
        )
        _         <- ZIO.attemptBlocking(Files.createDirectory(repository))
        directory <- skill(repository, "skill", "selected")
        _         <- write(directory.resolve("SKILL.md"), markdown)
        nested    <- ZIO.attemptBlocking(Files.createDirectory(directory.resolve("references")))
        _         <- write(nested.resolve("guide.md"), "  Exact café\r\n")
        _         <- write(nested.resolve("SKILL.md"), "Nested resource, not a skill document")
        _         <- write(directory.resolve(".notes"), "Benign dot file")
        _         <- write(directory.resolve("z.txt"), "Last")
        defaults  <- LocalSkillRepository.load(repository, WorkspaceTrust.HostControlled)
        loaded    <- cold
        bounded <- ZIO.foreach(
          Chunk(
            exact.copy(local =
              exact.local.copy(maxDocumentBytes = exact.local.maxDocumentBytes - 1)
            ),
            exact.copy(local =
              exact.local
                .copy(parser = exact.local.parser.copy(maxDocumentCharacters = markdown.length - 1))
            )
          )
        )(limits =>
          LocalSkillRepository.load(repository, WorkspaceTrust.HostControlled, limits).either
        )
        _        <- ZIO.attemptBlocking(Files.move(repository, root.resolve("moved")))
        resource <- ZIO.fromEither(loaded.load("selected", "references/guide.md"))
        _ <- ZIO.attemptBlocking(
          Files.write(root.resolve("moved/skill/binary"), Array(0xc3.toByte, 0x28.toByte))
        )
        isolated <- LocalSkillRepository.load(root.resolve("moved"), WorkspaceTrust.HostControlled)
        binary <- LocalSkillRepository
          .load(
            root.resolve("moved"),
            WorkspaceTrust.HostControlled,
            resources = LocalSkillResources.TextTree(LocalSkillResourceLimits())
          )
          .either
      yield assertTrue(
        defaults.definitions.forall(_.resources.isEmpty),
        loaded.definitions.map(_.id) == Chunk("selected"),
        loaded.definitions.map(_.content) == Chunk("# Exact body\nText café \uFEFF\n"),
        bounded.forall(_.isLeft),
        isolated.definitions == defaults.definitions,
        loaded.definitions.map(_.resources) == Chunk(
          Map(
            "references/guide.md" -> "  Exact café\r\n",
            ".notes"              -> "Benign dot file",
            "z.txt"               -> "Last",
            "references/SKILL.md" -> "Nested resource, not a skill document"
          )
        ),
        resource.content == "  Exact café\r\n",
        binary.left.exists(_.message.contains("decode UTF-8"))
      )
    ,
    test("text-tree budgets and unsafe entries reject whole snapshots with pre-I/O validation"):
      val tree   = LocalSkillResourceLimits(maxEntries = 5, maxDepth = 1)
      val local  = LocalSkillLimits(maxResources = 2, maxResourceBytes = 4)
      val limits = LocalSkillRepositoryLimits(local = local)
      for
        root <- temporaryDirectory
        preflight <- ZIO.foreach(Chunk(tree.copy(maxEntries = 0), tree.copy(maxDepth = 0))):
          invalid =>
            LocalSkillRepository
              .load(
                root.resolve("missing"),
                WorkspaceTrust.HostControlled,
                limits,
                LocalSkillResources.TextTree(invalid)
              )
              .either
        directory <- skill(root, "skill", "selected")
        nested    <- ZIO.attemptBlocking(Files.createDirectory(directory.resolve("references")))
        _         <- ZIO.attemptBlocking(Files.createDirectory(directory.resolve("z-empty")))
        _         <- write(directory.resolve("first.txt"), "1234")
        _         <- write(nested.resolve("second.txt"), "abcd")
        accepted <- LocalSkillRepository.load(
          root,
          WorkspaceTrust.HostControlled,
          limits,
          LocalSkillResources.TextTree(tree)
        )
        rejected <- ZIO.foreach(
          Chunk(
            (tree.copy(maxEntries = 4), limits, "directory entry limit exceeded"),
            (
              tree,
              limits.copy(local = local.copy(maxResources = 1)),
              "resource count limit exceeded"
            ),
            (tree, limits.copy(local = local.copy(maxResourceBytes = 3)), "byte limit exceeded")
          )
        ): (budget, configured, diagnostic) =>
          LocalSkillRepository
            .load(
              root,
              WorkspaceTrust.HostControlled,
              configured,
              LocalSkillResources.TextTree(budget)
            )
            .either
            .map(result => assertTrue(result.left.exists(_.message.contains(diagnostic))))
        _ <- ZIO.attemptBlocking(Files.createDirectory(nested.resolve("deeper")))
        depth <- LocalSkillRepository
          .load(
            root,
            WorkspaceTrust.HostControlled,
            limits,
            LocalSkillResources.TextTree(tree.copy(maxEntries = 6))
          )
          .either
        _ <- ZIO.attemptBlocking(Files.delete(nested.resolve("deeper")))
        _ <- write(nested.resolve(".env"), "sensitive")
        sensitive <- LocalSkillRepository
          .load(
            root,
            WorkspaceTrust.HostControlled,
            limits,
            LocalSkillResources.TextTree(tree.copy(maxEntries = 6))
          )
          .either
      yield rejected.foldLeft(
        assertTrue(
          preflight.forall(
            _ == Left(
              AgentError.InvalidConfiguration(
                "Local skill repository: resource entry and depth limits must be positive"
              )
            )
          ),
          accepted.definitions.map(_.resources.size) == Chunk(2),
          depth.left.exists(_.message.contains("resource directory depth limit exceeded")),
          denied(sensitive)
        )
      )(_ && _)
    ,
    test("cold discovery retains declared identities and metadata without resources or recursion") {
      for
        root <- temporaryDirectory
        directory = root.resolve("repository")
        cold      = LocalSkillRepository.load(directory, WorkspaceTrust.HostControlled)
        _       <- ZIO.attemptBlocking(Files.createDirectory(directory))
        first   <- skill(directory, "z-folder", "alpha", "activateGroups: [admin]\n")
        _       <- skill(directory, "a-folder", "zeta")
        ignored <- ZIO.attemptBlocking(Files.createDirectory(directory.resolve("no-skill")))
        _       <- skill(ignored, "nested", "not-discovered")
        _       <- write(directory.resolve("ordinary-file"), "Not a directory")
        _ <- ZIO.attemptBlocking(
          Files.write(first.resolve("unselected.bin"), Array(0xc3.toByte, 0x28.toByte))
        )
        loaded   <- cold
        _        <- ZIO.attemptBlocking(Files.move(directory, root.resolve("moved")))
        reloaded <- LocalSkillRepository.load(root.resolve("moved"), WorkspaceTrust.HostControlled)
        empty    <- ZIO.attemptBlocking(Files.createDirectory(root.resolve("empty")))
        emptyCatalog <- LocalSkillRepository.load(empty, WorkspaceTrust.HostControlled)
      yield assertTrue(
        loaded.definitions.map(_.id) == Chunk("alpha", "zeta"),
        loaded.definitions.map(_.content) == Chunk.fill(2)("# Exact body\nText café\n"),
        loaded.definitions.forall(value => value.resources.isEmpty && value.activateGroups.isEmpty),
        loaded.definitions.map(_.metadata) == Chunk(
          Json.Obj("activateGroups" -> Json.Arr(Json.Str("admin"))),
          Json.Obj()
        ),
        reloaded.definitions == loaded.definitions,
        emptyCatalog.isEmpty
      )
    },
    test(
      "trust, candidate shape, duplicate IDs and malformed selected skills fail the whole snapshot"
    ) {
      for
        root <- temporaryDirectory
        untrusted <- LocalSkillRepository
          .load(root.resolve("missing"), WorkspaceTrust.Untrusted)
          .either
        traversal <- LocalSkillRepository
          .load(root.resolve(".."), WorkspaceTrust.HostControlled)
          .either
        _ <- write(root.resolve("file"), "Not a directory")
        fileRoot <- LocalSkillRepository
          .load(root.resolve("file"), WorkspaceTrust.HostControlled)
          .either
        duplicates <- ZIO.attemptBlocking(Files.createDirectory(root.resolve("duplicates")))
        _          <- skill(duplicates, "first", "same")
        _          <- skill(duplicates, "second", "same")
        duplicate  <- LocalSkillRepository.load(duplicates, WorkspaceTrust.HostControlled).either
        malformed  <- ZIO.attemptBlocking(Files.createDirectory(root.resolve("malformed")))
        later      <- skill(malformed, "z-folder", "valid")
        _ <- write(later.resolve("SKILL.md"), "---\nname: valid\ndescription: null\n---\nBody")
        earlier <- skill(malformed, "a-folder", "valid")
        _   <- write(earlier.resolve("SKILL.md"), "---\nname: 123\ndescription: Valid\n---\nBody")
        bad <- LocalSkillRepository.load(malformed, WorkspaceTrust.HostControlled).either
        shapes            <- ZIO.attemptBlocking(Files.createDirectory(root.resolve("shapes")))
        _                 <- skill(shapes, "good", "good")
        invalid           <- ZIO.attemptBlocking(Files.createDirectory(shapes.resolve("invalid")))
        _                 <- ZIO.attemptBlocking(Files.createDirectory(invalid.resolve("SKILL.md")))
        directoryDocument <- LocalSkillRepository.load(shapes, WorkspaceTrust.HostControlled).either
        sensitive         <- ZIO.attemptBlocking(Files.createDirectory(root.resolve("sensitive")))
        _                 <- ZIO.attemptBlocking(Files.createDirectory(sensitive.resolve(".git")))
        sensitiveCandidate <- LocalSkillRepository
          .load(sensitive, WorkspaceTrust.HostControlled)
          .either
      yield assertTrue(
        denied(untrusted),
        denied(traversal),
        denied(fileRoot),
        duplicate == Left(AgentError.InvalidConfiguration("Duplicate skill ID 'same'")),
        bad == Left(
          AgentError.InvalidConfiguration(
            "Local skill: parse SKILL.md: Invalid skill markdown: name must be a nonblank string"
          )
        ),
        denied(directoryDocument),
        denied(sensitiveCandidate)
      )
    },
    test("all-entry, selected-count and local bounds validate before I/O and reject whole loads") {
      val bytes = document("alpha").getBytes(StandardCharsets.UTF_8).length
      val exact = LocalSkillRepositoryLimits(
        maxEntries = 3,
        maxSkills = 2,
        local = LocalSkillLimits(maxDocumentBytes = bytes)
      )
      val invalidLocal = Chunk(
        exact.local.copy(maxResources = 0),
        exact.local.copy(maxDocumentBytes = 0),
        exact.local.copy(maxResourceBytes = 0),
        exact.local.copy(maxDocumentBytes = Int.MaxValue),
        exact.local.copy(maxResourceBytes = Int.MaxValue),
        exact.local.copy(parser = SkillMarkdownLimits(maxDocumentCharacters = 0)),
        exact.local.copy(parser = SkillMarkdownLimits(maxHeaderCharacters = 0)),
        exact.local.copy(parser = SkillMarkdownLimits(maxMetadataDepth = 0))
      )
      for
        root <- temporaryDirectory
        preflight <- ZIO.foreach(Chunk(exact.copy(maxEntries = 0), exact.copy(maxSkills = 0)))(
          limits =>
            LocalSkillRepository
              .load(root.resolve("missing"), WorkspaceTrust.HostControlled, limits)
              .either
        )
        nestedPreflight <- ZIO.foreach(invalidLocal)(local =>
          LocalSkillRepository
            .load(root.resolve("missing"), WorkspaceTrust.HostControlled, exact.copy(local = local))
            .either
        )
        first    <- skill(root, "first", "alpha")
        _        <- skill(root, "second", "bravo")
        _        <- write(root.resolve("ignored"), "Counts despite not being a skill")
        accepted <- LocalSkillRepository.load(root, WorkspaceTrust.HostControlled, exact)
        entries <- LocalSkillRepository
          .load(root, WorkspaceTrust.HostControlled, exact.copy(maxEntries = 2))
          .either
        skills <- LocalSkillRepository
          .load(root, WorkspaceTrust.HostControlled, exact.copy(maxSkills = 1))
          .either
        documentLimit <- LocalSkillRepository
          .load(
            root,
            WorkspaceTrust.HostControlled,
            exact.copy(local = exact.local.copy(maxDocumentBytes = bytes - 1))
          )
          .either
        _ <- ZIO.attemptBlocking(
          Files.write(first.resolve("SKILL.md"), Array(0xc3.toByte, 0x28.toByte))
        )
        invalidUtf8 <- LocalSkillRepository.load(root, WorkspaceTrust.HostControlled, exact).either
        _           <- ZIO.attemptBlocking(Files.delete(first.resolve("SKILL.md")))
        remaining   <- LocalSkillRepository.load(root, WorkspaceTrust.HostControlled, exact)
      yield assertTrue(
        preflight.forall(
          _ == Left(
            AgentError.InvalidConfiguration(
              "Local skill repository: entry and skill limits must be positive"
            )
          )
        ),
        nestedPreflight.forall(
          _ == Left(
            AgentError.InvalidConfiguration(
              "Local skill: limits must be positive and byte limits must be below Int.MaxValue"
            )
          )
        ),
        accepted.definitions.map(_.id) == Chunk("alpha", "bravo"),
        entries == Left(
          AgentError.InvalidConfiguration("Local skill repository: directory entry limit exceeded")
        ),
        skills == Left(
          AgentError.InvalidConfiguration("Local skill repository: skill count limit exceeded")
        ),
        documentLimit.left.exists(_.message.contains("byte limit exceeded")),
        invalidUtf8.left.exists(_.message.contains("decode UTF-8")),
        remaining.definitions.map(_.id) == Chunk("bravo")
      )
    }
  )
