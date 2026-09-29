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
package com.weisgnosis.agents.core.agent

import com.weisgnosis.agents.core.model.{ContentBlock, Msg}
import com.weisgnosis.agents.core.client.{LlmClient, TurnOutput}
import com.weisgnosis.agents.core.event.AgentEvent
import zio.*
import zio.json.ast.Json
import zio.test.*

object StructuredJsonBoundarySpec extends ZIOSpecDefault:
  def spec: Spec[TestEnvironment & Scope, Any] = suite("Structured JSON boundary")(
    test("accepts a complete JSON value with surrounding JSON whitespace"):
      assertTrue(
        Msg.assistant(" \n{\"answer\":7}\t ").getStructuredData[Json] == Right(
          Json.Obj("answer" -> Json.Num(7))
        ),
        Msg.assistant(" true\r\n").getStructuredData[Json] == Right(Json.Bool(true))
      )
    ,
    test("rejects a valid JSON prefix followed by trailing input"):
      assertTrue(
        Chunk("{} {}", "{\"answer\":7} trailing", "{\"answer\":7}]", "true,false", "7 8")
          .forall(text => Msg.assistant(text).getStructuredData[Json].isLeft)
      )
    ,
    test("rejects ambiguous duplicate fields in text and authoritative metadata"):
      val duplicate = Json.Obj("answer" -> Json.Num(1), "answer" -> Json.Num(2))
      val metadata =
        Msg
          .assistant("{\"answer\":7}")
          .withMetadata(StructuredOutput.MetadataStructuredOutput, duplicate)
      assertTrue(
        Msg.assistant("{\"answer\":1,\"answer\":2}").getStructuredData[Json].isLeft,
        Msg.assistant("{\"nested\":{\"value\":1,\"value\":2}}").getStructuredData[Json].isLeft,
        metadata.getStructuredData[Json].isLeft
      )
    ,
    test("rejects ambiguous synthetic captures before publishing a finalized result"):
      val duplicate = Json.Obj("answer" -> Json.Num(1), "answer" -> Json.Num(2))
      val call = ContentBlock.ToolUse(
        "capture",
        StructuredOutput.ToolName,
        Json.Obj("response" -> duplicate)
      )
      for
        observed <- Ref.make(Chunk.empty[AgentEvent])
        client   <- LlmClient.mock(Chunk(TurnOutput.ToolCalls(Msg.assistant(Chunk(call)))))
        agent    <- ReActAgent.make(client = client)
        result <- agent
          .streamStructuredEvents[Json](
            Chunk(Msg.user("answer")),
            Json.Obj(),
            StructuredMode.SyntheticTool
          )
          .tap(event => observed.update(_ :+ event))
          .runDrain
          .either
        events <- observed.get
      yield assertTrue(result.isLeft, !events.exists(_.isInstanceOf[AgentEvent.AgentResultEvent]))
    ,
    test("validates the entire synthetic argument object before extracting response"):
      val tool             = StructuredOutput.createTool(Json.Obj(), _ => ZIO.unit)
      val duplicateWrapper = Json.Obj("response" -> Json.Num(1), "response" -> Json.Num(2))
      val ignoredDuplicate = Json.Obj(
        "response" -> Json.Num(1),
        "ignored"  -> Json.Obj("value" -> Json.Num(1), "value" -> Json.Num(2))
      )
      assertTrue(
        tool.decodeInput(duplicateWrapper).isLeft,
        tool.decodeInput(ignoredDuplicate).isLeft
      )
    ,
    test("preserves valid wrapped and direct synthetic payloads"):
      val tool  = StructuredOutput.createTool(Json.Obj(), _ => ZIO.unit)
      val value = Json.Obj("answer" -> Json.Num(7))
      assertTrue(
        tool.decodeInput(Json.Obj("response" -> value)) == Right(value),
        tool.decodeInput(value) == Right(value),
        tool.decodeInput(Json.Num(7)) == Right(Json.Num(7))
      )
  )
