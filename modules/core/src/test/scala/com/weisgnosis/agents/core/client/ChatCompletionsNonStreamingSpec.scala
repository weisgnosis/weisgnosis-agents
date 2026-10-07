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

import com.weisgnosis.agents.core.agent.ReActAgent
import com.weisgnosis.agents.core.event.AgentEvent
import com.weisgnosis.agents.core.model.{Msg, Role}
import com.weisgnosis.agents.core.tool.{Tool, ToolDef, Toolkit}
import java.nio.charset.StandardCharsets
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.stream.ZStream
import zio.test.*
import HttpClientTestSupport.scripted

object ChatCompletionsNonStreamingSpec extends ZIOSpecDefault:
  private enum TurnCase:
    case Text, ToolCycle, TextAfterRetry, TextDetails, SummaryDetails, MixedDetails

  private val textDetail = Json.Obj(
    "type" -> Json.Str("reasoning.text"),
    "text" -> Json.Str("First part")
  )
  private val summaryDetail = Json.Obj(
    "type" -> Json.Str("reasoning.summary"),
    "text" -> Json.Str(" and second part")
  )

  private def details(scenario: TurnCase): Chunk[Json] = scenario match
    case TurnCase.TextDetails    => Chunk(textDetail)
    case TurnCase.SummaryDetails => Chunk(summaryDetail)
    case TurnCase.MixedDetails   => Chunk(textDetail, summaryDetail)
    case _                       => Chunk.empty

  private def thought(scenario: TurnCase): String = scenario match
    case TurnCase.TextDetails    => "First part"
    case TurnCase.SummaryDetails => " and second part"
    case TurnCase.MixedDetails   => "First part and second part"
    case _                       => ""

  private val config = LlmConfig("json-model", endpoint = "http://localhost/completion")
  private val answer = full("\"content\":\"answer\"", "stop")
  private val call = full(
    "\"content\":\"search\",\"reasoning\":\"reason\",\"tool_calls\":[{\"id\":\"a\",\"type\":\"function\",\"function\":{\"name\":\"lookup\",\"arguments\":\"{\\\"query\\\":1}\"}}]",
    "tool_calls"
  )

  def spec: Spec[TestEnvironment & Scope, Any] = suite("ChatCompletionsNonStreamingSpec")(
    test(
      "explicit JSON clients preserve text, tool cycles, events, transformations and request ownership"
    ):
      ZIO
        .foreach(Chunk.fromArray(TurnCase.values)): scenario =>
          for
            requests    <- Ref.make(Chunk.empty[(String, Option[String])])
            released    <- Ref.make(0)
            inputs      <- Ref.make(Chunk.empty[Json])
            transformed <- Ref.make(Chunk.empty[String])
            retry       <- ZIO.fromEither(CompletionRetryPolicy.make(2))
            client <- ChatCompletionsClient.makeNonStreaming(
              scripted(request =>
                for
                  raw  <- request.body.asString
                  sent <- requests.updateAndGet(_ :+ (raw -> request.headers.get("Accept")))
                  _    <- ZIO.addFinalizer(released.update(_ + 1))
                yield
                  if scenario == TurnCase.TextAfterRetry && sent.size == 1 then
                    Response(status = Status.TooManyRequests, body = Body.fromString("busy"))
                  else
                    response(
                      bytes(
                        if scenario == TurnCase.ToolCycle && sent.size == 1 then call
                        else if details(scenario).nonEmpty then
                          full(
                            s"\"content\":\"answer\",\"reasoning_details\":${Json.Arr(details(scenario)).toJson}",
                            "stop"
                          )
                        else answer
                      )
                    )
              ),
              textTransform = ModelTextTransform((_, fragment) =>
                transformed
                  .update(_ :+ fragment.delta)
                  .as(fragment.delta.toUpperCase(java.util.Locale.ROOT))
              ),
              reasoningReplay =
                if details(scenario).nonEmpty then ReasoningReplay.OpenRouterDetails
                else ReasoningReplay.Omit,
              retryPolicy = scenario match
                case TurnCase.TextAfterRetry => retry
                case _                       => CompletionRetryPolicy.disabled
            )
            agent <- ReActAgent.make(
              client = client,
              toolkit = Toolkit.single(
                Tool.make(ToolDef("lookup", "lookup", Json.Obj()))(input =>
                  inputs.update(_ :+ input).as("found")
                )
              ),
              llmConfig = config
            )
            events       <- agent.streamEvents(Msg.user("question")).runCollect
            history      <- agent.history
            sent         <- requests.get
            closed       <- released.get
            called       <- inputs.get
            replacements <- transformed.get
            expectedTurns = scenario match
              case TurnCase.ToolCycle => 2
              case _                  => 1
            expectedInputs = scenario match
              case TurnCase.ToolCycle => Chunk(Json.Obj("query" -> Json.Num(1)))
              case _                  => Chunk.empty[Json]
            expectedRoles = scenario match
              case TurnCase.ToolCycle => Chunk(Role.User, Role.Assistant, Role.Tool, Role.Assistant)
              case _                  => Chunk(Role.User, Role.Assistant)
            expectedRequests = scenario match
              case TurnCase.TextAfterRetry => 2
              case _                       => expectedTurns
          yield assertTrue(
            sent.size == expectedRequests,
            closed == expectedRequests,
            scenario != TurnCase.TextAfterRetry || sent.map(_._1).distinct.size == 1,
            sent.forall { case (body, accept) =>
              com.weisgnosis.agents.core.json.JsonBoundary.decode(body).exists {
                case obj: Json.Obj =>
                  obj.get("stream").contains(Json.Bool(false)) && obj.get("stream_options").isEmpty
                case _ => false
              } && accept.contains("application/json")
            },
            called == expectedInputs,
            history.map(_.role) == expectedRoles,
            history.lastOption.exists(_.text == "ANSWER"),
            events.collect { case result: AgentEvent.AgentResultEvent =>
              result.result.text
            } == Chunk("ANSWER"),
            events.count(_.isInstanceOf[AgentEvent.ModelResponseEndEvent]) == expectedTurns,
            events.collect { case event: AgentEvent.ToolCallEndEvent =>
              event.callId
            } == expectedInputs.map(_ => "a"),
            events.collect { case event: AgentEvent.ThinkingBlockDeltaEvent =>
              event.delta
            } == (if scenario == TurnCase.ToolCycle then Chunk("reason")
                  else if details(scenario).nonEmpty then Chunk(thought(scenario))
                  else Chunk.empty),
            details(scenario).isEmpty || history.lastOption.exists(message =>
              message.thinking == thought(scenario) && message.content.exists {
                case com.weisgnosis.agents.core.model.ContentBlock.Thinking(_, metadata) =>
                  metadata.get("reasoningDetails").contains(Json.Arr(details(scenario)))
                case _ => false
              }
            ),
            replacements == (scenario match
              case TurnCase.ToolCycle => Chunk("search", "answer")
              case _                  => Chunk("answer"))
          )
        .map(_.foldLeft(assertTrue(true))(_ && _))
    ,
    test("JSON validation waits for EOF, bounds input and joins failure and cancellation cleanup"):
      val contentStart = answer.indexOf("answer")
      val invalidUtf8 = bytes(answer.take(contentStart)) ++ ZStream.succeed(0xc3.toByte) ++ bytes(
        answer.drop(contentStart)
      )
      val invalid = Chunk(
        response(bytes(answer.dropRight(1))),
        response(bytes(answer + "{}")),
        response(
          bytes(
            answer
              .replace("\"content\":\"answer\"", "\"content\":\"answer\",\"content\":\"duplicate\"")
          )
        ),
        response(bytes(call.replace("{\\\"query\\\":1}", "{"))),
        response(bytes(answer.replace("\"stop\"", "\"length\""))),
        response(bytes("{\"object\":\"chat.completion\",\"choices\":[],\"usage\":{}}")),
        response(bytes("{\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}")),
        response(invalidUtf8),
        Response(
          headers = Headers("Content-Type", "text/event-stream"),
          body = Body.fromString(answer)
        )
      )
      for
        failures <- ZIO.foreach(invalid): reply =>
          for
            closed      <- Ref.make(false)
            transformed <- Ref.make(0)
            observed    <- Ref.make(Chunk.empty[AgentEvent])
            invoked     <- Ref.make(0)
            client <- ChatCompletionsClient.makeNonStreaming(
              scripted(_ => ZIO.addFinalizer(closed.set(true)).as(reply)),
              textTransform =
                ModelTextTransform((_, fragment) => transformed.update(_ + 1).as(fragment.delta))
            )
            agent <- ReActAgent.make(
              client = client,
              llmConfig = config,
              toolkit = Toolkit.single(
                Tool.make(ToolDef("lookup", "lookup", Json.Obj()))(_ =>
                  invoked.update(_ + 1).as("unexpected")
                )
              )
            )
            result <- agent
              .streamEvents(Msg.user("q"))
              .tap(event => observed.update(_ :+ event))
              .runDrain
              .either
            released <- closed.get
            changes  <- transformed.get
            events   <- observed.get
            calls    <- invoked.get
          yield assertTrue(
            result.isLeft,
            released,
            changes == 0,
            calls == 0,
            !events.exists(_.isInstanceOf[AgentEvent.ModelResponseChunkEvent]),
            !events.exists(_.isInstanceOf[AgentEvent.TextBlockDeltaEvent]),
            !events.exists(_.isInstanceOf[AgentEvent.ToolCallStartEvent])
          )
        bounded <- ChatCompletionsTransport
          .makeNonStreaming(
            scripted(_ => ZIO.succeed(response(bytes(answer)))),
            CompletionTransportLimits(responseBytes = 8)
          )
          .flatMap(_.complete(Chunk(Msg.user("q")), Chunk.empty, config))
          .either
        cancellation <- ZIO.scoped:
          for
            read    <- Promise.make[Nothing, Unit]
            closed  <- Promise.make[Nothing, Unit]
            changes <- Ref.make(0)
            client <- ChatCompletionsClient.makeNonStreaming(
              scripted(_ =>
                ZIO
                  .addFinalizer(closed.succeed(()).unit)
                  .as(
                    response(
                      bytes(call) ++ ZStream.fromZIO(read.succeed(())).drain ++ ZStream.never
                    )
                  )
              ),
              textTransform =
                ModelTextTransform((_, fragment) => changes.update(_ + 1).as(fragment.delta))
            )
            agent  <- ReActAgent.make(client = client, llmConfig = config)
            fiber  <- agent.run(Msg.user("q")).forkScoped
            _      <- read.await
            before <- changes.get
            exit   <- fiber.interrupt
            _      <- closed.await
            after  <- changes.get
          yield assertTrue(before == 0, after == 0, exit.isInterrupted)
      yield failures.foldLeft(cancellation)(_ && _) && assertTrue(
        bounded.left.exists(_.message.contains("byte limit"))
      )
  ) @@ TestAspect.timeout(20.seconds)

  private def full(fields: String, finish: String): String =
    s"""{"object":"chat.completion","choices":[{"index":0,"message":{"role":"assistant",$fields},"finish_reason":"$finish"}],"usage":{"prompt_tokens":2,"completion_tokens":3}}"""

  private def bytes(value: String): ZStream[Any, Nothing, Byte] =
    ZStream.fromChunk(Chunk.fromArray(value.getBytes(StandardCharsets.UTF_8))).rechunk(1)

  private def response(stream: ZStream[Any, Nothing, Byte]): Response =
    Response(
      headers = Headers("Content-Type", "application/json; charset=utf-8"),
      body = Body.fromStreamChunked(stream)
    )
