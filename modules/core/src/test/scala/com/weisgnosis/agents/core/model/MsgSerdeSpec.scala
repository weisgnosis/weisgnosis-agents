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
package com.weisgnosis.agents.core.model

import zio.Chunk
import zio.json.*
import zio.json.ast.Json
import zio.test.*

/**
 * Selected upstream scenario suite for MsgUsageSerializationTest.java and Msg JSON serialization.
 */
object MsgSerdeSpec extends ZIOSpecDefault:

  def spec: Spec[TestEnvironment, Any] = suite("MsgSerdeSpec (AgentScope Java Parity)")(
    suite("ChatUsage Serialization")(
      test("usageFieldRoundTripsViaJson") {
        val usage = ChatUsage(inputTokens = 100, outputTokens = 50, time = 1.5)
        val msg = Msg.assistant(
          name = Some("assistant"),
          content = Chunk(ContentBlock.Text("hello")),
          usage = Some(usage)
        )

        val json         = msg.toJson
        val deserialized = json.fromJson[Msg]

        assertTrue(
          deserialized.isRight,
          deserialized.exists(_.usage.contains(usage)),
          deserialized.exists(_.usage.exists(_.inputTokens == 100)),
          deserialized.exists(_.usage.exists(_.outputTokens == 50)),
          deserialized.exists(_.usage.exists(_.totalTokens == Right(150)))
        )
      },
      test("cachedTokensRoundTripViaJson") {
        val usage = ChatUsage(inputTokens = 100, outputTokens = 50, cachedTokens = 30)
        val msg = Msg.assistant(
          name = Some("assistant"),
          content = Chunk(ContentBlock.Text("hello")),
          usage = Some(usage)
        )

        val json         = msg.toJson
        val deserialized = json.fromJson[Msg]

        assertTrue(
          deserialized.isRight,
          deserialized.exists(_.usage.exists(_.cachedTokens == 30))
        )
      }
    ),
    suite("Usage compatibility")(
      test("reasoningTokensRoundTripViaJson") {
        val usage = ChatUsage(inputTokens = 100, outputTokens = 50, reasoningTokens = 20)
        assertTrue(
          Msg
            .assistant("reply")
            .withUsage(usage)
            .toJson
            .fromJson[Msg]
            .exists(_.usage.contains(usage))
        )
      },
      test("detailedInputBreakdownRoundTripsViaJson") {
        val usage = ChatUsage(100, 50, 30, 10, 20, 15, 1.5)
        assertTrue(
          Msg
            .assistant("reply")
            .withUsage(usage)
            .toJson
            .fromJson[Msg]
            .exists(_.usage.contains(usage))
        )
      },
      test("getChatUsagePreservesDetailedTokensFromMetadataMap") {
        val original = Msg
          .assistant("reply")
          .withMetadata(
            "_chat_usage",
            Json.Obj(
              "inputTokens"         -> Json.Num(100),
              "outputTokens"        -> Json.Num(50),
              "cachedTokens"        -> Json.Num(30),
              "cacheCreationTokens" -> Json.Num(10),
              "reasoningTokens"     -> Json.Num(20),
              "toolUsePromptTokens" -> Json.Num(15),
              "time"                -> Json.Num(BigDecimal("1.5"))
            )
          )
        val expected = Some(ChatUsage(100, 50, 30, 10, 20, 15, 1.5))
        val encoded  = original.toJson
        assertTrue(
          original.chatUsage == expected,
          original.chatUsage == expected,
          original.usage.isEmpty,
          original.toJson == encoded,
          encoded.fromJson[Msg].exists(_.chatUsage == expected)
        )
      },
      test("getChatUsagePrefersDirectField") {
        val direct = ChatUsage(inputTokens = 200, outputTokens = 100, time = 2.0)
        val original = Msg
          .assistant("reply")
          .withUsage(direct)
          .withMetadata("_chat_usage", Json.Str("malformed legacy value"))
        assertTrue(original.chatUsage.contains(direct), original.usage.contains(direct))
      },
      test("getChatUsageFallsBackToMetadata") {
        val original = Msg
          .assistant("reply")
          .withMetadata("_chat_usage", Json.Obj("inputTokens" -> Json.Num(300)))
        assertTrue(
          original.usage.isEmpty,
          original.chatUsage.contains(ChatUsage(inputTokens = 300))
        )
      },
      test("cachedTokensDefaultsToZeroForLegacyConstructor") {
        assertTrue(ChatUsage(inputTokens = 100, outputTokens = 50, time = 1.5).cachedTokens == 0)
      },
      test("usageNullByDefault") {
        assertTrue(Msg.user("hi").usage.isEmpty, Msg.user("hi").chatUsage.isEmpty)
      },
      test("usageDeserializesFromJsonWithoutField") {
        val json =
          """{"id":"test-1","name":"user","role":"USER","content":[{"type":"text","text":"hello"}],"metadata":{}}"""
        assertTrue(json.fromJson[Msg].exists(_.usage.isEmpty))
      },
      test("malformed legacy usage is unavailable without modifying metadata") {
        val bad =
          Chunk(Json.Str("invalid"), Json.Num(7), Json.Obj("inputTokens" -> Json.Str("invalid")))
        assertTrue(bad.forall { value =>
          val msg = Msg.assistant("reply").withMetadata("_chat_usage", value)
          msg.chatUsage.isEmpty && msg.metadata.get("_chat_usage").contains(value)
        })
      }
    ),
    suite("Msg Wire Format & Invariants")(
      test("supplied malformed optional message fields fail decoding instead of disappearing") {
        val invalid = Chunk(
          "id"        -> Json.Num(7),
          "name"      -> Json.Bool(true),
          "timestamp" -> Json.Arr(),
          "metadata"  -> Json.Str("invalid"),
          "usage"     -> Json.Str("invalid"),
          "usage"     -> Json.Obj("inputTokens" -> Json.Str("invalid"))
        )
        assertTrue(invalid.forall { case (key, value) =>
          Json.Obj("role" -> Json.Str("assistant"), key -> value).as[Msg].isLeft
        })
      },
      test("missing and JSON-null optional message fields retain absent defaults") {
        val missing = Json.Obj("role" -> Json.Str("user"))
        val empty = Json.Obj(
          "role"      -> Json.Str("user"),
          "id"        -> Json.Null,
          "name"      -> Json.Null,
          "timestamp" -> Json.Null,
          "metadata"  -> Json.Null,
          "usage"     -> Json.Null,
          "content"   -> Json.Null
        )
        assertTrue(
          missing.as[Msg].contains(Msg.user(name = None)),
          empty.as[Msg].contains(Msg.user(name = None))
        )
      },
      test("User message round-trip serialization") {
        val msg          = Msg.user("How is the weather today?")
        val json         = msg.toJson
        val deserialized = json.fromJson[Msg]

        assertTrue(
          deserialized.isRight,
          deserialized.exists(_.role == Role.User),
          deserialized.exists(_.text == "How is the weather today?")
        )
      },
      test("Synthetic system reminder round-trip with metadata") {
        val reminder     = Msg.systemReminder("Remember to check test coverage")
        val json         = reminder.toJson
        val deserialized = json.fromJson[Msg]

        assertTrue(
          reminder.isSynthetic,
          reminder.reminderKind.contains("system_directive"),
          deserialized.isRight,
          deserialized.exists(_.isSynthetic),
          deserialized.exists(_.reminderKind.contains("system_directive"))
        )
      },
      test("Deserialization runs role-content validation and rejects invalid SYSTEM messages") {
        val invalidJson =
          """{
            |  "role": "system",
            |  "content": [
            |    {
            |      "type": "data",
            |      "source": {"type": "url", "url": "https://example.com/x.png"}
            |    }
            |  ]
            |}""".stripMargin

        val decoded = invalidJson.fromJson[Msg]
        assertTrue(
          decoded.isLeft,
          decoded.left.exists(_.contains("SYSTEM"))
        )
      },
      test("Deserialization runs role-content validation and rejects invalid USER messages") {
        val invalidJson =
          """{
            |  "role": "user",
            |  "content": [
            |    {
            |      "type": "thinking",
            |      "thinking": "user cannot have thinking block"
            |    }
            |  ]
            |}""".stripMargin

        val decoded = invalidJson.fromJson[Msg]
        assertTrue(
          decoded.isLeft,
          decoded.left.exists(_.contains("USER"))
        )
      }
    )
  )
