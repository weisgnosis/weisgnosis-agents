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

import com.weisgnosis.agents.core.client.{ChatCompletionsClient, LlmConfig}
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.event.AgentEvent
import com.weisgnosis.agents.core.model.Msg
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

object StructuredOutputHttpSpec extends ZIOSpecDefault:
  final private case class Answer(number: Int) derives JsonCodec
  private val payload = Json.Obj("number" -> Json.Num(7))
  private val schema = Json.Obj(
    "type"       -> Json.Str("object"),
    "properties" -> Json.Obj("number" -> Json.Obj("type" -> Json.Str("integer"))),
    "required"   -> Json.Arr(Json.Str("number"))
  )
  private val definitions = Json.Obj("Value" -> Json.Obj("type" -> Json.Str("integer")))
  private val referencedSchema = Json.Obj(
    "type"       -> Json.Str("object"),
    "properties" -> Json.Obj("number" -> Json.Obj("$ref" -> Json.Str("#/$defs/Value"))),
    "required"   -> Json.Arr(Json.Str("number")),
    "$defs"      -> definitions
  )

  def spec: Spec[TestEnvironment & Scope, Any] = suite("Structured output over HTTP")(
    test("native mode forwards the schema and preserves thinking usage and typed data"):
      withServer(_ => nativeResponse(payload.toJson)): (client, config, requests) =>
        for
          agent <- ReActAgent.make(client = client, llmConfig = config)
          result <- agent
            .runStructured[Answer](Chunk(Msg.user("number")), schema, StructuredMode.Native)
          bodies <- requests.get
        yield assertTrue(
          result._2 == Answer(7),
          result._1.reply.thinking == "reason",
          result._1.reply.usage.exists(usage => usage.inputTokens == 2 && usage.outputTokens == 3),
          result._1.reply.metadata.get(StructuredOutput.MetadataStructuredOutput).contains(payload),
          bodies.size == 1,
          bodies.headOption
            .flatMap(field(_, "response_format"))
            .flatMap(field(_, "json_schema"))
            .flatMap(field(_, "schema"))
            .contains(schema),
          bodies.forall(field(_, "tools").isEmpty)
        )
    ,
    test(
      "synthetic streaming hoists definitions and commits one compressed result without another request"
    ):
      withServer(_ => syntheticResponse): (client, config, requests) =>
        for
          agent <- ReActAgent.make(client = client, llmConfig = config)
          events <- agent
            .streamStructuredEvents[Answer](
              Chunk(Msg.user("number")),
              referencedSchema,
              StructuredMode.SyntheticTool
            )
            .runCollect
          history <- agent.history
          bodies  <- requests.get
          parameters = bodies.headOption
            .flatMap(field(_, "tools"))
            .flatMap(_.asArray)
            .flatMap(_.headOption)
            .flatMap(field(_, "function"))
            .flatMap(field(_, "parameters"))
        yield assertTrue(
          bodies.size == 1,
          parameters.flatMap(field(_, "$defs")).contains(definitions),
          parameters
            .flatMap(field(_, "properties"))
            .flatMap(field(_, "response"))
            .flatMap(field(_, "$defs"))
            .isEmpty,
          history.flatMap(_.toolUses).isEmpty,
          history.flatMap(_.toolResults).isEmpty,
          events.collect { case event: AgentEvent.AgentResultEvent =>
            event.result.metadata.get(StructuredOutput.MetadataStructuredOutput)
          } == Chunk(Some(payload)),
          events.count(_.isInstanceOf[AgentEvent.ModelRequestStartEvent]) == 1,
          events.lastOption.exists(_.isInstanceOf[AgentEvent.AgentEndEvent])
        )
    ,
    test("automatic fallback uses actual HTTP rejection without duplicating user input"):
      withServer(index =>
        if index == 0 then
          Response(
            status = Status.BadRequest,
            body = Body.fromString("unsupported response format")
          )
        else syntheticResponse
      ): (client, config, requests) =>
        for
          agent <- ReActAgent.make(client = client, llmConfig = config)
          events <- agent
            .streamStructuredEvents[Answer](Chunk(Msg.user("number")), schema, StructuredMode.Auto)
            .runCollect
          bodies  <- requests.get
          history <- agent.history
        yield assertTrue(
          bodies.size == 2,
          bodies.headOption.exists(field(_, "response_format").nonEmpty),
          bodies
            .drop(1)
            .headOption
            .exists(body =>
              field(body, "response_format").isEmpty && field(body, "tools").nonEmpty
            ),
          history.count(_.text == "number") == 1,
          events.count(_.isInstanceOf[AgentEvent.AgentStartEvent]) == 2,
          events.count(_.isInstanceOf[AgentEvent.AgentEndEvent]) == 2,
          events.count(_.isInstanceOf[AgentEvent.AgentResultEvent]) == 1
        )
    ,
    test("HTTP 503 does not trigger a synthetic retry"):
      withServer(_ =>
        Response(status = Status.ServiceUnavailable, body = Body.fromString("unavailable"))
      ): (client, config, requests) =>
        for
          agent <- ReActAgent.make(client = client, llmConfig = config)
          result <- agent
            .runStructured[Answer](Chunk(Msg.user("number")), schema, StructuredMode.Auto)
            .either
          bodies <- requests.get
        yield assertTrue(
          bodies.size == 1,
          result.left.exists {
            case AgentError.ModelError(503, _, _) => true
            case _                                => false
          }
        )
    ,
    test("rejects trailing native JSON without publishing structured success"):
      withServer(_ => nativeResponse(payload.toJson + " trailing")): (client, config, requests) =>
        for
          observed <- Ref.make(Chunk.empty[AgentEvent])
          agent    <- ReActAgent.make(client = client, llmConfig = config)
          result <- agent
            .streamStructuredEvents[Answer](
              Chunk(Msg.user("number")),
              schema,
              StructuredMode.Native
            )
            .tap(event => observed.update(_ :+ event))
            .runDrain
            .either
          events  <- observed.get
          bodies  <- requests.get
          history <- agent.history
        yield assertTrue(
          result.isLeft,
          bodies.size == 1,
          !events.exists(_.isInstanceOf[AgentEvent.AgentResultEvent]),
          history.forall(!_.metadata.contains(StructuredOutput.MetadataStructuredOutput)),
          events.lastOption.exists(_.isInstanceOf[AgentEvent.AgentEndEvent])
        )
  ) @@ TestAspect.timeout(20.seconds)

  private def field(json: Json, name: String): Option[Json] = json.asObject.flatMap(_.get(name))

  private def choice(delta: Json, finish: Option[String] = None): Json =
    Json.Obj(
      "choices" -> Json.Arr(
        Json.Obj(
          Chunk("index" -> Json.Num(0), "delta" -> delta) ++ Chunk.fromIterable(
            finish.map(value => "finish_reason" -> Json.Str(value))
          )
        )
      )
    )

  private def nativeResponse(content: String): Response = sse(
    Chunk(
      choice(Json.Obj("reasoning_content" -> Json.Str("reason"))),
      choice(Json.Obj("content" -> Json.Str(content))),
      choice(Json.Obj(), Some("stop")),
      Json.Obj(
        "choices" -> Json.Arr(),
        "usage"   -> Json.Obj("prompt_tokens" -> Json.Num(2), "completion_tokens" -> Json.Num(3))
      )
    )
  )

  private def syntheticResponse: Response = sse(
    Chunk(
      choice(
        Json.Obj(
          "tool_calls" -> Json.Arr(
            Json.Obj(
              "index" -> Json.Num(0),
              "id"    -> Json.Str("structured"),
              "function" -> Json.Obj(
                "name"      -> Json.Str(StructuredOutput.ToolName),
                "arguments" -> Json.Str(Json.Obj("response" -> payload).toJson)
              )
            )
          )
        ),
        Some("tool_calls")
      )
    )
  )

  private def sse(frames: Chunk[Json]): Response =
    Response(
      headers = Headers("Content-Type", "text/event-stream"),
      body = Body.fromString(
        frames.map(json => s"data: ${json.toJson}\n\n").mkString + "data: [DONE]\n\n"
      )
    )

  private def withServer(respond: Int => Response)(
      check: (ChatCompletionsClient, LlmConfig, Ref[Chunk[Json]]) => ZIO[
        Scope,
        Throwable,
        TestResult
      ]
  ): ZIO[Any, Throwable, TestResult] =
    ZIO
      .scoped:
        for
          requests <- Ref.make(Chunk.empty[Json])
          port <- Server.install(Routes(Method.POST / "completion" -> handler {
            (request: Request) =>
              request.body.asString.either.flatMap:
                case Left(_) => ZIO.succeed(Response.badRequest)
                case Right(body) =>
                  body.fromJson[Json] match
                    case Left(_) => ZIO.succeed(Response.badRequest)
                    case Right(json) =>
                      requests.modify(previous => (previous.size, previous :+ json)).map(respond)
          }))
          http   <- ZIO.service[Client]
          client <- ChatCompletionsClient.make(http)
          result <- check(
            client,
            LlmConfig("structured-model", endpoint = s"http://127.0.0.1:$port/completion"),
            requests
          )
        yield result
      .provide(Client.default, Server.defaultWithPort(0))
