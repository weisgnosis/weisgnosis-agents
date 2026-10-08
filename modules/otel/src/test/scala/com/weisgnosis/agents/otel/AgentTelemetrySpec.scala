/*
 * Copyright 2024-2026 WeisGnosis (https://weisgnosis.com)
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package com.weisgnosis.agents.otel

import com.weisgnosis.agents.core.agent.*
import com.weisgnosis.agents.core.client.{LlmClient, TurnOutput}
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.model.{ContentBlock, Msg}
import com.weisgnosis.agents.core.tool.*
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.{Span, StatusCode, Tracer}
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.`export`.{SimpleSpanProcessor, SpanExporter}
import io.opentelemetry.sdk.trace.`export`.BatchSpanProcessor
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import scala.jdk.CollectionConverters.*
import scala.annotation.unused
import zio.*
import zio.http.{Body, Headers, Method, Request, Response, Routes, Server, handler}
import zio.json.ast.Json
import zio.telemetry.opentelemetry.OpenTelemetry
import zio.telemetry.opentelemetry.context.ContextStorage
import zio.test.*

object AgentTelemetrySpec extends ZIOSpecDefault:
  final private case class Recording(tracer: Tracer, exporter: InMemorySpanExporter):
    def spans: Task[Chunk[SpanData]] =
      ZIO.attempt(Chunk.fromIterable(exporter.getFinishedSpanItems.asScala.toVector))

  private def recording: ZIO[Scope, Throwable, Recording] =
    for
      exporter <- ZIO.acquireRelease(ZIO.attempt(InMemorySpanExporter.create()))(value =>
        ZIO.attempt(value.close()).orDie
      )
      provider <- ZIO.acquireRelease(
        ZIO.attempt(
          SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build()
        )
      )(value => ZIO.attempt(value.close()).orDie)
      tracer <- ZIO.attempt(provider.get("weisgnosis-test"))
    yield Recording(tracer, exporter)

  private val unavailableExporter: SpanExporter = new SpanExporter:
    def `export`(@unused spans: java.util.Collection[SpanData]): CompletableResultCode =
      CompletableResultCode.ofFailure()
    def flush(): CompletableResultCode    = CompletableResultCode.ofFailure()
    def shutdown(): CompletableResultCode = CompletableResultCode.ofSuccess()

  private def attribute(span: SpanData, key: String): Option[String] =
    Option(span.getAttributes.get(AttributeKey.stringKey(key)))

  private def complete(operation: => CompletableResultCode): Task[Boolean] =
    ZIO.attemptBlocking(operation.join(5, TimeUnit.SECONDS).isSuccess)

  private def shutdown(operation: => CompletableResultCode): UIO[Unit] =
    complete(operation)
      .flatMap(success =>
        ZIO
          .fail(AgentError.InvalidConfiguration("Test tracing SDK shutdown failed"))
          .unless(success)
      )
      .unit
      .orDie

  private def install(
      client: LlmClient,
      telemetry: AgentTelemetry,
      toolkit: Toolkit = Toolkit.empty,
      authorizer: ToolAuthorizer = ToolAuthorizer.allow
  ): IO[AgentError, ReActAgent] =
    ReActAgent
      .builder()
      .model(client)
      .toolkit(toolkit.withDispatchObserver(telemetry.dispatchObserver))
      .toolAuthorizer(authorizer)
      .phaseObserver(telemetry.phaseObserver)
      .completionObserver(telemetry.completionObserver)
      .build

  def spec: Spec[TestEnvironment & Scope, Any] = suite("AgentTelemetrySpec")(
    test(
      "actual concurrent tool rounds retain fiber parents, normalized errors and private data boundaries"
    ):
      (for
        sdk      <- recording
        contexts <- ZIO.service[ContextStorage]
        adapter  <- AgentTelemetry.make(sdk.tracer, contexts)
        baseline <- contexts.get
        parent <- ZIO.acquireRelease(ZIO.attempt(sdk.tracer.spanBuilder("host").startSpan()))(
          span => ZIO.attempt(span.end()).orDie
        )
        observed <- Ref.make(Chunk.empty[String])
        tool = Tool.make(ToolDef("private_tool_name", "private_description", Json.Obj()))(
          (_: Json) =>
            contexts.get
              .flatMap(context =>
                ZIO.attempt(Span.fromContext(context).getSpanContext.getSpanId).orDie
              )
              .flatMap(id => observed.update(_ :+ id)) *>
              ZIO.fail(AgentError.ToolExecutionError("private_tool_name", "private_failure"))
        )
        results <- contexts.locally(baseline.`with`(parent))(
          ZIO.foreachPar(Chunk(1, 2))(_ =>
            for
              client <- LlmClient.mock(
                Chunk(
                  TurnOutput.ToolCalls(
                    Msg.assistant(
                      Chunk(
                        ContentBlock.ToolUse(
                          "private_call",
                          "private_tool_name",
                          Json.Obj("secret" -> Json.Str("private_argument"))
                        )
                      )
                    )
                  ),
                  TurnOutput.FinalText(Msg.assistant("private_answer"))
                )
              )
              agent  <- install(client, adapter, Toolkit.empty.updated(tool))
              result <- agent.run(Msg.user("private_prompt"))
            yield result
          )
        )
        spans    <- sdk.spans
        active   <- observed.get
        restored <- contexts.get
        stats    <- adapter.diagnostics
        invocations = spans.filter(_.getName == "agent.invocation")
        tools       = spans.filter(_.getName == "agent.tool")
        ids         = spans.map(_.getSpanId).toSet
        strings = spans.map(span =>
          span.getName + span.getAttributes.toString + span.getStatus.toString
        )
      yield assertTrue(
        results.forall(_.state == AgentRunState.Completed),
        invocations.size == 2,
        invocations.forall(_.getParentSpanId == parent.getSpanContext.getSpanId),
        invocations.forall(attribute(_, "weisgnosis.run.state").contains("completed")),
        spans.forall(span =>
          span.getName == "agent.invocation" || ids.contains(span.getParentSpanId)
        ),
        tools.size == 2,
        active.toSet == tools.map(_.getSpanId).toSet,
        tools.forall(_.getStatus.getStatusCode == StatusCode.ERROR),
        tools.forall(attribute(_, "weisgnosis.tool.state").contains("error")),
        strings.forall(!_.contains("private_")),
        !spans.exists(_.getName == "host"),
        restored == baseline,
        stats.operationFailures == 0
      )).provideSomeLayer[Scope](OpenTelemetry.contextZIO)
    ,
    test(
      "successful suspended runs remain suspended and model failures retain their original cause"
    ):
      (for
        sdk      <- recording
        contexts <- ZIO.service[ContextStorage]
        adapter  <- AgentTelemetry.make(sdk.tracer, contexts)
        tool = Tool.make(ToolDef("work", "work", Json.Obj()))((_: Json) => ZIO.succeed("unused"))
        client <- LlmClient.mock(
          Chunk(
            TurnOutput.ToolCalls(
              Msg.assistant(
                Chunk(
                  ContentBlock.ToolUse("call", "work", Json.Obj())
                )
              )
            )
          )
        )
        agent <- install(
          client,
          adapter,
          Toolkit.empty.updated(tool),
          ToolAuthorizer(_ => ZIO.succeed(ToolAuthorization.Deferred("host decision")))
        )
        suspended <- agent.run(Msg.user("request"))
        error = AgentError.ModelError(503, "private_provider_failure")
        failed <- install(LlmClient.mockEffectful((_, _, _) => ZIO.fail(error)), adapter)
        exit   <- failed.run(Msg.user("request")).exit
        spans  <- sdk.spans
        states = spans
          .filter(_.getName == "agent.invocation")
          .flatMap(attribute(_, "weisgnosis.run.state"))
      yield assertTrue(
        suspended.isSuspended,
        states.toSet == Set("suspended", "failed"),
        exit.causeOption.exists(_.failures == List(error)),
        spans
          .filter(_.getName == "agent.model")
          .exists(_.getStatus.getStatusCode == StatusCode.ERROR),
        spans.forall(!_.getStatus.getDescription.contains("private_provider_failure"))
      )).provideSomeLayer[Scope](OpenTelemetry.contextZIO)
    ,
    test(
      "mixed model failure and internal interruption remains an error rather than caller cancellation"
    ):
      (for
        sdk      <- recording
        contexts <- ZIO.service[ContextStorage]
        adapter  <- AgentTelemetry.make(sdk.tracer, contexts)
        error    = AgentError.ModelError(503, "private_mixed_failure")
        original = Cause.fail(error) ++ Cause.interrupt(FiberId.None)
        agent <- install(LlmClient.mockEffectful((_, _, _) => ZIO.refailCause(original)), adapter)
        exit  <- agent.run(Msg.user("request")).exit
        spans <- sdk.spans
        invocation = spans.filter(_.getName == "agent.invocation")
      yield assertTrue(
        exit.isFailure,
        invocation.nonEmpty,
        invocation.forall(attribute(_, "weisgnosis.run.state").contains("failed")),
        invocation.forall(_.getStatus.getStatusCode == StatusCode.ERROR)
      )).provideSomeLayer[Scope](OpenTelemetry.contextZIO)
    ,
    test(
      "caller cancellation joins model cleanup before ending spans and preserves its exact defect"
    ):
      (for
        sdk      <- recording
        contexts <- ZIO.service[ContextStorage]
        adapter  <- AgentTelemetry.make(sdk.tracer, contexts)
        entered  <- Promise.make[Nothing, Unit]
        cleaning <- Promise.make[Nothing, Unit]
        release  <- Promise.make[Nothing, Unit]
        defect = AgentError.InvalidConfiguration("private_cleanup_defect")
        client = LlmClient.mockEffectful((_, _, _) =>
          (entered.succeed(()) *> ZIO.never).ensuring(
            cleaning.succeed(()) *> release.await *> ZIO.die(defect)
          )
        )
        agent <- install(client, adapter)
        worker <- ZIO.uninterruptible(
          agent
            .run(Msg.user("request"))
            .interruptible
            .forkScoped
            .flatMap(fiber => ZIO.addFinalizer(release.succeed(()).unit).as(fiber))
        )
        _          <- entered.await
        controller <- ZIO.fiberId
        canceller <- ZIO.uninterruptible(
          worker
            .interruptAs(controller)
            .interruptible
            .forkScoped
            .flatMap(fiber => ZIO.addFinalizer(release.succeed(()).unit).as(fiber))
        )
        _      <- cleaning.await
        before <- sdk.spans
        _      <- release.succeed(())
        exit   <- canceller.join
        spans  <- sdk.spans
      yield assertTrue(
        before.isEmpty,
        exit.causeOption.exists(cause =>
          cause.interruptors.contains(controller) && cause.defects == List(defect)
        ),
        spans.count(_.getName == "agent.invocation") == 1,
        spans
          .filter(_.getName == "agent.invocation")
          .forall(attribute(_, "weisgnosis.run.state").contains("failed")),
        spans.forall(attribute(_, "weisgnosis.outcome").contains("failure")),
        spans.forall(attribute(_, "weisgnosis.interruption").contains("present"))
      )).provideSomeLayer[Scope](OpenTelemetry.contextZIO)
    ,
    test("host exporter failure does not change successful execution or cause a model retry"):
      (for
        provider <- ZIO.acquireRelease(
          ZIO.attempt(
            SdkTracerProvider
              .builder()
              .addSpanProcessor(SimpleSpanProcessor.create(unavailableExporter))
              .build()
          )
        )(value => ZIO.attempt(value.close()).orDie)
        tracer   <- ZIO.attempt(provider.get("unavailable-exporter"))
        contexts <- ZIO.service[ContextStorage]
        adapter  <- AgentTelemetry.make(tracer, contexts)
        calls    <- Ref.make(0)
        agent <- install(
          LlmClient.mockEffectful((_, _, _) =>
            calls.update(_ + 1).as(TurnOutput.FinalText(Msg.assistant("finished")))
          ),
          adapter
        )
        result <- agent.run(Msg.user("request"))
        count  <- calls.get
      yield assertTrue(
        result.state == AgentRunState.Completed,
        result.reply.text == "finished",
        count == 1
      )).provideSomeLayer[Scope](OpenTelemetry.contextZIO)
    ,
    test(
      "host SDK exports a bounded batch over actual local OTLP HTTP and flushes before shutdown"
    ):
      ZIO
        .scoped(
          for
            received <- Ref.make(Chunk.empty[(Option[String], Chunk[Byte])])
            port <- Server.install(Routes(Method.POST / "v1" / "traces" -> handler {
              (request: Request) =>
                request.body.asStream
                  .take(65537)
                  .runCollect
                  .orElseSucceed(Chunk.empty)
                  .flatMap(bytes =>
                    received.update(_ :+ (request.rawHeaders("Content-Type").headOption -> bytes))
                  )
                  .as(
                    Response(
                      headers = Headers("Content-Type" -> "application/x-protobuf"),
                      body = Body.empty
                    )
                  )
            }))
            provider <- ZIO.acquireRelease(
              for
                exporter <- ZIO.attempt(
                  OtlpHttpSpanExporter
                    .builder()
                    .setEndpoint(s"http://127.0.0.1:$port/v1/traces")
                    .setTimeout(java.time.Duration.ofSeconds(3))
                    .build()
                )
                processor <- ZIO
                  .attempt(
                    BatchSpanProcessor
                      .builder(exporter)
                      .setMaxQueueSize(128)
                      .setMaxExportBatchSize(64)
                      .setScheduleDelay(java.time.Duration.ofMinutes(1))
                      .setExporterTimeout(java.time.Duration.ofSeconds(3))
                      .build()
                  )
                  .onError(_ => shutdown(exporter.shutdown()))
                provider <- ZIO
                  .attempt(
                    SdkTracerProvider.builder().addSpanProcessor(processor).build()
                  )
                  .onError(_ => shutdown(processor.shutdown()))
              yield provider
            )(value => shutdown(value.shutdown()))
            tracer   <- ZIO.attempt(provider.get("local-otlp"))
            contexts <- ZIO.service[ContextStorage]
            adapter  <- AgentTelemetry.make(tracer, contexts)
            client   <- LlmClient.mock(Chunk(TurnOutput.FinalText(Msg.assistant("exported"))))
            agent    <- install(client, adapter)
            result   <- agent.run(Msg.user("request"))
            flushed  <- complete(provider.forceFlush())
            batches  <- received.get
            payloads = batches.map((_, bytes) =>
              new String(bytes.toArray, StandardCharsets.ISO_8859_1)
            )
          yield assertTrue(
            result.state == AgentRunState.Completed,
            flushed,
            batches.nonEmpty,
            batches.forall((contentType, bytes) =>
              contentType.exists(
                _.startsWith("application/x-protobuf")
              ) && bytes.nonEmpty && bytes.size <= 65536
            ),
            payloads.exists(_.contains("agent.invocation")),
            payloads.exists(_.contains("agent.model"))
          )
        )
        .provideSomeLayer[Scope](OpenTelemetry.contextZIO ++ Server.defaultWithPort(0))
  ) @@ TestAspect.timeout(20.seconds)
