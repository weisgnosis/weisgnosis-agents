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
import com.weisgnosis.agents.core.model.Msg
import com.weisgnosis.agents.core.tool.{ToolDef, Toolkit}
import zio.*
import zio.test.*

object PreparedInvocationSpec extends ZIOSpecDefault:
  def spec: Spec[TestEnvironment & Scope, Any] = suite("PreparedInvocationSpec")(
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
              fiber <- firstFiber.interrupt.interruptible.forkScoped
              _     <- ZIO.addFinalizer(release.succeed(()).unit *> cleanupRelease.succeed(()).unit)
            yield fiber
          _             <- cleanup.await
          beforeRelease <- stopping.poll
          _             <- cleanupRelease.succeed(())
          stopped       <- stopping.join
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
          stopped.isInterrupted,
          stopped.causeOption.exists(_.defects.contains(defect)),
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
