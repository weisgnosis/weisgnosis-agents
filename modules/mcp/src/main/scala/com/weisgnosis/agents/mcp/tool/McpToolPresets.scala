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
package com.weisgnosis.agents.mcp.tool

import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.json.JsonBoundary
import com.weisgnosis.agents.core.tool.ToolDef
import zio.json.ast.Json

/**
 * Validated model-overridable host defaults, not constraints or secret arguments. Merge occurs after
 * authorization of the supplied/approved tool input, before defaults expand the remote payload.
 */
final class McpToolPresets private (defaults: Json.Obj):
  private val keys: Set[String] = defaults.fields.map(_._1).toSet

  /** Validate all original input before a shallow merge; supplied values, including JSON null, win. */
  def merge(input: Json.Obj): Either[AgentError.InvalidMessage, Json.Obj] =
    JsonBoundary
      .validate(input)
      .left
      .map(AgentError.InvalidMessage(_))
      .map: _ =>
        val supplied = input.fields.map(_._1).toSet
        Json.Obj(defaults.fields.filterNot(field => supplied.contains(field._1)) ++ input.fields)

  /**
   * Remove defaulted top-level properties/required names from a structurally checked object schema.
   * Preserve all other fields and ordering without evaluating schemas, references or dependencies.
   */
  def project(host: ToolDef): Either[AgentError.InvalidConfiguration, ToolDef] =
    (for
      _ <- JsonBoundary.validate(host.parametersSchema)
      schema <- host.parametersSchema match
        case value: Json.Obj => Right(value)
        case _               => Left("MCP preset schema must be an object")
      _ <- Either.cond(
        schema.get("type").contains(Json.Str("object")),
        (),
        "MCP preset schema.type must be object"
      )
      _ <- schema.get("properties") match
        case None | Some(_: Json.Obj) => Right(())
        case _                        => Left("MCP preset schema.properties must be an object")
      _ <- schema.get("required") match
        case None                                                          => Right(())
        case Some(Json.Arr(values)) if values.forall(_.asString.isDefined) => Right(())
        case _ => Left("MCP preset schema.required must be an array of strings")
    yield host.copy(parametersSchema = Json.Obj(schema.fields.map:
      case ("properties", value: Json.Obj) =>
        "properties" -> Json.Obj(value.fields.filterNot(field => keys.contains(field._1)))
      case ("required", Json.Arr(values)) =>
        "required" -> Json.Arr(values.filterNot(_.asString.exists(keys.contains)))
      case field => field))).left.map(AgentError.InvalidConfiguration(_))

object McpToolPresets:
  val empty: McpToolPresets = new McpToolPresets(Json.Obj())

  def make(defaults: Json.Obj): Either[AgentError.InvalidConfiguration, McpToolPresets] =
    JsonBoundary
      .validate(defaults)
      .left
      .map(AgentError.InvalidConfiguration(_))
      .map(_ => new McpToolPresets(defaults))
