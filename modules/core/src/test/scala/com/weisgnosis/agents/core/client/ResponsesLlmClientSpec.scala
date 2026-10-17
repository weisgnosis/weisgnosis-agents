/* Copyright 2024-2026 WeisGnosis. Licensed under the Apache License, Version 2.0. */
package com.weisgnosis.agents.core.client

import com.weisgnosis.agents.core.agent.{
  AgentRunState,
  AgentSnapshot,
  ReActAgent,
  SuppliedToolResult
}
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.model.Msg
import com.weisgnosis.agents.core.tool.{Tool, ToolBinding, ToolDef, ToolExecutionResult, Toolkit}
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

object ResponsesLlmClientSpec extends ZIOSpecDefault:
  import ResponsesFixtures.*
  final private case class EmptyArguments() derives JsonCodec

  private val proposed: Chunk[Json.Obj] = Chunk(
    reasoning("reasoning-one", "opaque-one"),
    function("function-item-one", "call-one"),
    reasoning("reasoning-two", "opaque-two", "Review evidence"),
    function("function-item-two", "call-two")
  )
  private val definition: ToolDef = ToolDef(
    "read_evidence",
    "Read host evidence",
    Json.Obj("type" -> Json.Str("object")),
    isReadOnly = true
  )

  private def response(wire: String): Response = Response(body = Body.fromString(wire))
    .addHeader("Content-Type", "text/event-stream")

  private def server(requests: Ref[Chunk[(String, Boolean)]]): ZIO[Server, Nothing, Int] =
    Server.install(Routes(Method.POST / "responses" -> handler { (request: Request) =>
      request.body.asString
        .orElseSucceed("")
        .flatMap(body =>
          requests.modify(previous =>
            (
              previous.size,
              previous :+ (
                body,
                request.rawHeaders("Authorization").contains("Bearer fixture-key")
              )
            )
          )
        )
        .map(index =>
          response(
            if index == 0 then stream(proposed) else stream(Chunk(message("Review complete")))
          )
        )
    }))

  private def parsed(
      requests: Ref[Chunk[(String, Boolean)]]
  ): IO[AgentError, Chunk[(Json.Obj, Boolean)]] =
    requests.get.flatMap(values =>
      ZIO.foreach(values) { case (body, authorized) =>
        ZIO
          .fromEither(body.fromJson[Json.Obj])
          .mapError(AgentError.DeserializationError(_))
          .map((_, authorized))
      }
    )

  def spec: Spec[TestEnvironment & Scope, Any] = suite("ResponsesLlmClientSpec")(
    test(
      "actual HTTP continuation persists proposals before local execution and replays every native item"
    ):
      ZIO
        .scoped:
          for
            requests  <- Ref.make(Chunk.empty[(String, Boolean)])
            invoked   <- Ref.make(0)
            persisted <- Ref.make(Chunk.empty[Boolean])
            owner     <- Ref.make(Option.empty[ReActAgent])
            port      <- server(requests)
            http      <- ZIO.service[Client]
            client    <- ResponsesLlmClient.make(http)
            tool = Tool.make(definition)(_ =>
              for
                agent <- owner.get.flatMap(value =>
                  ZIO
                    .fromOption(value)
                    .orElseFail(AgentError.InvalidConfiguration("Missing owning runtime"))
                )
                history <- agent.history
                _ <- persisted.update(
                  _ :+ (history.flatMap(_.toolUses).map(_.id) == Chunk("call-one", "call-two"))
                )
                _ <- invoked.update(_ + 1)
              yield "Evidence checked"
            )
            agent <- ReActAgent.make(
              client = client,
              toolkit = Toolkit.single(tool),
              llmConfig = config
                .copy(endpoint = s"http://127.0.0.1:$port/responses", apiKey = Some("fixture-key"))
            )
            _          <- owner.set(Some(agent))
            result     <- agent.run(Msg.user("Review evidence"))
            sent       <- parsed(requests)
            calls      <- invoked.get
            visibility <- persisted.get
            continuation = sent
              .drop(1)
              .flatMap(_._1.get("input").flatMap(_.asArray).getOrElse(Chunk.empty))
          yield assertTrue(
            result.state == AgentRunState.Completed,
            result.reply.text == "Review complete",
            calls == 2,
            visibility == Chunk(true, true),
            sent.size == 2,
            sent.forall(_._2),
            sent.forall(_._1.get("store").contains(Json.Bool(false))),
            sent.headOption.exists(
              _._1
                .get("tools")
                .flatMap(_.asArray)
                .exists(
                  _.exists(value =>
                    value.asObject.exists(tool =>
                      tool.get("name").contains(Json.Str("read_evidence")) && tool
                        .get("strict")
                        .contains(Json.Bool(false))
                    )
                  )
                )
            ),
            continuation.slice(1, 5) == proposed,
            continuation
              .drop(5)
              .flatMap(_.asObject.flatMap(_.get("call_id")).flatMap(_.asString))
              .toSet == Set("call-one", "call-two"),
            result.usage.exists(value => value.inputTokens == 20 && value.outputTokens == 10)
          )
        .provide(Client.default, Server.defaultWithPort(0))
    ,
    test(
      "serialized suspended checkpoint resumes exact reasoning items over a second HTTP request"
    ):
      ZIO
        .scoped:
          for
            requests <- Ref.make(Chunk.empty[(String, Boolean)])
            port     <- server(requests)
            http     <- ZIO.service[Client]
            client   <- ResponsesLlmClient.make(http)
            binding  <- ZIO.fromEither(ToolBinding.external[EmptyArguments](definition))
            settings = config
              .copy(endpoint = s"http://127.0.0.1:$port/responses", apiKey = Some("fixture-key"))
            first <- ReActAgent
              .make(client = client, toolkit = Toolkit.single(binding), llmConfig = settings)
            suspended <- first.run(Msg.user("Review evidence"))
            snapshot  <- first.snapshot
            decoded   <- ZIO.fromEither(snapshot.toJson.fromJson[AgentSnapshot])
            restored <- ReActAgent
              .make(client = client, toolkit = Toolkit.single(binding), llmConfig = settings)
            _ <- restored.restoreSnapshot(decoded)
            result <- restored.resume(
              Chunk(
                SuppliedToolResult(0, ToolExecutionResult("first evidence")),
                SuppliedToolResult(1, ToolExecutionResult("second evidence"))
              )
            )
            sent <- parsed(requests)
            continuation = sent
              .drop(1)
              .flatMap(_._1.get("input").flatMap(_.asArray).getOrElse(Chunk.empty))
          yield assertTrue(
            suspended.isSuspended,
            result.state == AgentRunState.Completed,
            sent.size == 2,
            continuation.slice(1, 5) == proposed,
            result.history.flatMap(_.toolResults).map(_.text).toSet == Set(
              "first evidence",
              "second evidence"
            ),
            result.history.flatMap(_.toolUses).map(_.id) == Chunk("call-one", "call-two")
          )
        .provide(Client.default, Server.defaultWithPort(0))
  ) @@ TestAspect.withLiveClock @@ TestAspect.timeout(20.seconds)
