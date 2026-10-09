/* Copyright 2024-2026 WeisGnosis. Licensed under the Apache License, Version 2.0. */
package com.weisgnosis.agents.core.client

import com.weisgnosis.agents.core.agent.{AgentRunState, AgentRunUpdate, ReActAgent}
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.event.{AgentEvent, ModelStreamContext}
import com.weisgnosis.agents.core.json.JsonBoundary
import com.weisgnosis.agents.core.model.{ContentBlock, Msg, Role, ToolResultState}
import com.weisgnosis.agents.core.tool.{
  ToolAuthorizer,
  ToolAuthorization,
  ToolDispatchObserver,
  ToolDispatchCompletion,
  Toolkit
}
import zio.*
import zio.http.*
import zio.json.ast.Json
import zio.test.*
import zio.stream.ZStream

object GeminiLlmClientSpec extends ZIOSpecDefault:
  private val config: LlmConfig =
    LlmConfig("gemini-fixture", endpoint = "http://127.0.0.1/messages")
  private val context: ModelStreamContext = ModelStreamContext("reply", "request")

  private def text(value: String): Json = Json.Obj("text" -> Json.Str(value))
  private def code(id: Option[String]): Json = Json.Obj(
    "executableCode" -> Json.Obj(
      Chunk("language" -> Json.Str("PYTHON"), "code" -> Json.Str("print(2 + 2)")) ++
        Chunk.fromIterable(id.map(value => "id" -> Json.Str(value)))
    )
  )
  private def result(outcome: String, id: Option[String]): Json = Json.Obj(
    "codeExecutionResult" -> Json.Obj(
      Chunk(
        "outcome" -> Json.Str(outcome),
        "output"  -> Json.Str(if outcome == "OUTCOME_OK" then "4\n" else "failed\n")
      ) ++
        Chunk.fromIterable(id.map(value => "id" -> Json.Str(value)))
    )
  )

  private def frame(parts: Chunk[Json], finish: Option[String] = None): String =
    val candidate = Json.Obj(
      Chunk(
        "index"   -> Json.Num(0),
        "content" -> Json.Obj("role" -> Json.Str("model"), "parts" -> Json.Arr(parts))
      ) ++
        Chunk.fromIterable(finish.map(value => "finishReason" -> Json.Str(value)))
    )
    "data: " + JsonBoundary.render(
      Json.Obj(
        "candidates" -> Json.Arr(candidate),
        "usageMetadata" -> Json.Obj(
          "promptTokenCount"     -> Json.Num(12),
          "candidatesTokenCount" -> Json.Num(5)
        )
      )
    ) + "\n\n"

  private def response(wire: String): Response =
    Response(headers = Headers("Content-Type" -> "text/event-stream"), body = Body.fromString(wire))

  def spec: Spec[TestEnvironment & Scope, Any] = suite("GeminiLlmClient")(
    test(
      "native hosted execution completes once without local dispatch and replays exact native parts on the next turn"
    ):
      ZIO
        .foreach(Chunk("OUTCOME_OK", "OUTCOME_FAILED", "OUTCOME_DEADLINE_EXCEEDED"))(outcome =>
          ZIO
            .scoped:
              for
                requests   <- Ref.make(Chunk.empty[(Json, Boolean)])
                dispatches <- Ref.make(0)
                approvals  <- Ref.make(0)
                id = Option.when(outcome != "OUTCOME_OK")("native-code")
                signedText = Json
                  .Obj("text" -> Json.Str("before"), "thoughtSignature" -> Json.Str("c2ln"))
                originalParts = Chunk(signedText, code(id), result(outcome, id), text("after"))
                wire = frame(Chunk(signedText)) + frame(Chunk(code(id))) + frame(
                  Chunk(result(outcome, id))
                ) + frame(Chunk(text("after")), Some("STOP"))
                port <- Server.install(Routes(Method.POST / "messages" -> handler {
                  (request: Request) =>
                    request.body.asString
                      .orElseSucceed("")
                      .map(body => JsonBoundary.decode(body).getOrElse(Json.Null))
                      .flatMap(body =>
                        requests.modify(previous =>
                          (
                            previous.size,
                            previous :+ (body -> (
                              request
                                .rawHeaders("x-goog-api-key")
                                .contains("fixture-key") && request.url.queryParams
                                .getAll("alt")
                                .contains("sse")
                            ))
                          )
                        )
                      )
                      .map(index =>
                        response(
                          if index == 0 then wire else frame(Chunk(text("continued")), Some("STOP"))
                        )
                      )
                }))
                http   <- ZIO.service[Client]
                client <- GeminiLlmClient.make(http, GeminiCodeExecution.Enabled)
                observer = ToolDispatchObserver((_, _) =>
                  dispatches.update(_ + 1).as(ToolDispatchCompletion.ignore)
                )
                authorizer = ToolAuthorizer(request =>
                  approvals.update(_ + 1).as(ToolAuthorization.Approved(request.call.input))
                )
                agent <- ReActAgent
                  .builder()
                  .model(client)
                  .llmConfig(
                    config.copy(
                      endpoint = s"http://127.0.0.1:$port/messages",
                      apiKey = Some("fixture-key")
                    )
                  )
                  .toolkit(Toolkit.empty.withDispatchObserver(observer))
                  .toolAuthorizer(authorizer)
                  .build
                updates <- agent.streamWithResult(Msg.user("calculate")).runCollect
                first <- ZIO
                  .fromOption(updates.collect { case AgentRunUpdate.Result(value) =>
                    value
                  }.headOption)
                  .orElseFail(AgentError.InvalidConfiguration("Missing Gemini runtime result"))
                second     <- agent.run(Msg.user("continue"))
                sent       <- requests.get
                executed   <- dispatches.get
                authorized <- approvals.get
                models = sent
                  .drop(1)
                  .flatMap(
                    _._1.asObject
                      .flatMap(_.get("contents"))
                      .flatMap(_.asArray)
                      .getOrElse(Chunk.empty)
                  )
                  .collect {
                    case value: Json.Obj if value.get("role").contains(Json.Str("model")) => value
                  }
                observations = first.reply.toolResults
              yield assertTrue(
                first.state == AgentRunState.Completed,
                second.state == AgentRunState.Completed,
                second.reply.text == "continued",
                sent.size == 2 && sent.forall(_._2),
                sent.forall(
                  _._1.asObject.exists(
                    _.get("tools").contains(Json.Arr(Json.Obj("codeExecution" -> Json.Obj())))
                  )
                ),
                first.reply.toolUses.size == 1 && first.reply.toolUses.forall(_.isServerTool),
                observations.size == 1 && observations.forall(_.isServerTool),
                observations.forall(
                  _.state == (if outcome == "OUTCOME_OK" then ToolResultState.Success
                              else ToolResultState.Error)
                ),
                observations
                  .forall(_.outputText == (if outcome == "OUTCOME_OK" then "4\n" else "failed\n")),
                !first.history.exists(_.role == Role.Tool),
                first.reply.content.map {
                  case _: ContentBlock.Text       => "text"
                  case _: ContentBlock.ToolUse    => "code"
                  case _: ContentBlock.ToolResult => "result"
                  case _                          => "unexpected"
                } == Chunk("text", "code", "result", "text"),
                models.exists(_.get("parts").contains(Json.Arr(originalParts))),
                executed == 0 && authorized == 0,
                updates.exists {
                  case AgentRunUpdate.Event(_: AgentEvent.ToolCallEndEvent) => true; case _ => false
                },
                first.usage.exists(value => value.inputTokens == 12 && value.outputTokens == 5)
              )
            .provide(Client.default, Server.defaultWithPort(0))
        )
        .map(results => results.foldLeft(assertTrue(true))(_ && _))
    ,
    test(
      "unsupported settings, malformed pairs, truncation and changed replay fail closed without retry"
    ):
      for
        requests <- Ref.make(0)
        client <- GeminiLlmClient.make(
          HttpClientTestSupport.scripted(_ =>
            requests.update(_ + 1).as(response(frame(Chunk(text("ok")), Some("STOP"))))
          ),
          GeminiCodeExecution.Enabled
        )
        hub <- ZIO.acquireRelease(Hub.unbounded[AgentEvent])(_.shutdown)
        settings <- ZIO.foreach(
          Chunk(
            config.copy(responseFormat = Some(Json.Obj())),
            config.copy(thinkingBudget = ThinkingBudget.fromInt(1024).toOption),
            config.copy(seed = Some(1L))
          )
        )(value => client.streamTurn(Chunk(Msg.user("x")), Chunk.empty, value, hub, context).either)
        before <- requests.get
        malformed = Chunk(
          frame(Chunk(text("unfinished"))),
          frame(Chunk(code(None)), Some("STOP")),
          frame(Chunk(result("OUTCOME_OK", None)), Some("STOP")),
          frame(Chunk(code(Some("first")), result("OUTCOME_OK", Some("other"))), Some("STOP")),
          frame(Chunk(code(None), result("OUTCOME_UNSPECIFIED", None)), Some("STOP")),
          frame(Chunk(text("truncated")), Some("SAFETY")),
          frame(Chunk(Json.Obj("inlineData" -> Json.Obj())), Some("STOP")),
          frame(
            Chunk(Json.Obj("text" -> Json.Str("hidden"), "thought" -> Json.Bool(true))),
            Some("STOP")
          )
        )
        invalid <- ZIO.foreach(malformed)(wire =>
          GeminiLlmClient
            .make(
              HttpClientTestSupport.scripted(_ => ZIO.succeed(response(wire))),
              GeminiCodeExecution.Enabled
            )
            .flatMap(_.streamTurn(Chunk(Msg.user("x")), Chunk.empty, config, hub, context))
            .either
        )
        disabled <- GeminiLlmClient
          .make(
            HttpClientTestSupport.scripted(_ =>
              ZIO.succeed(
                response(frame(Chunk(code(None), result("OUTCOME_OK", None)), Some("STOP")))
              )
            )
          )
          .flatMap(_.streamTurn(Chunk(Msg.user("x")), Chunk.empty, config, hub, context))
          .either
        generated <- client.streamTurn(Chunk(Msg.user("x")), Chunk.empty, config, hub, context)
        changed = generated.message.withTextContent("changed")
        snapshot <- requests.get
        replay <- client
          .streamTurn(
            Chunk(Msg.user("x"), changed, Msg.user("next")),
            Chunk.empty,
            config,
            hub,
            context
          )
          .either
        after <- requests.get
      yield assertTrue(
        settings.forall(_.isLeft),
        before == 0,
        invalid.forall(_.isLeft),
        disabled.isLeft,
        replay.isLeft,
        snapshot == after
      )
    ,
    test("cancelled code streams retain partial hosted data and join exact failing HTTP cleanup"):
      for
        partial    <- Ref.make(Option.empty[TurnOutput.Interrupted])
        progressed <- Promise.make[Nothing, Unit]
        cleaning   <- Promise.make[Nothing, Unit]
        release    <- Promise.make[Nothing, Unit]
        defect = new IllegalStateException("Gemini request cleanup")
        client <- GeminiLlmClient.make(
          HttpClientTestSupport.scripted(_ =>
            ZIO
              .acquireRelease(ZIO.unit)(_ =>
                cleaning.succeed(()) *> release.await *> ZIO.die(defect)
              )
              .as(
                Response(
                  headers = Headers("Content-Type" -> "text/event-stream"),
                  body = Body.fromStreamChunked(
                    ZStream.fromIterable(
                      frame(Chunk(code(None))).getBytes(java.nio.charset.StandardCharsets.UTF_8)
                    ) ++ ZStream.never
                  )
                )
              )
          ),
          GeminiCodeExecution.Enabled
        )
        hub <- ZIO.acquireRelease(Hub.unbounded[AgentEvent])(_.shutdown)
        worker <- ZIO.uninterruptibleMask: restore =>
          for
            fiber <- restore(
              client.attemptTurnWithProgress(
                Chunk(Msg.user("x")),
                Chunk.empty,
                config,
                hub,
                context,
                value =>
                  partial.set(value) *> ZIO
                    .when(value.exists(_.message.toolUses.nonEmpty))(progressed.succeed(()))
                    .unit
              )
            ).forkScoped
            _ <- ZIO.addFinalizer(release.succeed(()).unit)
          yield fiber
        _          <- progressed.await
        controller <- ZIO.fiberId
        _          <- worker.interruptAsFork(controller)
        _          <- cleaning.await
        before     <- worker.poll
        _          <- release.succeed(())
        exit       <- worker.await
        captured   <- partial.get
      yield assertTrue(
        before.isEmpty,
        captured.exists(
          _.message.toolUses.exists(call =>
            call.isServerTool && call.input.asObject
              .exists(_.get("code").contains(Json.Str("print(2 + 2)")))
          )
        ),
        exit.causeOption.exists(cause =>
          cause.interruptors.contains(controller) && cause.defects == List(defect)
        )
      )
  ) @@ TestAspect.timeout(20.seconds) @@ TestAspect.withLiveClock
