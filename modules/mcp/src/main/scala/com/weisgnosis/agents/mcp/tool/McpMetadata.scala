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
import zio.Chunk
import zio.json.ast.Json

/** Checked host metadata composition; later objects replace whole top-level values. */
object McpMetadata:
  def merge(sources: Chunk[Json.Obj]): Either[AgentError.InvalidMessage, Json.Obj] =
    sources
      .foldLeft[Either[AgentError.InvalidMessage, Chunk[(String, Json)]]](Right(Chunk.empty)):
        case (result, source) =>
          for
            fields <- result
            _      <- JsonBoundary.validate(source).left.map(AgentError.InvalidMessage.apply)
            names = source.fields.map(_._1).toSet
          yield fields.filterNot((name, _) => names.contains(name)) ++ source.fields
      .map(fields => Json.Obj(fields))
