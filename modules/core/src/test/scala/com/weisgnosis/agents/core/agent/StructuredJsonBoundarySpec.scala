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
import com.weisgnosis.agents.core.client.{
  LlmClient,
  LlmConfig,
  StructuredOutputCapability,
  TurnOutput
}
import com.weisgnosis.agents.core.event.AgentEvent
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.tool.{Tool, ToolDef, Toolkit}
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

object StructuredJsonBoundarySpec extends ZIOSpecDefault:
  def spec: Spec[TestEnvironment & Scope, Any] = suite("Structured JSON boundary")(
    test("rejects native output that decodes but violates the requested schema"):
      val schema = Json.Obj(
        "type" -> Json.Str("object"),
        "properties" -> Json.Obj(
          "value" -> Json.Obj(
            "type"    -> Json.Str("integer"),
            "minimum" -> Json.Num(0)
          )
        ),
        "required" -> Json.Arr(Json.Str("value"))
      )
      for
        client <- LlmClient.mock(Chunk(TurnOutput.FinalText(Msg.assistant("""{"value":-1}"""))))
        agent  <- ReActAgent.make(client = client)
        outcome <- agent
          .runStructured[Json](Chunk(Msg.user("answer")), schema, StructuredMode.Native)
          .either
      yield assertTrue(outcome.left.exists(_.isInstanceOf[AgentError.DeserializationError]))
    ,
    test("enforces exact schema constraints for native and automatic completion"):
      val cases = Chunk(
        (Json.Obj("minimum" -> Json.Num(0)), Json.Num(-1)),
        (Json.Obj("enum" -> Json.Arr(Json.Str("ready"))), Json.Str("unknown")),
        (
          Json.Obj("type"  -> Json.Str("object"), "additionalProperties" -> Json.Bool(false)),
          Json.Obj("extra" -> Json.Bool(true))
        ),
        (
          Json.Obj(
            "type"       -> Json.Str("object"),
            "properties" -> Json.Obj("optional" -> Json.Obj("type" -> Json.Str("string")))
          ),
          Json.Obj("optional" -> Json.Null)
        ),
        (
          Json.Obj(
            "$defs" -> Json.Obj("positive" -> Json.Obj("minimum" -> Json.Num(0))),
            "$ref"  -> Json.Str("#/$defs/positive")
          ),
          Json.Num(-1)
        ),
        (Json.Bool(false), Json.Num(1))
      )
      ZIO
        .foreach(Chunk(StructuredMode.Native, StructuredMode.Auto)): mode =>
          ZIO.foreach(cases): (schema, payload) =>
            for
              client  <- LlmClient.mock(Chunk(TurnOutput.FinalText(Msg.assistant(payload.toJson))))
              agent   <- ReActAgent.make(client = client)
              outcome <- agent.runStructured[Json](Chunk(Msg.user("answer")), schema, mode).either
            yield assertTrue(outcome.left.exists(_.isInstanceOf[AgentError.DeserializationError]))
        .map(results => results.flatten.foldLeft(assertTrue(true))(_ && _))
    ,
    test("synthetic completion does not treat an optional null as an absent property"):
      val schema = Json.Obj(
        "type"       -> Json.Str("object"),
        "properties" -> Json.Obj("optional" -> Json.Obj("type" -> Json.Str("string")))
      )
      val payload = Json.Obj("optional" -> Json.Null)
      val call =
        ContentBlock.ToolUse("capture", StructuredOutput.ToolName, Json.Obj("response" -> payload))
      for
        client <- LlmClient.mock(Chunk(TurnOutput.ToolCalls(Msg.assistant(Chunk(call)))))
        agent  <- ReActAgent.make(client = client)
        outcome <- agent
          .runStructured[Json](Chunk(Msg.user("answer")), schema, StructuredMode.SyntheticTool)
          .either
      yield assertTrue(outcome.left.exists(_.isInstanceOf[AgentError.DeserializationError]))
    ,
    test("preserves explicitly nullable output and root definitions in every mode"):
      val schema = Json.Obj(
        "$defs" -> Json.Obj(
          "nullable" -> Json.Obj("type" -> Json.Arr(Json.Str("string"), Json.Str("null")))
        ),
        "type"       -> Json.Str("object"),
        "properties" -> Json.Obj("value" -> Json.Obj("$ref" -> Json.Str("#/$defs/nullable"))),
        "required"   -> Json.Arr(Json.Str("value"))
      )
      val payload = Json.Obj("value" -> Json.Null)
      ZIO
        .foreach(Chunk(StructuredMode.Native, StructuredMode.SyntheticTool, StructuredMode.Auto)):
          mode =>
            val reply = mode match
              case StructuredMode.SyntheticTool =>
                TurnOutput.ToolCalls(
                  Msg.assistant(
                    Chunk(
                      ContentBlock.ToolUse(
                        "capture",
                        StructuredOutput.ToolName,
                        Json.Obj("response" -> payload)
                      )
                    )
                  )
                )
              case _ => TurnOutput.FinalText(Msg.assistant(payload.toJson))
            for
              client    <- LlmClient.mock(Chunk(reply))
              agent     <- ReActAgent.make(client = client)
              completed <- agent.runStructured[Json](Chunk(Msg.user("answer")), schema, mode)
            yield assertTrue(
              completed._2 == payload,
              completed._1.reply.metadata
                .get(StructuredOutput.MetadataStructuredOutput)
                .contains(payload)
            )
        .map(_.foldLeft(assertTrue(true))(_ && _))
    ,
    test("schema rejection does not replay tools or publish a successful structured result"):
      val schema = Json.Obj("type" -> Json.Str("integer"), "minimum" -> Json.Num(0))
      val call   = ContentBlock.ToolUse("work", "work", Json.Obj())
      for
        requests   <- Ref.make(0)
        executions <- Ref.make(0)
        observed   <- Ref.make(Chunk.empty[AgentEvent])
        tool = Tool.make(ToolDef("work", "work", Json.Obj()))(_ =>
          executions.update(_ + 1).as("done")
        )
        toolkit <- ZIO.fromEither(Toolkit(tool))
        client = LlmClient.mockEffectful((_, _, _) =>
          requests
            .updateAndGet(_ + 1)
            .map: count =>
              if count == 1 then TurnOutput.ToolCalls(Msg.assistant(Chunk(call)))
              else TurnOutput.FinalText(Msg.assistant("-1"))
        )
        agent <- ReActAgent.make(
          client = client,
          toolkit = toolkit,
          llmConfig = LlmConfig(
            model = "mock",
            structuredOutputCapability = StructuredOutputCapability.Native()
          )
        )
        outcome <- agent
          .streamStructuredEvents[Json](Chunk(Msg.user("answer")), schema, StructuredMode.Auto)
          .tap(event => observed.update(_ :+ event))
          .runDrain
          .either
        events         <- observed.get
        history        <- agent.history
        requestCount   <- requests.get
        executionCount <- executions.get
      yield assertTrue(
        outcome.left.exists(_.isInstanceOf[AgentError.DeserializationError]),
        requestCount == 2,
        executionCount == 1,
        !events.exists(_.isInstanceOf[AgentEvent.AgentResultEvent]),
        events.lastOption.exists(_.isInstanceOf[AgentEvent.AgentEndEvent]),
        history
          .flatMap(_.toolResults)
          .exists(result =>
            result.id == "work" && result.state == com.weisgnosis.agents.core.model.ToolResultState.Success
          ),
        !history.exists(_.metadata.contains(StructuredOutput.MetadataStructuredOutput))
      )
    ,
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
