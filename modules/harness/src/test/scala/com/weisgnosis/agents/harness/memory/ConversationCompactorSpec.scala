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

import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.model.*
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

object ConversationCompactorSpec extends ZIOSpecDefault:
  private val counter: TokenCounter = new TokenCounter:
    def count(message: Msg): Long = 1L
  private val config: CompactorConfig =
    CompactorConfig(triggerTokens = 2, keepTokens = 2, preserveTailMessages = 2, protectTokens = 0)
  private def summary(text: String): Msg =
    Msg.user(text).withName(ConversationCompactor.SummaryName)
  private def call(id: String): Msg =
    Msg.assistant(Chunk(ContentBlock.ToolUse(id, "search", Json.Obj())))
  private def tool(id: String): Msg = Msg.tool(id, "search", "result")
  private def compactor: ConversationCompactor =
    new ConversationCompactor(_ => ZIO.succeed("summary"), config, counter)

  def spec: Spec[TestEnvironment & Scope, Any] = suite("ConversationCompactorSpec")(
    test("cutoff moves backward to preserve the assistant and all parallel results") {
      val history = Chunk(
        Msg.user("old"),
        Msg.assistant(content =
          Chunk(
            ContentBlock.ToolUse("a", "search", Json.Obj()),
            ContentBlock.ToolUse("b", "search", Json.Obj())
          )
        ),
        tool("a"),
        tool("b"),
        Msg.assistant("done")
      )
      assertTrue(compactor.calculateCutoffIndex(history, config) == 1)
    },
    test("orphaned result blocks move into the summarized prefix") {
      val history =
        Chunk(Msg.user("old"), Msg.assistant("old"), tool("orphan"), Msg.assistant("tail"))
      assertTrue(compactor.calculateCutoffIndex(history, config) == 3)
    },
    test("boundary never severs tool pairs across intervening messages") {
      val history =
        Chunk(Msg.user("old"), call("a"), Msg.user("extra"), tool("a"), Msg.assistant("tail"))
      assertTrue(compactor.calculateCutoffIndex(history, config) == 1)
    },
    test("reused call IDs do not pull completed older turns into the retained tail") {
      val history = Chunk(
        call("same"),
        tool("same"),
        Msg.user("next"),
        call("same"),
        tool("same"),
        Msg.assistant("done")
      )
      assertTrue(compactor.calculateCutoffIndex(history, config) == 3)
    },
    test("duplicate call occurrences retain every result across intervening messages") {
      val use = ContentBlock.ToolUse("same", "search", Json.Obj())
      val history = Chunk(
        Msg.assistant(Chunk(use, use)),
        tool("same"),
        Msg.user("extra"),
        tool("same"),
        Msg.assistant("tail")
      )
      assertTrue(
        compactor
          .calculateCutoffIndex(history, config.copy(keepTokens = 3, preserveTailMessages = 3)) == 0
      )
    },
    test(
      "named results consume the matching occurrence even when identical IDs complete in reverse order"
    ) {
      val history = Chunk(
        Msg.assistant(Chunk(ContentBlock.ToolUse("same", "first", Json.Obj()))),
        Msg.assistant(Chunk(ContentBlock.ToolUse("same", "second", Json.Obj()))),
        Msg.tool("same", "second", "result"),
        Msg.user("extra"),
        Msg.tool("same", "first", "result"),
        Msg.assistant("tail")
      )
      assertTrue(
        compactor.calculateCutoffIndex(
          history,
          config.copy(keepTokens = 3, preserveTailMessages = 3)
        ) == 0
      )
    },
    test("unnamed results consume one FIFO occurrence rather than every reused ID") {
      val use = ContentBlock.ToolUse("same", "search", Json.Obj())
      val unnamed =
        Msg.tool(Chunk(ContentBlock.ToolResult("same", None, Chunk(ContentBlock.Text("result")))))
      val history = Chunk(
        Msg.assistant(Chunk(use, use)),
        unnamed,
        Msg.user("extra"),
        unnamed,
        Msg.assistant("tail")
      )
      assertTrue(
        compactor.calculateCutoffIndex(
          history,
          config.copy(keepTokens = 3, preserveTailMessages = 3)
        ) == 0
      )
    },
    test("inline provider results cannot consume same-message pending local calls") {
      val local  = ContentBlock.ToolUse("same", "search", Json.Obj())
      val server = local.withServerTool(true)
      val inline = ContentBlock.ToolResult.text("same", "search", "inline")
      val history = Chunk(
        Msg.assistant(Chunk(local, server, inline)),
        Msg.user("one"),
        Msg.user("two"),
        tool("same"),
        Msg.assistant("tail")
      )
      assertTrue(
        compactor.calculateCutoffIndex(
          history,
          config.copy(keepTokens = 3, preserveTailMessages = 3)
        ) == 0
      )
    },
    test("later inline results cannot consume an older unresolved local occurrence") {
      val server = ContentBlock.ToolUse("same", "search", Json.Obj()).withServerTool(true)
      val history = Chunk(
        call("same"),
        Msg.assistant(Chunk(server, ContentBlock.ToolResult.text("same", "search", "inline"))),
        Msg.user("extra"),
        tool("same"),
        Msg.assistant("tail")
      )
      assertTrue(
        compactor.calculateCutoffIndex(
          history,
          config.copy(keepTokens = 3, preserveTailMessages = 3)
        ) == 0
      )
    },
    test("unmatched provider calls use local name-sensitive result matching") {
      val server = ContentBlock.ToolUse("same", "provider", Json.Obj()).withServerTool(true)
      val local  = ContentBlock.ToolUse("same", "local", Json.Obj())
      val history = Chunk(
        Msg.assistant(Chunk(server, local)),
        Msg.tool("same", "local", "result"),
        Msg.user("extra"),
        Msg.tool("same", "provider", "result"),
        Msg.assistant("tail")
      )
      assertTrue(
        compactor.calculateCutoffIndex(
          history,
          config.copy(keepTokens = 3, preserveTailMessages = 3)
        ) == 0
      )
    },
    test(
      "argument pruning clears cached content while preserving tool identity state and metadata"
    ) {
      val use = ContentBlock.ToolUse(
        "large",
        "search",
        Json.Str("x" * 3000),
        Some("stale arguments"),
        Map("audit" -> Json.Str("kept")),
        ToolCallState.Finished
      )
      val pruning = new ConversationCompactor(
        _ => ZIO.succeed("unused"),
        config.copy(keepTokens = 100),
        counter
      )
      for result <- pruning.compactIfNeeded(
          Chunk(
            Msg.assistant(Chunk(use)),
            tool("large"),
            Msg.assistant("recent"),
            Msg.assistant("tail")
          )
        )
      yield assertTrue(
        result
          .flatMap(_.toolUses)
          .exists(value =>
            value.input != use.input && value.content.isEmpty &&
              value.id == use.id && value.name == use.name && value.state == use.state && value.metadata == use.metadata
          )
      )
    },
    test(
      "signed and opaque reasoning metadata protect original arguments and cached content from pruning"
    ) {
      val fields = Chunk(
        Map("thoughtSignature" -> Json.Str("signature")),
        Map("thoughtSignature" -> Json.Bool(true)),
        Map("thoughtSignature" -> Json.Null),
        Map("reasoningDetails" -> Json.Arr(Json.Str("opaque"))),
        Map("reasoningDetails" -> Json.Null)
      )
      val pruning = new ConversationCompactor(
        _ => ZIO.succeed("unused"),
        config.copy(keepTokens = 100),
        counter
      )
      for results <- ZIO.foreach(fields)(metadata =>
          val use = ContentBlock.ToolUse(
            "large",
            "search",
            Json.Str("x" * 3000),
            Some("original raw arguments"),
            metadata
          )
          pruning
            .compactIfNeeded(
              Chunk(
                Msg.assistant(Chunk(use)),
                tool("large"),
                Msg.assistant("recent"),
                Msg.assistant("tail")
              )
            )
            .map(_.flatMap(_.toolUses) == Chunk(use))
        )
      yield assertTrue(results.forall(identity))
    },
    test("empty and below-threshold histories do not call the summarizer") {
      val failing = new ConversationCompactor(
        _ => ZIO.fail(AgentError.ModelError(500, "must not run")),
        config,
        counter
      )
      for
        empty     <- failing.compactIfNeeded(Chunk.empty)
        unchanged <- failing.compactIfNeeded(Chunk(Msg.user("one")))
      yield assertTrue(empty.isEmpty, unchanged.map(_.text) == Chunk("one"))
    },
    test("compactIfNeeded_routesPriorSummaryToSummaryInputButNotFlushOrTail") {
      val previous = summary("S1")
      val prefix   = Msg.assistant("M2")
      val tail     = Chunk(Msg.assistant("TAIL_1"), Msg.assistant("TAIL_2"))
      for
        inputs  <- Ref.make(Chunk.empty[Chunk[Msg]])
        flushed <- Ref.make(Chunk.empty[Chunk[Msg]])
        compactor = new ConversationCompactor(
          messages => inputs.update(_ :+ messages).as("replacement"),
          config,
          counter,
          messages => flushed.update(_ :+ messages)
        )
        result  <- compactor.compactIfNeeded(Chunk(previous, prefix) ++ tail)
        prompts <- inputs.get
        flushes <- flushed.get
      yield assertTrue(
        prompts == Chunk(Chunk(previous, prefix)),
        flushes == Chunk(Chunk(prefix)),
        result == Chunk(summary("replacement")) ++ tail
      )
    },
    test("compactIfNeeded_mergesMultiplePriorSummariesIntoOneReplacement") {
      val prefix = Chunk(summary("S0"), Msg.assistant("M1"), summary("S1"), Msg.assistant("M2"))
      val tail   = Chunk(Msg.assistant("TAIL_1"), Msg.assistant("TAIL_2"))
      for
        inputs  <- Ref.make(Chunk.empty[Chunk[Msg]])
        flushed <- Ref.make(Chunk.empty[Chunk[Msg]])
        compactor = new ConversationCompactor(
          messages => inputs.update(_ :+ messages).as("replacement"),
          config,
          counter,
          messages => flushed.update(_ :+ messages)
        )
        result  <- compactor.compactIfNeeded(prefix ++ tail)
        prompts <- inputs.get
        flushes <- flushed.get
      yield assertTrue(
        prompts == Chunk(prefix),
        flushes == Chunk(Chunk(Msg.assistant("M1"), Msg.assistant("M2"))),
        result == Chunk(summary("replacement")) ++ tail
      )
    },
    test("compactIfNeeded_summaryOnlyPrefixDoesNotBecomeEmptyHistory") {
      val previous = summary("ONLY_OLD_SUMMARY")
      val tail     = Chunk(Msg.assistant("TAIL_1"), Msg.assistant("TAIL_2"))
      for
        inputs  <- Ref.make(Chunk.empty[Chunk[Msg]])
        flushed <- Ref.make(Chunk.empty[Chunk[Msg]])
        compactor = new ConversationCompactor(
          messages => inputs.update(_ :+ messages).as("replacement"),
          config,
          counter,
          messages => flushed.update(_ :+ messages)
        )
        result  <- compactor.compactIfNeeded(Chunk(previous) ++ tail)
        prompts <- inputs.get
        flushes <- flushed.get
      yield assertTrue(
        prompts == Chunk(Chunk(previous)),
        flushes == Chunk(Chunk.empty[Msg]),
        result == Chunk(summary("replacement")) ++ tail
      )
    },
    test("compactIfNeeded_chainedCompactionUsesPreviousSummaryAndNewPrefix") {
      val system     = Msg.system("system")
      val firstTail  = Chunk(Msg.assistant("TAIL_1"), Msg.assistant("TAIL_2"))
      val secondTail = Chunk(Msg.assistant("TAIL_3"), Msg.assistant("TAIL_4"))
      for
        rounds  <- Ref.make(0)
        inputs  <- Ref.make(Chunk.empty[Chunk[Msg]])
        flushed <- Ref.make(Chunk.empty[Chunk[Msg]])
        compactor = new ConversationCompactor(
          messages =>
            inputs.update(_ :+ messages) *> rounds.updateAndGet(_ + 1).map(n => s"summary $n"),
          config,
          counter,
          messages => flushed.update(_ :+ messages)
        )
        first <- compactor.compactIfNeeded(
          Chunk(system, summary("S1"), Msg.assistant("M2")) ++ firstTail
        )
        second  <- compactor.compactIfNeeded(first ++ Chunk(Msg.assistant("M3")) ++ secondTail)
        prompts <- inputs.get
        flushes <- flushed.get
      yield assertTrue(
        prompts == Chunk(
          Chunk(summary("S1"), Msg.assistant("M2")),
          Chunk(summary("summary 1")) ++ firstTail ++ Chunk(Msg.assistant("M3"))
        ),
        flushes == Chunk(Chunk(Msg.assistant("M2")), firstTail ++ Chunk(Msg.assistant("M3"))),
        first == Chunk(system, summary("summary 1")) ++ firstTail,
        second == Chunk(system, summary("summary 2")) ++ secondTail
      )
    },
    test("compactIfNeeded_skipsFlushWhenDisabledAndStillUsesPriorSummary") {
      val prefix = Chunk(summary("S1"), Msg.assistant("M2"))
      val tail   = Chunk(Msg.assistant("TAIL_1"), Msg.assistant("TAIL_2"))
      for
        inputs <- Ref.make(Chunk.empty[Chunk[Msg]])
        compactor = new ConversationCompactor(
          messages => inputs.update(_ :+ messages).as("replacement"),
          config,
          counter
        )
        result  <- compactor.compactIfNeeded(prefix ++ tail)
        prompts <- inputs.get
      yield assertTrue(prompts == Chunk(prefix), result == Chunk(summary("replacement")) ++ tail)
    },
    test("summaries in the retained suffix are summarized again and removed from the tail") {
      for
        inputs  <- Ref.make(Chunk.empty[Msg])
        flushed <- Ref.make(Chunk.empty[Msg])
        compactor = new ConversationCompactor(
          messages => inputs.set(messages).as("replacement"),
          config,
          counter,
          messages => flushed.set(messages)
        )
        result <- compactor.compactIfNeeded(
          Chunk(summary("S0"), Msg.assistant("old"), summary("S1"), Msg.assistant("tail"))
        )
        prompt <- inputs.get
        flush  <- flushed.get
      yield assertTrue(
        prompt == Chunk(summary("S0"), Msg.assistant("old"), summary("S1")),
        flush == Chunk(Msg.assistant("old")),
        result == Chunk(summary("replacement"), Msg.assistant("tail"))
      )
    },
    test("summary and consecutive user messages are coalesced without losing content") {
      for result <- compactor.compactIfNeeded(
          Chunk(Msg.assistant("old"), Msg.user("first"), Msg.user("second"))
        )
      yield assertTrue(
        result.size == 1,
        result.headOption.exists(_.text == "summary\nfirst\nsecond")
      )
    },
    test("coalesced retained users reach the next memory flush exactly once") {
      val important = Msg.user("important retained fact").withId(Some("user-id"))
      for
        flushed <- Ref.make(Chunk.empty[Chunk[Msg]])
        compactor = new ConversationCompactor(
          _ => ZIO.succeed("summary"),
          config,
          counter,
          messages => flushed.update(_ :+ messages)
        )
        first <- compactor.compactIfNeeded(
          Chunk(Msg.assistant("old"), important, Msg.assistant("tail"))
        )
        _ <- compactor.compactIfNeeded(first ++ Chunk(Msg.assistant("new"), Msg.assistant("last")))
        observed <- flushed.get
      yield assertTrue(observed.flatten.count(_ == important) == 1)
    },
    test("coalesced user provenance survives serialization and rejects inconsistent content") {
      val user = Msg.user("retained user")
      val malformed = summary("unrelated").withMetadata(
        ConversationCompactor.RetainedUsersKey,
        Json.Str(Chunk(user).toJson)
      )
      for
        first <- compactor.compactIfNeeded(Chunk(Msg.assistant("old"), user, Msg.assistant("tail")))
        decoded  <- ZIO.fromEither(first.toJson.fromJson[Chunk[Msg]])
        restored <- compactor.compactIfNeeded(decoded)
        rejected <- compactor.compactIfNeeded(Chunk(malformed)).either
      yield assertTrue(restored == first, rejected.isLeft)
    },
    test("below-threshold histories preserve oversized outputs and arguments exactly") {
      val long    = "x" * 3000
      val history = Chunk(call("a"), Msg.tool("a", "search", long), Msg.assistant("tail"))
      val compactor = new ConversationCompactor(
        _ => ZIO.succeed("unused"),
        config.copy(triggerTokens = 100),
        counter
      )
      for result <- compactor.compactIfNeeded(history)
      yield assertTrue(result == history)
    },
    test("older outputs and arguments are pruned but the protected tail is unchanged") {
      val long = "x" * 3000
      val history = Chunk(
        Msg.assistant(Chunk(ContentBlock.ToolUse("a", "write_file", Json.Str(long)))),
        Msg.tool("a", "search", long),
        Msg.assistant("recent"),
        Msg.tool("recent", "search", long)
      )
      val pruning = new ConversationCompactor(
        _ => ZIO.succeed("unused"),
        config.copy(triggerTokens = 2, keepTokens = 100),
        counter
      )
      for result <- pruning.compactIfNeeded(history)
      yield assertTrue(
        result.headOption.exists(_.toolUses.exists(_.input != Json.Str(long))),
        result.drop(1).headOption.exists(_.toolResults.exists(_.text.contains("pruned"))),
        result.lastOption == history.lastOption
      )
    },
    test("typed flush failure stops offloading and summarization") {
      val error = AgentError.ModelError(500, "flush failed")
      for
        operations <- Ref.make(Chunk.empty[String])
        compactor = new ConversationCompactor(
          _ => operations.update(_ :+ "summary").as("summary"),
          config,
          counter,
          _ => operations.update(_ :+ "flush") *> ZIO.fail(error),
          _ => operations.update(_ :+ "offload")
        )
        result   <- compactor.compactIfNeeded(Chunk.fill(3)(Msg.assistant("old"))).either
        observed <- operations.get
      yield assertTrue(result == Left(error), observed == Chunk("flush"))
    },
    test("compactIfNeeded_propagatesInterruptedMemoryFlush") {
      for
        operations <- Ref.make(Chunk.empty[String])
        compactor = new ConversationCompactor(
          _ => operations.update(_ :+ "summary").as("summary"),
          config,
          counter,
          _ => operations.update(_ :+ "flush") *> ZIO.interrupt,
          _ => operations.update(_ :+ "offload")
        )
        exit     <- compactor.compactIfNeeded(Chunk.fill(3)(Msg.assistant("old"))).exit
        observed <- operations.get
      yield assertTrue(exit.isInterrupted, observed == Chunk("flush"))
    },
    test("compactIfNeeded_propagatesInterruptedMessageOffload") {
      for
        operations <- Ref.make(Chunk.empty[String])
        compactor = new ConversationCompactor(
          _ => operations.update(_ :+ "summary").as("summary"),
          config,
          counter,
          _ => operations.update(_ :+ "flush"),
          _ => operations.update(_ :+ "offload") *> ZIO.interrupt
        )
        exit     <- compactor.compactIfNeeded(Chunk.fill(3)(Msg.assistant("old"))).exit
        observed <- operations.get
      yield assertTrue(exit.isInterrupted, observed == Chunk("flush", "offload"))
    }
  )
