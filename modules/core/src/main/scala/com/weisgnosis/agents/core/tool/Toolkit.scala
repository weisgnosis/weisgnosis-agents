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
package com.weisgnosis.agents.core.tool

import com.weisgnosis.agents.core.model.ToolResultState
import zio.*
import zio.json.ast.Json

/** Immutable, type-safe registry of tools available to an agent.
  *
  * Corresponds to `io.agentscope.core.tool.Toolkit` in AgentScope Java.
  */
final case class Toolkit(
  tools: Map[String, Tool[Any, ?, ?]] = Map.empty
):

  /** Registers a tool, returning an error if a tool with the same name already exists. */
  def register(tool: Tool[Any, ?, ?]): Either[String, Toolkit] =
    if tools.contains(tool.definition.name) then
      Left(s"Tool collision: tool '${tool.definition.name}' is already registered in this toolkit.")
    else
      Right(copy(tools = tools + (tool.definition.name -> tool)))

  /** Chaining registration for static configuration; rejects duplicate names. */
  def withTool(tool: Tool[Any, ?, ?]): Toolkit =
    register(tool) match
      case Right(tk) => tk
      case Left(err) => throw new IllegalArgumentException(err)

  /** Merges another toolkit into this one. */
  def ++(other: Toolkit): Toolkit =
    copy(tools = tools ++ other.tools)

  /** Retrieves a tool by its registered name. */
  def get(name: String): Option[Tool[Any, ?, ?]] =
    tools.get(name)

  /** Checks if a tool with the given name is registered. */
  def contains(name: String): Boolean =
    tools.contains(name)

  /** Returns all tool definitions in this toolkit. */
  def definitions: Chunk[ToolDef] =
    Chunk.fromIterable(tools.values.map(_.definition))

  /** Number of registered tools. */
  def size: Int = tools.size

  /** Whether the toolkit contains zero tools. */
  def isEmpty: Boolean = tools.isEmpty

  /** Safely executes a tool by name with raw JSON arguments.
    * Never throws; failures and invalid arguments are captured into `ToolExecutionResult`
    * with `ToolResultState.Error` so the model can inspect error messages.
    */
  def executeSafe(name: String, rawArgs: Json): ZIO[Any, Nothing, ToolExecutionResult] =
    tools.get(name) match
      case None =>
        ZIO.succeed(
          ToolExecutionResult(
            output = s"Tool '$name' not found in toolkit.",
            state = ToolResultState.Error
          )
        )

      case Some(toolUntyped) =>
        val tool = toolUntyped.asInstanceOf[Tool[Any, Any, Any]]
        tool.decodeInput(rawArgs) match
          case Left(decodeErr) =>
            ZIO.succeed(
              ToolExecutionResult(
                output = s"Invalid arguments for '$name': $decodeErr",
                state = ToolResultState.Error
              )
            )

          case Right(typedInput) =>
            tool
              .execute(typedInput)
              .map(out => ToolExecutionResult(tool.encodeOutput(out), ToolResultState.Success))
              .catchAll(err =>
                ZIO.succeed(
                  ToolExecutionResult(
                    output = s"Execution failed for '$name': ${err.getMessage}",
                    state = ToolResultState.Error
                  )
                )
              )

object Toolkit:

  val empty: Toolkit = Toolkit()

  def apply(first: Tool[Any, ?, ?], rest: Tool[Any, ?, ?]*): Toolkit =
    (first +: rest).foldLeft(Toolkit.empty)(_.withTool(_))
