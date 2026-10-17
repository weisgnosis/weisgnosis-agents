/* Copyright 2024-2026 WeisGnosis. Licensed under the Apache License, Version 2.0. */
package com.weisgnosis.agents.examples

import com.weisgnosis.agents.core.agent.{AgentRunState, AgentSnapshot}
import com.weisgnosis.agents.core.client.{ScriptedLlmClient, TurnOutput}
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.model.Msg
import com.weisgnosis.agents.core.tool.Toolkit
import com.weisgnosis.agents.examples.HostModelRouting.*
import zio.*
import zio.json.*
import zio.test.*

object OfflineModelRoutingSpec extends ZIOSpecDefault:
  private def clients: IO[AgentError, (ScriptedLlmClient, ScriptedLlmClient)] =
    ScriptedLlmClient.make(Chunk.empty, 2).zip(ScriptedLlmClient.make(Chunk.empty, 2))

  def spec: Spec[TestEnvironment & Scope, Any] = suite("OfflineModelRoutingSpec")(
    test("a suspended reply restores its route after the default changes"):
      OfflineModelRouting.scenario.map(value =>
        assertTrue(
          value.restoredRoute == "reviewer",
          value.defaultModelRequests == 0,
          value.selectedModelRequests == 2,
          value.result.state == AgentRunState.Completed,
          value.result.history.flatMap(_.toolResults).size == 1,
          value.result.history.flatMap(_.toolResults).map(_.text) == Chunk(
            "Build and tests passed."
          )
        )
      )
    ,
    test("selection uses task, capability, cost and explicit caller eligibility"):
      for
        pair <- clients
        (a, b)     = pair
        registered = OfflineModelRouting.candidates(a, b)
        host <- ZIO.fromEither(registry(registered, "reviewer"))
        economyOnly  = OfflineModelRouting.requirements.copy(maxCost = CostTier.Economy)
        noReview     = registered.map(_.copy(tasks = Set(Task.General)))
        noCapability = registered.map(_.copy(capabilities = Set.empty))
        taskHost       <- ZIO.fromEither(registry(noReview, "reviewer"))
        capabilityHost <- ZIO.fromEither(registry(noCapability, "reviewer"))
      yield assertTrue(
        host.select(OfflineModelRouting.requirements).map(_.binding.id) == Right("reviewer"),
        host.select(economyOnly).map(_.binding.id) == Right("economy"),
        host.select(economyOnly, Some("reviewer")).isLeft,
        host.select(economyOnly, Some("unknown")).isLeft,
        taskHost.select(OfflineModelRouting.requirements).isLeft,
        capabilityHost.select(OfflineModelRouting.requirements).isLeft,
        registry(registered ++ registered, "reviewer").isLeft,
        registry(Chunk.empty, "reviewer").isLeft,
        registry(registered, "unknown").isLeft,
        registry(
          registered.map(value => value.copy(binding = value.binding.copy(revision = ""))),
          "reviewer"
        ).isLeft
      )
    ,
    test(
      "restore rejects missing, revised, incompatible and ineligible bindings before model calls"
    ):
      for
        pair <- clients
        (a, b)     = pair
        registered = OfflineModelRouting.candidates(a, b)
        host <- ZIO.fromEither(registry(registered, "economy"))
        checkpoint = Checkpoint(
          1,
          Binding("reviewer", "1", "reviewer-v1"),
          AgentSnapshot.Idle(Chunk.empty)
        )
        failures <- ZIO.foreach(
          Chunk(
            checkpoint.copy(version = 2),
            checkpoint.copy(binding = checkpoint.binding.copy(id = "missing")),
            checkpoint.copy(binding = checkpoint.binding.copy(revision = "2")),
            checkpoint.copy(binding = checkpoint.binding.copy(replayCompatibilityKey = "different"))
          )
        )(value => host.restore(value, OfflineModelRouting.requirements, Toolkit.empty).either)
        ineligible <- host
          .restore(
            checkpoint,
            OfflineModelRouting.requirements.copy(maxCost = CostTier.Economy),
            Toolkit.empty
          )
          .either
        aRequests <- a.requests
        bRequests <- b.requests
      yield assertTrue(
        failures.forall(_.isLeft),
        ineligible.isLeft,
        aRequests.isEmpty,
        bRequests.isEmpty
      )
    ,
    test(
      "checkpoint codecs exclude trusted provider configuration and restore without reselection"
    ):
      for
        a <- ScriptedLlmClient.make(Chunk.empty, 2)
        b <- ScriptedLlmClient.make(Chunk(TurnOutput.FinalText(Msg.assistant("bound reviewer"))), 2)
        registered = OfflineModelRouting
          .candidates(a, b)
          .map(candidate =>
            candidate.copy(target =
              candidate.target.copy(config =
                candidate.target.config.copy(
                  apiKey = Some("routing-test-credential"),
                  endpoint = "https://routing-test.invalid/private"
                )
              )
            )
          )
        original   <- ZIO.fromEither(registry(registered, "reviewer"))
        runtime    <- original.open(OfflineModelRouting.requirements, Toolkit.empty)
        checkpoint <- runtime.checkpoint
        encoded = checkpoint.toJson
        decoded   <- ZIO.fromEither(encoded.fromJson[Checkpoint])
        restarted <- ZIO.fromEither(registry(registered, "economy"))
        selected  <- ZIO.fromEither(restarted.select(OfflineModelRouting.requirements))
        restored  <- restarted.restore(decoded, OfflineModelRouting.requirements, Toolkit.empty)
        result    <- restored.agent.run(Msg.user("continue"))
        aRequests <- a.requests
        bRequests <- b.requests
      yield assertTrue(
        selected.binding.id == "economy",
        restored.binding.id == "reviewer",
        result.reply.text == "bound reviewer",
        aRequests.isEmpty,
        bRequests.size == 1,
        !encoded.contains("routing-test-credential"),
        !encoded.contains("routing-test.invalid"),
        !encoded.contains("apiKey"),
        !encoded.contains("endpoint")
      )
  )
