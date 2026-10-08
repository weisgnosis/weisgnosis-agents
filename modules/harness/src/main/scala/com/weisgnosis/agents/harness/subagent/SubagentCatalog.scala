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

/** Host-selected display identity and fresh-child factory; never decoded from model arguments. */
final case class SubagentEntry(
    name: String,
    description: String,
    factory: SubagentFactory,
    terminalBinding: Option[SubagentTerminalBinding] = None
)

/**
 * Immutable, finite host catalog. Every entry owns an existing scoped registry under one fixed
 * parent; task capacity is per entry, including retained completed records. Closing the acquiring
 * Scope closes those registries in parallel and joins their work. No separate lifecycle, session reuse or
 * dynamic replacement is introduced. Host callbacks must not close their own enclosing Scope.
 */
final class SubagentCatalog private (
    val entries: Chunk[SubagentEntry],
    registries: Map[String, SubagentTaskRegistry]
):
  /** Host access also permits explicit retirement of completed execution records. */
  def lookup(name: String): Option[SubagentTaskRegistry] = registries.get(name)

object SubagentCatalog:
  def open(
      parent: SubagentSpawner[AgentResult],
      entries: Chunk[SubagentEntry],
      maxTrackedPerAgent: Int = 256
  ): ZIO[Scope, AgentError, SubagentCatalog] =
    for
      _ <- ZIO.fromEither(validate(entries, maxTrackedPerAgent))
      registries <- ZIO
        .foreach(entries)(entry =>
          val acquired = entry.terminalBinding match
            case None => SubagentTaskRegistry.open(parent, entry.factory, maxTrackedPerAgent)
            case Some(binding) =>
              SubagentTaskRegistry.openDurable(
                parent,
                entry.factory,
                binding.store,
                maxTrackedPerAgent,
                binding.workerId
              )
          acquired.map(entry.name -> _)
        )
        .parallelFinalizers
    yield new SubagentCatalog(entries, registries.toMap)

  private def validate(entries: Chunk[SubagentEntry], capacity: Int): Either[AgentError, Unit] =
    if capacity <= 0 then
      Left(AgentError.InvalidConfiguration("Subagent catalog task capacity must be positive"))
    else if entries.exists(entry => entry.name.isBlank || entry.description.isBlank) then
      Left(
        AgentError.InvalidConfiguration("Subagent catalog names and descriptions must be nonblank")
      )
    else if entries.map(_.name).distinct.size != entries.size then
      Left(AgentError.InvalidConfiguration("Subagent catalog names must be unique"))
    else Right(())
