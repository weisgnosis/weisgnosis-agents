/* Copyright 2024-2026 WeisGnosis. Licensed under the Apache License, Version 2.0. */
package com.weisgnosis.agents.examples

import com.weisgnosis.agents.core.client.LlmConfig
import com.weisgnosis.agents.core.error.AgentError
import zio.*
import zio.http.*
import zio.json.*
import zio.test.*
import zio.stream.ZStream

object LiveRequestBoundarySpec extends ZIOSpecDefault:
  private val partialFrame: String =
    "data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"pending\",\"function\":{\"name\":\"boundary_probe\",\"arguments\":\"{\"}}]}}]}\n\n"

  def spec: Spec[TestEnvironment & Scope, Any] = suite("LiveRequestBoundarySpec")(
    test(
      "real loopback HTTP streaming joins the borrowed Netty request after a parsed partial frame"
    ):
      (for
        requests <- Ref.make(0)
        port <- Server.install(Routes(Method.POST / "fixture" -> handler {
          requests
            .update(_ + 1)
            .as(
              Response(
                headers = Headers("Content-Type", "text/event-stream"),
                body =
                  Body.fromCharSequenceStreamChunked(ZStream.succeed(partialFrame) ++ ZStream.never)
              )
            )
        }))
        http <- ZIO.service[Client]
        receipt <- LiveRequestBoundary.exercise(
          http,
          LlmConfig("offline", endpoint = s"http://127.0.0.1:$port/fixture"),
          30.seconds
        )
        count <- requests.get
      yield assertTrue(
        receipt.scenarioAccepted,
        receipt.parsedRuntimeChunk,
        receipt.consumerPendingDuringCleanup,
        receipt.requestScopeReleased,
        receipt.boundaryCleanupReleased,
        receipt.localExecutions == 0,
        receipt.partialCalls == 1,
        receipt.incompleteCalls == 1,
        receipt.incompleteArgumentsSanitized,
        count == 1
      )).provide(Client.default, Server.defaultWithPort(0))
    ,
    test("fixed boundary diagnostics retain only lifecycle fields and HTTP status"):
      val secret = "credential-private-frame-and-request"
      val snapshot = LiveRequestBoundary.FailureDiagnostic(
        phase = LiveRequestBoundary.Phase.AwaitingFirstParsedFrame,
        requests = 1,
        requestScopeReleased = true
      )
      val failures = Chunk(
        AgentError.ModelError(400, secret, Some(secret)),
        AgentError.InvalidMessage(secret)
      ).map(LiveRequestBoundary.failureDiagnostic(snapshot, _))
      assertTrue(
        failures.headOption.flatMap(_.httpStatus).contains(400),
        failures.forall(_.requests == 1),
        failures.forall(_.requestScopeReleased),
        !failures.toJson.contains(secret)
      )
    ,
    test(
      "parsed real client events trigger cancellation and join held request cleanup without dispatch"
    ):
      for
        closed <- Ref.make(false)
        http = LiveRequestBoundary.driver(_ =>
          ZIO
            .addFinalizer(closed.set(true))
            .as(
              Response(
                headers = Headers("Content-Type", "text/event-stream"),
                body = Body.fromString(
                  partialFrame +
                    "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\ndata: [DONE]\n\n"
                )
              )
            )
        )
        receipt <- LiveRequestBoundary.exercise(
          http,
          LlmConfig("offline", endpoint = "http://localhost/fixture"),
          30.seconds
        )
        upstreamReleased <- closed.get
      yield assertTrue(
        receipt.scenarioAccepted,
        receipt.parsedRuntimeChunk,
        receipt.consumerPendingDuringCleanup,
        receipt.requestScopeReleased,
        upstreamReleased,
        receipt.localExecutions == 0,
        receipt.interruptionPreserved,
        receipt.recoveryExactlyOnce,
        receipt.partialCalls == 1,
        receipt.incompleteCalls == 1,
        receipt.incompleteArgumentsSanitized,
        receipt.unfinishedCallsReconciled,
        receipt.noCompletedModelEvent
      )
    ,
    test(
      "acquisition failure preserves its public translated cause without waiting for the cleanup gate"
    ):
      val failure = AgentError.InvalidConfiguration("Fixture acquisition failed")
      for
        requests <- Ref.make(0)
        closed   <- Ref.make(false)
        outcome <- LiveRequestBoundary
          .exercise(
            LiveRequestBoundary.driver(_ =>
              requests.update(_ + 1) *> ZIO.addFinalizer(closed.set(true)) *> ZIO.fail(failure)
            ),
            LlmConfig("offline", endpoint = "http://localhost/fixture"),
            30.seconds
          )
          .either
        count    <- requests.get
        released <- closed.get
      yield assertTrue(
        outcome == Left(AgentError.ModelError(0, "HTTP request failed (other)")),
        count == 1,
        released
      )
    ,
    test(
      "a borrowed HTTP 503 retains its JSON body and exact public status without entering the stream gate"
    ):
      val body = "{\"error\":\"fixture unavailable\"}"
      for
        requests <- Ref.make(0)
        closed   <- Ref.make(false)
        outcome <- LiveRequestBoundary
          .exercise(
            LiveRequestBoundary.driver(_ =>
              requests.update(_ + 1) *> ZIO
                .addFinalizer(closed.set(true))
                .as(
                  Response(
                    status = Status.ServiceUnavailable,
                    headers = Headers("Content-Type", "application/json"),
                    body = Body.fromString(body)
                  )
                )
            ),
            LlmConfig("offline", endpoint = "http://localhost/fixture"),
            30.seconds
          )
          .either
        count    <- requests.get
        released <- closed.get
      yield assertTrue(
        outcome == Left(
          AgentError.ModelError(503, "HTTP completion request was rejected", Some(body))
        ),
        count == 1,
        released
      )
    ,
    test(
      "owned offline malformed, truncated and exhaustion controls never dispatch incomplete calls"
    ):
      ZIO
        .foreach(
          Chunk(
            LiveRequestBoundary.Control.Malformed,
            LiveRequestBoundary.Control.Truncated,
            LiveRequestBoundary.Control.TokenExhausted
          )
        )(
          LiveRequestBoundary.exerciseControl
        )
        .map(receipts =>
          assertTrue(
            receipts.forall(_.scenarioAccepted),
            receipts.forall(_.evidence == "offline-fixture"),
            receipts.forall(_.requests == 1),
            receipts.forall(_.requestScopeReleased),
            receipts.forall(_.localExecutions == 0),
            receipts.map(_.state) == Chunk("Rejected", "Rejected", "Stopped")
          )
        )
  ) @@ TestAspect.withLiveClock @@ TestAspect.timeout(2.minutes)
