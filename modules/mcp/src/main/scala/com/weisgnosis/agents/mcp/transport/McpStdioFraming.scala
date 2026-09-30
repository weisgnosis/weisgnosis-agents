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
 * MCP stdio framing only: strict UTF-8 JSON-RPC messages separated by LF. The raw byte budget
 * excludes LF but includes CR, so CRLF is accepted as JSON whitespace within that budget.
 * Blank frames, BOM-prefixed frames, and nonempty unterminated EOF are rejected. No process I/O.
 */
object McpStdioFraming:
  private type Decoder =
    ZChannel[Any, Nothing, Chunk[Byte], Any, AgentError, Chunk[McpMessage], Any]

  final private case class Frame(segments: List[Chunk[Byte]], size: Int):
    def append(segment: Chunk[Byte]): Frame =
      if segment.isEmpty then this
      // Copy bounded segments: a retained slice must not pin an oversized upstream chunk.
      else Frame(Chunk.fromArray(segment.toArray) :: segments, size + segment.size)

    def bytes: Chunk[Byte] = segments.reverse.foldLeft(Chunk.empty[Byte])(_ ++ _)

  def decode(maxFrameBytes: Int = 1048576): ZPipeline[Any, AgentError, Byte, McpMessage] =
    ZPipeline.fromChannel:
      def read(frame: Frame): Decoder =
        ZChannel.readWithCause(
          input => consume(input, frame),
          cause => ZChannel.refailCause(cause),
          _ =>
            if frame.size == 0 then ZChannel.unit
            else ZChannel.fail(AgentError.DeserializationError("Unterminated MCP stdio frame"))
        )

      def consume(input: Chunk[Byte], frame: Frame): Decoder =
        ZChannel.suspend:
          val delimiter = input.indexWhere(_ == 10.toByte)
          val length    = if delimiter < 0 then input.size else delimiter
          if length > maxFrameBytes - frame.size then
            ZChannel.fail(AgentError.DeserializationError("MCP stdio frame exceeds byte limit"))
          else
            val next = frame.append(input.take(length))
            if delimiter < 0 then read(next)
            else
              ZChannel
                .fromZIO(readFrame(next.bytes))
                .flatMap(message =>
                  ZChannel.write(Chunk(message)) *> consume(input.drop(length + 1), Frame(Nil, 0))
                )

      ZChannel.fromZIO(validateLimit(maxFrameBytes)) *> read(Frame(Nil, 0))

  def encode(
      message: McpMessage,
      maxFrameBytes: Int = 1048576
  ): IO[AgentError, Chunk[Byte]] =
    for
      _    <- validateLimit(maxFrameBytes)
      wire <- ZIO.fromEither(McpMessage.encode(message))
      // Every UTF-16 code unit needs at least one UTF-8 byte in a valid encoded string.
      _ <- ZIO
        .fail(AgentError.InvalidMessage("MCP stdio frame exceeds byte limit"))
        .when(wire.length > maxFrameBytes)
      bytes <- ZIO
        .attempt:
          val encoder = StandardCharsets.UTF_8
            .newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
          Chunk.fromByteBuffer(encoder.encode(CharBuffer.wrap(wire)))
        .mapError(_ => AgentError.InvalidMessage("MCP stdio frame cannot be encoded as UTF-8"))
      _ <- ZIO
        .fail(AgentError.InvalidMessage("MCP stdio frame exceeds byte limit"))
        .when(bytes.size > maxFrameBytes)
    yield bytes :+ 10.toByte

  private def validateLimit(maxFrameBytes: Int): IO[AgentError.InvalidConfiguration, Unit] =
    if maxFrameBytes > 0 then ZIO.unit
    else ZIO.fail(AgentError.InvalidConfiguration("MCP stdio frame byte limit must be positive"))

  private def readFrame(bytes: Chunk[Byte]): IO[AgentError, McpMessage] =
    val text = ZIO.attempt:
      val decoder = StandardCharsets.UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
      decoder.decode(ByteBuffer.wrap(bytes.toArray)).toString
    for
      wire <- text.mapError(_ =>
        AgentError.DeserializationError("Invalid UTF-8 in MCP stdio frame")
      )
      _ <- ZIO
        .fail(AgentError.DeserializationError("BOM is not accepted in MCP stdio frames"))
        .when(wire.startsWith("\ufeff"))
      message <- ZIO.fromEither(McpMessage.decode(wire))
    yield message
