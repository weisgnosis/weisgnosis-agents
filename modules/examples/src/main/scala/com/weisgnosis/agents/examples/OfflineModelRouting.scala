/* Copyright 2024-2026 WeisGnosis. Licensed under the Apache License, Version 2.0. */
package com.weisgnosis.agents.examples

import com.weisgnosis.agents.core.agent.{AgentResult, AgentRunState, SuppliedToolResult}
import com.weisgnosis.agents.core.client.{LlmConfig, ModelTarget, ScriptedLlmClient, TurnOutput}
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.model.{ContentBlock, Msg}
import com.weisgnosis.agents.core.tool.{ToolBinding, ToolDef, ToolExecutionResult, Toolkit}
import com.weisgnosis.agents.examples.HostModelRouting.*
import zio.*
import zio.json.*
import zio.json.ast.Json

/** Offline host routing, external execution suspension, serialization and trusted rebinding. */
object OfflineModelRouting extends ZIOAppDefault:
  final case class ReleaseQuery(release: String) derives JsonCodec

  final case class Evidence(
      result: AgentResult,
      restoredRoute: String,
      defaultModelRequests: Int,
      selectedModelRequests: Int,
      checkpointJson: String
  )

  val requirements: Requirements =
    Requirements(Task.ReleaseReview, Set(Capability.LocalTools), CostTier.Standard)

  def toolkit: IO[AgentError, Toolkit] =
    ZIO
      .fromEither(
        ToolBinding.external[ReleaseQuery](
          ToolDef(
            "read_release_evidence",
            "Read evidence supplied by the host.",
            Json.Obj("type" -> Json.Str("object")),
            isReadOnly = true
          )
        )
      )
      .map(Toolkit.single)

  def candidates(a: ScriptedLlmClient, b: ScriptedLlmClient): Chunk[Candidate] =
    Chunk(
      Candidate(
        Binding("economy", "1", "text-v1"),
        ModelTarget(a, LlmConfig("offline-economy")),
        Set(Task.General, Task.ReleaseReview),
        Set(Capability.LocalTools),
        CostTier.Economy
      ),
      Candidate(
        Binding("reviewer", "1", "reviewer-v1"),
        ModelTarget(b, LlmConfig("offline-reviewer")),
        Set(Task.General, Task.ReleaseReview),
        Set(Capability.LocalTools),
        CostTier.Standard
      )
    )

  def scenario: ZIO[Scope, AgentError, Evidence] =
    for
      a <- ScriptedLlmClient.make(Chunk.empty, 2)
      b <- ScriptedLlmClient.make(
        Chunk(
          TurnOutput.ToolCalls(
            Msg.assistant(
              Chunk(
                ContentBlock.ToolUse(
                  "evidence-1",
                  "read_release_evidence",
                  Json.Obj("release" -> Json.Str("0.1.1"))
                )
              )
            )
          ),
          TurnOutput.FinalText(Msg.assistant("Release evidence reviewed."))
        ),
        2
      )
      tools         <- toolkit
      firstRegistry <- ZIO.fromEither(registry(candidates(a, b), "reviewer"))
      runtime       <- firstRegistry.open(requirements, tools)
      suspended     <- runtime.agent.run(Msg.user("Review release 0.1.1"))
      _ <- ZIO
        .fail(AgentError.InvalidConfiguration("Expected external execution suspension"))
        .unless(suspended.isSuspended)
      checkpoint <- runtime.checkpoint
      encoded = checkpoint.toJson
      decoded <- ZIO
        .fromEither(encoded.fromJson[Checkpoint])
        .mapError(AgentError.InvalidConfiguration(_))
      restartedRegistry <- ZIO.fromEither(registry(candidates(a, b), "economy"))
      restored          <- restartedRegistry.restore(decoded, requirements, tools)
      result <- restored.agent.resume(
        Chunk(SuppliedToolResult(0, ToolExecutionResult("Build and tests passed.")))
      )
      aRequests <- a.requests
      bRequests <- b.requests
      _         <- b.verifyConsumed
      _ <- ZIO
        .fail(AgentError.InvalidConfiguration("Route-bound continuation did not complete"))
        .unless(
          result.state == AgentRunState.Completed && restored.binding.id == "reviewer" &&
            aRequests.isEmpty && bRequests.size == 2 && bRequests
              .forall(_.input.model == "offline-reviewer")
        )
    yield Evidence(result, restored.binding.id, aRequests.size, bRequests.size, encoded)

  def run: ZIO[Scope, Throwable, Unit] =
    scenario.flatMap(value =>
      Console.printLine(
        s"Route ${value.restoredRoute} resumed after checkpoint restore: ${value.result.reply.text} " +
          s"(${value.selectedModelRequests} selected requests, ${value.defaultModelRequests} default requests)"
      )
    )
