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

import com.weisgnosis.agents.core.client.{LlmClient, LlmConfig, TurnOutput}
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.event.AgentEvent
import com.weisgnosis.agents.core.model.*
import com.weisgnosis.agents.core.tool.{Tool, ToolDef, Toolkit}
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

/**
 * Selected upstream scenario tests for Structured Output generation and schema hoisting (INV-11).
 *
 * Corresponds to `io.agentscope.core.agent.ReActAgentStructuredOutputTest` in AgentScope Java.
 */
object StructuredOutputSpec extends ZIOSpecDefault:

  final case class WeatherResponse(
      location: String,
      temperature: String,
      condition: String
  ) derives JsonCodec

  final case class Material(name: String, quantity: Int) derives JsonCodec
  final case class Materials(items: Chunk[Material]) derives JsonCodec

  private def observingClient(
      output: TurnOutput,
      observe: (Chunk[ToolDef], LlmConfig) => UIO[Unit]
  ): LlmClient =
    new LlmClient:
      def streamTurn(
          history: Chunk[Msg],
          tools: Chunk[ToolDef],
          config: LlmConfig,
          eventHub: Hub[AgentEvent]
      ): ZIO[Scope, AgentError, TurnOutput] =
        observe(tools, config).as(output)

  private val numberSchema: Json = Json.Obj("type" -> Json.Str("integer"))

  private def responseTurn(
      value: Int,
      preceding: Chunk[ContentBlock] = Chunk.empty,
      usage: Option[ChatUsage] = None
  ): TurnOutput =
    TurnOutput.ToolCalls(
      Msg.assistant(
        content = preceding :+ ContentBlock.ToolUse(
          "structured",
          StructuredOutput.ToolName,
          Json.Obj("response" -> Json.Num(value))
        ),
        usage = usage,
        name = None
      )
    )

  def spec: Spec[TestEnvironment & Scope, Any] =
    suite("StructuredOutputSpec (Selected upstream scenarios)")(
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
            ZIO.succeed(TurnOutput.ToolCalls(Msg.assistant(content = Chunk(call), name = None)))
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
                  Msg.assistant(
                    content = Chunk(
                      ContentBlock.ToolUse("ordinary", "missing", Json.Obj()),
                      ContentBlock.ToolUse(
                        "structured",
                        StructuredOutput.ToolName,
                        Json.Obj("response" -> Json.Num(7))
                      )
                    ),
                    usage = Some(ChatUsage(inputTokens = 4, outputTokens = 3)),
                    name = None
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
      test("testStructuredOutputWithoutNewMessages preserves existing and empty user messages") {
        val original = Chunk(Msg.user("Give a number"), Msg.user(""))
        for
          observed <- Ref.make(Chunk.empty[Msg])
          client = LlmClient.mockEffectful { (history, _, _) =>
            observed.set(history).as(responseTurn(7))
          }
          agent <- ReActAgent.make(client = client, initialHistory = original)
          result <- agent
            .runStructured[Int](Chunk.empty, numberSchema, StructuredMode.SyntheticTool)
          request <- observed.get
          saved   <- agent.history
        yield assertTrue(
          result._2 == 7,
          request == original,
          saved == result._1.history,
          saved.take(original.size) == original,
          saved.filter(_.role == Role.User) == original
        )
      },
      test("testStructuredOutputPreservesThinkingBlock transfers signed thinking exactly once") {
        val thought =
          ContentBlock.Thinking("Check the answer", Map("thoughtSignature" -> Json.Str("signed")))
        val earlier =
          Msg.assistant(content = Chunk(ContentBlock.Thinking("Earlier reasoning")), name = None)
        for
          client <- LlmClient.mock(Chunk(responseTurn(7, Chunk(thought))))
          agent  <- ReActAgent.make(client = client, initialHistory = Chunk(earlier))
          result <- agent.runStructured[Int](
            Chunk(Msg.user("number")),
            numberSchema,
            StructuredMode.SyntheticTool
          )
        yield assertTrue(
          result._1.reply.content == Chunk(thought, ContentBlock.Text("7")),
          result._1.history.flatMap(_.content).count(_ == thought) == 1,
          result._1.history.headOption.contains(earlier),
          result._1.history.lastOption.contains(result._1.reply),
          result._1.reply.getStructuredData[Int] == Right(7)
        )
      },
      test("testStructuredOutputPreservesChatUsage retains every generating-request metric") {
        val usage = ChatUsage(100, 50, 12, 8, 9, 4, 1.5)
        for
          client <- LlmClient.mock(Chunk(responseTurn(7, usage = Some(usage))))
          agent  <- ReActAgent.make(client = client)
          result <- agent.runStructured[Int](
            Chunk(Msg.user("number")),
            numberSchema,
            StructuredMode.SyntheticTool
          )
        yield assertTrue(
          result._1.usage.contains(usage),
          result._1.reply.usage.contains(usage),
          result._1.history.flatMap(_.usage) == Chunk(usage)
        )
      },
      test("multi-turn structured completion keeps per-request usage without double counting") {
        val firstUsage = ChatUsage(inputTokens = 10, outputTokens = 4, time = 0.5)
        val finalUsage = ChatUsage(inputTokens = 20, outputTokens = 6, time = 1.5)
        val thought    = ContentBlock.Thinking("Ready to answer")
        val first = TurnOutput.ToolCalls(
          Msg.assistant(
            content = Chunk(ContentBlock.ToolUse("first", "missing", Json.Obj())),
            usage = Some(firstUsage),
            name = None
          )
        )
        val finalTurn = responseTurn(
          7,
          Chunk(thought, ContentBlock.ToolUse("second", "missing", Json.Obj())),
          Some(finalUsage)
        )
        for
          client <- LlmClient.mock(Chunk(first, finalTurn))
          agent  <- ReActAgent.make(client = client)
          result <- agent.runStructured[Int](
            Chunk(Msg.user("number")),
            numberSchema,
            StructuredMode.SyntheticTool
          )
        yield assertTrue(
          result._1.usage.contains(firstUsage.combine(finalUsage)),
          result._1.reply.usage.contains(finalUsage),
          result._1.history.flatMap(_.usage) == Chunk(firstUsage, finalUsage),
          result._1.history.flatMap(_.toolUses).map(_.id) == Chunk("first", "second"),
          result._1.history.flatMap(_.toolResults).map(_.id) == Chunk("first", "second"),
          result._1.history.flatMap(_.content).count(_ == thought) == 1
        )
      },
      test(
        "testConcurrencyConflictStructuredOutput isolates consecutive captures and toolkit registration"
      ) {
        val ordinary =
          Tool.make(ToolDef("ordinary", "An application tool", Json.Obj()))(_ => ZIO.succeed("ok"))
        for
          toolkit     <- ZIO.fromEither(Toolkit.empty.register(ordinary))
          definitions <- Ref.make(Chunk.empty[Chunk[String]])
          counter     <- Ref.make(0)
          client = LlmClient.mockEffectful { (_, tools, _) =>
            definitions
              .update(_ :+ tools.map(_.name)) *> counter.updateAndGet(_ + 1).map(responseTurn(_))
          }
          agent <- ReActAgent.make(client = client, toolkit = toolkit)
          firstFiber <- agent
            .runStructured[Int](
              Chunk(Msg.user("first")),
              numberSchema,
              StructuredMode.SyntheticTool
            )
            .forkScoped
          first <- firstFiber.join
          second <- agent.runStructured[Int](
            Chunk(Msg.user("second")),
            numberSchema,
            StructuredMode.SyntheticTool
          )
          offered <- definitions.get
          saved   <- agent.history
        yield assertTrue(
          first._2 == 1,
          second._2 == 2,
          offered.size == 2,
          offered.forall(names =>
            names.toSet == Set("ordinary", StructuredOutput.ToolName) && names.size == 2
          ),
          toolkit.definitions.map(_.name) == Chunk("ordinary"),
          agent.toolkit.definitions == toolkit.definitions,
          saved.filter(_.role == Role.Assistant).map(_.getStructuredData[Int]) == Chunk(
            Right(1),
            Right(2)
          )
        )
      },
      test("compression preserves unrelated empty messages and removes only synthetic content") {
        val empty =
          Chunk(Msg.system(name = None), Msg.user(name = None), Msg.assistant(name = None))
        val call = Msg.assistant(
          content = Chunk(ContentBlock.ToolUse("capture", StructuredOutput.ToolName, Json.Obj())),
          name = None
        )
        val result = Msg.tool(
          content = Chunk(ContentBlock.ToolResult.text("capture", StructuredOutput.ToolName, "ok")),
          name = None
        )
        assertTrue(StructuredOutput.compressHistory(empty ++ Chunk(call, result)) == empty)
      },
      test("noTools_nativeSoSupported_usesNativePath preserves usage and omits synthetic tools") {
        val usage = ChatUsage(100, 50, 12, 8, 9, 4, 1.5)
        val reply = Msg.assistant("7").withUsage(usage)
        for
          requests <- Ref.make(Chunk.empty[(Chunk[ToolDef], Option[Json])])
          client = observingClient(
            TurnOutput.FinalText(reply),
            (tools, config) => requests.update(_ :+ (tools, config.responseFormat))
          )
          agent <- ReActAgent.make(client = client)
          result <- agent
            .runStructured[Int](Chunk(Msg.user("number")), numberSchema, StructuredMode.Auto)
          observed <- requests.get
        yield assertTrue(
          result._2 == 7,
          result._1.reply.usage.contains(usage),
          result._1.usage.contains(usage),
          observed.size == 1,
          observed.forall { case (tools, format) => tools.isEmpty && format.isDefined }
        )
      },
      test(
        "hasTools_withToolsNotSupported_usesFallbackPath retains application tools and decoded data"
      ) {
        val ordinary =
          Tool.make(ToolDef("ordinary", "An application tool", Json.Obj()))(_ => ZIO.succeed("ok"))
        for
          toolkit  <- ZIO.fromEither(Toolkit.empty.register(ordinary))
          requests <- Ref.make(Chunk.empty[(Chunk[ToolDef], Option[Json])])
          client = observingClient(
            responseTurn(7),
            (tools, config) => requests.update(_ :+ (tools, config.responseFormat))
          )
          agent <- ReActAgent.make(client = client, toolkit = toolkit)
          result <- agent
            .runStructured[Int](Chunk(Msg.user("number")), numberSchema, StructuredMode.Auto)
          observed <- requests.get
        yield assertTrue(
          result._2 == 7,
          result._1.reply.getStructuredData[Int] == Right(7),
          observed.size == 1,
          observed.forall { case (tools, format) =>
            tools.map(_.name).toSet == Set("ordinary", StructuredOutput.ToolName) && format.isEmpty
          }
        )
      },
      test(
        "testDefsHoistedToRootForRefResolution retains real references and decodes nested data"
      ) {
        val materialSchema = Json.Obj(
          "type" -> Json.Str("object"),
          "properties" -> Json.Obj(
            "name"     -> Json.Obj("type" -> Json.Str("string")),
            "quantity" -> Json.Obj("type" -> Json.Str("integer"))
          ),
          "required" -> Json.Arr(Json.Str("name"), Json.Str("quantity"))
        )
        val reference   = Json.Obj("$ref" -> Json.Str("#/$defs/Material"))
        val itemsSchema = Json.Obj("type" -> Json.Str("array"), "items" -> reference)
        val schema = Json.Obj(
          "type"       -> Json.Str("object"),
          "properties" -> Json.Obj("items" -> itemsSchema),
          "$defs"      -> Json.Obj("Material" -> materialSchema),
          "required"   -> Json.Arr(Json.Str("items"))
        )
        val expected = Materials(Chunk(Material("Wood", 10), Material("Stone", 5)))
        for
          payload    <- ZIO.fromEither(expected.toJsonAST)
          parameters <- Ref.make(Option.empty[Json])
          client = LlmClient.mockEffectful { (_, tools, _) =>
            parameters
              .set(tools.find(_.name == StructuredOutput.ToolName).map(_.parametersSchema))
              .as(
                TurnOutput.ToolCalls(
                  Msg.assistant(
                    content = Chunk(
                      ContentBlock.ToolUse(
                        "nested",
                        StructuredOutput.ToolName,
                        Json.Obj("response" -> payload)
                      )
                    ),
                    name = None
                  )
                )
              )
          }
          agent <- ReActAgent.make(client = client)
          result <- agent.runStructured[Materials](
            Chunk(Msg.user("materials")),
            schema,
            StructuredMode.SyntheticTool
          )
          captured <- parameters.get
          root = captured.flatMap(_.asObject)
          response = root
            .flatMap(_.get("properties"))
            .flatMap(_.asObject)
            .flatMap(_.get("response"))
            .flatMap(_.asObject)
        yield assertTrue(
          result._2 == expected,
          result._1.reply.getStructuredData[Materials] == Right(expected),
          root.flatMap(_.get("$defs")).contains(Json.Obj("Material" -> materialSchema)),
          response.flatMap(_.get("$defs")).isEmpty,
          response.flatMap(_.get("properties")).contains(Json.Obj("items" -> itemsSchema))
        )
      },
      test(
        "interruption after capture preserves recovery instead of publishing structured success"
      ) {
        val thought = ContentBlock.Thinking("Need the tool result")
        for
          started <- Promise.make[Nothing, Unit]
          waiting = Tool.make(ToolDef("waiting", "Wait for completion", Json.Obj()))(_ =>
            started.succeed(()).unit *> ZIO.never
          )
          toolkit <- ZIO.fromEither(Toolkit.empty.register(waiting))
          modelReply = Msg.assistant(
            content = Chunk(
              thought,
              ContentBlock
                .ToolUse("capture", StructuredOutput.ToolName, Json.Obj("response" -> Json.Num(7))),
              ContentBlock.ToolUse("pending", "waiting", Json.Obj())
            ),
            usage = Some(ChatUsage(inputTokens = 10, outputTokens = 3)),
            name = None
          )
          client <- LlmClient.mock(Chunk(TurnOutput.ToolCalls(modelReply)))
          agent  <- ReActAgent.make(client = client, toolkit = toolkit)
          running <- agent
            .runStructured[Int](
              Chunk(Msg.user("number")),
              numberSchema,
              StructuredMode.SyntheticTool
            )
            .either
            .forkScoped
          _       <- started.await
          _       <- agent.interrupt
          result  <- running.join
          history <- agent.history
          interrupted = result match
            case Left(_: AgentError.Interrupted) => true
            case _                               => false
        yield assertTrue(
          interrupted,
          history.lastOption.flatMap(_.generateReason).contains("interrupted"),
          history.count(_.generateReason.contains("interrupted")) == 1,
          history.forall(!_.metadata.contains(StructuredOutput.MetadataStructuredOutput)),
          history.flatMap(_.content).count(_ == thought) == 1,
          history.flatMap(_.toolResults).map(r => r.id -> r.state).toMap == Map(
            "capture" -> ToolResultState.Success,
            "pending" -> ToolResultState.Error
          )
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
