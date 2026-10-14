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

import com.weisgnosis.agents.core.client.{LlmClient, LlmConfig, TurnOutput}
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.event.{EventPublisher, InvocationContext, ModelStreamContext}
import com.weisgnosis.agents.core.model.*
import com.weisgnosis.agents.core.tool.*
import zio.*
import zio.json.ast.Json
import zio.test.*

object InvocationPreparerSpec extends ZIOSpecDefault:
  private enum Failure:
    case Typed, Cancelled

  private enum InvocationBoundary:
    case IndependentSessions, PreparationRetry, NestedChild

  def spec: Spec[TestEnvironment & Scope, Any] = suite("InvocationPreparerSpec")(
    test("composition is lazy, scoped, ordered and rejects invalid intermediate rewrites"):
      val system   = Msg.system("keep")
      val original = InvocationPreparation(Chunk(system), Chunk(Msg.user("hello")))
      for
        context <- InvocationContext.fresh
        trace   <- Ref.make(Chunk.empty[String])
        closed  <- Ref.make(false)
        first = InvocationPreparer: (seen, value) =>
          ZIO.addFinalizer(closed.set(true)) *>
            trace
              .update(_ :+ seen.invocationId.toString)
              .as(value.copy(input = Msg.user("prefix") +: value.input))
        second = InvocationPreparer: (_, value) =>
          closed.get
            .flatMap(wasClosed =>
              trace.update(_ :+ s"${value.input.map(_.text).mkString(",")}:$wasClosed")
            )
            .as(value)
        effect = first.andThen(second).prepare(context, original)
        before   <- trace.get
        result   <- ZIO.scoped(effect)
        after    <- trace.get
        released <- closed.get
        rejected <- ZIO.foreach(
          Chunk(
            original.copy(history = Chunk.empty),
            original.copy(input =
              Chunk(Msg.assistant(Chunk(ContentBlock.ToolUse("pending", "read", Json.Obj()))))
            )
          )
        ): invalid =>
          for
            later <- Ref.make(0)
            failed <- ZIO
              .scoped(
                InvocationPreparer((_, _) => ZIO.succeed(invalid))
                  .andThen(InvocationPreparer((_, value) => later.update(_ + 1).as(value)))
                  .prepare(context, original)
              )
              .either
            count <- later.get
          yield failed.isLeft && count == 0
      yield assertTrue(
        before.isEmpty,
        after == Chunk(context.invocationId.toString, "prefix,hello:false"),
        released,
        result == original.copy(input = Chunk(Msg.user("prefix"), Msg.user("hello"))),
        original.input == Chunk(Msg.user("hello")),
        rejected.forall(identity)
      )
    ,
    test(
      "fresh ordinary and streamed invocations prepare prior state once and restore without replay"
    ):
      for
        observations <- Ref.make(Chunk.empty[(InvocationContext, InvocationPreparation)])
        requests     <- Ref.make(Chunk.empty[Chunk[Msg]])
        released     <- Ref.make(0)
        client = LlmClient.mockEffectful: (history, _, _) =>
          requests.update(_ :+ history).as(TurnOutput.FinalText(Msg.assistant("done")))
        preparer = InvocationPreparer: (context, value) =>
          ZIO.addFinalizer(released.update(_ + 1)) *>
            observations
              .update(_ :+ (context -> value))
              .as(
                value.copy(
                  history =
                    if value.history.isEmpty then Chunk(Msg.user("boot")) else value.history,
                  input = Msg.user("prefix") +: value.input
                )
              )
        agent <- ReActAgent.builder().model(client).invocationPreparer(preparer).build
        input = Chunk(Msg.user("hello"))
        first      <- agent.prepareRun(input)
        _          <- first.run
        saved      <- agent.snapshot
        second     <- agent.prepareRun(Msg.user("again"))
        _          <- second.streamWithResult.runCollect
        seen       <- observations.get
        rounds     <- requests.get
        count      <- released.get
        fresh      <- ReActAgent.make(client = client, invocationPreparer = preparer)
        _          <- fresh.restoreSnapshot(saved)
        beforeNext <- observations.get
        _          <- fresh.run(Msg.user("reopened"))
        reopened   <- observations.get
        entered    <- Promise.make[Nothing, Unit]
        release    <- Promise.make[Nothing, Unit]
        cleanup    <- Ref.make(false)
        blocked <- ReActAgent.make(
          client = client,
          invocationPreparer = InvocationPreparer((_, value) =>
            (entered.succeed(()) *> release.await.as(value)).ensuring(cleanup.set(true))
          )
        )
        beforeInterrupt <- requests.get
        active <- ZIO.uninterruptibleMask: restore =>
          for
            fiber <- restore(blocked.run(Msg.user("interrupt before model"))).forkScoped
            _     <- ZIO.addFinalizer(release.succeed(()).unit)
          yield fiber
        _              <- entered.await
        _              <- blocked.interrupt
        _              <- release.succeed(())
        interrupted    <- active.join
        afterInterrupt <- requests.get
        cleaned        <- cleanup.get
      yield assertTrue(
        input == Chunk(Msg.user("hello")),
        seen.map(_._1) == Chunk(first.context, second.context),
        seen.headOption.exists(_._2.history.isEmpty),
        seen
          .drop(1)
          .headOption
          .exists(_._2.history.map(_.text) == Chunk("boot", "prefix", "hello", "done")),
        rounds.headOption.exists(_.map(_.text) == Chunk("boot", "prefix", "hello")),
        rounds
          .drop(1)
          .headOption
          .exists(_.map(_.text) == Chunk("boot", "prefix", "hello", "done", "prefix", "again")),
        count == 2,
        beforeNext == seen,
        reopened.lastOption.exists(
          _._2.history.map(_.text) == Chunk("boot", "prefix", "hello", "done")
        ),
        interrupted.state == AgentRunState.Interrupted,
        afterInterrupt == beforeInterrupt,
        cleaned
      )
    ,
    test(
      "structured Auto fallback prepares once and checked suspended resume bypasses preparation"
    ):
      for
        prepared <- Ref.make(0)
        rounds   <- Ref.make(Chunk.empty[Chunk[Msg]])
        client = new LlmClient:
          def streamTurn(
              history: Chunk[Msg],
              tools: Chunk[ToolDef],
              config: LlmConfig,
              eventHub: EventPublisher,
              context: ModelStreamContext
          ): ZIO[Scope, AgentError, TurnOutput] =
            rounds.update(_ :+ history) *>
              (if config.responseFormat.nonEmpty then
                 ZIO.fail(AgentError.ModelError(400, "native unavailable"))
               else
                 ZIO.succeed(
                   TurnOutput.ToolCalls(
                     Msg.assistant(
                       Chunk(
                         ContentBlock.ToolUse(
                           "structured",
                           StructuredOutput.ToolName,
                           Json.Obj("response" -> Json.Num(7))
                         )
                       )
                     )
                   )
                 ))
        preparer = InvocationPreparer: (_, value) =>
          prepared.update(_ + 1).as(value.copy(input = Msg.user("prefix") +: value.input))
        agent <- ReActAgent.make(client = client, invocationPreparer = preparer)
        structured <- agent.runStructured[Int](
          Chunk(Msg.user("number")),
          Json.Obj("type" -> Json.Str("integer")),
          StructuredMode.Auto
        )
        attempts   <- rounds.get
        count      <- prepared.get
        executions <- Ref.make(0)
        tool = Tool.make(ToolDef("read", "Read", Json.Obj()))(_ =>
          executions.update(_ + 1).as("read")
        )
        toolkit <- ZIO.fromEither(Toolkit.empty.register(tool))
        queued <- LlmClient.mock(
          Chunk(
            TurnOutput.ToolCalls(
              Msg.assistant(Chunk(ContentBlock.ToolUse("call", "read", Json.Obj())))
            ),
            TurnOutput.FinalText(Msg.assistant("resumed"))
          )
        )
        original <- ReActAgent.make(
          client = queued,
          toolkit = toolkit,
          invocationPreparer = preparer,
          toolAuthorizer = ToolAuthorizer(_ => ZIO.succeed(ToolAuthorization.Deferred("approve")))
        )
        _        <- original.run(Msg.user("read"))
        snapshot <- original.snapshot
        restored <- ReActAgent.make(
          client = queued,
          toolkit = toolkit,
          invocationPreparer = preparer
        )
        _            <- restored.restoreSnapshot(snapshot)
        beforeResume <- prepared.get
        resumed      <- restored.resume()
        afterResume  <- prepared.get
        executed     <- executions.get
      yield assertTrue(
        structured._2 == 7,
        count == 1,
        attempts.size == 2,
        attempts.forall(_.filter(_.role == Role.User).map(_.text) == Chunk("prefix", "number")),
        beforeResume == 2,
        afterResume == beforeResume,
        resumed.state == AgentRunState.Completed,
        executed == 1
      )
    ,
    test(
      "required failure and caller cancellation join preparation cleanup before another invocation"
    ):
      for checks <- ZIO.foreach(Failure.values.toList): mode =>
          ZIO.scoped:
            for
              armed      <- Ref.make(true)
              models     <- Ref.make(0)
              later      <- Ref.make(0)
              entered    <- Promise.make[Nothing, Unit]
              cleanup    <- Promise.make[Nothing, Unit]
              release    <- Promise.make[Nothing, Unit]
              controller <- ZIO.fiberId
              failure = AgentError.InvalidConfiguration("preparation failed")
              defect  = new IllegalStateException("preparation cleanup")
              first = InvocationPreparer: (_, value) =>
                armed
                  .getAndSet(false)
                  .flatMap:
                    case false => ZIO.succeed(value)
                    case true =>
                      mode match
                        case Failure.Typed => ZIO.fail(failure)
                        case Failure.Cancelled =>
                          (entered.succeed(()) *> ZIO.never)
                            .ensuring(cleanup.succeed(()) *> release.await *> ZIO.die(defect))
              preparer = first.andThen(
                InvocationPreparer((_, value) => later.update(_ + 1).as(value))
              )
              client = LlmClient.mockEffectful((_, _, _) =>
                models.update(_ + 1).as(TurnOutput.FinalText(Msg.assistant("done")))
              )
              agent <- ReActAgent.make(client = client, invocationPreparer = preparer)
              running <- ZIO.uninterruptibleMask: restore =>
                for
                  fiber <- restore(agent.run(Msg.user("not committed"))).forkScoped
                  _     <- ZIO.addFinalizer(release.succeed(()).unit)
                yield fiber
              result <- mode match
                case Failure.Typed => running.await
                case Failure.Cancelled =>
                  for
                    _        <- entered.await
                    stopping <- running.interruptAs(controller).forkScoped
                    _        <- cleanup.await
                    early    <- stopping.poll
                    _        <- release.succeed(())
                    stopped  <- stopping.join
                    _ <- ZIO
                      .fail(AgentError.InvalidConfiguration("Cancellation returned before cleanup"))
                      .when(early.nonEmpty)
                  yield stopped
              before <- agent.history
              _      <- agent.run(Msg.user("next"))
              calls  <- models.get
              stages <- later.get
            yield assertTrue(
              before.isEmpty,
              calls == 1,
              stages == 1,
              result match
                case Exit.Failure(cause) =>
                  mode match
                    case Failure.Typed => cause.failures == List(failure)
                    case Failure.Cancelled =>
                      cause.interruptors.contains(controller) && cause.defects == List(defect)
                case Exit.Success(_) => false
            )
      yield checks.foldLeft(assertTrue(true))(_ && _)
    ,
    test(
      "explicit session selection, preparation retry and nested calls keep invocation signals isolated"
    ):
      for checks <- ZIO.foreach(InvocationBoundary.values.toList): boundary =>
          ZIO.scoped:
            for
              entered        <- Promise.make[Nothing, Unit]
              childEntered   <- Promise.make[Nothing, Unit]
              release        <- Promise.make[Nothing, Unit]
              _              <- ZIO.addFinalizer(release.succeed(()).unit)
              attempts       <- Ref.make(0)
              parentContexts <- Ref.make(Chunk.empty[InvocationContext])
              childContexts  <- Ref.make(Chunk.empty[InvocationContext])
              modelContexts  <- Ref.make(Chunk.empty[ModelStreamContext])
              parentModels   <- Ref.make(0)
              childModels    <- Ref.make(0)
              childClient = new LlmClient:
                def streamTurn(
                    history: Chunk[Msg],
                    tools: Chunk[ToolDef],
                    config: LlmConfig,
                    eventHub: EventPublisher,
                    context: ModelStreamContext
                ): ZIO[Scope, AgentError, TurnOutput] =
                  modelContexts.update(_ :+ context) *> childModels
                    .update(_ + 1)
                    .as(TurnOutput.FinalText(Msg.assistant("child completed")))
              child <- ReActAgent.make(
                client = childClient,
                invocationPreparer = InvocationPreparer((context, value) =>
                  childContexts.update(_ :+ context) *> childEntered.succeed(()) *>
                    release.await.as(value)
                )
              )
              childHandle <- child.prepareRun(Msg.user("child session"))
              parentPreparer = InvocationPreparer: (context, value) =>
                val attempt = parentContexts.update(_ :+ context) *> attempts
                  .updateAndGet(_ + 1)
                  .flatMap: number =>
                    boundary match
                      case InvocationBoundary.PreparationRetry if number == 1 =>
                        ZIO.fail(AgentError.InvalidConfiguration("retry preparation"))
                      case InvocationBoundary.NestedChild =>
                        entered.succeed(()) *> childHandle.run.as(value)
                      case _ => entered.succeed(()) *> release.await.as(value)
                boundary match
                  case InvocationBoundary.PreparationRetry => attempt.retry(Schedule.recurs(1))
                  case _                                   => attempt
              parent <- ReActAgent.make(
                client = LlmClient.mockEffectful((_, _, _) =>
                  parentModels.update(_ + 1).as(TurnOutput.FinalText(Msg.assistant("parent model")))
                ),
                invocationPreparer = parentPreparer
              )
              parentHandle <- parent.prepareRun(Msg.user("parent session"))
              running      <- parentHandle.run.forkScoped
              independent <- boundary match
                case InvocationBoundary.IndependentSessions =>
                  childHandle.run.forkScoped.map(Some(_))
                case _ => ZIO.none
              _ <- entered.await
              _ <- boundary match
                case InvocationBoundary.PreparationRetry => ZIO.unit
                case _                                   => childEntered.await
              parentBefore       <- parentHandle.status
              childBefore        <- childHandle.status
              attemptsBefore     <- attempts.get
              parentModelsBefore <- parentModels.get
              childModelsBefore  <- childModels.get
              signalled          <- parentHandle.interrupt
              _                  <- release.succeed(())
              result             <- running.join
              childResult <- boundary match
                case InvocationBoundary.PreparationRetry => ZIO.none
                case _ =>
                  childHandle.termination.flatMap:
                    case Exit.Success(value) => ZIO.some(value)
                    case Exit.Failure(cause) => ZIO.refailCause(cause)
              _           <- ZIO.foreachDiscard(independent)(_.join)
              parentAfter <- parentHandle.status
              childAfter  <- childHandle.status
              seenParent  <- parentContexts.get
              seenChild   <- childContexts.get
              seenModels  <- modelContexts.get
              parentCount <- parentModels.get
              childCount  <- childModels.get
            yield assertTrue(
              parentBefore == PreparedInvocation.Status.Running,
              childBefore == (if boundary == InvocationBoundary.PreparationRetry then
                                PreparedInvocation.Status.Created
                              else PreparedInvocation.Status.Running),
              attemptsBefore == (if boundary == InvocationBoundary.PreparationRetry then 2 else 1),
              parentModelsBefore == 0,
              childModelsBefore == 0,
              signalled,
              result.state == AgentRunState.Interrupted,
              parentAfter == PreparedInvocation.Status.Returned,
              seenParent == Chunk.fill(attemptsBefore)(parentHandle.context),
              parentCount == 0,
              boundary match
                case InvocationBoundary.PreparationRetry =>
                  childResult.isEmpty && childAfter == PreparedInvocation.Status.Created &&
                  seenChild.isEmpty && seenModels.isEmpty && childCount == 0
                case _ =>
                  childResult.exists(_.state == AgentRunState.Completed) &&
                  childAfter == PreparedInvocation.Status.Returned && childCount == 1 &&
                  seenChild == Chunk(
                    childHandle.context
                  ) && childHandle.context != parentHandle.context &&
                  seenModels.map(_.invocation) == Chunk(Some(childHandle.context))
            )
      yield checks.foldLeft(assertTrue(true))(_ && _)
  ) @@ TestAspect.timeout(20.seconds)
