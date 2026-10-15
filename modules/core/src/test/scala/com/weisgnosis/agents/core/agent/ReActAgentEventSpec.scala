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
import com.weisgnosis.agents.core.event.{AgentEvent, EventDeliveryBudget}
import com.weisgnosis.agents.core.model.Msg
import zio.*
import zio.test.*

/** Runtime completion, event correlation, draining, and scoped stream cancellation. */
object ReActAgentEventSpec extends ZIOSpecDefault:
  private def paired(events: Chunk[AgentEvent]): Boolean =
    val starts = events.collect { case event: AgentEvent.AgentStartEvent => event.replyId }
    val ends   = events.collect { case event: AgentEvent.AgentEndEvent => event.replyId }
    starts.size == 1 && starts == ends && events.headOption.exists {
      case _: AgentEvent.AgentStartEvent => true
      case _                             => false
    } && events.lastOption.exists {
      case _: AgentEvent.AgentEndEvent => true
      case _                           => false
    }

  def spec: Spec[TestEnvironment & Scope, Any] = suite("ReActAgent event lifecycle")(
    test("success emits one correlated boundary pair per run with fresh identity") {
      ZIO
        .foreach(Chunk("done", "")): text =>
          for
            model  <- LlmClient.mock(Chunk.fill(2)(TurnOutput.FinalText(Msg.assistant(text))))
            agent  <- ReActAgent.make(client = model)
            first  <- agent.streamWithResult(Msg.user("first")).runCollect
            second <- agent.streamWithResult(Msg.user("second")).runCollect
            checks = Chunk(first, second).map: updates =>
              val events  = updates.collect { case AgentRunUpdate.Event(event) => event }
              val results = updates.collect { case AgentRunUpdate.Result(result) => result }
              val phases = events.collect:
                case _: AgentEvent.AgentStartEvent  => "start"
                case _: AgentEvent.AgentResultEvent => "result"
                case _: AgentEvent.AgentEndEvent    => "end"
              assertTrue(
                paired(events),
                phases == Chunk("start", "result", "end"),
                results.map(_.state) == Chunk(AgentRunState.Completed),
                results.map(_.reply.text) == Chunk(text),
                events.collect { case event: AgentEvent.AgentResultEvent => event.result } ==
                  results.map(_.reply),
                updates.lastOption.exists:
                  case AgentRunUpdate.Result(_) => true
                  case _                        => false
              )
          yield checks.foldLeft(assertTrue(true))(_ && _) && assertTrue(
            first.collect { case AgentRunUpdate.Event(event: AgentEvent.AgentStartEvent) =>
              event.replyId
            } != second.collect { case AgentRunUpdate.Event(event: AgentEvent.AgentStartEvent) =>
              event.replyId
            }
          )
        .map(_.foldLeft(assertTrue(true))(_ && _))
    } @@ TestAspect.timeout(5.seconds),
    test("buffered model events drain in order before correlated completion") {
      val deltas = Chunk
        .fromIterable(0 until 512)
        .map(index => AgentEvent.TextBlockDeltaEvent(index.toString, 0))
      val model = LlmClient.mockEffectful((_, _, hub) =>
        hub.publishAll(deltas).as(TurnOutput.FinalText(Msg.assistant("done")))
      )
      for
        budget <- ZIO.fromEither(
          EventDeliveryBudget.fromLimits(
            deltas.size + 16,
            4L * 1024 * 1024,
            1024L * 1024
          )
        )
        agent  <- ReActAgent.make(AgentConfig(eventDeliveryBudget = budget), client = model)
        events <- agent.streamEvents(Msg.user("work")).runCollect
      yield assertTrue(
        paired(events),
        events.collect { case event: AgentEvent.TextBlockDeltaEvent => event } == deltas
      )
    },
    test("a provider-published end event cannot terminate runtime delivery") {
      val model = LlmClient.mockEffectful((_, _, hub) =>
        hub.publish(AgentEvent.AgentEndEvent("provider-marker")) *>
          hub
            .publish(AgentEvent.TextBlockDeltaEvent("after marker", 0))
            .as(TurnOutput.FinalText(Msg.assistant("done")))
      )
      for
        agent  <- ReActAgent.make(client = model)
        events <- agent.streamEvents(Msg.user("work")).runCollect
      yield assertTrue(
        events.exists {
          case AgentEvent.TextBlockDeltaEvent("after marker", _, _, _) => true; case _ => false
        },
        paired(events.filter {
          case AgentEvent.AgentEndEvent("provider-marker", _) => false; case _ => true
        })
      )
    },
    test("failure drains pending events and the correlated end before propagating the error") {
      val error = AgentError.ModelError(503, "unavailable")
      val model = LlmClient.mockEffectful((_, _, hub) =>
        hub.publish(AgentEvent.TextBlockDeltaEvent("before failure", 0)) *> ZIO.fail(error)
      )
      for
        observed <- Ref.make(Chunk.empty[AgentEvent])
        agent    <- ReActAgent.make(client = model)
        result <- agent
          .streamEvents(Msg.user("work"))
          .tap(event => observed.update(_ :+ event))
          .runDrain
          .either
        events <- observed.get
      yield assertTrue(
        paired(events),
        result == Left(error),
        events.exists(_.isInstanceOf[AgentEvent.TextBlockDeltaEvent])
      )
    },
    test("self-interruption completes the transport and releases model resources") {
      for
        released <- Ref.make(false)
        observed <- Ref.make(Chunk.empty[AgentEvent])
        model = LlmClient.mockEffectful((_, _, _) =>
          ZIO.acquireRelease(ZIO.unit)(_ => released.set(true)) *> ZIO.interrupt
        )
        agent <- ReActAgent.make(client = model)
        result <- agent
          .streamEvents(Msg.user("work"))
          .tap(event => observed.update(_ :+ event))
          .runDrain
          .exit
        events <- observed.get
        closed <- released.get
      yield assertTrue(paired(events), result.isInterrupted, closed)
    },
    test("cooperative interruption reconciles history before correlated completion") {
      for
        started  <- Promise.make[Nothing, Unit]
        released <- Ref.make(false)
        model = LlmClient.mockEffectful((_, _, _) =>
          ZIO.acquireRelease(ZIO.unit)(_ => released.set(true)) *> started.succeed(()) *> ZIO.never
        )
        agent    <- ReActAgent.make(client = model)
        consumer <- agent.streamEvents(Msg.user("work")).runCollect.forkScoped
        _        <- started.await
        _        <- agent.interrupt
        events   <- consumer.join
        history  <- agent.history
        closed   <- released.get
      yield assertTrue(
        paired(events),
        closed,
        history.count(_.generateReason.contains("interrupted")) == 1
      )
    },
    test("timeout closes resources and emits the end before failing the stream") {
      for
        started  <- Promise.make[Nothing, Unit]
        released <- Ref.make(false)
        observed <- Ref.make(Chunk.empty[AgentEvent])
        model = LlmClient.mockEffectful((_, _, _) =>
          ZIO.acquireRelease(ZIO.unit)(_ => released.set(true)) *> started.succeed(()) *> ZIO.never
        )
        agent <- ReActAgent.make(AgentConfig(timeout = 5.seconds), client = model)
        consumer <- agent
          .streamEvents(Msg.user("work"))
          .tap(event => observed.update(_ :+ event))
          .runDrain
          .either
          .forkScoped
        _      <- started.await
        _      <- TestClock.adjust(5.seconds)
        result <- consumer.join
        events <- observed.get
        closed <- released.get
      yield assertTrue(
        paired(events),
        closed,
        result.left.exists(_.isInstanceOf[AgentError.TimeoutError])
      )
    },
    test("early consumer completion interrupts the owned run before returning") {
      for
        released <- Ref.make(false)
        model = LlmClient.mockEffectful((_, _, hub) =>
          ZIO.acquireRelease(ZIO.unit)(_ => released.set(true)) *>
            hub.publish(AgentEvent.TextBlockDeltaEvent("ready", 0)) *> ZIO.never
        )
        agent <- ReActAgent.make(client = model)
        _ <- agent
          .streamEvents(Msg.user("work"))
          .takeUntil(_.isInstanceOf[AgentEvent.TextBlockDeltaEvent])
          .runDrain
        closed  <- released.get
        history <- agent.history
      yield assertTrue(closed, history.count(_.generateReason.contains("interrupted")) == 1)
    }
  )
