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
package com.weisgnosis.agents.harness.subagent

import com.weisgnosis.agents.core.error.AgentError
import zio.*
import zio.stm.*

enum SubagentInboxPresence:
  case Empty, Pending

enum SubagentInboxArrival:
  case Awaiting, Arrived

/** A latched arrival observation, not a message delivery or a guarantee the inbox remains pending. */
final class SubagentInboxSnapshot private[subagent] (
    val presence: SubagentInboxPresence,
    private val arrival: IO[AgentError, Unit],
    private val observedArrival: IO[AgentError, SubagentInboxArrival]
):
  def awaitArrival: IO[AgentError, Unit]                 = arrival
  def arrivalState: IO[AgentError, SubagentInboxArrival] = observedArrival

object SubagentInboxSnapshot:
  /**
   * Host adapters must atomically capture presence and a monotonic arrival latch. Arrival polling
   * and awaiting must share that latch; clearing the host inbox must not revoke captured arrival.
   */
  def awaiting(
      awaitArrival: => IO[AgentError, Unit],
      arrivalState: => IO[AgentError, SubagentInboxArrival]
  ): SubagentInboxSnapshot = new SubagentInboxSnapshot(
    SubagentInboxPresence.Empty,
    ZIO.suspendSucceed(awaitArrival),
    ZIO.suspendSucceed(arrivalState)
  )

  val pending: SubagentInboxSnapshot = new SubagentInboxSnapshot(
    SubagentInboxPresence.Pending,
    ZIO.unit,
    ZIO.succeed(SubagentInboxArrival.Arrived)
  )

/** The host owns inbox contents and synchronizes presence updates with its actual transport. */
trait SubagentInbox:
  def snapshot: IO[AgentError, SubagentInboxSnapshot]

final class SubagentInboxSignal private (
    state: TRef[SubagentInboxSignal.State]
) extends SubagentInbox:
  import SubagentInboxSignal.*

  def snapshot: IO[AgentError, SubagentInboxSnapshot] =
    state.get
      .flatMap:
        case State.Empty(latch) =>
          STM.succeed(
            SubagentInboxSnapshot.awaiting(
              latch.await.commit,
              latch.poll
                .flatMap:
                  case None              => STM.succeed(SubagentInboxArrival.Awaiting)
                  case Some(Right(_))    => STM.succeed(SubagentInboxArrival.Arrived)
                  case Some(Left(error)) => STM.fail(error)
                .commit
            )
          )
        case State.Pending => STM.succeed(SubagentInboxSnapshot.pending)
        case State.Closed  => STM.fail(closed)
      .commit

  /** Setting Empty rearms future snapshots; already captured arrival latches remain completed. */
  def setPresence(presence: SubagentInboxPresence): IO[AgentError, Unit] =
    state.get
      .flatMap:
        case State.Closed => STM.fail(closed)
        case State.Empty(latch) =>
          presence match
            case SubagentInboxPresence.Empty   => STM.unit
            case SubagentInboxPresence.Pending => latch.succeed(()).unit *> state.set(State.Pending)
        case State.Pending =>
          presence match
            case SubagentInboxPresence.Pending => STM.unit
            case SubagentInboxPresence.Empty =>
              TPromise.make[AgentError, Unit].flatMap(latch => state.set(State.Empty(latch)))
      .commit

  def close: UIO[Unit] =
    state.get
      .flatMap:
        case State.Empty(latch) => state.set(State.Closed) *> latch.fail(closed).unit
        case _                  => state.set(State.Closed)
      .commit

object SubagentInboxSignal:
  private enum State:
    case Empty(latch: TPromise[AgentError, Unit])
    case Pending, Closed
  private val closed: AgentError.InvalidConfiguration =
    AgentError.InvalidConfiguration("Async inbox signal is closed")

  def open: ZIO[Scope, Nothing, SubagentInboxSignal] =
    ZIO.acquireRelease(
      (for
        latch <- TPromise.make[AgentError, Unit]
        state <- TRef.make[State](State.Empty(latch))
      yield new SubagentInboxSignal(state)).commit
    )(_.close)
