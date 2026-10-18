/* Copyright 2024-2026 WeisGnosis. Licensed under the Apache License, Version 2.0. */
package consumer.live

import com.weisgnosis.agents.core.agent.{AgentConfig, AgentRunState, AgentRunUpdate, ReActAgent}
import com.weisgnosis.agents.core.client.{
  LlmConfig,
  ResponsesLlmClient,
  SseEvent,
  SseParser,
  StructuredOutputCapability,
  TemperaturePolicy
}
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.event.AgentEvent
import com.weisgnosis.agents.core.model.Msg
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.stream.ZStream

object LiveResponsesHttpCancellation extends ZIOAppDefault:
  final private case class Receipt(
      profile: String,
      model: String,
      realReasoningDelta: Boolean,
      originalInterruption: Boolean,
      heldUntilCleanupRelease: Boolean,
      httpScopeReleased: Boolean,
      cleanupFinished: Boolean,
      noFalseCompletion: Boolean,
      toolCalls: Int,
      recoveryMessages: Int,
      incompleteReplayFenced: Boolean,
      requestsBeforeFreshContext: Int,
      requestsTotal: Int,
      borrowedClientReusable: Boolean
  ) derives JsonCodec

  private def required(name: String): IO[AgentError, String] =
    System
      .env(name)
      .mapError(_ => AgentError.InvalidConfiguration("Cannot read live configuration"))
      .flatMap(value =>
        ZIO
          .fromOption(value.filterNot(_.isBlank))
          .orElseFail(AgentError.InvalidConfiguration(s"Missing $name"))
      )

  private def reasoningDelta(raw: String): Boolean =
    raw
      .fromJson[Json.Obj]
      .toOption
      .exists(value =>
        value
          .get("type")
          .flatMap(_.asString)
          .exists(kind =>
            kind == "response.reasoning_text.delta" || kind == "response.reasoning_summary_text.delta"
          ) &&
          value.get("delta").flatMap(_.asString).exists(_.nonEmpty)
      )

  private def encode(frame: SseEvent): String =
    frame.event.fold("")(value => s"event: $value\n") +
      frame.id.fold("")(value => s"id: $value\n") +
      frame.data.split("\n", -1).map(value => s"data: $value\n").mkString + "\n"

  private def instrumented(
      upstream: Client,
      requests: Ref[Int],
      closed: Ref[Boolean],
      cleaning: Promise[Nothing, Unit],
      release: Promise[Nothing, Unit],
      boundaryClosed: Ref[Boolean]
  ): Client = upstream.copy(driver = new ZClient.Driver[Any, Scope, Throwable]:
    def request(
        version: Version,
        method: Method,
        url: URL,
        headers: Headers,
        body: Body,
        sslConfig: Option[ClientSSLConfig],
        proxy: Option[Proxy]
    )(using Trace): ZIO[Scope, Throwable, Response] =
      for
        count <- requests.updateAndGet(_ + 1)
        // Registered before acquisition: this marker follows the real HTTP driver's finalizers.
        _        <- if count == 1 then ZIO.addFinalizer(closed.set(true)).unit else ZIO.unit
        response <- upstream.driver.request(version, method, url, headers, body, sslConfig, proxy)
        wrapped <-
          if count != 1 || !response.status.isSuccess then ZIO.succeed(response)
          else
            ZIO
              .addFinalizer(cleaning.succeed(()) *> release.await *> boundaryClosed.set(true))
              .as(
                response.copy(body =
                  Body.fromCharSequenceStreamChunked(
                    response.body.asStream
                      .take(1024L * 1024)
                      .via(SseParser.parseBytes)
                      .flatMap(frame =>
                        // Forward actual server frames, then park without closing the upstream reader.
                        ZStream.succeed(encode(frame)) ++
                          (if reasoningDelta(frame.data) then ZStream.fromZIO(ZIO.never)
                           else ZStream.empty)
                      )
                  )
                )
              )
      yield wrapped

    def socket[Env1 <: Any](version: Version, url: URL, headers: Headers, app: WebSocketApp[Env1])(
        using
        Trace,
        Scope =:= Scope
    ): ZIO[Env1 & Scope, Throwable, Response] =
      upstream.driver.socket(version, url, headers, app))

  private def exercise(upstream: Client, config: LlmConfig): ZIO[Scope, Throwable, Receipt] =
    for
      requests       <- Ref.make(0)
      closed         <- Ref.make(false)
      boundaryClosed <- Ref.make(false)
      modelEnded     <- Ref.make(false)
      parsed         <- Promise.make[Nothing, Unit]
      cleaning       <- Promise.make[Nothing, Unit]
      release        <- Promise.make[Nothing, Unit]
      http = instrumented(upstream, requests, closed, cleaning, release, boundaryClosed)
      client <- ResponsesLlmClient.make(http)
      agent <- ReActAgent.make(
        client = client,
        llmConfig = config.copy(maxTokens = Some(1024)),
        config = AgentConfig(maxIterations = 1, timeout = 180.seconds)
      )
      controller <- ZIO.fiberId
      worker <- ZIO.uninterruptibleMask: restore =>
        for
          running <- restore(
            agent
              .streamWithResult(
                Msg.user(
                  "Write a detailed explanation of structured concurrency with forty numbered examples. Use no tools."
                )
              )
              .tap {
                case AgentRunUpdate.Event(event: AgentEvent.ModelResponseChunkEvent)
                    if reasoningDelta(event.chunk) =>
                  parsed.succeed(()).unit
                case AgentRunUpdate.Event(_: AgentEvent.ModelResponseEndEvent) =>
                  modelEnded.set(true)
                case _ => ZIO.unit
              }
              .runDrain
          ).forkScoped
          // Release a held finalizer before child joining if an assertion or watchdog exits.
          _ <- ZIO.addFinalizer(release.succeed(()).unit)
        yield running
      _ <- parsed.await
        .raceFirst(
          cleaning.await *> ZIO.fail(
            AgentError.InvalidMessage("HTTP response closed before an admitted reasoning delta")
          )
        )
        .raceFirst(worker.await.flatMap {
          case Exit.Failure(cause) => ZIO.refailCause(cause)
          case Exit.Success(_) => ZIO.fail(AgentError.InvalidMessage("No admitted reasoning delta"))
        })
      _             <- worker.interruptAsFork(controller)
      _             <- cleaning.await
      held          <- worker.poll.map(_.isEmpty)
      _             <- release.succeed(())
      cancelled     <- worker.await
      history       <- agent.history
      requestClosed <- closed.get
      gateClosed    <- boundaryClosed.get
      ended         <- modelEnded.get
      before        <- requests.get
      next          <- agent.run(Msg.user("Continue the interrupted answer.")).exit
      after         <- requests.get
      fenced = history.exists(
        _.metadata.get(Msg.MetadataResponsesIncompleteReplay).contains(Json.Bool(true))
      ) &&
        next.causeOption.exists(_.failures.exists {
          case AgentError.InvalidMessage(message) =>
            message.contains("Interrupted Responses reasoning")
          case _ => false
        }) && before == 1 && after == before
      fresh <- ReActAgent.make(
        client = client,
        llmConfig = config,
        config = AgentConfig(maxIterations = 2, timeout = 180.seconds)
      )
      recovered        <- fresh.run(Msg.user("Reply with only READY. Use no tools."))
      total            <- requests.get
      observedProgress <- parsed.isDone
      receipt = Receipt(
        "native-http-cancellation",
        config.model,
        observedProgress,
        cancelled.causeOption.exists(_.interruptors.contains(controller)),
        held,
        requestClosed,
        gateClosed,
        !ended,
        history.flatMap(_.toolUses).size,
        history.count(_.metadata.get(Msg.MetadataGenerateReason).contains(Json.Str("interrupted"))),
        fenced,
        after,
        total,
        recovered.state == AgentRunState.Completed && recovered.reply.text.trim == "READY"
      )
      _ <- Console.printLine(receipt.toJson)
      _ <- ZIO
        .fail(AgentError.InvalidMessage("Live HTTP cancellation assertions failed"))
        .unless(
          receipt.realReasoningDelta && receipt.originalInterruption && receipt.heldUntilCleanupRelease &&
            receipt.httpScopeReleased && receipt.cleanupFinished && receipt.noFalseCompletion &&
            receipt.toolCalls == 0 && receipt.recoveryMessages == 1 && receipt.incompleteReplayFenced &&
            receipt.requestsBeforeFreshContext == 1 && receipt.requestsTotal == 2 && receipt.borrowedClientReusable
        )
    yield receipt

  def run: ZIO[Any, Throwable, Unit] =
    (for
      endpoint <- required("WEISGNOSIS_LIVE_ENDPOINT")
      model    <- required("WEISGNOSIS_LIVE_MODEL")
      key      <- required("WEISGNOSIS_LIVE_API_KEY")
      http     <- ZIO.service[Client]
      _ <- ZIO
        .scoped(
          exercise(
            http,
            LlmConfig(
              model,
              endpoint = endpoint,
              apiKey = Some(key),
              maxTokens = Some(4096),
              temperaturePolicy = TemperaturePolicy.Omit,
              structuredOutputCapability = StructuredOutputCapability.Unsupported
            )
          )
        )
        .timeoutFail(AgentError.TimeoutError(420L, "Live HTTP cancellation watchdog expired"))(
          420.seconds
        )
    yield ()).provide(Client.default)
