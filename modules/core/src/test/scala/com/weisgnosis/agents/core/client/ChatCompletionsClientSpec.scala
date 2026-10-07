/*
 * Copyright 2024-2026 WeisGnosis (https://weisgnosis.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.weisgnosis.agents.core.client

import com.weisgnosis.agents.core.agent.{ModelInputTransform, ReActAgent}
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.event.{AgentEvent, ModelStreamContext}
import com.weisgnosis.agents.core.model.{ChatUsage, ContentBlock, Msg, Role, ToolResultState}
import com.weisgnosis.agents.core.tool.{
  Tool,
  ToolAuthorization,
  ToolAuthorizer,
  ToolDef,
  ToolGroup,
  ToolGroupActivation,
  ToolGroups,
  ToolGroupScope,
  Toolkit
}
import zio.*
import zio.http.*
import zio.stream.ZStream
import zio.json.*
import zio.json.ast.Json
import zio.test.*
import HttpClientTestSupport.scripted

object ChatCompletionsClientSpec extends ZIOSpecDefault:
  /** Allowed deliberately receives a call despite wire "none"; only the host authorizer grants execution. */
  private enum ChoiceAdmission:
    case Allowed, Denied, Hidden

  private val config = LlmConfig("stream-model", endpoint = "http://localhost/completion")
  private val text   = frame("""{"choices":[{"index":0,"delta":{"content":"answer"}}]}""")
  private val thinking = frame(
    """{"choices":[{"index":0,"delta":{"reasoning_content":"reason"}}]}"""
  )
  private val stop = frame("""{"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""")
  private val tool = frame(
    """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call-1","function":{"name":"lookup","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}"""
  )
  private val done = "data: [DONE]\n\n"

  def spec: Spec[TestEnvironment & Scope, Any] = suite("ChatCompletionsClient")(
    test(
      "publishes correlated deltas before response completion and commits only the finished reply"
    ):
      ZIO.scoped:
        for
          release  <- Promise.make[Nothing, Unit]
          received <- Promise.make[Nothing, Unit]
          observed <- Ref.make(Chunk.empty[AgentEvent])
          client <- ChatCompletionsClient.make(
            scripted(_ =>
              ZIO.succeed(
                response(
                  ZStream.succeed(thinking + text) ++ ZStream.fromZIO(release.await.as(stop + done))
                )
              )
            )
          )
          agent <- ReActAgent.make(client = client, llmConfig = config)
          fiber <- agent
            .streamEvents(Msg.user("question"))
            .tap(event =>
              observed.update(_ :+ event) *> (event match
                case _: AgentEvent.TextBlockDeltaEvent => received.succeed(()).unit
                case _                                 => ZIO.unit)
            )
            .runCollect
            .forkScoped
          _              <- received.await
          prefix         <- observed.get
          partialHistory <- agent.history
          _              <- release.succeed(())
          events         <- fiber.join
          history        <- agent.history
          replyIds = events.collect { case event: AgentEvent.AgentStartEvent => event.replyId }
          starts   = events.collect { case event: AgentEvent.ModelRequestStartEvent => event }
          ends     = events.collect { case event: AgentEvent.ModelResponseEndEvent => event }
        yield assertTrue(
          prefix.exists(_.isInstanceOf[AgentEvent.TextBlockDeltaEvent]),
          !prefix.exists(_.isInstanceOf[AgentEvent.ModelResponseEndEvent]),
          !partialHistory.exists(_.text == "answer"),
          history.lastOption.exists(msg => msg.text == "answer" && msg.thinking == "reason"),
          starts.size == 1,
          ends.size == 1,
          starts.flatMap(_.context) == ends.flatMap(_.context),
          contexts(events).forall(context => replyIds.contains(context.replyId)),
          contexts(events).map(_.requestId).distinct.size == 1,
          lifecycle(events) == Chunk(
            "agent-start",
            "model-start",
            "thinking-start",
            "thinking-delta",
            "text-start",
            "text-delta",
            "thinking-end",
            "text-end",
            "model-end",
            "agent-result",
            "agent-end"
          ),
          events.indexWhere(_.isInstanceOf[AgentEvent.TextBlockEndEvent]) < events.indexWhere(
            _.isInstanceOf[AgentEvent.ModelResponseEndEvent]
          ),
          events.indexWhere(_.isInstanceOf[AgentEvent.ModelResponseEndEvent]) < events.indexWhere(
            _.isInstanceOf[AgentEvent.AgentResultEvent]
          ),
          events.lastOption.exists(_.isInstanceOf[AgentEvent.AgentEndEvent])
        )
    ,
    test(
      "real HTTP tool choice preserves request identities, admitted visibility and authorization"
    )(
      ZIO
        .foreach(Chunk(ChoiceAdmission.Allowed, ChoiceAdmission.Denied, ChoiceAdmission.Hidden))(
          admission =>
            ZIO
              .scoped:
                for
                  requests   <- Ref.make(Chunk.empty[String])
                  executed   <- Ref.make(0)
                  authorized <- Ref.make(0)
                  lookup = Tool.make(ToolDef("lookup", "Look up a value", Json.Obj()))(_ =>
                    executed.update(_ + 1).as("observation")
                  )
                  groups <- ZIO.fromEither(
                    ToolGroups.empty.register(
                      ToolGroup(
                        "selected",
                        "Host tool selection",
                        if admission == ChoiceAdmission.Hidden then ToolGroupActivation.Inactive
                        else ToolGroupActivation.Active,
                        ToolGroupScope.External,
                        Set("lookup")
                      )
                    )
                  )
                  port <- Server.install(Routes(Method.POST / "completion" -> handler {
                    (request: Request) =>
                      request.body.asString
                        .orElseSucceed("")
                        .flatMap(body =>
                          requests.modify(previous => (previous.size, previous :+ body))
                        )
                        .map(index =>
                          response(
                            ZStream.succeed(if index == 0 then tool + done else text + stop + done)
                          )
                        )
                  }))
                  http   <- ZIO.service[Client]
                  client <- ChatCompletionsClient.make(http)
                  agent <- ReActAgent.make(
                    client = client,
                    toolkit = Toolkit.single(lookup),
                    toolGroups = Some(groups),
                    toolAuthorizer = ToolAuthorizer(request =>
                      authorized
                        .update(_ + 1)
                        .as(
                          if admission == ChoiceAdmission.Denied then
                            ToolAuthorization.Denied("host denial")
                          else ToolAuthorization.Approved(request.call.input)
                        )
                    ),
                    llmConfig = config.copy(
                      endpoint = s"http://127.0.0.1:$port/completion",
                      toolChoice = Some(
                        if admission == ChoiceAdmission.Allowed then ToolChoice.None
                        else ToolChoice.Specific("lookup")
                      )
                    )
                  )
                  outcome <- agent.streamEvents(Msg.user("lookup")).runCollect.either
                  bodies  <- requests.get
                  count   <- executed.get
                  checks  <- authorized.get
                  history <- agent.history
                  events = outcome.getOrElse(Chunk.empty)
                  starts = events.collect { case event: AgentEvent.ModelRequestStartEvent =>
                    event.context
                  }
                  ends = events.collect { case event: AgentEvent.ModelResponseEndEvent =>
                    event.context
                  }
                yield
                  if admission == ChoiceAdmission.Hidden then
                    assertTrue(
                      outcome.left.exists(_.isInstanceOf[AgentError.InvalidConfiguration]),
                      bodies.isEmpty,
                      count == 0,
                      checks == 0,
                      history.flatMap(_.toolResults).isEmpty
                    )
                  else
                    assertTrue(
                      outcome.isRight,
                      count == (if admission == ChoiceAdmission.Allowed then 1 else 0),
                      checks == 1,
                      bodies.size == 2,
                      bodies.forall(
                        _.fromJson[Json].exists(
                          _.asObject.exists(fields =>
                            fields
                              .get("tool_choice")
                              .contains(
                                if admission == ChoiceAdmission.Allowed then Json.Str("none")
                                else
                                  Json.Obj(
                                    "type"     -> Json.Str("function"),
                                    "function" -> Json.Obj("name" -> Json.Str("lookup"))
                                  )
                              ) && fields.get("tools").exists(_.asArray.exists(_.size == 1))
                          )
                        )
                      ),
                      bodies
                        .drop(1)
                        .headOption
                        .exists(
                          _.contains(
                            if admission == ChoiceAdmission.Allowed then "observation"
                            else "host denial"
                          )
                        ),
                      history.flatMap(_.toolResults).map(_.state) == Chunk(
                        if admission == ChoiceAdmission.Allowed then ToolResultState.Success
                        else ToolResultState.Denied
                      ),
                      starts.size == 2,
                      starts == ends,
                      starts.flatten.map(_.requestId).distinct.size == 2,
                      starts.flatten.map(_.replyId).distinct.size == 1,
                      events.exists(_.isInstanceOf[AgentEvent.ToolCallEndEvent]),
                      admission != ChoiceAdmission.Allowed || lifecycle(events) == Chunk(
                        "agent-start",
                        "model-start",
                        "tool-call-start",
                        "tool-call-delta",
                        "tool-call-end",
                        "model-end",
                        "tool-result-start",
                        "tool-result-delta",
                        "tool-result-end",
                        "model-start",
                        "text-start",
                        "text-delta",
                        "text-end",
                        "model-end",
                        "agent-result",
                        "agent-end"
                      ),
                      events.collect { case event: AgentEvent.AgentResultEvent =>
                        event.result.text
                      } == Chunk("answer")
                    )
              .provide(Client.default, Server.defaultWithPort(0))
        )
        .map(_.foldLeft(assertTrue(true))(_ && _))
    ) @@ TestAspect.withLiveClock,
    test(
      "selected HTTP status retries preserve one logical model request and never replay client errors"
    )(
      ZIO
        .foreach(Chunk(Status.TooManyRequests, Status.BadRequest))(status =>
          ZIO
            .scoped:
              for
                requests  <- Ref.make(Chunk.empty[(String, Option[String])])
                projected <- Ref.make(0)
                port <- Server.install(Routes(Method.POST / "completion" -> handler {
                  (request: Request) =>
                    request.body.asString
                      .orElseSucceed("")
                      .flatMap(body =>
                        requests.modify(previous =>
                          (
                            previous.size,
                            previous :+ (body -> request.headers.get("Authorization"))
                          )
                        )
                      )
                      .map(index =>
                        if index == 0 then
                          Response(status = status, body = Body.fromString("rejected"))
                        else
                          response(
                            ZStream.succeed(
                              text + stop + frame(
                                """{"choices":[],"usage":{"prompt_tokens":3,"completion_tokens":4}}"""
                              ) + done
                            )
                          )
                      )
                }))
                policy <- ZIO.fromEither(CompletionRetryPolicy.make(2))
                http   <- ZIO.service[Client]
                client <- ChatCompletionsClient.make(http, retryPolicy = policy)
                agent <- ReActAgent.make(
                  client = client,
                  llmConfig = config.copy(
                    endpoint = s"http://127.0.0.1:$port/completion",
                    apiKey = Some("test-key")
                  ),
                  modelInputTransform =
                    ModelInputTransform((_, history) => projected.update(_ + 1).as(history))
                )
                seen <- Ref.make(Chunk.empty[AgentEvent])
                exit <- agent
                  .streamEvents(Msg.user("question"))
                  .tap(event => seen.update(_ :+ event))
                  .runDrain
                  .exit
                events      <- seen.get
                bodies      <- requests.get
                history     <- agent.history
                projections <- projected.get
                accepted = status == Status.TooManyRequests
              yield assertTrue(
                bodies.size == (if accepted then 2 else 1),
                bodies.distinct.size == 1,
                bodies.forall(_._2.contains("Bearer test-key")),
                projections == 1,
                history.count(_.role == Role.User) == 1,
                history.count(_.role == Role.Assistant) == (if accepted then 1 else 0),
                events.count(_.isInstanceOf[AgentEvent.ModelResponseChunkEvent]) == (if accepted
                                                                                     then 3
                                                                                     else 0),
                events.count(_.isInstanceOf[AgentEvent.ModelRequestStartEvent]) == 1,
                contexts(events).map(_.requestId).distinct.size == 1,
                events.count(_.isInstanceOf[AgentEvent.ModelResponseEndEvent]) == (if accepted then
                                                                                     1
                                                                                   else 0),
                events.collect { case event: AgentEvent.TextBlockDeltaEvent => event.delta } ==
                  (if accepted then Chunk("answer") else Chunk.empty),
                events.collect { case event: AgentEvent.AgentResultEvent => event.result.text } ==
                  (if accepted then Chunk("answer") else Chunk.empty),
                if accepted then
                  exit.isSuccess && history.lastOption.exists(message =>
                    message.text == "answer" && message.usage.contains(ChatUsage(3, 4))
                  )
                else
                  exit.causeOption.exists(_.failureOption.exists {
                    case AgentError.ModelError(400, _, _) => true
                    case _                                => false
                  })
                ,
                events.lastOption.exists(_.isInstanceOf[AgentEvent.AgentEndEvent])
              )
            .provide(Client.default, Server.defaultWithPort(0))
        )
        .map(_.foldLeft(assertTrue(true))(_ && _))
    ) @@ TestAspect.withLiveClock,
    test("a real signed tool cycle replays only its signature and denies edits to signed input")(
      ZIO
        .scoped:
          val beginning = frame(
            """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"signed","function":{"name":"lookup","arguments":"{\"value\":"}},{"index":1,"id":"unsigned","function":{"name":"lookup","arguments":"{\"value\":\"original\"}"}}]}}]}"""
          )
          val ending = frame(
            """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"original\"}"},"extra_content":{"google":{"thought_signature":"opaque-signature"},"serverTool":true}}]},"finish_reason":"tool_calls"}]}"""
          )
          val changed = Json.Obj("value" -> Json.Str("changed"))
          for
            requests   <- Ref.make(Chunk.empty[String])
            executed   <- Ref.make(Chunk.empty[Json])
            authorized <- Ref.make(Chunk.empty[Map[String, Json]])
            lookup = Tool.make(ToolDef("lookup", "Look up a value", Json.Obj()))(input =>
              executed.update(_ :+ input).as("observation")
            )
            authorizer = ToolAuthorizer(request =>
              authorized.update(_ :+ request.call.metadata).as(ToolAuthorization.Approved(changed))
            )
            port <- Server.install(Routes(Method.POST / "completion" -> handler {
              (request: Request) =>
                request.body.asString
                  .orElseSucceed("")
                  .flatMap(body => requests.modify(previous => (previous.size, previous :+ body)))
                  .map(index =>
                    response(
                      ZStream.succeed(
                        if index == 0 then beginning + ending + done else text + stop + done
                      )
                    )
                  )
            }))
            http <- ZIO.service[Client]
            client <- ChatCompletionsClient
              .make(http, toolSignatureFormat = ToolSignatureFormat.GoogleExtraContent)
            agent <- ReActAgent.make(
              client = client,
              toolkit = Toolkit.single(lookup),
              toolAuthorizer = authorizer,
              llmConfig = config.copy(endpoint = s"http://127.0.0.1:$port/completion")
            )
            events    <- agent.streamEvents(Msg.user("lookup both")).runCollect
            history   <- agent.history
            raw       <- requests.get
            bodies    <- ZIO.foreach(raw)(body => ZIO.fromEither(body.fromJson[Json]))
            inputs    <- executed.get
            decisions <- authorized.get
            replayed = bodies
              .drop(1)
              .flatMap(
                _.asObject.flatMap(_.get("messages")).flatMap(_.asArray).getOrElse(Chunk.empty)
              )
              .flatMap(
                _.asObject.flatMap(_.get("tool_calls")).flatMap(_.asArray).getOrElse(Chunk.empty)
              )
            signature = Json.Obj(
              "google" -> Json.Obj("thought_signature" -> Json.Str("opaque-signature"))
            )
            calls   = history.flatMap(_.toolUses)
            results = history.flatMap(_.toolResults)
          yield assertTrue(
            bodies.size == 2,
            inputs == Chunk(changed),
            decisions.count(_.contains("thoughtSignature")) == 1,
            calls
              .find(_.id == "signed")
              .exists(call =>
                call.input == Json.Obj("value" -> Json.Str("original")) &&
                  call.metadata.get("thoughtSignature").contains(Json.Str("opaque-signature"))
              ),
            calls
              .find(_.id == "unsigned")
              .exists(call => call.input == changed && !call.metadata.contains("thoughtSignature")),
            replayed
              .find(_.asObject.flatMap(_.get("id")).contains(Json.Str("signed")))
              .exists(_.asObject.flatMap(_.get("extra_content")).contains(signature)),
            replayed
              .find(_.asObject.flatMap(_.get("id")).contains(Json.Str("unsigned")))
              .exists(_.asObject.flatMap(_.get("extra_content")).isEmpty),
            results.find(_.id == "signed").exists(_.state == ToolResultState.Denied),
            results.find(_.id == "unsigned").exists(_.state == ToolResultState.Success),
            events.collect { case event: AgentEvent.AgentResultEvent =>
              event.result.text
            } == Chunk("answer")
          )
        .provide(Client.default, Server.defaultWithPort(0))
    ) @@ TestAspect.withLiveClock,
    test("real selected reasoning tool cycles preserve details, plaintext and original inputs")(
      ZIO
        .foreach(
          Chunk(ReasoningReplay.OpenRouterEncryptedDetails, ReasoningReplay.OpenRouterDetails)
        )(profile =>
          ZIO
            .scoped:
              val firstDetail = Json.Obj(
                "type"   -> Json.Str("reasoning.encrypted"),
                "id"     -> Json.Str("first"),
                "index"  -> Json.Num(0),
                "format" -> Json.Str("anthropic-claude-v1"),
                "data"   -> Json.Str(" opaque-first ")
              )
              val secondDetail = Json.Obj(
                "type"  -> Json.Str("reasoning.encrypted"),
                "id"    -> Json.Str("second"),
                "index" -> Json.Num(0),
                "data"  -> Json.Str("[REDACTED]")
              )
              val openingDetails = profile match
                case ReasoningReplay.OpenRouterDetails =>
                  Chunk(
                    firstDetail,
                    Json.Obj(
                      "type"      -> Json.Str("reasoning.text"),
                      "text"      -> Json.Str("consider "),
                      "signature" -> Json.Null,
                      "id"        -> Json.Str("shared"),
                      "index"     -> Json.Num(0)
                    )
                  )
                case _ => Chunk(firstDetail)
              val closingDetails = profile match
                case ReasoningReplay.OpenRouterDetails =>
                  Chunk(
                    Json.Obj(
                      "type"    -> Json.Str("reasoning.summary"),
                      "summary" -> Json.Str("summary ")
                    ),
                    secondDetail,
                    Json.Obj(
                      "type"      -> Json.Str("reasoning.text"),
                      "text"      -> Json.Str("answer"),
                      "signature" -> Json.Str(" opaque "),
                      "id"        -> Json.Str("shared"),
                      "index"     -> Json.Num(0)
                    )
                  )
                case _ => Chunk(secondDetail)
              val expected = Json.Arr(openingDetails ++ closingDetails)
              val expectedThinking = profile match
                case ReasoningReplay.OpenRouterDetails => "consider summary answer"
                case _                                 => ""
              def delta(details: Chunk[Json]): Json.Obj =
                val aliases = profile match
                  case ReasoningReplay.OpenRouterDetails =>
                    Chunk(
                      "reasoning_content" -> Json.Str("must not duplicate details"),
                      "thinking"          -> Json.Str("alternate scalar must not duplicate details")
                    )
                  case _ => Chunk.empty
                Json.Obj(Chunk("reasoning_details" -> Json.Arr(details)) ++ aliases)
              val opening = frame(
                Json
                  .Obj(
                    "choices" -> Json.Arr(
                      Json.Obj(
                        "index" -> Json.Num(0),
                        "delta" -> delta(openingDetails)
                      )
                    )
                  )
                  .toJson
              )
              val calls = frame(
                """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"edit","function":{"name":"lookup","arguments":"{}"}},{"index":1,"id":"keep","function":{"name":"lookup","arguments":"{}"}}]}}]}"""
              )
              val closing = frame(
                Json
                  .Obj(
                    "choices" -> Json.Arr(
                      Json.Obj(
                        "index"         -> Json.Num(0),
                        "delta"         -> delta(closingDetails),
                        "finish_reason" -> Json.Str("tool_calls")
                      )
                    )
                  )
                  .toJson
              )
              for
                requests <- Ref.make(Chunk.empty[String])
                executed <- Ref.make(Chunk.empty[Json])
                lookup = Tool.make(ToolDef("lookup", "Look up a value", Json.Obj()))(input =>
                  executed.update(_ :+ input).as("observation")
                )
                authorizer = ToolAuthorizer(request =>
                  ZIO.succeed(
                    ToolAuthorization.Approved(
                      if request.call.id == "edit" then Json.Obj("changed" -> Json.Bool(true))
                      else request.call.input
                    )
                  )
                )
                port <- Server.install(Routes(Method.POST / "completion" -> handler {
                  (request: Request) =>
                    request.body.asString
                      .orElseSucceed("")
                      .flatMap(body =>
                        requests.modify(previous => (previous.size, previous :+ body))
                      )
                      .map(index =>
                        response(
                          ZStream.succeed(
                            if index == 0 then opening + calls + closing + done
                            else text + stop + done
                          )
                        )
                      )
                }))
                http <- ZIO.service[Client]
                client <- ChatCompletionsClient
                  .make(http, reasoningReplay = profile)
                agent <- ReActAgent.make(
                  client = client,
                  toolkit = Toolkit.single(lookup),
                  toolAuthorizer = authorizer,
                  // The provider ignores this preference; both calls still require authorization.
                  llmConfig = config.copy(
                    endpoint = s"http://127.0.0.1:$port/completion",
                    parallelToolCalls = Some(ToolCallParallelism.Disabled)
                  )
                )
                events  <- agent.streamEvents(Msg.user("lookup both")).runCollect
                history <- agent.history
                raw     <- requests.get
                bodies  <- ZIO.foreach(raw)(body => ZIO.fromEither(body.fromJson[Json]))
                inputs  <- executed.get
                replayed = bodies
                  .drop(1)
                  .flatMap(
                    _.asObject.flatMap(_.get("messages")).flatMap(_.asArray).getOrElse(Chunk.empty)
                  )
                  .flatMap(_.asObject)
                  .filter(_.get("tool_calls").nonEmpty)
                wireCalls = replayed.flatMap(
                  _.get("tool_calls").flatMap(_.asArray).getOrElse(Chunk.empty)
                )
                retained = history.flatMap(_.content.collect { case value: ContentBlock.Thinking =>
                  value
                })
                results = history.flatMap(_.toolResults)
              yield assertTrue(
                bodies.size == 2,
                bodies.forall(
                  _.asObject.exists(_.get("parallel_tool_calls").contains(Json.Bool(false)))
                ),
                inputs == Chunk(Json.Obj()),
                replayed.size == 1,
                replayed.forall(_.get("reasoning_details").contains(expected)),
                wireCalls.size == 2,
                wireCalls.forall(
                  _.asObject
                    .flatMap(_.get("function"))
                    .flatMap(_.asObject)
                    .flatMap(_.get("arguments"))
                    .contains(Json.Str("{}"))
                ),
                retained.size == 1,
                retained
                  .forall(value =>
                    value.thinking == expectedThinking && value.reasoningDetails.contains(expected)
                  ),
                history
                  .flatMap(_.toolUses)
                  .forall(call => call.input == Json.Obj() && call.metadata.isEmpty),
                results.find(_.id == "edit").exists(_.state == ToolResultState.Denied),
                results.find(_.id == "keep").exists(_.state == ToolResultState.Success),
                events.collect { case event: AgentEvent.ThinkingBlockDeltaEvent =>
                  event.delta
                }.mkString == expectedThinking,
                events.collect { case event: AgentEvent.ThinkingBlockEndEvent =>
                  event.fullThought
                } ==
                  (if expectedThinking.isEmpty then Chunk.empty else Chunk(expectedThinking)),
                events.count(_.isInstanceOf[AgentEvent.ThinkingBlockStartEvent]) ==
                  (if expectedThinking.isEmpty then 0 else 1),
                replayed.forall(_.get("reasoning_content").isEmpty),
                events.collect { case event: AgentEvent.TextBlockDeltaEvent =>
                  event.delta
                }.mkString == "answer",
                events.collect { case event: AgentEvent.AgentResultEvent =>
                  event.result.text
                } == Chunk("answer")
              )
            .provide(Client.default, Server.defaultWithPort(0))
        )
        .map(_.foldLeft(assertTrue(true))(_ && _))
    ) @@ TestAspect.withLiveClock,
    test("buffers argument fragments until call identity exists"):
      val pending = frame(
        """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":2,"function":{"arguments":"{"}}]}}]}"""
      )
      val identified = frame(
        """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":2,"id":"late","function":{"name":"lookup","arguments":"}"}}]},"finish_reason":"tool_calls"}]}"""
      )
      ZIO.scoped:
        for
          hub   <- Hub.unbounded[AgentEvent]
          queue <- hub.subscribe
          client <- ChatCompletionsClient.make(
            scripted(_ => ZIO.succeed(response(ZStream.succeed(pending + identified + done))))
          )
          output <- client.streamTurn(
            Chunk(Msg.user("q")),
            Chunk.empty,
            config,
            hub,
            ModelStreamContext("reply", "request")
          )
          events <- queue.takeAll
          starts = events.collect { case event: AgentEvent.ToolCallStartEvent => event }
          deltas = events.collect { case event: AgentEvent.ToolCallDeltaEvent => event }
          ends   = events.collect { case event: AgentEvent.ToolCallEndEvent => event }
        yield assertTrue(
          output.message.toolUses.map(_.id) == Chunk("late"),
          starts.map(_.callId) == Chunk("late"),
          deltas.map(_.delta) == Chunk("{}"),
          ends.map(_.callId) == Chunk("late"),
          starts.forall(_.streamIndex.contains(2)),
          deltas.forall(_.streamIndex.contains(2)),
          events.indexWhere(_.isInstanceOf[AgentEvent.ToolCallStartEvent]) < events.indexWhere(
            _.isInstanceOf[AgentEvent.ToolCallDeltaEvent]
          )
        )
    ,
    test("incomplete tool streams cannot close calls or dispatch tools"):
      for
        called   <- Ref.make(false)
        observed <- Ref.make(Chunk.empty[AgentEvent])
        lookup = Tool.make(ToolDef("lookup", "lookup", Json.Obj()))(_ =>
          called.set(true).as("unexpected")
        )
        client <- ChatCompletionsClient.make(
          scripted(_ => ZIO.succeed(response(ZStream.succeed(tool))))
        )
        agent <- ReActAgent.make(
          client = client,
          toolkit = Toolkit.single(lookup),
          llmConfig = config
        )
        result <- agent
          .streamEvents(Msg.user("q"))
          .tap(event => observed.update(_ :+ event))
          .runDrain
          .either
        events   <- observed.get
        executed <- called.get
      yield assertTrue(
        result.isLeft,
        !executed,
        events.exists(_.isInstanceOf[AgentEvent.ToolCallStartEvent]),
        !events.exists(_.isInstanceOf[AgentEvent.ToolCallEndEvent]),
        !events.exists(_.isInstanceOf[AgentEvent.ModelResponseEndEvent]),
        !events.exists(_.isInstanceOf[AgentEvent.AgentResultEvent]),
        events.lastOption.exists(_.isInstanceOf[AgentEvent.AgentEndEvent])
      )
    ,
    test("rejects a malformed frame before publishing its apparently valid text"):
      val malformed = frame(
        """{"choices":[{"index":0,"delta":{"content":"untrusted","tool_calls":[{"function":{"name":"lookup"}}]}}]}"""
      )
      for
        observed <- Ref.make(Chunk.empty[AgentEvent])
        client <- ChatCompletionsClient.make(
          scripted(_ => ZIO.succeed(response(ZStream.succeed(text + malformed + done))))
        )
        agent <- ReActAgent.make(client = client, llmConfig = config)
        result <- agent
          .streamEvents(Msg.user("q"))
          .tap(event => observed.update(_ :+ event))
          .runDrain
          .either
        events <- observed.get
      yield assertTrue(
        result.isLeft,
        events.collect { case event: AgentEvent.TextBlockDeltaEvent => event.delta } == Chunk(
          "answer"
        ),
        events.count(_.isInstanceOf[AgentEvent.ModelResponseChunkEvent]) == 1,
        !events.exists(_.isInstanceOf[AgentEvent.TextBlockEndEvent])
      )
    ,
    test("cooperative interruption closes HTTP resources without completed model events"):
      ZIO.scoped:
        for
          waiting  <- Promise.make[Nothing, Unit]
          closed   <- Ref.make(false)
          observed <- Ref.make(Chunk.empty[AgentEvent])
          client <- ChatCompletionsClient.make(
            scripted(_ =>
              ZIO
                .addFinalizer(closed.set(true))
                .as(
                  response(
                    ZStream.succeed(text) ++ ZStream.fromZIO(waiting.succeed(()) *> ZIO.never)
                  )
                )
            )
          )
          agent <- ReActAgent.make(client = client, llmConfig = config)
          fiber <- agent
            .streamEvents(Msg.user("q"))
            .tap(event => observed.update(_ :+ event))
            .runCollect
            .forkScoped
          _        <- waiting.await
          _        <- agent.interrupt
          events   <- fiber.join
          released <- closed.get
        yield assertTrue(
          released,
          !events.exists(_.isInstanceOf[AgentEvent.ModelResponseEndEvent]),
          !events.exists(_.isInstanceOf[AgentEvent.TextBlockEndEvent]),
          events
            .collect { case event: AgentEvent.AgentResultEvent => event.result.text }
            .exists(_.contains("interrupted")),
          events.lastOption.exists(_.isInstanceOf[AgentEvent.AgentEndEvent])
        )
  ) @@ TestAspect.timeout(20.seconds)

  private def frame(json: String): String = s"data: $json\n\n"

  private def lifecycle(events: Chunk[AgentEvent]): Chunk[String] =
    events
      .filterNot(_.isInstanceOf[AgentEvent.ModelResponseChunkEvent])
      .map:
        case _: AgentEvent.AgentStartEvent         => "agent-start"
        case _: AgentEvent.ModelRequestStartEvent  => "model-start"
        case _: AgentEvent.ThinkingBlockStartEvent => "thinking-start"
        case _: AgentEvent.ThinkingBlockDeltaEvent => "thinking-delta"
        case _: AgentEvent.ThinkingBlockEndEvent   => "thinking-end"
        case _: AgentEvent.TextBlockStartEvent     => "text-start"
        case _: AgentEvent.TextBlockDeltaEvent     => "text-delta"
        case _: AgentEvent.TextBlockEndEvent       => "text-end"
        case _: AgentEvent.ToolCallStartEvent      => "tool-call-start"
        case _: AgentEvent.ToolCallDeltaEvent      => "tool-call-delta"
        case _: AgentEvent.ToolCallEndEvent        => "tool-call-end"
        case _: AgentEvent.ToolResultStartEvent    => "tool-result-start"
        case _: AgentEvent.ToolResultDeltaEvent    => "tool-result-delta"
        case _: AgentEvent.ToolResultEndEvent      => "tool-result-end"
        case _: AgentEvent.ModelResponseEndEvent   => "model-end"
        case _: AgentEvent.AgentResultEvent        => "agent-result"
        case _: AgentEvent.AgentEndEvent           => "agent-end"
        case _                                     => "unexpected"

  private def response(stream: ZStream[Any, Nothing, String]): Response =
    Response(
      headers = Headers("Content-Type", "text/event-stream"),
      body = Body.fromCharSequenceStreamChunked(stream)
    )

  private def contexts(events: Chunk[AgentEvent]): Chunk[ModelStreamContext] =
    events.flatMap:
      case event: AgentEvent.ModelRequestStartEvent  => Chunk.fromIterable(event.context)
      case event: AgentEvent.ModelResponseChunkEvent => Chunk.fromIterable(event.context)
      case event: AgentEvent.ModelResponseEndEvent   => Chunk.fromIterable(event.context)
      case event: AgentEvent.TextBlockStartEvent     => Chunk.fromIterable(event.context)
      case event: AgentEvent.TextBlockDeltaEvent     => Chunk.fromIterable(event.context)
      case event: AgentEvent.TextBlockEndEvent       => Chunk.fromIterable(event.context)
      case event: AgentEvent.ThinkingBlockStartEvent => Chunk.fromIterable(event.context)
      case event: AgentEvent.ThinkingBlockDeltaEvent => Chunk.fromIterable(event.context)
      case event: AgentEvent.ThinkingBlockEndEvent   => Chunk.fromIterable(event.context)
      case event: AgentEvent.ToolCallStartEvent      => Chunk.fromIterable(event.context)
      case event: AgentEvent.ToolCallDeltaEvent      => Chunk.fromIterable(event.context)
      case event: AgentEvent.ToolCallEndEvent        => Chunk.fromIterable(event.context)
      case _                                         => Chunk.empty
