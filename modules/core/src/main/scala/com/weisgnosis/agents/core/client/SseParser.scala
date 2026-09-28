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

import zio.*
import zio.stream.{ZChannel, ZPipeline, ZStream}
import java.nio.charset.CharacterCodingException

/**
 * Structured Server-Sent Event (SSE) frame.
 */
final case class SseEvent(
    data: String,
    event: Option[String] = None,
    id: Option[String] = None
):
  def isDone: Boolean = data.trim == "[DONE]"

/**
 * Pure streaming SSE parser transforming raw lines or byte streams into structured SseEvent frames.
 *
 * Discards comment lines (`: keep-alive`), buffers multi-line `data:` payloads,
 * and detects `[DONE]` termination.
 */
object SseParser:

  final private case class ParserState(
      event: Option[String] = None,
      id: Option[String] = None,
      dataBuffer: Chunk[String] = Chunk.empty
  ):
    def isEmpty: Boolean = dataBuffer.isEmpty && event.isEmpty && id.isEmpty

    def toEvent: Option[SseEvent] =
      if dataBuffer.isEmpty then None
      else Some(SseEvent(data = dataBuffer.mkString("\n"), event = event, id = id))

  def parseLines: ZPipeline[Any, Nothing, String, SseEvent] =
    ZPipeline.fromChannel {
      def loop(
          state: ParserState
      ): ZChannel[Any, Nothing, Chunk[String], Any, Nothing, Chunk[SseEvent], Any] =
        ZChannel.readWith(
          (in: Chunk[String]) => {
            val (finalState, emitted) =
              in.foldLeft((state, Chunk.empty[SseEvent])) { case ((currState, acc), rawLine) =>
                val line = rawLine.stripLineEnd
                if line.startsWith(":") then
                  // Comment line; discard
                  (currState, acc)
                else if line.isEmpty then
                  // End of SSE frame dispatch
                  currState.toEvent match
                    case Some(ev) => (ParserState(), acc :+ ev)
                    case None     => (ParserState(), acc)
                else if line.startsWith("data:") then
                  val payload = line.stripPrefix("data:").stripPrefix(" ")
                  (currState.copy(dataBuffer = currState.dataBuffer :+ payload), acc)
                else if line.startsWith("event:") then
                  val evName = line.stripPrefix("event:").stripPrefix(" ")
                  (currState.copy(event = Some(evName)), acc)
                else if line.startsWith("id:") then
                  val idVal = line.stripPrefix("id:").stripPrefix(" ")
                  (currState.copy(id = Some(idVal)), acc)
                else (currState, acc)
              }
            if emitted.nonEmpty then ZChannel.write(emitted) *> loop(finalState)
            else loop(finalState)
          },
          (err: Nothing) => ZChannel.fail(err),
          (_: Any) =>
            state.toEvent match
              case Some(ev) => ZChannel.write(Chunk(ev)) *> ZChannel.unit
              case None     => ZChannel.unit
        )
      loop(ParserState())
    }

  def parseBytes: ZPipeline[Any, CharacterCodingException, Byte, SseEvent] =
    ZPipeline.utf8Decode >>> ZPipeline.splitLines >>> parseLines

  def parseStream(stream: ZStream[Any, Throwable, String]): ZStream[Any, Throwable, SseEvent] =
    stream.via(parseLines)
