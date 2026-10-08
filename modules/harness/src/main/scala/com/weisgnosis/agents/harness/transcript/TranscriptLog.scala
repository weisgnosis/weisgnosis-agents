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
import java.nio.charset.StandardCharsets
import zio.Chunk
import zio.json.*
import zio.json.ast.Json

/** Versioned private disk rows; strict decoding never skips corrupt or ambiguous observations. */
private[transcript] object TranscriptLog:
  final private case class Row(version: Int, entry: TranscriptEntry) derives JsonCodec

  /** Recovery remains a forest of individually strict chains, never a fabricated linear union. */
  enum Document:
    case Linear(local: TranscriptState)
    case Recovered(local: TranscriptState, branches: Chunk[Chunk[TranscriptEntry]])

    def state: TranscriptState = this match
      case Linear(local)       => local
      case Recovered(local, _) => local

    def archives: Chunk[Chunk[TranscriptEntry]] = this match
      case Linear(_)              => Chunk.empty
      case Recovered(_, branches) => branches

    def entries: Chunk[TranscriptEntry] =
      (archives.flatten ++ state.entries).foldLeft(Chunk.empty[TranscriptEntry]): (result, entry) =>
        if result.exists(_.id == entry.id) then result else result :+ entry

    def withState(next: TranscriptState): Document = this match
      case Linear(_)              => Linear(next)
      case Recovered(_, branches) => Recovered(next, branches)

  private val maxBranches: Int = 16
  private val maxEntries: Int  = 4096
  private val maxBytes: Int    = 1048576
  final private case class Recovery(
      version: Int,
      localContinuation: Chunk[TranscriptEntry],
      remoteBranches: Chunk[Chunk[TranscriptEntry]]
  ) derives JsonCodec

  def reconcile(current: Document, remote: TranscriptState): Either[AgentError, Document] =
    val local    = current.state
    val selected = if remote.entries.startsWith(local.entries) then remote else local
    val divergent =
      !remote.entries.startsWith(local.entries) && !local.entries.startsWith(remote.entries)
    val next =
      if current.archives.isEmpty && !divergent then Document.Linear(selected)
      else Document.Recovered(selected, (Chunk(remote.entries) ++ current.archives).distinct)
    validate(next).map(_ => next)

  /** Bound remote admission without changing ordinary legacy local append capacity. */
  def encodeHandoff(document: Document): Either[AgentError, String] =
    encodeDocument(document).flatMap: text =>
      Either.cond(
        text.getBytes(StandardCharsets.UTF_8).length <= maxBytes,
        text,
        AgentError.MemoryStorageError("Transcript handoff encoded byte bound exceeded")
      )

  def encodeDocument(document: Document): Either[AgentError, String] = document match
    case Document.Linear(state) => encode(state)
    case Document.Recovered(state, branches) =>
      for
        _ <- validate(document)
        value <- Recovery(2, state.entries, branches).toJsonAST.left
          .map(AgentError.MemoryStorageError.apply)
        _ <- JsonBoundary.validate(value).left.map(AgentError.MemoryStorageError.apply)
        _ <- Either.cond(
          JsonBoundary.validUnicode(value),
          (),
          AgentError.MemoryStorageError("Invalid recovered transcript Unicode")
        )
        text = JsonBoundary.render(value) + "\n"
        _ <- Either.cond(
          text.getBytes(StandardCharsets.UTF_8).length <= maxBytes,
          (),
          AgentError.MemoryStorageError("Recovered transcript encoded byte bound exceeded")
        )
      yield text

  def decodeDocument(text: String): Either[AgentError, Document] =
    val single = text.stripSuffix("\n")
    val recovery =
      if single.contains("\n") then false
      else
        JsonBoundary
          .decode(single)
          .toOption
          .flatMap(_.asObject)
          .flatMap(_.get("version"))
          .contains(Json.Num(2))
    if !recovery then decode(text).map(Document.Linear.apply)
    else
      for
        _ <- Either.cond(
          text.getBytes(StandardCharsets.UTF_8).length <= maxBytes,
          (),
          AgentError.MemoryStorageError("Recovered transcript encoded byte bound exceeded")
        )
        json <- JsonBoundary.decode(single).left.map(AgentError.MemoryStorageError.apply)
        _ <- Either.cond(
          JsonBoundary.validUnicode(json),
          (),
          AgentError.MemoryStorageError("Invalid recovered transcript Unicode")
        )
        wire      <- json.as[Recovery].left.map(AgentError.MemoryStorageError.apply)
        canonical <- wire.toJsonAST.left.map(AgentError.MemoryStorageError.apply)
        _ <- Either.cond(
          wire.version == 2 && JsonBoundary.sameRepresentation(json, canonical),
          (),
          AgentError.MemoryStorageError("Noncanonical recovered transcript document")
        )
        state <- TranscriptState.restore(wire.localContinuation)
        document = Document.Recovered(state, wire.remoteBranches)
        _ <- validate(document)
      yield document

  private def validate(document: Document): Either[AgentError, Unit] =
    val chains = document.archives :+ document.state.entries
    for
      _ <- Either.cond(
        document.archives.size <= maxBranches &&
          chains.foldLeft(0L)((count, chain) => count + chain.size.toLong) <= maxEntries &&
          (document match
            case _: Document.Linear => true
            case _: Document.Recovered =>
              document.archives.nonEmpty && document.archives.distinct == document.archives),
        (),
        AgentError.MemoryStorageError("Recovered transcript branch or entry bound exceeded")
      )
      _ <- chains.foldLeft[Either[AgentError, Unit]](Right(())): (result, chain) =>
        result.flatMap(_ => TranscriptState.restore(chain).map(_ => ()))
      _ <- chains.flatten
        .foldLeft[Either[AgentError, Map[java.util.UUID, TranscriptEntry]]](Right(Map.empty)):
          (result, entry) =>
            result.flatMap: seen =>
              seen.get(entry.id) match
                case Some(previous) if previous != entry =>
                  Left(
                    AgentError.MemoryStorageError("Conflicting recovered transcript entry identity")
                  )
                case _ => Right(seen.updated(entry.id, entry))
    yield ()

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
        row.version == 1 && JsonBoundary.sameRepresentation(json, canonical),
        (),
        AgentError.MemoryStorageError("Unsupported or noncanonical transcript row")
      )
    yield row.entry
