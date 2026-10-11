/* Copyright 2024-2026 WeisGnosis. Licensed under the Apache License, Version 2.0. */
package com.weisgnosis.agents.examples

import com.weisgnosis.agents.core.client.{LlmClient, LlmConfig, TurnOutput}
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.model.{ContentBlock, Msg}
import zio.*
import zio.http.Client
import zio.json.ast.Json
import zio.test.*

object LiveMcpEvidenceSpec extends ZIOSpecDefault:
  private def model(proposal: Json): UIO[LlmClient] = LlmClient.mock(
    Chunk(
      TurnOutput.ToolCalls(
        Msg.assistant(Chunk(ContentBlock.ToolUse("read", "read_evidence", Json.Obj())))
      ),
      TurnOutput.ToolCalls(
        Msg.assistant(Chunk(ContentBlock.ToolUse("write", "write_report", proposal)))
      )
    )
  )

  def spec: Spec[TestEnvironment & Scope, Any] = suite("LiveMcpEvidenceSpec")(
    test(
      "HTTP MCP discovery, fixed selectors, Ask restoration, Deny and Plan preserve exact host effects"
    ):
      for
        client  <- model(Json.Obj("report" -> LiveMcpEvidence.expectedReport))
        receipt <- ZIO.scoped(LiveMcpEvidence.exercise(client, LlmConfig("offline"), 30.seconds))
      yield assertTrue(
        receipt.scenarioAccepted,
        receipt.modelRequests == 2,
        receipt.evidenceReads == 1,
        receipt.authorizedWrites == 1,
        receipt.askSuspendedWithoutWrite,
        receipt.deniedWithoutWrite,
        receipt.planWithoutWrite,
        receipt.restoredWithoutReasoningOrReadReplay,
        receipt.exactReportBytes,
        receipt.discoveredSchemas.size == 2,
        receipt.installedSchemas.size == 2,
        receipt.discoveredSchemas != receipt.installedSchemas
      )
    ,
    test("model-selected namespace, path and incorrect evidence cannot reach the MCP write"):
      ZIO
        .foreach(
          Chunk(
            Json
              .Obj("report" -> LiveMcpEvidence.expectedReport, "namespace" -> Json.Str("foreign")),
            Json.Obj(
              "report"     -> LiveMcpEvidence.expectedReport,
              "reportPath" -> Json.Str("../outside")
            ),
            Json.Obj("report" -> Json.Obj("releaseAllowed" -> Json.Bool(true)))
          )
        ): proposal =>
          for
            client <- model(proposal)
            writes <- Ref.make(Chunk.empty[Chunk[Byte]])
            result <- ZIO
              .scoped(
                LiveMcpEvidence.exerciseWithLedger(client, LlmConfig("offline"), 30.seconds, writes)
              )
              .either
            stored <- writes.get
          yield assertTrue(
            result.left.toOption.exists(_.isInstanceOf[AgentError.ReasoningHookError]),
            stored.isEmpty
          )
        .map(_.foldLeft(assertTrue(true))(_ && _))
  ).provide(Client.default) @@ TestAspect.withLiveClock @@ TestAspect
    .timeout(2.minutes)
