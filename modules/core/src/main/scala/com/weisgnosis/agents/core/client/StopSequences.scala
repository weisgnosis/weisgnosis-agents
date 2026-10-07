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
package com.weisgnosis.agents.core.client

import com.weisgnosis.agents.core.error.AgentError
import zio.Chunk
import zio.json.*
import zio.json.ast.Json

/** Exact provider stop strings; supported values and limits belong to the selected provider. */
opaque type StopSequences = Chunk[String]

object StopSequences:
  def one(first: String, rest: String*): StopSequences =
    Chunk(first) ++ Chunk.fromIterable(rest)

  def fromChunk(values: Chunk[String]): Either[AgentError.InvalidConfiguration, StopSequences] =
    if values.isEmpty then
      Left(AgentError.InvalidConfiguration("Stop sequences must contain at least one value"))
    else Right(values)

  extension (sequences: StopSequences) def values: Chunk[String] = sequences

  /** Validate the array shape and nonempty collection without restricting individual strings. */
  given JsonCodec[StopSequences] = JsonCodec[Json].transformOrFail(
    decode,
    sequences => Json.Arr(sequences.map(Json.Str(_)))
  )

  private def decode(json: Json): Either[String, StopSequences] = json match
    case Json.Arr(entries) =>
      val strings = entries.foldLeft[Either[String, Chunk[String]]](Right(Chunk.empty)):
        case (acc, Json.Str(value)) => acc.map(_ :+ value)
        case (_, _)                 => Left("Stop sequences must contain only strings")
      strings.flatMap(values => fromChunk(values).left.map(_.message))
    case _ => Left("Stop sequences must be an array of strings")
