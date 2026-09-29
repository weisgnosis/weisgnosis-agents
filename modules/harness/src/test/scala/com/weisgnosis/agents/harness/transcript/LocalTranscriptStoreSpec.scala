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
package com.weisgnosis.agents.harness.transcript

import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.model.{ContentBlock, Msg, Role}
import com.weisgnosis.agents.harness.eviction.WorkspaceTrust
import com.weisgnosis.agents.harness.memory.MemoryTestSupport.{children, temporaryDirectory}
import java.nio.file.Files
import zio.*
import zio.json.ast.Json
import zio.test.*

object LocalTranscriptStoreSpec extends ZIOSpecDefault:
  private def draft(id: String): TranscriptDraft = TranscriptDraft(
    Some(TranscriptKey.Message(id)),
    TranscriptPayload.Message(Role.User, id, Chunk(TranscriptBlockType.Text))
  )
  private def text(id: Option[String], value: String): Msg =
    Msg.user(Chunk(ContentBlock.Text(value)), id = id)
  private def linked(entries: Chunk[TranscriptEntry]): Boolean = entries.isEmpty || entries.map(
    _.parentId
  ) == (Chunk(None) ++ entries.dropRight(1).map(entry => Some(entry.id)))

  def spec: Spec[TestEnvironment & Scope, Any] = suite("LocalTranscriptStoreSpec")(
    test("missing transcript and empty append create no payload files") {
      for
        root <- temporaryDirectory
        entries <- ZIO.scoped:
          for
            store    <- LocalTranscriptStore.open(root, WorkspaceTrust.HostControlled)
            appended <- store.append(Chunk.empty)
            saved    <- store.entries
          yield appended ++ saved
        files <- children(root)
      yield assertTrue(entries.isEmpty, files.isEmpty)
    },
    test("overlapping batches persist four observations and exact parent continuity across reopen") {
      for
        root <- temporaryDirectory
        first <- ZIO.scoped:
          for
            store    <- LocalTranscriptStore.open(root, WorkspaceTrust.HostControlled)
            appended <- store.append(Chunk(draft("m1"), draft("m2")))
          yield appended
        all <- ZIO.scoped:
          for
            store <- LocalTranscriptStore.open(root, WorkspaceTrust.HostControlled)
            _     <- store.append(Chunk(draft("m1"), draft("m2"), draft("m3")))
            _     <- store.append(Chunk(draft("m2"), draft("m3"), draft("m4")))
            saved <- store.entries
          yield saved
        bytes <- ZIO.attemptBlocking(Files.readString(root.resolve("transcript.jsonl")))
        names <- children(root)
      yield assertTrue(
        all.flatMap(_.key) == Chunk("m1", "m2", "m3", "m4").map(TranscriptKey.Message.apply),
        linked(all),
        all.take(2) == first,
        bytes.linesIterator.size == 4,
        names == Set("transcript.jsonl")
      )
    },
    test(
      "anonymous and blank IDs append again while stable summaries survive reopen exactly once"
    ) {
      val messages = Chunk(
        text(Some("summary"), "prior summary"),
        text(None, "anonymous"),
        text(Some(" "), "blank"),
        text(Some("user"), "latest")
      )
      for
        root <- temporaryDirectory
        _ <- ZIO.foreachDiscard(1 to 2)(_ =>
          ZIO.scoped(
            LocalTranscriptStore
              .open(root, WorkspaceTrust.HostControlled)
              .flatMap(store => new SessionTranscriptWriter(store).offload(messages))
          )
        )
        all <- ZIO.scoped(
          LocalTranscriptStore.open(root, WorkspaceTrust.HostControlled).flatMap(_.entries)
        )
        content <- ZIO.attemptBlocking(Files.readString(root.resolve("transcript.jsonl")))
      yield assertTrue(
        all.size == 6,
        content.linesIterator.size == 6,
        linked(all),
        all.map(_.payload) == Chunk(
          "prior summary",
          "anonymous",
          "blank",
          "latest",
          "anonymous",
          "blank"
        ).map(value => TranscriptPayload.Message(Role.User, value, Chunk(TranscriptBlockType.Text)))
      )
    },
    test("all-duplicate append preserves exact persisted bytes and first-observation payloads") {
      for
        root <- temporaryDirectory
        result <- ZIO.scoped:
          for
            store  <- LocalTranscriptStore.open(root, WorkspaceTrust.HostControlled)
            _      <- store.append(Chunk(draft("same")))
            before <- ZIO.attemptBlocking(Files.readString(root.resolve("transcript.jsonl")))
            appended <- store.append(
              Chunk(
                draft("same").copy(payload =
                  TranscriptPayload.Message(Role.User, "changed", Chunk.empty)
                )
              )
            )
            after <- ZIO.attemptBlocking(Files.readString(root.resolve("transcript.jsonl")))
          yield appended.isEmpty && before == after
      yield assertTrue(result)
    },
    test("independent stores isolate session keys and a live owner excludes competitors") {
      for
        left  <- temporaryDirectory
        right <- temporaryDirectory
        result <- ZIO.scoped:
          for
            first  <- LocalTranscriptStore.open(left, WorkspaceTrust.HostControlled)
            second <- LocalTranscriptStore.open(right, WorkspaceTrust.HostControlled)
            competitor <- ZIO
              .scoped(LocalTranscriptStore.open(left, WorkspaceTrust.HostControlled))
              .either
            a <- first.append(Chunk(draft("same")))
            b <- second.append(Chunk(draft("same")))
          yield competitor.isLeft && a.size == 1 && b.size == 1
      yield assertTrue(result)
    },
    test("escaped handles fail after closure and a new owner can read committed entries") {
      for
        root   <- temporaryDirectory
        closed <- ZIO.scoped(LocalTranscriptStore.open(root, WorkspaceTrust.HostControlled))
        read   <- closed.entries.either
        append <- closed.append(Chunk(draft("wrong"))).either
        reopened <- ZIO.scoped(
          LocalTranscriptStore.open(root, WorkspaceTrust.HostControlled).flatMap(_.entries)
        )
      yield assertTrue(read.isLeft, append.isLeft, reopened.isEmpty)
    },
    test("parallel overlapping batches remain whole and form one continuous persisted chain") {
      for
        root <- temporaryDirectory
        sizes <- ZIO.scoped:
          for
            store <- LocalTranscriptStore.open(root, WorkspaceTrust.HostControlled)
            appended <- ZIO.foreachPar(1 to 8)(offset =>
              store.append(
                Chunk.fromIterable(offset to offset + 12).map(index => draft(index.toString))
              )
            )
          yield appended.map(_.size).sum
        all <- ZIO.scoped(
          LocalTranscriptStore.open(root, WorkspaceTrust.HostControlled).flatMap(_.entries)
        )
      yield assertTrue(
        sizes == 20,
        all.size == 20,
        linked(all),
        all.flatMap(_.key).distinct.size == 20
      )
    },
    test("corrupt input prevents both opening and appending without changing bytes") {
      for
        root <- temporaryDirectory
        result <- ZIO.scoped:
          for
            store  <- LocalTranscriptStore.open(root, WorkspaceTrust.HostControlled)
            _      <- store.append(Chunk(draft("one")))
            before <- ZIO.attemptBlocking(Files.readString(root.resolve("transcript.jsonl")))
            corrupt = before + "{broken\n"
            _ <- ZIO.attemptBlocking(Files.writeString(root.resolve("transcript.jsonl"), corrupt))
            append <- store.append(Chunk(draft("two"))).either
            after  <- ZIO.attemptBlocking(Files.readString(root.resolve("transcript.jsonl")))
          yield append.isLeft && after == corrupt
        reopened <- ZIO
          .scoped(LocalTranscriptStore.open(root, WorkspaceTrust.HostControlled))
          .either
      yield assertTrue(result, reopened.isLeft)
    },
    test("UUID collision rolls back the whole batch and leaves original bytes unchanged") {
      for
        root <- temporaryDirectory
        result <- ZIO.scoped:
          for
            store  <- LocalTranscriptStore.open(root, WorkspaceTrust.HostControlled)
            _      <- TestRandom.setSeed(19L)
            _      <- Random.nextUUID
            _      <- store.append(Chunk(draft("existing")))
            before <- ZIO.attemptBlocking(Files.readString(root.resolve("transcript.jsonl")))
            _      <- TestRandom.setSeed(19L)
            failed <- store.append(Chunk(draft("new"), draft("collision"))).either
            after  <- ZIO.attemptBlocking(Files.readString(root.resolve("transcript.jsonl")))
          yield failed.left.exists(_.isInstanceOf[AgentError.MemoryStorageError]) && before == after
      yield assertTrue(result)
    },
    test("untrusted, traversing and sensitive directories are rejected without ownership files") {
      for
        root      <- temporaryDirectory
        untrusted <- ZIO.scoped(LocalTranscriptStore.open(root, WorkspaceTrust.Untrusted)).either
        _         <- ZIO.attemptBlocking(Files.createDirectory(root.resolve("child")))
        traversal <- ZIO
          .scoped(
            LocalTranscriptStore.open(root.resolve("child/.."), WorkspaceTrust.HostControlled)
          )
          .either
        sensitive <- ZIO.attemptBlocking(Files.createDirectory(root.resolve(".env.private")))
        rejected <- ZIO
          .scoped(LocalTranscriptStore.open(sensitive, WorkspaceTrust.HostControlled))
          .either
        names <- children(root)
      yield assertTrue(
        untrusted.isLeft,
        traversal.isLeft,
        rejected.isLeft,
        names == Set("child", ".env.private")
      )
    },
    test("stale ownership and nonregular payloads remain intact after failed opening") {
      for
        stale <- temporaryDirectory
        _ <- ZIO.attemptBlocking(
          Files.writeString(stale.resolve(".transcript-writer.lease"), "previous-owner")
        )
        blocked <- ZIO
          .scoped(LocalTranscriptStore.open(stale, WorkspaceTrust.HostControlled))
          .either
        token   <- ZIO.attemptBlocking(Files.readString(stale.resolve(".transcript-writer.lease")))
        invalid <- temporaryDirectory
        _       <- ZIO.attemptBlocking(Files.createDirectory(invalid.resolve("transcript.jsonl")))
        rejected <- ZIO
          .scoped(LocalTranscriptStore.open(invalid, WorkspaceTrust.HostControlled))
          .either
        remains <- ZIO.attemptBlocking(Files.isDirectory(invalid.resolve("transcript.jsonl")))
      yield assertTrue(blocked.isLeft, token == "previous-owner", rejected.isLeft, remains)
    },
    test("unicode input and output previews persist without splitting surrogate pairs") {
      val message =
        Msg.tool(Chunk(ContentBlock.ToolResult.text("t", "tool", "x" + "\ud83d\ude00" * 500)))
      val call =
        Msg.assistant(Chunk(ContentBlock.ToolUse("t", "tool", Json.Str("\ud83d\ude00" * 250))))
      for
        root <- temporaryDirectory
        recorded <- ZIO.scoped(
          LocalTranscriptStore
            .open(root, WorkspaceTrust.HostControlled)
            .flatMap(store =>
              new SessionTranscriptWriter(store).appendMessages(Chunk(call, message))
            )
        )
        reopened <- ZIO.scoped(
          LocalTranscriptStore.open(root, WorkspaceTrust.HostControlled).flatMap(_.entries)
        )
      yield assertTrue(
        recorded == reopened,
        recorded.map(_.payload) == Chunk(
          TranscriptPayload
            .ToolUse("t", "tool", TranscriptPreview.Truncated("\"" + "\ud83d\ude00" * 249, 502)),
          TranscriptPayload.ToolResult(
            "t",
            Some("tool"),
            TranscriptPreview.Truncated("x" + "\ud83d\ude00" * 499, 1001),
            Chunk(TranscriptBlockType.Text),
            com.weisgnosis.agents.core.model.ToolResultState.Success
          )
        )
      )
    }
  )
