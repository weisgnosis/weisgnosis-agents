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
import com.weisgnosis.agents.core.model.Msg
import java.nio.charset.StandardCharsets
import zio.*
import zio.http.*
import zio.stream.ZStream
import zio.test.*
import HttpClientTestSupport.scripted

object ChatCompletionsTransportSpec extends ZIOSpecDefault:
  private val config  = LlmConfig("test-model", endpoint = "http://localhost/completion")
  private val text    = frame("""{"choices":[{"index":0,"delta":{"content":"hello"}}]}""")
  private val stop    = frame("""{"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""")
  private val done    = "data: [DONE]\n\n"
  private val success = text + stop + done

  def spec: Spec[TestEnvironment & Scope, Any] = suite("ChatCompletionsTransport")(
    test("posts validated JSON and credentials only in headers"):
      for
        captured <- Ref.make(Option.empty[(Request, String)])
        transport <- ChatCompletionsTransport.make(
          scripted(request =>
            request.body.asString
              .flatMap(body => captured.set(Some((request, body))))
              .as(response(success))
          )
        )
        output <- transport.complete(
          Chunk(Msg.user("question")),
          Chunk.empty,
          config.copy(apiKey = Some("secret"))
        )
        sent <- captured.get
      yield assertTrue(
        output.message.text == "hello",
        sent.exists { case (request, body) =>
          request.method == Method.POST && request.url.path.encode == "/completion" &&
          request.headers.get("Authorization").contains("Bearer secret") &&
          request.headers.get("Content-Type").contains("application/json") &&
          request.headers.get("Accept").contains("text/event-stream") &&
          body.contains("question") && !body.contains("secret")
        }
      )
    ,
    test("completes at DONE without pulling an open response tail and closes the request scope"):
      for
        closed <- Ref.make(false)
        transport <- ChatCompletionsTransport.make(
          scripted(_ =>
            ZIO.addFinalizer(closed.set(true)).as(streamResponse(bytes(success) ++ ZStream.never))
          )
        )
        output   <- transport.complete(Chunk(Msg.user("q")), Chunk.empty, config)
        released <- closed.get
      yield assertTrue(output.message.text == "hello", released)
    ,
    test("assembles UTF-8 split at every byte and retains usage after finish"):
      val payload = frame("""{"choices":[{"index":0,"delta":{"content":"caf\u00e9"}}]}""") + stop +
        frame(
          """{"choices":[],"usage":{"prompt_tokens":2,"completion_tokens":3,"total_tokens":5}}"""
        ) + done
      for output <- run(streamResponse(bytes(payload).rechunk(1)))
      yield assertTrue(
        output.message.text == "caf\u00e9",
        output.message.usage.exists(_.totalTokens == 5)
      )
    ,
    test("assembles interleaved parallel calls before exposing executable output"):
      val payload = frame(
        """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":1,"id":"b","function":{"name":"second","arguments":"{"}},{"index":0,"id":"a","function":{"name":"first","arguments":"{}"}}]}}]}"""
      ) +
        frame(
          """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":1,"function":{"arguments":"}"}}]},"finish_reason":"tool_calls"}]}"""
        ) + done
      for output <- run(response(payload))
      yield assertTrue(
        output.isInstanceOf[TurnOutput.ToolCalls],
        output.message.toolUses.map(_.id) == Chunk("a", "b")
      )
    ,
    test("requires DONE even after a valid finish marker"):
      for result <- run(response(text + stop)).either
      yield assertTrue(result.left.exists(_.message == "Event stream ended before [DONE]"))
    ,
    test("requires valid completion and rejects malformed protocol frames"):
      for
        missing   <- run(response(text + done)).either
        malformed <- run(response(frame("not-json") + done)).either
        truncated <- run(
          response(
            text + frame("""{"choices":[{"index":0,"delta":{},"finish_reason":"length"}]}""") + done
          )
        ).either
      yield assertTrue(missing.isLeft, malformed.isLeft, truncated.isLeft)
    ,
    test("preserves in-band provider failures"):
      for result <- run(response(frame("""{"error":{"message":"overloaded"}}""") + done)).either
      yield assertTrue(result.left.exists {
        case AgentError.ModelError(200, "overloaded", _) => true
        case _                                           => false
      })
    ,
    test("rejects wrong or missing content type before reading the body"):
      for
        wrong <- run(Response(body = Body.fromString(success))).either
        json <- run(
          Response(
            headers = Headers("Content-Type", "application/json"),
            body = Body.fromString(success)
          )
        ).either
      yield assertTrue(wrong.isLeft, json.isLeft)
    ,
    test("bounds HTTP error bodies and preserves actual status"):
      for
        transport <- ChatCompletionsTransport.make(
          scripted(_ =>
            ZIO.succeed(
              Response(
                status = Status.TooManyRequests,
                body = Body.fromStreamChunked(bytes("abcdef") ++ ZStream.never)
              )
            )
          ),
          CompletionTransportLimits(errorBodyBytes = 4)
        )
        result <- transport.complete(Chunk(Msg.user("q")), Chunk.empty, config).either
      yield assertTrue(result.left.exists {
        case AgentError.ModelError(429, _, Some("abcd")) => true
        case _                                           => false
      })
    ,
    test("bounds streamed bytes and closes resources on parser failures"):
      for
        closed <- Ref.make(false)
        transport <- ChatCompletionsTransport.make(
          scripted(_ => ZIO.addFinalizer(closed.set(true)).as(response(success))),
          CompletionTransportLimits(responseBytes = 8)
        )
        result   <- transport.complete(Chunk(Msg.user("q")), Chunk.empty, config).either
        released <- closed.get
      yield assertTrue(result.left.exists(_.message.contains("byte limit")), released)
    ,
    test("normalizes network and body failures without swallowing cancellation"):
      for
        transport <- ChatCompletionsTransport.make(
          scripted(_ => ZIO.fail(AgentError.InvalidMessage("internal detail")))
        )
        network <- transport.complete(Chunk(Msg.user("q")), Chunk.empty, config).either
        body <- run(streamResponse(ZStream.fail(AgentError.InvalidMessage("body failed")))).either
      yield assertTrue(network.left.exists(_.message == "HTTP request failed"), body.isLeft)
    ,
    test("interrupts an in-flight stream and waits for scoped cleanup"):
      ZIO.scoped:
        for
          reading <- Promise.make[Nothing, Unit]
          closed  <- Ref.make(false)
          transport <- ChatCompletionsTransport.make(
            scripted(_ =>
              ZIO
                .addFinalizer(closed.set(true))
                .as(
                  streamResponse(
                    ZStream.fromZIO(reading.succeed(()) *> ZIO.never)
                  )
                )
            )
          )
          fiber    <- transport.complete(Chunk(Msg.user("q")), Chunk.empty, config).forkScoped
          _        <- reading.await
          exit     <- fiber.interrupt
          released <- closed.get
        yield assertTrue(exit.isInterrupted, released)
    ,
    test("rejects invalid limits endpoints and credentials before sending"):
      for
        sent <- Ref.make(0)
        http = scripted(_ => sent.update(_ + 1).as(response(success)))
        badLimit <- ChatCompletionsTransport
          .make(http, CompletionTransportLimits(responseBytes = 0))
          .either
        transport <- ChatCompletionsTransport.make(http)
        endpoints <- ZIO.foreach(
          Chunk(
            "/relative",
            "ftp://localhost/file",
            "http://user:password@localhost/",
            "http://localhost/#fragment",
            "http://localhost:0/",
            "not a url"
          )
        )(endpoint =>
          transport
            .complete(Chunk(Msg.user("q")), Chunk.empty, config.copy(endpoint = endpoint))
            .either
        )
        keys <- ZIO.foreach(Chunk("", "has space", "injected\r\nheader"))(key =>
          transport
            .complete(Chunk(Msg.user("q")), Chunk.empty, config.copy(apiKey = Some(key)))
            .either
        )
        count <- sent.get
      yield assertTrue(
        badLimit.isLeft,
        endpoints.forall(_.isLeft),
        keys.forall(_.isLeft),
        count == 0
      )
    ,
    test("sends a real loopback HTTP request and consumes chunked SSE")(
      ZIO
        .scoped:
          for
            observed <- Promise.make[Nothing, (String, Option[String])]
            port <- Server.install(Routes(Method.POST / "completion" -> handler {
              (request: Request) =>
                request.body.asString
                  .orElseSucceed("")
                  .flatMap(body => observed.succeed((body, request.headers.get("Authorization"))))
                  .as(streamResponse(bytes(success).rechunk(3)))
            }))
            http      <- ZIO.service[Client]
            transport <- ChatCompletionsTransport.make(http)
            output <- transport.complete(
              Chunk(Msg.user("loopback")),
              Chunk.empty,
              config.copy(
                endpoint = s"http://127.0.0.1:$port/completion",
                apiKey = Some("loopback-key")
              )
            )
            sent <- observed.await
          yield assertTrue(
            output.message.text == "hello",
            sent._1.contains("loopback"),
            sent._2.contains("Bearer loopback-key")
          )
        .provide(Client.default, Server.defaultWithPort(0))
    ) @@ TestAspect.withLiveClock
  ) @@ TestAspect.timeout(20.seconds)

  private def frame(json: String): String = s"data: $json\n\n"

  private def bytes(text: String): ZStream[Any, Nothing, Byte] =
    ZStream.fromChunk(Chunk.fromArray(text.getBytes(StandardCharsets.UTF_8)))

  private def response(text: String): Response = streamResponse(bytes(text))

  private def streamResponse(bytes: ZStream[Any, Throwable, Byte]): Response =
    Response(
      headers = Headers("Content-Type", "text/event-stream; charset=utf-8"),
      body = Body.fromStreamChunked(bytes)
    )

  private def run(response: Response): IO[AgentError, TurnOutput] =
    ChatCompletionsTransport
      .make(scripted(_ => ZIO.succeed(response)))
      .flatMap(_.complete(Chunk(Msg.user("q")), Chunk.empty, config))
