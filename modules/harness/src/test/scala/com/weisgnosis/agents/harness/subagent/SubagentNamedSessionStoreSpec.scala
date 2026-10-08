/*
 * Copyright 2024-2026 WeisGnosis (https://weisgnosis.com)
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 */
package com.weisgnosis.agents.harness.subagent

import com.weisgnosis.agents.core.agent.{AgentConfig, AgentResult, AgentSnapshot}
import com.weisgnosis.agents.core.client.{LlmClient, LlmConfig, TurnOutput}
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.model.{ContentBlock, Msg, ToolHistory, ToolResultState}
import com.weisgnosis.agents.core.tool.{ToolAuthorizer, Toolkit}
import zio.*
import zio.json.ast.Json
import zio.test.*

object SubagentNamedSessionStoreSpec extends ZIOSpecDefault:
  def spec: Spec[TestEnvironment & Scope, Any] = suite("SubagentNamedSessionStoreSpec")(
    test(
      "registration and snapshot share strict bounded CAS; stale save and corrupt envelopes never mutate or hydrate"
    ):
      for
        backend <- SubagentTerminalStoreSpec.fixture
        store   <- ZIO.fromEither(SubagentNamedSessionStore.make(backend.store, Chunk("strict")))
        key     <- ZIO.fromEither(SubagentNamedSessionKey.from("parent", "worker", "label"))
        target  <- ZIO.fromEither(store.address(key))
        initial <- store.load(key)
        snapshot: AgentSnapshot = AgentSnapshot.Idle(Chunk(Msg.user("exact λ")))
        revision <- store.save(key, initial.revision, snapshot)
        original <- backend.store
          .read(target)
          .someOrFail(AgentError.StorageError("Missing fixture envelope"))
        stale     <- store.save(key, initial.revision, AgentSnapshot.Idle(Chunk.empty)).either
        unchanged <- backend.store.read(target)
        loaded    <- store.load(key)
        malformed = original.value match
          case Json.Obj(fields) =>
            Chunk(
              Json.Obj(
                fields.map((name, value) =>
                  if name == "schemaVersion" then name -> Json.Num(2) else name -> value
                )
              ),
              Json.Obj(fields :+ ("unknown"       -> Json.Bool(true))),
              Json.Obj(fields :+ ("schemaVersion" -> Json.Num(1))),
              Json.Obj(
                fields.map((name, value) =>
                  if name == "registration" then
                    name -> Json.Obj(
                      "parentSessionId" -> Json.Str("other"),
                      "agentId"         -> Json.Str("worker"),
                      "label"           -> Json.Str("label")
                    )
                  else name -> value
                )
              )
            )
          case _ => Chunk.empty
        beforeReads <- backend.writes.get
        rejected <- ZIO.foreach(malformed)(value =>
          backend.values
            .update(_.updated(target, original.copy(value = value))) *> store.load(key).either
        )
        afterReads <- backend.writes.get
        _          <- backend.values.update(_.updated(target, original))
        limits     <- ZIO.fromEither(SubagentNamedSessionStore.Limits.make(1024, 1))
        bounded <- ZIO.fromEither(
          SubagentNamedSessionStore.make(backend.store, Chunk("strict"), limits)
        )
        beforeBounds <- backend.writes.get
        oversized <- bounded
          .save(key, revision, AgentSnapshot.Idle(Chunk(Msg.user("one"), Msg.user("two"))))
          .either
        byteLimits <- ZIO.fromEither(SubagentNamedSessionStore.Limits.make(1, 100))
        byteBounded <- ZIO.fromEither(
          SubagentNamedSessionStore.make(backend.store, Chunk("strict"), byteLimits)
        )
        byteOverflow      <- byteBounded.save(key, revision, snapshot).either
        afterBoundsWrites <- backend.writes.get
        afterBounds       <- backend.store.read(target)
      yield assertTrue(
        initial.snapshot == AgentSnapshot.Idle(Chunk.empty),
        loaded.snapshot == snapshot,
        loaded.revision == revision,
        stale.isLeft,
        unchanged.contains(original),
        malformed.size == 4,
        rejected.forall(_.isLeft),
        afterReads == beforeReads,
        oversized.isLeft,
        byteOverflow.isLeft,
        afterBoundsWrites == beforeBounds,
        afterBounds.contains(original)
      )
    ,
    test(
      "provider running previews in completed idle history survive snapshot persistence and fresh factory restoration without replay"
    ):
      for
        backend <- SubagentTerminalStoreSpec.fixture
        store   <- ZIO.fromEither(SubagentNamedSessionStore.make(backend.store, Chunk("provider")))
        key     <- ZIO.fromEither(SubagentNamedSessionKey.from("parent", "worker", "label"))
        provider = ContentBlock.ToolUse("p", "search", Json.Obj()).withServerTool(true)
        running  = ContentBlock.ToolResult.text("p", "search", "preview", ToolResultState.Running)
        terminal = running.copy(state = ToolResultState.Success)
        history = Chunk(
          Msg.assistant(Chunk(provider, running)),
          Msg.assistant(Chunk(provider, terminal, ContentBlock.Text("done")))
        )
        initial    <- store.load(key)
        _          <- store.save(key, initial.revision, AgentSnapshot.Idle(history))
        saved      <- store.load(key)
        supervisor <- TaskSupervisor.open[AgentResult]
        root       <- SubagentSpawner.root(supervisor)
        calls      <- Ref.make(0)
        factory <- ZIO.fromEither(
          SubagentFactory.make(
            AgentConfig(),
            LlmConfig("provider"),
            Toolkit.empty,
            SubagentToolBoundary(),
            SubagentToolBoundary(),
            _ => ZIO.succeed(Toolkit.empty),
            _ =>
              ZIO.succeed(
                LlmClient.mockEffectful((_, _, _) =>
                  calls
                    .update(_ + 1)
                    .as(TurnOutput.FinalText(Msg.assistant("not during hydration")))
                )
              ),
            ToolAuthorizer.allow
          )
        )
        restored <- factory.build(root, saved.snapshot).either
        actual   <- ZIO.foreach(restored.toOption)(_.snapshot)
        unfinished: AgentSnapshot = AgentSnapshot.Idle(
          Chunk(Msg.assistant(Chunk(ContentBlock.ToolUse("local", "lookup", Json.Obj()))))
        )
        localRejected <- factory.build(root, unfinished).either
        localRunning = AgentSnapshot.Idle(
          Chunk(
            Msg.assistant(Chunk(ContentBlock.ToolUse("local", "lookup", Json.Obj()))),
            Msg.tool(
              Chunk(
                ContentBlock.ToolResult
                  .text("local", "lookup", "still working", ToolResultState.Running)
              )
            )
          )
        )
        localRunningRejected <- factory.build(root, localRunning).either
        modelCalls           <- calls.get
      yield assertTrue(
        ToolHistory.inspect(history).isComplete,
        restored.isRight,
        actual.contains(AgentSnapshot.Idle(history)),
        localRejected.isLeft,
        localRunningRejected.isLeft,
        modelCalls == 0
      )
  )
