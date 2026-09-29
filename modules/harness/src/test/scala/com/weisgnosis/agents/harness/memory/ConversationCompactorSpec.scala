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
    test("prior summaries reach summarization but never flush or the retained tail") {
      for
        inputs  <- Ref.make(Chunk.empty[Msg])
        flushed <- Ref.make(Chunk.empty[Msg])
        compactor = new ConversationCompactor(
          messages => inputs.set(messages).as("new summary"),
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
        prompt.map(_.text).toSet == Set("S0", "S1", "old"),
        flush.map(_.text) == Chunk("old"),
        result.count(ConversationCompactor.isSummary) == 1,
        result.lastOption.exists(_.text == "tail")
      )
    },
    test("a summary-only prefix still produces a replacement summary") {
      for result <- compactor.compactIfNeeded(
          Chunk(summary("old summary"), Msg.assistant("tail1"), Msg.assistant("tail2"))
        )
      yield assertTrue(result.size == 3, result.headOption.exists(_.text == "summary"))
    },
    test("chained compaction replaces the previous summary and preserves system prompts") {
      for
        rounds <- Ref.make(0)
        inputs <- Ref.make(Chunk.empty[Chunk[Msg]])
        compactor = new ConversationCompactor(
          messages =>
            inputs.update(_ :+ messages) *> rounds.updateAndGet(_ + 1).map(n => s"summary $n"),
          config,
          counter
        )
        first <- compactor.compactIfNeeded(
          Chunk(
            Msg.system("system"),
            Msg.assistant("old"),
            Msg.assistant("tail1"),
            Msg.assistant("tail2")
          )
        )
        second <- compactor.compactIfNeeded(
          first ++ Chunk(Msg.assistant("new"), Msg.assistant("last"))
        )
        prompts <- inputs.get
      yield assertTrue(
        second.headOption.exists(_.role == Role.System),
        second.count(ConversationCompactor.isSummary) == 1,
        prompts.lastOption.exists(_.exists(_.text == "summary 1")),
        second.exists(_.text == "summary 2")
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
    test("failed and interrupted hooks never become successful compaction") {
      val error = AgentError.ModelError(500, "flush failed")
      val failing = new ConversationCompactor(
        _ => ZIO.succeed("summary"),
        config,
        counter,
        _ => ZIO.fail(error)
      )
      val interrupted = new ConversationCompactor(
        _ => ZIO.succeed("summary"),
        config,
        counter,
        offload = _ => ZIO.interrupt
      )
      val history = Chunk(Msg.assistant("one"), Msg.assistant("two"), Msg.assistant("three"))
      for
        failure      <- failing.compactIfNeeded(history).either
        cancellation <- interrupted.compactIfNeeded(history).exit
      yield assertTrue(failure == Left(error), cancellation.isInterrupted)
    }
  )
