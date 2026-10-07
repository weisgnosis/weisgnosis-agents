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
package com.weisgnosis.agents.core.client

import com.weisgnosis.agents.core.agent.{ReActAgent, StructuredMode, StructuredOutput}
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.event.{AgentEvent, ModelStreamContext}
import com.weisgnosis.agents.core.model.Msg
import com.weisgnosis.agents.core.tool.{Tool, ToolDef, Toolkit}
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.stream.ZStream
import zio.test.*
import HttpClientTestSupport.scripted

object ChatCompletionsTransformSpec extends ZIOSpecDefault:
  final private case class Weather(city: String, temperature: String) derives JsonCodec
  private val config: LlmConfig =
    LlmConfig("transform-model", endpoint = "http://localhost/completion")
  private val stop: String = frame(
    """{"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}"""
  )
  private val done: String = "data: [DONE]\n\n"
  private val tool: String = frame(
    """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call","function":{"name":"lookup","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}"""
  )
  private val usage: String = frame(
    """{"choices":[],"usage":{"prompt_tokens":10,"completion_tokens":20}}"""
  )
  private val schema: Json = Json.Obj("type" -> Json.Str("object"))

  private def frame(json: String): String = s"data: $json\n\n"
  private def text(value: String): String = frame(
    Json
      .Obj(
        "choices" -> Json.Arr(
          Json.Obj("index" -> Json.Num(0), "delta" -> Json.Obj("content" -> Json.Str(value)))
        )
      )
      .toJson
  )
  private def response(body: String): Response = Response(
    headers = Headers("Content-Type", "text/event-stream"),
    body = Body.fromCharSequenceStreamChunked(ZStream.succeed(body))
  )
  private def record(agent: ReActAgent): ZIO[Scope, AgentError, Chunk[AgentEvent]] =
    agent.streamEvents(Msg.user("question")).runCollect

  def spec: Spec[TestEnvironment & Scope, Any] = suite("ChatCompletionsTransformSpec")(
    test("modelCallTextDeltaReplacement_updatesNativeStructuredOutput over real HTTP") {
      val payload = """{"city":"Shanghai","temperature":"32°C"}"""
      val fenced  = text("```json\n") + text(payload) + text("\n```") + stop + usage + done
      ZIO
        .scoped {
          for
            contexts <- Ref.make(Chunk.empty[ModelStreamContext])
            transform = ModelTextTransform((context, fragment) =>
              contexts
                .update(_ :+ context)
                .as(
                  if fragment.delta.startsWith("```json") || fragment.delta.endsWith("```") then ""
                  else fragment.delta
                )
            )
            port <- Server.install(Routes(Method.POST / "completion" -> handler(response(fenced))))
            http <- ZIO.service[Client]
            client <- ChatCompletionsClient.make(http, textTransform = transform)
            agent <- ReActAgent.make(
              client = client,
              llmConfig = config.copy(
                endpoint = s"http://127.0.0.1:$port/completion",
                structuredOutputCapability = StructuredOutputCapability.Native()
              )
            )
            events <- agent
              .streamStructuredEvents[Weather](Chunk(Msg.user("weather")), schema)
              .runCollect
            history <- agent.history
            seen    <- contexts.get
            results = events.collect { case event: AgentEvent.AgentResultEvent => event.result }
            modelContexts = events.collect { case event: AgentEvent.ModelRequestStartEvent =>
              event.context
            }
          yield assertTrue(
            results.size == 1,
            events
              .collect { case event: AgentEvent.ModelResponseChunkEvent => event.chunk }
              .exists(_.contains("```json")),
            results.forall(_.getStructuredData[Weather] == Right(Weather("Shanghai", "32°C"))),
            results.forall(_.text == payload),
            results.forall(_.metadata.contains(StructuredOutput.MetadataStructuredOutput)),
            results.forall(
              _.usage.exists(value => value.inputTokens == 10 && value.outputTokens == 20)
            ),
            history.lastOption == results.headOption,
            events.collect { case event: AgentEvent.TextBlockDeltaEvent => event.delta } == Chunk(
              "",
              payload,
              ""
            ),
            events.collect { case event: AgentEvent.TextBlockEndEvent => event.fullText } == Chunk(
              payload
            ),
            seen.size == 3,
            seen.distinct.map(Some(_)) == modelContexts
          )
        }
        .provide(Client.default, Server.defaultWithPort(0))
    } @@ TestAspect.withLiveClock,
    test(
      "text replacement leaves thinking tool arguments and usage unchanged and closes before model end"
    ) {
      for
        requests      <- Ref.make(0)
        closed        <- Ref.make(false)
        releasedAtEnd <- Ref.make(Chunk.empty[Boolean])
        inputs        <- Ref.make(Chunk.empty[Json])
        lookup = Tool.make(ToolDef("lookup", "Read a value", Json.Obj()))(input =>
          inputs.update(_ :+ input).as("observed")
        )
        transform = ModelTextTransform((_, fragment) =>
          ZIO.addFinalizer(closed.set(true)).as(fragment.delta.replace("answer", "ANSWER"))
        )
        client <- ChatCompletionsClient.make(
          scripted(_ =>
            requests
              .getAndUpdate(_ + 1)
              .map(index =>
                response(
                  if index == 0 then tool + done
                  else
                    frame(
                      """{"choices":[{"index":0,"delta":{"reasoning_content":"reason","content":"answer"}}]}"""
                    ) + stop + usage + done
                )
              )
          ),
          textTransform = transform
        )
        agent <- ReActAgent.make(
          client = client,
          toolkit = Toolkit.single(lookup),
          llmConfig = config
        )
        events <- agent
          .streamEvents(Msg.user("question"))
          .tap {
            case _: AgentEvent.ModelResponseEndEvent =>
              closed.get.flatMap(value => releasedAtEnd.update(_ :+ value))
            case _ => ZIO.unit
          }
          .runCollect
        observed <- inputs.get
        released <- releasedAtEnd.get
        history  <- agent.history
      yield assertTrue(
        observed == Chunk(Json.Obj()),
        released.size == 2,
        released.lastOption.contains(true),
        history.lastOption.exists(message =>
          message.text == "ANSWER" && message.thinking == "reason" && message.usage.exists(
            _.totalTokens == Right(30)
          )
        ),
        events.collect { case event: AgentEvent.ThinkingBlockDeltaEvent => event.delta } == Chunk(
          "reason"
        )
      )
    },
    test("malformed complete frames fail before any text transform or frame publication") {
      val malformed = frame(
        """{"choices":[{"index":0,"delta":{"content":"untrusted","tool_calls":[{"function":{"name":"lookup"}}]}}]}"""
      )
      for
        calls    <- Ref.make(0)
        observed <- Ref.make(Chunk.empty[AgentEvent])
        transform = ModelTextTransform((_, _) => calls.update(_ + 1).as("fixed"))
        client <- ChatCompletionsClient.make(
          scripted(_ => ZIO.succeed(response(malformed + done))),
          textTransform = transform
        )
        agent <- ReActAgent.make(client = client, llmConfig = config)
        result <- agent
          .streamEvents(Msg.user("q"))
          .tap(event => observed.update(_ :+ event))
          .runDrain
          .either
        count  <- calls.get
        events <- observed.get
      yield assertTrue(
        result.isLeft,
        count == 0,
        !events.exists(_.isInstanceOf[AgentEvent.ModelResponseChunkEvent]),
        !events.exists(_.isInstanceOf[AgentEvent.TextBlockDeltaEvent])
      )
    },
    test("post-finish text is rejected before the transform can erase it") {
      for
        calls <- Ref.make(0)
        transform = ModelTextTransform((_, _) => calls.update(_ + 1).as(""))
        client <- ChatCompletionsClient.make(
          scripted(_ => ZIO.succeed(response(text("first") + stop + text("late") + done))),
          textTransform = transform
        )
        agent  <- ReActAgent.make(client = client, llmConfig = config)
        result <- record(agent).either
        count  <- calls.get
      yield assertTrue(result.isLeft, count == 1)
    },
    test(
      "transform failures preserve the typed error close resources and publish no false completion"
    ) {
      val error = AgentError.TextTransformationError("rejected transformed text")
      for
        closed   <- Ref.make(false)
        observed <- Ref.make(Chunk.empty[AgentEvent])
        transform = ModelTextTransform((_, _) =>
          ZIO.addFinalizer(closed.set(true)) *> ZIO.fail(error)
        )
        client <- ChatCompletionsClient.make(
          scripted(_ => ZIO.succeed(response(text("value") + stop + done))),
          textTransform = transform
        )
        agent <- ReActAgent.make(client = client, llmConfig = config)
        result <- agent
          .streamEvents(Msg.user("q"))
          .tap(event => observed.update(_ :+ event))
          .runDrain
          .either
        events   <- observed.get
        released <- closed.get
      yield assertTrue(
        result == Left(error),
        released,
        !events.exists(_.isInstanceOf[AgentEvent.ModelResponseChunkEvent]),
        !events.exists(_.isInstanceOf[AgentEvent.ModelResponseEndEvent]),
        !events.exists(_.isInstanceOf[AgentEvent.AgentResultEvent]),
        events.lastOption.exists(_.isInstanceOf[AgentEvent.AgentEndEvent])
      )
    },
    test(
      "cooperative cancellation interrupts a waiting transform and closes its request resources"
    ) {
      for
        entered <- Promise.make[Nothing, Unit]
        closed  <- Ref.make(false)
        transform = ModelTextTransform((_, _) =>
          ZIO.addFinalizer(closed.set(true)) *> entered.succeed(()) *> ZIO.never
        )
        client <- ChatCompletionsClient.make(
          scripted(_ => ZIO.succeed(response(text("value") + stop + done))),
          textTransform = transform
        )
        agent    <- ReActAgent.make(client = client, llmConfig = config)
        fiber    <- record(agent).forkScoped
        _        <- entered.await
        _        <- agent.interrupt
        events   <- fiber.join
        released <- closed.get
      yield assertTrue(
        released,
        !events.exists(_.isInstanceOf[AgentEvent.TextBlockEndEvent]),
        !events.exists(_.isInstanceOf[AgentEvent.ModelResponseEndEvent]),
        events
          .collect { case event: AgentEvent.AgentResultEvent => event.result }
          .exists(_.generateReason.contains("interrupted"))
      )
    },
    test("transformed native JSON still requires strict structured validation") {
      for
        observed <- Ref.make(Chunk.empty[AgentEvent])
        transform = ModelTextTransform((_, fragment) => ZIO.succeed(fragment.delta + " trailing"))
        client <- ChatCompletionsClient.make(
          scripted(_ => ZIO.succeed(response(text("7") + stop + done))),
          textTransform = transform
        )
        agent <- ReActAgent.make(client = client, llmConfig = config)
        result <- agent
          .streamStructuredEvents[Int](
            Chunk(Msg.user("q")),
            Json.Obj("type" -> Json.Str("integer")),
            StructuredMode.Native
          )
          .tap(event => observed.update(_ :+ event))
          .runDrain
          .either
        events <- observed.get
      yield assertTrue(
        result.isLeft,
        events.collect { case event: AgentEvent.TextBlockDeltaEvent => event.delta } == Chunk(
          "7 trailing"
        ),
        !events.exists(_.isInstanceOf[AgentEvent.AgentResultEvent])
      )
    },
    test("automatic structured output never retries a transform-origin provider-like error") {
      val externalError = AgentError.ModelError(400, "host transform rejected text")
      val error         = AgentError.TextTransformationError(externalError.message)
      val synthetic = frame(
        """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"capture","function":{"name":"generate_response","arguments":"{\"response\":7}"}}]},"finish_reason":"tool_calls"}]}"""
      ) + done
      for
        calls <- Ref.make(0)
        transform = ModelTextTransform((_, _) =>
          ZIO
            .fail(externalError)
            .mapError(cause => AgentError.TextTransformationError(cause.message))
        )
        client <- ChatCompletionsClient.make(
          scripted(_ =>
            calls
              .getAndUpdate(_ + 1)
              .map(index => response(if index == 0 then text("7") + stop + done else synthetic))
          ),
          textTransform = transform
        )
        agent <- ReActAgent.make(client = client, llmConfig = config)
        result <- agent
          .runStructured[Int](Chunk(Msg.user("number")), Json.Obj("type" -> Json.Str("integer")))
          .either
        count <- calls.get
      yield assertTrue(result == Left(error), count == 1)
    },
    test("text transformation cannot replace missing protocol completion") {
      for
        client <- ChatCompletionsClient.make(
          scripted(_ => ZIO.succeed(response(text("raw")))),
          textTransform = ModelTextTransform((_, _) => ZIO.succeed("complete-looking answer"))
        )
        agent   <- ReActAgent.make(client = client, llmConfig = config)
        result  <- record(agent).either
        history <- agent.history
      yield assertTrue(result.isLeft, history.forall(_.text != "complete-looking answer"))
    }
  ) @@ TestAspect.timeout(20.seconds)
