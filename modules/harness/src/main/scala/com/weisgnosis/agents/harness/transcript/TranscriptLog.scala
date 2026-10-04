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
package com.weisgnosis.agents.harness.transcript

import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.json.JsonBoundary
import zio.Chunk
import zio.json.*
import zio.json.ast.Json

/** Versioned private disk rows; strict decoding never skips corrupt or ambiguous observations. */
private[transcript] object TranscriptLog:
  final private case class Row(version: Int, entry: TranscriptEntry) derives JsonCodec

  def encode(state: TranscriptState): Either[AgentError.MemoryStorageError, String] =
    state.entries
      .foldLeft[Either[AgentError.MemoryStorageError, Chunk[String]]](Right(Chunk.empty)):
        case (result, entry) =>
          for
            rows <- result
            json <- Row(1, entry).toJsonAST.left.map(AgentError.MemoryStorageError.apply)
          yield rows :+ JsonBoundary.render(json)
      .map(_.mkString("", "\n", if state.entries.isEmpty then "" else "\n"))

  def decode(text: String): Either[AgentError, TranscriptState] =
    if text.isEmpty then Right(TranscriptState.empty)
    else
      val rows = text.stripSuffix("\n").split("\n", -1).toList.map(_.stripSuffix("\r"))
      rows
        .foldLeft[Either[AgentError, Chunk[TranscriptEntry]]](Right(Chunk.empty)):
          case (result, line) =>
            for
              entries <- result
              entry   <- decodeRow(line)
            yield entries :+ entry
        .flatMap(TranscriptState.restore)

  private def decodeRow(line: String): Either[AgentError, TranscriptEntry] =
    for
      json      <- JsonBoundary.decode(line).left.map(AgentError.MemoryStorageError.apply)
      row       <- json.as[Row].left.map(AgentError.MemoryStorageError.apply)
      canonical <- row.toJsonAST.left.map(AgentError.MemoryStorageError.apply)
      _ <- Either.cond(
        row.version == 1 && sameRepresentation(json, canonical),
        (),
        AgentError.MemoryStorageError("Unsupported or noncanonical transcript row")
      )
    yield row.entry

  /**
   * Prevents coercions and ignored fields; object ordering and numeric formatting are immaterial.
   * Optional fields must use the writer's omitted-field representation rather than explicit nulls.
   */
  private def sameRepresentation(actual: Json, expected: Json): Boolean = (actual, expected) match
    case (Json.Obj(left), Json.Obj(right)) =>
      val values = right.toMap
      left.size == right.size && left.forall((key, value) =>
        values.get(key).exists(sameRepresentation(value, _))
      )
    case (Json.Arr(left), Json.Arr(right)) =>
      left.size == right.size && left.zip(right).forall((a, b) => sameRepresentation(a, b))
    case (Json.Num(left), Json.Num(right)) => left.compareTo(right) == 0
    case _                                 => actual == expected
