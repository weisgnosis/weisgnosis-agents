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
import com.weisgnosis.agents.core.model.{ContentBlock, Msg, Role, ToolResultState}
import com.weisgnosis.agents.core.tool.{Tool, ToolDef, Toolkit}
import zio.*
import zio.json.ast.Json
import zio.test.*

object ReActAgentToolEventSpec extends ZIOSpecDefault:
  private val call = ContentBlock.ToolUse("call", "lookup", Json.Obj())

  private def model(calls: Chunk[ContentBlock.ToolUse]): LlmClient =
    LlmClient.mockFunction((history, _) =>
      if history.exists(_.role == Role.Tool) then TurnOutput.FinalText(Msg.assistant("done"))
      else TurnOutput.ToolCalls(Msg.assistant(calls))
    )

  def spec: Spec[TestEnvironment & Scope, Any] = suite("ReActAgent tool events")(
    test("commits local observations before delivering output and completion"):
      val lookup =
        Tool.make(ToolDef("lookup", "lookup", Json.Obj()))(_ => ZIO.succeed("observation"))
      for
        snapshots <- Ref.make(Chunk.empty[(AgentEvent, Chunk[Msg])])
        agent <- ReActAgent.make(
          client = model(Chunk(call)),
          toolkit = Toolkit(Map("lookup" -> lookup))
        )
        events <- agent
          .streamEvents(Msg.user("q"))
          .tap(event => agent.history.flatMap(history => snapshots.update(_ :+ (event -> history))))
          .runCollect
        observed <- snapshots.get
        starts = events.collect { case event: AgentEvent.ModelRequestStartEvent =>
          event.context
        }.flatten
        output = observed.collect {
          case (event: AgentEvent.ToolResultDeltaEvent, history) =>
            (event.callId, event.delta, event.context, history)
          case (event: AgentEvent.ToolResultEndEvent, history) =>
            (event.callId, event.output, event.context, history)
        }
      yield assertTrue(
        output.size == 2,
        output.forall { case (id, text, context, history) =>
          id == "call" && text == "observation" && context == starts.headOption &&
          history.flatMap(_.toolResults).exists(result => result.id == id && result.text == text)
        },
        events.indexWhere(_.isInstanceOf[AgentEvent.ToolResultStartEvent]) < events.indexWhere(
          _.isInstanceOf[AgentEvent.ToolResultDeltaEvent]
        ),
        events.indexWhere(_.isInstanceOf[AgentEvent.ToolResultDeltaEvent]) < events.indexWhere(
          _.isInstanceOf[AgentEvent.ToolResultEndEvent]
        ),
        events.indexWhere(_.isInstanceOf[AgentEvent.ToolResultEndEvent]) < events.lastIndexWhere(
          _.isInstanceOf[AgentEvent.ModelRequestStartEvent]
        )
      )
    ,
    test("publishes normalized tool failures as correlated error observations"):
      val lookup = Tool.make(ToolDef("lookup", "lookup", Json.Obj()))(_ =>
        ZIO.fail(AgentError.InvalidMessage("failed lookup"))
      )
      for
        agent <- ReActAgent.make(
          client = model(Chunk(call)),
          toolkit = Toolkit(Map("lookup" -> lookup))
        )
        events  <- agent.streamEvents(Msg.user("q")).runCollect
        history <- agent.history
        deltas = events.collect { case event: AgentEvent.ToolResultDeltaEvent => event }
        ends   = events.collect { case event: AgentEvent.ToolResultEndEvent => event }
      yield assertTrue(
        deltas.size == 1,
        ends.size == 1,
        ends.forall(event =>
          event.state == ToolResultState.Error && event.context.nonEmpty && event.output.contains(
            "failed lookup"
          )
        ),
        deltas.map(_.delta) == ends.map(_.output),
        history.flatMap(_.toolResults).exists(_.state == ToolResultState.Error),
        events.exists(_.isInstanceOf[AgentEvent.AgentResultEvent])
      )
    ,
    test("preserves empty local text observations with an explicit empty delta"):
      val lookup = Tool.make(ToolDef("lookup", "lookup", Json.Obj()))(_ => ZIO.succeed(""))
      for
        agent <- ReActAgent.make(
          client = model(Chunk(call)),
          toolkit = Toolkit(Map("lookup" -> lookup))
        )
        events <- agent.streamEvents(Msg.user("q")).runCollect
      yield assertTrue(
        events.collect { case event: AgentEvent.ToolResultDeltaEvent => event.delta } == Chunk(""),
        events.collect { case event: AgentEvent.ToolResultEndEvent => event.state } == Chunk(
          ToolResultState.Success
        )
      )
    ,
    test("parallel results retain per-call order and one originating request context"):
      ZIO.scoped:
        for
          release <- Promise.make[Nothing, Unit]
          first = Tool.make(ToolDef("first", "first", Json.Obj()))(_ =>
            release.await.as("first result")
          )
          second = Tool.make(ToolDef("second", "second", Json.Obj()))(_ =>
            ZIO.succeed("second result")
          )
          agent <- ReActAgent.make(
            client = model(
              Chunk(call.copy(id = "a", name = "first"), call.copy(id = "b", name = "second"))
            ),
            toolkit = Toolkit(Map("first" -> first, "second" -> second))
          )
          events <- agent
            .streamEvents(Msg.user("q"))
            .tap {
              case event: AgentEvent.ToolResultEndEvent if event.callId == "b" =>
                release.succeed(()).unit
              case _ => ZIO.unit
            }
            .runCollect
          starts = events.collect { case event: AgentEvent.ToolResultStartEvent => event }
          deltas = events.collect { case event: AgentEvent.ToolResultDeltaEvent => event }
          ends   = events.collect { case event: AgentEvent.ToolResultEndEvent => event }
        yield assertTrue(
          starts.map(_.callId).toSet == Set("a", "b"),
          ends.map(_.callId) == Chunk("b", "a"),
          deltas.map(_.callId) == ends.map(_.callId),
          starts.flatMap(_.context).distinct.size == 1,
          ends.flatMap(_.context).distinct == starts.flatMap(_.context).distinct,
          Chunk("a", "b").forall(id =>
            events.indexWhere {
              case event: AgentEvent.ToolResultStartEvent => event.callId == id; case _ => false
            } <
              events.indexWhere {
                case event: AgentEvent.ToolResultDeltaEvent => event.callId == id; case _ => false
              }
          )
        )
    ,
    test("cancellation preserves completed events and reconciles only unfinished history"):
      ZIO.scoped:
        for
          waiting <- Promise.make[Nothing, Unit]
          completed = Tool.make(ToolDef("fast", "fast", Json.Obj()))(_ => ZIO.succeed("kept"))
          pending = Tool.make(ToolDef("slow", "slow", Json.Obj()))(_ =>
            waiting.succeed(()) *> ZIO.never
          )
          agent <- ReActAgent.make(
            client =
              model(Chunk(call.copy(id = "a", name = "fast"), call.copy(id = "b", name = "slow"))),
            toolkit = Toolkit(Map("fast" -> completed, "slow" -> pending))
          )
          events <- agent
            .streamEvents(Msg.user("q"))
            .tap {
              case event: AgentEvent.ToolResultEndEvent if event.callId == "a" =>
                waiting.await *> agent.interrupt
              case _ => ZIO.unit
            }
            .runCollect
          history <- agent.history
          ends    = events.collect { case event: AgentEvent.ToolResultEndEvent => event }
          results = history.flatMap(_.toolResults)
        yield assertTrue(
          ends.map(_.callId) == Chunk("a"),
          ends.forall(_.state == ToolResultState.Success),
          results.exists(result =>
            result.id == "a" && result.state == ToolResultState.Success && result.text == "kept"
          ),
          results.exists(result => result.id == "b" && result.state == ToolResultState.Error),
          results.count(_.id == "b") == 1,
          events.lastOption.exists(_.isInstanceOf[AgentEvent.AgentEndEvent])
        )
    ,
    test("reused call IDs in later iterations retain distinct request provenance"):
      val lookup = Tool.make(ToolDef("lookup", "lookup", Json.Obj()))(_ => ZIO.succeed("value"))
      for
        client <- LlmClient.mock(
          Chunk(
            TurnOutput.ToolCalls(Msg.assistant(Chunk(call))),
            TurnOutput.ToolCalls(Msg.assistant(Chunk(call))),
            TurnOutput.FinalText(Msg.assistant("done"))
          )
        )
        agent  <- ReActAgent.make(client = client, toolkit = Toolkit(Map("lookup" -> lookup)))
        events <- agent.streamEvents(Msg.user("q")).runCollect
        contexts = events.collect { case event: AgentEvent.ToolResultEndEvent =>
          event.context
        }.flatten
      yield assertTrue(
        contexts.size == 2,
        contexts.map(_.requestId).distinct.size == 2,
        contexts.map(_.replyId).distinct.size == 1
      )
  ) @@ TestAspect.timeout(10.seconds)
