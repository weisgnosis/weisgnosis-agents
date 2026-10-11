/* Copyright 2024-2026 WeisGnosis. Licensed under the Apache License, Version 2.0. */
package com.weisgnosis.agents.core.client

import com.weisgnosis.agents.core.error.{AgentError, TransportFailure}
import com.weisgnosis.agents.core.event.{AgentEvent, ModelStreamContext}
import com.weisgnosis.agents.core.json.JsonBoundary
import com.weisgnosis.agents.core.model.Msg
import com.weisgnosis.agents.core.tool.ToolDef
import java.net.URI
import java.nio.charset.StandardCharsets
import zio.*
import zio.http.{Body, Client, Headers, Request, Response, URL}
import zio.stream.ZStream

/** Native streaming text and local function calls, or explicitly selected provider-hosted code. */
final class GeminiLlmClient private (
    http: Client,
    execution: GeminiCodeExecution,
    limits: CompletionTransportLimits
) extends LlmClient:
  def streamTurn(
      history: Chunk[Msg],
      tools: Chunk[ToolDef],
      config: LlmConfig,
      eventHub: Hub[AgentEvent],
      context: ModelStreamContext
  ): ZIO[Scope, AgentError, TurnOutput] =
    attemptTurnWithProgress(history, tools, config, eventHub, context, _ => ZIO.unit)

  override private[core] def attemptTurnWithProgress(
      history: Chunk[Msg],
      tools: Chunk[ToolDef],
      config: LlmConfig,
      eventHub: Hub[AgentEvent],
      context: ModelStreamContext,
      recordPartial: Option[TurnOutput.Interrupted] => UIO[Unit]
  ): ZIO[Scope, AgentError, TurnOutput] =
    ZIO.scoped:
      for
        body <- ZIO.fromEither(GeminiMessagesRequest.encode(history, tools, config, execution))
        url  <- endpoint(config.endpoint)
        _ <- ZIO
          .fail(AgentError.InvalidConfiguration("Gemini API key must be visible ASCII"))
          .unless(
            config.apiKey.forall(value => value.nonEmpty && value.forall(c => c > ' ' && c < 127))
          )
        headers = Headers("Content-Type" -> "application/json", "Accept" -> "text/event-stream") ++
          config.apiKey.fold(Headers.empty)(value => Headers("x-goog-api-key" -> value))
        publisher <- ModelStreamPublisher.make(eventHub, context)
        response <- Client
          .streaming(
            Request.post(url, Body.fromString(JsonBoundary.render(body))).addHeaders(headers)
          )
          .provideSomeEnvironment[Scope](_.add(http))
          .mapError(error =>
            AgentError
              .ModelError(0, TransportFailure.describe("Gemini HTTP acquisition failed", error))
          )
        output <-
          if response.status.isSuccess then consume(response, publisher, context, recordPartial)
          else reject(response)
        _ <- publisher.finish(output)
      yield output

  private def consume(
      response: Response,
      publisher: ModelStreamPublisher,
      context: ModelStreamContext,
      recordPartial: Option[TurnOutput.Interrupted] => UIO[Unit]
  ): ZIO[Scope, AgentError, TurnOutput] =
    Ref
      .make(GeminiMessageStream.empty(context.requestId, execution))
      .flatMap: latest =>
        (
          for
            _ <- ZIO
              .fail(
                AgentError
                  .ModelError(response.status.code, "Expected Gemini text/event-stream response")
              )
              .unless(
                response.rawHeaders("Content-Type").size == 1 && response
                  .rawHeaders("Content-Type")
                  .headOption
                  .exists(_.takeWhile(_ != ';').trim.equalsIgnoreCase("text/event-stream"))
              )
            state <- bounded(response.body.asStream, response.status.code)
              .via(SseParser.parseBytes)
              .mapError:
                case error: AgentError => error
                case error =>
                  AgentError.ModelError(
                    response.status.code,
                    TransportFailure.describe("Failed to read Gemini UTF-8 stream", error)
                  )
              .mapAccumZIO(GeminiMessageStream.empty(context.requestId, execution))(
                (previous, frame) =>
                  for
                    accepted <- ZIO.fromEither(previous.accept(frame))
                    (next, deltas) = accepted
                    _ <- latest.set(next)
                    _ <- publisher.frame(frame.data, deltas)
                  yield next -> next
              )
              .takeUntil(_.finished)
              .runLast
            finalState <- ZIO
              .fromOption(state)
              .orElseFail(AgentError.DeserializationError("Empty Gemini stream"))
            output <- ZIO.fromEither(finalState.output)
            _      <- recordPartial(Some(TurnOutput.Interrupted(output.message)))
          yield output
        ).onInterrupt(latest.get.flatMap(value => recordPartial(Some(value.partial))))

  private def bounded(
      bytes: ZStream[Any, Throwable, Byte],
      status: Int
  ): ZStream[Any, Throwable, Byte] =
    bytes.chunks
      .mapAccumZIO(0L)((consumed, chunk) =>
        if chunk.size.toLong > limits.responseBytes - consumed then
          ZIO.fail(AgentError.ModelError(status, "Gemini response exceeds configured byte limit"))
        else ZIO.succeed((consumed + chunk.size, chunk))
      )
      .flattenChunks

  private def reject(response: Response): IO[AgentError, Nothing] =
    response.body.asStream
      .take(limits.errorBodyBytes.toLong)
      .runCollect
      .mapError(error =>
        AgentError.ModelError(
          response.status.code,
          TransportFailure.describe("Failed to read Gemini HTTP rejection", error)
        )
      )
      .flatMap(bytes =>
        ZIO.fail(
          AgentError.ModelError(
            response.status.code,
            "Gemini HTTP request rejected",
            Some(new String(bytes.toArray, StandardCharsets.UTF_8))
          )
        )
      )

  private def endpoint(value: String): IO[AgentError, URL] =
    val invalid = AgentError.InvalidConfiguration(
      "Gemini endpoint must be explicit absolute HTTP(S), without credentials/fragment; only alt=sse query is supported"
    )
    for
      parsed <- ZIO.attempt(new URI(value)).mapError(_ => invalid)
      _ <- ZIO
        .fail(invalid)
        .unless(
          Option(parsed.getScheme)
            .exists(value => value.equalsIgnoreCase("https") || value.equalsIgnoreCase("http")) &&
            Option(parsed.getHost).exists(_.nonEmpty) && Option(parsed.getUserInfo).isEmpty &&
            Option(parsed.getFragment).isEmpty && Option(parsed.getRawQuery)
              .forall(_ == "alt=sse") &&
            (parsed.getPort == -1 || parsed.getPort > 0 && parsed.getPort <= 65535)
        )
      url <- ZIO
        .fromEither(
          URL.decode(if Option(parsed.getRawQuery).isEmpty then value + "?alt=sse" else value)
        )
        .mapError(_ => invalid)
    yield url

object GeminiLlmClient:
  /** endpoint in LlmConfig is the full selected-model :streamGenerateContent URL. */
  def make(
      http: Client,
      execution: GeminiCodeExecution = GeminiCodeExecution.Disabled,
      limits: CompletionTransportLimits = CompletionTransportLimits()
  ): IO[AgentError, GeminiLlmClient] =
    ZIO
      .fail(AgentError.InvalidConfiguration("Gemini transport byte limits must be positive"))
      .unless(limits.responseBytes > 0 && limits.errorBodyBytes > 0)
      .as(new GeminiLlmClient(http, execution, limits))
