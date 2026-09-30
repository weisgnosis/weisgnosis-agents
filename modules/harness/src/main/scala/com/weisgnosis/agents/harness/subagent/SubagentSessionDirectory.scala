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
import java.util.UUID
import zio.*

/** Identifies directory membership, independently of a session's execution IDs. */
final case class SubagentSessionId(value: UUID)

enum SubagentSessionRegistration:
  case Accepted(id: SubagentSessionId)
  case Capacity, Closed, IdentityCollision

enum SubagentSessionRemoval:
  case Removed, Missing

/**
 * Bounded host-controlled membership of already acquired sessions. Registration adds a closure
 * obligation without transferring ownership: original caller scopes and supervisors still apply.
 * Rejection leaves the resource untouched. Aliases are allowed; removing one closes their shared
 * resource. Session workers and finalizers must not close their own directory or remove themselves.
 */
trait SubagentSessionDirectory:
  def register(resource: TaskResource[SubagentSession]): UIO[SubagentSessionRegistration]

  /** Ready membership does not guarantee liveness after an external owner closes the resource. */
  def lookup(id: SubagentSessionId): UIO[Option[SubagentSession]]

  /** All retained IDs, including removals and shutdown still awaiting resource cleanup. */
  def list: UIO[Chunk[SubagentSessionId]]
  def remove(id: SubagentSessionId): UIO[SubagentSessionRemoval]
  def close: UIO[Unit]

object SubagentSessionDirectory:
  private enum Availability:
    case Ready, Removing

  final private case class Entry(
      id: SubagentSessionId,
      resource: TaskResource[SubagentSession],
      availability: Availability
  )

  private enum State:
    case Open(values: Chunk[Entry])
    case Closed(values: Chunk[Entry])

    def entries: Chunk[Entry] = this match
      case Open(values)   => values
      case Closed(values) => values

    def mapEntries(f: Chunk[Entry] => Chunk[Entry]): State = this match
      case Open(values)   => Open(f(values))
      case Closed(values) => Closed(f(values))

  private enum Removal:
    case First(resource: TaskResource[SubagentSession])
    case Join(resource: TaskResource[SubagentSession])
    case Missing

  def open(maxSessions: Int = 64): ZIO[Scope, AgentError, SubagentSessionDirectory] =
    ZIO.uninterruptible:
      for
        _ <- ZIO
          .fail(AgentError.InvalidConfiguration("Subagent session capacity must be positive"))
          .when(maxSessions <= 0)
        parent <- ZIO.scope
        owner  <- TaskResourceOwner.make(parent)
        gate   <- Semaphore.make(1)
        state  <- Ref.make[State](State.Open(Chunk.empty))
        directory = new Live(maxSessions, owner, gate, state)
        _ <- owner.scope.addFinalizer(directory.shutdown)
      yield directory

  final private class Live(
      maxSessions: Int,
      owner: TaskResourceOwner,
      gate: Semaphore,
      state: Ref[State]
  ) extends SubagentSessionDirectory:
    def register(resource: TaskResource[SubagentSession]): UIO[SubagentSessionRegistration] =
      gate.withPermit(ZIO.uninterruptible:
        state.get.flatMap:
          case State.Closed(_) => ZIO.succeed(SubagentSessionRegistration.Closed)
          case State.Open(entries) if entries.size >= maxSessions =>
            ZIO.succeed(SubagentSessionRegistration.Capacity)
          case State.Open(entries) =>
            Random.nextUUID
              .map(SubagentSessionId(_))
              .flatMap: id =>
                if entries.exists(_.id == id) then
                  ZIO.succeed(SubagentSessionRegistration.IdentityCollision)
                else
                  state
                    .set(State.Open(entries :+ Entry(id, resource, Availability.Ready)))
                    .as(SubagentSessionRegistration.Accepted(id)))

    def lookup(id: SubagentSessionId): UIO[Option[SubagentSession]] =
      state.get.map:
        case State.Closed(_) => None
        case State.Open(entries) =>
          entries
            .find(entry => entry.id == id && entry.availability == Availability.Ready)
            .map(_.resource.value)

    def list: UIO[Chunk[SubagentSessionId]] = state.get.map(_.entries.map(_.id))

    def remove(id: SubagentSessionId): UIO[SubagentSessionRemoval] =
      ZIO.uninterruptible:
        gate
          .withPermit(state.modify: current =>
            current.entries.find(_.id == id) match
              case None => (Removal.Missing, current)
              case Some(entry) if entry.availability == Availability.Removing =>
                (Removal.Join(entry.resource), current)
              case Some(entry) =>
                (
                  Removal.First(entry.resource),
                  current.mapEntries(
                    _.map(value =>
                      if value.id == id then value.copy(availability = Availability.Removing)
                      else value
                    )
                  )
                ))
          .flatMap:
            case Removal.Missing        => ZIO.succeed(SubagentSessionRemoval.Missing)
            case Removal.Join(resource) => resource.close.as(SubagentSessionRemoval.Removed)
            case Removal.First(resource) =>
              resource.close
                .ensuring(gate.withPermit(state.update(_.mapEntries(_.filterNot(_.id == id)))))
                .as(SubagentSessionRemoval.Removed)

    def close: UIO[Unit] = owner.close(Exit.unit)

    def shutdown: UIO[Unit] =
      gate
        .withPermit(state.modify(current => (current.entries, State.Closed(current.entries))))
        .flatMap(entries =>
          ZIO.foreachPar(entries)(_.resource.close.exit).flatMap(Exit.collectAllParDiscard)
        )
        .ensuring(state.set(State.Closed(Chunk.empty)))
