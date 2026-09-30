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

import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.model.ToolResultState
import zio.*
import zio.json.ast.Json

/** Immutable registration and existentially typed execution without runtime casts. */
final case class Toolkit(tools: Map[String, Tool[Any, ?, ?]] = Map.empty):
  def register(tool: Tool[Any, ?, ?]): Either[AgentError, Toolkit] =
    val name = tool.definition.name
    if tools.contains(name) then
      Left(AgentError.ToolExecutionError(name, s"Tool collision: '$name' is already registered"))
    else Right(copy(tools = tools.updated(name, tool)))

  def withTool(tool: Tool[Any, ?, ?]): Either[AgentError, Toolkit] = register(tool)

  def ++(other: Toolkit): Either[AgentError, Toolkit] =
    other.tools.values.foldLeft[Either[AgentError, Toolkit]](Right(this)) { (result, tool) =>
      result.flatMap(_.register(tool))
    }

  def resolve(name: String): Option[Tool[Any, ?, ?]] = tools.get(name)
  def contains(name: String): Boolean                = tools.contains(name)
  def definitions: Chunk[ToolDef] =
    Chunk.fromIterable(tools.toList.sortBy(_._1).map(_._2.definition))
  def size: Int        = tools.size
  def isEmpty: Boolean = tools.isEmpty

  /** Unknown names retain legacy dispatch behavior; attached policies travel with tool values. */
  def prepareInput(name: String, input: Json): Either[AgentError.InvalidMessage, Json] =
    tools
      .get(name)
      .fold[Either[AgentError.InvalidMessage, Json]](Right(input))(_.fixedArguments.prepare(input))

  def validateApprovedInput(name: String, input: Json): Either[AgentError.InvalidMessage, Unit] =
    tools
      .get(name)
      .fold[Either[AgentError.InvalidMessage, Unit]](Right(()))(
        _.fixedArguments.validateApproved(input)
      )

  /** Tool failures and defects are observations; interruption remains cancellation. */
  def executeSafe(name: String, rawArgs: Json): UIO[ToolExecutionResult] =
    executeSafe(name, rawArgs, ToolInvocation.Detached)

  def executeSafe(
      name: String,
      rawArgs: Json,
      invocation: ToolInvocation
  ): UIO[ToolExecutionResult] =
    ZIO.suspendSucceed {
      tools.get(name) match
        case None =>
          ZIO.succeed(
            ToolExecutionResult(s"Tool '$name' not found in toolkit.", ToolResultState.Error)
          )
        case Some(tool) => executeTyped(tool, rawArgs, invocation)
    }

  private def executeTyped[In, Out](
      tool: Tool[Any, In, Out],
      input: Json,
      invocation: ToolInvocation
  ): UIO[ToolExecutionResult] =
    ZIO
      .suspendSucceed {
        ZIO
          .fromEither(
            tool.fixedArguments.prepare(input).left.map(_.message).flatMap(tool.decodeInput)
          )
          .mapError(error =>
            AgentError.ToolExecutionError(
              tool.definition.name,
              s"Invalid arguments for '${tool.definition.name}': $error"
            )
          )
          .flatMap(input => tool.executeWithContext(input, invocation))
          .map: output =>
            val result = tool.encodeResult(output)
            result.state match
              case ToolResultState.Success | ToolResultState.Error => result
              case other =>
                ToolExecutionResult(
                  s"Tool '${tool.definition.name}' returned unsupported execution state '$other'; only Success and Error are allowed.",
                  ToolResultState.Error
                )
      }
      .foldCauseZIO(
        cause =>
          if cause.isInterrupted then ZIO.refailCause(cause).orDie
          else
            ZIO.succeed(
              ToolExecutionResult(
                s"Execution failed for '${tool.definition.name}': ${cause.failureOption.orElse(cause.dieOption).map(error => Option(error.getMessage).getOrElse(error.toString)).getOrElse("Interrupted tool execution")}",
                ToolResultState.Error
              )
            )
        ,
        ZIO.succeed(_)
      )

object Toolkit:
  val empty: Toolkit = Toolkit()

  def apply(first: Tool[Any, ?, ?], rest: Tool[Any, ?, ?]*): Either[AgentError, Toolkit] =
    (first +: rest).foldLeft[Either[AgentError, Toolkit]](Right(empty)) { (result, tool) =>
      result.flatMap(_.register(tool))
    }
