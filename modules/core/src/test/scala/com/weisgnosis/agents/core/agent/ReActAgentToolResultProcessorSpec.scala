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

object ReActAgentToolResultProcessorSpec extends ZIOSpecDefault:
  private def call(name: String, id: String = "call"): ContentBlock.ToolUse =
    ContentBlock.ToolUse(id, name, Json.Obj())
  private def output(calls: ContentBlock.ToolUse*): TurnOutput =
    TurnOutput.ToolCalls(Msg.assistant(Chunk.fromIterable(calls)))
  private def tool(name: String): Tool[Any, Json, String] =
    Tool.make(ToolDef(name, "Read output", Json.Obj()))(_ => ZIO.succeed(s"raw-$name"))

  def spec: Spec[TestEnvironment & Scope, Any] = suite("ReActAgentToolResultProcessorSpec")(
    test(
      "typed tool execution persists error content and emits data while runtime owns identity and control metadata"
    ) {
      val image = ContentBlock.Image(MediaSource.Base64("image/png", "aW1hZ2U="))
      val metadata =
        Map("mcp" -> Json.Obj("structuredContent" -> Json.Obj("reason" -> Json.Str("unavailable"))))
      val payload = ToolResultPayload(
        Chunk(ContentBlock.Text("partial result"), image),
        metadata ++ Map(
          "serverTool"             -> Json.Bool(true),
          "agentscope_server_tool" -> Json.Bool(true),
          "agentscope_suspended"   -> Json.Bool(true)
        )
      )
      val typed = new Tool[Any, Json, ToolExecutionResult]:
        def definition: ToolDef = ToolDef("lookup", "Typed output", Json.Obj())
        def decodeInput(raw: Json): Either[String, Json] = Right(raw)
        def execute(input: Json): UIO[ToolExecutionResult] =
          ZIO.succeed(ToolExecutionResult(payload, ToolResultState.Error))
        def encodeOutput(out: ToolExecutionResult): String                       = out.output
        override def encodeResult(out: ToolExecutionResult): ToolExecutionResult = out
      for
        observed <- Ref.make(Chunk.empty[ContentBlock.ToolResult])
        model = LlmClient.mockEffectful((history, _, _) =>
          if history.flatMap(_.toolResults).isEmpty then ZIO.succeed(output(call("lookup")))
          else
            observed
              .set(history.flatMap(_.toolResults))
              .as(TurnOutput.FinalText(Msg.assistant("done")))
        )
        agent    <- ReActAgent.make(client = model, toolkit = Toolkit(Map("lookup" -> typed)))
        events   <- agent.streamEvents(Msg.user("q")).runCollect
        history  <- agent.history
        received <- observed.get
        expected = ContentBlock.ToolResult(
          "call",
          Some("lookup"),
          payload.output,
          ToolResultState.Error,
          metadata
        )
      yield assertTrue(
        received == Chunk(expected),
        history.flatMap(_.toolResults) == received,
        events.collect { case event: AgentEvent.ToolResultDataDeltaEvent => event.data } == Chunk(
          image
        ),
        events.collect { case event: AgentEvent.ToolResultEndEvent => event.state } == Chunk(
          ToolResultState.Error
        )
      )
    },
    test(
      "processing observes committed raw output then commits safe payload before model continuation and events"
    ) {
      for
        agentRef               <- Ref.make(Option.empty[ReActAgent])
        rawObserved            <- Ref.make(Chunk.empty[ContentBlock.ToolResult])
        nextObserved           <- Ref.make(Chunk.empty[ContentBlock.ToolResult])
        closed                 <- Ref.make(false)
        releasedAtContinuation <- Ref.make(false)
        processor = ToolResultProcessor(_ =>
          for
            agent <- agentRef.get.flatMap(value =>
              ZIO
                .fromOption(value)
                .orElseFail(AgentError.InvalidConfiguration("missing test agent"))
            )
            history <- agent.history
            _       <- rawObserved.set(history.flatMap(_.toolResults))
            _       <- ZIO.addFinalizer(closed.set(true))
          yield ToolResultPayload(
            Chunk(ContentBlock.Text("preview")),
            Map(
              "cache"                -> Json.Str("key"),
              "serverTool"           -> Json.Bool(true),
              "agentscope_suspended" -> Json.Bool(true)
            )
          )
        )
        model = LlmClient.mockEffectful((history, _, _) =>
          if history.flatMap(_.toolResults).isEmpty then ZIO.succeed(output(call("lookup")))
          else
            nextObserved.set(history.flatMap(_.toolResults)) *> closed.get
              .flatMap(releasedAtContinuation.set)
              .as(TurnOutput.FinalText(Msg.assistant("done")))
        )
        agent <- ReActAgent
          .builder()
          .model(model)
          .toolkit(Toolkit(Map("lookup" -> tool("lookup"))))
          .toolResultProcessor(processor)
          .build
        _        <- agentRef.set(Some(agent))
        events   <- agent.streamEvents(Msg.user("q")).runCollect
        raw      <- rawObserved.get
        observed <- nextObserved.get
        released <- releasedAtContinuation.get
        history  <- agent.history
      yield assertTrue(
        raw.map(_.text) == Chunk("raw-lookup"),
        observed.map(_.text) == Chunk("preview"),
        released,
        observed.forall(result =>
          result.id == "call" && result.name.contains(
            "lookup"
          ) && result.state == ToolResultState.Success && !result.isServerTool && !result.isSuspended
        ),
        observed.forall(_.metadata == Map("cache" -> Json.Str("key"))),
        history.flatMap(_.toolResults) == observed,
        events.collect { case event: AgentEvent.ToolResultDeltaEvent => event.delta } == Chunk(
          "preview"
        ),
        events.collect { case event: AgentEvent.ToolResultEndEvent => event.metadata } == Chunk(
          Map("cache" -> Json.Str("key"))
        )
      )
    },
    test("typed processor failure preserves the normalized error observation and closes resources") {
      for
        closed <- Ref.make(false)
        failing = Tool.make(ToolDef("lookup", "Fail", Json.Obj()))(_ =>
          ZIO.fail(AgentError.ToolExecutionError("lookup", "failed"))
        )
        client <- LlmClient.mock(
          Chunk(output(call("lookup")), TurnOutput.FinalText(Msg.assistant("done")))
        )
        processor = ToolResultProcessor(_ =>
          ZIO.addFinalizer(closed.set(true)) *> ZIO.fail(
            AgentError.ToolExecutionError("store", "disk full")
          )
        )
        agent <- ReActAgent.make(
          client = client,
          toolkit = Toolkit(Map("lookup" -> failing)),
          toolResultProcessor = processor
        )
        result   <- agent.run(Msg.user("q"))
        released <- closed.get
      yield assertTrue(
        released,
        result.reply.text == "done",
        result.history
          .flatMap(_.toolResults)
          .exists(result => result.state == ToolResultState.Error && result.text.contains("failed"))
      )
    },
    test(
      "cooperative cancellation during processing preserves completed raw results and reconciles only unexecuted calls"
    ) {
      for
        entered  <- Promise.make[Nothing, Unit]
        closed   <- Ref.make(false)
        executed <- Ref.make(Chunk.empty[String])
        first = Tool.make(ToolDef("first", "First", Json.Obj()))(_ =>
          executed.update(_ :+ "first").as("completed raw")
        )
        second = Tool.make(ToolDef("second", "Second", Json.Obj()))(_ =>
          executed.update(_ :+ "second").as("second raw")
        )
        client <- LlmClient.mock(
          Chunk(output(call("first", "first-call"), call("second", "second-call")))
        )
        processor = ToolResultProcessor(_ =>
          ZIO.addFinalizer(closed.set(true)) *> entered.succeed(()) *> ZIO.never
        )
        agent <- ReActAgent.make(
          config = AgentConfig(toolExecution = ToolExecution.Sequential),
          client = client,
          toolkit = Toolkit(Map("first" -> first, "second" -> second)),
          toolResultProcessor = processor
        )
        fiber    <- agent.streamEvents(Msg.user("q")).runCollect.forkScoped
        _        <- entered.await
        _        <- agent.interrupt
        events   <- fiber.join
        history  <- agent.history
        calls    <- executed.get
        released <- closed.get
        results = history.flatMap(_.toolResults)
      yield assertTrue(
        released,
        calls == Chunk("first"),
        results.size == 2,
        results.exists(result =>
          result.id == "first-call" && result.state == ToolResultState.Success && result.text == "completed raw"
        ),
        results.exists(result =>
          result.id == "second-call" && result.state == ToolResultState.Error
        ),
        !events.exists(_.isInstanceOf[AgentEvent.ToolResultEndEvent]),
        history.count(_.generateReason.contains("interrupted")) == 1
      )
    },
    test("fiber interruption during processing retains raw success exactly once") {
      for
        entered <- Promise.make[Nothing, Unit]
        closed  <- Ref.make(false)
        client  <- LlmClient.mock(Chunk(output(call("lookup"))))
        processor = ToolResultProcessor(_ =>
          ZIO.addFinalizer(closed.set(true)) *> entered.succeed(()) *> ZIO.never
        )
        agent <- ReActAgent.make(
          client = client,
          toolkit = Toolkit(Map("lookup" -> tool("lookup"))),
          toolResultProcessor = processor
        )
        fiber    <- agent.run(Msg.user("q")).forkScoped
        _        <- entered.await
        exit     <- fiber.interrupt
        history  <- agent.history
        released <- closed.get
      yield assertTrue(
        exit.isInterrupted,
        released,
        history.flatMap(_.toolResults).map(result => (result.text, result.state)) == Chunk(
          ("raw-lookup", ToolResultState.Success)
        ),
        history.count(_.generateReason.contains("interrupted")) == 1
      )
    },
    test("parallel processors finish in reverse order without overwriting same-ID observations") {
      for
        firstStored     <- Promise.make[Nothing, Unit]
        secondPublished <- Promise.make[Nothing, Unit]
        second = Tool.make(ToolDef("second", "Second", Json.Obj()))(_ =>
          firstStored.await.as("raw-second")
        )
        client <- LlmClient.mock(
          Chunk(
            output(call("first", "same"), call("second", "same")),
            TurnOutput.FinalText(Msg.assistant("done"))
          )
        )
        processor = ToolResultProcessor(result =>
          if result.name.contains("first") then
            firstStored.succeed(()) *> secondPublished.await.as(
              ToolResultPayload(Chunk(ContentBlock.Text("preview-first")))
            )
          else ZIO.succeed(ToolResultPayload(Chunk(ContentBlock.Text("preview-second"))))
        )
        agent <- ReActAgent.make(
          client = client,
          toolkit = Toolkit(Map("first" -> tool("first"), "second" -> second)),
          toolResultProcessor = processor
        )
        events <- agent
          .streamEvents(Msg.user("q"))
          .tap {
            case event: AgentEvent.ToolResultEndEvent if event.name == "second" =>
              secondPublished.succeed(()).unit
            case _ => ZIO.unit
          }
          .runCollect
        history <- agent.history
      yield assertTrue(
        history.flatMap(_.toolResults).map(result => result.name -> result.text) == Chunk(
          Some("first")  -> "preview-first",
          Some("second") -> "preview-second"
        ),
        events.collect { case event: AgentEvent.ToolResultEndEvent => event.name } == Chunk(
          "second",
          "first"
        )
      )
    },
    test("inline provider observations are processed without local dispatch") {
      val serverCall = call("server").withServerTool(true)
      val inline = ContentBlock.ToolResult
        .text("call", "server", "inline")
        .copy(metadata = Map("serverTool" -> Json.Bool(true)))
      for
        calls <- Ref.make(0)
        client <- LlmClient.mock(
          Chunk(TurnOutput.ToolCalls(Msg.assistant(Chunk(serverCall, inline))))
        )
        processor = ToolResultProcessor(result =>
          calls.update(_ + 1).as(ToolResultPayload.fromResult(result))
        )
        agent  <- ReActAgent.make(client = client, toolResultProcessor = processor)
        result <- agent.run(Msg.user("q"))
        count  <- calls.get
      yield assertTrue(
        count == 1,
        result.history.forall(_.role != Role.Tool),
        result.reply.toolResults == Chunk(inline)
      )
    },
    test("sequential processor defects preserve raw completion and reconcile unstarted siblings") {
      val defect = new IllegalStateException("processor defect")
      for
        executed <- Ref.make(Chunk.empty[String])
        closed   <- Ref.make(false)
        seen     <- Ref.make(Chunk.empty[AgentEvent])
        first = Tool.make(ToolDef("first", "First", Json.Obj()))(_ =>
          executed.update(_ :+ "first").as("raw first")
        )
        second = Tool.make(ToolDef("second", "Second", Json.Obj()))(_ =>
          executed.update(_ :+ "second").as("raw second")
        )
        client <- LlmClient.mock(Chunk(output(call("first", "first"), call("second", "second"))))
        agent <- ReActAgent.make(
          config = AgentConfig(toolExecution = ToolExecution.Sequential),
          client = client,
          toolkit = Toolkit(Map("first" -> first, "second" -> second)),
          toolResultProcessor =
            ToolResultProcessor(_ => ZIO.addFinalizer(closed.set(true)) *> ZIO.die(defect))
        )
        exit <- agent
          .streamEvents(Msg.user("q"))
          .tap(event => seen.update(_ :+ event))
          .runDrain
          .exit
        history  <- agent.history
        names    <- executed.get
        released <- closed.get
        events   <- seen.get
      yield assertTrue(
        exit.causeOption.exists(_.defects.contains(defect)),
        released,
        names == Chunk("first"),
        history.flatMap(_.toolResults).map(r => (r.id, r.state)) == Chunk(
          ("first", ToolResultState.Success),
          ("second", ToolResultState.Error)
        ),
        history.flatMap(_.toolResults).headOption.exists(_.text == "raw first"),
        ToolHistory.inspect(history).isComplete,
        history.forall(!_.generateReason.contains("interrupted")),
        !events.exists(_.isInstanceOf[AgentEvent.ToolResultEndEvent])
      )
    },
    test(
      "parallel processor defects wait for sibling cleanup before finalizing unmatched observations"
    ) {
      val defect = new IllegalStateException("parallel processor defect")
      for
        entered <- Promise.make[Nothing, Unit]
        closed  <- Ref.make(false)
        sibling = Tool.make(ToolDef("second", "Second", Json.Obj()))(_ =>
          (entered.succeed(()) *> ZIO.never).ensuring(closed.set(true))
        )
        client <- LlmClient.mock(Chunk(output(call("first", "first"), call("second", "second"))))
        agent <- ReActAgent.make(
          client = client,
          toolkit = Toolkit(Map("first" -> tool("first"), "second" -> sibling)),
          toolResultProcessor = ToolResultProcessor(_ => entered.await *> ZIO.die(defect))
        )
        exit     <- agent.run(Msg.user("q")).exit
        history  <- agent.history
        released <- closed.get
      yield assertTrue(
        exit.causeOption.exists(_.defects.contains(defect)),
        released,
        history.flatMap(_.toolResults).map(r => (r.id, r.state)) == Chunk(
          ("first", ToolResultState.Success),
          ("second", ToolResultState.Error)
        ),
        ToolHistory.inspect(history).isComplete,
        history.forall(!_.generateReason.contains("interrupted"))
      )
    },
    test("processor finalizer defects preserve raw output and close pending calls") {
      val defect = new IllegalStateException("processor finalizer defect")
      for
        client <- LlmClient.mock(Chunk(output(call("first", "first"), call("second", "second"))))
        agent <- ReActAgent.make(
          config = AgentConfig(toolExecution = ToolExecution.Sequential),
          client = client,
          toolkit = Toolkit(Map("first" -> tool("first"), "second" -> tool("second"))),
          toolResultProcessor = ToolResultProcessor(_ =>
            ZIO
              .addFinalizer(ZIO.die(defect))
              .as(ToolResultPayload(Chunk(ContentBlock.Text("uncommitted preview"))))
          )
        )
        exit    <- agent.run(Msg.user("q")).exit
        history <- agent.history
      yield assertTrue(
        exit.causeOption.exists(_.defects.contains(defect)),
        history.flatMap(_.toolResults).headOption.exists(_.text == "raw-first"),
        ToolHistory.inspect(history).isComplete,
        history.forall(!_.generateReason.contains("interrupted"))
      )
    },
    test("a later composition failure restores original output and releases earlier resources") {
      for
        closed <- Ref.make(false)
        first = ToolResultProcessor(_ =>
          ZIO
            .addFinalizer(closed.set(true))
            .as(ToolResultPayload(Chunk(ContentBlock.Text("intermediate"))))
        )
        second = ToolResultProcessor(_ =>
          ZIO.fail(AgentError.ToolExecutionError("processor", "failed"))
        )
        client <- LlmClient.mock(
          Chunk(output(call("lookup")), TurnOutput.FinalText(Msg.assistant("done")))
        )
        agent <- ReActAgent.make(
          client = client,
          toolkit = Toolkit(Map("lookup" -> tool("lookup"))),
          toolResultProcessor = first.andThen(second)
        )
        result   <- agent.run(Msg.user("q"))
        released <- closed.get
      yield assertTrue(
        released,
        result.history.flatMap(_.toolResults).map(_.text) == Chunk("raw-lookup")
      )
    }
  ) @@ TestAspect.timeout(20.seconds)
