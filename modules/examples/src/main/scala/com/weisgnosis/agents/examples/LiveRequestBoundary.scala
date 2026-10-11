/* Copyright 2024-2026 WeisGnosis. Licensed under the Apache License, Version 2.0. */
package com.weisgnosis.agents.examples

import com.weisgnosis.agents.core.agent.{AgentConfig, AgentRunState, AgentRunUpdate, ReActAgent}
import com.weisgnosis.agents.core.client.{
  ChatCompletionsClient,
  CompletionTransportLimits,
  LlmConfig,
  SseEvent,
  SseParser
}
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.event.AgentEvent
import com.weisgnosis.agents.core.model.{Msg, ToolResultState}
import com.weisgnosis.agents.core.tool.{Tool, ToolDef, Toolkit}
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.stream.ZStream

/** Instrumented SSE forwarding proves local request ownership, never remote inference cancellation. */
object LiveRequestBoundary extends ZIOAppDefault:
  private[examples] enum Phase derives JsonCodec:
    case AcquiringResponse, AwaitingFirstParsedFrame, AwaitingCleanup, JoiningConsumer

  final private[examples] case class FailureDiagnostic(
      phase: Phase = Phase.AcquiringResponse,
      httpStatus: Option[Int] = None,
      requests: Int = 0,
      firstFrameSeen: Boolean = false,
      parsedChunkSeen: Boolean = false,
      cleanupEntered: Boolean = false,
      requestScopeReleased: Boolean = false
  ) derives JsonCodec

  final private[examples] case class Receipt(
      model: String,
      scenarioAccepted: Boolean,
      requests: Int,
      parsedRuntimeChunk: Boolean,
      consumerPendingDuringCleanup: Boolean,
      requestScopeReleased: Boolean,
      boundaryCleanupReleased: Boolean,
      localExecutions: Int,
      interruptionPreserved: Boolean,
      recoveryExactlyOnce: Boolean,
      partialCalls: Int,
      incompleteCalls: Int,
      incompleteArgumentsSanitized: Boolean,
      unfinishedCallsReconciled: Boolean,
      noCompletedModelEvent: Boolean
  ) derives JsonCodec

  private[examples] enum Control:
    case Malformed, Truncated, TokenExhausted

  final private[examples] case class ControlReceipt(
      control: String,
      evidence: String,
      scenarioAccepted: Boolean,
      requests: Int,
      requestScopeReleased: Boolean,
      localExecutions: Int,
      state: String
  ) derives JsonCodec

  private val responseLimit: Long = 1024L * 1024
  private val partial: String =
    "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"partial\",\"tool_calls\":[{\"index\":0,\"id\":\"pending\",\"function\":{\"name\":\"boundary_probe\",\"arguments\":\"{\"}}]}}]}\n\n"
  private val done: String = "data: [DONE]\n\n"

  def run: ZIO[Any, Throwable, Unit] = LiveProviderSmoke.configuration.flatMap:
    case None => Console.printLine("Request boundary disabled; set WEISGNOSIS_LIVE_SMOKE=enabled.")
    case Some(config) =>
      (for
        http    <- ZIO.service[Client]
        timeout <- LiveProviderSmoke.runTimeout
        catalog <- LiveMultiModel.discover(http, config)
        eligibility = LiveMultiModel.eligibility(config.model, catalog)
        _ <- eligibility match
          case Some(status) =>
            Console.printLine(
              LiveMultiModel
                .Scenario(
                  "active-http-request-cancellation",
                  Chunk(config.model),
                  status,
                  "Selected identity is absent, non-chat, or unavailable in the catalog"
                )
                .toJson
            )
          case None =>
            LiveMultiModel.record("active-http-request-cancellation", Chunk(config.model))(
              exercise(http, config, timeout).flatMap(receipt =>
                Console.printLine(receipt.toJson).orDie
              )
            )
        _ <- ZIO.foreachDiscard(
          Chunk(Control.Malformed, Control.Truncated, Control.TokenExhausted)
        )(control => exerciseControl(control).flatMap(receipt => Console.printLine(receipt.toJson)))
        _ <- eligibility match
          case Some(status) =>
            Console.printLine(
              LiveMultiModel
                .Scenario(
                  "definite-http-rejection-failover",
                  Chunk(config.model),
                  status,
                  "Selected identity is absent, non-chat, or unavailable in the catalog"
                )
                .toJson
            )
          case None =>
            LiveMultiModel.record("definite-http-rejection-failover", Chunk(config.model))(
              LiveMultiModel
                .definiteFailover(http, config)
                .provideLayer(
                  Server
                    .defaultWithPort(0)
                    .mapError(_ =>
                      AgentError.InvalidConfiguration("Cannot acquire rejection fixture")
                    )
                )
            )
      yield ()).provide(Client.default)

  private[examples] def exercise(
      upstream: Client,
      config: LlmConfig,
      timeout: Duration
  ): IO[AgentError, Receipt] = Ref
    .make(FailureDiagnostic())
    .flatMap(diagnostic =>
      ZIO
        .scoped(
          for
            requests       <- Ref.make(0)
            requestClosed  <- Ref.make(false)
            boundaryClosed <- Ref.make(false)
            parsed         <- Promise.make[Nothing, Unit]
            cleaning       <- Promise.make[Nothing, Unit]
            release        <- Promise.make[Nothing, Unit]
            executions     <- Ref.make(0)
            firstFrame     <- Ref.make(Option.empty[String])
            http = driver(request =>
              for
                count <- requests.getAndUpdate(_ + 1)
                _     <- diagnostic.update(_.copy(requests = count + 1))
                _ <- ZIO
                  .fail(
                    AgentError.InvalidMessage("Request boundary permits only one model request")
                  )
                  .unless(count == 0)
                // Registered first: this marker runs after the borrowed HTTP request's own finalizers.
                _ <- ZIO.addFinalizer(
                  requestClosed.set(true) *> diagnostic.update(_.copy(requestScopeReleased = true))
                )
                response <- Client.streaming(request).provideSomeEnvironment[Scope](_.add(upstream))
                _ <- diagnostic.update(
                  _.copy(
                    phase = Phase.AwaitingFirstParsedFrame,
                    httpStatus = Some(response.status.code)
                  )
                )
                forwarded <-
                  if !response.status.isSuccess then ZIO.succeed(response)
                  else
                    ZIO
                      .addFinalizer(
                        diagnostic.update(_.copy(cleanupEntered = true)) *> cleaning
                          .succeed(()) *> release.await *> boundaryClosed.set(true)
                      )
                      .as(
                        response.copy(body =
                          Body.fromCharSequenceStreamChunked(
                            response.body.asStream
                              .take(responseLimit)
                              .via(SseParser.parseBytes)
                              .filter(_.data.trim.nonEmpty)
                              .take(1)
                              .tap(frame =>
                                firstFrame.set(Some(frame.data)) *> diagnostic
                                  .update(_.copy(firstFrameSeen = true))
                              )
                              .map(encodeFrame) ++ ZStream.fromZIO(ZIO.never)
                          )
                        )
                      )
              yield forwarded
            )
            client <- ChatCompletionsClient
              .make(http, CompletionTransportLimits(responseBytes = responseLimit))
            agent <- ReActAgent.make(
              AgentConfig(maxIterations = 1, timeout = timeout),
              client = client,
              llmConfig = config,
              toolkit = probe(executions)
            )
            worker <- ZIO.uninterruptibleMask: restore =>
              for
                running <- restore(
                  agent
                    .streamWithResult(
                      Msg.user(
                        "Call boundary_probe exactly once with no arguments. Do not answer before calling the tool."
                      )
                    )
                    .tap:
                      case AgentRunUpdate.Event(_: AgentEvent.ModelResponseChunkEvent) =>
                        diagnostic.update(_.copy(parsedChunkSeen = true)) *> parsed.succeed(()).unit
                      case _ => ZIO.unit
                    .runCollect
                ).forkScoped
                // Emergency release precedes child closure if a watchdog or assertion interrupts the fixture.
                _ <- ZIO.addFinalizer(release.succeed(()).unit)
              yield running
            _ <- parsed.await
              .raceFirst(
                cleaning.await *> parsed.poll.flatMap:
                  case Some(_) => ZIO.unit
                  case None =>
                    release.succeed(()) *> worker.join *> ZIO
                      .fail(
                        AgentError.InvalidMessage("No parsed runtime chunk before response ended")
                      )
              )
              .raceFirst(worker.await.flatMap:
                case Exit.Failure(cause) => ZIO.refailCause(cause)
                case Exit.Success(_) =>
                  ZIO.fail(
                    AgentError.InvalidMessage("No parsed runtime chunk before response ended")
                  ))
            _ <- diagnostic.update(_.copy(phase = Phase.AwaitingCleanup))
            _ <- agent.interrupt
            _ <- cleaning.await.raceFirst(
              worker.join *> ZIO.fail(
                AgentError.InvalidMessage("Consumer finished without held request cleanup")
              )
            )
            _        <- agent.interrupt
            pending  <- worker.poll.map(_.isEmpty)
            _        <- diagnostic.update(_.copy(phase = Phase.JoiningConsumer))
            _        <- release.succeed(())
            updates  <- worker.join
            history  <- agent.history
            count    <- requests.get
            closed   <- requestClosed.get
            boundary <- boundaryClosed.get
            executed <- executions.get
            results     = updates.collect { case AgentRunUpdate.Result(result) => result }
            events      = updates.collect { case AgentRunUpdate.Event(event) => event }
            interrupted = results.size == 1 && results.forall(_.state == AgentRunState.Interrupted)
            recovery = history.count(
              _.metadata.get(Msg.MetadataGenerateReason).contains(Json.Str("interrupted"))
            ) == 1
            calls        = history.flatMap(_.toolUses)
            observations = history.flatMap(_.toolResults)
            raw <- firstFrame.get
            incomplete = raw.fold(Set.empty[String])(incompleteCallIds)
            sanitized = incomplete.forall(id =>
              calls.exists(call =>
                call.id == id && call.input == Json.Obj() && call.content.contains("{}")
              )
            )
            reconciled = calls.map(_.id) == observations.map(_.id) && observations.forall(
              _.state == ToolResultState.Error
            )
            noCompletion = !events.exists(_.isInstanceOf[AgentEvent.ModelResponseEndEvent])
            accepted =
              count == 1 && pending && closed && boundary && executed == 0 && interrupted && recovery && sanitized && reconciled && noCompletion
            _ <- require(accepted, "Active request boundary assertions failed")
          yield Receipt(
            config.model,
            accepted,
            count,
            true,
            pending,
            closed,
            boundary,
            executed,
            interrupted,
            recovery,
            calls.size,
            incomplete.size,
            sanitized,
            reconciled,
            noCompletion
          )
        )
        .timeoutFail(
          AgentError
            .TimeoutError(timeout.toSeconds, "Active request boundary aggregate budget exhausted")
        )(timeout)
        .tapError(error =>
          diagnostic.get.flatMap(snapshot =>
            Console.printLine(failureDiagnostic(snapshot, error).toJson).catchAll(_ => ZIO.unit)
          )
        )
    )

  private[examples] def failureDiagnostic(
      snapshot: FailureDiagnostic,
      error: AgentError
  ): FailureDiagnostic =
    error match
      case AgentError.ModelError(status, _, _) => snapshot.copy(httpStatus = Some(status))
      case _                                   => snapshot

  private[examples] def exerciseControl(control: Control): IO[AgentError, ControlReceipt] =
    ZIO.scoped:
      for
        requests <- Ref.make(0)
        closed   <- Ref.make(false)
        executed <- Ref.make(0)
        payload = control match
          case Control.Malformed => partial + "data: not-json\n\n" + done
          case Control.Truncated => partial
          case Control.TokenExhausted =>
            partial + "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"length\"}]}\n\n" + done
        http = driver(_ =>
          requests.update(_ + 1) *> ZIO
            .addFinalizer(closed.set(true))
            .as(
              Response(
                headers = Headers("Content-Type", "text/event-stream"),
                body = Body.fromString(payload)
              )
            )
        )
        client <- ChatCompletionsClient.make(http)
        agent <- ReActAgent.make(
          client = client,
          llmConfig = LlmConfig("offline-boundary", endpoint = "http://localhost/fixture"),
          toolkit = probe(executed)
        )
        outcome    <- agent.run(Msg.user("probe")).either
        count      <- requests.get
        released   <- closed.get
        executions <- executed.get
        expected = control match
          case Control.TokenExhausted =>
            outcome.exists(_.state == AgentRunState.Stopped("Model output token budget exhausted"))
          case Control.Malformed =>
            outcome.left.toOption.exists:
              case _: AgentError.DeserializationError => true
              case _                                  => false
          case Control.Truncated =>
            outcome == Left(AgentError.ModelError(200, "Event stream ended before [DONE]"))
        accepted = expected && count == 1 && released && executions == 0
        _ <- require(accepted, "Offline request boundary control failed")
      yield ControlReceipt(
        control.toString,
        "offline-fixture",
        accepted,
        count,
        released,
        executions,
        if control == Control.TokenExhausted then "Stopped" else "Rejected"
      )

  private def probe(executions: Ref[Int]): Toolkit = Toolkit.single(
    Tool.make(
      ToolDef(
        "boundary_probe",
        "Observe whether a tool executes after a complete model response.",
        Json.Obj(
          "type"                 -> Json.Str("object"),
          "properties"           -> Json.Obj(),
          "additionalProperties" -> Json.Bool(false)
        )
      )
    )(_ => executions.update(_ + 1).as("unexpected execution"))
  )

  private def encodeFrame(frame: SseEvent): String =
    (Chunk.fromIterable(frame.event.map(value => s"event: $value")) ++
      Chunk.fromIterable(frame.id.map(value => s"id: $value")) ++
      Chunk.fromArray(frame.data.split("\n", -1)).map(value => s"data: $value"))
      .mkString("\n") + "\n\n"

  private def incompleteCallIds(data: String): Set[String] =
    val calls = data
      .fromJson[Json]
      .toOption
      .flatMap(_.asObject)
      .flatMap(_.get("choices"))
      .flatMap(_.asArray)
      .getOrElse(Chunk.empty)
      .flatMap(choice =>
        choice.asObject
          .flatMap(_.get("delta"))
          .flatMap(_.asObject)
          .flatMap(_.get("tool_calls"))
          .flatMap(_.asArray)
          .getOrElse(Chunk.empty)
      )
    calls
      .flatMap(call =>
        (for
          fields    <- call.asObject
          id        <- fields.get("id").flatMap(_.asString)
          function  <- fields.get("function").flatMap(_.asObject)
          arguments <- function.get("arguments").flatMap(_.asString)
          if !arguments
            .fromJson[Json]
            .toOption
            .exists:
              case _: Json.Obj => true
              case _           => false
        yield id).toList
      )
      .toSet

  private[examples] def driver(f: Request => ZIO[Scope, Throwable, Response]): Client =
    ZClient.fromDriver(new ZClient.Driver[Any, Scope, Throwable]:
      def request(
          version: Version,
          method: Method,
          url: URL,
          headers: Headers,
          body: Body,
          sslConfig: Option[ClientSSLConfig],
          proxy: Option[Proxy]
      )(using Trace): ZIO[Scope, Throwable, Response] =
        ZIO.suspendSucceed(
          f(Request(version = version, method = method, url = url, headers = headers, body = body))
        )

      def socket[Env1 <: Any](
          version: Version,
          url: URL,
          headers: Headers,
          app: WebSocketApp[Env1]
      )(using Trace, Scope =:= Scope): ZIO[Env1 & Scope, Throwable, Response] =
        ZIO.fail(AgentError.InvalidConfiguration("WebSockets are outside this request boundary")))

  private def require(accepted: Boolean, message: String): IO[AgentError, Unit] =
    ZIO.fail(AgentError.InvalidMessage(message)).unless(accepted).unit
