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
import com.weisgnosis.agents.core.model.{ContentBlock, MediaSource, Msg, Role, ToolResultState}
import com.weisgnosis.agents.core.tool.*
import zio.*
import zio.json.ast.Json
import zio.stream.ZStream
import zio.test.*

object ReActAgentToolEventSpec extends ZIOSpecDefault:
  private val call = ContentBlock.ToolUse("call", "lookup", Json.Obj())

  private def model(calls: Chunk[ContentBlock.ToolUse]): LlmClient =
    LlmClient.mockFunction((history, _) =>
      if history.exists(_.role == Role.Tool) then TurnOutput.FinalText(Msg.assistant("done"))
      else TurnOutput.ToolCalls(Msg.assistant(calls))
    )

  private def streamingTool(
      name: String,
      result: ToolExecutionResult,
      updates: ZStream[Any, Throwable, ToolExecutionUpdate[ToolExecutionResult]]
  ): Tool[Any, Json, ToolExecutionResult] = new Tool[Any, Json, ToolExecutionResult]:
    def definition: ToolDef = ToolDef(name, "Stream provisional output", Json.Obj())
    def decodeInput(raw: Json): Either[String, Json]   = Right(raw)
    def execute(input: Json): UIO[ToolExecutionResult] = ZIO.succeed(result)
    override def streamWithContext(
        input: Json,
        invocation: ToolInvocation
    ): ZStream[Any, Throwable, ToolExecutionUpdate[ToolExecutionResult]] = updates
    def encodeOutput(out: ToolExecutionResult): String                       = out.output
    override def encodeResult(out: ToolExecutionResult): ToolExecutionResult = out

  def spec: Spec[TestEnvironment & Scope, Any] = suite("ReActAgent tool events")(
    test(
      "provisional payload is visible before completion but only the final observation reaches the model"
    ):
      ZIO.scoped:
        val image    = ContentBlock.Image(MediaSource.Base64("image/png", "aW1hZ2U="))
        val metadata = Map("phase" -> Json.Str("progress"))
        val progress = ToolResultPayload(
          Chunk(ContentBlock.Text("working"), image),
          metadata.updated("serverTool", Json.Bool(true))
        )
        val result = ToolExecutionResult(
          ToolResultPayload(Chunk(ContentBlock.Text("final")), Map("phase" -> Json.Str("final"))),
          ToolResultState.Success
        )
        for
          release       <- Promise.make[Nothing, Unit]
          closed        <- Ref.make(false)
          snapshots     <- Ref.make(Chunk.empty[Msg])
          received      <- Ref.make(Chunk.empty[Msg])
          closedAtModel <- Ref.make(false)
          updates = ZStream
            .acquireReleaseWith(ZIO.unit)(_ => closed.set(true))
            .flatMap(_ =>
              ZStream.succeed(ToolExecutionUpdate.Progress(progress)) ++
                ZStream.fromZIO(release.await.as(ToolExecutionUpdate.Completed(result)))
            )
          client = LlmClient.mockEffectful((history, _, _) =>
            if history.exists(_.role == Role.Tool) then
              received.set(history) *> closed.get
                .flatMap(closedAtModel.set)
                .as(TurnOutput.FinalText(Msg.assistant("done")))
            else ZIO.succeed(TurnOutput.ToolCalls(Msg.assistant(Chunk(call))))
          )
          agent <- ReActAgent.make(
            client = client,
            toolkit = Toolkit(Map("lookup" -> streamingTool("lookup", result, updates)))
          )
          events <- agent
            .streamEvents(Msg.user("q"))
            .tap {
              case _: AgentEvent.ToolResultDataDeltaEvent =>
                agent.history.flatMap(snapshots.set) *> release.succeed(()).unit
              case _ => ZIO.unit
            }
            .runCollect
            .ensuring(release.succeed(()))
          during   <- snapshots.get
          after    <- received.get
          released <- closedAtModel.get
          deltas = events.collect { case event: AgentEvent.ToolResultDeltaEvent => event }
          data   = events.collect { case event: AgentEvent.ToolResultDataDeltaEvent => event }
          ends   = events.collect { case event: AgentEvent.ToolResultEndEvent => event }
        yield assertTrue(
          during.flatMap(_.toolUses) == Chunk(call),
          during.flatMap(_.toolResults).isEmpty,
          after.flatMap(_.toolResults).map(_.text) == Chunk("final"),
          after.flatMap(_.toolResults).forall(_.metadata == result.payload.metadata),
          released,
          deltas.map(_.delta) == Chunk("working"),
          data.map(_.data) == Chunk(image),
          deltas.forall(_.metadata == metadata),
          data.forall(event =>
            event.toolCallId == call.id && event.toolCallName == call.name && event.metadata == metadata
          ),
          ends.map(_.output) == Chunk("final"),
          ends.forall(event =>
            event.state == ToolResultState.Success && event.metadata == result.payload.metadata
          ),
          (deltas.flatMap(_.context) ++ data.flatMap(_.context) ++ ends.flatMap(
            _.context
          )).distinct.size == 1,
          events.indexWhere(_.isInstanceOf[AgentEvent.ToolResultStartEvent]) < events.indexWhere(
            _.isInstanceOf[AgentEvent.ToolResultDeltaEvent]
          )
        )
    ,
    test(
      "parallel repeated IDs isolate progress and preserve aggregate output for an ordinary sibling"
    ):
      ZIO.scoped:
        for
          release <- Promise.make[Nothing, Unit]
          result   = ToolExecutionResult("stream final")
          progress = ToolResultPayload(Chunk(ContentBlock.Text("provisional")))
          streaming = streamingTool(
            "streaming",
            result,
            ZStream.succeed(ToolExecutionUpdate.Progress(progress)) ++
              ZStream.fromZIO(release.await.as(ToolExecutionUpdate.Completed(result)))
          )
          otherResult = ToolExecutionResult("other final")
          other = streamingTool(
            "other",
            otherResult,
            ZStream.fromZIO(release.await).drain ++
              ZStream(
                ToolExecutionUpdate.Progress(
                  ToolResultPayload(Chunk(ContentBlock.Text("other progress")))
                ),
                ToolExecutionUpdate.Completed(otherResult)
              )
          )
          ordinary = Tool.make(ToolDef("ordinary", "Ordinary output", Json.Obj()))(_ =>
            release.await.as("ordinary final")
          )
          agent <- ReActAgent.make(
            client = model(
              Chunk(
                call.copy(name = "streaming"),
                call.copy(name = "other"),
                call.copy(name = "ordinary")
              )
            ),
            toolkit =
              Toolkit(Map("streaming" -> streaming, "other" -> other, "ordinary" -> ordinary))
          )
          events <- agent
            .streamEvents(Msg.user("q"))
            .tap {
              case event: AgentEvent.ToolResultDeltaEvent if event.delta == "provisional" =>
                release.succeed(()).unit
              case _ => ZIO.unit
            }
            .runCollect
            .ensuring(release.succeed(()))
          history <- agent.history
          deltas = events.collect { case event: AgentEvent.ToolResultDeltaEvent => event.delta }
          ends   = events.collect { case event: AgentEvent.ToolResultEndEvent => event }
        yield assertTrue(
          deltas.headOption.contains("provisional"),
          deltas.toSet == Set("provisional", "other progress", "ordinary final"),
          deltas.size == 3,
          ends.map(_.name).toSet == Set("streaming", "other", "ordinary"),
          ends.forall(event => event.callId == "call" && event.state == ToolResultState.Success),
          history.flatMap(_.toolResults).map(_.text).toSet == Set(
            "stream final",
            "other final",
            "ordinary final"
          ),
          history.flatMap(_.toolResults).size == 3
        )
    ,
    test("concurrent agents sharing one toolkit isolate streamed output and request context"):
      ZIO.scoped:
        for
          entered <- ZIO.acquireRelease(Queue.unbounded[String])(_.shutdown)
          release <- Promise.make[Nothing, Unit]
          shared = new Tool[Any, String, String]:
            def definition: ToolDef = ToolDef("lookup", "Stream isolated output", Json.Obj())
            def decodeInput(raw: Json): Either[String, String] =
              raw.asString.toRight("Expected a label")
            def encodeOutput(output: String): String = output
            def execute(input: String): UIO[String]  = ZIO.succeed(s"done-$input")
            override def streamWithContext(
                input: String,
                invocation: ToolInvocation
            ): ZStream[Any, Throwable, ToolExecutionUpdate[String]] =
              ZStream.succeed(
                ToolExecutionUpdate.Progress(
                  ToolResultPayload(Chunk(ContentBlock.Text(s"chunk-$input")))
                )
              ) ++
                ZStream.fromZIO(entered.offer(input) *> release.await).drain ++
                ZStream(
                  ToolExecutionUpdate.Progress(
                    ToolResultPayload(Chunk(ContentBlock.Text(s"chunk2-$input")))
                  ),
                  ToolExecutionUpdate.Completed(s"done-$input")
                )
          toolkit = Toolkit(Map("lookup" -> shared))
          agents <- ZIO.foreach(Chunk("A", "B"))(label =>
            ReActAgent
              .make(
                client = model(Chunk(call.copy(input = Json.Str(label)))),
                toolkit = toolkit
              )
              .map(agent => (label, agent))
          )
          collect = ZIO.foreachPar(agents): (label, agent) =>
            for
              events  <- agent.streamEvents(Msg.user(label)).runCollect
              history <- agent.history
            yield (label, events, history)
          assertion <- (for
            running <- collect.forkScoped
            first   <- entered.take
            second  <- entered.take
            held    <- running.poll
            _       <- release.succeed(())
            results <- running.join
            contexts = results.flatMap { case (_, events, _) =>
              events
                .collect { case event: AgentEvent.ModelRequestStartEvent => event.context }
                .flatten
                .take(1)
            }
            destinations = results.map: (label, events, history) =>
              val context = events
                .collect { case event: AgentEvent.ModelRequestStartEvent =>
                  event.context
                }
                .flatten
                .headOption
              val starts = events.collect { case event: AgentEvent.ToolResultStartEvent => event }
              val deltas = events.collect { case event: AgentEvent.ToolResultDeltaEvent => event }
              val ends   = events.collect { case event: AgentEvent.ToolResultEndEvent => event }
              assertTrue(
                starts.size == 1,
                deltas.map(_.delta) == Chunk(s"chunk-$label", s"chunk2-$label"),
                ends.map(_.output) == Chunk(s"done-$label"),
                ends.forall(_.state == ToolResultState.Success),
                context.nonEmpty,
                starts.forall(event => event.callId == call.id && event.context == context),
                deltas.forall(event => event.callId == call.id && event.context == context),
                ends.forall(event => event.callId == call.id && event.context == context),
                history.flatMap(_.toolUses).map(_.input) == Chunk(Json.Str(label)),
                history.flatMap(_.toolResults).map(_.text) == Chunk(s"done-$label")
              )
          yield assertTrue(
            Set(first, second) == Set("A", "B"),
            held.isEmpty,
            contexts.size == 2,
            contexts.map(_.requestId).distinct.size == 2,
            contexts.map(_.replyId).distinct.size == 2
          ) && destinations.foldLeft(assertTrue(true))(_ && _))
            .ensuring(release.succeed(()).unit)
        yield assertion
    ,
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
          pending = streamingTool(
            "slow",
            ToolExecutionResult("unreachable"),
            ZStream.succeed(
              ToolExecutionUpdate.Progress(ToolResultPayload(Chunk(ContentBlock.Text("pending"))))
            ) ++
              ZStream.fromZIO(waiting.succeed(()) *> ZIO.never)
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
