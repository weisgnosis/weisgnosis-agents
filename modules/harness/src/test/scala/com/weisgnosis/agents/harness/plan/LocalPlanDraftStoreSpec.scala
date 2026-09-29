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
package com.weisgnosis.agents.harness.plan

import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.harness.eviction.WorkspaceTrust
import com.weisgnosis.agents.harness.memory.MemoryTestSupport.{children, temporaryDirectory}
import java.nio.file.Files
import zio.*
import zio.test.*

object LocalPlanDraftStoreSpec extends ZIOSpecDefault:
  private def draft(content: String): IO[AgentError.InvalidMessage, PlanDraft] =
    ZIO.fromEither(PlanDraft.fromString(content))

  def spec: Spec[TestEnvironment & Scope, Any] = suite("LocalPlanDraftStoreSpec")(
    test("missing draft creates no payload and releasing ownership leaves an empty directory") {
      for
        root <- temporaryDirectory
        saved <- ZIO.scoped(
          LocalPlanDraftStore.open(root, WorkspaceTrust.HostControlled).flatMap(_.read)
        )
        names <- children(root)
      yield assertTrue(saved.isEmpty, names.isEmpty)
    },
    test("exact Unicode, whitespace and line endings survive replacement and reopen") {
      val content = "  # Plan\r\nExplore \ud83d\ude00 and caf\u00e9.\n  "
      for
        root  <- temporaryDirectory
        first <- draft("previous")
        next  <- draft(content)
        _ <- ZIO.scoped:
          LocalPlanDraftStore
            .open(root, WorkspaceTrust.HostControlled)
            .flatMap(store => store.write(first) *> store.write(next))
        saved <- ZIO.scoped(
          LocalPlanDraftStore.open(root, WorkspaceTrust.HostControlled).flatMap(_.read)
        )
        raw   <- ZIO.attemptBlocking(Files.readString(root.resolve("PLAN.md")))
        names <- children(root)
      yield assertTrue(saved.contains(next), raw == content, names == Set("PLAN.md"))
    },
    test("a live owner excludes competitors and different directories remain independent") {
      for
        left  <- temporaryDirectory
        right <- temporaryDirectory
        a     <- draft("left draft")
        b     <- draft("right draft")
        result <- ZIO.scoped:
          for
            first  <- LocalPlanDraftStore.open(left, WorkspaceTrust.HostControlled)
            second <- LocalPlanDraftStore.open(right, WorkspaceTrust.HostControlled)
            competitor <- ZIO
              .scoped(LocalPlanDraftStore.open(left, WorkspaceTrust.HostControlled))
              .either
            _          <- first.write(a) *> second.write(b)
            leftSaved  <- first.read
            rightSaved <- second.read
          yield competitor.left.exists(_.isInstanceOf[AgentError.PlanStorageError]) &&
            leftSaved.contains(a) && rightSaved.contains(b)
      yield assertTrue(result)
    },
    test("closed capabilities reject reads and writes without changing the persisted draft") {
      for
        root        <- temporaryDirectory
        original    <- draft("original")
        replacement <- draft("replacement")
        closed <- ZIO.scoped:
          LocalPlanDraftStore.open(root, WorkspaceTrust.HostControlled).tap(_.write(original))
        read  <- closed.read.either
        write <- closed.write(replacement).either
        saved <- ZIO.scoped(
          LocalPlanDraftStore.open(root, WorkspaceTrust.HostControlled).flatMap(_.read)
        )
      yield assertTrue(
        read.left.exists(_.isInstanceOf[AgentError.PlanStorageError]),
        write.left.exists(_.isInstanceOf[AgentError.PlanStorageError]),
        saved.contains(original)
      )
    },
    test("concurrent reads observe only complete published drafts") {
      for
        root   <- temporaryDirectory
        drafts <- ZIO.foreach(1 to 12)(index => draft(s"draft $index\n" * 300))
        observed <- ZIO.scoped:
          for
            store     <- LocalPlanDraftStore.open(root, WorkspaceTrust.HostControlled)
            reads     <- ZIO.foreachPar(drafts)(value => store.write(value) *> store.read)
            finalRead <- store.read
          yield reads :+ finalRead
        names <- children(root)
      yield assertTrue(observed.forall(_.exists(drafts.contains)), names == Set("PLAN.md"))
    },
    test("stale ownership is refused without deleting or overwriting its token") {
      for
        root <- temporaryDirectory
        _    <- ZIO.attemptBlocking(Files.writeString(root.resolve(".plan-writer.lease"), "stale"))
        failed <- ZIO.scoped(LocalPlanDraftStore.open(root, WorkspaceTrust.HostControlled)).either
        token  <- ZIO.attemptBlocking(Files.readString(root.resolve(".plan-writer.lease")))
        names  <- children(root)
      yield assertTrue(
        failed.left.exists(_.isInstanceOf[AgentError.PlanStorageError]),
        token == "stale",
        names == Set(".plan-writer.lease")
      )
    },
    test("blank or invalid UTF-8 payloads fail opening and release newly acquired ownership") {
      for
        blank   <- temporaryDirectory
        corrupt <- temporaryDirectory
        _       <- ZIO.attemptBlocking(Files.writeString(blank.resolve("PLAN.md"), " \r\n"))
        _ <- ZIO.attemptBlocking(
          Files.write(corrupt.resolve("PLAN.md"), Chunk[Byte](0xc3.toByte, 0x28.toByte).toArray)
        )
        rejected <- ZIO.foreach(Chunk(blank, corrupt))(root =>
          ZIO.scoped(LocalPlanDraftStore.open(root, WorkspaceTrust.HostControlled)).either
        )
        names      <- ZIO.foreach(Chunk(blank, corrupt))(children)
        blankBytes <- ZIO.attemptBlocking(Files.readString(blank.resolve("PLAN.md")))
        corruptBytes <- ZIO.attemptBlocking(
          Chunk.fromArray(Files.readAllBytes(corrupt.resolve("PLAN.md")))
        )
      yield assertTrue(
        rejected.forall(_.left.exists(_.isInstanceOf[AgentError.PlanStorageError])),
        names.forall(_ == Set("PLAN.md")),
        blankBytes == " \r\n",
        corruptBytes == Chunk[Byte](0xc3.toByte, 0x28.toByte)
      )
    },
    test("untrusted, traversing, sensitive, missing and file directories never acquire ownership") {
      for
        root      <- temporaryDirectory
        child     <- ZIO.attemptBlocking(Files.createDirectory(root.resolve("child")))
        sensitive <- ZIO.attemptBlocking(Files.createDirectory(root.resolve(".env.private")))
        file      <- ZIO.attemptBlocking(Files.writeString(root.resolve("file"), "keep"))
        untrusted <- ZIO.scoped(LocalPlanDraftStore.open(root, WorkspaceTrust.Untrusted)).either
        rejected <- ZIO.foreach(
          Chunk(child.resolve(".."), sensitive, root.resolve("missing"), file)
        )(path => ZIO.scoped(LocalPlanDraftStore.open(path, WorkspaceTrust.HostControlled)).either)
        names <- children(root)
      yield assertTrue(
        untrusted.isLeft,
        rejected.forall(_.isLeft),
        names == Set("child", ".env.private", "file")
      )
    }
  )
