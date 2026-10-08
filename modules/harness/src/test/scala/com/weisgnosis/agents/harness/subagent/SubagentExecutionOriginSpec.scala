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
package com.weisgnosis.agents.harness.subagent

import zio.*
import zio.json.*
import zio.test.*

object SubagentExecutionOriginSpec extends ZIOSpecDefault:
  def spec: Spec[TestEnvironment & Scope, Any] = suite("SubagentExecutionOriginSpec")(
    test(
      "host endpoints preserve exact spelling and structural identity; admission hardening rejects unsafe or malformed URLs"
    ):
      val valid = Chunk(
        "http://remote-agent:8080",
        "https://EXAMPLE.org/base/",
        "https://example.org/a%20b",
        "https://agents.example/worker@team"
      )
      val invalid = Chunk(
        "",
        "/relative",
        "ftp://example.org",
        "http://",
        "https://user:secret@example.org",
        "https://example.org?",
        "https://example.org#",
        "https://example.org/a b",
        "https://example.org/" + "a" * 2048,
        "https://example.org/\uD800"
      )
      assertTrue(
        valid.forall(value =>
          SubagentProtocolBaseUri
            .from(value)
            .exists: uri =>
              val origin: SubagentExecutionOrigin = SubagentExecutionOrigin.AgentProtocol(uri)
              uri.value == value && SubagentProtocolBaseUri.from(value).contains(uri) &&
              origin.toJson.fromJson[SubagentExecutionOrigin] == Right(origin)
        ),
        invalid.forall(value =>
          SubagentProtocolBaseUri.from(value).isLeft && value.toJson
            .fromJson[SubagentProtocolBaseUri]
            .isLeft
        ),
        SubagentExecutionOrigin.Local.toJson.fromJson[SubagentExecutionOrigin] == Right(
          SubagentExecutionOrigin.Local
        )
      )
  )
