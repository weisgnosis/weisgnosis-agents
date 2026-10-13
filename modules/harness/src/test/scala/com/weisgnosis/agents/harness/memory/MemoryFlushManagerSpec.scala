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
package com.weisgnosis.agents.harness.memory

import com.weisgnosis.agents.core.json.JsonBoundary
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.model.*
import java.time.{Instant, LocalDate, ZoneOffset}
import zio.*
import zio.json.ast.Json
import zio.test.*

object MemoryFlushManagerSpec extends ZIOSpecDefault:
  final private case class RecordingStore(
      reads: Ref[Chunk[String]],
      entries: Ref[Chunk[(LocalDate, String)]],
      curated: Option[String] = None,
      daily: Option[String] = None
  ) extends DailyMemoryStore:
    def readCurated: IO[AgentError, Option[String]] = reads.update(_ :+ "curated").as(curated)
    def readDaily(day: LocalDate): IO[AgentError, Option[String]] =
      reads.update(_ :+ day.toString) *> entries.get.map(written =>
        daily.orElse(Option.when(written.nonEmpty)(written.map(_._2).mkString))
      )
    def appendDaily(day: LocalDate, entry: String): IO[AgentError, Unit] =
      entries.update(_ :+ (day -> entry))

  private def recording: UIO[RecordingStore] =
    Ref
      .make(Chunk.empty[String])
      .zipWith(Ref.make(Chunk.empty[(LocalDate, String)]))(RecordingStore(_, _))
  private def summary(text: String): Msg =
    Msg.user(text).withName(ConversationCompactor.SummaryName)
  private val history: Chunk[Msg] = Chunk(Msg.user("real follow-up"))

  def spec: Spec[TestEnvironment & Scope, Any] = suite("MemoryFlushManagerSpec")(
    test("extraction input truncates tool arguments and output at Unicode boundaries") {
      val emoji     = "\uD83D\uDE00"
      val arguments = Json.Str("a" * 498 + emoji + "rest")
      val output    = "b" * 999 + emoji + "rest"
      for
        store  <- recording
        inputs <- Ref.make(Chunk.empty[Msg])
        manager <- MemoryFlushManager.make(
          store,
          MemoryExtractor(input => inputs.set(input).as("NO_REPLY"))
        )
        _ <- manager.flush(
          Chunk(
            Msg.assistant(Chunk(ContentBlock.ToolUse("unicode", "search", arguments))),
            Msg.tool("unicode", "search", output)
          )
        )
        observed <- inputs.get
      yield assertTrue(
        observed.forall(message => JsonBoundary.validUnicode(Json.Str(message.text))),
        observed.lastOption.exists(_.text.contains("(\"" + "a" * 498 + "...)")),
        observed.lastOption.exists(
          _.text.contains("[tool_result: search] " + "b" * 999 + "...(truncated)")
        )
      )
    },
    test("flushMemories_skipsCompactionSummariesWhenRealMessagesRemain") {
      for
        store  <- recording
        inputs <- Ref.make(Chunk.empty[Chunk[Msg]])
        manager <- MemoryFlushManager.make(
          store,
          MemoryExtractor(input => inputs.update(_ :+ input).as("NO_REPLY"))
        )
        _ <- manager.flush(Chunk(summary("internal summary should not be flushed")) ++ history)
        observed <- inputs.get
        entries  <- store.entries.get
      yield assertTrue(
        observed.size == 1,
        observed.headOption.exists(_.map(_.role) == Chunk(Role.System, Role.User)),
        observed.headOption.flatMap(_.lastOption).exists(_.text.contains("real follow-up")),
        !observed.flatten.exists(_.text.contains("internal summary should not be flushed")),
        entries.isEmpty
      )
    },
    test("flushMemories_summaryOnlyInputDoesNotCallModel") {
      for
        store <- recording
        calls <- Ref.make(0)
        manager <- MemoryFlushManager
          .make(store, MemoryExtractor(_ => calls.update(_ + 1).as("wrong")))
        _       <- manager.flush(Chunk(summary("internal only")))
        count   <- calls.get
        reads   <- store.reads.get
        entries <- store.entries.get
      yield assertTrue(count == 0, reads.isEmpty, entries.isEmpty)
    },
    test(
      "reads curated and dated context and appends only new output after extraction scope closes"
    ) {
      val instant = Instant.parse("2026-09-29T10:00:00Z")
      for
        _     <- TestClock.setTime(instant)
        empty <- recording
        store = empty.copy(curated = Some("curated fact"), daily = Some("earlier daily fact"))
        inputs <- Ref.make(Chunk.empty[Chunk[Msg]])
        closed <- Ref.make(false)
        checkedStore = new DailyMemoryStore:
          def readCurated: IO[AgentError, Option[String]]               = store.readCurated
          def readDaily(day: LocalDate): IO[AgentError, Option[String]] = store.readDaily(day)
          def appendDaily(day: LocalDate, entry: String): IO[AgentError, Unit] =
            closed.get.flatMap(done =>
              if done then store.appendDaily(day, entry)
              else ZIO.fail(AgentError.InvalidMessage("Extraction scope still open"))
            )
        manager <- MemoryFlushManager.make(
          checkedStore,
          MemoryExtractor(input =>
            ZIO.addFinalizer(closed.set(true)) *> inputs.update(_ :+ input).as("- new fact")
          ),
          prompt = "custom extraction rules"
        )
        _        <- manager.flush(history)
        observed <- inputs.get
        entries  <- store.entries.get
        reads    <- store.reads.get
      yield assertTrue(
        observed.headOption.flatMap(_.headOption).contains(Msg.system("custom extraction rules")),
        observed.headOption
          .flatMap(_.lastOption)
          .exists(message =>
            message.text.contains("curated fact") && message.text.contains("earlier daily fact") &&
              message.text.contains("[USER]: real follow-up")
          ),
        reads == Chunk("curated", "2026-09-29"),
        entries == Chunk(
          LocalDate.of(2026, 9, 29) -> "\n## Memory Flush - 2026-09-29T10:00:00Z\n- new fact\n"
        )
      )
    },
    test("blank and exact trimmed NO_REPLY responses never append") {
      for results <- ZIO.foreach(Chunk("", " \n\t", "NO_REPLY", " \nNO_REPLY\t")): output =>
          for
            store   <- recording
            manager <- MemoryFlushManager.make(store, MemoryExtractor(_ => ZIO.succeed(output)))
            _       <- manager.flush(history)
            entries <- store.entries.get
          yield entries.isEmpty
      yield assertTrue(results.forall(identity))
    },
    test("NO_REPLY within a substantive response is preserved verbatim") {
      for
        store <- recording
        manager <- MemoryFlushManager
          .make(store, MemoryExtractor(_ => ZIO.succeed("- NO_REPLY is a sentinel")))
        _       <- manager.flush(history)
        entries <- store.entries.get
      yield assertTrue(
        entries.size == 1,
        entries.headOption.exists(_._2.contains("- NO_REPLY is a sentinel"))
      )
    },
    test("one instant and explicit zone bind reads and append across midnight") {
      val start = Instant.parse("2026-09-29T18:59:59Z")
      for
        _       <- TestClock.setTime(start)
        store   <- recording
        entered <- Promise.make[Nothing, Unit]
        release <- Promise.make[Nothing, Unit]
        manager <- MemoryFlushManager.make(
          store,
          MemoryExtractor(_ => entered.succeed(()) *> release.await.as("- new fact")),
          zone = ZoneOffset.ofHours(5)
        )
        fiber   <- manager.flush(history).forkScoped
        _       <- entered.await
        _       <- TestClock.adjust(2.seconds)
        _       <- release.succeed(())
        _       <- fiber.join
        entries <- store.entries.get
        reads   <- store.reads.get
      yield assertTrue(
        reads == Chunk("curated", "2026-09-29"),
        entries.map(_._1) == Chunk(LocalDate.of(2026, 9, 29)),
        entries.headOption.exists(_._2.contains(start.toString))
      )
    },
    test("coalesced user provenance is extracted once without summary text") {
      val counter = new TokenCounter:
        def count(message: Msg): Long = 1L
      val compactor = new ConversationCompactor(
        _ => ZIO.succeed("internal summary"),
        CompactorConfig(
          triggerTokens = 2,
          keepTokens = 2,
          preserveTailMessages = 2,
          protectTokens = 0
        ),
        counter
      )
      for
        coalesced <- compactor.compactIfNeeded(
          Chunk(Msg.assistant("old"), Msg.user("retained fact"), Msg.assistant("tail"))
        )
        store  <- recording
        inputs <- Ref.make(Chunk.empty[Msg])
        manager <- MemoryFlushManager.make(
          store,
          MemoryExtractor(input => inputs.set(input).as("NO_REPLY"))
        )
        _        <- manager.flush(coalesced)
        observed <- inputs.get
      yield assertTrue(
        observed.lastOption.exists(message =>
          message.text.endsWith("[USER]: retained fact\n[ASSISTANT]: tail") && !message.text
            .contains("internal summary")
        )
      )
    },
    test("invalid coalesced provenance fails before storage or extraction") {
      for
        store <- recording
        calls <- Ref.make(0)
        manager <- MemoryFlushManager.make(
          store,
          MemoryExtractor(_ => calls.update(_ + 1).as("wrong"))
        )
        result <- manager
          .flush(
            Chunk(
              summary("invalid").withMetadata(ConversationCompactor.RetainedUsersKey, Json.Num(1))
            )
          )
          .either
        count <- calls.get
        reads <- store.reads.get
      yield assertTrue(
        result.left.exists(_.isInstanceOf[AgentError.DeserializationError]),
        count == 0,
        reads.isEmpty
      )
    },
    test("internal and nonvisible blocks are excluded while tool text has exact preview bounds") {
      val input  = "x" * 600
      val output = "y" * 1100
      val numeric = Json.Obj(
        "values" -> Json.Arr(Json.Num(Long.MinValue), Json.Num(BigDecimal("-9223372036854775.808")))
      )
      val numericText = """{"values":[-9223372036854775808,-9223372036854775.808]}"""
      val messages = Chunk(
        Msg.system("system secret"),
        Msg.user("<session_context>internal context"),
        Msg.assistant(Chunk(ContentBlock.Thinking("thinking secret"))),
        Msg.assistant(Chunk(ContentBlock.ToolUse("numeric", "read", numeric))),
        Msg.assistant(Chunk(ContentBlock.ToolUse("id", "read", Json.Str(input)))),
        Msg.tool("id", "read", output)
      )
      for
        store  <- recording
        inputs <- Ref.make(Chunk.empty[Msg])
        manager <- MemoryFlushManager.make(
          store,
          MemoryExtractor(input => inputs.set(input).as("NO_REPLY"))
        )
        _        <- manager.flush(messages)
        observed <- inputs.get
      yield assertTrue(
        observed.lastOption.exists(message =>
          message.text.endsWith(
            s"[ASSISTANT]: [tool_call: read($numericText)]\n[ASSISTANT]: [tool_call: read(\"${"x" * 499}...)]\n[TOOL]: [tool_result: read] ${"y" * 1000}...(truncated)"
          ) &&
            !message.text.contains("system secret") && !message.text.contains(
              "internal context"
            ) && !message.text.contains("thinking secret")
        )
      )
    },
    test("extraction failure closes scope and leaves the ledger unchanged") {
      val error = AgentError.ModelError(503, "unavailable")
      for
        store  <- recording
        closed <- Ref.make(false)
        manager <- MemoryFlushManager.make(
          store,
          MemoryExtractor(_ => ZIO.addFinalizer(closed.set(true)) *> ZIO.fail(error))
        )
        result   <- manager.flush(history).either
        released <- closed.get
        entries  <- store.entries.get
      yield assertTrue(result == Left(error), released, entries.isEmpty)
    },
    test("cancellation closes extraction scope and permits a later flush") {
      for
        store   <- recording
        entered <- Promise.make[Nothing, Unit]
        calls   <- Ref.make(0)
        closed  <- Ref.make(false)
        manager <- MemoryFlushManager.make(
          store,
          MemoryExtractor(_ =>
            calls
              .getAndUpdate(_ + 1)
              .flatMap(index =>
                if index == 0 then
                  ZIO.addFinalizer(closed.set(true)) *> entered.succeed(()) *> ZIO.never
                else ZIO.succeed("- later fact")
              )
          )
        )
        fiber    <- manager.flush(history).forkScoped
        _        <- entered.await
        exit     <- fiber.interrupt
        before   <- store.entries.get
        released <- closed.get
        _        <- manager.flush(history)
        after    <- store.entries.get
      yield assertTrue(exit.isInterrupted, released, before.isEmpty, after.size == 1)
    },
    test("cleanup defects prevent a successful extraction from being appended") {
      val defect = new IllegalStateException("cleanup failed")
      for
        store <- recording
        manager <- MemoryFlushManager.make(
          store,
          MemoryExtractor(_ => ZIO.addFinalizer(ZIO.die(defect)).as("- fact"))
        )
        exit    <- manager.flush(history).exit
        entries <- store.entries.get
      yield assertTrue(exit.causeOption.exists(_.defects.contains(defect)), entries.isEmpty)
    },
    test("storage read and append errors propagate without silent fallback or retry") {
      val error = AgentError.InvalidMessage("storage failed")
      for
        reads       <- Ref.make(0)
        appends     <- Ref.make(0)
        extractions <- Ref.make(0)
        readFailure = new DailyMemoryStore:
          def readCurated: IO[AgentError, Option[String]] = reads.update(_ + 1) *> ZIO.fail(error)
          def readDaily(day: LocalDate): IO[AgentError, Option[String]] =
            reads.update(_ + 1).as(None)
          def appendDaily(day: LocalDate, entry: String): IO[AgentError, Unit] =
            appends.update(_ + 1)
        appendFailure = new DailyMemoryStore:
          def readCurated: IO[AgentError, Option[String]]               = ZIO.none
          def readDaily(day: LocalDate): IO[AgentError, Option[String]] = ZIO.none
          def appendDaily(day: LocalDate, entry: String): IO[AgentError, Unit] =
            appends.update(_ + 1) *> ZIO.fail(error)
        extractor = MemoryExtractor(_ => extractions.update(_ + 1).as("- fact"))
        first           <- MemoryFlushManager.make(readFailure, extractor)
        readResult      <- first.flush(history).either
        readCalls       <- reads.get
        before          <- extractions.get
        second          <- MemoryFlushManager.make(appendFailure, extractor)
        appendResult    <- second.flush(history).either
        appendCalls     <- appends.get
        extractionCalls <- extractions.get
      yield assertTrue(
        readResult == Left(error),
        appendResult == Left(error),
        readCalls == 1,
        before == 0,
        extractionCalls == 1,
        appendCalls == 1
      )
    },
    test("concurrent flushes on one manager observe the preceding committed daily entry") {
      for
        store   <- recording
        entered <- Promise.make[Nothing, Unit]
        release <- Promise.make[Nothing, Unit]
        calls   <- Ref.make(0)
        inputs  <- Ref.make(Chunk.empty[Chunk[Msg]])
        manager <- MemoryFlushManager.make(
          store,
          MemoryExtractor(input =>
            inputs.update(_ :+ input) *> calls
              .getAndUpdate(_ + 1)
              .flatMap(index =>
                if index == 0 then entered.succeed(()) *> release.await.as("- first fact")
                else ZIO.succeed("- second fact")
              )
          )
        )
        first          <- manager.flush(history).forkScoped
        _              <- entered.await
        second         <- manager.flush(history).forkScoped
        _              <- TestClock.adjust(0.seconds)
        blockedCalls   <- calls.get
        blockedReads   <- store.reads.get
        blockedEntries <- store.entries.get
        _              <- release.succeed(())
        _              <- first.join *> second.join
        observed       <- inputs.get
        entries        <- store.entries.get
      yield assertTrue(
        blockedCalls == 1,
        blockedReads.size == 2,
        blockedEntries.isEmpty,
        observed.size == 2,
        entries.size == 2,
        observed.lastOption.flatMap(_.lastOption).exists(_.text.contains("- first fact"))
      )
    }
  )
