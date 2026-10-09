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
import com.weisgnosis.agents.core.json.ExactJson
import com.weisgnosis.agents.core.model.Msg
import zio.*
import zio.json.*

enum HubMode derives JsonCodec:
  case Automatic, Manual

/** A queued observation is data awaiting delivery, not a completed recipient turn. */
final case class PendingPeerDelivery(
    id: String,
    recipientId: String,
    senderId: String,
    senderName: String,
    messages: Chunk[Msg]
) derives JsonCodec

/** Scoped subscriptions borrow agent runtimes; delivery never invokes their reasoning loops. */
final class MsgHub private (
    val id: String,
    state: Ref[MsgHub.State],
    gate: Semaphore
):
  import MsgHub.State

  def participants: UIO[Chunk[ReActAgent]] = state.get.map:
    case State.Open(members, _, _) => members
    case State.Closed(_)           => Chunk.empty

  def pendingDeliveries: UIO[Chunk[PendingPeerDelivery]] = state.get.map:
    case State.Open(_, _, pending) => pending
    case State.Closed(pending)     => pending

  /** Explicitly transfers queued data to the host, including after scope closure. */
  def takePending: UIO[Chunk[PendingPeerDelivery]] = gate.withPermit:
    state.modify:
      case current @ State.Open(_, _, pending) => (pending, current.copy(pending = Chunk.empty))
      case State.Closed(pending)               => (pending, State.Closed(Chunk.empty))

  /** Call between rounds; no worker waits for an active or suspended recipient. */
  def flush: IO[AgentError, Chunk[PeerDelivery]] = admitted(flushPending)

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
      .modify:
        case State.Open(members, _, pending) => (members, State.Closed(pending))
        case current: State.Closed           => (Chunk.empty, current)
      .flatMap(members => ZIO.foreachDiscard(members)(_.unsubscribePeers(id)))

  private def admitted[A](effect: IO[AgentError, A]): IO[AgentError, A] =
    gate.withPermit(effect)

  private def open: IO[AgentError, State.Open] = state.get.flatMap:
    case current: State.Open => ZIO.succeed(current)
    case State.Closed(_)     => ZIO.fail(AgentError.InvalidConfiguration("MsgHub is closed"))

  private def subscribe(agent: ReActAgent): IO[AgentError, Unit] =
    agent.subscribePeers(
      id,
      result =>
        admitted:
          state.get.flatMap:
            case State.Open(members, HubMode.Automatic, _)
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
      flushPending *> ZIO.foreach(recipients)(recipient =>
        ZIO.uninterruptible:
          open.flatMap(current =>
            if current.pending.exists(_.recipientId == recipient.agentId) then
              enqueue(senderId, senderName, messages, recipient.agentId)
            else
              recipient
                .tryObservePeer(senderId, senderName, messages)
                .either
                .flatMap:
                  case Right(Some(_)) =>
                    ZIO.succeed(receipt(recipient.agentId, PeerDeliveryOutcome.Delivered))
                  case Right(None) => enqueue(senderId, senderName, messages, recipient.agentId)
                  case Left(error) =>
                    ZIO.succeed(
                      receipt(recipient.agentId, PeerDeliveryOutcome.Rejected(error.message))
                    )
          )
      )

  private def receipt(recipientId: String, outcome: PeerDeliveryOutcome): PeerDelivery =
    PeerDelivery(id, Some(recipientId), outcome)

  private def enqueue(
      senderId: String,
      senderName: String,
      messages: Chunk[Msg],
      recipientId: String
  ): IO[AgentError, PeerDelivery] =
    for
      current <- open
      key     <- Random.nextUUID.map(_.toString)
      entry     = PendingPeerDelivery(key, recipientId, senderId, senderName, messages)
      candidate = current.pending :+ entry
      encoded <- ZIO.fromEither(ExactJson.encode(candidate))
      result <-
        if current.pending.exists(_.id == key) then
          ZIO.succeed(
            receipt(
              recipientId,
              PeerDeliveryOutcome.Rejected("MsgHub pending delivery identity collision")
            )
          )
        else if candidate.foldLeft(0L)((count, item) =>
            count + item.messages.size
          ) > 64 || encoded.length > 1000000
        then
          ZIO.succeed(
            receipt(
              recipientId,
              PeerDeliveryOutcome.Rejected("MsgHub pending delivery limit exceeded")
            )
          )
        else
          state
            .set(current.copy(pending = candidate))
            .as(receipt(recipientId, PeerDeliveryOutcome.Queued))
    yield result

  private def flushPending: IO[AgentError, Chunk[PeerDelivery]] =
    open.flatMap(current =>
      ZIO.foreach(current.pending)(entry =>
        ZIO.uninterruptible:
          open.flatMap(latest =>
            if latest.pending.takeWhile(_.id != entry.id).exists(_.recipientId == entry.recipientId)
            then ZIO.succeed(receipt(entry.recipientId, PeerDeliveryOutcome.Queued))
            else
              latest.members.find(_.agentId == entry.recipientId) match
                case None =>
                  ZIO.succeed(
                    receipt(
                      entry.recipientId,
                      PeerDeliveryOutcome.Rejected("MsgHub recipient is no longer a participant")
                    )
                  )
                case Some(recipient) =>
                  recipient
                    .tryObservePeer(entry.senderId, entry.senderName, entry.messages)
                    .either
                    .flatMap:
                      case Right(Some(_)) =>
                        state
                          .set(latest.copy(pending = latest.pending.filterNot(_.id == entry.id)))
                          .as(receipt(entry.recipientId, PeerDeliveryOutcome.Delivered))
                      case Right(None) =>
                        ZIO.succeed(receipt(entry.recipientId, PeerDeliveryOutcome.Queued))
                      case Left(error) =>
                        ZIO.succeed(
                          receipt(entry.recipientId, PeerDeliveryOutcome.Rejected(error.message))
                        )
          )
      )
    )

object MsgHub:
  private enum State:
    case Open(members: Chunk[ReActAgent], mode: HubMode, pending: Chunk[PendingPeerDelivery])
    case Closed(pending: Chunk[PendingPeerDelivery])

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
      state <- Ref.make[State](State.Open(Chunk.empty, mode, Chunk.empty))
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
