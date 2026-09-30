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
import com.weisgnosis.agents.mcp.transport.McpHttpFraming.McpSseEvent
import java.nio.charset.StandardCharsets
import zio.*
import zio.json.ast.Json
import zio.stream.{ZChannel, ZStream}
import zio.test.*

object McpHttpFramingSpec extends ZIOSpecDefault:
  private val message: McpMessage = McpMessage.Success(
    McpRequestId.Number(1),
    Json.Obj("text" -> Json.Str("caf\u00e9 \uD83D\uDE80"))
  )

  private def bytes(value: String): UIO[Chunk[Byte]] =
    ZIO.succeed(Chunk.fromArray(value.getBytes(StandardCharsets.UTF_8)))

  def spec: Spec[TestEnvironment & Scope, Any] = suite("McpHttpFramingSpec")(
    test("SSE snapshots commit literal, inherited IDs only at completed block boundaries") {
      for
        encoded <- ZIO.fromEither(McpMessage.encode(message))
        data = s"data: $encoded\n\n"
        cases = Chunk(
          data -> Chunk(McpSseEvent(Some(message), "")),
          ("id: first\n\n" + data) ->
            Chunk(McpSseEvent(None, "first"), McpSseEvent(Some(message), "first")),
          ("id: first\nid: second\nid: ignored\u0000value\nID: ignored\nid-suffix: ignored\n" + data) ->
            Chunk(McpSseEvent(Some(message), "second")),
          ("id: first\n\nid\n\n" + data) ->
            Chunk(
              McpSseEvent(None, "first"),
              McpSseEvent(None, ""),
              McpSseEvent(Some(message), "")
            ),
          ("id: first\n\nid:\n\n" + data) ->
            Chunk(
              McpSseEvent(None, "first"),
              McpSseEvent(None, ""),
              McpSseEvent(Some(message), "")
            ),
          ("\ufeffid:  caf\u00e9 \uD83D\uDE80 \n\n: comment\nunknown: value\n\n\n" + data) ->
            Chunk(
              McpSseEvent(None, " caf\u00e9 \uD83D\uDE80 "),
              McpSseEvent(None, " caf\u00e9 \uD83D\uDE80 "),
              McpSseEvent(None, " caf\u00e9 \uD83D\uDE80 "),
              McpSseEvent(Some(message), " caf\u00e9 \uD83D\uDE80 ")
            ),
          "id: unfinished\n"                -> Chunk.empty[McpSseEvent],
          "id: committed\n\nid: unfinished" -> Chunk(McpSseEvent(None, "committed"))
        )
        results <- ZIO.foreach(cases): (wire, expected) =>
          ZIO.foreach(Chunk("\n", "\r\n", "\r")): newline =>
            for
              raw   <- bytes(wire.replace("\n", newline))
              whole <- ZStream.fromChunk(raw).via(McpHttpFraming.sseEvents()).runCollect
              fragmented <- ZStream
                .fromChunk(raw)
                .rechunk(1)
                .via(McpHttpFraming.sseEvents())
                .runCollect
            yield whole == expected && fragmented == expected
      yield assertTrue(results.flatten.forall(identity))
    },
    test(
      "SSE handles fragmented BOM, Unicode, all line endings and multiline data, discarding unfinished events"
    ) {
      for
        wire <- bytes(
          "\ufeff: keepalive\rignored: field\r\nevent: message\nid: opaque\n\n" +
            "data\rdata: {\"jsonrpc\":\"2.0\",\r\ndata: \"id\":1,\"result\":{\"text\":\"caf\u00e9 \uD83D\uDE80\"}}\n\r" +
            "data: {\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{}}\n"
        )
        chunkings = Chunk(Chunk(wire), wire.map(Chunk(_)), Chunk(wire.take(2), wire.drop(2)))
        decoded <- ZIO.foreach(chunkings)(parts =>
          ZStream.fromChunks(parts*).via(McpHttpFraming.sse()).runCollect
        )
        comments   <- bytes(": keepalive\r\n\r\n")
        none       <- ZStream.fromChunk(comments).via(McpHttpFraming.sse()).runCollect
        incomplete <- bytes("data: {\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}")
        discarded  <- ZStream.fromChunk(incomplete).via(McpHttpFraming.sse()).runCollect
      yield assertTrue(decoded.forall(_ == Chunk(message)), none.isEmpty, discarded.isEmpty)
    },
    test(
      "SSE validates strict UTF-8 and checked envelopes within body and event-content byte budgets"
    ) {
      for
        encoded <- McpHttpFraming.encode(message)
        prefix  <- bytes("data: ")
        ending  <- bytes("\r\n\r\n")
        frame       = prefix ++ encoded ++ ending
        contentSize = prefix.size + encoded.size
        exact <- ZStream
          .fromChunk(frame)
          .rechunk(1)
          .via(McpHttpFraming.sse(frame.size.toLong, contentSize))
          .runCollect
        overEvent <- ZStream
          .fromChunk(frame)
          .via(McpHttpFraming.sse(frame.size.toLong, contentSize - 1))
          .runCollect
          .exit
        overBody <- ZStream
          .fromChunk(frame)
          .via(McpHttpFraming.sse(frame.size.toLong - 1, contentSize))
          .runCollect
          .exit
        repeated <- ZStream
          .fromChunk(frame ++ frame)
          .via(McpHttpFraming.sse(frame.size.toLong * 2, contentSize))
          .runCollect
        extraContent <- ZIO.foreach(Chunk(": comment\n", "unknown: field\r\n", "\ufeff"))(value =>
          for
            before <- bytes(value)
            wire = before ++ frame
            contentBudget = contentSize + before.count(byte =>
              byte != 13.toByte && byte != 10.toByte
            )
            accepted <- ZStream
              .fromChunk(wire)
              .rechunk(1)
              .via(McpHttpFraming.sse(wire.size.toLong, contentBudget))
              .runCollect
            rejected <- ZStream
              .fromChunk(wire)
              .via(McpHttpFraming.sse(wire.size.toLong, contentBudget - 1))
              .runCollect
              .exit
          yield accepted == Chunk(message) && rejected.isFailure
        )
        badText <- ZIO.foreach(
          Chunk("data: [DONE]\n\n", "data\n\n", "data: {}\n\n", "\ufeff\ufeffdata: {}\n\n")
        )(bytes)
        invalidUtf8 = Chunk(
          Chunk(0xc3.toByte),
          Chunk(0xc3.toByte, 0x28.toByte),
          Chunk(0xc0.toByte, 0xaf.toByte)
        )
        malformed <- ZIO.foreach(badText.take(3) ++ invalidUtf8.map(prefix ++ _ ++ ending))(wire =>
          ZStream.fromChunk(wire).via(McpHttpFraming.sse()).runCollect.exit
        )
        truncated <- ZStream
          .fromChunk(prefix ++ Chunk(0xc3.toByte))
          .via(McpHttpFraming.sse())
          .runCollect
          .exit
        secondBom <- ZStream
          .fromChunk(badText.drop(3).flatten)
          .via(McpHttpFraming.sse())
          .runCollect
        pulled <- Ref.make(false)
        limited <- (ZStream.fromChunk(Chunk.fill(5)(32.toByte)) ++
          ZStream.fromZIO(pulled.set(true).as(10.toByte)))
          .via(McpHttpFraming.sse(100, 4))
          .runCollect
          .exit
        continued <- pulled.get
        invalidLimits <- ZIO.foreach(Chunk((0L, 1), (1L, 0))): (body, event) =>
          ZStream
            .fromZIO(pulled.set(true).as(10.toByte))
            .via(McpHttpFraming.sse(body, event))
            .runCollect
            .exit
        invalidPulled <- pulled.get
        idBlock       <- bytes("id: cursor\n\n")
        idLimits <- ZIO.foreach(
          Chunk((idBlock.size.toLong, 10), (idBlock.size.toLong - 1, 10), (idBlock.size.toLong, 9))
        ): (body, event) =>
          ZStream.fromChunk(idBlock).via(McpHttpFraming.sseEvents(body, event)).runCollect.exit
      yield assertTrue(
        exact == Chunk(message),
        overEvent.isFailure,
        overBody.isFailure,
        repeated == Chunk(message, message),
        extraContent.forall(identity),
        malformed.forall(_.isFailure),
        truncated.isFailure,
        secondBom.isEmpty,
        limited.isFailure,
        !continued,
        invalidLimits.forall(_.isFailure),
        !invalidPulled,
        idLimits.headOption.contains(Exit.succeed(Chunk(McpSseEvent(None, "cursor")))),
        idLimits.drop(1).forall(_.isFailure)
      )
    },
    test(
      "JSON is one complete checked UTF-8 envelope with symmetric exact outbound and inbound limits"
    ) {
      for
        encoded <- McpHttpFraming.encode(message)
        exact   <- McpHttpFraming.encode(message, encoded.size)
        decoded <- ZStream
          .fromChunk(encoded)
          .rechunk(1)
          .via(McpHttpFraming.json(encoded.size))
          .runCollect
        tooLarge <- ZStream
          .fromChunk(encoded)
          .via(McpHttpFraming.json(encoded.size - 1))
          .runCollect
          .exit
        outboundTooLarge <- McpHttpFraming.encode(message, encoded.size - 1).exit
        invalidLimit     <- McpHttpFraming.encode(message, 0).exit
        malformedUnicode <- McpHttpFraming
          .encode(McpMessage.Notification("bad", Some(Json.Obj("text" -> Json.Str("\ud800")))))
          .exit
        duplicate <- McpHttpFraming
          .encode(
            McpMessage.Notification("bad", Some(Json.Obj("x" -> Json.Num(1), "x" -> Json.Num(2))))
          )
          .exit
        bom <- bytes("\ufeff")
        malformed <- ZIO.foreach(
          Chunk(Chunk.empty[Byte], encoded ++ encoded, bom ++ encoded, Chunk(0xc3.toByte))
        )(wire => ZStream.fromChunk(wire).via(McpHttpFraming.json()).runCollect.exit)
        pulled <- Ref.make(false)
        badLimit <- ZStream
          .fromZIO(pulled.set(true).as(32.toByte))
          .via(McpHttpFraming.json(0))
          .runCollect
          .exit
        continued <- pulled.get
      yield assertTrue(
        exact == encoded,
        !encoded.contains(10.toByte),
        decoded == Chunk(message),
        tooLarge.isFailure,
        outboundTooLarge.isFailure,
        invalidLimit.isFailure,
        malformedUnicode.isFailure,
        duplicate.isFailure,
        malformed.forall(_.isFailure),
        badLimit.isFailure,
        !continued
      )
    },
    test(
      "SSE respects downstream demand and both decoders preserve complete upstream causes and cancellation"
    ) {
      for
        encoded <- McpHttpFraming.encode(message)
        prefix  <- bytes("data: ")
        ending  <- bytes("\n\n")
        pulled  <- Ref.make(false)
        first <- (ZStream.fromChunk(
          prefix ++ encoded ++ ending ++ prefix ++ Chunk(0xc3.toByte) ++ ending
        ) ++
          ZStream.fromZIO(pulled.set(true).as(10.toByte)))
          .via(McpHttpFraming.sse())
          .take(1)
          .runCollect
        continued <- pulled.get
        idBlock   <- bytes("id: committed\n\n")
        firstEvent <- (ZStream.fromChunk(idBlock ++ prefix ++ Chunk(0xc3.toByte) ++ ending) ++
          ZStream.fromZIO(pulled.set(true).as(10.toByte)))
          .via(McpHttpFraming.sseEvents())
          .take(1)
          .runCollect
        eventContinued <- pulled.get
        defect = new IllegalStateException("HTTP body failed")
        cause = Cause.fail(AgentError.McpError("read failed")) ++ Cause.die(defect) ++ Cause
          .interrupt(FiberId.None)
        upstream = ZStream.fromChannel(ZChannel.write(prefix) *> ZChannel.refailCause(cause))
        baselineFiber <- upstream.runCollect.forkScoped
        baseline      <- baselineFiber.await
        failures <- ZIO.foreach(
          Chunk(
            McpHttpFraming.json().map(_ => ()),
            McpHttpFraming.sse().map(_ => ()),
            McpHttpFraming.sseEvents().map(_ => ())
          )
        )(decoder => upstream.via(decoder).runCollect.forkScoped.flatMap(_.await))
        entered <- Promise.make[Nothing, Unit]
        cleanup <- Promise.make[Nothing, Unit]
        release <- Promise.make[Nothing, Unit]
        running <- (ZStream.fromChunk(prefix) ++ ZStream.fromZIO(entered.succeed(()) *> ZIO.never))
          .ensuring(cleanup.succeed(()).unit *> release.await)
          .via(McpHttpFraming.sse())
          .runCollect
          .forkScoped
        _        <- entered.await
        stopping <- running.interrupt.forkScoped
        _        <- cleanup.await
        pending  <- stopping.poll
        _        <- release.succeed(())
        stopped  <- stopping.join
      yield assertTrue(
        first == Chunk(message),
        !continued,
        firstEvent == Chunk(McpSseEvent(None, "committed")),
        !eventContinued,
        baseline.causeOption.contains(cause),
        failures.forall(_.causeOption.contains(cause)),
        pending.isEmpty,
        stopped.isInterrupted
      )
    }
  ) @@ TestAspect.timeout(10.seconds)
