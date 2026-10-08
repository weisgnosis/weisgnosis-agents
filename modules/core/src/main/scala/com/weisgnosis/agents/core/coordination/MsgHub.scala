/*
 * Copyright 2024-2026 WeisGnosis (https://weisgnosis.com)
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.weisgnosis.agents.core.coordination

import com.weisgnosis.agents.core.agent.{
  PeerDelivery,
  PeerDeliveryOutcome,
  PeerObservation,
  ReActAgent
}
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.model.Msg
import zio.*
import zio.json.*

enum HubMode derives JsonCodec:
  case Automatic, Manual

/** Scoped subscriptions borrow agent runtimes; delivery never invokes their reasoning loops. */
final class MsgHub private (
    val id: String,
    state: Ref[MsgHub.State],
    gate: Semaphore
):
  import MsgHub.State

  def participants: UIO[Chunk[ReActAgent]] = state.get.map:
    case State.Open(members, _) => members
    case State.Closed           => Chunk.empty

  def setMode(mode: HubMode): IO[AgentError, Unit] = admitted:
    open.flatMap(current => state.set(current.copy(mode = mode)))

  /** Adding a participant does not replay history or earlier announcements. */
  def add(agent: ReActAgent): IO[AgentError, Unit] = admitted:
    ZIO.uninterruptible(addMember(agent))

  private def addMember(agent: ReActAgent): IO[AgentError, Unit] =
    for
      current <- open
      _ <- ZIO
        .fail(AgentError.InvalidConfiguration("MsgHub participant limit exceeded"))
        .when(current.members.size >= 64 && !current.members.exists(_.agentId == agent.agentId))
      _ <-
        if current.members.exists(_.agentId == agent.agentId) then ZIO.unit
        else subscribe(agent) *> state.set(current.copy(members = current.members :+ agent))
    yield ()

  private def initialize(participants: Chunk[ReActAgent]): IO[AgentError, Unit] =
    gate.withPermit(
      ZIO
        .foreachDiscard(participants)(agent => ZIO.uninterruptible(addMember(agent)))
        .onExit:
          case Exit.Success(_) => ZIO.unit
          case Exit.Failure(_) => closeOwned
    )

  def remove(agent: ReActAgent): IO[AgentError, Unit] = admitted:
    ZIO.uninterruptible:
      open.flatMap(current =>
        agent.unsubscribePeers(id) *> state.set(
          current.copy(members = current.members.filterNot(_.agentId == agent.agentId))
        )
      )

  /** Explicit delivery includes the sender, matching upstream manual broadcast semantics. */
  def broadcast(sender: ReActAgent, messages: Chunk[Msg]): IO[AgentError, Chunk[PeerDelivery]] =
    admitted:
      for
        current <- open
        _ <- ZIO
          .fail(AgentError.InvalidConfiguration("MsgHub sender is not a participant"))
          .unless(current.members.exists(_.agentId == sender.agentId))
        receipts <- deliver(sender.agentId, sender.name, messages, current.members)
      yield receipts

  def announce(messages: Chunk[Msg]): IO[AgentError, Chunk[PeerDelivery]] = admitted:
    open.flatMap(current => deliver(id, "MsgHub announcement", messages, current.members))

  /** Joins admitted deliveries and removes only this hub's subscriptions. */
  def close: UIO[Unit] = gate.withPermit:
    ZIO.uninterruptible(closeOwned)

  private def closeOwned: UIO[Unit] =
    state
      .getAndSet(State.Closed)
      .flatMap:
        case State.Open(members, _) => ZIO.foreachDiscard(members)(_.unsubscribePeers(id))
        case State.Closed           => ZIO.unit

  private def admitted[A](effect: IO[AgentError, A]): IO[AgentError, A] =
    gate
      .tryWithPermit(effect)
      .someOrFail(AgentError.InvalidConfiguration("MsgHub operation is already active"))

  private def open: IO[AgentError, State.Open] = state.get.flatMap:
    case current: State.Open => ZIO.succeed(current)
    case State.Closed        => ZIO.fail(AgentError.InvalidConfiguration("MsgHub is closed"))

  private def subscribe(agent: ReActAgent): IO[AgentError, Unit] =
    agent.subscribePeers(
      id,
      result =>
        admitted:
          state.get.flatMap:
            case State.Open(members, HubMode.Automatic)
                if members.exists(_.agentId == agent.agentId) =>
              deliver(
                agent.agentId,
                agent.name,
                Chunk(result.reply),
                members.filterNot(_.agentId == agent.agentId)
              )
            case _ => ZIO.succeed(Chunk.empty)
    )

  private def deliver(
      senderId: String,
      senderName: String,
      messages: Chunk[Msg],
      recipients: Chunk[ReActAgent]
  ): IO[AgentError, Chunk[PeerDelivery]] =
    ZIO.fromEither(PeerObservation.validate(senderId, senderName, messages)) *>
      ZIO.foreach(recipients)(recipient =>
        recipient
          .observePeer(senderId, senderName, messages)
          .either
          .map:
            case Right(_) =>
              PeerDelivery(id, Some(recipient.agentId), PeerDeliveryOutcome.Delivered)
            case Left(error) =>
              PeerDelivery(id, Some(recipient.agentId), PeerDeliveryOutcome.Rejected(error.message))
      )

object MsgHub:
  private enum State:
    case Open(members: Chunk[ReActAgent], mode: HubMode)
    case Closed

  def make(
      participants: Chunk[ReActAgent],
      mode: HubMode = HubMode.Automatic
  ): ZIO[Scope, AgentError, MsgHub] =
    for
      _ <- ZIO
        .fail(AgentError.InvalidConfiguration("MsgHub requires 1–64 distinct participants"))
        .unless(
          participants.nonEmpty && participants.size <= 64 &&
            participants.map(_.agentId).distinct.size == participants.size
        )
      id    <- Random.nextUUID.map(_.toString)
      state <- Ref.make[State](State.Open(Chunk.empty, mode))
      gate  <- Semaphore.make(1)
      created = new MsgHub(id, state, gate)
      hub <- ZIO.acquireRelease(
        created
          .initialize(participants)
          .as(created)
          .onExit:
            case Exit.Success(_) => ZIO.unit
            case Exit.Failure(_) => created.close
      )(_.close)
    yield hub
