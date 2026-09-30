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

import com.weisgnosis.agents.core.event.AgentEvent
import zio.*
import zio.test.*

object AgentEventForwarderSpec extends ZIOSpecDefault:
  private enum FinishMode:
    case DeliveryDefect, CallerCancellation

  private val first: AgentEvent  = AgentEvent.AgentEndEvent("first")
  private val second: AgentEvent = AgentEvent.AgentEndEvent("second")
  private val third: AgentEvent  = AgentEvent.AgentEndEvent("third")

  def spec: Spec[TestEnvironment & Scope, Any] = suite("AgentEventForwarderSpec")(
    test("one consumer drains the held claimed prefix before the queued suffix") {
      ZIO.scoped:
        for
          hub          <- ZIO.acquireRelease(Hub.unbounded[AgentEvent])(_.shutdown)
          subscription <- hub.subscribe
          entered      <- Promise.make[Nothing, Unit]
          release      <- Promise.make[Nothing, Unit]
          observed     <- Ref.make(Chunk.empty[AgentEvent])
          result <- (for
            finish <- AgentEventForwarder.start(
              subscription,
              event =>
                ZIO.when(event == first)(entered.succeed(()) *> release.await) *>
                  observed.update(_ :+ event)
            )
            _         <- hub.publish(first)
            _         <- entered.await
            _         <- hub.publishAll(Chunk(second, third))
            finishing <- finish.forkScoped
            _         <- (ZIO.yieldNow *> finishing.status).repeatUntil(_.isSuspended)
            pending   <- finishing.poll
            before    <- observed.get
            _         <- release.succeed(())
            exit      <- finishing.await
            events    <- observed.get
            closed    <- subscription.isShutdown
          yield assertTrue(
            pending.isEmpty,
            before.isEmpty,
            exit.isSuccess,
            events == Chunk(first, second, third),
            closed
          )).ensuring(release.succeed(()).unit)
        yield result
    },
    test(
      "empty shutdown completes and claimed-only shutdown joins publication without leaking interruption"
    ) {
      for
        empty <- ZIO.scoped:
          for
            hub          <- ZIO.acquireRelease(Hub.unbounded[AgentEvent])(_.shutdown)
            subscription <- hub.subscribe
            observed     <- Ref.make(Chunk.empty[AgentEvent])
            finish <- AgentEventForwarder.start(subscription, event => observed.update(_ :+ event))
            exit   <- finish.exit
            events <- observed.get
            closed <- subscription.isShutdown
          yield assertTrue(exit.isSuccess, events.isEmpty, closed)
        claimed <- ZIO.scoped:
          for
            hub          <- ZIO.acquireRelease(Hub.unbounded[AgentEvent])(_.shutdown)
            subscription <- hub.subscribe
            entered      <- Promise.make[Nothing, Unit]
            release      <- Promise.make[Nothing, Unit]
            observed     <- Ref.make(Chunk.empty[AgentEvent])
            result <- (for
              finish <- AgentEventForwarder.start(
                subscription,
                event => entered.succeed(()) *> release.await *> observed.update(_ :+ event)
              )
              _         <- hub.publish(first)
              _         <- entered.await
              finishing <- finish.forkScoped
              _         <- subscription.awaitShutdown
              pending   <- finishing.poll
              _         <- release.succeed(())
              exit      <- finishing.await
              events    <- observed.get
            yield assertTrue(pending.isEmpty, exit.isSuccess, events == Chunk(first)))
              .ensuring(release.succeed(()).unit)
          yield result
      yield empty && claimed
    },
    test("finish preserves delivery defects and caller cancellation waits for owned publication") {
      ZIO
        .foreach(Chunk(FinishMode.DeliveryDefect, FinishMode.CallerCancellation))(finishOutcome)
        .map(_.foldLeft(assertTrue(true))(_ && _))
    }
  ) @@ TestAspect.timeout(15.seconds)

  private def finishOutcome(mode: FinishMode): UIO[TestResult] = ZIO.scoped:
    for
      hub          <- ZIO.acquireRelease(Hub.unbounded[AgentEvent])(_.shutdown)
      subscription <- hub.subscribe
      entered      <- Promise.make[Nothing, Unit]
      release      <- Promise.make[Nothing, Unit]
      delivered    <- Ref.make(0)
      defect = new IllegalStateException("event delivery failed")
      result <- (for
        finish <- AgentEventForwarder.start(
          subscription,
          _ =>
            entered.succeed(()) *> release.await *> delivered.update(_ + 1) *>
              (mode match
                case FinishMode.DeliveryDefect     => ZIO.die(defect)
                case FinishMode.CallerCancellation => ZIO.unit)
        )
        _         <- hub.publish(first)
        _         <- entered.await
        finishing <- finish.forkScoped
        _         <- subscription.awaitShutdown
        result <- mode match
          case FinishMode.DeliveryDefect =>
            for
              _     <- release.succeed(())
              exit  <- finishing.await
              count <- delivered.get
            yield assertTrue(exit.causeOption.contains(Cause.die(defect)), count == 1)
          case FinishMode.CallerCancellation =>
            for
              interrupting <- finishing.interrupt.forkScoped
              _            <- (ZIO.yieldNow *> interrupting.status).repeatUntil(_.isSuspended)
              pending      <- interrupting.poll
              before       <- delivered.get
              _            <- release.succeed(())
              exit         <- interrupting.join
              count        <- delivered.get
            yield assertTrue(
              pending.isEmpty,
              before == 0,
              exit.causeOption.exists(_.isInterruptedOnly),
              count == 1
            )
      yield result).ensuring(release.succeed(()).unit)
    yield result
