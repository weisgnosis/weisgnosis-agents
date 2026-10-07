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
package com.weisgnosis.agents.core.client

import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.model.{ChatUsage, Msg}
import zio.Chunk
import zio.test.*
import zio.json.*
import zio.json.ast.Json

/** Protocol failures must not become empty successful model observations. */
object ReasoningAdapterValidationSpec extends ZIOSpecDefault:
  private def delta(fields: String): String = s"""{"choices":[{"index":0,"delta":{$fields}}]}"""

  def spec: Spec[TestEnvironment, Any] = suite("ReasoningAdapterValidationSpec")(
    test(
      "Google signatures assemble by stream index through late fragments and interrupted snapshots"
    ) {
      val first           = "opaque-first+/="
      val second          = "opaque-second"
      val argumentsPrefix = Json.Str("{\"x\":").toJson
      val frames = Chunk(
        delta(
          s""""tool_calls":[{"index":0,"id":"one","function":{"name":"search","arguments":$argumentsPrefix},"extra_content":{"google":{"thought_signature":"$first"}}},{"index":1,"id":"two","function":{"name":"search","arguments":"{}"}}]"""
        ),
        delta(
          """"tool_calls":[{"index":2,"id":"three","function":{"name":"search","arguments":"{"}},{"index":0,"function":{"arguments":"1}"}}]"""
        ),
        delta(
          s""""tool_calls":[{"index":2,"function":{"arguments":"}"},"extra_content":{"google":{"thought_signature":"$second"}}},{"index":0,"extra_content":{"google":{"thought_signature":"$first"}}}]"""
        )
      )
      def assemble(values: Chunk[String]): Either[AgentError, StreamAccumulator] =
        values.foldLeft[Either[AgentError, StreamAccumulator]](Right(StreamAccumulator.empty))(
          (acc, frame) =>
            for
              state <- acc
              chunks <- ReasoningModelAdapter.parseChunk(
                frame,
                ToolSignatureFormat.GoogleExtraContent
              )
              next <- chunks.foldLeft[Either[AgentError, StreamAccumulator]](Right(state))(
                (current, chunk) => current.flatMap(_.feed(chunk))
              )
            yield next
        )
      val finalMessage = for
        accumulated <- assemble(frames)
        finished    <- accumulated.feed(LlmChunk.FinishReason("tool_calls"))
        output      <- finished.toTurnOutput
      yield output.message
      val roundTrip =
        finalMessage.flatMap(_.toJson.fromJson[Msg].left.map(AgentError.DeserializationError(_)))
      val interrupted = assemble(frames.take(1)).map(_.toInterrupted.message)
      assertTrue(
        roundTrip == finalMessage,
        finalMessage.exists(
          _.toolUses.map(call => (call.id, call.input, call.thoughtSignature)) == Chunk(
            ("one", Json.Obj("x" -> Json.Num(1)), Some(first)),
            ("two", Json.Obj(), None),
            ("three", Json.Obj(), Some(second))
          )
        ),
        interrupted.exists(
          _.toolUses.map(call => (call.input, call.thoughtSignature)) == Chunk(
            (Json.Obj(), Some(first)),
            (Json.Obj(), None)
          )
        ),
        """{"streamIndex":0}""".fromJson[LlmChunk.ToolCallChunk].exists(_.thoughtSignature.isEmpty)
      )
    },
    test(
      "unsupported malformed or conflicting Google signatures fail without revealing their value"
    ) {
      val token = "private-opaque-token"
      val badValues = Chunk[Json](
        Json.Null,
        Json.Num(1),
        Json.Bool(true),
        Json.Arr(),
        Json.Obj(),
        Json.Str(""),
        Json.Str(" \t")
      )
      def signed(value: Json): String = delta(
        s""""tool_calls":[{"index":0,"extra_content":{"google":{"thought_signature":${value.toJson}}}}]"""
      )
      val invalidContainers = Chunk(
        delta(""""tool_calls":[{"index":0,"extra_content":{"google":null}}]"""),
        delta(""""tool_calls":[{"index":0,"extra_content":{"google":[]}}]"""),
        delta(""""tool_calls":[{"index":0,"extra_content":42}]""")
      )
      val rejected = badValues.map(signed) ++ invalidContainers
      val formats  = Chunk(ToolSignatureFormat.Unsupported, ToolSignatureFormat.GoogleExtraContent)
      val initial =
        StreamAccumulator.empty.feed(LlmChunk.ToolCallChunk(0, thoughtSignature = Some(token)))
      val conflicting = initial.flatMap(
        _.feed(LlmChunk.ToolCallChunk(0, thoughtSignature = Some("other-private-token")))
      )
      val unsupported = ReasoningModelAdapter.parseChunk(signed(Json.Str(token)))
      val unsigned = delta(
        """"tool_calls":[{"index":0,"id":"call","function":{"name":"search","arguments":"{}"},"extra_content":{"unrelated":{"serverTool":true}}}]"""
      )
      assertTrue(
        formats.forall(format =>
          rejected.forall(frame => ReasoningModelAdapter.parseChunk(frame, format).isLeft)
        ),
        unsupported.left.exists(error => !error.message.contains(token)),
        conflicting.left.exists(error =>
          !error.message.contains(token) && !error.message.contains("other-private-token")
        ),
        StreamAccumulator.empty
          .feed(LlmChunk.ToolCallChunk(0, thoughtSignature = Some(" ")))
          .isLeft,
        ReasoningModelAdapter.parseChunk(unsigned) == ReasoningModelAdapter
          .parseChunk(unsigned, ToolSignatureFormat.GoogleExtraContent),
        ReasoningModelAdapter.parseChunk(unsigned) == Right(
          Chunk(LlmChunk.ToolCallChunk(0, Some("call"), Some("search"), "{}"))
        )
      )
    },
    test("malformed JSON and invalid root envelopes fail in the typed channel") {
      val invalid =
        Chunk("broken", "[]", "null", "{}", """{"choices":null}""", """{"choices":[]}""")
      assertTrue(
        invalid.forall(value =>
          ReasoningModelAdapter
            .parseChunk(value)
            .left
            .exists(_.isInstanceOf[AgentError.DeserializationError])
        )
      )
    },
    test("valid frame prefixes cannot conceal trailing response garbage") {
      assertTrue(
        ReasoningModelAdapter.parseChunk(delta("\"content\":\"answer\"") + " trailing").isLeft
      )
    },
    test("usage cannot conceal a missing required choices array") {
      assertTrue(ReasoningModelAdapter.parseChunk("""{"usage":{"prompt_tokens":1}}""").isLeft)
    },
    test("fractional and overflowing indexes and token counts fail") {
      val numbers = Chunk("0.5", "2147483648", "1e30")
      assertTrue(
        numbers.forall(number =>
          ReasoningModelAdapter.parseChunk(delta(s""""tool_calls":[{"index":$number}]""")).isLeft &&
            ReasoningModelAdapter
              .parseChunk(s"""{"choices":[{"index":$number,"delta":{}}]}""")
              .isLeft &&
            ReasoningModelAdapter
              .parseChunk(s"""{"choices":[],"usage":{"prompt_tokens":$number}}""")
              .isLeft
        )
      )
    },
    test("legacy function calls cannot be silently lost even alongside visible text") {
      val legacy = """"function_call":{"name":"search","arguments":"{}"}"""
      assertTrue(
        ReasoningModelAdapter.parseChunk(delta(legacy)).isLeft,
        ReasoningModelAdapter.parseChunk(delta(legacy + ",\"content\":\"working\"")).isLeft,
        ReasoningModelAdapter.parseChunk(delta("\"function_call\":null")) == Right(Chunk.empty)
      )
    },
    test("duplicate ignored extension fields and malformed finish reasons fail") {
      assertTrue(
        ReasoningModelAdapter
          .parseChunk("""{"choices":[{"index":0,"delta":{}}],"extension":{"x":1,"x":2}}""")
          .isLeft,
        ReasoningModelAdapter
          .parseChunk("""{"choices":[{"index":0,"delta":{},"finish_reason":7}]}""")
          .isLeft
      )
    },
    test("malformed recognized delta fields are rejected instead of discarded") {
      val invalid = Chunk(
        "\"content\":42",
        "\"reasoning_content\":true",
        "\"thinking\":[]",
        "\"tool_calls\":{}",
        "\"tool_calls\":[null]",
        "\"role\":7",
        "\"refusal\":{}"
      )
      assertTrue(invalid.forall(fields => ReasoningModelAdapter.parseChunk(delta(fields)).isLeft))
    },
    test("missing malformed negative and duplicate tool indexes cannot collide at zero") {
      val invalid = Chunk(
        "{}",
        "{\"index\":null}",
        "{\"index\":\"1\"}",
        "{\"index\":-1}",
        "{\"index\":0},{\"index\":0}",
        "{\"index\":0,\"index\":1}"
      )
      assertTrue(
        invalid.forall(calls =>
          ReasoningModelAdapter.parseChunk(delta(s"\"tool_calls\":[$calls]")).isLeft
        )
      )
    },
    test("first and subsequent tool fragments preserve their explicit stream indexes") {
      val first = delta(
        """"tool_calls":[{"index":1,"id":"call-1","type":"function","function":{"name":"search","arguments":"{"}}]"""
      )
      val fragment = delta(
        """"tool_calls":[{"index":1,"function":{"arguments":"}"}},{"index":0,"id":"call-0"}]"""
      )
      assertTrue(
        ReasoningModelAdapter.parseChunk(first) == Right(
          Chunk(LlmChunk.ToolCallChunk(1, Some("call-1"), Some("search"), "{"))
        ),
        ReasoningModelAdapter.parseChunk(fragment) == Right(
          Chunk(
            LlmChunk.ToolCallChunk(1, argumentsDelta = "}"),
            LlmChunk.ToolCallChunk(0, Some("call-0"))
          )
        )
      )
    },
    test("multiple choices and nonzero choice indexes cannot be mixed into one reply") {
      val invalid = Chunk(
        """{"choices":[{"index":1,"delta":{"content":"other"}}]}""",
        """{"choices":[{"index":0,"delta":{}},{"index":1,"delta":{}}]}""",
        """{"choices":[{"index":null,"delta":{}}]}""",
        """{"choices":[{"index":0,"delta":null}]}"""
      )
      assertTrue(invalid.forall(ReasoningModelAdapter.parseChunk(_).isLeft))
    },
    test("role-only and terminal empty deltas are valid protocol chunks") {
      assertTrue(
        ReasoningModelAdapter.parseChunk(delta("\"role\":\"assistant\",\"content\":null")) == Right(
          Chunk.empty
        ),
        ReasoningModelAdapter.parseChunk(
          """{"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}"""
        ) == Right(Chunk(LlmChunk.FinishReason("stop")))
      )
    },
    test("usage-only chunks preserve metrics and normalize absent or null counts") {
      val cases = Chunk(
        (
          """{"prompt_tokens":null,"completion_tokens":7,"prompt_tokens_details":{"cached_tokens":null}}""",
          ChatUsage(outputTokens = 7)
        ),
        (
          """{"prompt_tokens":100,"completion_tokens":50,"prompt_cache_hit_tokens":60,"prompt_tokens_details":{"cached_tokens":20,"cache_write_tokens":12}}""",
          ChatUsage(100, 50, cachedTokens = 60, cacheCreationTokens = 12)
        ),
        (
          """{"prompt_cache_hit_tokens":0,"prompt_tokens_details":{"cached_tokens":20}}""",
          ChatUsage()
        ),
        (
          """{"prompt_cache_hit_tokens":null,"prompt_tokens_details":{"cached_tokens":20,"cache_write_tokens":null}}""",
          ChatUsage(cachedTokens = 20)
        ),
        (
          """{"prompt_tokens_details":{"cached_tokens":20,"cache_write_tokens":12}}""",
          ChatUsage(cachedTokens = 20, cacheCreationTokens = 12)
        )
      )
      checkAll(Gen.fromIterable(cases)) { case (usage, expected) =>
        assertTrue(
          ReasoningModelAdapter.parseChunk(s"""{"choices":[],"usage":$usage}""") == Right(
            Chunk(LlmChunk.UsageChunk(expected))
          )
        )
      }
    },
    test("malformed and negative usage fields fail rather than becoming zero") {
      val invalid = Chunk(
        """{"prompt_tokens":"10"}""",
        """{"completion_tokens":-1}""",
        """{"prompt_tokens_details":[]}""",
        """{"completion_tokens_details":{"reasoning_tokens":true}}"""
      )
      val invalidCounts = Chunk("-1", "\"10\"", "0.5", "2147483648", "true", "[]", "{}")
      val cacheFields = invalidCounts.flatMap(value =>
        Chunk(
          s"""{"prompt_cache_hit_tokens":$value,"prompt_tokens_details":{"cached_tokens":20}}""",
          s"""{"prompt_cache_hit_tokens":60,"prompt_tokens_details":{"cache_write_tokens":$value}}""",
          s"""{"prompt_cache_hit_tokens":60,"prompt_tokens_details":{"cached_tokens":$value}}"""
        )
      )
      checkAll(Gen.fromIterable(invalid ++ cacheFields))(usage =>
        assertTrue(ReasoningModelAdapter.parseChunk(s"""{"choices":[],"usage":$usage}""").isLeft)
      )
    },
    test("provider errors are surfaced even when the choices field is absent") {
      assertTrue(
        ReasoningModelAdapter.parseChunk(
          """{"error":{"message":"capacity unavailable"}}"""
        ) == Left(AgentError.ModelError(200, "capacity unavailable")),
        ReasoningModelAdapter.parseChunk("""{"error":{"message":null}}""") == Left(
          AgentError.ModelError(200, "Completion stream reported an error")
        ),
        ReasoningModelAdapter.parseChunk("""{"error":{"message":7}}""").isLeft
      )
    },
    test("unsupported roles refusals and tool kinds fail explicitly") {
      val invalid = Chunk(
        "\"role\":\"user\"",
        "\"refusal\":\"declined\"",
        "\"tool_calls\":[{\"index\":0,\"type\":\"custom\"}]",
        "\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":{}}}]"
      )
      assertTrue(invalid.forall(fields => ReasoningModelAdapter.parseChunk(delta(fields)).isLeft))
    },
    test("reasoning precedes visible text and canonical reasoning content wins its alias") {
      val json = delta(""""reasoning_content":"reason","thinking":"alias","content":"answer"""")
      assertTrue(
        ReasoningModelAdapter.parseChunk(json) == Right(
          Chunk(
            LlmChunk.ThinkingDelta("reason"),
            LlmChunk.TextDelta("answer")
          )
        )
      )
    }
  )
