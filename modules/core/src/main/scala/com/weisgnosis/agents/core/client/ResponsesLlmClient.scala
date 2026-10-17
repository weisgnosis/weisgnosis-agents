/* Copyright 2024-2026 WeisGnosis. Licensed under the Apache License, Version 2.0. */
package com.weisgnosis.agents.core.client

import com.weisgnosis.agents.core.error.{AgentError, TransportFailure}
import com.weisgnosis.agents.core.event.{EventPublisher, ModelStreamContext}
import com.weisgnosis.agents.core.json.JsonBoundary
import com.weisgnosis.agents.core.model.Msg
import com.weisgnosis.agents.core.tool.ToolDef
import java.net.URI
import java.nio.charset.StandardCharsets
import zio.*
import zio.http.{Body, Client, Headers, Request, Response, URL}
import zio.stream.ZStream

/**
 * Stateless native Responses text/reasoning/local-function client. The HTTP client is borrowed, never closed here.
 * No automatic retry or definite pre-response rejection authority is inferred from network errors.
 */
final class ResponsesLlmClient private (
    http: Client,
    limits: CompletionTransportLimits
) extends LlmClient:
  def streamTurn(
      history: Chunk[Msg],
      tools: Chunk[ToolDef],
      config: LlmConfig,
      eventPublisher: EventPublisher,
      context: ModelStreamContext
  ): ZIO[Scope, AgentError, TurnOutput] =
    attemptTurnWithProgress(history, tools, config, eventPublisher, context, _ => ZIO.unit)

  override private[core] def attemptTurnWithProgress(
      history: Chunk[Msg],
      tools: Chunk[ToolDef],
      config: LlmConfig,
      eventPublisher: EventPublisher,
      context: ModelStreamContext,
      recordPartial: Option[TurnOutput.Interrupted] => UIO[Unit]
  ): ZIO[Scope, AgentError, TurnOutput] =
    ZIO.scoped:
      for
        body <- ZIO.fromEither(ResponsesRequest.encode(history, tools, config))
        url  <- endpoint(config.endpoint)
        _ <- ZIO
          .fail(AgentError.InvalidConfiguration("Responses API key must be visible ASCII"))
          .unless(
            config.apiKey.forall(value => value.nonEmpty && value.forall(c => c > ' ' && c < 127))
          )
        headers = Headers(
          "Content-Type" -> "application/json",
          "Accept"       -> "text/event-stream"
        ) ++
          config.apiKey.fold(Headers.empty)(value => Headers("Authorization" -> s"Bearer $value"))
        publisher <- ModelStreamPublisher.make(eventPublisher, context)
        response <- Client
          .streaming(
            Request.post(url, Body.fromString(JsonBoundary.render(body))).addHeaders(headers)
          )
          .provideSomeEnvironment[Scope](_.add(http))
          .mapError(error =>
            AgentError
              .ModelError(0, TransportFailure.describe("Responses HTTP acquisition failed", error))
          )
        output <-
          if response.status.isSuccess then
            consume(response, config.model, publisher, recordPartial)
          else reject(response)
        _ <- publisher.finish(output)
      yield output

  private def consume(
      response: Response,
      model: String,
      publisher: ModelStreamPublisher,
      recordPartial: Option[TurnOutput.Interrupted] => UIO[Unit]
  ): ZIO[Scope, AgentError, TurnOutput] =
    Ref
      .make(ResponsesMessageStream.empty(model))
      .flatMap: latest =>
        (
          for
            _ <- ZIO
              .fail(
                AgentError
                  .ModelError(response.status.code, "Expected Responses text/event-stream response")
              )
              .unless(
                response.rawHeaders("Content-Type").size == 1 &&
                  response
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
                    TransportFailure.describe("Failed to read Responses UTF-8 stream", error)
                  )
              .mapAccumZIO(ResponsesMessageStream.empty(model)): (previous, frame) =>
                for
                  accepted <- ZIO.fromEither(previous.accept(frame))
                  (next, deltas) = accepted
                  _ <- (latest.set(next) *> recordPartial(Some(next.partial))).uninterruptible
                  _ <- publisher.frame(frame.data, deltas)
                yield (next, next)
              .takeUntil(_.done)
              .runLast
            output <- ZIO.fromEither(state.getOrElse(ResponsesMessageStream.empty(model)).output)
            _      <- recordPartial(Some(TurnOutput.Interrupted(output.message)))
          yield output
        ).onInterrupt(latest.get.flatMap(value => recordPartial(Some(value.partial))))

  private def bounded(
      bytes: ZStream[Any, Throwable, Byte],
      status: Int
  ): ZStream[Any, Throwable, Byte] =
    bytes.chunks
      .mapAccumZIO(0L): (consumed, chunk) =>
        if chunk.size.toLong > limits.responseBytes - consumed then
          ZIO.fail(
            AgentError.ModelError(status, "Responses response exceeds configured byte limit")
          )
        else ZIO.succeed((consumed + chunk.size, chunk))
      .flattenChunks

  private def reject(response: Response): IO[AgentError, Nothing] =
    response.body.asStream
      .take(limits.errorBodyBytes.toLong)
      .runCollect
      .mapError(error =>
        AgentError.ModelError(
          response.status.code,
          TransportFailure.describe("Failed to read Responses HTTP rejection", error)
        )
      )
      .flatMap(bytes =>
        ZIO.fail(
          AgentError.ModelError(
            response.status.code,
            "Responses HTTP request rejected",
            Some(new String(bytes.toArray, StandardCharsets.UTF_8))
          )
        )
      )

  private def endpoint(value: String): IO[AgentError, URL] =
    val invalid = AgentError.InvalidConfiguration(
      "Responses endpoint must be explicit absolute HTTP(S) ending in /responses, without credentials or fragment"
    )
    for
      parsed <- ZIO.attempt(new URI(value)).mapError(_ => invalid)
      _ <- ZIO
        .fail(invalid)
        .unless(
          Option(parsed.getScheme)
            .exists(value => value.equalsIgnoreCase("https") || value.equalsIgnoreCase("http")) &&
            Option(parsed.getHost).exists(_.nonEmpty) && Option(parsed.getUserInfo).isEmpty &&
            Option(
              parsed.getFragment
            ).isEmpty && Option(parsed.getPath).exists(
              _.endsWith("/responses")
            ) && (parsed.getPort == -1 || parsed.getPort > 0 && parsed.getPort <= 65535)
        )
      url <- ZIO.fromEither(URL.decode(value)).mapError(_ => invalid)
    yield url

object ResponsesLlmClient:
  def make(
      http: Client,
      limits: CompletionTransportLimits = CompletionTransportLimits()
  ): IO[AgentError, ResponsesLlmClient] =
    ZIO
      .fail(AgentError.InvalidConfiguration("Responses response limits must be positive"))
      .unless(limits.responseBytes > 0 && limits.errorBodyBytes > 0)
      .as(new ResponsesLlmClient(http, limits))
