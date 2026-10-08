/* Copyright 2024-2026 WeisGnosis. Licensed under the Apache License, Version 2.0. */
package com.weisgnosis.agents.core.client

import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.event.{AgentEvent, ModelStreamContext}
import com.weisgnosis.agents.core.json.JsonBoundary
import com.weisgnosis.agents.core.model.Msg
import com.weisgnosis.agents.core.tool.ToolDef
import java.net.URI
import java.nio.charset.StandardCharsets
import zio.*
import zio.http.{Body, Client, Headers, Request, Response, URL}
import zio.stream.ZStream

/**
 * Native text/thinking/local-tool Messages client. The HTTP client is borrowed, never closed here.
 * No automatic retry or definite pre-response rejection authority is inferred from network errors.
 */
final class AnthropicLlmClient private (
    http: Client,
    limits: CompletionTransportLimits,
    apiVersion: String,
    webSearch: Option[AnthropicWebSearch]
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
        body <- ZIO.fromEither(AnthropicMessagesRequest.encode(history, tools, config, webSearch))
        url  <- endpoint(config.endpoint)
        _ <- ZIO
          .fail(AgentError.InvalidConfiguration("Anthropic API key must be visible ASCII"))
          .unless(
            config.apiKey.forall(value => value.nonEmpty && value.forall(c => c > ' ' && c < 127))
          )
        headers = Headers(
          "Content-Type"      -> "application/json",
          "Accept"            -> "text/event-stream",
          "anthropic-version" -> apiVersion
        ) ++
          config.apiKey.fold(Headers.empty)(value => Headers("x-api-key" -> value))
        publisher <- ModelStreamPublisher.make(eventHub, context)
        response <- Client
          .streaming(
            Request.post(url, Body.fromString(JsonBoundary.render(body))).addHeaders(headers)
          )
          .provideSomeEnvironment[Scope](_.add(http))
          .mapError(_ => AgentError.ModelError(0, "Anthropic HTTP acquisition failed"))
        output <-
          if response.status.isSuccess then consume(response, publisher, recordPartial)
          else reject(response)
        _ <- publisher.finish(output)
        _ <- recordPartial(None)
      yield output

  private def consume(
      response: Response,
      publisher: ModelStreamPublisher,
      recordPartial: Option[TurnOutput.Interrupted] => UIO[Unit]
  ): ZIO[Scope, AgentError, TurnOutput] =
    for
      _ <- ZIO
        .fail(
          AgentError
            .ModelError(response.status.code, "Expected Anthropic text/event-stream response")
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
          case _ =>
            AgentError.ModelError(response.status.code, "Failed to read Anthropic UTF-8 stream")
        .takeUntil(_.event.contains("message_stop"))
        .runFoldZIO(AnthropicMessageStream.empty(webSearch)): (previous, frame) =>
          for
            accepted <- ZIO.fromEither(previous.accept(frame))
            (next, deltas) = accepted
            _ <- recordPartial(Some(next.partial))
            _ <- publisher.frame(frame.data, deltas)
          yield next
      output <- ZIO.fromEither(state.output)
    yield output

  private def bounded(
      bytes: ZStream[Any, Throwable, Byte],
      status: Int
  ): ZStream[Any, Throwable, Byte] =
    bytes.chunks
      .mapAccumZIO(0L): (consumed, chunk) =>
        if chunk.size.toLong > limits.responseBytes - consumed then
          ZIO.fail(
            AgentError.ModelError(status, "Anthropic response exceeds configured byte limit")
          )
        else ZIO.succeed((consumed + chunk.size, chunk))
      .flattenChunks

  private def reject(response: Response): IO[AgentError, Nothing] =
    response.body.asStream
      .take(limits.errorBodyBytes.toLong)
      .runCollect
      .mapError(_ =>
        AgentError.ModelError(response.status.code, "Failed to read Anthropic HTTP rejection")
      )
      .flatMap(bytes =>
        ZIO.fail(
          AgentError.ModelError(
            response.status.code,
            "Anthropic HTTP request rejected",
            Some(new String(bytes.toArray, StandardCharsets.UTF_8))
          )
        )
      )

  private def endpoint(value: String): IO[AgentError, URL] =
    val invalid = AgentError.InvalidConfiguration(
      "Anthropic endpoint must be explicit absolute HTTP(S), without credentials or fragment"
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
            ).isEmpty && (parsed.getPort == -1 || parsed.getPort > 0 && parsed.getPort <= 65535)
        )
      url <- ZIO.fromEither(URL.decode(value)).mapError(_ => invalid)
    yield url

object AnthropicLlmClient:
  def make(
      http: Client,
      limits: CompletionTransportLimits = CompletionTransportLimits(),
      apiVersion: String = "2023-06-01",
      webSearch: Option[AnthropicWebSearch] = None
  ): IO[AgentError, AnthropicLlmClient] =
    ZIO
      .fail(
        AgentError.InvalidConfiguration(
          "Anthropic response limits and visible ASCII API version must be positive/nonempty"
        )
      )
      .unless(
        limits.responseBytes > 0 && limits.errorBodyBytes > 0 &&
          apiVersion.nonEmpty && apiVersion.forall(c => c > ' ' && c < 127)
      )
      .as(new AnthropicLlmClient(http, limits, apiVersion, webSearch))
