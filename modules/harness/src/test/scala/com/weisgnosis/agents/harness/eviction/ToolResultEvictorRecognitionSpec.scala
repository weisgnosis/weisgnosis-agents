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
import com.weisgnosis.agents.core.json.JsonBoundary
import com.weisgnosis.agents.core.model.*
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

object ToolResultEvictorRecognitionSpec extends ZIOSpecDefault:
  private val config: EvictionConfig =
    EvictionConfig(maxResultChars = 10, previewHeadChars = 3, previewTailChars = 4)
  private val original: ContentBlock.ToolResult = ContentBlock.ToolResult
    .text("call", "lookup", "START" + "m" * 80 + "FINISH")
    .copy(state = ToolResultState.Error, metadata = Map("custom" -> Json.Str("preserved")))
  private def memoryStore(saved: Ref[Map[String, String]], writes: Ref[Int]): ToolResultStore =
    new ToolResultStore:
      def persist(key: String, content: String): IO[AgentError, String] =
        writes
          .update(_ + 1) *> saved.update(_.updated(key, content)).as(s"large_tool_results/$key.txt")
      def resolve(key: String): IO[AgentError, Option[StoredToolResult]] =
        saved.get.map(
          _.get(key).map(content => StoredToolResult(content, s"large_tool_results/$key.txt"))
        )

  def spec: Spec[TestEnvironment & Scope, Any] = suite("ToolResultEvictorRecognitionSpec")(
    test("historical split-surrogate v1 JSON previews are repaired without repersisting") {
      val text     = "\uD83D\uDE00middle\uD83D\uDE00"
      val settings = config.copy(maxResultChars = 0, previewHeadChars = 1, previewTailChars = 1)
      for outcomes <- ZIO.foreach(Chunk(0, 10000)) { threshold =>
          for
            saved  <- Ref.make(Map.empty[String, String])
            writes <- Ref.make(0)
            store   = memoryStore(saved, writes)
            evictor = new ToolResultEvictor(store)
            safe <- evictor.evictResult(
              ContentBlock.ToolResult.text("unicode", "lookup", text),
              settings
            )
            path <- ZIO
              .fromOption(safe.metadata.get("eviction_path").flatMap(_.asString))
              .orElseFail(AgentError.InvalidMessage("Missing Unicode fixture path"))
            legacyText =
              s"Tool output was too large (${text.length} chars) and has been saved to `$path`.\nUse `read_file` with path `$path` to inspect the full output.\n\n${text.take(1)}\n\n... omitted ...\n\n${text.takeRight(1)}"
            legacy = safe.copy(
              output = Chunk(ContentBlock.Text(legacyText)),
              metadata = safe.metadata
                .updated("eviction_preview_head_chars", Json.Num(1))
                .updated("eviction_preview_tail_chars", Json.Num(1))
            )
            decoded <- ZIO.fromEither(Chunk(Msg.tool(Chunk(legacy))).toJson.fromJson[Chunk[Msg]])
            repaired <- new ToolResultEvictor(store)
              .evictOversizedToolResults(decoded, settings.copy(maxResultChars = threshold))
            alteredWidths = safe.copy(metadata =
              safe.metadata
                .updated("eviction_preview_head_chars", Json.Num(1))
                .updated("eviction_preview_tail_chars", Json.Num(1))
            )
            unrecognized <- evictor.evictResult(
              alteredWidths,
              settings.copy(maxResultChars = 10000)
            )
            count <- writes.get
            files <- saved.get
          yield assertTrue(
            unrecognized == alteredWidths,
            decoded.flatMap(_.toolResults).map(_.text) == Chunk(legacyText),
            !JsonBoundary.validUnicode(Json.Str(legacyText)),
            repaired.flatMap(_.toolResults) == Chunk(safe),
            repaired
              .flatMap(_.toolResults)
              .forall(result => JsonBoundary.validUnicode(Json.Str(result.text))),
            count == 1,
            files.values.toSet == Set(text)
          )
        }
      yield outcomes.foldLeft(assertTrue(true))(_ && _)
    },
    test(
      "low-threshold previews remain stable across repeated passes fresh instances and JSON reloads"
    ) {
      for
        saved  <- Ref.make(Map.empty[String, String])
        writes <- Ref.make(0)
        store = memoryStore(saved, writes)
        first   <- new ToolResultEvictor(store).evictResult(original, config)
        decoded <- ZIO.fromEither(Chunk(Msg.tool(Chunk(first))).toJson.fromJson[Chunk[Msg]])
        repeated <- ZIO.foldLeft(1 to 5)(decoded)((history, _) =>
          new ToolResultEvictor(store).evictOversizedToolResults(history, config)
        )
        count <- writes.get
        files <- saved.get
      yield assertTrue(
        first.text.length > config.maxResultChars,
        repeated.flatMap(_.toolResults) == Chunk(first),
        count == 1,
        files.values.toSet == Set(original.text)
      )
    },
    test("changed preview windows derive from the stored original without writing a cache chain") {
      for
        saved  <- Ref.make(Map.empty[String, String])
        writes <- Ref.make(0)
        evictor = new ToolResultEvictor(memoryStore(saved, writes))
        first <- evictor.evictResult(original, config)
        second <- evictor.evictResult(
          first,
          config.copy(maxResultChars = 10000, previewHeadChars = 5, previewTailChars = 6)
        )
        excluded <- evictor.evictResult(second, config.copy(excludedTools = Set("lookup")))
        count    <- writes.get
      yield assertTrue(
        second.text.contains("START"),
        second.text.endsWith("FINISH"),
        second.metadata.get("eviction_cache_key") == first.metadata.get("eviction_cache_key"),
        second.state == ToolResultState.Error,
        second.metadata.get("custom").contains(Json.Str("preserved")),
        excluded == second,
        count == 1
      )
    },
    test("forged versions keys paths widths and altered preview text cannot bypass eviction") {
      for
        saved  <- Ref.make(Map.empty[String, String])
        writes <- Ref.make(0)
        evictor = new ToolResultEvictor(memoryStore(saved, writes))
        first <- evictor.evictResult(original, config)
        forgeries = Chunk(
          first.copy(metadata = first.metadata.updated("eviction_preview_version", Json.Num(2))),
          first.copy(metadata = first.metadata.updated("eviction_preview_version", Json.Str("1"))),
          first.copy(metadata =
            first.metadata.updated("eviction_preview_head_chars", Json.Num(BigDecimal("3.5")))
          ),
          first.copy(metadata =
            first.metadata
              .updated("eviction_preview_head_chars", Json.Num(BigDecimal("2147483648")))
          ),
          first.copy(metadata =
            first.metadata.updated("eviction_cache_key", Json.Str("../escape"))
          ),
          first.copy(metadata =
            first.metadata.updated("eviction_path", Json.Str("C:/private/file"))
          ),
          first.copy(metadata =
            first.metadata.updated("eviction_preview_head_chars", Json.Num(-1))
          ),
          first.copy(metadata =
            first.metadata.updated("eviction_preview_tail_chars", Json.Str("4"))
          ),
          first.copy(metadata = first.metadata.updated("eviction_preview_tail_chars", Json.Num(5))),
          first.copy(output = Chunk(ContentBlock.Text(first.text + " forged"))),
          first.copy(id = "another-call")
        )
        results <- ZIO.foreach(forgeries)(evictor.evictResult(_, config))
        count   <- writes.get
        files   <- saved.get
      yield assertTrue(
        count == 1 + forgeries.size,
        forgeries.forall(value => files.values.toSet.contains(value.text)),
        results.forall(_.metadata.get("eviction_preview_version").contains(Json.Num(1)))
      )
    },
    test("malformed marker keys are rejected before storage lookup") {
      for
        lookedUp <- Ref.make(false)
        saved    <- Ref.make(Option.empty[String])
        store = new ToolResultStore:
          def persist(key: String, content: String): IO[AgentError, String] =
            saved.set(Some(content)).as("trusted-path")
          def resolve(key: String): IO[AgentError, Option[StoredToolResult]] =
            lookedUp.set(true) *> ZIO.fail(AgentError.PermissionDenied("store", "invalid key"))
        candidate = original.copy(metadata =
          Map(
            "eviction_preview_version"    -> Json.Num(1),
            "eviction_cache_key"          -> Json.Str("../escape"),
            "eviction_path"               -> Json.Str("untrusted"),
            "eviction_preview_head_chars" -> Json.Num(3),
            "eviction_preview_tail_chars" -> Json.Num(4)
          )
        )
        result  <- new ToolResultEvictor(store).evictResult(candidate, config)
        lookup  <- lookedUp.get
        content <- saved.get
      yield assertTrue(!lookup, content.contains(candidate.text), result != candidate)
    },
    test("missing and corrupt backing payloads are raw candidates rather than verified previews") {
      for
        saved  <- Ref.make(Map.empty[String, String])
        writes <- Ref.make(0)
        evictor = new ToolResultEvictor(memoryStore(saved, writes))
        first   <- evictor.evictResult(original, config)
        _       <- saved.update(_.view.mapValues(_ => "corrupt").toMap)
        corrupt <- evictor.evictResult(first, config)
        _       <- saved.set(Map.empty)
        missing <- evictor.evictResult(first, config)
        count   <- writes.get
      yield assertTrue(count == 3, corrupt != first, missing == corrupt)
    },
    test("typed lookup failure retains the full input without attempting persistence") {
      for
        saved  <- Ref.make(Map.empty[String, String])
        writes <- Ref.make(0)
        first  <- new ToolResultEvictor(memoryStore(saved, writes)).evictResult(original, config)
        store = new ToolResultStore:
          def persist(key: String, content: String): IO[AgentError, String] =
            writes.update(_ + 1).as("unexpected")
          def resolve(key: String): IO[AgentError, Option[StoredToolResult]] =
            ZIO.fail(AgentError.ToolExecutionError("store", "unavailable"))
        result <- new ToolResultEvictor(store).evictResult(first, config)
        count  <- writes.get
      yield assertTrue(result == first, count == 1)
    },
    test("lookup interruption propagates and does not persist the preview") {
      for
        saved  <- Ref.make(Map.empty[String, String])
        writes <- Ref.make(0)
        first  <- new ToolResultEvictor(memoryStore(saved, writes)).evictResult(original, config)
        store = new ToolResultStore:
          def persist(key: String, content: String): IO[AgentError, String] =
            writes.update(_ + 1).as("unexpected")
          def resolve(key: String): IO[AgentError, Option[StoredToolResult]] = ZIO.interrupt
        exit  <- new ToolResultEvictor(store).evictResult(first, config).exit
        count <- writes.get
      yield assertTrue(exit.isInterrupted, count == 1)
    },
    test("same-ID distinct payloads retain separate original cache entries and nontext blocks") {
      val data = ContentBlock.Data(MediaSource.Url("https://example.test/result"))
      for
        saved  <- Ref.make(Map.empty[String, String])
        writes <- Ref.make(0)
        evictor = new ToolResultEvictor(memoryStore(saved, writes))
        first <- evictor.evictResult(original.copy(output = original.output :+ data), config)
        second <- evictor.evictResult(
          original.copy(output = Chunk(ContentBlock.Text("other" * 20), data)),
          config
        )
        repeated <- ZIO.foreach(Chunk(first, second))(evictor.evictResult(_, config))
        count    <- writes.get
      yield assertTrue(
        repeated == Chunk(first, second),
        count == 2,
        first.output.contains(data),
        second.output.contains(data),
        first.metadata.get("eviction_cache_key") != second.metadata.get("eviction_cache_key")
      )
    },
    test("local store verifies a reloaded preview through its canonical key and path") {
      for
        directory   <- EvictionTestSupport.temporaryDirectory
        firstStore  <- ToolResultStore.local(directory, WorkspaceTrust.HostControlled)
        first       <- new ToolResultEvictor(firstStore).evictResult(original, config)
        secondStore <- ToolResultStore.local(directory, WorkspaceTrust.HostControlled)
        repeated    <- new ToolResultEvictor(secondStore).evictResult(first, config)
      yield assertTrue(first == repeated)
    }
  )
