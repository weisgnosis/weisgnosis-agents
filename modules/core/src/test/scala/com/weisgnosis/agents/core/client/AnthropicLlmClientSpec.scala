/* Copyright 2024-2026 WeisGnosis. Licensed under the Apache License, Version 2.0. */
package com.weisgnosis.agents.core.client

import com.weisgnosis.agents.core.agent.{AgentConfig, AgentRunState, ReActAgent}
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.event.{AgentEvent, ModelStreamContext}
import com.weisgnosis.agents.core.model.{ContentBlock, Msg}
import com.weisgnosis.agents.core.tool.{Tool, ToolDef, Toolkit}
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.stream.ZStream
import zio.test.*

object AnthropicLlmClientSpec extends ZIOSpecDefault:
  private enum SearchScenario:
    case Hosted, HostedError, Mixed, MixedError

  private val config =
    LlmConfig("native-fixture", endpoint = "http://127.0.0.1/messages", maxTokens = Some(2048))
  private val start = frame(
    "message_start",
    """{"message":{"role":"assistant","content":[],"usage":{"input_tokens":12,"output_tokens":1,"cache_read_input_tokens":3}}} """
  )
  private val stop      = frame("message_stop", "{}")
  private val finalText = start + textBlock(0, "answer") + finish("end_turn") + stop
  private val localTurn = start + textBlock(0, "before") +
    frame(
      "content_block_start",
      """{"index":1,"content_block":{"type":"thinking","thinking":""}}"""
    ) +
    frame(
      "content_block_delta",
      """{"index":1,"delta":{"type":"thinking_delta","thinking":"reason"}}"""
    ) +
    frame(
      "content_block_delta",
      """{"index":1,"delta":{"type":"signature_delta","signature":"sig"}}"""
    ) +
    frame(
      "content_block_delta",
      """{"index":1,"delta":{"type":"signature_delta","signature":"nature"}}"""
    ) +
    blockStop(1) + toolStart(2) +
    frame(
      "content_block_delta",
      """{"index":2,"delta":{"type":"input_json_delta","partial_json":"{\"q\":"}}"""
    ) +
    frame(
      "content_block_delta",
      """{"index":2,"delta":{"type":"input_json_delta","partial_json":"\"x\"}"}}"""
    ) +
    blockStop(2) + textBlock(3, "after") + finish("tool_use") + stop

  def spec: Spec[TestEnvironment & Scope, Any] = suite("AnthropicLlmClient")(
    test(
      "native HTTP tool continuation preserves indexed thinking, signatures, result roles and usage"
    ):
      ZIO
        .scoped:
          for
            requests <- Ref.make(Chunk.empty[(String, Boolean)])
            invoked  <- Ref.make(Chunk.empty[Json])
            port <- Server.install(Routes(Method.POST / "messages" -> handler { (request: Request) =>
              request.body.asString
                .orElseSucceed("")
                .flatMap(body =>
                  requests.modify(previous =>
                    (
                      previous.size,
                      previous :+ (
                        body,
                        request.rawHeaders("x-api-key").contains("fixture-key") &&
                          request.rawHeaders("anthropic-version").contains("2023-06-01") &&
                          request.rawHeaders("Authorization").isEmpty
                      )
                    )
                  )
                )
                .map(index => response(if index == 0 then localTurn else finalText))
            }))
            http   <- ZIO.service[Client]
            client <- AnthropicLlmClient.make(http)
            lookup = Tool.make(
              ToolDef("lookup", "Look up q", Json.Obj("type" -> Json.Str("object")))
            )(input => invoked.update(_ :+ input).as("observation"))
            agent <- ReActAgent.make(
              config = AgentConfig(maxIterations = 3),
              client = client,
              toolkit = Toolkit.single(lookup),
              llmConfig = config
                .copy(endpoint = s"http://127.0.0.1:$port/messages", apiKey = Some("fixture-key"))
            )
            result <- agent.run(Msg.user("lookup x"))
            sent   <- requests.get
            calls  <- invoked.get
            parsed <- ZIO.foreach(sent)(entry =>
              ZIO
                .fromEither(entry._1.fromJson[Json.Obj])
                .mapError(AgentError.DeserializationError(_))
            )
            continuation = parsed
              .drop(1)
              .flatMap(_.get("messages").flatMap(_.asArray).getOrElse(Chunk.empty))
            assistant = continuation.collect {
              case obj: Json.Obj if obj.get("role").contains(Json.Str("assistant")) => obj
            }
            blocks = assistant.flatMap(_.get("content").flatMap(_.asArray).getOrElse(Chunk.empty))
            nativeTypes = blocks.collect { case obj: Json.Obj => obj.get("type") }
            toolResult = continuation
              .collect { case obj: Json.Obj if obj.get("role").contains(Json.Str("user")) => obj }
              .flatMap(_.get("content").flatMap(_.asArray).getOrElse(Chunk.empty))
              .collect {
                case obj: Json.Obj if obj.get("type").contains(Json.Str("tool_result")) => obj
              }
            historyCall = result.history.find(_.toolUses.nonEmpty)
          yield assertTrue(
            result.state == AgentRunState.Completed,
            result.reply.text == "answer",
            sent.size == 2,
            sent.forall(_._2),
            calls == Chunk(Json.Obj("q" -> Json.Str("x"))),
            nativeTypes == Chunk("text", "thinking", "tool_use", "text")
              .map(value => Some(Json.Str(value))),
            blocks.exists {
              case obj: Json.Obj =>
                obj.get("signature").contains(Json.Str("signature")) && obj
                  .get("thinking")
                  .contains(Json.Str("reason"))
              case _ => false
            },
            toolResult.exists(_.get("tool_use_id").contains(Json.Str("call-1"))),
            toolResult.exists(
              _.get("content").contains(
                Json.Arr(Json.Obj("type" -> Json.Str("text"), "text" -> Json.Str("observation")))
              )
            ),
            historyCall.exists(_.content.map {
              case _: ContentBlock.Text     => "text"
              case _: ContentBlock.Thinking => "thinking"
              case _: ContentBlock.ToolUse  => "tool_use"
              case _                        => "unexpected"
            } == Chunk("text", "thinking", "tool_use", "text")),
            result.usage.exists(value =>
              value.inputTokens == 24 && value.outputTokens == 14 && value.cachedTokens == 6
            )
          )
        .provide(Client.default, Server.defaultWithPort(0))
    ,
    test("native protocol errors and unsupported requests fail without tool dispatch or retry"):
      val malformed = Chunk(
        start + textBlock(0, "answer") + finish(
          "end_turn"
        ) + "data: {\"type\":\"message_stop\"}\n\n",
        start + textBlock(0, "partial"),
        start + toolStart(1),
        start + toolStart(0) + frame(
          "content_block_delta",
          """{"index":0,"delta":{"type":"input_json_delta","partial_json":"{"}}"""
        ) + blockStop(0),
        start + textBlock(0, "truncated") + finish("max_tokens") + stop,
        start + frame(
          "content_block_start",
          """{"index":0,"content_block":{"type":"redacted_thinking","data":"opaque"}}"""
        ),
        start + frame(
          "content_block_start",
          """{"index":0,"content_block":{"type":"server_tool_use","id":"remote","name":"search","input":{}}}"""
        ),
        start + frame("error", """{"error":{"type":"overloaded_error","message":"busy"}}"""),
        start + textBlock(0, "x") + finish("tool_use") + stop,
        start + frame(
          "content_block_delta",
          """{"index":0,"delta":{"type":"text_delta","text":"orphan"}}"""
        )
      )
      for
        attempted <- Ref.make(0)
        client <- AnthropicLlmClient.make(
          HttpClientTestSupport.scripted(_ => attempted.update(_ + 1).as(response(finalText)))
        )
        hub <- ZIO.acquireRelease(Hub.unbounded[AgentEvent])(_.shutdown)
        unsupported <- ZIO.foreach(
          Chunk(
            config.copy(responseFormat = Some(Json.Obj())),
            config.copy(seed = Some(1L)),
            config.copy(frequencyPenalty = Some(0.1)),
            config.copy(reasoningEffort = Some(ReasoningEffort.Low))
          )
        )(settings =>
          client.streamTurn(Chunk(Msg.user("x")), Chunk.empty, settings, hub, context).either
        )
        before <- attempted.get
        invalid <- ZIO.foreach(malformed)(wire =>
          AnthropicLlmClient
            .make(HttpClientTestSupport.scripted(_ => ZIO.succeed(response(wire))))
            .flatMap(_.streamTurn(Chunk(Msg.user("x")), Chunk.empty, config, hub, context))
            .either
        )
        search <- ZIO.fromEither(AnthropicWebSearch.make())
        incompleteHosted = start + frame(
          "content_block_start",
          """{"index":0,"content_block":{"type":"server_tool_use","id":"remote","name":"web_search","input":{"query":"example"}}}"""
        ) + blockStop(0)
        invalidHosted <- ZIO.foreach(
          Chunk(
            incompleteHosted + finish("tool_use") + stop,
            incompleteHosted + toolStart(1) + blockStop(1) + finish("tool_use") + stop,
            searchTurn(mixed = false, failed = false)
              .replace(finish("end_turn"), finish("tool_use"))
          )
        )(wire =>
          AnthropicLlmClient
            .make(
              HttpClientTestSupport.scripted(_ => ZIO.succeed(response(wire))),
              webSearch = Some(search)
            )
            .flatMap(_.streamTurn(Chunk(Msg.user("x")), Chunk.empty, config, hub, context))
            .either
        )
        rejection <- AnthropicLlmClient
          .make(
            HttpClientTestSupport.scripted(_ =>
              ZIO.succeed(
                Response(status = Status.ServiceUnavailable, body = Body.fromString("unavailable"))
              )
            )
          )
          .flatMap(_.attemptTurn(Chunk(Msg.user("x")), Chunk.empty, config, hub, context))
          .exit
        initialSignature <- AnthropicLlmClient
          .make(
            HttpClientTestSupport.scripted(_ =>
              ZIO.succeed(
                response(
                  start + frame(
                    "content_block_start",
                    """{"index":0,"content_block":{"type":"thinking","thinking":"reason","signature":"initial-signature"}}"""
                  ) +
                    blockStop(0) + textBlock(1, "answer") + finish("end_turn") + stop
                )
              )
            )
          )
          .flatMap(_.streamTurn(Chunk(Msg.user("x")), Chunk.empty, config, hub, context))
          .either
      yield assertTrue(
        unsupported.forall(_.left.exists(_.isInstanceOf[AgentError.InvalidConfiguration])),
        before == 0,
        invalid.forall(_.isLeft),
        invalidHosted.forall(_.isLeft),
        rejection.causeOption.exists(_.failures.exists(_.isInstanceOf[AgentError.ModelError])),
        initialSignature.exists(_.message.content.exists {
          case value: ContentBlock.Thinking => value.signature.contains("initial-signature")
          case _                            => false
        })
      )
    ,
    test(
      "explicit native search completes inline and mixed local work replays exact citations and results"
    ):
      ZIO
        .foreach(
          Chunk(
            SearchScenario.Hosted,
            SearchScenario.HostedError,
            SearchScenario.Mixed,
            SearchScenario.MixedError
          )
        )(scenario =>
          ZIO.scoped(searchCase(scenario)).provide(Client.default, Server.defaultWithPort(0))
        )
        .map(_.foldLeft(assertTrue(true))(_ && _))
    ,
    test("cancelled native reads retain sanitized progress and join failed request cleanup"):
      val cleanupDefect = new IllegalStateException("native request cleanup")
      ZIO.scoped:
        for
          partial        <- Ref.make(Option.empty[TurnOutput.Interrupted])
          progressed     <- Promise.make[Nothing, Unit]
          cleanupEntered <- Promise.make[Nothing, Unit]
          releaseCleanup <- Promise.make[Nothing, Unit]
          requestCount   <- Ref.make(0)
          client <- AnthropicLlmClient.make(
            HttpClientTestSupport.scripted(_ =>
              ZIO
                .acquireRelease(requestCount.update(_ + 1))(_ =>
                  cleanupEntered.succeed(()) *> releaseCleanup.await *> ZIO.die(cleanupDefect)
                )
                .as(
                  Response(
                    headers = Headers("Content-Type" -> "text/event-stream"),
                    body = Body.fromStreamChunked(
                      ZStream.fromIterable(
                        (start + toolStart(0) + frame(
                          "content_block_delta",
                          """{"index":0,"delta":{"type":"input_json_delta","partial_json":"{\"q\":"}}"""
                        )).getBytes(java.nio.charset.StandardCharsets.UTF_8)
                      ) ++ ZStream.never
                    )
                  )
                )
            )
          )
          hub <- ZIO.acquireRelease(Hub.unbounded[AgentEvent])(_.shutdown)
          worker <- ZIO.uninterruptibleMask: restore =>
            for
              running <- restore(
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
              _ <- ZIO.addFinalizer(releaseCleanup.succeed(()).unit)
            yield running
          _          <- progressed.await
          controller <- ZIO.fiberId
          _          <- worker.interruptAsFork(controller)
          _          <- cleanupEntered.await
          before     <- worker.poll
          _          <- releaseCleanup.succeed(())
          exit       <- worker.await
          captured   <- partial.get
          calls      <- requestCount.get
        yield assertTrue(
          before.isEmpty,
          calls == 1,
          captured.exists(_.message.toolUses.exists(_.input == Json.Obj())),
          exit.causeOption.exists(cause =>
            cause.interruptors.contains(controller) && cause.defects == List(cleanupDefect)
          )
        )
  ) @@ TestAspect.timeout(20.seconds) @@ TestAspect.withLiveClock

  private val context: ModelStreamContext = ModelStreamContext("reply", "request")

  private def searchCase(
      scenario: SearchScenario
  ): ZIO[Client & Server & Scope, AgentError | Throwable, TestResult] =
    val mixed  = scenario == SearchScenario.Mixed || scenario == SearchScenario.MixedError
    val failed = scenario == SearchScenario.HostedError || scenario == SearchScenario.MixedError
    for
      requests   <- Ref.make(Chunk.empty[Json.Obj])
      dispatched <- Ref.make(0)
      native = searchTurn(mixed, failed)
      port <- Server.install(Routes(Method.POST / "messages" -> handler { (request: Request) =>
        request.body.asString
          .orElseSucceed("")
          .flatMap(body =>
            ZIO
              .fromEither(body.fromJson[Json.Obj])
              .orElseSucceed(Json.Obj())
              .flatMap(parsed => requests.modify(previous => (previous.size, previous :+ parsed)))
          )
          .map(index => response(if index == 0 then native else finalText))
      }))
      selected <- ZIO.fromEither(AnthropicWebSearch.make(2))
      http     <- ZIO.service[Client]
      client   <- AnthropicLlmClient.make(http, webSearch = Some(selected))
      lookup = Tool.make(ToolDef("lookup", "Local lookup", Json.Obj("type" -> Json.Str("object"))))(
        _ => dispatched.update(_ + 1).as("local observation")
      )
      agent <- ReActAgent.make(
        client = client,
        toolkit = Toolkit.single(lookup),
        llmConfig = config.copy(endpoint = s"http://127.0.0.1:$port/messages")
      )
      updates <- agent.streamWithResult(Msg.user("search, then optionally look up")).runCollect
      result <- ZIO
        .fromOption(updates.collect {
          case com.weisgnosis.agents.core.agent.AgentRunUpdate.Result(value) => value
        }.lastOption)
        .orElseFail(AgentError.InvalidMessage("Search fixture returned no result"))
      bodies <- requests.get
      count  <- dispatched.get
      assistant     = result.history.find(_.toolResults.exists(_.isServerTool))
      inline        = assistant.toList.flatMap(_.toolResults).filter(_.isServerTool)
      nativeContent = assistant.flatMap(_.metadata.get(AnthropicNativeReplay.key))
      replay = bodies
        .drop(1)
        .flatMap(_.get("messages").flatMap(_.asArray).getOrElse(Chunk.empty))
        .collect {
          case obj: Json.Obj if obj.get("role").contains(Json.Str("assistant")) =>
            obj.get("content")
        }
      events = updates.collect {
        case com.weisgnosis.agents.core.agent.AgentRunUpdate.Event(value) => value
      }
      toolMessages = result.history.filter(_.role == com.weisgnosis.agents.core.model.Role.Tool)
      altered <- assistant match
        case None => ZIO.succeed(Right(Json.Obj()): Either[AgentError, Json])
        case Some(value) =>
          ZIO.succeed(
            AnthropicMessagesRequest.encode(
              Chunk(value.withMetadata(AnthropicNativeReplay.key, Json.Arr())),
              Chunk.empty,
              config,
              Some(selected)
            )
          )
    yield assertTrue(
      result.state == AgentRunState.Completed,
      bodies.size == (if mixed then 2 else 1),
      count == (if mixed then 1 else 0),
      inline.size == 1,
      inline.forall(
        _.state == (if failed then com.weisgnosis.agents.core.model.ToolResultState.Error
                    else com.weisgnosis.agents.core.model.ToolResultState.Success)
      ),
      assistant.exists(_.toolUses.filter(_.name == "web_search").forall(_.isServerTool)),
      events.collect {
        case event: AgentEvent.ToolResultEndEvent if event.name == "web_search" => event
      }.size == 1,
      toolMessages.flatMap(_.toolResults).forall(_.name.contains("lookup")),
      mixed || toolMessages.isEmpty,
      !mixed || replay.contains(nativeContent),
      bodies.forall(_.get("tools").flatMap(_.asArray).exists(_.contains(selected.declaration))),
      nativeContent.exists(_.asArray.exists(_.exists {
        case obj: Json.Obj => obj.get("citations").exists(_.asArray.exists(_.nonEmpty))
        case _             => false
      })),
      altered.isLeft
    )

  private def searchTurn(mixed: Boolean, failed: Boolean): String =
    val content: Json =
      if failed then
        Json.Obj(
          "type"       -> Json.Str("web_search_tool_result_error"),
          "error_code" -> Json.Str("unavailable")
        )
      else
        Json.Arr(
          Json.Obj(
            "type"              -> Json.Str("web_search_result"),
            "url"               -> Json.Str("https://example.com"),
            "title"             -> Json.Str("Result"),
            "encrypted_content" -> Json.Str("opaque-exact-content")
          )
        )
    start + frame(
      "content_block_start",
      Json
        .Obj(
          "index" -> Json.Num(0),
          "content_block" -> Json.Obj(
            "type"  -> Json.Str("server_tool_use"),
            "id"    -> Json.Str("server-1"),
            "name"  -> Json.Str("web_search"),
            "input" -> Json.Obj("query" -> Json.Str("example"))
          )
        )
        .toJson
    ) + blockStop(0) + frame(
      "content_block_start",
      Json
        .Obj(
          "index" -> Json.Num(1),
          "content_block" -> Json.Obj(
            "type"        -> Json.Str("web_search_tool_result"),
            "tool_use_id" -> Json.Str("server-1"),
            "content"     -> content
          )
        )
        .toJson
    ) + blockStop(1) + frame(
      "content_block_start",
      Json
        .Obj(
          "index" -> Json.Num(2),
          "content_block" -> Json
            .Obj("type" -> Json.Str("text"), "text" -> Json.Str("cited answer"))
        )
        .toJson
    ) +
      frame(
        "content_block_delta",
        Json
          .Obj(
            "index" -> Json.Num(2),
            "delta" -> Json.Obj(
              "type" -> Json.Str("citations_delta"),
              "citation" -> Json.Obj(
                "type"            -> Json.Str("web_search_result_location"),
                "url"             -> Json.Str("https://example.com"),
                "title"           -> Json.Str("Result"),
                "encrypted_index" -> Json.Str("opaque-exact-index"),
                "cited_text"      -> Json.Str("evidence")
              )
            )
          )
          .toJson
      ) + blockStop(2) +
      (if mixed then toolStart(3) + blockStop(3) + finish("tool_use")
       else finish("end_turn")) + stop

  private def response(value: String): Response = Response(
    headers = Headers("Content-Type" -> "text/event-stream"),
    body = Body.fromString(value)
  )

  private def frame(kind: String, body: String): String =
    val fields = body.fromJson[Json.Obj].toOption.map(_.fields).getOrElse(Chunk.empty)
    s"event: $kind\ndata: ${Json.Obj(Chunk("type" -> Json.Str(kind)) ++ fields).toJson}\n\n"

  private def textBlock(index: Int, value: String): String =
    frame(
      "content_block_start",
      Json
        .Obj(
          "index"         -> Json.Num(index),
          "content_block" -> Json.Obj("type" -> Json.Str("text"), "text" -> Json.Str(value))
        )
        .toJson
    ) + blockStop(index)

  private def toolStart(index: Int): String = frame(
    "content_block_start",
    Json
      .Obj(
        "index" -> Json.Num(index),
        "content_block" -> Json.Obj(
          "type"  -> Json.Str("tool_use"),
          "id"    -> Json.Str("call-1"),
          "name"  -> Json.Str("lookup"),
          "input" -> Json.Obj()
        )
      )
      .toJson
  )

  private def blockStop(index: Int): String =
    frame("content_block_stop", Json.Obj("index" -> Json.Num(index)).toJson)

  private def finish(reason: String): String = frame(
    "message_delta",
    Json
      .Obj(
        "delta" -> Json.Obj("stop_reason" -> Json.Str(reason)),
        "usage" -> Json.Obj("output_tokens" -> Json.Num(7))
      )
      .toJson
  )
