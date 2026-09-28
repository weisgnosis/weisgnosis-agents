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

import com.weisgnosis.agents.core.client.{LlmClient, TurnOutput}
import com.weisgnosis.agents.core.model.*
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

/**
 * 1:1 Parity Tests for Structured Output generation and schema hoisting (INV-11).
 *
 * Corresponds to `io.agentscope.core.agent.ReActAgentStructuredOutputTest` in AgentScope Java.
 */
object StructuredOutputSpec extends ZIOSpecDefault:

  final case class WeatherResponse(
      location: String,
      temperature: String,
      condition: String
  ) derives JsonCodec

  def spec: Spec[TestEnvironment & Scope, Any] =
    suite("StructuredOutputSpec (AgentScope Java 1:1 Parity)")(
      test("testStructuredOutputToolBased") {
        val payload = Json.Obj(
          Chunk(
            "location"    -> Json.Str("San Francisco"),
            "temperature" -> Json.Str("72°F"),
            "condition"   -> Json.Str("Sunny")
          )
        )

        val mockClient = LlmClient.mockEffectful { (history, _, _) =>
          val hasToolResults = history.exists(_.role == Role.Tool)
          if !hasToolResults then
            // First turn: call generate_response tool
            val call = ContentBlock.ToolUse(
              id = "call_123",
              name = "generate_response",
              input = Json.Obj(Chunk("response" -> payload))
            )
            ZIO.succeed(TurnOutput.ToolCalls(Msg(role = Role.Assistant, content = Chunk(call))))
          else
            // Second turn: finished
            ZIO.succeed(TurnOutput.FinalText(Msg.assistant("Response generated")))
        }

        val weatherSchema = Json.Obj(
          Chunk(
            "type" -> Json.Str("object"),
            "properties" -> Json.Obj(
              Chunk(
                "location"    -> Json.Obj(Chunk("type" -> Json.Str("string"))),
                "temperature" -> Json.Obj(Chunk("type" -> Json.Str("string"))),
                "condition"   -> Json.Obj(Chunk("type" -> Json.Str("string")))
              )
            )
          )
        )

        for
          agent <- ReActAgent
            .builder()
            .name("weather-agent")
            .model(mockClient)
            .build
          userMsg = Msg.user("What's the weather in San Francisco?")
          (result, weather) <- agent.runStructured[WeatherResponse](
            Chunk(userMsg),
            weatherSchema,
            StructuredMode.SyntheticTool
          )
          extractedFromMsg = result.reply.getStructuredData[WeatherResponse]
          context <- agent.history
          // Structured output transient messages should be compressed from context
          hasGenToolInContext = context.exists(_.toolUses.exists(_.name == "generate_response"))
        yield assertTrue(
          weather.location == "San Francisco",
          weather.temperature == "72°F",
          weather.condition == "Sunny",
          extractedFromMsg == Right(WeatherResponse("San Francisco", "72°F", "Sunny")),
          !hasGenToolInContext
        )
      },
      test("testHoistDefs correctly hoists $defs up to root schema (INV-11)") {
        val innerSchemaWithDefs = Json.Obj(
          Chunk(
            "type"       -> Json.Str("object"),
            "properties" -> Json.Obj(Chunk("item" -> Json.Str("ref"))),
            "$defs" -> Json.Obj(
              Chunk(
                "Item" -> Json.Obj(Chunk("type" -> Json.Str("string")))
              )
            )
          )
        )

        val paramsSchema  = StructuredOutput.buildParametersSchema(innerSchemaWithDefs)
        val obj           = paramsSchema.asObject
        val hasDefsAtRoot = obj.flatMap(_.get("$defs")).isDefined
        val innerResponse = obj
          .flatMap(_.get("properties"))
          .flatMap(_.asObject)
          .flatMap(_.get("response"))
          .flatMap(_.asObject)
        val hasDefsInInner = innerResponse.flatMap(_.get("$defs")).isDefined

        assertTrue(
          hasDefsAtRoot,
          !hasDefsInInner
        )
      },
      test("legacy definitions keep their reference namespace at the parameter root") {
        val schema = Json.Obj(
          "definitions" -> Json.Obj("Item" -> Json.Obj("type" -> Json.Str("string"))),
          "$ref"        -> Json.Str("#/definitions/Item")
        )
        val parameters = StructuredOutput.buildParametersSchema(schema)
        assertTrue(
          parameters.asObject.flatMap(_.get("definitions")).isDefined,
          parameters.toJson.contains("#/definitions/Item")
        )
      },
      test("synthetic completion stops immediately and preserves ordinary mixed-tool history") {
        for
          requests <- Ref.make(0)
          model = LlmClient.mockEffectful { (_, _, _) =>
            requests
              .update(_ + 1)
              .as(
                TurnOutput.ToolCalls(
                  Msg(
                    role = Role.Assistant,
                    content = Chunk(
                      ContentBlock.ToolUse("ordinary", "missing", Json.Obj()),
                      ContentBlock.ToolUse(
                        "structured",
                        StructuredOutput.ToolName,
                        Json.Obj("response" -> Json.Num(7))
                      )
                    ),
                    usage = Some(ChatUsage(inputTokens = 4, outputTokens = 3))
                  )
                )
              )
          }
          agent <- ReActAgent.make(client = model)
          result <- agent.runStructured[Int](
            Chunk(Msg.user("number")),
            Json.Obj("type" -> Json.Str("integer")),
            StructuredMode.SyntheticTool
          )
          count <- requests.get
        yield assertTrue(
          count == 1,
          result._2 == 7,
          result._1.history.flatMap(_.toolUses).map(_.id) == Chunk("ordinary"),
          result._1.history.flatMap(_.toolResults).map(_.id) == Chunk("ordinary"),
          result._1.usage.exists(_.totalTokens == 7)
        )
      },
      test("testParseStructuredResult parses metadata and text JSON") {
        val payload = WeatherResponse("Tokyo", "20°C", "Rainy")
        val jsonAst = payload.toJsonAST.getOrElse(Json.Obj())
        val msgWithMeta = Msg
          .assistant("Some reply")
          .withMetadata(StructuredOutput.MetadataStructuredOutput, jsonAst)
        val parsedFromMeta = msgWithMeta.getStructuredData[WeatherResponse]

        val msgWithText    = Msg.assistant(payload.toJson)
        val parsedFromText = msgWithText.getStructuredData[WeatherResponse]

        assertTrue(
          parsedFromMeta == Right(payload),
          parsedFromText == Right(payload)
        )
      }
    )
