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
import com.weisgnosis.agents.core.model.{Msg, Role}
import com.weisgnosis.agents.core.tool.{ToolDef, Toolkit}
import zio.*
import zio.json.ast.Json
import zio.test.*

object PreparedInvocationSpec extends ZIOSpecDefault:
  private enum Delivery:
    case Ordinary, Stream

  def spec: Spec[TestEnvironment & Scope, Any] = suite("PreparedInvocationSpec")(
    test(
      "cancellation before either execution path never invokes the source and termination is replayable"
    ):
      for
        checks <- ZIO.foreach(Chunk(Delivery.Ordinary, Delivery.Stream))(delivery =>
          ZIO.scoped:
            for
              calls <- Ref.make(0)
              agent <- ReActAgent.make(
                client = LlmClient.mockEffectful((_, _, _) =>
                  calls.update(_ + 1).as(TurnOutput.FinalText(Msg.assistant("unexpected")))
                ),
                toolkit = Toolkit.empty
              )
              handle     <- agent.prepareRun(Msg.user("not executed"))
              initial    <- handle.status
              observer   <- handle.termination.forkScoped
              unobserved <- observer.poll
              controller <- ZIO.fiberId
              cancelled  <- handle.cancel
              first <- delivery match
                case Delivery.Ordinary => handle.run.unit.exit
                case Delivery.Stream   => handle.streamWithResult.runDrain.exit
              duplicate <- delivery match
                case Delivery.Ordinary => handle.streamWithResult.runDrain.exit
                case Delivery.Stream   => handle.run.unit.exit
              terminal    <- observer.join
              replayed    <- handle.termination
              finalStatus <- handle.status
              repeated    <- handle.cancel
              actualCalls <- calls.get
              history     <- agent.history
            yield assertTrue(
              initial == PreparedInvocation.Status.Created,
              unobserved.isEmpty,
              cancelled.contains(terminal),
              first.causeOption.exists(_.interruptors.contains(controller)),
              terminal.causeOption.exists(_.interruptors.contains(controller)),
              replayed == terminal,
              duplicate.causeOption.exists(
                _.failures.exists(_.isInstanceOf[AgentError.InvalidConfiguration])
              ),
              finalStatus == PreparedInvocation.Status.Cancelled,
              repeated.isEmpty,
              actualCalls == 0,
              history.isEmpty
            )
        )
        failure = AgentError.ModelInputError("Invocation failure retained")
        failing <- ReActAgent.make(
          client = LlmClient.mockEffectful((_, _, _) => ZIO.fail(failure)),
          toolkit = Toolkit.empty
        )
        failed         <- failing.prepareRun(Msg.user("fail"))
        failedExit     <- failed.run.exit
        failureReceipt <- failed.termination
        failedStatus   <- failed.status
      yield checks.foldLeft(assertTrue(true))(_ && _) && assertTrue(
        failedExit.causeOption.exists(_.failures.contains(failure)),
        failureReceipt == failedExit,
        failedStatus == PreparedInvocation.Status.Failed
      )
    ,
    test("execution callbacks and host-scoped descendants cannot cancel their own invocation"):
      for
        owner   <- ZIO.scope
        ready   <- Promise.make[Nothing, PreparedInvocation[AgentResult]]
        release <- Promise.make[Nothing, Unit]
        stopReady <- Promise
          .make[Nothing, Fiber[Nothing, Exit[AgentError, Option[Exit[AgentError, AgentResult]]]]]
        directObserved <- Promise
          .make[Nothing, Option[Either[AgentError, Option[Exit[AgentError, AgentResult]]]]]
        calls <- Ref.make(0)
        client = LlmClient.mockEffectful((_, _, _) =>
          ZIO.uninterruptible:
            for
              _      <- calls.update(_ + 1)
              handle <- ready.await
              stop <- (handle.cancel: IO[AgentError, Option[Exit[AgentError, AgentResult]]]).exit
                .forkIn(owner)
              _     <- stopReady.succeed(stop)
              _     <- release.await
              early <- stop.poll
              // A direct self-call is safe only after the inherited guard has demonstrably refused.
              direct <- early match
                case Some(Exit.Success(Exit.Failure(cause)))
                    if cause.failures.exists(_.isInstanceOf[AgentError.InvalidConfiguration]) =>
                  (handle.cancel: IO[AgentError, Option[Exit[AgentError, AgentResult]]]).either
                    .map(Some(_))
                case _ => ZIO.none
              _ <- directObserved.succeed(direct)
            yield TurnOutput.FinalText(Msg.assistant("continued"))
        )
        agent  <- ReActAgent.make(client = client, toolkit = Toolkit.empty)
        handle <- agent.prepareRun(Msg.user("self cancellation is invalid"))
        _      <- ready.succeed(handle)
        running <- ZIO.uninterruptible:
          for
            fiber <- handle.run.interruptible.forkScoped
            _     <- ZIO.addFinalizer(release.succeed(()).unit)
          yield fiber
        stop          <- stopReady.await
        _             <- TestClock.adjust(Duration.Zero)
        beforeRelease <- stop.poll
        _             <- release.succeed(())
        result        <- running.await
        stopped       <- stop.join
        direct        <- directObserved.await
        terminal      <- handle.termination
        status        <- handle.status
        actualCalls   <- calls.get
        refused = stopped match
          case Exit.Failure(cause) =>
            cause.failures.exists:
              case error: AgentError.InvalidConfiguration =>
                error.message == "Execution callbacks cannot cancel their own invocation"
              case _ => false
          case _ => false
        directRefused = direct.exists:
          case Left(error: AgentError.InvalidConfiguration) =>
            error.message == "Execution callbacks cannot cancel their own invocation"
          case _ => false
        continued = result match
          case Exit.Success(value) => value.reply.text == "continued"
          case _                   => false
      yield assertTrue(
        beforeRelease.isDefined,
        refused,
        directRefused,
        continued,
        terminal == result,
        status == PreparedInvocation.Status.Returned,
        actualCalls == 1
      )
    ,
    test(
      "queued and stale controls cannot affect another owner; prepared streaming shares the reserved context"
    ):
      ZIO.scoped:
        for
          entered  <- Queue.unbounded[String]
          releaseA <- Promise.make[Nothing, Unit]
          releaseC <- Promise.make[Nothing, Unit]
          contexts <- Ref.make(Chunk.empty[ModelStreamContext])
          client = new LlmClient:
            def streamTurn(
                history: Chunk[Msg],
                tools: Chunk[ToolDef],
                config: LlmConfig,
                eventHub: Hub[AgentEvent],
                context: ModelStreamContext
            ): ZIO[Scope, AgentError, TurnOutput] =
              val label = history.reverse.find(_.role == Role.User).fold("")(_.text)
              contexts.update(_ :+ context) *> entered.offer(label) *>
                (if label == "a" then releaseA.await else releaseC.await)
                  .as(TurnOutput.FinalText(Msg.assistant(label)))
          agent <- ReActAgent.make(client = client, toolkit = Toolkit.empty)
          a     <- agent.prepareRun(Msg.user("a"))
          b     <- agent.prepareRun(Msg.user("b"))
          c     <- agent.prepareRun(Msg.user("c"))
          running <- ZIO.uninterruptible:
            for
              fiber <- a.run.interruptible.forkScoped
              _     <- ZIO.addFinalizer(releaseA.succeed(()).unit *> releaseC.succeed(()).unit)
            yield fiber
          firstEntered        <- entered.take
          waiting             <- b.run.uninterruptible.forkScoped
          streamed            <- c.streamWithResult.runCollect.forkScoped
          _                   <- TestClock.adjust(Duration.Zero)
          aStatus             <- a.status
          bStatus             <- b.status
          cStatus             <- c.status
          cancellingB         <- b.cancel.forkScoped
          cancellationCheck   <- cancellingB.await.timeout(1.second).forkScoped
          _                   <- TestClock.adjust(1.second)
          beforeActiveRelease <- cancellationCheck.join
          stillA              <- a.status
          stillC              <- c.status
          cooperative         <- a.interrupt
          interrupted         <- running.join
          cancelled           <- cancellingB.join
          bExit               <- waiting.await
          nextEntered         <- entered.take
          staleCancel         <- a.cancel
          staleInterrupt      <- a.interrupt
          cRunning            <- c.status
          _                   <- releaseC.succeed(())
          updates             <- streamed.join
          receipt             <- c.termination
          correctReceipt = receipt match
            case Exit.Success(value) => value.reply.text == "c"
            case Exit.Failure(_)     => false
          duplicate <- c.run.exit
          observed  <- contexts.get
          aFinished <- a.status
          bFinished <- b.status
          cFinished <- c.status
        yield assertTrue(
          firstEntered == "a",
          aStatus == PreparedInvocation.Status.Running,
          bStatus == PreparedInvocation.Status.Queued,
          cStatus == PreparedInvocation.Status.Queued,
          cancelled.contains(bExit),
          beforeActiveRelease.exists:
            case Exit.Success(Some(exit)) => exit.isInterrupted
            case _                        => false,
          bExit.isInterrupted,
          stillA == PreparedInvocation.Status.Running,
          stillC == PreparedInvocation.Status.Queued,
          cooperative,
          interrupted.reply.metadata
            .get(Msg.MetadataGenerateReason)
            .contains(Json.Str("interrupted")),
          nextEntered == "c",
          staleCancel.isEmpty,
          !staleInterrupt,
          cRunning == PreparedInvocation.Status.Running,
          updates.collect { case AgentRunUpdate.Result(value) => value.reply.text } == Chunk("c"),
          updates.exists:
            case AgentRunUpdate.Event(_) => true
            case _                       => false,
          correctReceipt,
          duplicate.causeOption.exists(
            _.failures.exists(_.isInstanceOf[AgentError.InvalidConfiguration])
          ),
          observed.map(_.invocation) == Chunk(Some(a.context), Some(c.context)),
          aFinished == PreparedInvocation.Status.Returned,
          bFinished == PreparedInvocation.Status.Cancelled,
          cFinished == PreparedInvocation.Status.Returned
        )
    ,
    test(
      "prepared execution is lazy and single-use; queued cancellation preserves the active owner and joins its own cleanup"
    ):
      ZIO.scoped:
        for
          entered        <- Promise.make[Nothing, Unit]
          release        <- Promise.make[Nothing, Unit]
          cleanup        <- Promise.make[Nothing, Unit]
          cleanupRelease <- Promise.make[Nothing, Unit]
          calls          <- Ref.make(0)
          contexts       <- Ref.make(Chunk.empty[ModelStreamContext])
          completions    <- Ref.make(Chunk.empty[TurnCompletion])
          defect = new IllegalStateException("Owned model cleanup defect")
          client = new LlmClient:
            def streamTurn(
                history: Chunk[Msg],
                tools: Chunk[ToolDef],
                config: LlmConfig,
                eventHub: Hub[AgentEvent],
                context: ModelStreamContext
            ): ZIO[Scope, AgentError, TurnOutput] =
              contexts.update(_ :+ context) *> calls
                .getAndUpdate(_ + 1)
                .flatMap:
                  case 0 =>
                    ZIO.acquireRelease(entered.succeed(()))(_ =>
                      cleanup.succeed(()) *> cleanupRelease.await *> ZIO.die(defect)
                    ) *> release.await.as(TurnOutput.FinalText(Msg.assistant("first")))
                  case _ => ZIO.succeed(TurnOutput.FinalText(Msg.assistant("next")))
          agent <- ReActAgent.make(
            client = client,
            toolkit = Toolkit.empty,
            completionObserver =
              TurnCompletionObserver(completion => completions.update(_ :+ completion))
          )
          first         <- agent.prepareRun(Msg.user("first"))
          unused        <- agent.prepareRun(Msg.user("unused"))
          historyBefore <- agent.history
          callsBefore   <- calls.get
          firstFiber <- ZIO.uninterruptible:
            for
              fiber <- first.run.interruptible.forkScoped
              _     <- ZIO.addFinalizer(release.succeed(()).unit *> cleanupRelease.succeed(()).unit)
            yield fiber
          _                 <- entered.await
          duplicate         <- first.run.exit
          queued            <- agent.prepareRun(Msg.user("queued"))
          waiting           <- queued.run.forkScoped
          _                 <- (ZIO.yieldNow *> waiting.status).repeatUntil(_.isSuspended)
          cancelled         <- waiting.interrupt
          retried           <- queued.run.exit
          activeCalls       <- calls.get
          prematurelyClosed <- cleanup.isDone
          observedBefore    <- completions.get
          stopping <- ZIO.uninterruptible:
            for
              fiber <- first.cancel.interruptible.forkScoped
              _     <- ZIO.addFinalizer(release.succeed(()).unit *> cleanupRelease.succeed(()).unit)
            yield fiber
          _             <- cleanup.await
          beforeRelease <- stopping.poll
          _             <- cleanupRelease.succeed(())
          stopped       <- stopping.join
          stoppedRun    <- firstFiber.await
          terminal      <- first.termination
          firstStatus   <- first.status
          afterFirst    <- completions.get
          next          <- agent.prepareRun(Msg.user("next"))
          completed     <- next.run
          observed      <- contexts.get
          finished      <- completions.get
        yield assertTrue(
          historyBefore.isEmpty,
          callsBefore == 0,
          first.context != unused.context,
          duplicate.causeOption.exists(
            _.failures.exists(_.isInstanceOf[AgentError.InvalidConfiguration])
          ),
          cancelled.isInterrupted,
          retried.causeOption.exists(
            _.failures.exists(_.isInstanceOf[AgentError.InvalidConfiguration])
          ),
          activeCalls == 1,
          !prematurelyClosed,
          observedBefore.isEmpty,
          beforeRelease.isEmpty,
          stopped.exists(_.isInterrupted),
          stopped.flatMap(_.causeOption).exists(_.defects.contains(defect)),
          stopped.contains(terminal),
          stopped.contains(stoppedRun),
          firstStatus == PreparedInvocation.Status.Cancelled,
          afterFirst.size == 1,
          afterFirst.forall(completion =>
            completion.invocation.contains(first.context) &&
              completion.outcome.causeOption.exists(_.defects.contains(defect))
          ),
          completed.reply.text == "next",
          finished.size == 2,
          observed.map(_.invocation) == Chunk(Some(first.context), Some(next.context)),
          finished.map(_.invocation) == Chunk(Some(first.context), Some(next.context)),
          next.context != first.context,
          next.context != queued.context
        )
  ) @@ TestAspect.timeout(15.seconds)
