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

import com.weisgnosis.agents.core.agent.AgentResult
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.harness.storage.StorageAddress
import zio.*

/**
 * Bounded keyed runtime ownership; failed admission occupies a slot until explicit retirement.
 * Retirement joins the old runtime/claim before permitting a new generation. Failed snapshot
 * persistence likewise requires explicit retirement/reopen, never automatic command replay.
 */
final class SubagentNamedSessions private (
    val parentSessionId: String,
    val entries: Chunk[SubagentEntry],
    parent: SubagentSpawner[AgentResult],
    store: SubagentNamedSessionStore,
    authority: SubagentNamedSessions.Authority,
    directory: SubagentSessionDirectory,
    state: Ref.Synchronized[SubagentNamedSessions.State],
    maxSessions: Int,
    maxTracked: Int
):
  import SubagentNamedSessions.*

  def getOrOpen(agentId: String, label: String): IO[AgentError, SubagentSession] =
    ZIO.uninterruptibleMask: restore =>
      for
        key <- ZIO.fromEither(SubagentNamedSessionKey.from(parentSessionId, agentId, label))
        entry <- ZIO
          .fromOption(entries.find(_.name == agentId))
          .orElseFail(AgentError.InvalidConfiguration("Unknown named session agent"))
        _ <- ZIO
          .fail(AgentError.InvalidConfiguration("PRIMARY-only agent cannot be spawned"))
          .unless(entry.spawnable)
        admission <- state.modifyZIO:
          case State.Closed(_) => ZIO.fail(closed)
          case current @ State.Open(values) =>
            values.get(key) match
              case Some(Slot.Opening(promise))  => ZIO.succeed((Admission.Join(promise), current))
              case Some(Slot.Ready(promise, _)) => ZIO.succeed((Admission.Join(promise), current))
              case Some(Slot.Failed(promise))   => ZIO.succeed((Admission.Join(promise), current))
              case Some(Slot.Retiring) =>
                ZIO.fail(AgentError.InvalidConfiguration("Named session is retiring"))
              case None if values.size >= maxSessions =>
                ZIO.fail(AgentError.InvalidConfiguration("Named session capacity reached"))
              case None =>
                Promise
                  .make[AgentError, SubagentSession]
                  .map(promise =>
                    (
                      Admission.Initialize(promise),
                      State.Open(values.updated(key, Slot.Opening(promise)))
                    )
                  )
        session <- admission match
          case Admission.Join(promise) => restore(promise.await)
          case Admission.Initialize(promise) =>
            restore(initialize(key, entry)).exit.flatMap: outcome =>
              state
                .modify:
                  case State.Open(values) =>
                    outcome match
                      case Exit.Success((session, id)) =>
                        (
                          Exit.succeed(session),
                          State.Open(values.updated(key, Slot.Ready(promise, id)))
                        )
                      case Exit.Failure(cause) =>
                        (
                          Exit.failCause(cause),
                          State.Open(values.updated(key, Slot.Failed(promise)))
                        )
                  case current @ State.Closed(_) =>
                    val failure = outcome match
                      case Exit.Failure(cause) => Exit.failCause(cause ++ Cause.fail(closed))
                      case Exit.Success(_)     => Exit.fail(closed)
                    (failure, current)
                .flatMap(result => promise.done(result) *> result)
      yield session

  /** Joins the old runtime and releases its host claim before a later getOrOpen can hydrate again. */
  def retire(agentId: String, label: String): IO[AgentError, Unit] =
    ZIO.uninterruptible:
      for
        key <- ZIO.fromEither(SubagentNamedSessionKey.from(parentSessionId, agentId, label))
        selected <- state.modifyZIO:
          case State.Closed(_) => ZIO.fail(closed)
          case current @ State.Open(values) =>
            values.get(key) match
              case Some(Slot.Opening(_)) | Some(Slot.Retiring) =>
                ZIO.fail(
                  AgentError.InvalidConfiguration("Named session admission or retirement is active")
                )
              case Some(Slot.Ready(_, id)) =>
                ZIO.succeed((Some(id), State.Open(values.updated(key, Slot.Retiring))))
              case Some(Slot.Failed(_)) => ZIO.succeed((None, State.Open(values - key)))
              case None                 => ZIO.succeed((None, current))
        _ <- ZIO.foreachDiscard(selected)(directory.remove)
        _ <- state.update:
          case State.Open(values) => State.Open(values - key)
          case closed             => closed
      yield ()

  private def initialize(
      key: SubagentNamedSessionKey,
      entry: SubagentEntry
  ): IO[AgentError, (SubagentSession, SubagentSessionId)] =
    ZIO.uninterruptibleMask: restore =>
      restore(
        SubagentSession.openPersistentRetained(
          parent,
          entry.factory,
          key,
          store,
          authority,
          maxTracked,
          entry.terminalBinding
        )
      ).flatMap:
        case TaskResourceAdmission.Closed => ZIO.fail(closed)
        case TaskResourceAdmission.Accepted(resource) =>
          directory
            .register(resource)
            .flatMap:
              case SubagentSessionRegistration.Accepted(id) => ZIO.succeed((resource.value, id))
              case rejection =>
                resource.close *> ZIO.fail(
                  AgentError.InvalidConfiguration(
                    s"Named session registration rejected: $rejection"
                  )
                )

  private def closeAdmissions: UIO[Unit] =
    state
      .getAndUpdate:
        case State.Open(values) => State.Closed(values)
        case closed             => closed
      .flatMap(current =>
        ZIO.foreachDiscard(current.slots.values):
          case Slot.Opening(promise) => promise.fail(closed).unit
          case _                     => ZIO.unit
      )

object SubagentNamedSessions:
  /** Host-exclusive claim for this exact registration/address, retained throughout runtime lifetime. */
  trait Authority:
    def acquire(key: SubagentNamedSessionKey, address: StorageAddress): ZIO[Scope, AgentError, Unit]

  private val closed: AgentError.InvalidConfiguration =
    AgentError.InvalidConfiguration("Named session owner is closed")
  private enum Slot:
    case Opening(promise: Promise[AgentError, SubagentSession])
    case Ready(promise: Promise[AgentError, SubagentSession], id: SubagentSessionId)
    case Failed(promise: Promise[AgentError, SubagentSession])
    case Retiring
  private enum Admission:
    case Join(promise: Promise[AgentError, SubagentSession])
    case Initialize(promise: Promise[AgentError, SubagentSession])
  private enum State:
    case Open(values: Map[SubagentNamedSessionKey, Slot])
    case Closed(values: Map[SubagentNamedSessionKey, Slot])
    def slots: Map[SubagentNamedSessionKey, Slot] = this match
      case Open(values)   => values
      case Closed(values) => values

  def open(
      parent: SubagentSpawner[AgentResult],
      parentSessionId: String,
      entries: Chunk[SubagentEntry],
      store: SubagentNamedSessionStore,
      authority: Authority,
      maxSessions: Int = 64,
      maxTrackedPerSession: Int = 256
  ): ZIO[Scope, AgentError, TaskResourceAdmission[SubagentNamedSessions]] =
    for
      _ <- ZIO.fromEither(SubagentNamedSessionKey.from(parentSessionId, "validation", "validation"))
      _ <- ZIO
        .fail(
          AgentError.InvalidConfiguration(
            "Named session entries and capacities must be bounded and unique"
          )
        )
        .unless(
          maxSessions > 0 && maxTrackedPerSession > 0 && entries
            .map(_.name)
            .distinct
            .size == entries.size && entries
            .forall(entry => !entry.name.isBlank && !entry.description.isBlank)
        )
      resource <- parent.ownContext(context =>
        for
          directory <- SubagentSessionDirectory.index(maxSessions)
          state     <- Ref.Synchronized.make[State](State.Open(Map.empty))
          sessions = new SubagentNamedSessions(
            parentSessionId,
            entries,
            context,
            store,
            authority,
            directory,
            state,
            maxSessions,
            maxTrackedPerSession
          )
          _ <- ZIO.addFinalizer(sessions.closeAdmissions)
        yield sessions
      )
    yield resource
