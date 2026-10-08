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
import com.weisgnosis.agents.core.agent.ReActAgent
import com.weisgnosis.agents.core.client.{LlmClient, TurnOutput}
import com.weisgnosis.agents.core.model.{ContentBlock, MediaSource, Msg, Role, ToolResultState}
import com.weisgnosis.agents.harness.memory.{CompactorConfig, ConversationCompactor}
import com.weisgnosis.agents.harness.storage.*
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

object SessionTranscriptWriterSpec extends ZIOSpecDefault:
  private def text(id: String, value: String): Msg =
    Msg.user(Chunk(ContentBlock.Text(value)), id = Some(id))

  private def backend: UIO[VersionedJsonStore] =
    Ref.Synchronized
      .make(Map.empty[StorageAddress, VersionedJson])
      .map: values =>
        new VersionedJsonStore:
          def read(address: StorageAddress): IO[AgentError, Option[VersionedJson]] =
            values.get.map(_.get(address))
          def compareAndSet(
              address: StorageAddress,
              expected: ExpectedRevision,
              value: Json
          ): IO[AgentError, ConditionalWrite] =
            values.modifyZIO: current =>
              val previous = current.get(address)
              val matches = expected match
                case ExpectedRevision.Absent => previous.isEmpty
                case ExpectedRevision.AtRevision(revision) =>
                  previous.exists(_.revision == revision)
              if !matches then ZIO.succeed((ConditionalWrite.Conflict, current))
              else
                val revision = previous.fold(0L)(_.revision.value)
                if revision == Long.MaxValue then
                  ZIO.fail(AgentError.StorageError("Revision exhausted"))
                else
                  ZIO
                    .fromEither(StorageRevision.fromLong(revision + 1L))
                    .map: next =>
                      (
                        ConditionalWrite.Applied(next),
                        current.updated(address, VersionedJson(value, next))
                      )

  private def selectedStore(
      storage: VersionedJsonStore,
      session: TranscriptSession
  ): IO[AgentError, TranscriptStore] =
    ZIO.fromEither(session.layout match
      case TranscriptLayout.Aggregate => VersionedTranscriptStore.make(storage, session.manifest)
      case TranscriptLayout.Segmented => SegmentedTranscriptStore.make(storage, session.manifest))

  private def registeredContract(layout: TranscriptLayout): ZIO[Scope, AgentError, TestResult] =
    for
      storage  <- backend
      manifest <- ZIO.fromEither(StorageAddress.from(Chunk("transcripts"), "session"))
      index    <- ZIO.fromEither(StorageAddress.from(Chunk("transcripts"), "catalog"))
      session <- ZIO.fromEither(
        TranscriptTargetId
          .fromString("session")
          .flatMap(TranscriptSession.make(_, manifest, layout))
      )
      underlying <- ZIO.fromEither(VersionedTranscriptSessionCatalog.make(storage, index))
      reader     <- selectedStore(storage, session)
      attempts   <- Ref.make(0)
      reject     <- Ref.make(true)
      ordered    <- Ref.make(true)
      failure = AgentError.StorageError("Catalog registration unavailable")
      catalog = new TranscriptSessionCatalog:
        def sessions: IO[AgentError, Chunk[TranscriptSession]] = underlying.sessions
        def register(value: TranscriptSession): IO[AgentError, TranscriptRegistration] =
          for
            entries <- reader.entries
            _       <- ordered.update(_ && entries.nonEmpty && value == session)
            _       <- attempts.update(_ + 1)
            denied  <- reject.get
            result  <- if denied then ZIO.fail(failure) else underlying.register(value)
          yield result
      writer <- ZIO.fromEither(SessionTranscriptWriter.registered(storage, session, catalog))
      before <- attempts.get
      empty  <- writer.appendMessages(Chunk(Msg.user("<session_context>hidden")))
      noRegistration <- attempts.get
      absent         <- storage.read(manifest)
      failed         <- writer.appendMessages(Chunk(text("seed", "preserved"))).either
      preserved      <- reader.entries
      missingIndex   <- underlying.sessions
      failedAttempts <- attempts.get
      _              <- reject.set(false)
      _              <- writer.offload(Chunk(text("seed", "preserved")))
      repaired       <- underlying.sessions
      deduplicated   <- reader.entries
      client = LlmClient.mockFunction((_, _) =>
        TurnOutput.FinalText(Msg.assistant(Chunk(ContentBlock.Text("done")), id = Some("answer")))
      )
      agent <- ReActAgent.make(
        client = client,
        completionObserver = writer.completionObserver(AnonymousTranscriptPolicy.RequireStableIds)
      )
      result        <- agent.run(text("question", "hello"))
      coldCatalog   <- ZIO.fromEither(VersionedTranscriptSessionCatalog.make(storage, index))
      discovered    <- coldCatalog.sessions
      coldHistory   <- ZIO.foreach(discovered)(selectedStore(storage, _).flatMap(_.entries))
      _             <- writer.appendMessages(Chunk(text("seed", "preserved")))
      unchanged     <- reader.entries
      order         <- ordered.get
      totalAttempts <- attempts.get
    yield assertTrue(
      before == 0 && noRegistration == 0 && empty.isEmpty && absent.isEmpty,
      failed == Left(failure) && failedAttempts == 1,
      missingIndex.isEmpty && preserved.size == 1,
      repaired == Chunk(session) && deduplicated == preserved,
      result.reply.text == "done",
      discovered == Chunk(session),
      coldHistory.map(_.flatMap(_.key)) == Chunk(
        Chunk("seed", "question", "answer").map(TranscriptKey.Message.apply)
      ),
      coldHistory == Chunk(unchanged),
      order && totalAttempts == 4
    )

  private def registrationCancellation(
      layout: TranscriptLayout
  ): ZIO[Scope, AgentError, TestResult] =
    for
      storage  <- backend
      manifest <- ZIO.fromEither(StorageAddress.from(Chunk("cancel"), "session"))
      session <- ZIO.fromEither(
        TranscriptTargetId.fromString("cancel").flatMap(TranscriptSession.make(_, manifest, layout))
      )
      entered  <- Promise.make[Nothing, Unit]
      cleaning <- Promise.make[Nothing, Unit]
      release  <- Promise.make[Nothing, Unit]
      defect = new IllegalStateException("Catalog registration cleanup")
      catalog = new TranscriptSessionCatalog:
        def sessions: IO[AgentError, Chunk[TranscriptSession]] = ZIO.succeed(Chunk.empty)
        def register(value: TranscriptSession): IO[AgentError, TranscriptRegistration] =
          ZIO.scoped(
            ZIO.addFinalizer(cleaning.succeed(()) *> release.await *> ZIO.die(defect)) *>
              entered.succeed(()) *> ZIO.never
          )
      writer <- ZIO.fromEither(SessionTranscriptWriter.registered(storage, session, catalog))
      active <- ZIO.uninterruptibleMask: restore =>
        for
          fiber <- restore(
            writer.offload(Chunk(text("committed", "before cancellation")))
          ).forkScoped
          _ <- ZIO.addFinalizer(release.succeed(()).unit)
        yield fiber
      _          <- entered.await
      controller <- ZIO.fiberId
      _          <- active.interruptAsFork(controller)
      _          <- cleaning.await
      held       <- active.poll
      reader     <- selectedStore(storage, session)
      committed  <- reader.entries
      _          <- release.succeed(())
      exit       <- active.await
    yield assertTrue(
      held.isEmpty,
      committed.flatMap(_.key) == Chunk(TranscriptKey.Message("committed")),
      exit.causeOption.exists(cause =>
        cause.interruptors.contains(controller) && cause.defects == List(
          defect
        ) && cause.failures.isEmpty
      )
    )

  def spec: Spec[TestEnvironment & Scope, Any] = suite("SessionTranscriptWriterSpec")(
    test("registered writers publish history before discovery and repair indexing without replay"):
      ZIO
        .foreach(Chunk(TranscriptLayout.Aggregate, TranscriptLayout.Segmented))(registeredContract)
        .map(_.foldLeft(assertTrue(true))(_ && _))
    ,
    test("registered writer cancellation joins catalog cleanup without rolling back history"):
      ZIO
        .foreach(Chunk(TranscriptLayout.Aggregate, TranscriptLayout.Segmented))(
          registrationCancellation
        )
        .map(_.foldLeft(assertTrue(true))(_ && _))
    ,
    test("appendMessages_isIdempotent_andKeepsChain; offload skips a persisted prefix") {
      for
        store <- TranscriptStore.inMemory
        writer = new SessionTranscriptWriter(store)
        _ <- writer.appendMessages(Chunk(text("m1", "one"), text("m2", "two")))
        _ <- writer.offload(Chunk(text("m1", "one"), text("m2", "two"), text("m3", "three")))
        _ <- writer.appendMessages(
          Chunk(text("m2", "two"), text("m3", "three"), text("m4", "four"))
        )
        entries <- store.entries
      yield assertTrue(
        entries.flatMap(_.key) == Chunk("m1", "m2", "m3", "m4").map(TranscriptKey.Message.apply),
        entries
          .map(_.parentId) == (Chunk(None) ++ entries.dropRight(1).map(entry => Some(entry.id)))
      )
    },
    test("deriveEntries_emitsStructuredToolUseAndResult_withPairing") {
      val assistant = Msg.assistant(
        Chunk(
          ContentBlock.Text("calling tools"),
          ContentBlock.ToolUse("t1", "search", Json.Obj("q" -> Json.Str("cats"))),
          ContentBlock.ToolUse("t2", "read", Json.Obj())
        ),
        id = Some("a1")
      )
      val tool = Msg.tool(
        Chunk(ContentBlock.ToolResult.text("t1", "search", "meow", ToolResultState.Error)),
        id = Some("r1")
      )
      val a = SessionTranscriptWriter.project(assistant)
      val r = SessionTranscriptWriter.project(tool)
      assertTrue(
        a.map(_.key) == Chunk(
          Some(TranscriptKey.Message("a1")),
          Some(TranscriptKey.ToolUse("a1", 0)),
          Some(TranscriptKey.ToolUse("a1", 1))
        ),
        a.map(_.payload) == Chunk(
          TranscriptPayload.Message(
            Role.Assistant,
            "calling tools",
            Chunk(
              TranscriptBlockType.Text,
              TranscriptBlockType.ToolUse,
              TranscriptBlockType.ToolUse
            )
          ),
          TranscriptPayload
            .ToolUse("t1", "search", TranscriptPreview.Complete(Json.Obj("q" -> Json.Str("cats")))),
          TranscriptPayload.ToolUse("t2", "read", TranscriptPreview.Complete(Json.Obj()))
        ),
        r.map(_.payload) == Chunk(
          TranscriptPayload.ToolResult(
            "t1",
            Some("search"),
            TranscriptPreview.Complete("meow"),
            Chunk(TranscriptBlockType.Text),
            ToolResultState.Error
          )
        )
      )
    },
    test("deriveEntries_placeholderForImageOnlyMessage and empty messages") {
      val image = SessionTranscriptWriter.project(
        Msg.user(
          Chunk(ContentBlock.Image(MediaSource.url("https://example.test/image"))),
          id = Some("img")
        )
      )
      val empty = SessionTranscriptWriter.project(Msg.assistant())
      assertTrue(
        image.map(_.payload) == Chunk(
          TranscriptPayload.Message(Role.User, "", Chunk(TranscriptBlockType.Image))
        ),
        empty.map(_.payload) == Chunk(
          TranscriptPayload.Message(Role.Assistant, "", Chunk(TranscriptBlockType.Empty))
        )
      )
    },
    test("missing and blank IDs append again without breaking stable IDs") {
      val messages =
        Chunk(text("m1", "one"), Msg.user("anonymous"), text(" ", "blank"), text("m2", "two"))
      for
        store <- TranscriptStore.inMemory
        writer = new SessionTranscriptWriter(store)
        _       <- writer.appendMessages(messages)
        _       <- writer.appendMessages(messages)
        entries <- store.entries
      yield assertTrue(
        entries.size == 6,
        entries.count(_.key.isEmpty) == 4,
        entries.flatMap(_.key) == Chunk(TranscriptKey.Message("m1"), TranscriptKey.Message("m2")),
        entries.map(_.payload) == Chunk("one", "anonymous", "blank", "two", "anonymous", "blank")
          .map(value =>
            TranscriptPayload.Message(Role.User, value, Chunk(TranscriptBlockType.Text))
          ),
        entries
          .map(_.parentId) == (Chunk(None) ++ entries.dropRight(1).map(entry => Some(entry.id)))
      )
    },
    test("retains and deduplicates stable summaries independently of daily extraction") {
      val summary = Msg.user(
        Chunk(ContentBlock.Text("prior summary")),
        id = Some("summary"),
        name = Some(ConversationCompactor.SummaryName)
      )
      for
        store <- TranscriptStore.inMemory
        writer = new SessionTranscriptWriter(store)
        _       <- writer.appendMessages(Chunk(summary, text("m1", "latest")))
        _       <- writer.appendMessages(Chunk(summary, text("m1", "latest")))
        entries <- store.entries
      yield assertTrue(
        entries.size == 2,
        entries
          .flatMap(_.key) == Chunk(TranscriptKey.Message("summary"), TranscriptKey.Message("m1")),
        entries.map(_.payload) == Chunk("prior summary", "latest").map(value =>
          TranscriptPayload.Message(Role.User, value, Chunk(TranscriptBlockType.Text))
        )
      )
    },
    test("thinking and system observations remain visible but user session context is skipped") {
      val messages = Chunk(
        Msg.user("<session_context>internal"),
        Msg.system("<session_context>system"),
        Msg.assistant(Chunk(ContentBlock.Thinking("reasoning"), ContentBlock.Text("visible")))
      )
      val payloads = messages.flatMap(SessionTranscriptWriter.project).map(_.payload)
      assertTrue(
        payloads == Chunk(
          TranscriptPayload
            .Message(Role.System, "<session_context>system", Chunk(TranscriptBlockType.Text)),
          TranscriptPayload.Message(
            Role.Assistant,
            "reasoning\nvisible",
            Chunk(TranscriptBlockType.Thinking, TranscriptBlockType.Text)
          )
        )
      )
    },
    test("repeated tool IDs and delimiter-containing message IDs retain distinct observations") {
      val calls = Msg.assistant(
        Chunk.fill(2)(ContentBlock.ToolUse("same", "tool", Json.Obj())),
        id = Some("a")
      )
      for
        store <- TranscriptStore.inMemory
        writer = new SessionTranscriptWriter(store)
        _       <- writer.appendMessages(Chunk(calls, text("a:use:same", "separate")))
        _       <- writer.appendMessages(Chunk(calls))
        entries <- store.entries
      yield assertTrue(entries.size == 3, entries.flatMap(_.key).distinct.size == 3)
    },
    test("compactor offloads full conversation before summarization") {
      for
        store <- TranscriptStore.inMemory
        writer = new SessionTranscriptWriter(store)
        seen <- Ref.make(Chunk.empty[TranscriptEntry])
        compactor = new ConversationCompactor(
          _ => store.entries.flatMap(seen.set).as("summary"),
          CompactorConfig(
            triggerTokens = 1,
            keepTokens = 0,
            preserveTailMessages = 1,
            protectTokens = 0
          ),
          offload = writer.offload
        )
        history = Chunk(
          text("one", "first"),
          Msg.assistant(Chunk(ContentBlock.Text("second")), id = Some("two")),
          text("three", "third")
        )
        _             <- compactor.compactIfNeeded(history)
        duringSummary <- seen.get
      yield assertTrue(
        duringSummary.flatMap(_.key) == Chunk("one", "two", "three").map(
          TranscriptKey.Message.apply
        )
      )
    },
    test("tool input keeps 500 characters complete and previews 501 characters") {
      val complete  = Json.Str("x" * 498)
      val oversized = Json.Str("x" * 499)
      val numeric = Json.Obj(
        "values" -> Json
          .Arr(Json.Num(Long.MinValue), Json.Num(BigDecimal("-9223372036854775.808"))),
        "padding" -> Json.Str("x" * 500)
      )
      val numericText = """{"values":[-9223372036854775808,-9223372036854775.808],"padding":"""" +
        "x" * 500 + "\"}"
      val observations = Chunk(complete, oversized, numeric)
        .flatMap(input =>
          SessionTranscriptWriter.project(
            Msg.assistant(Chunk(ContentBlock.ToolUse("t", "tool", input)))
          )
        )
        .map(_.payload)
      assertTrue(
        observations == Chunk(
          TranscriptPayload.ToolUse("t", "tool", TranscriptPreview.Complete(complete)),
          TranscriptPayload.ToolUse(
            "t",
            "tool",
            TranscriptPreview.Truncated(oversized.toJson.take(500), 501)
          ),
          TranscriptPayload.ToolUse(
            "t",
            "tool",
            TranscriptPreview.Truncated(numericText.take(500), numericText.length)
          )
        )
      )
    },
    test("joined output keeps 1000 characters complete and previews 1001 with non-text types") {
      val observations = Chunk(500, 501)
        .flatMap(length =>
          SessionTranscriptWriter.project(
            Msg.tool(
              Chunk(
                ContentBlock.ToolResult(
                  "t",
                  output = Chunk(
                    ContentBlock.Text("x" * 499),
                    ContentBlock.Text("y" * length),
                    ContentBlock.Image(MediaSource.url("https://example.test/image"))
                  )
                )
              )
            )
          )
        )
        .map(_.payload)
      val types =
        Chunk(TranscriptBlockType.Text, TranscriptBlockType.Text, TranscriptBlockType.Image)
      val complete = "x" * 499 + "\n" + "y" * 500
      assertTrue(
        observations == Chunk(
          TranscriptPayload.ToolResult(
            "t",
            None,
            TranscriptPreview.Complete(complete),
            types,
            ToolResultState.Success
          ),
          TranscriptPayload.ToolResult(
            "t",
            None,
            TranscriptPreview.Truncated(complete, 1001),
            types,
            ToolResultState.Success
          )
        )
      )
    },
    test("non-text tool observations retain their types without inventing text") {
      val observations = SessionTranscriptWriter
        .project(
          Msg.tool(
            Chunk(
              ContentBlock.ToolResult(
                "t",
                output = Chunk(
                  ContentBlock.Image(MediaSource.url("https://example.test/image"))
                )
              )
            )
          )
        )
        .map(_.payload)
      assertTrue(
        observations == Chunk(
          TranscriptPayload.ToolResult(
            "t",
            None,
            TranscriptPreview.Complete(""),
            Chunk(TranscriptBlockType.Image),
            ToolResultState.Success
          )
        )
      )
    },
    test("cancellation remains interruption and waits for backend cleanup") {
      for
        entered <- Promise.make[Nothing, Unit]
        cleaned <- Ref.make(false)
        store = new TranscriptStore:
          def entries: IO[AgentError, Chunk[TranscriptEntry]] = ZIO.succeed(Chunk.empty)
          def append(drafts: Chunk[TranscriptDraft]): IO[AgentError, Chunk[TranscriptEntry]] =
            (entered.succeed(()) *> ZIO.never).ensuring(cleaned.set(true))
        fiber <- new SessionTranscriptWriter(store).offload(Chunk(text("one", "first"))).forkScoped
        _     <- entered.await
        exit  <- fiber.interrupt
        released <- cleaned.get
      yield assertTrue(exit.isInterrupted, released)
    },
    test("storage errors propagate from the offload callback") {
      val error = AgentError.MemoryStorageError("write failed")
      val store = new TranscriptStore:
        def entries: IO[AgentError, Chunk[TranscriptEntry]] = ZIO.succeed(Chunk.empty)
        def append(drafts: Chunk[TranscriptDraft]): IO[AgentError, Chunk[TranscriptEntry]] =
          ZIO.fail(error)
      new SessionTranscriptWriter(store)
        .offload(Chunk(text("one", "first")))
        .either
        .map(result => assertTrue(result == Left(error)))
    }
  ) @@ TestAspect.timeout(20.seconds)
