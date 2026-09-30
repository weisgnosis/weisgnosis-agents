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
import com.weisgnosis.agents.core.model.{ChatUsage, ContentBlock, Msg}
import com.weisgnosis.agents.core.tool.*
import zio.*
import zio.json.ast.Json
import zio.test.*

object ReActAgentForwardingSpec extends ZIOSpecDefault:
  def spec: Spec[TestEnvironment & Scope, Any] = suite("ReActAgentForwardingSpec")(
    test(
      "direct forwarding drains ordered nested events and retains the complete single-run result"
    ) {
      val usage = ChatUsage(inputTokens = 2, outputTokens = 3)
      val reply = Msg.assistant("done").withUsage(usage)
      for
        hub          <- ZIO.acquireRelease(Hub.unbounded[AgentEvent])(_.shutdown)
        subscription <- hub.subscribe
        destination  <- ToolEventPort.open(hub)
        requests     <- Ref.make(0)
        observed     <- Ref.make(Chunk.empty[TurnCompletion])
        tool = new Tool[Any, Json, String]:
          def definition: ToolDef = ToolDef("progress", "Publish progress", Json.Obj())
          def execute(input: Json): Task[String] = ZIO.succeed("unexpected detached call")
          override def executeWithContext(input: Json, invocation: ToolInvocation): Task[String] =
            invocation match
              case ToolInvocation.Detached => execute(input)
              case ToolInvocation.Active(context, _, _, events) =>
                ZIO
                  .foreachDiscard(0 until 64)(index =>
                    events.publish(
                      AgentEvent.TextBlockDeltaEvent(
                        index.toString,
                        0,
                        source = Some("nested"),
                        context = Some(context)
                      )
                    )
                  )
                  .as("progress complete")
          def decodeInput(raw: Json): Either[String, Json] = Right(raw)
          def encodeOutput(out: String): String            = out
        agent <- ReActAgent.make(
          client = LlmClient.mockEffectful((_, _, _) =>
            requests
              .updateAndGet(_ + 1)
              .map(count =>
                if count == 1 then
                  TurnOutput.ToolCalls(
                    Msg.assistant(
                      Chunk(
                        ContentBlock.ToolUse("progress-call", "progress", Json.Obj())
                      )
                    )
                  )
                else TurnOutput.FinalText(reply)
              )
          ),
          toolkit = Toolkit(Map("progress" -> tool)),
          completionObserver = TurnCompletionObserver(value => observed.update(_ :+ value))
        )
        result      <- agent.runForwarding(Msg.user("work"), destination, "child")
        events      <- subscription.takeAll
        count       <- requests.get
        completions <- observed.get
        deltas = events.collect { case event: AgentEvent.TextBlockDeltaEvent => event }
        starts = events.collect { case event: AgentEvent.ToolResultStartEvent => event }
        ends   = events.collect { case event: AgentEvent.AgentEndEvent => event }
        history <- agent.history
      yield assertTrue(
        count == 2,
        result.reply == reply,
        result.history == history,
        result.history.flatMap(_.toolResults).map(_.text) == Chunk("progress complete"),
        result.usage.contains(usage),
        result.iterationCount == 2,
        result.state == AgentRunState.Completed,
        completions.map(_.outcome) == Chunk(Exit.succeed(result)),
        deltas.map(_.delta) == Chunk.fromIterable((0 until 64).map(_.toString)),
        deltas.forall(_.source.contains("child/nested")),
        deltas.forall(delta => starts.exists(_.context == delta.context)),
        events
          .filterNot(_.isInstanceOf[AgentEvent.TextBlockDeltaEvent])
          .forall(_.source.contains("child")),
        events.lastOption == ends.lastOption,
        ends.size == 1,
        starts.forall(start => ends.exists(end => start.context.exists(_.replyId == end.replyId)))
      )
    },
    test(
      "forwarding preserves mixed observation causes and joins cancellation cleanup before returning"
    ) {
      val observationError = AgentError.CompletionObservationError("observation failed")
      val observerDefect   = new IllegalStateException("observation cleanup")
      val cleanupDefect    = new IllegalStateException("model cleanup")
      val observationCause = Cause.fail(observationError) ++ Cause.die(observerDefect)
      for
        hub          <- ZIO.acquireRelease(Hub.unbounded[AgentEvent])(_.shutdown)
        subscription <- hub.subscribe
        destination  <- ToolEventPort.open(hub)
        observed     <- Ref.make(Chunk.empty[TurnCompletion])
        observer = TurnCompletionObserver(value =>
          observed.update(_ :+ value) *> ZIO.refailCause(observationCause)
        )
        completed <- ReActAgent.make(
          client = LlmClient.mockFunction((_, _) => TurnOutput.FinalText(Msg.assistant("done"))),
          completionObserver = observer
        )
        failed   <- completed.runForwarding(Chunk(Msg.user("work")), destination, "completed").exit
        first    <- observed.get
        _        <- observed.set(Chunk.empty)
        _        <- subscription.takeAll
        entered  <- Promise.make[Nothing, Unit]
        cleaning <- Promise.make[Nothing, Unit]
        release  <- Promise.make[Nothing, Unit]
        cancelled <- ReActAgent.make(
          client = LlmClient.mockEffectful((_, _, _) =>
            ZIO.acquireRelease(entered.succeed(()))(_ =>
              cleaning.succeed(()) *> release.await *> ZIO.die(cleanupDefect)
            ) *> ZIO.never
          ),
          completionObserver = observer
        )
        cancellation <- ZIO.scoped {
          (for
            running <- cancelled
              .runForwarding(Msg.user("work"), destination, "cancelled")
              .forkScoped
            _           <- entered.await
            stopping    <- running.interrupt.forkScoped
            _           <- cleaning.await
            pending     <- stopping.poll
            _           <- release.succeed(())
            exit        <- stopping.join
            completions <- observed.get
            events      <- subscription.takeAll
          yield assertTrue(
            pending.isEmpty,
            exit.isInterrupted,
            exit.causeOption.exists(_.defects.contains(cleanupDefect)),
            completions.size == 1,
            completions.headOption.exists(completion =>
              completion.outcome.causeOption.exists(original =>
                exit.causeOption.contains(original ++ observationCause)
              )
            ),
            events.lastOption.exists {
              case event: AgentEvent.AgentEndEvent => event.source.contains("cancelled")
              case _                               => false
            }
          )).ensuring(release.succeed(()).unit)
        }
      yield assertTrue(
        failed.causeOption.contains(observationCause),
        first.size == 1
      ) && cancellation
    },
    test("closed destinations discard events without cancelling execution or losing its result") {
      for
        hub          <- ZIO.acquireRelease(Hub.unbounded[AgentEvent])(_.shutdown)
        subscription <- hub.subscribe
        owner        <- Scope.make
        destination  <- owner.extend(ToolEventPort.open(hub))
        _            <- owner.close(Exit.unit)
        calls        <- Ref.make(0)
        observations <- Ref.make(0)
        agent <- ReActAgent.make(
          client = LlmClient.mockEffectful((_, _, _) =>
            calls.update(_ + 1).as(TurnOutput.FinalText(Msg.assistant("finished")))
          ),
          completionObserver = TurnCompletionObserver(_ => observations.update(_ + 1))
        )
        result    <- agent.runForwarding(Msg.user("work"), destination, "closed")
        events    <- subscription.takeAll
        count     <- calls.get
        completed <- observations.get
      yield assertTrue(
        result.reply.text == "finished",
        result.state == AgentRunState.Completed,
        events.isEmpty,
        count == 1,
        completed == 1
      )
    }
  ) @@ TestAspect.timeout(15.seconds)
