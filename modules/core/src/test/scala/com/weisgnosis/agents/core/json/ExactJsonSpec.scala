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
package com.weisgnosis.agents.core.json

import com.weisgnosis.agents.core.agent.{AgentResult, AgentRunState, PendingActing}
import com.weisgnosis.agents.core.event.{AgentEvent, ModelStreamContext}
import com.weisgnosis.agents.core.model.{ContentBlock, Msg, ToolResultState}
import com.weisgnosis.agents.core.tool.ToolDef
import zio.Chunk
import zio.json.*
import zio.json.ast.Json
import zio.json.internal.Write
import zio.test.*

object ExactJsonSpec extends ZIOSpecDefault:
  def spec: Spec[TestEnvironment, Any] = suite("ExactJsonSpec")(
    test("suspended runtime data and tagged events preserve nested numeric values"):
      val input = Json.Obj(
        "values" -> Json.Arr(Json.Num(Long.MinValue), Json.Num(BigDecimal("-9223372036854775.808")))
      )
      val metadata = Map("numeric" -> input)
      val blocks = Chunk[ContentBlock](
        ContentBlock.Thinking("reason", metadata),
        ContentBlock.ToolUse("call", "read", input, metadata = metadata),
        ContentBlock.ToolResult.text("call", "read", "done").copy(metadata = metadata)
      )
      val assistant = Msg
        .assistant(
          Chunk(
            ContentBlock.Thinking("reason", metadata),
            ContentBlock.ToolUse("call", "read", input, metadata = metadata)
          )
        )
        .withMetadata(metadata)
      val checkpoint = AgentResult(
        assistant,
        Chunk(assistant),
        1,
        AgentRunState.Suspended(
          PendingActing(
            "host review",
            ModelStreamContext("reply", "request"),
            0,
            Chunk(ToolDef("read", "Read data", Json.Obj())),
            None,
            None
          )
        )
      )
      val events = Chunk[AgentEvent](
        AgentEvent.AgentResultEvent(assistant),
        AgentEvent.ToolResultStartEvent("call", "read", metadata = metadata),
        AgentEvent.ToolResultDeltaEvent("call", "part", metadata = metadata),
        AgentEvent.ToolResultEndEvent("call", "read", "done", ToolResultState.Success, metadata = metadata),
        AgentEvent.ToolResultDataDeltaEvent("reply", "call", "read", ContentBlock.Text("done"), metadata = metadata)
      )
      assertTrue(
        ExactJson.encode(input) == Right(
          """{"values":[-9223372036854775808,-9223372036854775.808]}"""
        ),
        ExactJson.encode(checkpoint).flatMap(_.fromJson[AgentResult]) == Right(checkpoint),
        events.forall(event => ExactJson.encode(event).flatMap(_.fromJson[AgentEvent]) == Right(event)),
        Chunk(
          """{"event":"tool_result_start","callId":"call","name":"read"}""",
          """{"event":"tool_result_start","callId":"call","name":"read","metadata":null}"""
        ).forall(_.fromJson[AgentEvent] == Right(AgentEvent.ToolResultStartEvent("call", "read"))),
        """{"event":"tool_result_start","callId":"call","name":"read","metadata":[]}""".fromJson[AgentEvent].isLeft,
        blocks.forall(block =>
          ExactJson.encode(block).flatMap(_.fromJson[ContentBlock]) == Right(block)
        )
      )
    ,
    test("ambiguous ASTs and failed encoder conversion produce typed failures"):
      val rejected: JsonEncoder[String] = new JsonEncoder[String]:
        def unsafeEncode(value: String, indent: Option[Int], out: Write): Unit =
          JsonEncoder.string.unsafeEncode(value, indent, out)
        override def toJsonAST(value: String): Either[String, Json] =
          Left(s"Unsupported value: $value")
      val duplicate = Json.Obj("same" -> Json.Num(1), "same" -> Json.Num(2))
      assertTrue(
        ExactJson.encode(Json.Arr(duplicate)).isLeft,
        ExactJson
          .encode("unsupported")(using rejected)
          .left
          .exists(_.message.contains("Unsupported value: unsupported"))
      )
  )
