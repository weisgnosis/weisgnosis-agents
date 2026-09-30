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
import com.weisgnosis.agents.core.event.{AgentEvent, ModelStreamContext}
import com.weisgnosis.agents.core.model.Msg
import com.weisgnosis.agents.core.tool.{Tool, ToolDef, Toolkit}
import zio.*
import zio.http.*
import zio.stream.ZStream
import zio.json.ast.Json
import zio.test.*
import HttpClientTestSupport.scripted

object ChatCompletionsClientSpec extends ZIOSpecDefault:
  private val config = LlmConfig("stream-model", endpoint = "http://localhost/completion")
  private val text   = frame("""{"choices":[{"index":0,"delta":{"content":"answer"}}]}""")
  private val thinking = frame(
    """{"choices":[{"index":0,"delta":{"reasoning_content":"reason"}}]}"""
  )
  private val stop = frame("""{"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""")
  private val tool = frame(
    """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call-1","function":{"name":"lookup","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}"""
  )
  private val done = "data: [DONE]\n\n"

  def spec: Spec[TestEnvironment & Scope, Any] = suite("ChatCompletionsClient")(
    test(
      "publishes correlated deltas before response completion and commits only the finished reply"
    ):
      ZIO.scoped:
        for
          release  <- Promise.make[Nothing, Unit]
          received <- Promise.make[Nothing, Unit]
          observed <- Ref.make(Chunk.empty[AgentEvent])
          client <- ChatCompletionsClient.make(
            scripted(_ =>
              ZIO.succeed(
                response(
                  ZStream.succeed(thinking + text) ++ ZStream.fromZIO(release.await.as(stop + done))
                )
              )
            )
          )
          agent <- ReActAgent.make(client = client, llmConfig = config)
          fiber <- agent
            .streamEvents(Msg.user("question"))
            .tap(event =>
              observed.update(_ :+ event) *> (event match
                case _: AgentEvent.TextBlockDeltaEvent => received.succeed(()).unit
                case _                                 => ZIO.unit)
            )
            .runCollect
            .forkScoped
          _              <- received.await
          prefix         <- observed.get
          partialHistory <- agent.history
          _              <- release.succeed(())
          events         <- fiber.join
          history        <- agent.history
          replyIds = events.collect { case event: AgentEvent.AgentStartEvent => event.replyId }
          starts   = events.collect { case event: AgentEvent.ModelRequestStartEvent => event }
          ends     = events.collect { case event: AgentEvent.ModelResponseEndEvent => event }
        yield assertTrue(
          prefix.exists(_.isInstanceOf[AgentEvent.TextBlockDeltaEvent]),
          !prefix.exists(_.isInstanceOf[AgentEvent.ModelResponseEndEvent]),
          !partialHistory.exists(_.text == "answer"),
          history.lastOption.exists(msg => msg.text == "answer" && msg.thinking == "reason"),
          starts.size == 1,
          ends.size == 1,
          starts.flatMap(_.context) == ends.flatMap(_.context),
          contexts(events).forall(context => replyIds.contains(context.replyId)),
          contexts(events).map(_.requestId).distinct.size == 1,
          events.indexWhere(_.isInstanceOf[AgentEvent.TextBlockEndEvent]) < events.indexWhere(
            _.isInstanceOf[AgentEvent.ModelResponseEndEvent]
          ),
          events.indexWhere(_.isInstanceOf[AgentEvent.ModelResponseEndEvent]) < events.indexWhere(
            _.isInstanceOf[AgentEvent.AgentResultEvent]
          ),
          events.lastOption.exists(_.isInstanceOf[AgentEvent.AgentEndEvent])
        )
    ,
    test("uses distinct request identities across a real HTTP tool cycle")(
      ZIO
        .scoped:
          for
            requests <- Ref.make(Chunk.empty[String])
            executed <- Ref.make(0)
            lookup = Tool.make(ToolDef("lookup", "Look up a value", Json.Obj()))(_ =>
              executed.update(_ + 1).as("observation")
            )
            port <- Server.install(Routes(Method.POST / "completion" -> handler {
              (request: Request) =>
                request.body.asString
                  .orElseSucceed("")
                  .flatMap(body => requests.modify(previous => (previous.size, previous :+ body)))
                  .map(index =>
                    response(
                      ZStream.succeed(if index == 0 then tool + done else text + stop + done)
                    )
                  )
            }))
            http   <- ZIO.service[Client]
            client <- ChatCompletionsClient.make(http)
            agent <- ReActAgent.make(
              client = client,
              toolkit = Toolkit(Map("lookup" -> lookup)),
              llmConfig = config.copy(endpoint = s"http://127.0.0.1:$port/completion")
            )
            events <- agent.streamEvents(Msg.user("lookup")).runCollect
            bodies <- requests.get
            count  <- executed.get
            starts = events.collect { case event: AgentEvent.ModelRequestStartEvent =>
              event.context
            }
            ends = events.collect { case event: AgentEvent.ModelResponseEndEvent => event.context }
          yield assertTrue(
            count == 1,
            bodies.size == 2,
            bodies.drop(1).headOption.exists(_.contains("observation")),
            starts.size == 2,
            starts == ends,
            starts.flatten.map(_.requestId).distinct.size == 2,
            starts.flatten.map(_.replyId).distinct.size == 1,
            events.exists(_.isInstanceOf[AgentEvent.ToolCallEndEvent]),
            events.collect { case event: AgentEvent.AgentResultEvent =>
              event.result.text
            } == Chunk("answer")
          )
        .provide(Client.default, Server.defaultWithPort(0))
    ) @@ TestAspect.withLiveClock,
    test("buffers argument fragments until call identity exists"):
      val pending = frame(
        """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":2,"function":{"arguments":"{"}}]}}]}"""
      )
      val identified = frame(
        """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":2,"id":"late","function":{"name":"lookup","arguments":"}"}}]},"finish_reason":"tool_calls"}]}"""
      )
      ZIO.scoped:
        for
          hub   <- Hub.unbounded[AgentEvent]
          queue <- hub.subscribe
          client <- ChatCompletionsClient.make(
            scripted(_ => ZIO.succeed(response(ZStream.succeed(pending + identified + done))))
          )
          output <- client.streamTurn(
            Chunk(Msg.user("q")),
            Chunk.empty,
            config,
            hub,
            ModelStreamContext("reply", "request")
          )
          events <- queue.takeAll
          starts = events.collect { case event: AgentEvent.ToolCallStartEvent => event }
          deltas = events.collect { case event: AgentEvent.ToolCallDeltaEvent => event }
          ends   = events.collect { case event: AgentEvent.ToolCallEndEvent => event }
        yield assertTrue(
          output.message.toolUses.map(_.id) == Chunk("late"),
          starts.map(_.callId) == Chunk("late"),
          deltas.map(_.delta) == Chunk("{}"),
          ends.map(_.callId) == Chunk("late"),
          starts.forall(_.streamIndex.contains(2)),
          deltas.forall(_.streamIndex.contains(2)),
          events.indexWhere(_.isInstanceOf[AgentEvent.ToolCallStartEvent]) < events.indexWhere(
            _.isInstanceOf[AgentEvent.ToolCallDeltaEvent]
          )
        )
    ,
    test("incomplete tool streams cannot close calls or dispatch tools"):
      for
        called   <- Ref.make(false)
        observed <- Ref.make(Chunk.empty[AgentEvent])
        lookup = Tool.make(ToolDef("lookup", "lookup", Json.Obj()))(_ =>
          called.set(true).as("unexpected")
        )
        client <- ChatCompletionsClient.make(
          scripted(_ => ZIO.succeed(response(ZStream.succeed(tool))))
        )
        agent <- ReActAgent.make(
          client = client,
          toolkit = Toolkit(Map("lookup" -> lookup)),
          llmConfig = config
        )
        result <- agent
          .streamEvents(Msg.user("q"))
          .tap(event => observed.update(_ :+ event))
          .runDrain
          .either
        events   <- observed.get
        executed <- called.get
      yield assertTrue(
        result.isLeft,
        !executed,
        events.exists(_.isInstanceOf[AgentEvent.ToolCallStartEvent]),
        !events.exists(_.isInstanceOf[AgentEvent.ToolCallEndEvent]),
        !events.exists(_.isInstanceOf[AgentEvent.ModelResponseEndEvent]),
        !events.exists(_.isInstanceOf[AgentEvent.AgentResultEvent]),
        events.lastOption.exists(_.isInstanceOf[AgentEvent.AgentEndEvent])
      )
    ,
    test("rejects a malformed frame before publishing its apparently valid text"):
      val malformed = frame(
        """{"choices":[{"index":0,"delta":{"content":"untrusted","tool_calls":[{"function":{"name":"lookup"}}]}}]}"""
      )
      for
        observed <- Ref.make(Chunk.empty[AgentEvent])
        client <- ChatCompletionsClient.make(
          scripted(_ => ZIO.succeed(response(ZStream.succeed(text + malformed + done))))
        )
        agent <- ReActAgent.make(client = client, llmConfig = config)
        result <- agent
          .streamEvents(Msg.user("q"))
          .tap(event => observed.update(_ :+ event))
          .runDrain
          .either
        events <- observed.get
      yield assertTrue(
        result.isLeft,
        events.collect { case event: AgentEvent.TextBlockDeltaEvent => event.delta } == Chunk(
          "answer"
        ),
        events.count(_.isInstanceOf[AgentEvent.ModelResponseChunkEvent]) == 1,
        !events.exists(_.isInstanceOf[AgentEvent.TextBlockEndEvent])
      )
    ,
    test("cooperative interruption closes HTTP resources without completed model events"):
      ZIO.scoped:
        for
          waiting  <- Promise.make[Nothing, Unit]
          closed   <- Ref.make(false)
          observed <- Ref.make(Chunk.empty[AgentEvent])
          client <- ChatCompletionsClient.make(
            scripted(_ =>
              ZIO
                .addFinalizer(closed.set(true))
                .as(
                  response(
                    ZStream.succeed(text) ++ ZStream.fromZIO(waiting.succeed(()) *> ZIO.never)
                  )
                )
            )
          )
          agent <- ReActAgent.make(client = client, llmConfig = config)
          fiber <- agent
            .streamEvents(Msg.user("q"))
            .tap(event => observed.update(_ :+ event))
            .runCollect
            .forkScoped
          _        <- waiting.await
          _        <- agent.interrupt
          events   <- fiber.join
          released <- closed.get
        yield assertTrue(
          released,
          !events.exists(_.isInstanceOf[AgentEvent.ModelResponseEndEvent]),
          !events.exists(_.isInstanceOf[AgentEvent.TextBlockEndEvent]),
          events
            .collect { case event: AgentEvent.AgentResultEvent => event.result.text }
            .exists(_.contains("interrupted")),
          events.lastOption.exists(_.isInstanceOf[AgentEvent.AgentEndEvent])
        )
  ) @@ TestAspect.timeout(20.seconds)

  private def frame(json: String): String = s"data: $json\n\n"

  private def response(stream: ZStream[Any, Nothing, String]): Response =
    Response(
      headers = Headers("Content-Type", "text/event-stream"),
      body = Body.fromCharSequenceStreamChunked(stream)
    )

  private def contexts(events: Chunk[AgentEvent]): Chunk[ModelStreamContext] =
    events.flatMap:
      case event: AgentEvent.ModelRequestStartEvent  => Chunk.fromIterable(event.context)
      case event: AgentEvent.ModelResponseChunkEvent => Chunk.fromIterable(event.context)
      case event: AgentEvent.ModelResponseEndEvent   => Chunk.fromIterable(event.context)
      case event: AgentEvent.TextBlockStartEvent     => Chunk.fromIterable(event.context)
      case event: AgentEvent.TextBlockDeltaEvent     => Chunk.fromIterable(event.context)
      case event: AgentEvent.TextBlockEndEvent       => Chunk.fromIterable(event.context)
      case event: AgentEvent.ThinkingBlockStartEvent => Chunk.fromIterable(event.context)
      case event: AgentEvent.ThinkingBlockDeltaEvent => Chunk.fromIterable(event.context)
      case event: AgentEvent.ThinkingBlockEndEvent   => Chunk.fromIterable(event.context)
      case event: AgentEvent.ToolCallStartEvent      => Chunk.fromIterable(event.context)
      case event: AgentEvent.ToolCallDeltaEvent      => Chunk.fromIterable(event.context)
      case event: AgentEvent.ToolCallEndEvent        => Chunk.fromIterable(event.context)
      case _                                         => Chunk.empty
