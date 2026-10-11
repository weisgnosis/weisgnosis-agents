/* Copyright 2024-2026 WeisGnosis. Licensed under the Apache License, Version 2.0. */
package com.weisgnosis.agents.examples

import com.weisgnosis.agents.core.client.{LlmClient, LlmConfig, TurnOutput}
import com.weisgnosis.agents.core.agent.ToolExecution
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.event.{AgentEvent, ModelStreamContext}
import com.weisgnosis.agents.core.model.Msg
import com.weisgnosis.agents.core.model.ContentBlock
import com.weisgnosis.agents.core.tool.ToolDef
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

object LiveMultiModelSpec extends ZIOSpecDefault:
  private def inspecting(
      respond: (Chunk[Msg], LlmConfig) => IO[AgentError, TurnOutput]
  ): LlmClient = new LlmClient:
    def streamTurn(
        history: Chunk[Msg],
        tools: Chunk[ToolDef],
        config: LlmConfig,
        hub: Hub[AgentEvent],
        context: ModelStreamContext
    ): ZIO[Scope, AgentError, TurnOutput] = respond(history, config)

  private val catalog: String = Json
    .Obj(
      "data" -> Json.Arr(
        Json.Obj(
          "id"           -> Json.Str("primary"),
          "capabilities" -> Json.Arr(Json.Str("chat")),
          "status"       -> Json.Str("loaded"),
          "endpoint"     -> Json.Str("/api/v1/chat/completions")
        ),
        Json.Obj(
          "id"           -> Json.Str("peer"),
          "capabilities" -> Json.Arr(Json.Str("chat")),
          "status"       -> Json.Str("not-loaded"),
          "endpoint"     -> Json.Str("/api/v1/chat/completions")
        ),
        Json.Obj(
          "id"           -> Json.Str("embedding"),
          "capabilities" -> Json.Arr(Json.Str("embeddings")),
          "status"       -> Json.Str("loaded"),
          "endpoint"     -> Json.Str("/api/v1/embeddings")
        )
      )
    )
    .toJson

  def spec: Spec[TestEnvironment & Scope, Any] = suite("LiveMultiModel")(
    test(
      "bounded authenticated discovery validates exact chat selections and rejects malformed catalogs"
    ):
      (for
        reads <- Ref.make(Chunk.empty[Boolean])
        port <- Server.install(
          Routes(
            Method.GET / "healthz" -> handler(Response.text("healthy")),
            Method.GET / "api" / "v1" / "models" -> handler { (request: Request) =>
              reads
                .update(_ :+ request.rawHeaders("Authorization").contains("Bearer fixture-key"))
                .as(Response.json(catalog))
            },
            Method.GET / "api" / "@tenant" / "v1" / "models" -> handler { (request: Request) =>
              reads
                .update(_ :+ request.rawHeaders("Authorization").contains("Bearer fixture-key"))
                .as(Response.json(catalog))
            }
          )
        )
        http <- ZIO.service[Client]
        models <- LiveMultiModel.discover(
          http,
          LlmConfig(
            "primary",
            endpoint = s"http://127.0.0.1:$port/api/v1/chat/completions",
            apiKey = Some("fixture-key")
          )
        )
        pathAt <- LiveMultiModel.discover(
          http,
          LlmConfig(
            "primary",
            endpoint = s"http://127.0.0.1:$port/api/@tenant/v1/chat/completions",
            apiKey = Some("fixture-key")
          )
        )
        userInfo <- LiveMultiModel
          .discover(
            http,
            LlmConfig(
              "primary",
              endpoint = s"http://user:password@127.0.0.1:$port/api/v1/chat/completions"
            )
          )
          .either
        observed <- reads.get
        duplicate = Json
          .Obj(
            "data" -> Json.Arr(
              Chunk
                .fromIterable(models.headOption.toList)
                .flatMap(model =>
                  Chunk(model.toJsonAST.getOrElse(Json.Null), model.toJsonAST.getOrElse(Json.Null))
                )
            )
          )
          .toJson
      yield assertTrue(
        observed == Chunk(true, true),
        pathAt == models,
        userInfo.isLeft,
        LiveMultiModel.eligibility("primary", models).isEmpty,
        LiveMultiModel.eligibility("peer", models).isEmpty,
        LiveMultiModel.eligibility("embedding", models).contains(LiveMultiModel.Status.Unsupported),
        LiveMultiModel.eligibility("absent", models).contains(LiveMultiModel.Status.Unavailable),
        LiveMultiModel.decodeCatalog("{\"data\":false}").isLeft,
        LiveMultiModel.decodeCatalog(duplicate).isLeft
      )).provideSomeLayer[Scope](Client.default ++ Server.defaultWithPort(0))
    ,
    test(
      "same public collaboration and real HTTP rejection paths preserve attribution and outcome authority"
    ):
      (for
        requests <- Ref.make(Chunk.empty[(String, Chunk[Msg])])
        client = inspecting((history, config) =>
          requests
            .updateAndGet(_ :+ (config.model -> history))
            .map(observed => TurnOutput.FinalText(Msg.assistant(s"reply-${observed.size}")))
        )
        _        <- LiveMultiModel.conversation(client, LlmConfig("primary"), LlmConfig("peer"))
        observed <- requests.get
        familyRequests <- Ref.make(Chunk.empty[String])
        familyClient = inspecting((history, config) =>
          familyRequests
            .update(_ :+ config.model)
            .as(
              if config.model == "parent" && history.flatMap(_.toolResults).isEmpty then
                TurnOutput.ToolCalls(
                  Msg.assistant(
                    Chunk(ContentBlock.ToolUse("child-call", "consult_child", Json.Obj()))
                  )
                )
              else
                TurnOutput.FinalText(
                  Msg.assistant(if config.model == "child" then "child answer" else "parent answer")
                )
            )
        )
        _      <- LiveMultiModel.parentChild(familyClient, LlmConfig("parent"), LlmConfig("child"))
        family <- familyRequests.get
        port <- Server.install(Routes(Method.POST / "complete" -> handler {
          Response(
            headers = Headers("Content-Type" -> "text/event-stream"),
            body = Body.fromString(
              "data: {\"id\":\"fallback\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"finished\"},\"finish_reason\":null}]}\n\n" +
                "data: {\"id\":\"fallback\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n" +
                "data: [DONE]\n\n"
            )
          )
        }))
        http <- ZIO.service[Client]
        failover <- LiveMultiModel.record("failover", Chunk("primary"))(
          LiveMultiModel.definiteFailover(
            http,
            LlmConfig("primary", endpoint = s"http://127.0.0.1:$port/complete")
          )
        )
        failed <- LiveMultiModel.record("failed", Chunk("primary"))(
          ZIO.fail(AgentError.InvalidMessage("assertion failed"))
        )
        unavailable <- LiveMultiModel.record("unavailable", Chunk("primary"))(
          ZIO.fail(AgentError.ModelError(503, "not admitted"))
        )
        cancelling <- LiveMultiModel.record("cancelled", Chunk("primary"))(ZIO.interrupt).forkScoped
        cancelled  <- cancelling.await
      yield assertTrue(
        observed.map(_._1) == Chunk("primary", "peer", "primary"),
        observed.lift(1).exists(_._2.exists(_.text.contains("Peer message from \"Alice\""))),
        observed.lift(2).exists(_._2.exists(_.text.contains("Peer message from \"Bob\""))),
        family == Chunk("parent", "child", "parent"),
        failover.status == LiveMultiModel.Status.Passed,
        failed.status == LiveMultiModel.Status.Failed,
        unavailable.status == LiveMultiModel.Status.Unavailable,
        cancelled.isInterrupted
      )).provideSomeLayer[Scope](Client.default ++ Server.defaultWithPort(0))
    ,
    test(
      "live evidence fixture verifies parallel overlap, sequential release and rejects unexercised or wrong tasks"
    ):
      val calls: TurnOutput = TurnOutput.ToolCalls(
        Msg.assistant(
          Chunk(
            ContentBlock.ToolUse("right", "evidence_right", Json.Obj()),
            ContentBlock.ToolUse("left", "evidence_left", Json.Obj())
          )
        )
      )
      for
        requests <- Ref.make(Chunk.empty[Chunk[Msg]])
        client = inspecting((history, _) =>
          requests
            .update(_ :+ history)
            .as(
              if history.flatMap(_.toolResults).isEmpty then calls
              else TurnOutput.FinalText(Msg.assistant("{\"sum\":37}"))
            )
        )
        verified <- ZIO.foreach(Chunk(ToolExecution.Sequential, ToolExecution.Parallel))(mode =>
          LiveParallelTools.exerciseMode(client, LlmConfig("primary"), mode)
        )
        observed <- requests.get
        missing <- LiveParallelTools
          .exerciseMode(
            inspecting((_, _) => ZIO.succeed(TurnOutput.FinalText(Msg.assistant("{\"sum\":37}")))),
            LlmConfig("primary"),
            ToolExecution.Parallel
          )
          .either
        wrong <- LiveParallelTools
          .exerciseMode(
            inspecting((history, _) =>
              ZIO.succeed(
                if history.flatMap(_.toolResults).isEmpty then calls
                else TurnOutput.FinalText(Msg.assistant("{\"sum\":38}"))
              )
            ),
            LlmConfig("primary"),
            ToolExecution.Parallel
          )
          .either
        continuing <- Promise.make[Nothing, Unit]
        cancelled <- ZIO.scoped:
          for
            running <- LiveParallelTools
              .exerciseMode(
                inspecting((history, _) =>
                  if history.flatMap(_.toolResults).isEmpty then ZIO.succeed(calls)
                  else continuing.succeed(()) *> ZIO.never
                ),
                LlmConfig("primary"),
                ToolExecution.Parallel
              )
              .forkScoped
            _    <- continuing.await
            exit <- running.interrupt
          yield exit
      yield assertTrue(
        verified.map(_.peakActive) == Chunk(1, 2),
        verified.forall(_.releasedTools == 2),
        observed.size == 4,
        observed
          .filter(_.flatMap(_.toolResults).nonEmpty)
          .forall(history =>
            history.flatMap(_.toolResults).map(_.outputText).toSet == Set("17", "20")
          ),
        missing.left.exists(_.isInstanceOf[AgentError.ReasoningHookError]),
        wrong.left.exists(_.isInstanceOf[AgentError.InvalidMessage]),
        cancelled.isInterrupted
      )
  ) @@ TestAspect.timeout(30.seconds) @@ TestAspect.withLiveClock
