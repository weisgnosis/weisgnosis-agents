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
import com.weisgnosis.agents.core.model.*
import com.weisgnosis.agents.core.tool.*
import zio.*
import zio.json.ast.Json
import zio.test.*

object ReActAgentInlineProcessingSpec extends ZIOSpecDefault:
  private val server: ContentBlock.ToolUse =
    ContentBlock.ToolUse("same", "server", Json.Obj()).withServerTool(true)
  private val local: ContentBlock.ToolUse = ContentBlock.ToolUse("same", "local", Json.Obj())
  private val inline: ContentBlock.ToolResult = ContentBlock.ToolResult
    .text("same", "server", "raw inline")
    .copy(metadata = Map("serverTool" -> Json.Bool(true)))
  private val assistant: Msg = Msg.assistant(Chunk(server, inline, local))
  private def model(message: Msg): UIO[LlmClient] =
    LlmClient.mock(
      Chunk(TurnOutput.ToolCalls(message), TurnOutput.FinalText(Msg.assistant("done")))
    )

  def spec: Spec[TestEnvironment & Scope, Any] = suite("ReActAgentInlineProcessingSpec")(
    test(
      "inline processing commits exact duplicate occurrences and closes resources before events"
    ) {
      for
        agentRef       <- Ref.make(Option.empty[ReActAgent])
        raw            <- Ref.make(Chunk.empty[String])
        closed         <- Ref.make(0)
        count          <- Ref.make(0)
        authorized     <- Ref.make(0)
        closedAtEvents <- Ref.make(Chunk.empty[Int])
        client         <- model(Msg.assistant(Chunk(server, inline, server, inline)))
        processor = ToolResultProcessor(_ =>
          for
            agent <- agentRef.get.flatMap(value =>
              ZIO
                .fromOption(value)
                .orElseFail(AgentError.InvalidConfiguration("missing test agent"))
            )
            history <- agent.history
            _       <- raw.update(_ ++ history.flatMap(_.toolResults).map(_.text))
            ordinal <- count.updateAndGet(_ + 1)
            _       <- ZIO.addFinalizer(closed.update(_ + 1))
          yield ToolResultPayload(
            Chunk(ContentBlock.Text(s"preview-$ordinal")),
            Map("cache" -> Json.Str(s"$ordinal"))
          )
        )
        agent <- ReActAgent.make(
          client = client,
          toolResultProcessor = processor,
          toolAuthorizer = ToolAuthorizer(request =>
            authorized.update(_ + 1).as(ToolAuthorization.Approved(request.call.input))
          )
        )
        _ <- agentRef.set(Some(agent))
        events <- agent
          .streamEvents(Msg.user("q"))
          .tap {
            case _: AgentEvent.ToolResultStartEvent =>
              closed.get.flatMap(n => closedAtEvents.update(_ :+ n))
            case _ => ZIO.unit
          }
          .runCollect
        history   <- agent.history
        observed  <- raw.get
        released  <- closedAtEvents.get
        authCount <- authorized.get
        resultMessages = events.collect { case event: AgentEvent.AgentResultEvent => event.result }
      yield assertTrue(
        observed == Chunk("raw inline", "raw inline", "preview-1", "raw inline"),
        history.flatMap(_.toolResults).map(_.text) == Chunk("preview-1", "preview-2"),
        history.flatMap(_.toolResults).forall(_.isServerTool),
        history.forall(_.role != Role.Tool),
        resultMessages.flatMap(_.toolResults) == history.flatMap(_.toolResults),
        released.size == 2 && released.zipWithIndex.forall((n, index) => n >= index + 1),
        authCount == 0,
        events.collect { case event: AgentEvent.ToolResultDeltaEvent => event.delta } == Chunk(
          "preview-1",
          "preview-2"
        )
      )
    },
    test(
      "same-ID local and inline observations are processed separately with only local authorization"
    ) {
      for
        processed  <- Ref.make(Chunk.empty[String])
        authorized <- Ref.make(Chunk.empty[String])
        client     <- model(assistant)
        tool = Tool.make(ToolDef("local", "Local", Json.Obj()))(_ => ZIO.succeed("raw local"))
        agent <- ReActAgent.make(
          client = client,
          toolkit = Toolkit.single(tool),
          toolResultProcessor = ToolResultProcessor(result =>
            processed
              .update(_ :+ result.text)
              .as(ToolResultPayload(Chunk(ContentBlock.Text(s"preview ${result.text}"))))
          ),
          toolAuthorizer = ToolAuthorizer(request =>
            authorized
              .update(_ :+ request.call.name)
              .as(ToolAuthorization.Approved(request.call.input))
          )
        )
        result       <- agent.run(Msg.user("q"))
        observations <- processed.get
        approvals    <- authorized.get
      yield assertTrue(
        observations == Chunk("raw inline", "raw local"),
        approvals == Chunk("local"),
        result.history.filter(_.role == Role.Tool).flatMap(_.toolResults).map(_.text) == Chunk(
          "preview raw local"
        ),
        ToolHistory.inspect(result.history).isComplete
      )
    },
    test("typed inline processor failure retains original payload after resource cleanup") {
      for
        closed <- Ref.make(false)
        client <- model(Msg.assistant(Chunk(server, inline)))
        agent <- ReActAgent.make(
          client = client,
          toolResultProcessor = ToolResultProcessor(_ =>
            ZIO.addFinalizer(closed.set(true)) *> ZIO.fail(
              AgentError.ToolExecutionError("store", "failed")
            )
          )
        )
        result   <- agent.run(Msg.user("q"))
        released <- closed.get
      yield assertTrue(
        released,
        result.reply.toolResults == Chunk(inline),
        result.history.forall(_.role != Role.Tool)
      )
    },
    test(
      "cooperative cancellation retains a processed inline sibling and reconciles only pending local calls"
    ) {
      for
        entered <- Promise.make[Nothing, Unit]
        count   <- Ref.make(0)
        closed  <- Ref.make(false)
        client  <- model(Msg.assistant(Chunk(server, inline, server, inline, local)))
        agent <- ReActAgent.make(
          client = client,
          toolResultProcessor = ToolResultProcessor(_ =>
            count.updateAndGet(_ + 1).flatMap {
              case 1 => ZIO.succeed(ToolResultPayload(Chunk(ContentBlock.Text("preview"))))
              case _ => ZIO.addFinalizer(closed.set(true)) *> entered.succeed(()) *> ZIO.never
            }
          )
        )
        fiber    <- agent.streamEvents(Msg.user("q")).runCollect.forkScoped
        _        <- entered.await
        _        <- agent.interrupt
        events   <- fiber.join
        history  <- agent.history
        released <- closed.get
      yield assertTrue(
        released,
        history.filter(_.role == Role.Assistant).flatMap(_.toolResults).map(_.text) == Chunk(
          "preview",
          "raw inline"
        ),
        history
          .filter(_.role == Role.Tool)
          .flatMap(_.toolResults)
          .map(r => (r.name, r.state)) == Chunk((Some("local"), ToolResultState.Error)),
        events.count(_.isInstanceOf[AgentEvent.ToolResultEndEvent]) == 1,
        history.count(_.generateReason.contains("interrupted")) == 1,
        ToolHistory.inspect(history).isComplete
      )
    },
    test(
      "fiber interruption during inline processing preserves provider completion and closes its scope"
    ) {
      for
        entered <- Promise.make[Nothing, Unit]
        closed  <- Ref.make(false)
        client  <- model(assistant)
        agent <- ReActAgent.make(
          client = client,
          toolResultProcessor = ToolResultProcessor(_ =>
            ZIO.addFinalizer(closed.set(true)) *> entered.succeed(()) *> ZIO.never
          )
        )
        fiber    <- agent.run(Msg.user("q")).forkScoped
        _        <- entered.await
        exit     <- fiber.interrupt
        history  <- agent.history
        released <- closed.get
      yield assertTrue(
        exit.isInterrupted,
        released,
        history.filter(_.role == Role.Assistant).flatMap(_.toolResults) == Chunk(inline),
        history.filter(_.role == Role.Tool).flatMap(_.toolResults).map(_.name) == Chunk(
          Some("local")
        ),
        history.count(_.generateReason.contains("interrupted")) == 1,
        ToolHistory.inspect(history).isComplete
      )
    },
    test(
      "inline processor defects retain their cause and finalize only pending local observations"
    ) {
      val defect = new IllegalStateException("inline processor defect")
      for
        client <- model(assistant)
        seen   <- Ref.make(Chunk.empty[AgentEvent])
        agent <- ReActAgent.make(
          client = client,
          toolResultProcessor = ToolResultProcessor(_ => ZIO.die(defect))
        )
        exit <- agent
          .streamEvents(Msg.user("q"))
          .tap(event => seen.update(_ :+ event))
          .runDrain
          .exit
        history <- agent.history
        events  <- seen.get
      yield assertTrue(
        exit.causeOption.exists(_.defects.contains(defect)),
        history.filter(_.role == Role.Assistant).flatMap(_.toolResults) == Chunk(inline),
        history
          .filter(_.role == Role.Tool)
          .flatMap(_.toolResults)
          .forall(_.state == ToolResultState.Error),
        ToolHistory.inspect(history).isComplete,
        history.forall(!_.generateReason.contains("interrupted")),
        !events.exists(_.isInstanceOf[AgentEvent.ToolResultEndEvent])
      )
    },
    test(
      "cooperative cancellation propagates a processor finalizer defect after pairing pending calls"
    ) {
      val defect = new IllegalStateException("cancelled processor finalizer defect")
      for
        entered <- Promise.make[Nothing, Unit]
        client  <- model(assistant)
        agent <- ReActAgent.make(
          client = client,
          toolResultProcessor = ToolResultProcessor(_ =>
            ZIO.addFinalizer(ZIO.die(defect)) *> entered.succeed(()) *> ZIO.never
          )
        )
        fiber   <- agent.run(Msg.user("q")).exit.forkScoped
        _       <- entered.await
        _       <- agent.interrupt
        exit    <- fiber.join
        history <- agent.history
      yield assertTrue(
        exit.causeOption.exists(_.defects.contains(defect)),
        history.filter(_.role == Role.Assistant).flatMap(_.toolResults) == Chunk(inline),
        ToolHistory.inspect(history).isComplete,
        history.count(_.generateReason.contains("interrupted")) == 1
      )
    },
    test(
      "fiber cancellation preserves a processor finalizer defect and completed provider observation"
    ) {
      val defect = new IllegalStateException("interrupted processor finalizer defect")
      for
        entered <- Promise.make[Nothing, Unit]
        client  <- model(assistant)
        agent <- ReActAgent.make(
          client = client,
          toolResultProcessor = ToolResultProcessor(_ =>
            ZIO.addFinalizer(ZIO.die(defect)) *> entered.succeed(()) *> ZIO.never
          )
        )
        fiber   <- agent.run(Msg.user("q")).forkScoped
        _       <- entered.await
        exit    <- fiber.interrupt
        history <- agent.history
      yield assertTrue(
        exit.isInterrupted,
        exit.causeOption.exists(_.defects.contains(defect)),
        history.filter(_.role == Role.Assistant).flatMap(_.toolResults) == Chunk(inline),
        ToolHistory.inspect(history).isComplete
      )
    },
    test(
      "timeout propagates processor cleanup defects instead of replacing them with TimeoutError"
    ) {
      val defect = new IllegalStateException("timed out processor finalizer defect")
      for
        entered <- Promise.make[Nothing, Unit]
        client  <- model(assistant)
        agent <- ReActAgent.make(
          config = AgentConfig(timeout = 1.second),
          client = client,
          toolResultProcessor = ToolResultProcessor(_ =>
            ZIO.addFinalizer(ZIO.die(defect)) *> entered.succeed(()) *> ZIO.never
          )
        )
        fiber   <- agent.run(Msg.user("q")).exit.forkScoped
        _       <- entered.await
        _       <- TestClock.adjust(1.second)
        exit    <- fiber.join
        history <- agent.history
      yield assertTrue(
        exit.causeOption.exists(_.defects.contains(defect)),
        exit.causeOption.forall(_.failureOption.isEmpty),
        history.filter(_.role == Role.Assistant).flatMap(_.toolResults) == Chunk(inline),
        ToolHistory.inspect(history).isComplete,
        history.forall(!_.generateReason.contains("interrupted"))
      )
    },
    test(
      "a mixed processor cause closes pending calls without misclassifying the defect as cancellation"
    ) {
      val defect = new IllegalStateException("mixed processor defect")
      for
        client <- model(assistant)
        agent <- ReActAgent.make(
          client = client,
          toolResultProcessor = ToolResultProcessor(_ =>
            ZIO.failCause(Cause.die(defect) && Cause.interrupt(FiberId.None))
          )
        )
        exit    <- agent.run(Msg.user("q")).exit
        history <- agent.history
      yield assertTrue(
        exit.causeOption.exists(_.defects.contains(defect)),
        ToolHistory.inspect(history).isComplete,
        history.forall(!_.generateReason.contains("interrupted"))
      )
    }
  ) @@ TestAspect.timeout(20.seconds)
