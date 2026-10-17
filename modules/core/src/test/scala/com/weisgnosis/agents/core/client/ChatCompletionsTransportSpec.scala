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
import com.weisgnosis.agents.core.model.{ContentBlock, MediaSource, Msg}
import com.weisgnosis.agents.core.event.{AgentEvent, EventPublisher, ModelStreamContext}
import zio.json.*
import zio.json.ast.Json
import java.nio.charset.StandardCharsets
import zio.*
import zio.http.*
import zio.stream.ZStream
import zio.test.*
import HttpClientTestSupport.scripted

object ChatCompletionsTransportSpec extends ZIOSpecDefault:
  private enum TokenLimitEntry:
    case ClientStreaming, ClientJson, TransportStreaming, TransportJson

  private def tokenLimitRequest(
      entry: TokenLimitEntry,
      http: Client,
      selected: LlmConfig,
      field: ChatCompletionTokenLimitField,
      retry: CompletionRetryPolicy
  ): ZIO[Scope, AgentError, TurnOutput] =
    val history = Chunk(Msg.user("hello"))
    entry match
      case TokenLimitEntry.ClientStreaming =>
        ChatCompletionsClient
          .make(http, retryPolicy = retry, tokenLimitField = field)
          .flatMap(
            _.streamTurn(
              history,
              Chunk.empty,
              selected,
              EventPublisher.discard,
              ModelStreamContext("reply", "request")
            )
          )
      case TokenLimitEntry.ClientJson =>
        ChatCompletionsClient
          .makeNonStreaming(http, retryPolicy = retry, tokenLimitField = field)
          .flatMap(
            _.streamTurn(
              history,
              Chunk.empty,
              selected,
              EventPublisher.discard,
              ModelStreamContext("reply", "request")
            )
          )
      case TokenLimitEntry.TransportStreaming =>
        ChatCompletionsTransport
          .make(http, retryPolicy = retry, tokenLimitField = field)
          .flatMap(_.complete(history, Chunk.empty, selected))
      case TokenLimitEntry.TransportJson =>
        ChatCompletionsTransport
          .makeNonStreaming(http, retryPolicy = retry, tokenLimitField = field)
          .flatMap(_.complete(history, Chunk.empty, selected))

  private enum RetryExclusion:
    case Acquisition, ErrorBody, ErrorBodyDefect, StreamBody, Parser, Transform, Callback

  private enum RetryCleanup:
    case Release, Defect, Cancellation, BodyCancellation

  private val config  = LlmConfig("test-model", endpoint = "http://localhost/completion")
  private val text    = frame("""{"choices":[{"index":0,"delta":{"content":"hello"}}]}""")
  private val stop    = frame("""{"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""")
  private val done    = "data: [DONE]\n\n"
  private val success = text + stop + done

  def spec: Spec[TestEnvironment & Scope, Any] = suite("ChatCompletionsTransport")(
    (test(
      "public JSON and SSE factories retain the selected token field across actual HTTP retries"
    ):
      ZIO
        .foreach(Chunk.fromArray(TokenLimitEntry.values)): entry =>
          ZIO.foreach(
            Chunk(
              ChatCompletionTokenLimitField.MaxTokens,
              ChatCompletionTokenLimitField.MaxCompletionTokens
            )
          ): field =>
            val (media, wire) = entry match
              case TokenLimitEntry.ClientJson | TokenLimitEntry.TransportJson =>
                (
                  "application/json",
                  """{"object":"chat.completion","choices":[{"index":0,"message":{"role":"assistant","content":"hello"},"finish_reason":"stop"}]}"""
                )
              case _ => ("text/event-stream", success)
            ZIO
              .scoped:
                for
                  sent  <- Ref.make(Chunk.empty[String])
                  retry <- ZIO.fromEither(CompletionRetryPolicy.make(2))
                  port <- Server.install(Routes(Method.POST / "completion" -> handler {
                    (request: Request) =>
                      for
                        raw    <- request.body.asString.orDie
                        bodies <- sent.updateAndGet(_ :+ raw)
                      yield
                        if bodies.size % 2 == 1 then
                          Response(
                            status = Status.ServiceUnavailable,
                            body = Body.fromString("busy")
                          )
                        else
                          Response(
                            headers = Headers("Content-Type" -> media),
                            body = Body.fromString(wire)
                          )
                  }))
                  http <- ZIO.service[Client]
                  outputs <- ZIO.foreach(Chunk(None, Some(37))): limit =>
                    tokenLimitRequest(
                      entry,
                      http,
                      config
                        .copy(endpoint = s"http://127.0.0.1:$port/completion", maxTokens = limit),
                      field,
                      retry
                    )
                  bodies <- sent.get
                  expected =
                    if field == ChatCompletionTokenLimitField.MaxTokens then "max_tokens"
                    else "max_completion_tokens"
                  absent =
                    if field == ChatCompletionTokenLimitField.MaxTokens then "max_completion_tokens"
                    else "max_tokens"
                yield assertTrue(
                  outputs.forall(_.message.text == "hello"),
                  bodies.size == 4,
                  bodies.take(2).distinct.size == 1,
                  bodies.drop(2).distinct.size == 1,
                  bodies.zipWithIndex.forall: (raw, index) =>
                    raw
                      .fromJson[Json.Obj]
                      .exists(body =>
                        body.get(expected) == Option
                          .when(index >= 2)(Json.Num(37)) && body.get(absent).isEmpty
                      )
                )
              .provide(Client.default, Server.defaultWithPort(0))
        .map(_.flatten.foldLeft(assertTrue(true))(_ && _))
    ) @@ TestAspect.withLiveClock,
    test("both explicit client response modes forward the selected image-part tool profile"):
      val factories: Chunk[(Client => IO[AgentError, ChatCompletionsClient], String, String)] =
        Chunk(
          (
            (http: Client) =>
              ChatCompletionsClient.make(http, toolResultFormat = ToolResultFormat.ImageParts),
            "text/event-stream",
            success
          ),
          (
            (http: Client) =>
              ChatCompletionsClient
                .makeNonStreaming(http, toolResultFormat = ToolResultFormat.ImageParts),
            "application/json",
            """{"object":"chat.completion","choices":[{"index":0,"message":{"role":"assistant","content":"hello"},"finish_reason":"stop"}]}"""
          )
        )
      ZIO
        .foreach(factories): (make, mediaType, response) =>
          ZIO.scoped:
            for
              sent <- Ref.make(Option.empty[String])
              client <- make(
                scripted(request =>
                  request.body.asString
                    .flatMap(body => sent.set(Some(body)))
                    .as(
                      Response(
                        headers = Headers(Header.ContentType.parse(mediaType).toOption.toList),
                        body = Body.fromString(response)
                      )
                    )
                )
              )
              hub <- ZIO.acquireRelease(Hub.unbounded[AgentEvent])(_.shutdown)
              history = Chunk(
                Msg.assistant(Chunk(ContentBlock.ToolUse("image-call", "search", Json.Obj()))),
                Msg.tool(
                  Chunk(
                    ContentBlock.ToolResult(
                      "image-call",
                      Some("search"),
                      Chunk(ContentBlock.Image(MediaSource.Base64("image/png", "aGVsbG8=")))
                    )
                  )
                )
              )
              output <- client.streamTurn(
                history,
                Chunk.empty,
                config,
                EventPublisher.borrowedHub(hub),
                ModelStreamContext("reply", "request")
              )
              body <- sent.get
            yield assertTrue(
              output.message.text == "hello",
              body
                .flatMap(_.fromJson[Json.Obj].toOption)
                .flatMap(_.get("messages"))
                .flatMap(_.asArray)
                .contains(
                  Chunk(
                    Json.Obj(
                      "role"    -> Json.Str("assistant"),
                      "content" -> Json.Str(""),
                      "name"    -> Json.Str("assistant"),
                      "tool_calls" -> Json.Arr(
                        Json.Obj(
                          "id"   -> Json.Str("image-call"),
                          "type" -> Json.Str("function"),
                          "function" -> Json
                            .Obj("name" -> Json.Str("search"), "arguments" -> Json.Str("{}"))
                        )
                      )
                    ),
                    Json.Obj(
                      "role"         -> Json.Str("tool"),
                      "tool_call_id" -> Json.Str("image-call"),
                      "content" -> Json.Arr(
                        Json.Obj(
                          "type" -> Json.Str("image_url"),
                          "image_url" -> Json
                            .Obj("url" -> Json.Str("data:image/png;base64,aGVsbG8="))
                        )
                      )
                    )
                  )
                )
            )
        .map(_.foldLeft(assertTrue(true))(_ && _))
    ,
    (test(
      "retries only selected HTTP rejection statuses with bounded attempts and original failures"
    ):
      for
        enabled <- ZIO.fromEither(CompletionRetryPolicy.make(3))
        single  <- ZIO.fromEither(CompletionRetryPolicy.make(1))
        cases = Chunk(429, 500, 502, 503, 504).map(status => (status, enabled, 3)) ++
          Chunk(400, 401, 403, 408, 501).map(status => (status, enabled, 1)) ++
          Chunk((429, CompletionRetryPolicy.disabled, 1), (429, single, 1))
        statuses <- ZIO.foreach(cases): (status, policy, expected) =>
          for
            sent <- Ref.make(0)
            transport <- ChatCompletionsTransport.make(
              scripted(_ =>
                sent
                  .updateAndGet(_ + 1)
                  .map(attempt =>
                    Response(
                      status = Status.fromInt(status),
                      body = Body.fromString(s"attempt $attempt")
                    )
                  )
              ),
              retryPolicy = policy
            )
            exit  <- transport.complete(Chunk(Msg.user("q")), Chunk.empty, config).exit
            count <- sent.get
          yield assertTrue(
            count == expected,
            exit.causeOption.contains(
              Cause.fail(
                AgentError.ModelError(
                  status,
                  "HTTP completion request was rejected",
                  Some(s"attempt $expected")
                )
              )
            )
          )
        excluded <- ZIO.foreach(Chunk.fromArray(RetryExclusion.values)): scenario =>
          val failure = AgentError.ModelError(429, "not an HTTP rejection")
          val defect  = new IllegalStateException("callback failed")
          for
            sent <- Ref.make(0)
            transport <- ChatCompletionsTransport.make(
              scripted(_ =>
                sent.update(_ + 1) *> (scenario match
                  case RetryExclusion.Acquisition => ZIO.fail(failure)
                  case RetryExclusion.ErrorBody =>
                    ZIO.succeed(
                      Response(
                        status = Status.TooManyRequests,
                        body = Body.fromStreamChunked(ZStream.fail(failure))
                      )
                    )
                  case RetryExclusion.ErrorBodyDefect =>
                    ZIO.succeed(
                      Response(
                        status = Status.TooManyRequests,
                        body =
                          Body.fromStreamChunked(ZStream.fail(failure).ensuring(ZIO.die(defect)))
                      )
                    )
                  case RetryExclusion.StreamBody =>
                    ZIO.succeed(streamResponse(ZStream.fail(failure)))
                  case RetryExclusion.Parser => ZIO.succeed(response(frame("not-json") + done))
                  case _                     => ZIO.succeed(response(success)))
              ),
              retryPolicy = enabled
            )
            exit <- transport
              .observe(Chunk(Msg.user("q")), Chunk.empty, config)(
                (_, _) =>
                  scenario match
                    case RetryExclusion.Callback => ZIO.die(defect)
                    case _                       => ZIO.unit,
                fragment =>
                  scenario match
                    case RetryExclusion.Transform =>
                      ZIO.fail(AgentError.TextTransformationError("transform failed"))
                    case _ => ZIO.succeed(fragment.delta)
              )
              .exit
            count <- sent.get
          yield assertTrue(count == 1, exit.isFailure) && (scenario match
            case RetryExclusion.Callback =>
              assertTrue(exit.causeOption.exists(_.defects == List(defect)))
            case RetryExclusion.Transform =>
              assertTrue(
                exit.causeOption
                  .contains(Cause.fail(AgentError.TextTransformationError("transform failed")))
              )
            case RetryExclusion.Acquisition =>
              assertTrue(
                exit.causeOption
                  .contains(Cause.fail(AgentError.ModelError(0, "HTTP request failed (other)")))
              )
            case RetryExclusion.ErrorBody =>
              assertTrue(
                exit.causeOption.contains(
                  Cause.fail(AgentError.ModelError(429, "Failed to read HTTP error response"))
                )
              )
            case RetryExclusion.ErrorBodyDefect =>
              assertTrue(
                exit.causeOption.contains(
                  Cause.fail(
                    AgentError.ModelError(429, "Failed to read HTTP error response")
                  ) ++ Cause.die(defect)
                )
              )
            case RetryExclusion.StreamBody =>
              assertTrue(exit.causeOption.contains(Cause.fail(failure)))
            case RetryExclusion.Parser =>
              assertTrue(
                exit.causeOption.exists(cause =>
                  cause.failures.exists(_.isInstanceOf[AgentError.DeserializationError]) &&
                    cause.failures.size == 1 && cause.defects.isEmpty && !cause.isInterrupted
                )
              ))
      yield (statuses ++ excluded).foldLeft(assertTrue(true))(_ && _)
    ) @@ TestAspect.withLiveClock,
    (test("joins rejection cleanup before retry and preserves cancellation or cleanup defects"):
      for
        policy <- ZIO.fromEither(CompletionRetryPolicy.make(2))
        outcomes <- ZIO.foreach(Chunk.fromArray(RetryCleanup.values)): scenario =>
          ZIO.scoped:
            for
              sent              <- Ref.make(0)
              entered           <- Promise.make[Nothing, Unit]
              bodyEntered       <- Promise.make[Nothing, Unit]
              release           <- Promise.make[Nothing, Unit]
              cancelled         <- Promise.make[Nothing, Unit]
              closed            <- Ref.make(false)
              secondAfterClosed <- Ref.make(false)
              defect = new IllegalStateException("rejection cleanup failed")
              cancellation = scenario match
                case RetryCleanup.Cancellation =>
                  ZIO.descriptor
                    .zip(release.isDone)
                    .flatMap:
                      case (descriptor, released) =>
                        if descriptor.interrupters.nonEmpty then cancelled.succeed(()).as(true)
                        else ZIO.yieldNow.as(released)
                case _ => ZIO.succeed(true)
              cleanup = entered.succeed(()) *> cancellation.repeatUntil(
                identity
              ) *> release.await *>
                closed.set(true) *> (scenario match
                  case RetryCleanup.Release => ZIO.unit
                  case _                    => ZIO.die(defect))
              transport <- ChatCompletionsTransport.make(
                scripted(_ =>
                  sent
                    .updateAndGet(_ + 1)
                    .flatMap:
                      case 1 =>
                        scenario match
                          case RetryCleanup.BodyCancellation =>
                            ZIO.succeed(
                              Response(
                                status = Status.TooManyRequests,
                                body = Body.fromStreamChunked(
                                  ZStream.unwrapScoped(
                                    ZIO.addFinalizer(cleanup) *> bodyEntered
                                      .succeed(())
                                      .as(ZStream.never)
                                  )
                                )
                              )
                            )
                          case _ =>
                            ZIO
                              .addFinalizer(cleanup)
                              .as(
                                Response(
                                  status = Status.TooManyRequests,
                                  body = Body.fromString("busy")
                                )
                              )
                      case _ => closed.get.flatMap(secondAfterClosed.set).as(response(success))
                ),
                retryPolicy = policy
              )
              checked <- (for
                fiber <- transport.complete(Chunk(Msg.user("q")), Chunk.empty, config).forkScoped
                _ <- scenario match
                  case RetryCleanup.BodyCancellation =>
                    bodyEntered.await *> ZIO.fiberId.flatMap(fiber.interruptAsFork)
                  case _ => ZIO.unit
                _ <- entered.await
                _ <- scenario match
                  case RetryCleanup.Cancellation =>
                    ZIO.fiberId.flatMap(fiber.interruptAsFork) *> cancelled.await
                  case _ => ZIO.unit
                pending <- fiber.poll
                before  <- sent.get
                _       <- release.succeed(())
                exit    <- fiber.await
                count   <- sent.get
                ordered <- secondAfterClosed.get
                completed = exit match
                  case Exit.Success(output) => output.message.text == "hello"
                  case _                    => false
              yield assertTrue(pending.isEmpty, before == 1) && (scenario match
                case RetryCleanup.Release => assertTrue(completed, count == 2, ordered)
                case RetryCleanup.Defect =>
                  assertTrue(
                    count == 1,
                    exit.causeOption.contains(
                      Cause.fail(
                        AgentError
                          .ModelError(429, "HTTP completion request was rejected", Some("busy"))
                      ) ++ Cause.die(defect)
                    )
                  )
                case RetryCleanup.Cancellation =>
                  assertTrue(
                    count == 1,
                    exit.isInterrupted,
                    exit.causeOption.exists(cause =>
                      cause.defects == List(defect) &&
                        cause.failures == List(
                          AgentError
                            .ModelError(429, "HTTP completion request was rejected", Some("busy"))
                        )
                    )
                  )
                case RetryCleanup.BodyCancellation =>
                  assertTrue(
                    count == 1,
                    exit.isInterrupted,
                    exit.causeOption
                      .exists(cause => cause.defects == List(defect) && cause.failures.isEmpty)
                  ))).ensuring(release.succeed(()).unit)
            yield checked
      yield outcomes.foldLeft(assertTrue(true))(_ && _)
    ) @@ TestAspect.withLiveClock,
    test("interrupts a registered retry sleep before its deterministic deadline"):
      for
        delay <- ZIO.fromEither(CompletionRetryPolicy.make(2, 1.hour))
        checked <- ZIO.scoped:
          for
            count  <- Ref.make(0)
            closed <- Promise.make[Nothing, Unit]
            transport <- ChatCompletionsTransport.make(
              scripted(_ =>
                count.update(_ + 1) *> ZIO
                  .addFinalizer(closed.succeed(()).unit)
                  .as(
                    Response(status = Status.TooManyRequests, body = Body.fromString("busy"))
                  )
              ),
              retryPolicy = delay
            )
            started <- Clock.instant
            deadline = started.plus(1.hour)
            fiber     <- transport.complete(Chunk(Msg.user("q")), Chunk.empty, config).forkScoped
            _         <- closed.await
            _         <- (ZIO.yieldNow *> TestClock.sleeps).repeatUntil(_.contains(deadline))
            _         <- TestClock.adjust(1.second)
            remaining <- TestClock.sleeps
            before    <- count.get
            pending   <- fiber.poll
            exit      <- fiber.interrupt
            sent      <- count.get
          yield assertTrue(
            remaining.contains(deadline),
            before == 1,
            pending.isEmpty,
            exit.isInterrupted,
            sent == 1
          )
      yield checked
    ,
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
        output.message.usage.exists(_.totalTokens == Right(5))
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
      val full = frame(
        """{"object":"chat.completion","choices":[{"index":0,"message":{"role":"assistant","content":"hello"},"finish_reason":"stop"}]}"""
      )
      val cases = Chunk(
        (CompletionEnvelopeProfile.IndexedDeltas, text + stop),
        (CompletionEnvelopeProfile.DeltasWithFullMessageSummary, full)
      )
      ZIO
        .foreach(cases): (profile, prefix) =>
          ZIO
            .foreach(Chunk("", "data: [DONE]", "data: [DONE]\n", "data: [DONE]\r\n")): tail =>
              for
                closed <- Ref.make(false)
                transport <- ChatCompletionsTransport.make(
                  scripted(_ => ZIO.addFinalizer(closed.set(true)).as(response(prefix + tail))),
                  envelopeProfile = profile
                )
                result   <- transport.complete(Chunk(Msg.user("q")), Chunk.empty, config).either
                released <- closed.get
              yield assertTrue(
                result.left.exists(_.message == "Event stream ended before [DONE]"),
                released
              )
            .map(_.foldLeft(assertTrue(true))(_ && _))
        .map(_.foldLeft(assertTrue(true))(_ && _))
    ,
    test("requires valid completion and rejects malformed protocol frames"):
      for
        missing   <- run(response(text + done)).either
        malformed <- run(response(frame("not-json") + done)).either
        truncated <- run(
          response(
            text + frame(
              """{"choices":[{"index":0,"delta":{},"finish_reason":"content_filter"}]}"""
            ) + done
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
    test("reports bounded type-only transport categories through the public completion boundary"):
      val secret = "https://private.example/path?api_key=secret-token"
      val cases: Chunk[(Throwable, String)] = Chunk(
        (new java.net.UnknownHostException(secret), "dns"),
        (new javax.net.ssl.SSLHandshakeException(secret), "tls"),
        (new java.net.ConnectException(secret), "connection"),
        (new java.net.SocketTimeoutException(secret), "timeout"),
        (new java.net.http.HttpTimeoutException(secret), "timeout"),
        (new java.util.concurrent.TimeoutException(secret), "timeout"),
        (new java.io.IOException(secret, new java.net.UnknownHostException(secret)), "dns"),
        (new IllegalStateException(secret), "other")
      )
      ZIO
        .foreach(cases): (failure, category) =>
          for
            transport <- ChatCompletionsTransport.make(scripted(_ => ZIO.fail(failure)))
            result    <- transport.complete(Chunk(Msg.user("q")), Chunk.empty, config).either
          yield assertTrue(
            result == Left(AgentError.ModelError(0, s"HTTP request failed ($category)"))
          )
        .map(_.foldLeft(assertTrue(true))(_ && _))
    ,
    test("classifies external body failures while retaining domain observations"):
      val failure = new java.net.SocketTimeoutException("secret endpoint and token")
      val domain  = AgentError.InvalidMessage("domain observation")
      for
        streaming <- run(streamResponse(ZStream.fail(failure))).either
        jsonTransport <- ChatCompletionsTransport.makeNonStreaming(
          scripted(_ =>
            ZIO.succeed(
              Response(
                headers = Headers("Content-Type", "application/json"),
                body = Body.fromStreamChunked(ZStream.fail(failure))
              )
            )
          )
        )
        json <- jsonTransport.complete(Chunk(Msg.user("q")), Chunk.empty, config).either
        rejected <- run(
          Response(status = Status.BadRequest, body = Body.fromStreamChunked(ZStream.fail(failure)))
        ).either
        preserved <- run(streamResponse(ZStream.fail(domain))).either
      yield assertTrue(
        streaming == Left(
          AgentError.ModelError(200, "Failed to read a UTF-8 event stream (timeout)")
        ),
        json == Left(AgentError.ModelError(200, "Failed to read a UTF-8 JSON response (timeout)")),
        rejected == Left(
          AgentError.ModelError(400, "Failed to read HTTP error response (timeout)")
        ),
        preserved == Left(domain)
      )
    ,
    test("acquisition interruption remains interrupted and joins owned cleanup"):
      ZIO.scoped:
        for
          entered <- Promise.make[Nothing, Unit]
          closed  <- Ref.make(false)
          transport <- ChatCompletionsTransport.make(
            scripted(_ => ZIO.addFinalizer(closed.set(true)) *> entered.succeed(()) *> ZIO.never)
          )
          fiber    <- transport.complete(Chunk(Msg.user("q")), Chunk.empty, config).forkScoped
          _        <- entered.await
          exit     <- fiber.interrupt
          released <- closed.get
        yield assertTrue(exit.isInterrupted, released, exit.causeOption.forall(_.failures.isEmpty))
    ,
    test("normalizes network and body failures without swallowing cancellation"):
      for
        transport <- ChatCompletionsTransport.make(
          scripted(_ => ZIO.fail(AgentError.InvalidMessage("internal detail")))
        )
        network <- transport.complete(Chunk(Msg.user("q")), Chunk.empty, config).either
        body <- run(streamResponse(ZStream.fail(AgentError.InvalidMessage("body failed")))).either
      yield assertTrue(network.left.exists(_.message == "HTTP request failed (other)"), body.isLeft)
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
