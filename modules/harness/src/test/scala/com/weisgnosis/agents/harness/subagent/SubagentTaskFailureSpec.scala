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

import com.weisgnosis.agents.core.error.AgentError
import zio.json.*
import zio.json.ast.Json
import zio.test.*

object SubagentTaskFailureSpec extends ZIOSpecDefault:
  def spec: Spec[TestEnvironment, Any] = suite("SubagentTaskFailureSpec")(
    test(
      "only supplied typed direct causes survive exact wire conversion and derived presentation"
    ):
      val cases = zio.Chunk(
        (
          AgentError.ToolExecutionError("read", "exact λ", Some("cause <&😀")),
          SubagentTaskFailure("exact λ", Some("cause <&😀")),
          "exact λ\nCause: cause <&😀"
        ),
        (
          AgentError.ToolExecutionError("read", "empty cause", Some("")),
          SubagentTaskFailure("empty cause", Some("")),
          "empty cause\nCause: "
        ),
        (AgentError.ToolExecutionError("read", "none"), SubagentTaskFailure("none"), "none"),
        (
          AgentError.StorageError("storage message"),
          SubagentTaskFailure("storage message"),
          "storage message"
        )
      )
      val decoded = Json
        .Obj("message" -> Json.Str("exact λ"), "cause" -> Json.Str("cause <&😀"))
        .as[SubagentTaskFailure]
      val unknown = Json
        .Obj("message" -> Json.Str("exact λ"), "unknown" -> Json.Bool(true))
        .as[SubagentTaskFailure]
      assertTrue(
        cases.forall((error, expected, display) =>
          SubagentTaskFailure.fromError(
            error
          ) == expected && expected.displayText == display && expected.toJson
            .fromJson[SubagentTaskFailure] == Right(expected)
        ),
        decoded == Right(SubagentTaskFailure("exact λ", Some("cause <&😀"))),
        unknown.isLeft
      )
  )
