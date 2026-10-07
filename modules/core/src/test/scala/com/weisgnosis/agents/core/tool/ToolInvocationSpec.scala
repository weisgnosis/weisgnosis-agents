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
package com.weisgnosis.agents.core.tool

import com.weisgnosis.agents.core.agent.{AgentConfig, ReActAgent, ToolExecution}
import com.weisgnosis.agents.core.client.{LlmClient, TurnOutput}
import com.weisgnosis.agents.core.event.AgentEvent
import com.weisgnosis.agents.core.model.{ContentBlock, Msg, ToolResultState}
import zio.*
import zio.json.ast.Json
import zio.test.*

object ToolInvocationSpec extends ZIOSpecDefault:
  private val marker: AgentEvent         = AgentEvent.AgentProgressEvent("tool progress", 1)
  private val call: ContentBlock.ToolUse = ContentBlock.ToolUse("repeated", "inspect", Json.Obj())

  private def contextual(
      ordinary: UIO[String],
      run: (Json, ToolInvocation) => Task[String]
  ): Tool[Any, Json, String] = new Tool[Any, Json, String]:
    def definition: ToolDef                = ToolDef("inspect", "Inspect invocation", Json.Obj())
    def execute(input: Json): Task[String] = ordinary
    override def executeWithContext(input: Json, invocation: ToolInvocation): Task[String] =
      run(input, invocation)
    def decodeInput(raw: Json): Either[String, Json] = Right(raw)
    def encodeOutput(out: String): String            = out

  private def model(calls: Chunk[ContentBlock.ToolUse]): UIO[LlmClient] =
    LlmClient.mock(
      Chunk(
        TurnOutput.ToolCalls(Msg.assistant(calls)),
        TurnOutput.FinalText(Msg.assistant("done"))
      )
    )

  def spec: Spec[TestEnvironment & Scope, Any] = suite("ToolInvocationSpec")(
    test(
      "runtime supplies approved persisted occurrences and closes each lease before processing"
    ) {
      val approved: Json = Json.Obj("approved" -> Json.Bool(true))
      for
        agentRef    <- Ref.make(Option.empty[ReActAgent])
        invocations <- Ref.make(Chunk.empty[ToolInvocation])
        persisted   <- Ref.make(Chunk.empty[ContentBlock.ToolUse])
        processed   <- Ref.make(Chunk.empty[ToolEventDelivery])
        published   <- Ref.make(Chunk.empty[ToolEventDelivery])
        received    <- Ref.make(Chunk.empty[Json])
        tool = contextual(
          ZIO.succeed("ordinary"),
          (input, invocation) =>
            received.update(_ :+ input) *> invocations.update(_ :+ invocation) *>
              (invocation match
                case ToolInvocation.Detached => ZIO.succeed("detached")
                case ToolInvocation.Active(_, approvedCall, callOrdinal, port) =>
                  agentRef.get
                    .flatMap(ZIO.foreach(_)(_.history))
                    .flatMap(history =>
                      persisted.update(
                        _ ++ Chunk
                          .fromIterable(history)
                          .flatten
                          .flatMap(_.toolUses)
                          .slice(callOrdinal, callOrdinal + 1)
                      )
                    ) *> port
                    .publish(marker)
                    .flatMap(result => published.update(_ :+ result))
                    .as(approvedCall.name))
        )
        client <- model(Chunk(call, call))
        agent <- ReActAgent.make(
          config = AgentConfig(toolExecution = ToolExecution.Sequential),
          client = client,
          toolkit = Toolkit.single(tool),
          toolAuthorizer = ToolAuthorizer(_ => ZIO.succeed(ToolAuthorization.Approved(approved))),
          toolResultProcessor = ToolResultProcessor(result =>
            invocations.get
              .flatMap(values =>
                ZIO.foreach(values.lastOption) {
                  case ToolInvocation.Active(_, _, _, port) =>
                    port.publish(marker).flatMap(value => processed.update(_ :+ value))
                  case ToolInvocation.Detached => ZIO.unit
                }
              )
              .as(ToolResultPayload.fromResult(result))
          )
        )
        _          <- agentRef.set(Some(agent))
        events     <- agent.streamEvents(Msg.user("inspect")).runCollect
        active     <- invocations.get.map(_.collect { case value: ToolInvocation.Active => value })
        history    <- persisted.get
        inputs     <- received.get
        deliveries <- published.get
        after      <- processed.get
        late       <- ZIO.foreach(active)(_.events.publish(marker))
        starts = events.collect { case event: AgentEvent.ToolResultStartEvent => event }
      yield assertTrue(
        active.map(_.callOrdinal) == Chunk(0, 1),
        active.map(_.call.id) == Chunk("repeated", "repeated"),
        active.map(_.call) == history,
        active.forall(value => starts.exists(_.context.contains(value.context))),
        active.forall(value => value.context.replyId.nonEmpty && value.context.requestId.nonEmpty),
        inputs == Chunk(approved, approved),
        history.forall(_.input == approved),
        deliveries == Chunk.fill(2)(ToolEventDelivery.Published),
        after == Chunk.fill(2)(ToolEventDelivery.Closed),
        late == Chunk.fill(2)(ToolEventDelivery.Closed),
        events.count(_ == marker) == 2
      )
    },
    test(
      "legacy execution delegates, standalone calls are detached, and denials invoke neither handler"
    ) {
      for
        ordinary <- Ref.make(0)
        contexts <- Ref.make(Chunk.empty[ToolInvocation])
        custom = contextual(
          ordinary.update(_ + 1).as("ordinary"),
          (_, invocation) => contexts.update(_ :+ invocation).as("contextual")
        )
        tools = Toolkit.single(custom)
        detached <- tools.executeSafe("inspect", Json.Obj())
        client   <- model(Chunk(call))
        denied <- ReActAgent
          .make(
            client = client,
            toolkit = tools,
            toolAuthorizer = ToolAuthorizer(_ => ZIO.succeed(ToolAuthorization.Denied("denied")))
          )
          .flatMap(_.run(Msg.user("inspect")))
        legacyClient <- model(Chunk(call))
        legacy <- ReActAgent
          .make(
            client = legacyClient,
            toolkit =
              Toolkit.single(Tool.make(custom.definition)(_ => ordinary.update(_ + 1).as("legacy")))
          )
          .flatMap(_.run(Msg.user("inspect")))
        count    <- ordinary.get
        observed <- contexts.get
      yield assertTrue(
        detached.output == "contextual",
        observed == Chunk(ToolInvocation.Detached),
        count == 1,
        denied.history.flatMap(_.toolResults).map(_.state) == Chunk(ToolResultState.Denied),
        legacy.history.flatMap(_.toolResults).map(_.text) == Chunk("legacy")
      )
    },
    test(
      "lease closure orders concurrent publication and joins cancelled tool cleanup without erasing defects"
    ) {
      val defect = new IllegalStateException("tool cleanup")
      for
        hub          <- ZIO.acquireRelease(Hub.unbounded[AgentEvent])(_.shutdown)
        subscription <- hub.subscribe
        owner        <- Scope.make
        port         <- owner.extend(ToolEventPort.open(hub))
        first        <- port.publish(marker)
        raced <- ZIO
          .foreachPar(1 to 16)(_ => port.publish(marker))
          .zipPar(owner.close(Exit.unit))
        late     <- port.publish(marker)
        queued   <- subscription.takeAll
        entered  <- Promise.make[Nothing, ToolEventPort]
        cleaning <- Promise.make[Nothing, Unit]
        release  <- Promise.make[Nothing, Unit]
        tool = contextual(
          ZIO.succeed("unused"),
          (_, invocation) =>
            invocation match
              case ToolInvocation.Detached => ZIO.succeed("unexpected detached execution")
              case ToolInvocation.Active(_, _, _, leased) =>
                (entered.succeed(leased) *> ZIO.never).ensuring(
                  cleaning.succeed(()) *> release.await *> ZIO.die(defect)
                )
        )
        client <- model(Chunk(call))
        agent  <- ReActAgent.make(client = client, toolkit = Toolkit.single(tool))
        cancellation <- ZIO.scoped {
          (for
            cancelled    <- agent.run(Msg.user("inspect")).forkScoped
            leased       <- entered.await
            interrupting <- cancelled.interrupt.forkScoped
            _            <- cleaning.await
            pending      <- interrupting.poll
            _            <- release.succeed(())
            exit         <- interrupting.join
            closed       <- leased.publish(marker)
          yield (pending, exit, closed)).ensuring(release.succeed(()).unit)
        }
        (pending, exit, closed) = cancellation
      yield assertTrue(
        first == ToolEventDelivery.Published,
        late == ToolEventDelivery.Closed,
        queued.size == 1 + raced.count(_ == ToolEventDelivery.Published),
        pending.isEmpty,
        exit.isInterrupted,
        exit.causeOption.exists(_.defects.contains(defect)),
        closed == ToolEventDelivery.Closed
      )
    }
  ) @@ TestAspect.timeout(10.seconds)
