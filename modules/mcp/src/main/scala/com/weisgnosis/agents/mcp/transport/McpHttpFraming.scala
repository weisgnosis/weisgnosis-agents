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
package com.weisgnosis.agents.mcp.transport

import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.mcp.protocol.McpMessage
import java.nio.{ByteBuffer, CharBuffer}
import java.nio.charset.{CodingErrorAction, StandardCharsets}
import zio.*
import zio.stream.{ZChannel, ZPipeline}

/**
 * Checked HTTP body framing, without requests or session ownership. JSON requires one complete
 * envelope at EOF and rejects a leading BOM. SSE ignores one leading BOM, dispatches only at blank
 * lines, and discards an unterminated final event after validating its UTF-8. Strict malformed UTF-8
 * rejection is a local policy. SSE body limits count every consumed byte; event limits count raw
 * line content, including comments, unknown fields and BOM, but exclude CR/LF separators.
 */
object McpHttpFraming:
  /** A completed SSE block; its cursor is inherited only within this response. */
  final case class McpSseEvent(message: Option[McpMessage], lastEventId: String)

  private type Decoder[A] =
    ZChannel[Any, Nothing, Chunk[Byte], Any, AgentError, Chunk[A], Any]

  final private case class Buffer(segments: List[Chunk[Byte]], size: Int):
    def append(segment: Chunk[Byte]): Buffer =
      if segment.isEmpty then this
      else Buffer(Chunk.fromArray(segment.toArray) :: segments, size + segment.size)

    def bytes: Chunk[Byte] = segments.reverse.foldLeft(Chunk.empty[Byte])(_ ++ _)

  private enum Newline:
    case Normal, AfterCr

  private enum Position:
    case Beginning, Started

  final private case class SseState(
      line: Buffer = Buffer(Nil, 0),
      data: List[String] = Nil,
      lastEventId: String = "",
      bodyBytes: Long = 0L,
      eventBytes: Int = 0,
      newline: Newline = Newline.Normal,
      position: Position = Position.Beginning
  )

  def encode(
      message: McpMessage,
      maxBytes: Int = 1048576
  ): IO[AgentError, Chunk[Byte]] =
    for
      _    <- validate(maxBytes > 0)
      wire <- ZIO.fromEither(McpMessage.encode(message))
      _ <- ZIO
        .fail(AgentError.InvalidMessage("MCP HTTP body exceeds byte limit"))
        .when(wire.length > maxBytes)
      bytes <- ZIO
        .attempt:
          val encoder = StandardCharsets.UTF_8
            .newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
          Chunk.fromByteBuffer(encoder.encode(CharBuffer.wrap(wire)))
        .mapError(_ => AgentError.InvalidMessage("MCP HTTP body cannot be encoded as UTF-8"))
      _ <- ZIO
        .fail(AgentError.InvalidMessage("MCP HTTP body exceeds byte limit"))
        .when(bytes.size > maxBytes)
    yield bytes

  def json(maxBytes: Int = 1048576): ZPipeline[Any, AgentError, Byte, McpMessage] =
    ZPipeline.fromChannel:
      def read(buffer: Buffer): Decoder[McpMessage] =
        ZChannel.readWithCause(
          input =>
            if input.size > maxBytes - buffer.size then
              ZChannel.fail(
                AgentError.DeserializationError("MCP HTTP JSON body exceeds byte limit")
              )
            else read(buffer.append(input)),
          cause => ZChannel.refailCause(cause),
          _ =>
            ZChannel
              .fromZIO(
                text(buffer.bytes).flatMap(value =>
                  if value.startsWith("\ufeff") then
                    ZIO.fail(
                      AgentError.DeserializationError("BOM is not accepted in MCP HTTP JSON bodies")
                    )
                  else ZIO.fromEither(McpMessage.decode(value))
                )
              )
              .flatMap(message => ZChannel.write(Chunk(message)))
        )
      ZChannel.fromZIO(validate(maxBytes > 0)) *> read(Buffer(Nil, 0))

  def sse(
      maxBodyBytes: Long = 8L * 1024 * 1024,
      maxEventBytes: Int = 1048576
  ): ZPipeline[Any, AgentError, Byte, McpMessage] =
    sseEvents(maxBodyBytes, maxEventBytes).collect:
      case McpSseEvent(Some(message), _) => message

  /**
   * Emits each blank-line-terminated block, including blocks without data. IDs are literal,
   * NUL-containing values are ignored, and an empty ID resets the response-local cursor. No cursor
   * or message is emitted for an unfinished final block. This does not implement resumption.
   */
  def sseEvents(
      maxBodyBytes: Long = 8L * 1024 * 1024,
      maxEventBytes: Int = 1048576
  ): ZPipeline[Any, AgentError, Byte, McpSseEvent] =
    ZPipeline.fromChannel:
      def read(state: SseState): Decoder[McpSseEvent] =
        ZChannel.readWithCause(
          input => consume(input, state),
          cause => ZChannel.refailCause(cause),
          _ =>
            if state.line.size == 0 then ZChannel.unit
            else ZChannel.fromZIO(text(state.line.bytes)).unit
        )

      def finishLine(rest: Chunk[Byte], state: SseState): Decoder[McpSseEvent] =
        ZChannel
          .fromZIO(text(state.line.bytes))
          .flatMap: decoded =>
            val line = state.position match
              case Position.Beginning => decoded.stripPrefix("\ufeff")
              case Position.Started   => decoded
            val next = state.copy(line = Buffer(Nil, 0), position = Position.Started)
            if line.isEmpty then
              val reset = next.copy(data = Nil, eventBytes = 0)
              state.data match
                case Nil =>
                  ZChannel
                    .write(Chunk(McpSseEvent(None, state.lastEventId))) *> consume(rest, reset)
                case data =>
                  ZChannel
                    .fromZIO(ZIO.fromEither(McpMessage.decode(data.reverse.mkString("\n"))))
                    .flatMap(message =>
                      ZChannel.write(Chunk(McpSseEvent(Some(message), state.lastEventId))) *>
                        consume(rest, reset)
                    )
            else if line == "data" then consume(rest, next.copy(data = "" :: state.data))
            else if line.startsWith("data:") then
              consume(rest, next.copy(data = line.drop(5).stripPrefix(" ") :: state.data))
            else if line == "id" then consume(rest, next.copy(lastEventId = ""))
            else if line.startsWith("id:") then
              val value = line.drop(3).stripPrefix(" ")
              consume(
                rest,
                if value.contains('\u0000') then next else next.copy(lastEventId = value)
              )
            else consume(rest, next)

      def consume(input: Chunk[Byte], state: SseState): Decoder[McpSseEvent] =
        ZChannel.suspend:
          if input.isEmpty then read(state)
          else if state.newline == Newline.AfterCr then
            if input.headOption.contains(10.toByte) then
              if state.bodyBytes >= maxBodyBytes then bodyLimit
              else
                consume(
                  input.drop(1),
                  state.copy(
                    bodyBytes = state.bodyBytes + 1,
                    newline = Newline.Normal
                  )
                )
            else consume(input, state.copy(newline = Newline.Normal))
          else
            val delimiter = input.indexWhere(byte => byte == 10.toByte || byte == 13.toByte)
            val length    = if delimiter < 0 then input.size else delimiter
            if length.toLong > maxBodyBytes - state.bodyBytes then bodyLimit
            else if length > maxEventBytes - state.eventBytes then
              ZChannel.fail(
                AgentError.DeserializationError("MCP SSE event exceeds content byte limit")
              )
            else
              val next = state.copy(
                line = state.line.append(input.take(length)),
                bodyBytes = state.bodyBytes + length,
                eventBytes = state.eventBytes + length
              )
              if delimiter < 0 then read(next)
              else if next.bodyBytes >= maxBodyBytes then bodyLimit
              else
                val newline =
                  if input.drop(length).headOption.contains(13.toByte) then Newline.AfterCr
                  else Newline.Normal
                finishLine(
                  input.drop(length + 1),
                  next.copy(
                    bodyBytes = next.bodyBytes + 1,
                    newline = newline
                  )
                )

      ZChannel.fromZIO(validate(maxBodyBytes > 0 && maxEventBytes > 0)) *> read(SseState())

  private def bodyLimit[A]: Decoder[A] =
    ZChannel.fail(AgentError.DeserializationError("MCP SSE body exceeds byte limit"))

  private def validate(valid: Boolean): IO[AgentError.InvalidConfiguration, Unit] =
    if valid then ZIO.unit
    else ZIO.fail(AgentError.InvalidConfiguration("MCP HTTP framing byte limits must be positive"))

  private def text(bytes: Chunk[Byte]): IO[AgentError.DeserializationError, String] =
    ZIO
      .attempt:
        val decoder = StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
        decoder.decode(ByteBuffer.wrap(bytes.toArray)).toString
      .mapError(_ => AgentError.DeserializationError("Invalid UTF-8 in MCP HTTP body"))
