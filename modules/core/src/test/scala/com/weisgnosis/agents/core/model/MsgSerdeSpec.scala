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
import zio.test.*

/**
 * 1:1 test parity suite for MsgUsageSerializationTest.java and Msg JSON serialization.
 */
object MsgSerdeSpec extends ZIOSpecDefault:

  def spec: Spec[TestEnvironment, Any] = suite("MsgSerdeSpec (AgentScope Java Parity)")(
    suite("ChatUsage Serialization")(
      test("usageFieldRoundTripsViaJson") {
        val usage = ChatUsage(inputTokens = 100, outputTokens = 50, time = 1.5)
        val msg = Msg(
          name = Some("assistant"),
          role = Role.Assistant,
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
          deserialized.exists(_.usage.exists(_.totalTokens == 150))
        )
      },
      test("cachedTokensRoundTripViaJson") {
        val usage = ChatUsage(inputTokens = 100, outputTokens = 50, cachedTokens = 30)
        val msg = Msg(
          name = Some("assistant"),
          role = Role.Assistant,
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
    suite("Msg Wire Format & Invariants")(
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
