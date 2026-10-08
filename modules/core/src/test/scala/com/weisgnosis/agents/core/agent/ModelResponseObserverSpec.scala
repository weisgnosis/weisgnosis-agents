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

import com.weisgnosis.agents.core.client.*
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.event.{AgentEvent, ModelStreamContext}
import com.weisgnosis.agents.core.model.*
import com.weisgnosis.agents.core.skill.{SkillCatalog, SkillDefinition}
import com.weisgnosis.agents.core.tool.*
import zio.*
import zio.http.*
import zio.json.ast.Json
import zio.test.*

object ModelResponseObserverSpec extends ZIOSpecDefault:
  private enum FailureMode:
    case Typed, CleanupDefect, Suspend, StructuredAuto

  private enum Cancellation:
    case Fiber, Cooperative, FiberCleanupDefect, CooperativeCleanupDefect

  private val call: ContentBlock.ToolUse = ContentBlock.ToolUse("repeated", "counter", Json.Obj())
  private val server: ContentBlock.ToolUse =
    ContentBlock.ToolUse("server", "remote", Json.Obj()).withServerTool(true)
  private val inline: ContentBlock.ToolResult = ContentBlock.ToolResult
    .text("server", "remote", "inline")
    .copy(metadata = Map("serverTool" -> Json.Bool(true)))
  private val assistant: Msg = Msg.assistant(Chunk(call, call, server, inline))

  private def counter(count: Ref[Int]): Tool[Any, Json, String] =
    Tool.make(ToolDef("counter", "Count execution", Json.Obj()))(_ =>
      count.update(_ + 1).as("done")
    )

  private def rejectedClient: Client =
    ZClient.fromDriver(new ZClient.Driver[Any, Scope, Throwable]:
      def request(
          version: Version,
          method: Method,
          url: URL,
          headers: Headers,
          body: Body,
          sslConfig: Option[ClientSSLConfig],
          proxy: Option[Proxy]
      )(using Trace): ZIO[Scope, Throwable, Response] =
        ZIO.succeed(Response(status = Status.ServiceUnavailable, body = Body.fromString("busy")))
      def socket[Env1 <: Any](
          version: Version,
          url: URL,
          headers: Headers,
          app: WebSocketApp[Env1]
      )(using Trace, Scope =:= Scope): ZIO[Env1 & Scope, Throwable, Response] =
        ZIO.fail(AgentError.InvalidConfiguration("WebSockets are outside this fixture")))

  def spec: Spec[TestEnvironment & Scope, Any] = suite("ModelResponseObserverSpec")(
    test(
      "accepted observations retain the winning projected request and persisted replacement; discarded and synthetic responses are excluded"
    ):
      ZIO.scoped:
        for
          selected <- Ref.make(
            Chunk.empty[(Chunk[Msg], Chunk[ToolDef], LlmConfig, ModelStreamContext)]
          )
          observed <- Ref.make(Chunk.empty[AcceptedModelResponse])
          closed   <- Ref.make(false)
          primary  <- ChatCompletionsClient.make(rejectedClient)
          target = new LlmClient:
            def streamTurn(
                history: Chunk[Msg],
                tools: Chunk[ToolDef],
                config: LlmConfig,
                eventHub: Hub[AgentEvent],
                context: ModelStreamContext
            ): ZIO[Scope, AgentError, TurnOutput] =
              selected
                .update(_ :+ (history, tools, config, context))
                .as(TurnOutput.FinalText(Msg.assistant("raw")))
          skills <- ZIO.fromEither(
            SkillCatalog.make(Chunk(SkillDefinition("guide", "Guide", "Trusted guidance")))
          )
          first = ModelResponseObserver(response =>
            ZIO.acquireRelease(observed.update(_ :+ response))(_ => closed.set(true)).unit
          )
          next = ModelResponseObserver(_ =>
            closed.get
              .flatMap(value =>
                ZIO
                  .fail(
                    AgentError.CompletionObservationError("Previous observer cleanup is incomplete")
                  )
                  .unless(value)
              )
              .unit
          )
          agent <- ReActAgent
            .builder()
            .client(primary)
            .llmConfig(LlmConfig("primary", endpoint = "http://localhost/rejected"))
            .fallback(ModelTarget(target, LlmConfig("selected", temperature = 0.2)))
            .skills(skills)
            .modelInputTransform(
              ModelInputTransform.withRequest(ModelInputTransform.identity)((_, request) =>
                ZIO.succeed(
                  ModelInputProjection(
                    Chunk(Msg.user("projected")) ++ request.history,
                    request.settings.copy(maxTokens = Some(77))
                  )
                )
              )
            )
            .postReasoningHook(
              PostReasoningHook((_, _) =>
                ZIO.succeed(ReasoningDecision.Replace(Msg.assistant("accepted")))
              )
            )
            .modelResponseObserver(first.andThen(next))
            .build
          result    <- agent.run(Msg.user("question"))
          inputs    <- selected.get
          responses <- observed.get
          scripted <- LlmClient.mock(
            Chunk(
              TurnOutput.FinalText(Msg.assistant("discard")),
              TurnOutput.ThinkingOnly(Msg.assistant(Chunk(ContentBlock.Thinking("thought")))),
              TurnOutput.FinalText(Msg.assistant("replace"))
            )
          )
          retryObserved <- Ref.make(Chunk.empty[AcceptedModelResponse])
          retry <- ReActAgent.make(
            client = scripted,
            postReasoningHook = PostReasoningHook((_, message) =>
              ZIO.succeed(
                if message.text == "discard" then ReasoningDecision.Discard
                else if message.text == "replace" then
                  ReasoningDecision.Replace(Msg.assistant("final"))
                else ReasoningDecision.Keep
              )
            ),
            modelResponseObserver =
              ModelResponseObserver(response => retryObserved.update(_ :+ response))
          )
          retried       <- retry.run(Msg.user("retry"))
          retries       <- retryObserved.get
          resumeSeen    <- Ref.make(Chunk.empty[AcceptedModelResponse])
          resumeCounter <- Ref.make(0)
          resumeToolkit <- ZIO.fromEither(Toolkit.empty.register(counter(resumeCounter)))
          resumeClient <- LlmClient.mock(
            Chunk(
              TurnOutput.ToolCalls(Msg.assistant(Chunk(call))),
              TurnOutput.FinalText(Msg.assistant("resumed"))
            )
          )
          resumable <- ReActAgent.make(
            client = resumeClient,
            toolkit = resumeToolkit,
            postReasoningHook = PostReasoningHook((_, message) =>
              ZIO.succeed(
                if message.toolUses.nonEmpty then ReasoningDecision.Suspend("review")
                else ReasoningDecision.Keep
              )
            ),
            modelResponseObserver =
              ModelResponseObserver(response => resumeSeen.update(_ :+ response))
          )
          suspended         <- resumable.run(Msg.user("resume"))
          beforeResume      <- resumeSeen.get
          resumed           <- resumable.resume()
          afterResume       <- resumeSeen.get
          resumedExecutions <- resumeCounter.get
        yield assertTrue(
          result.reply.text == "accepted",
          inputs.size == 1,
          responses.size == 1,
          inputs.headOption
            .zip(responses.headOption)
            .exists: (input, response) =>
              response.context == input._4 && response.context.invocation.nonEmpty &&
                response.request.history == input._1 && response.request.tools == input._2 &&
                response.request.model == input._3.model && response.request.model == "selected" &&
                response.request.settings.maxTokens == Some(
                  77
                ) && response.request.settings.temperature == 0.2 &&
                response.request.history.exists(_.text.contains("Available skills")) &&
                response.history == result.history && response.history.lastOption
                  .contains(response.message) &&
                response.message.text == "accepted" && !response.history.exists(
                  _.text == "projected"
                ),
          suspended.isSuspended,
          resumed.reply.text == "resumed",
          beforeResume.size == 1,
          afterResume.map(_.message.toolUses.size) == Chunk(1, 0),
          afterResume.map(_.context.invocation).distinct.size == 2,
          resumedExecutions == 1,
          retried.reply.text == "final",
          retries.map(_.message.text) == Chunk("", "final"),
          retries.headOption.exists(response =>
            response.history.lastOption.contains(response.message) && !response.history.exists(
              _.metadata.contains(Msg.MetadataReminderKind)
            )
          ),
          retries.lastOption.exists(
            _.history.exists(
              _.metadata.get(Msg.MetadataReminderKind).contains(Json.Str("empty_response"))
            )
          )
        )
    ,
    test(
      "observer failure reconciles only local occurrences before authorization and cannot publish a suspended checkpoint or trigger model failover"
    ):
      ZIO
        .foreach(Chunk.fromIterable(FailureMode.values)): mode =>
          ZIO.scoped:
            for
              executed      <- Ref.make(0)
              authorized    <- Ref.make(0)
              fallbackCalls <- Ref.make(0)
              toolkit       <- ZIO.fromEither(Toolkit.empty.register(counter(executed)))
              requests      <- Ref.make(0)
              client = LlmClient.mockEffectful((_, _, _) =>
                requests
                  .update(_ + 1)
                  .as(
                    if mode == FailureMode.StructuredAuto then
                      TurnOutput.FinalText(Msg.assistant("7"))
                    else TurnOutput.ToolCalls(assistant)
                  )
              )
              error  = AgentError.CompletionObservationError("Accepted response observation failed")
              defect = new IllegalStateException("Observer cleanup defect")
              observer = ModelResponseObserver(_ =>
                if mode == FailureMode.CleanupDefect then
                  ZIO.acquireRelease(ZIO.unit)(_ => ZIO.die(defect)) *> ZIO.fail(error)
                else ZIO.fail(error)
              )
              agent <- ReActAgent.make(
                client = client,
                toolkit = toolkit,
                modelResponseObserver =
                  observer.andThen(ModelResponseObserver(_ => authorized.update(_ + 100))),
                toolAuthorizer = ToolAuthorizer(request =>
                  authorized.update(_ + 1).as(ToolAuthorization.Approved(request.call.input))
                ),
                postReasoningHook = PostReasoningHook((_, _) =>
                  ZIO.succeed(
                    if mode == FailureMode.Suspend then ReasoningDecision.Suspend("review")
                    else ReasoningDecision.Keep
                  )
                ),
                fallback = Some(
                  ModelTarget(
                    LlmClient.mockEffectful((_, _, _) =>
                      fallbackCalls
                        .update(_ + 1)
                        .as(TurnOutput.FinalText(Msg.assistant("unexpected")))
                    ),
                    LlmConfig("fallback")
                  )
                )
              )
              failed <- (if mode == FailureMode.StructuredAuto then
                           agent
                             .runStructured[Int](
                               Chunk(Msg.user("work")),
                               Json.Obj("type" -> Json.Str("integer")),
                               StructuredMode.Auto
                             )
                             .unit
                         else agent.run(Msg.user("work")).unit).exit
              requestCount  <- requests.get
              history       <- agent.history
              resumed       <- agent.resume().exit
              count         <- executed.get
              decisions     <- authorized.get
              fallbackCount <- fallbackCalls.get
            yield assertTrue(
              failed.causeOption.exists(cause =>
                cause.failures == List(
                  error
                ) && !cause.isInterrupted && cause.defects == (if mode == FailureMode.CleanupDefect
                                                               then List(defect)
                                                               else Nil)
              ),
              count == 0,
              decisions == 0,
              fallbackCount == 0,
              requestCount == 1,
              history
                .flatMap(_.toolResults)
                .filterNot(_.isServerTool)
                .map(result =>
                  (result.id, result.state)
                ) == (if mode == FailureMode.StructuredAuto then Chunk.empty
                      else
                        Chunk(
                          ("repeated", ToolResultState.Error),
                          ("repeated", ToolResultState.Error)
                        )),
              history.filter(_.role == Role.Assistant).size == 1,
              mode == FailureMode.StructuredAuto || history
                .filter(_.role == Role.Assistant)
                .flatMap(_.toolResults)
                .contains(inline),
              !history.exists(_.generationReason == GenerateReason.Interrupted),
              resumed.causeOption.exists(_.failures.exists:
                case AgentError.InvalidConfiguration("No suspended batch is available") => true
                case _                                                                  => false)
            )
        .map(_.foldLeft(assertTrue(true))(_ && _))
    ,
    test(
      "fiber and cooperative interruption join observer cleanup before reconciling unfinished calls exactly once"
    ):
      ZIO
        .foreach(Chunk.fromIterable(Cancellation.values)): mode =>
          ZIO.scoped:
            for
              count   <- Ref.make(0)
              toolkit <- ZIO.fromEither(Toolkit.empty.register(counter(count)))
              client  <- LlmClient.mock(Chunk(TurnOutput.ToolCalls(assistant)))
              entered <- Promise.make[Nothing, Unit]
              cleanup <- Promise.make[Nothing, Unit]
              release <- Promise.make[Nothing, Unit]
              defect = new IllegalStateException("Interrupted observer cleanup defect")
              agent <- ReActAgent.make(
                client = client,
                toolkit = toolkit,
                modelResponseObserver = ModelResponseObserver(_ =>
                  ZIO.acquireRelease(ZIO.unit)(_ =>
                    cleanup.succeed(()) *> release.await *> (mode match
                      case Cancellation.FiberCleanupDefect |
                          Cancellation.CooperativeCleanupDefect =>
                        ZIO.die(defect)
                      case Cancellation.Fiber | Cancellation.Cooperative => ZIO.unit)
                  ) *> entered.succeed(()) *> ZIO.never
                )
              )
              worker <- ZIO.uninterruptibleMask: restore =>
                for
                  fiber <- restore(agent.run(Msg.user("work"))).forkScoped
                  _     <- ZIO.addFinalizer(release.succeed(()).unit)
                yield fiber
              _ <- entered.await
              cancelling <- ZIO.uninterruptibleMask: restore =>
                for
                  fiber <- restore(mode match
                    case Cancellation.Fiber | Cancellation.FiberCleanupDefect => worker.interrupt
                    case Cancellation.Cooperative | Cancellation.CooperativeCleanupDefect =>
                      agent.interrupt *> worker.await).forkScoped
                  _ <- ZIO.addFinalizer(release.succeed(()).unit)
                yield fiber
              _           <- cleanup.await
              before      <- cancelling.poll
              beforeCount <- count.get
              _           <- release.succeed(())
              exit        <- cancelling.join
              history     <- agent.history
              calls       <- count.get
            yield assertTrue(
              before.isEmpty,
              beforeCount == 0,
              calls == 0,
              mode match
                case Cancellation.Fiber =>
                  exit.isInterrupted && exit.causeOption
                    .exists(cause => cause.defects.isEmpty && cause.failures.isEmpty)
                case Cancellation.FiberCleanupDefect | Cancellation.CooperativeCleanupDefect =>
                  exit.isInterrupted && exit.causeOption
                    .exists(cause => cause.defects == List(defect) && cause.failures.isEmpty)
                case Cancellation.Cooperative =>
                  exit match
                    case Exit.Success(result) => result.state == AgentRunState.Interrupted
                    case _                    => false
              ,
              history.flatMap(_.toolResults).filterNot(_.isServerTool).map(_.state) == Chunk(
                ToolResultState.Error,
                ToolResultState.Error
              ),
              history.count(_.generationReason == GenerateReason.Interrupted) == 1,
              history.lastOption
                .exists(_.text == "I noticed that you have interrupted me. What can I do for you?")
            )
        .map(_.foldLeft(assertTrue(true))(_ && _))
  ) @@ TestAspect.timeout(20.seconds)
