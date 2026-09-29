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
import com.weisgnosis.agents.core.model.ContentBlock
import zio.*
import zio.json.*
import zio.test.*
import java.nio.file.{Files, Path}
import EvictionTestSupport.temporaryDirectory

object ResultStorageLocationSpec extends ZIOSpecDefault:
  def spec: Spec[TestEnvironment & Scope, Any] = suite("ResultStorageLocationSpec")(
    test("location codecs preserve pure configuration without parsing platform-specific paths") {
      val values = Chunk(
        ResultStorageLocation.WorkspaceDefault,
        ResultStorageLocation.AbsoluteDirectory("/workspace/results///"),
        ResultStorageLocation.AbsoluteDirectory("unvalidated configuration")
      )
      assertTrue(
        values.forall(value => value.toJson.fromJson[ResultStorageLocation] == Right(value))
      )
    },
    test("preservesConfiguredAbsolutePathWhileTrimmingTrailingSlashes") {
      val content = "START" + "full output" * 100 + "FINISH"
      for
        directory <- temporaryDirectory
        unusedWorkspace = directory.resolve("unused-workspace")
        location        = ResultStorageLocation.AbsoluteDirectory(directory.toString + "///")
        store <- ToolResultStore.local(unusedWorkspace, WorkspaceTrust.HostControlled, location)
        first <- new ToolResultEvictor(store, "Test Agent").evictResult(
          ContentBlock.ToolResult.text("call:1", "lookup", content),
          EvictionConfig(maxResultChars = 20, previewHeadChars = 4, previewTailChars = 4)
        )
        key <- ZIO
          .fromOption(first.metadata.get("eviction_cache_key").flatMap(_.asString))
          .orElseFail(AgentError.InvalidMessage("missing eviction key"))
        stored <- store
          .resolve(key)
          .flatMap(value =>
            ZIO
              .fromOption(value)
              .orElseFail(AgentError.InvalidMessage("missing stored payload"))
          )
        canonical        <- ZIO.attemptBlocking(directory.toRealPath())
        workspaceCreated <- ZIO.attemptBlocking(Files.exists(unusedWorkspace))
        defaultCreated <- ZIO.attemptBlocking(Files.exists(directory.resolve("large_tool_results")))
        secondStore <- ToolResultStore.local(
          unusedWorkspace,
          WorkspaceTrust.HostControlled,
          location
        )
        repeated <- new ToolResultEvictor(secondStore, "Test Agent").evictResult(
          first,
          EvictionConfig(maxResultChars = 20, previewHeadChars = 4, previewTailChars = 4)
        )
      yield assertTrue(
        stored.content == content,
        Path.of(stored.path).getParent == canonical,
        first.text.contains(stored.path),
        first.text.endsWith("NISH"),
        !workspaceCreated,
        !defaultCreated,
        repeated == first
      )
    },
    test("relative drive-relative root-relative and parent-traversing paths fail before storage") {
      for
        directory <- temporaryDirectory
        traversal = directory.resolve("child").resolve("..").toString
        invalid   = Chunk("", "relative", "C:relative", "\\relative", traversal)
        results <- ZIO.foreach(invalid)(path =>
          ToolResultStore
            .local(
              directory,
              WorkspaceTrust.HostControlled,
              ResultStorageLocation.AbsoluteDirectory(path)
            )
            .either
        )
        created <- ZIO.attemptBlocking(Files.exists(directory.resolve("child")))
      yield assertTrue(results.forall(_.isLeft), !created)
    },
    test("missing regular-file sensitive and filesystem-root directories are rejected") {
      for
        directory <- temporaryDirectory
        regular   <- ZIO.attemptBlocking(Files.writeString(directory.resolve("regular"), "content"))
        sensitive <- ZIO.attemptBlocking(Files.createDirectory(directory.resolve(".ssh")))
        missing    = directory.resolve("missing")
        candidates = Chunk(missing, regular, sensitive, directory.getRoot)
        results <- ZIO.foreach(candidates)(path =>
          ToolResultStore
            .local(
              directory,
              WorkspaceTrust.HostControlled,
              ResultStorageLocation.AbsoluteDirectory(path.toString)
            )
            .either
        )
        created <- ZIO.attemptBlocking(Files.exists(missing))
      yield assertTrue(results.forall(_.isLeft), !created)
    },
    test("untrusted absolute configuration fails before path parsing or directory creation") {
      for
        directory <- temporaryDirectory
        missing = directory.resolve("missing")
        results <- ZIO.foreach(Chunk(missing.toString, "invalid:configuration"))(path =>
          ToolResultStore
            .local(
              directory,
              WorkspaceTrust.Untrusted,
              ResultStorageLocation.AbsoluteDirectory(path)
            )
            .either
        )
        created <- ZIO.attemptBlocking(Files.exists(missing))
      yield assertTrue(
        results.forall(_.left.exists(_.isInstanceOf[AgentError.PermissionDenied])),
        !created
      )
    },
    test(
      "independent absolute stores preserve concurrent complete publication and collision protection"
    ) {
      val content = "complete output" * 10000
      for
        directory <- temporaryDirectory
        stores <- ZIO.foreach(1 to 8)(_ =>
          ToolResultStore.local(
            directory.resolve("unused"),
            WorkspaceTrust.HostControlled,
            ResultStorageLocation.AbsoluteDirectory(directory.toString)
          )
        )
        paths      <- ZIO.foreachPar(stores)(_.persist("shared", content))
        results    <- ZIO.foreach(stores)(_.resolve("shared"))
        collisions <- ZIO.foreach(stores)(_.persist("shared", "different").either)
      yield assertTrue(
        paths.distinct.size == 1,
        results.forall(_.exists(_.content == content)),
        collisions.forall(_.isLeft)
      )
    },
    test("selected absolute storage is revalidated when replaced by a file or removed") {
      for
        parent    <- temporaryDirectory
        directory <- ZIO.attemptBlocking(Files.createDirectory(parent.resolve("results")))
        store <- ToolResultStore.local(
          parent,
          WorkspaceTrust.HostControlled,
          ResultStorageLocation.AbsoluteDirectory(directory.toString)
        )
        _         <- ZIO.attemptBlocking(Files.delete(directory))
        missing   <- store.resolve("key").either
        _         <- ZIO.attemptBlocking(Files.writeString(directory, "replacement"))
        read      <- store.resolve("key").either
        write     <- store.persist("key", "data").either
        unchanged <- ZIO.attemptBlocking(Files.readString(directory))
      yield assertTrue(missing.isLeft, read.isLeft, write.isLeft, unchanged == "replacement")
    },
    test("default location retains lazy workspace storage without touching external directories") {
      for
        directory <- temporaryDirectory
        store <- ToolResultStore.local(
          directory,
          WorkspaceTrust.HostControlled,
          ResultStorageLocation.WorkspaceDefault
        )
        before    <- ZIO.attemptBlocking(Files.exists(directory.resolve("large_tool_results")))
        path      <- store.persist("key", "content")
        canonical <- ZIO.attemptBlocking(directory.resolve("large_tool_results").toRealPath())
      yield assertTrue(!before, Path.of(path).getParent == canonical)
    }
  )
