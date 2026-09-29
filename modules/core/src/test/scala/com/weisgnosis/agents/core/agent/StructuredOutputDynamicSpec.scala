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
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.event.AgentEvent
import com.weisgnosis.agents.core.model.{ContentBlock, Msg}
import com.weisgnosis.agents.core.tool.ToolDef
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

/** Runtime JSON schemas and untyped JSON results from StructuredOutputDynamicDefineTest. */
object StructuredOutputDynamicSpec extends ZIOSpecDefault:
  private val schemaSource: String = """{
    "type":"object",
    "properties":{
      "location":{"type":"string"},
      "temperature":{"type":"string"},
      "condition":{"type":"string"}
    },
    "required":["location","temperature","condition"],
    "additionalProperties":false
  }"""

  private val weather: Json = Json.Obj(
    "location"    -> Json.Str("San Francisco"),
    "temperature" -> Json.Str("72\u00b0F"),
    "condition"   -> Json.Str("Sunny")
  )

  final private case class Fixture(
      agent: ReActAgent,
      schema: Json,
      requests: Ref[Chunk[Chunk[ToolDef]]]
  ):
    def expectedParameters: Json = Json.Obj(
      "type"       -> Json.Str("object"),
      "properties" -> Json.Obj("response" -> schema),
      "required"   -> Json.Arr(Json.Str("response"))
    )

  private def fixture: IO[AgentError, Fixture] =
    for
      schema <- ZIO
        .fromEither(schemaSource.fromJson[Json])
        .mapError(AgentError.DeserializationError(_))
      requests <- Ref.make(Chunk.empty[Chunk[ToolDef]])
      call = ContentBlock.ToolUse(
        "weather-response",
        StructuredOutput.ToolName,
        Json.Obj("response" -> weather)
      )
      model = LlmClient.mockEffectful((_, tools, _) =>
        requests.update(_ :+ tools).as(TurnOutput.ToolCalls(Msg.assistant(Chunk(call))))
      )
      agent <- ReActAgent.make(client = model)
    yield Fixture(agent, schema, requests)

  def spec: Spec[TestEnvironment & Scope, Any] = suite("StructuredOutputDynamicSpec")(
    test("testDynamicComplexNestedStructure") {
      for
        setup <- fixture
        completed <- setup.agent.runStructured[Json](
          Chunk(Msg.user("What's the weather in San Francisco?")),
          setup.schema,
          StructuredMode.SyntheticTool
        )
        requests <- setup.requests.get
        history  <- setup.agent.history
      yield assertTrue(
        completed._2 == weather,
        completed._1.reply.getStructuredData[Json] == Right(weather),
        completed._1.reply.metadata
          .get(StructuredOutput.MetadataStructuredOutput)
          .contains(weather),
        history.lastOption.contains(completed._1.reply),
        !history.exists(StructuredOutput.isStructuredOutputRelated),
        requests.map(_.map(_.name)) == Chunk(Chunk(StructuredOutput.ToolName)),
        requests.flatten.map(_.parametersSchema) == Chunk(setup.expectedParameters)
      )
    },
    test("testDynamicComplexNestedStructureStreamingMode") {
      for
        setup <- fixture
        events <- setup.agent
          .streamStructuredEvents[Json](
            Chunk(Msg.user("What's the weather in San Francisco?")),
            setup.schema,
            StructuredMode.SyntheticTool
          )
          .runCollect
        requests <- setup.requests.get
        history  <- setup.agent.history
        replies = events.collect { case event: AgentEvent.AgentResultEvent => event.result }
      yield assertTrue(
        replies.size == 1,
        replies.forall(_.getStructuredData[Json] == Right(weather)),
        replies.forall(_.metadata.get(StructuredOutput.MetadataStructuredOutput).contains(weather)),
        replies.headOption == history.lastOption,
        events.dropRight(1).lastOption.exists(_.isInstanceOf[AgentEvent.AgentResultEvent]),
        events.lastOption.exists(_.isInstanceOf[AgentEvent.AgentEndEvent]),
        !history.exists(StructuredOutput.isStructuredOutputRelated),
        requests.map(_.map(_.name)) == Chunk(Chunk(StructuredOutput.ToolName)),
        requests.flatten.map(_.parametersSchema) == Chunk(setup.expectedParameters)
      )
    }
  )
