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
import com.weisgnosis.agents.core.event.AgentEvent
import com.weisgnosis.agents.core.model.*
import com.weisgnosis.agents.core.tool.*
import zio.*
import zio.http.*
import zio.json.ast.Json
import zio.test.*

object PhaseObserverSpec extends ZIOSpecDefault:
  private enum Failure:
    case Enter, Finish, Cleanup, Model

  private enum Cancellation:
    case Fiber, Cooperative, FiberCleanup, CooperativeCleanup, CallerDuringCleanup

  private def label(context: PhaseContext): String = context match
    case PhaseContext.Invocation(_)           => "invocation"
    case PhaseContext.Reasoning(_, iteration) => s"reasoning:$iteration"
    case PhaseContext.ModelCall(_, _)         => "model"

  private def fallbackContract: ZIO[Scope, AgentError, TestResult] =
    for
      methods       <- Ref.make(Chunk.empty[Method])
      fallbackCalls <- Ref.make(0)
      phases        <- Ref.make(Chunk.empty[(PhaseContext, Exit[AgentError, Unit])])
      http = ZClient.fromDriver(new ZClient.Driver[Any, Scope, Throwable]:
        def request(
            version: Version,
            method: Method,
            url: URL,
            headers: Headers,
            body: Body,
            sslConfig: Option[ClientSSLConfig],
            proxy: Option[Proxy]
        )(using Trace): ZIO[Scope, Throwable, Response] =
          methods
            .update(_ :+ method)
            .as(
              Response(status = Status.ServiceUnavailable, body = Body.fromString("busy"))
            )
        def socket[Env1 <: Any](
            version: Version,
            url: URL,
            headers: Headers,
            app: WebSocketApp[Env1]
        )(using Trace, Scope =:= Scope): ZIO[Env1 & Scope, Throwable, Response] =
          ZIO.fail(AgentError.InvalidConfiguration("WebSockets are outside this fixture")))
      primary <- ChatCompletionsClient.make(http)
      fallback = LlmClient.mockEffectful((_, _, _) =>
        fallbackCalls.update(_ + 1).as(TurnOutput.FinalText(Msg.assistant("fallback")))
      )
      observer = PhaseObserver((context, _) =>
        ZIO.succeed(PhaseCompletion(exit => phases.update(_ :+ (context -> exit))))
      )
      agent <- ReActAgent
        .builder()
        .model(primary)
        .llmConfig(LlmConfig("primary", endpoint = "http://localhost/rejected"))
        .fallback(ModelTarget(fallback, LlmConfig("fallback")))
        .phaseObserver(observer)
        .build
      result         <- agent.run(Msg.user("hello"))
      seen           <- phases.get
      primaryMethods <- methods.get
      calls          <- fallbackCalls.get
      models = seen.collect { case (PhaseContext.ModelCall(context, model), exit) =>
        (context, model, exit)
      }
      reasoning = seen.collect { case (context: PhaseContext.Reasoning, exit) => (context, exit) }
      rejection = AgentError.ModelError(503, "HTTP completion request was rejected", Some("busy"))
    yield assertTrue(
      result.state == AgentRunState.Completed,
      result.reply.text == "fallback",
      primaryMethods == Chunk(Method.POST),
      calls == 1,
      models.map(_._2) == Chunk("primary", "fallback"),
      models.map(_._1.requestId).distinct.size == 2,
      models.map(_._1.invocation).distinct.size == 1,
      models.headOption.exists(_._3 == Exit.fail(rejection)),
      models.drop(1).headOption.exists(_._3 == Exit.unit),
      reasoning.size == 1 && reasoning.forall(_._2.isSuccess)
    )

  def spec: Spec[TestEnvironment & Scope, Any] = suite("PhaseObserverSpec")(
    test(
      "nested phase scopes preserve onion order around actual tool rounds and publish one revocable Hint"
    ):
      for
        trace    <- Ref.make(Chunk.empty[String])
        retained <- Ref.make(Option.empty[PhaseHints])
        missing  <- Ref.make(Chunk.empty[String])
        contexts <- Ref.make(Chunk.empty[PhaseContext])
        observer = (name: String) =>
          PhaseObserver: (context, hints) =>
            val phase = label(context)
            for
              _ <- trace.update(_ :+ s"$name:enter:$phase")
              _ <- ZIO.addFinalizer(trace.update(_ :+ s"$name:close:$phase"))
              _ <- if name == "A" then contexts.update(_ :+ context) else ZIO.unit
              _ <- if hints.isEmpty then missing.update(_ :+ phase) else ZIO.unit
              _ <- (context, hints) match
                case (PhaseContext.Reasoning(_, 1), Some(port)) if name == "A" =>
                  retained.set(Some(port)) *> port.publish("observed once").unit
                case _ => ZIO.unit
            yield PhaseCompletion(outcome =>
              trace.update(_ :+ s"$name:finish:$phase:${outcome.isSuccess}")
            )
        tool = Tool.make(ToolDef("work", "Record actual tool execution", Json.Obj()))((_: Json) =>
          trace.update(_ :+ "tool").as("done")
        )
        client <- LlmClient.mock(
          Chunk(
            TurnOutput.ToolCalls(
              Msg.assistant(Chunk(ContentBlock.ToolUse("call", "work", Json.Obj())))
            ),
            TurnOutput.FinalText(Msg.assistant("finished"))
          )
        )
        agent <- ReActAgent
          .builder()
          .model(client)
          .toolkit(Toolkit.empty.updated(tool))
          .phaseObserver(observer("A").andThen(observer("B")))
          .build
        updates <- agent.streamWithResult(Msg.user("begin")).runCollect
        actual  <- trace.get
        seen    <- contexts.get
        absent  <- missing.get
        late <- retained.get.flatMap:
          case Some(port) => port.publish("too late")
          case None       => ZIO.succeed(ToolEventDelivery.Published)
        round = (iteration: Int) =>
          Chunk(
            s"A:enter:reasoning:$iteration",
            s"B:enter:reasoning:$iteration",
            "A:enter:model",
            "B:enter:model",
            "B:finish:model:true",
            "B:close:model",
            "A:finish:model:true",
            "A:close:model",
            s"B:finish:reasoning:$iteration:true",
            s"B:close:reasoning:$iteration",
            s"A:finish:reasoning:$iteration:true",
            s"A:close:reasoning:$iteration"
          )
        expected = Chunk("A:enter:invocation", "B:enter:invocation") ++ round(1) ++
          Chunk("tool") ++ round(2) ++ Chunk(
            "B:finish:invocation:true",
            "B:close:invocation",
            "A:finish:invocation:true",
            "A:close:invocation"
          )
        invocations = seen.collect:
          case PhaseContext.Invocation(value)   => Some(value)
          case PhaseContext.Reasoning(value, _) => Some(value)
          case PhaseContext.ModelCall(value, _) => value.invocation
      yield assertTrue(
        actual == expected,
        absent == Chunk("invocation", "invocation"),
        invocations.distinct.size == 1,
        invocations.forall(_.nonEmpty),
        updates.collect { case AgentRunUpdate.Event(AgentEvent.HintBlockEvent(value, _)) =>
          value
        } ==
          Chunk("observed once"),
        updates.count {
          case AgentRunUpdate.Event(_: AgentEvent.ModelRequestStartEvent) => true; case _ => false
        } == 2,
        updates.collect { case AgentRunUpdate.Result(result) => result.state } == Chunk(
          AgentRunState.Completed
        ),
        late == ToolEventDelivery.Closed
      )
    ,
    test(
      "entry, completion and model failures preserve exact causes and close entered observer scopes without retry"
    ):
      ZIO
        .foreach(Failure.values): mode =>
          val error      = AgentError.CompletionObservationError(s"observer:$mode")
          val modelError = AgentError.ModelError(500, "provider failed")
          for
            trace    <- Ref.make(Chunk.empty[String])
            attempts <- Ref.make(0)
            observed <- Ref.make(Chunk.empty[Exit[AgentError, Unit]])
            outer = PhaseObserver((context, _) =>
              context match
                case _: PhaseContext.Invocation =>
                  ZIO
                    .addFinalizer(trace.update(_ :+ "outer:close"))
                    .as(
                      PhaseCompletion(exit =>
                        observed.update(_ :+ exit) *> trace.update(_ :+ "outer:finish")
                      )
                    )
                case _ => ZIO.succeed(PhaseCompletion.ignore)
            )
            inner = PhaseObserver((context, _) =>
              context match
                case _: PhaseContext.Invocation =>
                  ZIO.addFinalizer(
                    trace.update(_ :+ "inner:close") *> ZIO
                      .die(error)
                      .when(mode == Failure.Cleanup)
                      .unit
                  ) *> (if mode == Failure.Enter then ZIO.fail(error)
                        else
                          ZIO.succeed(
                            PhaseCompletion(_ =>
                              trace.update(_ :+ "inner:finish") *>
                                ZIO.fail(error).when(mode == Failure.Finish).unit
                            )
                          ))
                case _ => ZIO.succeed(PhaseCompletion.ignore)
            )
            client = LlmClient.mockEffectful((_, _, _) =>
              attempts.update(_ + 1) *>
                (if mode == Failure.Model then ZIO.fail(modelError)
                 else ZIO.succeed(TurnOutput.FinalText(Msg.assistant("done"))))
            )
            agent    <- ReActAgent.make(client = client, phaseObserver = outer.andThen(inner))
            result   <- agent.run(Msg.user("hello")).exit
            calls    <- attempts.get
            actual   <- trace.get
            outcomes <- observed.get
            cause           = result.causeOption.getOrElse(Cause.empty)
            expectedFailure = if mode == Failure.Model then List(modelError) else List(error)
          yield assertTrue(
            result.isFailure,
            calls == (if mode == Failure.Enter then 0 else 1),
            actual == (if mode == Failure.Enter then
                         Chunk("inner:close", "outer:finish", "outer:close")
                       else Chunk("inner:finish", "inner:close", "outer:finish", "outer:close")),
            outcomes.size == 1,
            outcomes.forall(_.isFailure),
            cause.failures.toList == (if mode == Failure.Cleanup then Nil else expectedFailure),
            cause.defects.toList == (if mode == Failure.Cleanup then List(error) else Nil)
          )
        .map(results => results.foldLeft(assertTrue(true))(_ && _))
        .zipWith(fallbackContract)(_ && _)
    ,
    test(
      "fiber and cooperative cancellation join observer cleanup and retain cleanup defects and caller provenance"
    ):
      ZIO
        .foreach(Cancellation.values): mode =>
          ZIO.scoped:
            val defect = AgentError.CompletionObservationError(s"cleanup:$mode")
            val fiberCancellation =
              mode == Cancellation.Fiber || mode == Cancellation.FiberCleanup ||
                mode == Cancellation.CallerDuringCleanup
            val cleanupDefect =
              mode == Cancellation.FiberCleanup || mode == Cancellation.CooperativeCleanup ||
                mode == Cancellation.CallerDuringCleanup
            val duringCleanup = mode == Cancellation.CallerDuringCleanup
            for
              entered   <- Promise.make[Nothing, Unit]
              cleaning  <- Promise.make[Nothing, Unit]
              release   <- Promise.make[Nothing, Unit]
              signalled <- Promise.make[Nothing, Unit]
              finishes  <- Ref.make(0)
              outcomes  <- Ref.make(Chunk.empty[Exit[AgentError, Unit]])
              observer = PhaseObserver((context, _) =>
                context match
                  case _: PhaseContext.ModelCall =>
                    ZIO
                      .addFinalizer(
                        cleaning.succeed(()) *> release.await *>
                          ZIO.die(defect).when(cleanupDefect).unit
                      )
                      .as(
                        PhaseCompletion(exit =>
                          finishes.update(_ + 1) *> outcomes.update(_ :+ exit)
                        )
                      )
                  case _ => ZIO.succeed(PhaseCompletion.ignore)
              )
              client = LlmClient.mockEffectful((_, _, _) =>
                entered.succeed(()) *>
                  (if duringCleanup then ZIO.succeed(TurnOutput.FinalText(Msg.assistant("done")))
                   else ZIO.never)
              )
              agent <- ReActAgent.make(client = client, phaseObserver = observer)
              active <- ZIO.uninterruptibleMask: restore =>
                for
                  active <- restore(agent.run(Msg.user("interrupt"))).forkScoped
                  _      <- ZIO.addFinalizer(release.succeed(()).unit)
                yield active
              _          <- entered.await
              _          <- cleaning.await.when(duringCleanup)
              controller <- ZIO.fiberId
              stopping <- ZIO.uninterruptibleMask: restore =>
                for
                  stopping <- restore(
                    if fiberCancellation then
                      active.interruptAsFork(controller) *> signalled.succeed(
                        ()
                      ) *> active.await.unit
                    else agent.interrupt *> signalled.succeed(()) *> active.await.unit
                  ).forkScoped
                  _ <- ZIO.addFinalizer(release.succeed(()).unit)
                yield stopping
              _        <- cleaning.await
              _        <- signalled.await
              early    <- stopping.poll
              _        <- release.succeed(())
              _        <- stopping.join
              result   <- active.await
              count    <- finishes.get
              observed <- outcomes.get
              cause = result.causeOption.getOrElse(Cause.empty)
              completed = result match
                case Exit.Success(value) => value.state == AgentRunState.Interrupted
                case _                   => false
            yield assertTrue(
              early.isEmpty,
              count == 1,
              observed.size == 1,
              if duringCleanup then observed.forall(_.isSuccess)
              else observed.forall(_.isInterrupted),
              if fiberCancellation then cause.interruptors.contains(controller) else true,
              cause.defects.toList == (if cleanupDefect then List(defect) else Nil),
              cause.failures.isEmpty,
              if !fiberCancellation && !cleanupDefect then completed else result.isFailure
            )
        .map(results => results.foldLeft(assertTrue(true))(_ && _))
  ) @@ TestAspect.timeout(20.seconds)
