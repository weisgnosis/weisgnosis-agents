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
import com.weisgnosis.agents.core.json.JsonBoundary
import com.weisgnosis.agents.core.model.Msg
import com.weisgnosis.agents.core.tool.ToolDef
import java.net.URI
import java.nio.charset.StandardCharsets
import zio.*
import zio.http.{Body, Client, Headers, Request, Response, URL}
import zio.json.*
import zio.stream.{ZPipeline, ZStream}

final case class CompletionTransportLimits(
    responseBytes: Long = 8L * 1024 * 1024,
    errorBodyBytes: Int = 8192
) derives JsonCodec

/** Scoped HTTP transport for explicit SSE or ordinary JSON text/function completion. */
final class ChatCompletionsTransport private (
    http: Client,
    limits: CompletionTransportLimits,
    reasoningReplay: ReasoningReplay,
    toolSignatureFormat: ToolSignatureFormat,
    retryPolicy: CompletionRetryPolicy,
    responseMode: CompletionResponseMode,
    toolResultFormat: ToolResultFormat
):
  import ChatCompletionsTransport.*

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
  )(
      onFrame: (String, Chunk[LlmChunk]) => UIO[Unit],
      transformText: LlmChunk.TextDelta => ZIO[Scope, AgentError.TextTransformationError, String] =
        fragment => ZIO.succeed(fragment.delta)
  ): IO[AgentError, TurnOutput] =
    BeforeResponseRejection.expose(observeAttempt(history, tools, config)(onFrame, transformText))

  private[client] def observeAttempt(
      history: Chunk[Msg],
      tools: Chunk[ToolDef],
      config: LlmConfig
  )(
      onFrame: (String, Chunk[LlmChunk]) => UIO[Unit],
      transformText: LlmChunk.TextDelta => ZIO[Scope, AgentError.TextTransformationError, String],
      recordPartial: Option[TurnOutput.Interrupted] => UIO[Unit] = _ => ZIO.unit
  ): IO[AgentError | BeforeResponseRejection, TurnOutput] =
    ZIO.suspendSucceed:
      for
        body <- ZIO.fromEither(responseMode match
          case CompletionResponseMode.Streaming(_) =>
            ChatCompletionsRequest
              .encode(
                history,
                tools,
                config,
                reasoningReplay,
                toolSignatureFormat,
                toolResultFormat
              )
          case CompletionResponseMode.NonStreaming =>
            ChatCompletionsRequest.encodeNonStreaming(
              history,
              tools,
              config,
              reasoningReplay,
              toolSignatureFormat,
              toolResultFormat
            ))
        url <- endpoint(config.endpoint)
        _ <- ZIO
          .fail(
            AgentError.InvalidConfiguration(
              "API key must contain visible non-space ASCII characters"
            )
          )
          .unless(config.apiKey.forall(key => key.nonEmpty && key.forall(c => c > ' ' && c < 127)))
        accepted = responseMode match
          case CompletionResponseMode.Streaming(_) => "text/event-stream"
          case CompletionResponseMode.NonStreaming => "application/json"
        headers = Headers("Content-Type" -> "application/json", "Accept" -> accepted) ++
          config.apiKey.fold(Headers.empty)(key => Headers("Authorization", s"Bearer $key"))
        request = Request.post(url, Body.fromString(JsonBoundary.render(body))).addHeaders(headers)
        output <- retryPolicy match
          case CompletionRetryPolicy.Disabled =>
            exchange(request, 1, Duration.Zero, onFrame, transformText, recordPartial)
          case policy: CompletionRetryPolicy.HttpStatusRetries =>
            exchange(
              request,
              policy.maxAttempts,
              policy.delay,
              onFrame,
              transformText,
              recordPartial
            )
      yield output

  private def exchange(
      request: Request,
      remaining: Int,
      delay: Duration,
      onFrame: (String, Chunk[LlmChunk]) => UIO[Unit],
      transformText: LlmChunk.TextDelta => ZIO[Scope, AgentError.TextTransformationError, String],
      recordPartial: Option[TurnOutput.Interrupted] => UIO[Unit]
  ): IO[AgentError | BeforeResponseRejection, TurnOutput] =
    ZIO.uninterruptibleMask: restore =>
      restore(attempt(request, onFrame, transformText, recordPartial)).exit.flatMap:
        case Exit.Success(output) => restore(ZIO.succeed(output))
        case Exit.Failure(cause) =>
          val failure = cause.map {
            case HttpRejection(error) => error
            case error: AgentError    => error
          }
          if cause.isInterrupted then ZIO.refailCause(failure)
          else
            // Observe cancellation after joined cleanup without discarding its failed outcome.
            restore(ZIO.unit).exit.flatMap:
              case Exit.Failure(interruption) => ZIO.refailCause(failure ++ interruption)
              case Exit.Success(_) =>
                cause match
                  case Cause.Fail(HttpRejection(error), _)
                      if remaining > 1 && retryableStatuses.contains(error.statusCode) =>
                    restore(
                      ZIO.sleep(delay) *> exchange(
                        request,
                        remaining - 1,
                        delay,
                        onFrame,
                        transformText,
                        recordPartial
                      )
                    )
                  case Cause.Fail(HttpRejection(error), _)
                      if retryableStatuses.contains(error.statusCode) =>
                    ZIO.fail(new BeforeResponseRejection(error))
                  case _ => ZIO.refailCause(failure)

  private def attempt(
      request: Request,
      onFrame: (String, Chunk[LlmChunk]) => UIO[Unit],
      transformText: LlmChunk.TextDelta => ZIO[Scope, AgentError.TextTransformationError, String],
      recordPartial: Option[TurnOutput.Interrupted] => UIO[Unit]
  ): IO[AttemptFailure, TurnOutput] =
    ZIO.scoped:
      for
        _ <- recordPartial(None)
        response <- ZIO.uninterruptibleMask: restore =>
          restore(Client.streaming(request).provideSomeEnvironment[Scope](_.add(http)))
            .mapError(_ => AgentError.ModelError(0, "HTTP request failed"))
        output <-
          if response.status.isSuccess then
            responseMode match
              case CompletionResponseMode.Streaming(profile) =>
                consumeEventStream(response, onFrame, transformText, profile, recordPartial)
              case CompletionResponseMode.NonStreaming =>
                consumeJson(response, onFrame, transformText, recordPartial)
          else reject(response)
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

  private def consumeEventStream(
      response: Response,
      onFrame: (String, Chunk[LlmChunk]) => UIO[Unit],
      transformText: LlmChunk.TextDelta => ZIO[Scope, AgentError.TextTransformationError, String],
      envelopeProfile: CompletionEnvelopeProfile,
      recordPartial: Option[TurnOutput.Interrupted] => UIO[Unit]
  ): ZIO[Scope, AgentError, TurnOutput] =
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
        .runFoldZIO[Scope, AgentError, ChatCompletionsTransport.StreamState](
          ChatCompletionsTransport.StreamState.Reading(
            StreamAccumulator.empty,
            CompletionEnvelopeDecoder.initial(envelopeProfile, toolSignatureFormat, reasoningReplay)
          )
        ):
          case (ChatCompletionsTransport.StreamState.Reading(accumulator, decoder), frame) =>
            if frame.isDone then
              ZIO
                .fromEither(accumulator.toTurnOutput)
                .map(ChatCompletionsTransport.StreamState.Completed(_))
            else
              for
                decoded <- ZIO.fromEither(decoder.decode(frame.data, accumulator.completionReason))
                (nextDecoder, deltas) = decoded
                _ <- ZIO.fromEither(accumulate(accumulator, deltas))
                transformed <- ZIO.foreach(deltas):
                  case fragment: LlmChunk.TextDelta =>
                    transformText(fragment).map(text => fragment.copy(delta = text))
                  case other => ZIO.succeed(other)
                next <- ZIO.fromEither(accumulate(accumulator, transformed))
                _    <- recordPartial(Some(next.toInterrupted))
                _    <- onFrame(frame.data, transformed)
              yield ChatCompletionsTransport.StreamState.Reading(next, nextDecoder)
          case (completed: ChatCompletionsTransport.StreamState.Completed, _) =>
            ZIO.succeed(completed)
      output <- state match
        case ChatCompletionsTransport.StreamState.Completed(value) => ZIO.succeed(value)
        case ChatCompletionsTransport.StreamState.Reading(_, _) =>
          ZIO.fail(AgentError.ModelError(status, "Event stream ended before [DONE]"))
    yield output

  private def consumeJson(
      response: Response,
      onFrame: (String, Chunk[LlmChunk]) => UIO[Unit],
      transformText: LlmChunk.TextDelta => ZIO[Scope, AgentError.TextTransformationError, String],
      recordPartial: Option[TurnOutput.Interrupted] => UIO[Unit]
  ): ZIO[Scope, AgentError, TurnOutput] =
    val status = response.status.code
    val jsonBody = response.rawHeaders("Content-Type") match
      case values if values.size == 1 =>
        values.headOption.exists(_.takeWhile(_ != ';').trim.equalsIgnoreCase("application/json"))
      case _ => false
    for
      _ <- ZIO
        .fail(AgentError.ModelError(status, "Expected an application/json response"))
        .unless(jsonBody)
      parts <- bounded(response.body.asStream, status)
        .via(ZPipeline.utf8Decode)
        .mapError:
          case error: AgentError => error
          case _ => AgentError.ModelError(status, "Failed to read a UTF-8 JSON response")
        .runCollect
      raw = parts.mkString
      deltas <- ZIO.fromEither(
        CompletionEnvelopeDecoder.fullMessage(raw, toolSignatureFormat, reasoningReplay)
      )
      transformed <- ZIO.foreach(deltas):
        case fragment: LlmChunk.TextDelta =>
          transformText(fragment).map(text => fragment.copy(delta = text))
        case other => ZIO.succeed(other)
      accumulated <- ZIO.fromEither(accumulate(StreamAccumulator.empty, transformed))
      output      <- ZIO.fromEither(accumulated.toTurnOutput)
      _           <- recordPartial(Some(accumulated.toInterrupted))
      _           <- onFrame(raw, transformed)
    yield output

  private def accumulate(
      initial: StreamAccumulator,
      deltas: Chunk[LlmChunk]
  ): Either[AgentError, StreamAccumulator] =
    deltas.foldLeft[Either[AgentError, StreamAccumulator]](Right(initial))((current, delta) =>
      current.flatMap(_.feed(delta))
    )

  private def bounded(
      bytes: ZStream[Any, Throwable, Byte],
      status: Int
  ): ZStream[Any, Throwable, Byte] =
    bytes.chunks
      .mapAccumZIO(0L): (consumed, chunk) =>
        if chunk.size.toLong > limits.responseBytes - consumed then
          ZIO.fail(
            AgentError.ModelError(status, "Completion response exceeds the configured byte limit")
          )
        else ZIO.succeed((consumed + chunk.size, chunk))
      .flattenChunks

  private def reject(response: Response): IO[AttemptFailure, Nothing] =
    ZIO.uninterruptibleMask: restore =>
      restore(response.body.asStream.take(limits.errorBodyBytes.toLong).runCollect)
        .mapError(_ =>
          AgentError.ModelError(response.status.code, "Failed to read HTTP error response")
        )
        .flatMap(bytes =>
          ZIO.fail(
            HttpRejection(
              AgentError.ModelError(
                response.status.code,
                "HTTP completion request was rejected",
                Some(new String(bytes.toArray, StandardCharsets.UTF_8))
              )
            )
          )
        )

object ChatCompletionsTransport:
  final private case class HttpRejection(error: AgentError.ModelError)
  private type AttemptFailure = AgentError | HttpRejection
  private val retryableStatuses = Set(429, 500, 502, 503, 504)

  private enum StreamState:
    case Reading(accumulator: StreamAccumulator, decoder: CompletionEnvelopeDecoder)
    case Completed(output: TurnOutput)

  def make(
      http: Client,
      limits: CompletionTransportLimits = CompletionTransportLimits(),
      reasoningReplay: ReasoningReplay = ReasoningReplay.Omit,
      toolSignatureFormat: ToolSignatureFormat = ToolSignatureFormat.Unsupported,
      retryPolicy: CompletionRetryPolicy = CompletionRetryPolicy.disabled,
      envelopeProfile: CompletionEnvelopeProfile = CompletionEnvelopeProfile.IndexedDeltas,
      toolResultFormat: ToolResultFormat = ToolResultFormat.TextOnly
  ): IO[AgentError, ChatCompletionsTransport] =
    configured(
      http,
      limits,
      reasoningReplay,
      toolSignatureFormat,
      retryPolicy,
      CompletionResponseMode.Streaming(envelopeProfile),
      toolResultFormat
    )

  /** Explicit ordinary JSON completion; response validation waits for bounded body EOF. */
  def makeNonStreaming(
      http: Client,
      limits: CompletionTransportLimits = CompletionTransportLimits(),
      reasoningReplay: ReasoningReplay = ReasoningReplay.Omit,
      toolSignatureFormat: ToolSignatureFormat = ToolSignatureFormat.Unsupported,
      retryPolicy: CompletionRetryPolicy = CompletionRetryPolicy.disabled,
      toolResultFormat: ToolResultFormat = ToolResultFormat.TextOnly
  ): IO[AgentError, ChatCompletionsTransport] =
    configured(
      http,
      limits,
      reasoningReplay,
      toolSignatureFormat,
      retryPolicy,
      CompletionResponseMode.NonStreaming,
      toolResultFormat
    )

  private def configured(
      http: Client,
      limits: CompletionTransportLimits,
      reasoningReplay: ReasoningReplay,
      toolSignatureFormat: ToolSignatureFormat,
      retryPolicy: CompletionRetryPolicy,
      mode: CompletionResponseMode,
      toolResultFormat: ToolResultFormat
  ): IO[AgentError, ChatCompletionsTransport] =
    ZIO.fromEither:
      Either.cond(
        limits.responseBytes > 0 && limits.errorBodyBytes > 0,
        new ChatCompletionsTransport(
          http,
          limits,
          reasoningReplay,
          toolSignatureFormat,
          retryPolicy,
          mode,
          toolResultFormat
        ),
        AgentError.InvalidConfiguration("Transport byte limits must be positive")
      )
