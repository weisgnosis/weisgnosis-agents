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
import zio.*

/**
 * A finite host-installed set of named sessions. Exact names select one initialized runtime per
 * catalog; different catalogs never share membership. Installation creates no model request.
 * The returned TaskResource owns initialization, sessions and their descendants without consuming
 * another lineage depth. Removed entries remain descriptive but cannot be looked up or recreated.
 */
final class SubagentSessionCatalog private (
    val entries: Chunk[SubagentEntry],
    identities: Map[String, SubagentSessionId],
    directory: SubagentSessionDirectory
):
  /** Resolve membership at observation time; a returned session remains subject to owner closure. */
  def lookup(name: String): UIO[Option[(SubagentSessionId, SubagentSession)]] =
    identities.get(name) match
      case None     => ZIO.none
      case Some(id) => directory.lookup(id).map(_.map(session => (id, session)))

  /** Join the existing resource cleanup; removal never creates a replacement session. */
  def remove(name: String): UIO[SubagentSessionRemoval] =
    identities
      .get(name)
      .fold[UIO[SubagentSessionRemoval]](ZIO.succeed(SubagentSessionRemoval.Missing))(
        directory.remove
      )

object SubagentSessionCatalog:
  def open(
      parent: SubagentSpawner[AgentResult],
      entries: Chunk[SubagentEntry],
      maxSessions: Int = 64,
      maxTrackedPerSession: Int = 256
  ): ZIO[Scope, AgentError, TaskResourceAdmission[SubagentSessionCatalog]] =
    ZIO.fromEither(validate(entries, maxSessions, maxTrackedPerSession)) *>
      parent.ownContext(context =>
        (for
          directory <- SubagentSessionDirectory.index(maxSessions)
          identities <- ZIO.foreach(entries)(entry =>
            SubagentSession
              .openRetained(context, entry.factory, maxTrackedPerSession)
              .flatMap:
                case TaskResourceAdmission.Closed =>
                  ZIO.fail(
                    AgentError
                      .InvalidConfiguration("Session catalog owner closed during installation")
                  )
                case TaskResourceAdmission.Accepted(resource) =>
                  directory
                    .register(resource)
                    .flatMap:
                      case SubagentSessionRegistration.Accepted(id) => ZIO.succeed(entry.name -> id)
                      case rejection =>
                        ZIO.fail(
                          AgentError.InvalidConfiguration(
                            s"Session catalog registration rejected: $rejection"
                          )
                        )
          )
        yield new SubagentSessionCatalog(entries, identities.toMap, directory)).parallelFinalizers
      )

  private def validate(
      entries: Chunk[SubagentEntry],
      capacity: Int,
      taskCapacity: Int
  ): Either[AgentError, Unit] =
    if capacity <= 0 || taskCapacity <= 0 then
      Left(AgentError.InvalidConfiguration("Session catalog capacities must be positive"))
    else if entries.size > capacity then
      Left(AgentError.InvalidConfiguration("Session catalog entry count exceeds capacity"))
    else if entries.exists(entry => entry.name.isBlank || entry.description.isBlank) then
      Left(
        AgentError.InvalidConfiguration("Session catalog names and descriptions must be nonblank")
      )
    else if entries.map(_.name).distinct.size != entries.size then
      Left(AgentError.InvalidConfiguration("Session catalog names must be unique"))
    else Right(())
