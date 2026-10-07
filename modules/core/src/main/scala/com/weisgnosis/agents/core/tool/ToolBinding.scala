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
import com.weisgnosis.agents.core.json.JsonBoundary
import zio.json.JsonDecoder
import zio.json.ast.Json

/** Host-owned execution capabilities; a declaration never supplies an executable handler. */
sealed trait ToolBinding:
  def definition: ToolDef
  def fixedArguments: ToolFixedArguments
  def isConcurrencySafe: Boolean
  def isReadOnly: Boolean

object ToolBinding:
  final case class Local(tool: Tool[Any, ?, ?]) extends ToolBinding:
    def definition: ToolDef                = tool.definition
    def fixedArguments: ToolFixedArguments = tool.fixedArguments
    def isConcurrencySafe: Boolean         = tool.isConcurrencySafe
    def isReadOnly: Boolean                = tool.isReadOnly

  final class External private[ToolBinding] (
      val definition: ToolDef,
      private val validate: Json => Either[String, Unit]
  ) extends ToolBinding:
    val fixedArguments: ToolFixedArguments = ToolFixedArguments.empty
    def isConcurrencySafe: Boolean         = definition.isConcurrencySafe
    def isReadOnly: Boolean                = definition.isReadOnly

    private[core] def validateInput(input: Json): Either[AgentError.InvalidMessage, Unit] =
      JsonBoundary
        .validate(input)
        .flatMap(validate)
        .left
        .map(error => AgentError.InvalidMessage(s"Invalid external tool arguments: $error"))

  /** The decoder validates arguments; the schema remains a provider-facing declaration. */
  def external[A: JsonDecoder](
      definition: ToolDef
  ): Either[AgentError.InvalidConfiguration, External] =
    for
      _ <- Either.cond(
        definition.name.trim.nonEmpty,
        (),
        AgentError.InvalidConfiguration("External tool name must not be blank")
      )
      _ <- JsonBoundary
        .validate(definition.parametersSchema)
        .left
        .map(_ => AgentError.InvalidConfiguration("Invalid external tool parameter schema"))
      _ <- definition.outputSchema.fold[Either[AgentError.InvalidConfiguration, Unit]](Right(()))(
        schema =>
          JsonBoundary
            .validate(schema)
            .map(_ => ())
            .left
            .map(_ => AgentError.InvalidConfiguration("Invalid external tool output schema"))
      )
    yield new External(definition, input => summon[JsonDecoder[A]].fromJsonAST(input).map(_ => ()))
