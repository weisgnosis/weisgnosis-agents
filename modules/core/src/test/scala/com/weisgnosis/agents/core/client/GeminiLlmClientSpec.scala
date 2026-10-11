/* Copyright 2024-2026 WeisGnosis. Licensed under the Apache License, Version 2.0. */
package com.weisgnosis.agents.core.client

import com.weisgnosis.agents.core.agent.{AgentRunState, AgentRunUpdate, ReActAgent}
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.event.{AgentEvent, ModelStreamContext}
import com.weisgnosis.agents.core.json.JsonBoundary
import com.weisgnosis.agents.core.model.{ContentBlock, Msg, Role, ToolResultState}
import com.weisgnosis.agents.core.tool.{
  Tool,
  ToolDef,
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
  private def function(id: Option[String], query: String, signature: Boolean = false): Json =
    Json.Obj(
      Chunk(
        "functionCall" -> Json.Obj(
          Chunk("name" -> Json.Str("lookup"), "args" -> Json.Obj("q" -> Json.Str(query))) ++ Chunk
            .fromIterable(id.map(value => "id" -> Json.Str(value)))
        )
      ) ++ Chunk.fromIterable(Option.when(signature)("thoughtSignature" -> Json.Str("c2ln")))
    )

  private def contents(body: Json): Chunk[Json] =
    body.asObject.flatMap(_.get("contents")).flatMap(_.asArray).getOrElse(Chunk.empty)

  private def arrayField(value: Json, name: String): Chunk[Json] =
    value.asObject.flatMap(_.get(name)).flatMap(_.asArray).getOrElse(Chunk.empty)
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
      "local parallel calls continue with exact signatures, occurrence identities and error observations"
    ):
      ZIO
        .foreach(Chunk(true, false))(providerIds =>
          ZIO
            .scoped:
              for
                requests <- Ref.make(Chunk.empty[Json])
                invoked  <- Ref.make(Chunk.empty[String])
                firstParts = Chunk(
                  Json.Obj("text" -> Json.Str("plan"), "thought" -> Json.Bool(true)),
                  function(Option.when(providerIds)("one"), "ok", true),
                  function(Option.when(providerIds)("two"), "fail")
                )
                repeatedParts = Chunk(function(Option.when(providerIds)("one"), "again", true))
                port <- Server.install(Routes(Method.POST / "messages" -> handler {
                  (request: Request) =>
                    request.body.asString
                      .orElseSucceed("")
                      .map(body => JsonBoundary.decode(body).getOrElse(Json.Null))
                      .flatMap(body =>
                        requests.modify(previous => previous.size -> (previous :+ body))
                      )
                      .map(index =>
                        response(
                          if index == 0 then frame(firstParts, Some("STOP"))
                          else if index == 1 then frame(repeatedParts, Some("STOP"))
                          else frame(Chunk(text("done")), Some("STOP"))
                        )
                      )
                }))
                http   <- ZIO.service[Client]
                client <- GeminiLlmClient.make(http)
                lookup = Tool.make(
                  ToolDef("lookup", "Look up q", Json.Obj("type" -> Json.Str("object")))
                )(input =>
                  val query = input.asObject.flatMap(_.get("q")).flatMap(_.asString).getOrElse("")
                  invoked
                    .update(_ :+ query) *> (if query == "fail" then
                                              ZIO.fail(AgentError.InvalidMessage("fixture failure"))
                                            else ZIO.succeed(s"found $query"))
                )
                agent <- ReActAgent.make(
                  client = client,
                  toolkit = Toolkit.single(lookup),
                  llmConfig = config.copy(endpoint = s"http://127.0.0.1:$port/messages")
                )
                result <- agent.run(Msg.user("look up"))
                sent   <- requests.get
                calls  <- invoked.get
                historyCalls  = result.history.flatMap(_.toolUses)
                observations  = result.history.filter(_.role == Role.Tool).flatMap(_.toolResults)
                continuations = sent.drop(1).flatMap(contents)
                nativeModels = continuations.collect {
                  case value: Json.Obj if value.get("role").contains(Json.Str("model")) =>
                    value.get("parts")
                }
                responseGroups = continuations
                  .flatMap(arrayField(_, "parts"))
                  .flatMap(_.asObject.flatMap(_.get("functionResponse")).flatMap(_.asObject))
                firstResponseGroups = sent
                  .lift(1)
                  .toList
                  .flatMap(contents)
                  .flatMap(arrayField(_, "parts"))
                  .filter(_.asObject.exists(_.get("functionResponse").nonEmpty))
                declarations = sent
                  .flatMap(arrayField(_, "tools"))
                  .flatMap(arrayField(_, "functionDeclarations"))
              yield assertTrue(
                result.state == AgentRunState.Completed && result.reply.text == "done",
                sent.size == 3,
                firstResponseGroups.size == 2,
                sent
                  .lift(1)
                  .exists(body =>
                    contents(body).count(
                      _.asObject
                        .flatMap(_.get("parts"))
                        .flatMap(_.asArray)
                        .exists(parts =>
                          parts.size == 2 && parts
                            .forall(_.asObject.exists(_.get("functionResponse").nonEmpty))
                        )
                    ) == 1
                  ),
                calls.toSet == Set("ok", "fail", "again") && calls.size == 3,
                observations.size == 3 && observations.count(_.state == ToolResultState.Error) == 1,
                historyCalls.size == 3 && historyCalls.forall(call => !call.isServerTool),
                historyCalls.take(2).map(_.id).distinct.size == 2,
                if providerIds then historyCalls.map(_.id) == Chunk("one", "two", "one")
                else historyCalls.map(_.id).distinct.size == 3,
                nativeModels.contains(Some(Json.Arr(firstParts))) && nativeModels
                  .contains(Some(Json.Arr(repeatedParts))),
                responseGroups.forall(_.get("name").contains(Json.Str("lookup"))),
                responseGroups
                  .exists(_.get("response").flatMap(_.asObject).exists(_.get("error").nonEmpty)),
                if providerIds then responseGroups.forall(_.get("id").nonEmpty)
                else responseGroups.forall(_.get("id").isEmpty),
                declarations.size == sent.size && declarations.forall(
                  _.asObject.exists(
                    _.get("parametersJsonSchema").contains(Json.Obj("type" -> Json.Str("object")))
                  )
                )
              )
            .provide(Client.default, Server.defaultWithPort(0))
        )
        .map(_.foldLeft(assertTrue(true))(_ && _))
    ,
    test(
      "local cancellation joins owned cleanup, reconciles once and resumes through native error response"
    ):
      for
        requests <- Ref.make(Chunk.empty[Json])
        started  <- Promise.make[Nothing, Unit]
        cleaning <- Promise.make[Nothing, Unit]
        release  <- Promise.make[Nothing, Unit]
        client <- GeminiLlmClient.make(
          HttpClientTestSupport.scripted(request =>
            request.body.asString
              .orElseSucceed("")
              .map(body => JsonBoundary.decode(body).getOrElse(Json.Null))
              .flatMap(body => requests.modify(previous => previous.size -> (previous :+ body)))
              .map(index =>
                response(
                  if index == 0 then
                    frame(Chunk(function(Some("cancel"), "wait", true)), Some("STOP"))
                  else frame(Chunk(text("resumed")), Some("STOP"))
                )
              )
          )
        )
        lookup = Tool.make(ToolDef("lookup", "Lookup", Json.Obj("type" -> Json.Str("object"))))(_ =>
          ZIO.scoped(
            ZIO.acquireRelease(started.succeed(()))(_ =>
              cleaning.succeed(()) *> release.await
            ) *> ZIO.never
          )
        )
        agent <- ReActAgent.make(
          client = client,
          toolkit = Toolkit.single(lookup),
          llmConfig = config
        )
        _           <- ZIO.addFinalizer(release.succeed(()).unit)
        fiber       <- agent.run(Msg.user("wait")).forkScoped
        _           <- started.await
        persisted   <- agent.history
        _           <- agent.interrupt
        _           <- cleaning.await
        before      <- fiber.poll
        _           <- release.succeed(())
        interrupted <- fiber.join
        recovered   <- agent.history
        resumed     <- agent.run(Msg.user("continue"))
        sent        <- requests.get
        responses = sent
          .drop(1)
          .flatMap(contents)
          .flatMap(arrayField(_, "parts"))
          .flatMap(_.asObject.flatMap(_.get("functionResponse")).flatMap(_.asObject))
      yield assertTrue(
        persisted.flatMap(_.toolUses).map(_.id) == Chunk("cancel") && persisted
          .flatMap(_.toolResults)
          .isEmpty,
        before.isEmpty,
        interrupted.isInterrupted && interrupted.reply.generateReason.contains("interrupted"),
        recovered
          .flatMap(_.toolResults)
          .size == 1 && recovered.flatMap(_.toolResults).forall(_.state == ToolResultState.Error),
        resumed.state == AgentRunState.Completed && resumed.reply.text == "resumed",
        sent.size == 2,
        responses.size == 1 && responses.forall(value =>
          value
            .get("id")
            .contains(Json.Str("cancel")) && value.get("name").contains(Json.Str("lookup")) && value
            .get("response")
            .flatMap(_.asObject)
            .exists(_.get("error").nonEmpty)
        )
      )
    ,
    test(
      "malformed local calls and inconsistent continuation fail closed; truncation cannot dispatch"
    ):
      for
        requests <- Ref.make(0)
        hub      <- ZIO.acquireRelease(Hub.unbounded[AgentEvent])(_.shutdown)
        declaration = ToolDef("lookup", "Lookup", Json.Obj("type" -> Json.Str("object")))
        boundaryRequests <- Ref.make(0)
        boundaryName = "f" * 64
        boundaryClient <- GeminiLlmClient.make(
          HttpClientTestSupport.scripted(_ =>
            boundaryRequests
              .update(_ + 1)
              .as(
                response(
                  frame(
                    Chunk(Json.Obj("functionCall" -> Json.Obj("name" -> Json.Str(boundaryName)))),
                    Some("STOP")
                  )
                )
              )
          )
        )
        acceptedBoundary <- boundaryClient.streamTurn(
          Chunk(Msg.user("x")),
          Chunk(declaration.copy(name = boundaryName)),
          config,
          hub,
          context
        )
        oversizedDeclaration <- boundaryClient
          .streamTurn(
            Chunk(Msg.user("x")),
            Chunk(declaration.copy(name = boundaryName + "f")),
            config,
            hub,
            context
          )
          .either
        boundaryCalls <- boundaryRequests.get
        client <- GeminiLlmClient.make(
          HttpClientTestSupport.scripted(_ =>
            requests
              .update(_ + 1)
              .as(response(frame(Chunk(function(Some("one"), "ok", true)), Some("STOP"))))
          )
        )
        original <- client.streamTurn(
          Chunk(Msg.user("x")),
          Chunk(declaration),
          config,
          hub,
          context
        )
        invalidHistory = Chunk(
          Chunk(Msg.user("x"), original.message),
          Chunk(
            Msg.user("x"),
            original.message,
            Msg.tool(content = Chunk(ContentBlock.ToolResult.text("other", "lookup", "x")))
          ),
          Chunk(
            Msg.user("x"),
            original.message,
            Msg.tool(content = Chunk(ContentBlock.ToolResult.text("one", "other", "x")))
          ),
          Chunk(
            Msg.user("x"),
            original.message,
            Msg.tool(content =
              Chunk(
                ContentBlock.ToolResult.text("one", "lookup", "x"),
                ContentBlock.ToolResult.text("one", "lookup", "duplicate")
              )
            )
          ),
          Chunk(
            Msg.user("x"),
            original.message.withTextContent("changed"),
            Msg.tool(content = Chunk(ContentBlock.ToolResult.text("one", "lookup", "x")))
          )
        )
        before <- requests.get
        histories <- ZIO.foreach(invalidHistory)(history =>
          client.streamTurn(history, Chunk(declaration), config, hub, context).either
        )
        after <- requests.get
        unsupported <- client
          .streamTurn(
            Chunk(Msg.user("x")),
            Chunk(declaration),
            config.copy(parallelToolCalls = Some(ToolCallParallelism.Disabled)),
            hub,
            context
          )
          .either
        malformed = Chunk(
          Chunk(function(Some("same"), "x"), function(Some("same"), "y")),
          Chunk(
            Json.Obj(
              "functionCall" -> Json.Obj("name" -> Json.Str("lookup"), "args" -> Json.Str("bad"))
            )
          ),
          Chunk(Json.Obj("functionCall" -> Json.Obj("name" -> Json.Str("")))),
          Chunk(Json.Obj("functionCall" -> Json.Obj("name" -> Json.Str("invalid name")))),
          Chunk(Json.Obj("functionCall" -> Json.Obj("name" -> Json.Str(boundaryName + "f")))),
          Chunk(
            Json.Obj("functionCall" -> Json.Obj("name" -> Json.Str("lookup"), "id" -> Json.Str("")))
          )
        )
        rejected <- ZIO.foreach(malformed)(parts =>
          GeminiLlmClient
            .make(
              HttpClientTestSupport.scripted(_ => ZIO.succeed(response(frame(parts, Some("STOP")))))
            )
            .flatMap(_.streamTurn(Chunk(Msg.user("x")), Chunk(declaration), config, hub, context))
            .either
        )
        dispatches <- Ref.make(0)
        exhausted <- GeminiLlmClient.make(
          HttpClientTestSupport.scripted(_ =>
            ZIO.succeed(
              response(
                frame(Chunk(text("partial"), function(Some("x"), "x", true)), Some("MAX_TOKENS"))
              )
            )
          )
        )
        lookup = Tool.make(declaration)(_ => dispatches.update(_ + 1).as("unreachable"))
        agent <- ReActAgent.make(
          client = exhausted,
          toolkit = Toolkit.single(lookup),
          llmConfig = config
        )
        truncated <- agent.run(Msg.user("x"))
        count     <- dispatches.get
        hosted <- GeminiLlmClient.make(
          HttpClientTestSupport.scripted(_ =>
            requests.update(_ + 1).as(response(frame(Chunk(text("bad")), Some("STOP"))))
          ),
          GeminiCodeExecution.Enabled
        )
        combined <- hosted
          .streamTurn(Chunk(Msg.user("x")), Chunk(declaration), config, hub, context)
          .either
        finalRequests <- requests.get
      yield assertTrue(
        acceptedBoundary.message.toolUses.map(_.name) == Chunk(boundaryName),
        oversizedDeclaration.isLeft && boundaryCalls == 1,
        histories.forall(_.isLeft),
        before == after,
        unsupported.isLeft,
        rejected.forall(_.isLeft),
        count == 0,
        truncated.reply.toolUses.isEmpty,
        truncated.reply.text == "partial",
        combined.isLeft,
        finalRequests == after
      )
    ,
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
            Chunk(Json.Obj("text" -> Json.Str("hidden"), "thought" -> Json.Str("invalid"))),
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
    test("native acquisition and body failures expose only bounded transport categories"):
      ZIO.scoped:
        val secret = "https://private.example/?api_key=private-token"
        val cases: Chunk[(Throwable, String)] = Chunk(
          (new java.net.UnknownHostException(secret), "dns"),
          (new javax.net.ssl.SSLHandshakeException(secret), "tls"),
          (new java.net.ConnectException(secret), "connection"),
          (new java.net.SocketTimeoutException(secret), "timeout"),
          (new java.io.IOException(secret), "other")
        )
        for
          hub <- ZIO.acquireRelease(Hub.unbounded[AgentEvent])(_.shutdown)
          acquired <- ZIO.foreach(cases): (failure, category) =>
            for
              client <- GeminiLlmClient.make(HttpClientTestSupport.scripted(_ => ZIO.fail(failure)))
              result <- client
                .streamTurn(Chunk(Msg.user("x")), Chunk.empty, config, hub, context)
                .either
            yield assertTrue(
              result == Left(
                AgentError.ModelError(0, s"Gemini HTTP acquisition failed ($category)")
              )
            )
          client <- GeminiLlmClient.make(
            HttpClientTestSupport.scripted(_ =>
              ZIO.succeed(
                Response(
                  headers = Headers("Content-Type" -> "text/event-stream"),
                  body = Body.fromStreamChunked(
                    ZStream.fail(new java.net.SocketTimeoutException(secret))
                  )
                )
              )
            )
          )
          body <- client.streamTurn(Chunk(Msg.user("x")), Chunk.empty, config, hub, context).either
        yield acquired.foldLeft(
          assertTrue(
            body == Left(AgentError.ModelError(200, "Failed to read Gemini UTF-8 stream (timeout)"))
          )
        )(_ && _)
    ,
    test("completed native response cleanup cancellation preserves proposals without execution"):
      ZIO.scoped:
        for
          cleaning    <- Promise.make[Nothing, Unit]
          interrupted <- Promise.make[Nothing, Unit]
          release     <- Promise.make[Nothing, Unit]
          closed      <- Ref.make(false)
          calls       <- Ref.make(0)
          client <- GeminiLlmClient.make(
            HttpClientTestSupport.scripted(_ =>
              ZIO
                .addFinalizer(
                  cleaning.succeed(()) *> ZIO.interruptible(ZIO.never).exit *>
                    interrupted.succeed(()) *> release.await *> closed.set(true)
                )
                .as(
                  response(
                    frame(Chunk(text("received"), function(Some("call-1"), "x")), Some("STOP"))
                  )
                )
            )
          )
          tool = Tool.make(ToolDef("lookup", "Count execution", Json.Obj()))(_ =>
            calls.update(_ + 1).as("unexpected")
          )
          agent <- ReActAgent.make(
            client = client,
            llmConfig = config,
            toolkit = Toolkit.single(tool)
          )
          worker <- ZIO.uninterruptibleMask: restore =>
            for
              running <- restore(agent.run(Msg.user("lookup"))).forkScoped
              _       <- ZIO.addFinalizer(release.succeed(()).unit)
            yield running
          _        <- cleaning.await
          _        <- worker.interruptFork
          _        <- interrupted.await
          _        <- release.succeed(())
          exit     <- worker.await
          history  <- agent.history
          count    <- calls.get
          released <- closed.get
        yield assertTrue(
          exit.isInterrupted,
          released,
          count == 0,
          history.exists(_.text.contains("received")),
          history.flatMap(_.toolUses).map(_.id) == Chunk("call-1"),
          history.flatMap(_.toolResults).map(_.state) == Chunk(
            com.weisgnosis.agents.core.model.ToolResultState.Error
          ),
          history.count(_.generateReason.contains("interrupted")) == 1
        )
    ,
    test("cancelled code streams retain partial hosted data and join exact failing HTTP cleanup"):
      for
        partial  <- Ref.make(Option.empty[TurnOutput.Interrupted])
        cleaning <- Promise.make[Nothing, Unit]
        release  <- Promise.make[Nothing, Unit]
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
        hub    <- ZIO.acquireRelease(Hub.unbounded[AgentEvent])(_.shutdown)
        events <- hub.subscribe
        worker <- ZIO.uninterruptibleMask: restore =>
          for
            fiber <- restore(
              client.attemptTurnWithProgress(
                Chunk(Msg.user("x")),
                Chunk.empty,
                config,
                hub,
                context,
                partial.set
              )
            ).forkScoped
            _ <- ZIO.addFinalizer(release.succeed(()).unit)
          yield fiber
        _ <- events.take.repeatUntil:
          case event: AgentEvent.ModelResponseChunkEvent => event.chunk.contains("executableCode")
          case _                                         => false
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
