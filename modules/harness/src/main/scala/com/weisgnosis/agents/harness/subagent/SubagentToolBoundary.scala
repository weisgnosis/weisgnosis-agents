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
import com.weisgnosis.agents.core.tool.Toolkit
import zio.json.*

/** Exact names only. An empty Only set denies every tool; AllAvailable expresses inheritance. */
enum SubagentToolSelection derives JsonCodec:
  case AllAvailable
  case Only(names: Set[String])

  def intersect(other: SubagentToolSelection): SubagentToolSelection = (this, other) match
    case (SubagentToolSelection.AllAvailable, right) => right
    case (left, SubagentToolSelection.AllAvailable)  => left
    case (SubagentToolSelection.Only(left), SubagentToolSelection.Only(right)) =>
      SubagentToolSelection.Only(left.intersect(right))

  def contains(name: String): Boolean = this match
    case SubagentToolSelection.AllAvailable => true
    case SubagentToolSelection.Only(names)  => names.contains(name)

/** Static capability selection, independent of argument-sensitive execution authorization. */
final case class SubagentToolBoundary(
    selection: SubagentToolSelection = SubagentToolSelection.AllAvailable,
    denied: Set[String] = Set.empty
) derives JsonCodec:
  def narrow(child: SubagentToolBoundary): SubagentToolBoundary =
    SubagentToolBoundary(selection.intersect(child.selection), denied ++ child.denied)

  def assemble(inherited: Toolkit, registered: Toolkit): Either[AgentError, Toolkit] =
    for
      _ <- validateNames(inherited)
      _ <- validateNames(registered)
      combined <- registered.tools.toList
        .sortBy(_._1)
        .foldLeft[Either[AgentError, Toolkit]](Right(inherited)) { case (assembled, (_, tool)) =>
          assembled.flatMap(_.register(tool))
        }
    yield Toolkit(combined.tools.filter { case (name, _) =>
      selection.contains(name) && !denied.contains(name)
    })

  private def validateNames(toolkit: Toolkit): Either[AgentError, Unit] =
    toolkit.tools.toList.sortBy(_._1).foldLeft[Either[AgentError, Unit]](Right(())) {
      case (checked, (key, tool)) =>
        checked.flatMap: _ =>
          Either.cond(
            key == tool.definition.name,
            (),
            AgentError.InvalidConfiguration(
              s"Toolkit key '$key' does not match its declared tool name '${tool.definition.name}'"
            )
          )
    }
