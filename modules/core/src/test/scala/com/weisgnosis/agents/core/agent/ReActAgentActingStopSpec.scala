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

import com.weisgnosis.agents.core.client.{
  LlmClient,
  LlmConfig,
  NativeToolSupport,
  StructuredOutputCapability,
  TurnOutput
}
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.event.AgentEvent
import com.weisgnosis.agents.core.model.{ContentBlock, GenerateReason, Msg, Role, ToolResultState}
import com.weisgnosis.agents.core.tool.*
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

object ReActAgentActingStopSpec extends ZIOSpecDefault:
  final private case class Answer(value: Int) derives JsonCodec

  private def call(name: String): ContentBlock.ToolUse =
    ContentBlock.ToolUse("repeated", name, Json.Obj())

  private def tool(name: String, run: UIO[String]): Tool[Any, Json, String] =
    Tool.make(ToolDef(name, "Read an observation", Json.Obj()))(_ => run)

  def spec: Spec[TestEnvironment & Scope, Any] = suite("ReActAgent acting stop")(
    test(
      "drains mixed inline and parallel local calls, selecting the earliest original occurrence"
    ):
      ZIO.scoped:
        for
          fastProcessed    <- Promise.make[Nothing, Unit]
          processed        <- Ref.make(Chunk.empty[String])
          modelCalls       <- Ref.make(0)
          remoteExecutions <- Ref.make(0)
          remote = call("remote").withServerTool(true)
          inline = ContentBlock.ToolResult
            .text("repeated", "remote", "remote")
            .copy(metadata = Map("serverTool" -> Json.Bool(true)))
          message = Msg.assistant(Chunk(call("slow"), remote, inline, call("fast")))
          client = LlmClient.mockEffectful((_, _, _) =>
            modelCalls
              .getAndUpdate(_ + 1)
              .map(index =>
                if index == 0 then TurnOutput.ToolCalls(message)
                else TurnOutput.FinalText(Msg.assistant("next work"))
              )
          )
          processor = ToolResultProcessor.deciding(result =>
            val name    = result.name.getOrElse("missing")
            val payload = ToolResultPayload(Chunk(ContentBlock.Text(s"$name-processed")))
            processed.update(_ :+ name) *>
              (if name == "fast" then fastProcessed.succeed(()).unit else ZIO.unit)
                .as(ToolResultDecision.Stop(payload, name))
          )
          agent <- ReActAgent.make(
            client = client,
            toolkit = Toolkit
              .single(tool("slow", fastProcessed.await.as("slow")))
              .updated(tool("fast", ZIO.succeed("fast")))
              .updated(tool("remote", remoteExecutions.update(_ + 1).as("unexpected"))),
            toolResultProcessor = processor
          )
          updates     <- agent.streamWithResult(Msg.user("run tools")).runCollect
          firstCalls  <- modelCalls.get
          observed    <- processed.get
          remoteCount <- remoteExecutions.get
          next        <- agent.run(Msg.user("new work"))
          events    = updates.collect { case AgentRunUpdate.Event(value) => value }
          results   = updates.collect { case AgentRunUpdate.Result(value) => value }
          stops     = events.collect { case value: AgentEvent.RequestStopEvent => value }
          stopIndex = events.indexWhere(_.isInstanceOf[AgentEvent.RequestStopEvent])
          ends = events.zipWithIndex.collect { case (_: AgentEvent.ToolResultEndEvent, index) =>
            index
          }
        yield assertTrue(
          firstCalls == 1,
          observed == Chunk("remote", "fast", "slow"),
          remoteCount == 0,
          results.size == 1,
          results.forall(result =>
            result.state == AgentRunState.Stopped("slow") &&
              result.reply.generateReason.contains("acting_stop_requested") &&
              result.history.find(_.toolUses.nonEmpty).contains(result.reply) &&
              result.history.flatMap(_.toolResults).map(_.text).toSet ==
              Set("slow-processed", "remote-processed", "fast-processed") &&
              result.history.count(_.role == Role.Tool) == 2 &&
              result.history.count(_.generateReason.contains("interrupted")) == 0
          ),
          stops.map(_.reason) == Chunk("slow"),
          stops.forall(_.generateReason == GenerateReason.ActingStopRequested),
          ends.size == 3,
          ends.forall(_ < stopIndex),
          events.indexWhere(_.isInstanceOf[AgentEvent.AgentResultEvent]) > stopIndex,
          events.lastOption.exists(_.isInstanceOf[AgentEvent.AgentEndEvent]),
          next.state == AgentRunState.Completed,
          next.reply.text == "next work"
        )
    ,
    test(
      "cancellation joins held processor cleanup and retains its defect instead of accepting Stop"
    ):
      ZIO.scoped:
        for
          cleanupEntered      <- Promise.make[Nothing, Unit]
          releaseCleanup      <- Promise.make[Nothing, Unit]
          cancellationReached <- Promise.make[Nothing, Unit]
          hub                 <- ZIO.acquireRelease(Hub.unbounded[AgentEvent])(_.shutdown)
          subscription        <- hub.subscribe
          destination         <- ToolEventPort.open(hub)
          awaitCancellation = ZIO.descriptor
            .zip(releaseCleanup.isDone)
            .flatMap:
              case (descriptor, released) =>
                if descriptor.interrupters.nonEmpty then cancellationReached.succeed(()).as(true)
                else ZIO.yieldNow.as(released)
            .repeatUntil(identity)
            .unit
          defect = new IllegalStateException("processor cleanup failed")
          processor = ToolResultProcessor.deciding(result =>
            ZIO
              .acquireRelease(ZIO.unit)(_ =>
                cleanupEntered.succeed(()) *> awaitCancellation *> releaseCleanup.await *> ZIO.die(
                  defect
                )
              )
              .as(ToolResultDecision.Stop(ToolResultPayload.fromResult(result), "provisional"))
          )
          agent <- ReActAgent.make(
            client = LlmClient.mockFunction((_, _) =>
              TurnOutput.ToolCalls(Msg.assistant(Chunk(call("lookup"))))
            ),
            toolkit = Toolkit.single(tool("lookup", ZIO.succeed("persisted"))),
            toolResultProcessor = processor
          )
          result <- (for
            fiber <- agent.runForwarding(Msg.user("run"), destination, "acting-stop").forkScoped
            _     <- cleanupEntered.await
            interrupter <- ZIO.fiberId
            _           <- fiber.interruptAsFork(interrupter)
            _           <- cancellationReached.await
            pending     <- fiber.poll
            before      <- subscription.takeAll
            _           <- releaseCleanup.succeed(())
            exit        <- fiber.await
            after       <- subscription.takeAll
            history     <- agent.history
            events = before ++ after
          yield assertTrue(
            pending.isEmpty,
            !before.exists(_.isInstanceOf[AgentEvent.RequestStopEvent]),
            exit.causeOption.exists(cause => cause.isInterrupted && cause.defects.contains(defect)),
            !events.exists(_.isInstanceOf[AgentEvent.RequestStopEvent]),
            !events.exists(_.isInstanceOf[AgentEvent.AgentResultEvent]),
            history
              .flatMap(_.toolResults)
              .exists(result =>
                result.text == "persisted" && result.state == ToolResultState.Success
              ),
            history.count(_.generateReason.contains("interrupted")) == 1
          )).ensuring(releaseCleanup.succeed(()).unit)
        yield result
    ,
    test(
      "structured Stop overrides captured output and never becomes success or a provider fallback"
    ):
      ZIO
        .foreach(
          Chunk(
            StructuredOutputCapability.Unsupported,
            StructuredOutputCapability.Native(NativeToolSupport.Supported)
          )
        )(capability =>
          ZIO.scoped:
            val target = capability match
              case StructuredOutputCapability.Unsupported => "generate_response"
              case _                                      => "lookup"
            val answer = Json.Obj("value" -> Json.Num(7))
            val input =
              if target == "generate_response" then Json.Obj("response" -> answer) else answer
            val request = ContentBlock.ToolUse("structured", target, input)
            for
              calls    <- Ref.make(0)
              observed <- Ref.make(Chunk.empty[AgentEvent])
              agent <- ReActAgent.make(
                client = LlmClient.mockEffectful((_, _, _) =>
                  calls.update(_ + 1).as(TurnOutput.ToolCalls(Msg.assistant(Chunk(request))))
                ),
                llmConfig = LlmConfig("test", structuredOutputCapability = capability),
                toolkit = Toolkit.single(tool("lookup", ZIO.succeed("observation"))),
                toolResultProcessor = ToolResultProcessor.deciding(result =>
                  ZIO.succeed(
                    ToolResultDecision.Stop(ToolResultPayload.fromResult(result), "host stop")
                  )
                )
              )
              exit <- agent
                .streamStructuredEvents[Answer](
                  Chunk(Msg.user("structured")),
                  Json.Obj("type" -> Json.Str("object")),
                  StructuredMode.Auto
                )
                .tap(event => observed.update(_ :+ event))
                .runCollect
                .exit
              count   <- calls.get
              events  <- observed.get
              history <- agent.history
            yield assertTrue(
              exit.causeOption.contains(Cause.fail(AgentError.ExecutionStopped("host stop"))),
              count == 1,
              events.count(_.isInstanceOf[AgentEvent.RequestStopEvent]) == 1,
              !events.exists(_.isInstanceOf[AgentEvent.AgentResultEvent]),
              history
                .flatMap(_.toolResults)
                .exists(result =>
                  result.name.contains(target) && result.state == ToolResultState.Success
                ),
              !history.exists(_.metadata.contains("_structured_output")),
              history.exists(_.generateReason.contains("acting_stop_requested"))
            )
        )
        .map(_.foldLeft(assertTrue(true))(_ && _))
  ) @@ TestAspect.timeout(10.seconds)
