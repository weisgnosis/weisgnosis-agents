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
import zio.stream.ZStream

/** One subscriber owns delivery; invoke the returned drain only after all producers have closed. */
private[agent] object AgentEventForwarder:
  def start(
      subscription: Dequeue[AgentEvent],
      publish: AgentEvent => UIO[Unit]
  ): ZIO[Scope, Nothing, UIO[Unit]] =
    for
      producersDone <- Promise.make[Nothing, Unit]
      drained       <- Promise.make[Nothing, Unit]
      forwarder <- ZStream
        .fromQueue(subscription)
        .runForeachChunk(events =>
          (ZIO.foreachDiscard(events)(event => ZIO.suspendSucceed(publish(event))) *>
            producersDone.isDone.flatMap(done =>
              ZIO
                .when(done)(
                  emptyOrClosed(subscription)
                    .flatMap(empty => ZIO.when(empty)(drained.succeed(())).unit)
                )
                .unit
            )).uninterruptible
        )
        .forkScoped
    yield ZIO.uninterruptibleMask: restore =>
      for
        _     <- producersDone.succeed(())
        empty <- subscription.isEmpty
        // Only signal observers race; cancelling their loser must never cancel the sole consumer.
        _ <- ZIO.unless(empty)(
          drained.await.interruptible.raceFirst(forwarder.await.unit.interruptible)
        )
        _ <- subscription.shutdown
        _ <- forwarder.join
        // Honor caller cancellation only after successful cleanup, retaining its original mask.
        _ <- restore(ZIO.unit)
      yield ()

  /** A closed private subscription interrupts size queries; other causes retain their authority. */
  private def emptyOrClosed(subscription: Dequeue[AgentEvent]): UIO[Boolean] =
    subscription.isEmpty.exit.flatMap:
      case Exit.Success(empty) => ZIO.succeed(empty)
      case Exit.Failure(cause) if cause.isInterruptedOnly =>
        subscription.isShutdown.flatMap(closed =>
          if closed then ZIO.succeed(true) else ZIO.refailCause(cause)
        )
      case Exit.Failure(cause) => ZIO.refailCause(cause)
