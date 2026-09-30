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
import com.weisgnosis.agents.mcp.protocol.*
import java.nio.charset.StandardCharsets
import zio.*
import zio.json.ast.Json
import zio.stream.{ZChannel, ZStream}
import zio.test.*

object McpStdioFramingSpec extends ZIOSpecDefault:
  private val message: McpMessage = McpMessage.Notification(
    "sample",
    Some(
      Json.Obj(
        "text" -> Json.Str("a supplementary character: \uD83D\uDE80 and an escaped newline\n")
      )
    )
  )

  private def bytes(value: String): UIO[Chunk[Byte]] =
    ZIO.succeed(Chunk.fromArray(value.getBytes(StandardCharsets.UTF_8)))

  private def decode(
      parts: Chunk[Chunk[Byte]],
      limit: Int = 1048576
  ): IO[AgentError, Chunk[McpMessage]] =
    ZStream.fromChunks(parts*).via(McpStdioFraming.decode(limit)).runCollect

  def spec: Spec[TestEnvironment, Any] = suite("McpStdioFramingSpec")(
    test("chunk boundaries preserve UTF-8 and multiple LF or CRLF messages") {
      for
        first  <- McpStdioFraming.encode(message)
        second <- McpStdioFraming.encode(McpMessage.Success(McpRequestId.Number(2), Json.Obj()))
        wire = first ++ second.dropRight(1) ++ Chunk(13.toByte, 10.toByte)
        chunkings = Chunk(
          Chunk(wire),
          wire.map(byte => Chunk(byte)),
          Chunk(wire.take(9), wire.drop(9))
        )
        decoded   <- ZIO.foreach(chunkings)(decode(_))
        malformed <- bytes("not JSON\n")
        pulled    <- Ref.make(false)
        prefix <- (ZStream.fromChunk(first ++ malformed) ++
          ZStream.fromZIO(pulled.set(true).as(10.toByte)))
          .via(McpStdioFraming.decode())
          .take(1)
          .runCollect
        continued <- pulled.get
      yield assertTrue(
        decoded.forall(_ == Chunk(message, McpMessage.Success(McpRequestId.Number(2), Json.Obj()))),
        prefix == Chunk(message),
        !continued
      )
    },
    test("raw byte budgets and strict frame boundaries reject malformed input before more pulls") {
      for
        frame     <- McpStdioFraming.encode(message)
        blank     <- bytes(" \t\r\n")
        malformed <- bytes("{}\n")
        bad = Chunk(
          Chunk(10.toByte),
          blank,
          malformed,
          frame.dropRight(1),
          Chunk(0xc3.toByte, 0x28.toByte, 10.toByte),
          Chunk(0xc3.toByte, 10.toByte),
          Chunk(0xc0.toByte, 0xaf.toByte, 10.toByte),
          Chunk(0xef.toByte, 0xbb.toByte, 0xbf.toByte) ++ frame
        )
        invalid      <- ZIO.foreach(bad)(value => decode(Chunk(value)).exit)
        empty        <- decode(Chunk.empty)
        invalidLimit <- decode(Chunk.empty, 0).exit
        exact        <- decode(Chunk(frame), frame.size - 1)
        over         <- decode(frame.map(byte => Chunk(byte)), frame.size - 2).exit
        crlf = frame.dropRight(1) ++ Chunk(13.toByte, 10.toByte)
        crExact <- decode(Chunk(crlf), frame.size)
        crOver  <- decode(Chunk(crlf), frame.size - 1).exit
        pulled  <- Ref.make(false)
        tooLong <- (ZStream.fromChunk(Chunk.fill(5)(32.toByte)) ++
          ZStream.fromZIO(pulled.set(true).as(10.toByte)))
          .via(McpStdioFraming.decode(4))
          .runCollect
          .exit
        continued <- pulled.get
      yield assertTrue(
        invalid.forall(_.isFailure),
        empty.isEmpty,
        invalidLimit.isFailure,
        exact == Chunk(message),
        over.isFailure,
        crExact == Chunk(message),
        crOver.isFailure,
        tooLong.isFailure,
        !continued
      )
    },
    test("upstream compound failures and actual cancellation preserve causes and join cleanup") {
      for
        partial <- bytes("{\"jsonrpc\":")
        defect = new IllegalStateException("upstream failed")
        cause = Cause.fail(AgentError.McpError("read failed")) ++ Cause.die(defect) ++ Cause
          .interrupt(FiberId.None)
        upstream = ZStream.fromChannel(ZChannel.write(partial) *> ZChannel.refailCause(cause))
        baselineFiber <- upstream.runCollect.fork
        baseline      <- baselineFiber.await
        failed        <- upstream.via(McpStdioFraming.decode()).runCollect.fork
        failure       <- failed.await
        entered       <- Promise.make[Nothing, Unit]
        cleaned       <- Promise.make[Nothing, Unit]
        reading <- (ZStream.fromChunk(partial) ++ ZStream.fromZIO(entered.succeed(()) *> ZIO.never))
          .ensuring(cleaned.succeed(()).unit)
          .via(McpStdioFraming.decode())
          .runCollect
          .fork
        _         <- entered.await
        cancelled <- reading.interrupt
        cleanup   <- cleaned.isDone
      yield assertTrue(
        baseline.causeOption.contains(cause),
        failure.causeOption.contains(cause),
        cancelled.isInterrupted,
        cleanup
      )
    },
    test("outbound frames use checked compact JSON, exact UTF-8 budgets and one delimiter") {
      for
        encoded      <- McpStdioFraming.encode(message)
        exact        <- McpStdioFraming.encode(message, encoded.size - 1)
        over         <- McpStdioFraming.encode(message, encoded.size - 2).exit
        invalidLimit <- McpStdioFraming.encode(message, 0).exit
        malformedUnicode <- McpStdioFraming
          .encode(
            McpMessage.Notification(
              "bad",
              Some(Json.Obj("text" -> Json.Str("\ud800")))
            )
          )
          .exit
        duplicate <- McpStdioFraming
          .encode(
            McpMessage.Notification(
              "bad",
              Some(Json.Obj(Chunk("x" -> Json.Num(1), "x" -> Json.Num(2))))
            )
          )
          .exit
        result <- decode(Chunk(encoded))
      yield assertTrue(
        encoded.lastOption.contains(10.toByte),
        encoded.count(_ == 10.toByte) == 1,
        exact == encoded,
        over.isFailure,
        invalidLimit.isFailure,
        malformedUnicode.isFailure,
        duplicate.isFailure,
        result == Chunk(message)
      )
    }
  ) @@ TestAspect.timeout(10.seconds)
