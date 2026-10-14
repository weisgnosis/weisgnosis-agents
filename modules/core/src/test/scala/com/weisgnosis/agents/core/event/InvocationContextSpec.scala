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

import com.weisgnosis.agents.core.agent.*
import com.weisgnosis.agents.core.client.*
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.model.{ContentBlock, Msg}
import com.weisgnosis.agents.core.tool.*
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

object InvocationContextSpec extends ZIOSpecDefault:
  private enum Host:
    case Original, Restored

  private enum Preparation:
    case Ready, Failed

  private def automatic: ZIO[Scope, AgentError, TestResult] =
    for
      models      <- Ref.make(Chunk.empty[ModelStreamContext])
      projections <- Ref.make(Chunk.empty[ModelStreamContext])
      prepared    <- Ref.make(Chunk.empty[InvocationContext])
      hooks       <- Ref.make(Chunk.empty[ModelStreamContext])
      authorized  <- Ref.make(Chunk.empty[ModelStreamContext])
      completions <- Ref.make(Chunk.empty[TurnCompletion])
      preparation <- Ref.make(Preparation.Ready)
      preparer = new HistoryPreparer:
        def prepare(
            history: Chunk[Msg]
        ): ZIO[Scope, AgentError.HistoryPreparationError, Chunk[Msg]] = ZIO.succeed(history)
        override def prepare(
            context: InvocationContext,
            history: Chunk[Msg]
        ): ZIO[Scope, AgentError.HistoryPreparationError, Chunk[Msg]] =
          prepared.update(_ :+ context) *> preparation.get.flatMap:
            case Preparation.Ready => ZIO.succeed(history)
            case Preparation.Failed =>
              ZIO.fail(AgentError.HistoryPreparationError("Preparation failed before the model"))
      client = new LlmClient:
        def streamTurn(
            history: Chunk[Msg],
            tools: Chunk[ToolDef],
            config: LlmConfig,
            eventHub: EventPublisher,
            context: ModelStreamContext
        ): ZIO[Scope, AgentError, TurnOutput] =
          models.update(_ :+ context) *> (
            if config.responseFormat.nonEmpty then
              ZIO.fail(AgentError.ModelError(400, "Native format unsupported"))
            else if tools.exists(_.name == StructuredOutput.ToolName) then
              ZIO.succeed(
                TurnOutput.ToolCalls(
                  Msg.assistant(
                    Chunk(
                      ContentBlock.ToolUse(
                        "capture",
                        StructuredOutput.ToolName,
                        Json.Obj("response" -> Json.Num(7))
                      )
                    )
                  )
                )
              )
            else ZIO.succeed(TurnOutput.FinalText(Msg.assistant("done")))
          )
      agent <- ReActAgent.make(
        client = client,
        toolkit = Toolkit.empty,
        llmConfig = LlmConfig("test"),
        historyPreparer = preparer,
        modelInputTransform =
          ModelInputTransform((context, history) => projections.update(_ :+ context).as(history)),
        postReasoningHook =
          PostReasoningHook((context, _) => hooks.update(_ :+ context).as(ReasoningDecision.Keep)),
        toolAuthorizer = ToolAuthorizer(request =>
          authorized.update(_ :+ request.context).as(ToolAuthorization.Approved(request.call.input))
        ),
        completionObserver =
          TurnCompletionObserver(completion => completions.update(_ :+ completion))
      )
      structured <- agent
        .runStructured[Int](Msg.user("number"), Json.Obj("type" -> Json.Str("integer")))
      autoModels      <- models.get
      autoProjections <- projections.get
      autoPrepared    <- prepared.get
      autoHooks       <- hooks.get
      autoAuthorized  <- authorized.get
      autoCompleted   <- completions.get
      _               <- agent.run(Msg.user("ordinary"))
      _               <- preparation.set(Preparation.Failed)
      failed          <- agent.run(Msg.user("failed")).exit
      allModels       <- models.get
      allCompleted    <- completions.get
      first      = autoCompleted.headOption.flatMap(_.invocation)
      identities = allCompleted.flatMap(_.invocation)
    yield assertTrue(
      structured._2 == 7,
      first.nonEmpty,
      autoModels.size == 2,
      autoModels.map(_.replyId).distinct.size == 2,
      autoModels.map(_.requestId).distinct.size == 2,
      autoModels.forall(_.invocation == first),
      autoProjections == autoModels,
      autoPrepared.size == 2,
      autoPrepared.forall(value => first.contains(value)),
      autoHooks.size == 1,
      autoHooks.forall(_.invocation == first),
      autoAuthorized.size == 1,
      autoAuthorized.forall(_.invocation == first),
      autoCompleted.size == 1,
      identities.size == 3,
      identities.distinct.size == 3,
      identities.forall(value => value.toJson.fromJson[InvocationContext] == Right(value)),
      allModels.size == 3,
      failed.causeOption.exists(
        _.failures.exists(_.isInstanceOf[AgentError.HistoryPreparationError])
      ),
      allCompleted.lastOption.exists(_.outcome.isFailure)
    )

  private def resumed(host: Host): ZIO[Scope, AgentError, TestResult] =
    for
      modelCount  <- Ref.make(0)
      models      <- Ref.make(Chunk.empty[ModelStreamContext])
      authorized  <- Ref.make(Chunk.empty[ModelStreamContext])
      completions <- Ref.make(Chunk.empty[TurnCompletion])
      tool = Tool.make(ToolDef("read", "Read a value", Json.Obj()))(_ => ZIO.succeed("observed"))
      client = new LlmClient:
        def streamTurn(
            history: Chunk[Msg],
            tools: Chunk[ToolDef],
            config: LlmConfig,
            eventHub: EventPublisher,
            context: ModelStreamContext
        ): ZIO[Scope, AgentError, TurnOutput] =
          models.update(_ :+ context) *> modelCount
            .getAndUpdate(_ + 1)
            .map:
              case 0 =>
                TurnOutput.ToolCalls(
                  Msg.assistant(Chunk(ContentBlock.ToolUse("call", "read", Json.Obj())))
                )
              case _ => TurnOutput.FinalText(Msg.assistant("done"))
      make = ReActAgent.make(
        client = client,
        toolkit = Toolkit.single(tool),
        llmConfig = LlmConfig("test"),
        postReasoningHook = PostReasoningHook((_, message) =>
          ZIO.succeed(
            if message.toolUses.nonEmpty then ReasoningDecision.Suspend("review")
            else ReasoningDecision.Keep
          )
        ),
        toolAuthorizer = ToolAuthorizer(request =>
          authorized.update(_ :+ request.context).as(ToolAuthorization.Approved(request.call.input))
        ),
        completionObserver =
          TurnCompletionObserver(completion => completions.update(_ :+ completion))
      )
      original  <- make
      suspended <- original.run(Msg.user("inspect"))
      decoded <- ZIO.fromEither(
        suspended.toJson.fromJson[AgentResult].left.map(AgentError.DeserializationError(_))
      )
      target <- host match
        case Host.Original => ZIO.succeed(original)
        case Host.Restored => make.tap(_.restore(decoded))
      completed <- target.resume()
      contexts  <- models.get
      acting    <- authorized.get
      observed  <- completions.get
      initial = contexts.headOption
      resumed = contexts.lastOption
      first   = observed.headOption.flatMap(_.invocation)
      second  = observed.lastOption.flatMap(_.invocation)
    yield assertTrue(
      suspended.isSuspended,
      decoded == suspended,
      completed.state == AgentRunState.Completed,
      contexts.size == 2,
      acting.size == 1,
      observed.size == 2,
      first.nonEmpty,
      second.nonEmpty,
      first != second,
      initial.map(_.replyId) == resumed.map(_.replyId),
      initial.map(_.requestId) != resumed.map(_.requestId),
      initial.flatMap(_.invocation) == first,
      resumed.flatMap(_.invocation) == second,
      acting.forall(context =>
        context.invocation == second && initial.exists(previous =>
          previous.replyId == context.replyId && previous.requestId == context.requestId
        )
      )
    )

  def spec: Spec[TestEnvironment & Scope, Any] = suite("InvocationContextSpec")(
    test(
      "invocation identity spans Auto attempts and hooks, remains fresh on resume and survives codecs"
    ):
      for
        auto    <- automatic
        resumes <- ZIO.foreach(Chunk.fromArray(Host.values))(resumed)
      yield resumes.foldLeft(auto)(_ && _)
  ) @@ TestAspect.timeout(15.seconds)
