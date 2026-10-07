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
package com.weisgnosis.agents.core.event

import com.weisgnosis.agents.core.agent.ReActAgent
import com.weisgnosis.agents.core.client.{ChatCompletionsClient, LlmConfig}
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.model.Msg
import com.weisgnosis.agents.core.tool.{Tool, ToolDef, Toolkit}
import zio.*
import zio.http.*
import zio.json.ast.Json
import zio.stream.{ZChannel, ZStream}
import zio.test.*

object FinalAnswerFilterSpec extends ZIOSpecDefault:
  private val first  = ModelStreamContext("reply", "first")
  private val second = ModelStreamContext("reply", "second")

  private def start(context: ModelStreamContext): AgentEvent =
    AgentEvent.ModelRequestStartEvent("model", 0, context = Some(context))

  private def end(context: ModelStreamContext): AgentEvent =
    AgentEvent.ModelResponseEndEvent("model", 0, 0, context = Some(context))

  private def text(value: String, context: ModelStreamContext): Chunk[AgentEvent] =
    Chunk(
      AgentEvent.TextBlockStartEvent(0, context = Some(context)),
      AgentEvent.TextBlockDeltaEvent(value, 0, context = Some(context)),
      AgentEvent.TextBlockEndEvent(0, value, context = Some(context))
    )

  def spec: Spec[TestEnvironment & Scope, Any] = suite("FinalAnswerFilter")(
    test("isolates correlated rounds and subscriptions with bounded immutable buffers"):
      val call     = AgentEvent.ToolCallStartEvent("call", "lookup", 0, context = Some(first))
      val raw      = AgentEvent.ModelResponseChunkEvent("intermediate raw", context = Some(first))
      val thinking = AgentEvent.ThinkingBlockDeltaEvent("reason", 0, context = Some(first))
      val result   = AgentEvent.AgentResultEvent(Msg.assistant("unchanged result"))
      val uncorrelated = AgentEvent.TextBlockDeltaEvent("unmatched", 0)
      val peer         = text("peer", first).map(_.withSource("child"))
      val peerStart    = start(first).withSource("child")
      val peerEnd      = end(first).withSource("child")
      val otherRequest = text("other request", second)
      val input = Chunk(start(first), peerStart, start(second)) ++ text("hidden", first) ++ peer ++
        otherRequest ++ Chunk(raw, call, thinking) ++ text("still hidden", first) ++
        Chunk(uncorrelated, end(first), end(second), peerEnd, result)
      val bounds = Chunk(
        (Chunk(start(first)) ++ text("ab", first) :+ end(first), true),
        (Chunk(start(first)) ++ text("abc", first), false),
        (
          Chunk(start(first)) ++ text("", first) :+ AgentEvent
            .TextBlockDeltaEvent("", 0, context = Some(first)),
          false
        ),
        (
          Chunk(start(first), start(second)) ++ text("", first).take(2) ++ text("", second).take(2),
          false
        ),
        (
          Chunk(
            start(first),
            start(second),
            AgentEvent.TextBlockDeltaEvent("abc", 0, context = Some(first)),
            AgentEvent.TextBlockDeltaEvent("abc", 0, context = Some(second))
          ),
          false
        ),
        (Chunk(start(first), start(second), start(ModelStreamContext("other", "third"))), false),
        (
          Chunk(start(first)) ++ text("ab", first) ++ Chunk(start(first)) ++ text(
            "ab",
            first
          ) :+ end(first),
          true
        ),
        (
          Chunk(start(first)) ++ text("ab", first) ++ Chunk(call, start(second)) ++ text(
            "ab",
            second
          ) :+ end(second),
          true
        )
      )
      for
        pipeline <- ZIO.fromEither(FinalAnswerFilter.make())
        observed <- ZStream.fromChunk(input).via(pipeline).runCollect
        fresh <- ZStream
          .fromChunk(Chunk(start(first)) ++ text("fresh", first) :+ end(first))
          .via(pipeline)
          .runCollect
        cleared <- ZStream
          .fromChunk(
            Chunk(start(first), peerStart) ++ text("abandoned", first) ++ peer ++
              Chunk(AgentEvent.AgentEndEvent("reply"), end(first), peerEnd)
          )
          .via(pipeline)
          .runCollect
        unfinished <- ZStream
          .fromChunk(Chunk(start(first)) ++ text("unfinished", first))
          .via(pipeline)
          .runCollect
        detached <- ZStream.fromChunk(text("unstarted", first)).via(pipeline).runCollect
        checked <- ZIO.foreach(bounds): (events, accepted) =>
          for
            bounded <- ZIO.fromEither(FinalAnswerFilter.make(3, 4, 2))
            outcome <- ZStream.fromChunk(events).via(bounded).runCollect.either
          yield assertTrue(outcome.isRight == accepted)
      yield assertTrue(
        observed == Chunk(
          start(first),
          peerStart,
          start(second),
          raw,
          call,
          thinking,
          uncorrelated,
          end(first)
        ) ++
          otherRequest ++ Chunk(end(second)) ++ peer ++ Chunk(peerEnd, result),
        fresh == Chunk(start(first)) ++ text("fresh", first) :+ end(first),
        cleared == Chunk(
          start(first),
          peerStart,
          AgentEvent.AgentEndEvent("reply"),
          end(first)
        ) ++ peer :+ peerEnd,
        unfinished == Chunk(start(first)),
        detached == text("unstarted", first),
        FinalAnswerFilter.make(0, 4, 2).isLeft,
        FinalAnswerFilter.make(3, 0, 2).isLeft,
        FinalAnswerFilter.make(3, 4, 0).isLeft
      ) && checked.foldLeft(assertTrue(true))(_ && _)
    ,
    test(
      "filters an actual HTTP tool cycle without changing history, diagnostics or final results"
    )(
      ZIO
        .scoped:
          for
            requests <- Ref.make(Chunk.empty[String])
            executed <- Ref.make(0)
            port <- Server.install(Routes(Method.POST / "completion" -> handler {
              (request: Request) =>
                request.body.asString
                  .orElseSucceed("")
                  .flatMap(body => requests.modify(previous => (previous.size, previous :+ body)))
                  .map(index =>
                    val delta =
                      if index == 0 then
                        """{"choices":[{"index":0,"delta":{"content":"intermediate","tool_calls":[{"index":0,"id":"call","function":{"name":"lookup","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}"""
                      else
                        """{"choices":[{"index":0,"delta":{"content":"final answer"},"finish_reason":"stop"}]}"""
                    Response(
                      headers = Headers("Content-Type", "text/event-stream"),
                      body = Body.fromCharSequenceStreamChunked(
                        ZStream.succeed(s"data: $delta\n\ndata: [DONE]\n\n")
                      )
                    )
                  )
            }))
            http   <- ZIO.service[Client]
            client <- ChatCompletionsClient.make(http)
            lookup = Tool.make(ToolDef("lookup", "Read a value", Json.Obj()))(_ =>
              executed.update(_ + 1).as("observed")
            )
            agent <- ReActAgent.make(
              client = client,
              llmConfig = LlmConfig("model", endpoint = s"http://127.0.0.1:$port/completion"),
              toolkit = Toolkit.single(lookup)
            )
            pipeline <- ZIO.fromEither(FinalAnswerFilter.make())
            events   <- agent.streamEvents(Msg.user("question")).via(pipeline).runCollect
            history  <- agent.history
            bodies   <- requests.get
            count    <- executed.get
            textEvents = events.collect { case event: AgentEvent.TextBlockDeltaEvent =>
              event.delta
            }
          yield assertTrue(
            textEvents == Chunk("final answer"),
            events.count(_.isInstanceOf[AgentEvent.TextBlockStartEvent]) == 1,
            events.count(_.isInstanceOf[AgentEvent.TextBlockEndEvent]) == 1,
            events.count(_.isInstanceOf[AgentEvent.ModelResponseEndEvent]) == 2,
            events.indexWhere(_.isInstanceOf[AgentEvent.TextBlockEndEvent]) < events.lastIndexWhere(
              _.isInstanceOf[AgentEvent.ModelResponseEndEvent]
            ),
            events.exists {
              case event: AgentEvent.ModelResponseChunkEvent =>
                event.chunk.contains("intermediate")
              case _ => false
            },
            events.collect { case event: AgentEvent.AgentResultEvent =>
              event.result.text
            } == Chunk("final answer"),
            events.lastOption.exists(_.isInstanceOf[AgentEvent.AgentEndEvent]),
            history.exists(message => message.text == "intermediate" && message.toolUses.nonEmpty),
            bodies.size == 2,
            bodies.drop(1).exists(_.contains("intermediate")),
            count == 1
          )
        .provide(Client.default, Server.defaultWithPort(0))
    ) @@ TestAspect.withLiveClock,
    test(
      "preserves upstream failures and joins cancellation cleanup without flushing pending text"
    ):
      val error                    = AgentError.ModelError(503, "failed")
      val defect                   = new IllegalStateException("cleanup")
      val cause: Cause[AgentError] = Cause.fail(error) ++ Cause.die(defect)
      val failing = ZStream.fromChannel(
        ZChannel.write(Chunk(start(first)) ++ text("pending", first)) *> ZChannel.refailCause(cause)
      )
      ZIO.scoped:
        for
          pipeline <- ZIO.fromEither(FinalAnswerFilter.make())
          baseline <- failing.runCollect.exit
          failed   <- failing.via(pipeline).runCollect.exit
          entered  <- Promise.make[Nothing, Unit]
          closing  <- Promise.make[Nothing, Unit]
          release  <- Promise.make[Nothing, Unit]
          observed <- Ref.make(Chunk.empty[AgentEvent])
          assertion <- (for
            fiber <- ZStream
              .acquireReleaseWith(ZIO.unit)(_ =>
                closing.succeed(()) *> release.await *> ZIO.die(defect)
              )
              .flatMap(_ =>
                ZStream.fromChunk(Chunk(start(first)) ++ text("pending", first)) ++
                  ZStream.fromZIO(entered.succeed(()) *> ZIO.never)
              )
              .via(pipeline)
              .tap(event => observed.update(_ :+ event))
              .runDrain
              .forkScoped
            _      <- entered.await
            id     <- ZIO.fiberId
            _      <- fiber.interruptAsFork(id)
            _      <- closing.await
            held   <- fiber.poll
            _      <- release.succeed(())
            exit   <- fiber.await
            events <- observed.get
          yield assertTrue(
            baseline.causeOption.contains(cause),
            failed.causeOption.contains(cause),
            held.isEmpty,
            exit.isInterrupted,
            exit.causeOption.exists(_.defects == List(defect)),
            events == Chunk(start(first))
          )).ensuring(release.succeed(()).unit)
        yield assertion
  ) @@ TestAspect.timeout(20.seconds)
