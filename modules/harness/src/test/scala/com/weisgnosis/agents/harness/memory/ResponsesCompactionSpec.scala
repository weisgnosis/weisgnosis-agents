/* Copyright 2024-2026 WeisGnosis. Licensed under the Apache License, Version 2.0. */
package com.weisgnosis.agents.harness.memory

import com.weisgnosis.agents.core.model.{ContentBlock, Msg, ToolHistory}
import zio.*
import zio.json.ast.Json
import zio.test.*

object ResponsesCompactionSpec extends ZIOSpecDefault:
  private val counter: TokenCounter = new TokenCounter:
    def count(message: Msg): Long = if message.content.isEmpty then 0L else 1L

  private val settings: CompactorConfig = CompactorConfig(
    triggerTokens = 1,
    keepTokens = 0,
    preserveTailMessages = 1,
    protectTokens = 0,
    maxPruneOutputChars = 5
  )

  private def nativeCall(itemId: String, callId: String, text: String): Msg =
    val arguments = Json.Obj("query" -> Json.Str(text))
    Msg
      .assistant(Chunk(ContentBlock.ToolUse(callId, "search", arguments)))
      .withMetadata(
        Msg.MetadataResponsesNative,
        Json.Obj(
          "version" -> Json.Num(1),
          "model"   -> Json.Str("fixture-reasoner"),
          "items" -> Json.Arr(
            Json.Obj(
              "id"        -> Json.Str(itemId),
              "type"      -> Json.Str("function_call"),
              "status"    -> Json.Str("completed"),
              "call_id"   -> Json.Str(callId),
              "name"      -> Json.Str("search"),
              "arguments" -> Json.Str(s"{\"query\":\"$text\"}")
            )
          )
        )
      )

  def spec: Spec[TestEnvironment & Scope, Any] = suite("ResponsesCompactionSpec")(
    test(
      "active native continuation retains exact repeated call occurrences, results and synthetic reminder"
    ):
      val prefix = Chunk(Msg.user("old question"), Msg.assistant("old answer"))
      val active = Chunk(
        Msg.user("current question"),
        nativeCall("item-1", "repeated", "first-long-argument"),
        Msg.tool("repeated", "search", "first-long-result"),
        nativeCall("item-2", "repeated", "second-long-argument"),
        Msg.tool("repeated", "search", "second-long-result"),
        Msg
          .user("<system-reminder>Continue</system-reminder>")
          .withMetadata(Msg.MetadataSynthetic, Json.Bool(true)),
        Msg.assistant("retained tail")
      )
      for
        summarized <- Ref.make(Chunk.empty[Msg])
        engine = new ConversationCompactor(
          messages => summarized.set(messages).as("older summary"),
          settings,
          counter
        )
        result   <- engine.compactIfNeeded(prefix ++ active)
        expanded <- ZIO.fromEither(ConversationCompactor.expandCoalescedUsers(result))
        observed <- summarized.get
      yield assertTrue(
        observed == prefix,
        expanded.filterNot(ConversationCompactor.isSummary) == active,
        ToolHistory.inspect(expanded).isComplete,
        expanded.flatMap(_.toolUses).map(_.id) == Chunk("repeated", "repeated"),
        expanded.flatMap(_.toolResults).map(_.text) == Chunk(
          "first-long-result",
          "second-long-result"
        )
      )
    ,
    test("incomplete replay fence survives compaction even after a newer user turn"):
      val incomplete = Msg
        .assistant("partial native response")
        .withMetadata(Msg.MetadataResponsesIncompleteReplay, Json.Bool(true))
      val retained = Chunk(
        incomplete,
        Msg.assistant("interrupted recovery"),
        Msg.user("new question"),
        Msg.assistant("new answer")
      )
      for
        summarized <- Ref.make(Chunk.empty[Msg])
        engine = new ConversationCompactor(
          messages => summarized.set(messages).as("older summary"),
          settings,
          counter
        )
        result   <- engine.compactIfNeeded(Chunk(Msg.user("earlier question")) ++ retained)
        expanded <- ZIO.fromEither(ConversationCompactor.expandCoalescedUsers(result))
        observed <- summarized.get
      yield assertTrue(
        observed == Chunk(Msg.user("earlier question")),
        expanded.filterNot(ConversationCompactor.isSummary) == retained,
        expanded.count(_.metadata.contains(Msg.MetadataResponsesIncompleteReplay)) == 1
      )
    ,
    test("closed native segment may compact after a new ordinary user starts a fresh segment"):
      val closed = Chunk(
        Msg.user("old question"),
        nativeCall("old-item", "old-call", "long-old-input"),
        Msg.tool("old-call", "search", "long-old-result"),
        Msg.assistant("old completion")
      )
      val fresh = Chunk(Msg.user("new question"), Msg.assistant("fresh answer"))
      for
        summarized <- Ref.make(Chunk.empty[Msg])
        engine = new ConversationCompactor(
          messages => summarized.set(messages).as("older summary"),
          settings.copy(preserveTailMessages = 2),
          counter
        )
        result   <- engine.compactIfNeeded(closed ++ fresh)
        expanded <- ZIO.fromEither(ConversationCompactor.expandCoalescedUsers(result))
        observed <- summarized.get
      yield assertTrue(
        observed.exists(_.metadata.contains(Msg.MetadataResponsesNative)),
        observed.flatMap(_.toolResults).forall(_.text.contains("... pruned ...")),
        expanded.filterNot(ConversationCompactor.isSummary) == fresh,
        !expanded.exists(_.metadata.contains(Msg.MetadataResponsesNative))
      )
  )
