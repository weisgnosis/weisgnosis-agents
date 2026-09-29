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

import com.weisgnosis.agents.core.client.*
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.event.{AgentEvent, ModelStreamContext}
import com.weisgnosis.agents.core.model.{ContentBlock, Msg, Role}
import com.weisgnosis.agents.core.tool.{Tool, ToolDef, Toolkit}
import zio.*
import zio.json.ast.Json
import zio.test.*

object StructuredOutputCapabilityRoutingSpec extends ZIOSpecDefault:
  private val schema: Json = Json.Obj("type" -> Json.Str("integer"))
  private val applicationTool: Tool[Any, Json, String] =
    Tool.make(ToolDef("lookup", "Read a value", Json.Obj()))(_ => ZIO.succeed("7"))

  final private case class Request(history: Chunk[Msg], tools: Chunk[ToolDef], config: LlmConfig)

  private def client(
      requests: Ref[Chunk[Request]],
      respond: Request => IO[AgentError, TurnOutput]
  ): LlmClient = new LlmClient:
    def streamTurn(
        history: Chunk[Msg],
        tools: Chunk[ToolDef],
        config: LlmConfig,
        eventHub: Hub[AgentEvent],
        context: ModelStreamContext
    ): ZIO[Scope, AgentError, TurnOutput] =
      val request = Request(history, tools, config)
      requests.update(_ :+ request) *> respond(request)

  private def answer(request: Request): UIO[TurnOutput] =
    ZIO.succeed:
      if request.tools.exists(_.name == StructuredOutput.ToolName) then
        TurnOutput.ToolCalls(
          Msg.assistant(
            Chunk(
              ContentBlock.ToolUse(
                "capture",
                StructuredOutput.ToolName,
                Json.Obj("response" -> Json.Num(7))
              )
            )
          )
        )
      else TurnOutput.FinalText(Msg.assistant("7"))

  private def observeRoute(
      capability: StructuredOutputCapability,
      tools: Chunk[Tool[Any, Json, String]],
      mode: StructuredMode = StructuredMode.Auto
  ): ZIO[Scope, AgentError, (Chunk[Request], (AgentResult, Int))] =
    for
      toolkit <- ZIO.fromEither(
        tools.foldLeft[Either[AgentError, Toolkit]](Right(Toolkit.empty))((current, tool) =>
          current.flatMap(_.register(tool))
        )
      )
      requests <- Ref.make(Chunk.empty[Request])
      agent <- ReActAgent.make(
        client = client(requests, answer),
        toolkit = toolkit,
        llmConfig = LlmConfig("configured", structuredOutputCapability = capability)
      )
      result   <- agent.runStructured[Int](Chunk(Msg.user("number")), schema, mode)
      observed <- requests.get
    yield (observed, result)

  private def native(requests: Chunk[Request]): Boolean =
    requests.size == 1 && requests.forall(request =>
      request.config.responseFormat.isDefined && !request.tools.exists(
        _.name == StructuredOutput.ToolName
      )
    )

  private def synthetic(requests: Chunk[Request]): Boolean =
    requests.size == 1 && requests.forall(request =>
      request.config.responseFormat.isEmpty && request.tools.count(
        _.name == StructuredOutput.ToolName
      ) == 1
    )

  def spec: Spec[TestEnvironment & Scope, Any] = suite("StructuredOutputCapabilityRoutingSpec")(
    test("noTools_nativeSoSupported_usesNativePath") {
      observeRoute(StructuredOutputCapability.Native(), Chunk.empty).map {
        case (requests, result) =>
          assertTrue(native(requests), result._2 == 7, requests.forall(_.tools.isEmpty))
      }
    },
    test("noTools_nativeSoNotSupported_usesFallbackPath without probing native formatting") {
      observeRoute(StructuredOutputCapability.Unsupported, Chunk.empty).map {
        case (requests, result) =>
          assertTrue(synthetic(requests), result._2 == 7, requests.forall(_.tools.size == 1))
      }
    },
    test("hasTools_withToolsSupported_usesNativePath") {
      observeRoute(StructuredOutputCapability.Native(), Chunk(applicationTool)).map {
        case (requests, result) =>
          assertTrue(
            native(requests),
            result._2 == 7,
            requests.forall(_.tools.map(_.name) == Chunk("lookup"))
          )
      }
    },
    test(
      "hasTools_withToolsNotSupported_usesFallbackPath preserves application tools and structured data"
    ) {
      observeRoute(
        StructuredOutputCapability.Native(NativeToolSupport.Unsupported),
        Chunk(applicationTool)
      ).map { case (requests, result) =>
        assertTrue(
          synthetic(requests),
          result._2 == 7,
          result._1.reply.getStructuredData[Int] == Right(7),
          requests.forall(_.tools.map(_.name).toSet == Set("lookup", StructuredOutput.ToolName))
        )
      }
    },
    test(
      "native support restricted to requests without tools still selects native for an empty toolkit"
    ) {
      observeRoute(StructuredOutputCapability.Native(NativeToolSupport.Unsupported), Chunk.empty)
        .map { case (requests, result) =>
          assertTrue(native(requests), result._2 == 7)
        }
    },
    test("unsupported native formatting with application tools selects synthetic without a probe") {
      observeRoute(StructuredOutputCapability.Unsupported, Chunk(applicationTool)).map {
        case (requests, result) => assertTrue(synthetic(requests), result._2 == 7)
      }
    },
    test(
      "unknown capability preserves native probing without tools and synthetic selection with tools"
    ) {
      for
        withoutTools <- observeRoute(StructuredOutputCapability.Unknown, Chunk.empty)
        withTools    <- observeRoute(StructuredOutputCapability.Unknown, Chunk(applicationTool))
      yield assertTrue(
        native(withoutTools._1),
        synthetic(withTools._1),
        withoutTools._2._2 == 7,
        withTools._2._2 == 7
      )
    },
    test("explicit modes override host capability declarations") {
      for
        forcedNative <- observeRoute(
          StructuredOutputCapability.Unsupported,
          Chunk(applicationTool),
          StructuredMode.Native
        )
        forcedSynthetic <- observeRoute(
          StructuredOutputCapability.Native(),
          Chunk.empty,
          StructuredMode.SyntheticTool
        )
      yield assertTrue(native(forcedNative._1), synthetic(forcedSynthetic._1))
    },
    test("native with tools executes an application call before returning structured data") {
      for
        requests   <- Ref.make(Chunk.empty[Request])
        executions <- Ref.make(0)
        tool = Tool.make(applicationTool.definition)(_ => executions.update(_ + 1).as("7"))
        toolkit <- ZIO.fromEither(Toolkit.empty.register(tool))
        model = client(
          requests,
          request =>
            if request.history.exists(_.role == Role.Tool) then answer(request)
            else
              ZIO.succeed(
                TurnOutput.ToolCalls(
                  Msg.assistant(Chunk(ContentBlock.ToolUse("lookup-call", "lookup", Json.Obj())))
                )
              )
        )
        agent <- ReActAgent.make(
          client = model,
          toolkit = toolkit,
          llmConfig = LlmConfig(
            "configured",
            structuredOutputCapability = StructuredOutputCapability.Native()
          )
        )
        result   <- agent.runStructured[Int](Chunk(Msg.user("number")), schema)
        observed <- requests.get
        count    <- executions.get
      yield assertTrue(
        result._2 == 7,
        count == 1,
        observed.size == 2,
        observed.forall(_.config.responseFormat.isDefined),
        observed.forall(_.tools.map(_.name) == Chunk("lookup")),
        result._1.history.flatMap(_.toolResults).map(_.id) == Chunk("lookup-call")
      )
    },
    test("synthetic selection clears a preset response format while preserving other settings") {
      val configured = LlmConfig(
        "configured",
        temperature = 0.25,
        maxTokens = Some(128),
        responseFormat = Some(Json.Obj("type" -> Json.Str("json_object"))),
        structuredOutputCapability = StructuredOutputCapability.Unsupported
      )
      for
        requests <- Ref.make(Chunk.empty[Request])
        agent    <- ReActAgent.make(client = client(requests, answer), llmConfig = configured)
        result   <- agent.runStructured[Int](Chunk(Msg.user("number")), schema)
        observed <- requests.get
      yield assertTrue(
        synthetic(observed),
        result._2 == 7,
        observed.map(_.config) == Chunk(configured.copy(responseFormat = None))
      )
    },
    test(
      "explicit synthetic mode and rejection fallback clear preset formats while ordinary runs preserve them"
    ) {
      val configured = LlmConfig(
        "configured",
        responseFormat = Some(Json.Obj("type" -> Json.Str("json_object"))),
        structuredOutputCapability = StructuredOutputCapability.Native()
      )
      for
        requests <- Ref.make(Chunk.empty[Request])
        model = client(
          requests,
          request =>
            if request.config.responseFormat
                .exists(_.asObject.flatMap(_.get("type")).contains(Json.Str("json_schema")))
            then ZIO.fail(AgentError.ModelError(400, "unsupported format"))
            else answer(request)
        )
        agent    <- ReActAgent.make(client = model, llmConfig = configured)
        ordinary <- agent.run(Msg.user("ordinary"))
        explicit <- agent
          .runStructured[Int](Chunk(Msg.user("explicit")), schema, StructuredMode.SyntheticTool)
        fallback <- agent.runStructured[Int](Chunk(Msg.user("fallback")), schema)
        observed <- requests.get
      yield assertTrue(
        ordinary.reply.text == "7",
        explicit._2 == 7,
        fallback._2 == 7,
        observed.size == 4,
        observed.headOption.exists(_.config == configured),
        observed.drop(1).map(_.config.responseFormat.isDefined) == Chunk(false, true, false),
        observed
          .filter(_.tools.exists(_.name == StructuredOutput.ToolName))
          .forall(_.config == configured.copy(responseFormat = None))
      )
    },
    test("declared native support retains rejection fallback without duplicating the user input") {
      for
        requests <- Ref.make(Chunk.empty[Request])
        model = client(
          requests,
          request =>
            if request.config.responseFormat.isDefined then
              ZIO.fail(AgentError.ModelError(400, "unsupported format"))
            else answer(request)
        )
        agent <- ReActAgent.make(
          client = model,
          llmConfig = LlmConfig(
            "configured",
            structuredOutputCapability = StructuredOutputCapability.Native()
          )
        )
        result   <- agent.runStructured[Int](Chunk(Msg.user("number")), schema)
        observed <- requests.get
      yield assertTrue(
        result._2 == 7,
        observed.size == 2,
        observed.map(_.config.responseFormat.isDefined) == Chunk(true, false),
        observed.forall(_.history.count(_.role == Role.User) == 1),
        result._1.history.count(_.role == Role.User) == 1
      )
    }
  )
