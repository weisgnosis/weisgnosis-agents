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
import zio.json.ast.Json

/** Checked host-fixed arguments for raw dispatch; trusted typed execute calls remain unrestricted. */
final class ToolFixedArguments private (fixed: Json.Obj):
  private val fields = fixed.fields.toMap

  def isEmpty: Boolean = fixed.fields.isEmpty

  /** Preserve original field order, replacing fixed values and appending absent fields in fixed order. */
  def prepare(raw: Json): Either[AgentError.InvalidMessage, Json] =
    if isEmpty then Right(raw)
    else
      JsonBoundary
        .validate(raw)
        .left
        .map(AgentError.InvalidMessage.apply)
        .flatMap:
          case input: Json.Obj =>
            val present = input.fields.map(_._1).toSet
            val replaced = input.fields.map { case (name, value) =>
              name -> fields.getOrElse(name, value)
            }
            Right(Json.Obj(replaced ++ fixed.fields.filterNot(field => present.contains(field._1))))
          case _ => Left(AgentError.InvalidMessage("Fixed tool arguments require an object input"))

  /** Authorization may change other fields, but must retain every fixed value exactly. */
  def validateApproved(raw: Json): Either[AgentError.InvalidMessage, Unit] =
    prepare(raw).flatMap(prepared =>
      Either.cond(
        prepared == raw,
        (),
        AgentError.InvalidMessage("Approved tool input changed or removed fixed arguments")
      )
    )

object ToolFixedArguments:
  val empty: ToolFixedArguments = new ToolFixedArguments(Json.Obj())

  def make(fixed: Json.Obj): Either[AgentError.InvalidConfiguration, ToolFixedArguments] =
    JsonBoundary
      .validate(fixed)
      .left
      .map(AgentError.InvalidConfiguration.apply)
      .map(_ => if fixed.fields.isEmpty then empty else new ToolFixedArguments(fixed))
