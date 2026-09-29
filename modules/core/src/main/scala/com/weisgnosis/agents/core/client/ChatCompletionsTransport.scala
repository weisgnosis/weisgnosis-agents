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
import com.weisgnosis.agents.core.tool.ToolDef
import java.net.URI
import java.nio.charset.StandardCharsets
import zio.*
import zio.http.{Body, Client, Headers, Request, Response, URL}
import zio.json.*
import zio.stream.ZStream

final case class CompletionTransportLimits(
    responseBytes: Long = 8L * 1024 * 1024,
    errorBodyBytes: Int = 8192
) derives JsonCodec

/** Scoped HTTP transport for the validated text/function streaming protocol. */
final class ChatCompletionsTransport private (
    http: Client,
    limits: CompletionTransportLimits,
    reasoningReplay: ReasoningReplay
):
  /** Owns the request scope, closing it before success, failure, or interruption returns. */
  def complete(
      history: Chunk[Msg],
      tools: Chunk[ToolDef],
      config: LlmConfig
  ): IO[AgentError, TurnOutput] =
    observe(history, tools, config)((_, _) => ZIO.unit)

  private[client] def observe(
      history: Chunk[Msg],
      tools: Chunk[ToolDef],
      config: LlmConfig
  )(onFrame: (String, Chunk[LlmChunk]) => UIO[Unit]): IO[AgentError, TurnOutput] =
    ZIO.scoped:
      for
        body <- ZIO.fromEither(
          ChatCompletionsRequest.encode(history, tools, config, reasoningReplay)
        )
        url <- endpoint(config.endpoint)
        _ <- ZIO
          .fail(
            AgentError.InvalidConfiguration(
              "API key must contain visible non-space ASCII characters"
            )
          )
          .unless(config.apiKey.forall(key => key.nonEmpty && key.forall(c => c > ' ' && c < 127)))
        headers = Headers("Content-Type" -> "application/json", "Accept" -> "text/event-stream") ++
          config.apiKey.fold(Headers.empty)(key => Headers("Authorization", s"Bearer $key"))
        request = Request.post(url, Body.fromString(body.toJson)).addHeaders(headers)
        response <- Client
          .streaming(request)
          .provideSomeEnvironment[Scope](_.add(http))
          .mapError(_ => AgentError.ModelError(0, "HTTP request failed"))
        output <- if response.status.isSuccess then consume(response, onFrame) else reject(response)
      yield output

  private def endpoint(value: String): IO[AgentError, URL] =
    val invalid = AgentError.InvalidConfiguration(
      "Endpoint must be an absolute HTTP(S) URL without user information or a fragment"
    )
    for
      uri <- ZIO.attempt(new URI(value)).mapError(_ => invalid)
      _ <- ZIO
        .fail(invalid)
        .unless(
          Option(uri.getScheme).exists(scheme =>
            scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https")
          ) &&
            Option(uri.getHost).exists(_.nonEmpty) &&
            Option(uri.getUserInfo).isEmpty && Option(uri.getFragment).isEmpty &&
            (uri.getPort == -1 || (uri.getPort > 0 && uri.getPort <= 65535))
        )
      url <- ZIO.fromEither(URL.decode(value)).mapError(_ => invalid)
    yield url

  private def consume(
      response: Response,
      onFrame: (String, Chunk[LlmChunk]) => UIO[Unit]
  ): IO[AgentError, TurnOutput] =
    val status = response.status.code
    val eventStream = response.rawHeaders("Content-Type") match
      case values if values.size == 1 =>
        values.headOption.exists(_.takeWhile(_ != ';').trim.equalsIgnoreCase("text/event-stream"))
      case _ => false
    for
      _ <- ZIO
        .fail(AgentError.ModelError(status, "Expected a text/event-stream response"))
        .unless(eventStream)
      state <- bounded(response.body.asStream, status)
        .via(SseParser.parseBytes)
        .mapError {
          case error: AgentError => error
          case _ => AgentError.ModelError(status, "Failed to read a UTF-8 event stream")
        }
        .takeUntil(_.isDone)
        .runFoldZIO[Any, AgentError, ChatCompletionsTransport.StreamState](
          ChatCompletionsTransport.StreamState.Reading(StreamAccumulator.empty)
        ):
          case (ChatCompletionsTransport.StreamState.Reading(accumulator), frame) =>
            if frame.isDone then
              ZIO
                .fromEither(accumulator.toTurnOutput)
                .map(ChatCompletionsTransport.StreamState.Completed(_))
            else
              for
                deltas <- ZIO.fromEither(ReasoningModelAdapter.parseChunk(frame.data))
                next <- ZIO.fromEither(
                  deltas.foldLeft[Either[AgentError, StreamAccumulator]](Right(accumulator))(
                    (current, delta) => current.flatMap(_.feed(delta))
                  )
                )
                _ <- onFrame(frame.data, deltas)
              yield ChatCompletionsTransport.StreamState.Reading(next)
          case (completed: ChatCompletionsTransport.StreamState.Completed, _) =>
            ZIO.succeed(completed)
      output <- state match
        case ChatCompletionsTransport.StreamState.Completed(value) => ZIO.succeed(value)
        case ChatCompletionsTransport.StreamState.Reading(_) =>
          ZIO.fail(AgentError.ModelError(status, "Event stream ended before [DONE]"))
    yield output

  private def bounded(
      bytes: ZStream[Any, Throwable, Byte],
      status: Int
  ): ZStream[Any, Throwable, Byte] =
    bytes.chunks
      .mapAccumZIO(0L): (consumed, chunk) =>
        if chunk.size.toLong > limits.responseBytes - consumed then
          ZIO.fail(AgentError.ModelError(status, "Event stream exceeds the configured byte limit"))
        else ZIO.succeed((consumed + chunk.size, chunk))
      .flattenChunks

  private def reject(response: Response): IO[AgentError, Nothing] =
    response.body.asStream
      .take(limits.errorBodyBytes.toLong)
      .runCollect
      .map(bytes => new String(bytes.toArray, StandardCharsets.UTF_8))
      .mapError(_ =>
        AgentError.ModelError(response.status.code, "Failed to read HTTP error response")
      )
      .flatMap(payload =>
        ZIO.fail(
          AgentError
            .ModelError(response.status.code, "HTTP completion request was rejected", Some(payload))
        )
      )

object ChatCompletionsTransport:
  private enum StreamState:
    case Reading(accumulator: StreamAccumulator)
    case Completed(output: TurnOutput)

  def make(
      http: Client,
      limits: CompletionTransportLimits = CompletionTransportLimits(),
      reasoningReplay: ReasoningReplay = ReasoningReplay.Omit
  ): IO[AgentError, ChatCompletionsTransport] =
    ZIO.fromEither:
      Either.cond(
        limits.responseBytes > 0 && limits.errorBodyBytes > 0,
        new ChatCompletionsTransport(http, limits, reasoningReplay),
        AgentError.InvalidConfiguration("Transport byte limits must be positive")
      )
