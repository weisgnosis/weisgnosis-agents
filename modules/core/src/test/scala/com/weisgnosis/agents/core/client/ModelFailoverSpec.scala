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

import com.weisgnosis.agents.core.agent.{
  ModelInputProjection,
  ModelInputTransform,
  ModelRequestInput,
  ModelRequestSettings,
  ReActAgent,
  StructuredMode
}
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.event.{AgentEvent, ModelStreamContext}
import com.weisgnosis.agents.core.model.{Msg, Role}
import com.weisgnosis.agents.core.tool.{Tool, ToolDef, ToolFixedArguments, Toolkit}
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.stream.ZStream
import zio.test.*
import HttpClientTestSupport.scripted

object ModelFailoverSpec extends ZIOSpecDefault:
  private enum Exclusion:
    case Legacy, ClientCleanup, Malformed, Provider, Partial, Disabled, NativeUnsupported,
      NativeToolsUnsupported

  private enum Cleanup:
    case Release, Defect, Cancel, FallbackFailure, FallbackCleanup, TargetProjection

  private val primaryConfig = LlmConfig("primary", endpoint = "http://localhost/primary")
  private val targetConfig  = LlmConfig("target", endpoint = "http://localhost/target")
  private val rejection =
    AgentError.ModelError(503, "HTTP completion request was rejected", Some("busy"))
  private val done   = "data: [DONE]\n\n"
  private val finish = frame("""{"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""")
  private val answer =
    frame("""{"choices":[{"index":0,"delta":{"content":"7"}}]}""") + finish + done
  private val toolCall = frame(
    """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"lookup-1","function":{"name":"lookup","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}"""
  ) + done
  private val lookup =
    Tool.make(ToolDef("lookup", "Read a value", Json.Obj()))(_ => ZIO.succeed("value"))

  def spec: Spec[TestEnvironment & Scope, Any] = suite("ModelFailover")(
    test(
      "selects the target per request with truthful contexts, projected input and one canonical turn"
    ):
      ZIO.scoped:
        for
          calls       <- Ref.make(Chunk.empty[(String, Json)])
          projections <- Ref.make(Chunk.empty[(ModelStreamContext, ModelRequestInput)])
          executed    <- Ref.make(Chunk.empty[Json])
          targetCalls <- Ref.make(0)
          retryPolicy <- ZIO.fromEither(CompletionRetryPolicy.make(2))
          primary <- ChatCompletionsClient.make(
            scripted(request =>
              request.body.asString
                .flatMap(body =>
                  ZIO.fromEither(body.fromJson[Json]).mapError(AgentError.DeserializationError(_))
                )
                .flatMap(body => calls.update(_ :+ ("primary", body)))
                .as(rejected)
            ),
            retryPolicy = retryPolicy
          )
          target <- ChatCompletionsClient.make(
            scripted(request =>
              request.body.asString
                .flatMap(body =>
                  ZIO.fromEither(body.fromJson[Json]).mapError(AgentError.DeserializationError(_))
                )
                .flatMap(body => calls.update(_ :+ ("target", body))) *>
                targetCalls
                  .updateAndGet(_ + 1)
                  .map(count => response(if count == 1 then toolCall else answer))
            )
          )
          fixed <- ZIO.fromEither(ToolFixedArguments.make(Json.Obj("tenant" -> Json.Str("host"))))
          selectedTool <- ZIO.fromEither(
            Tool.withFixedArguments(
              Tool.make(lookup.definition)(input => executed.update(_ :+ input).as("value")),
              fixed
            )
          )
          toolkit <- ZIO.fromEither(Toolkit.empty.register(selectedTool))
          agent <- ReActAgent.make(
            client = primary,
            llmConfig = primaryConfig,
            toolkit = toolkit,
            fallback = Some(
              ModelTarget(
                target,
                targetConfig.copy(
                  temperature = 1.1,
                  maxTokens = Some(77),
                  responseFormat = Some(Json.Obj("unused" -> Json.Bool(true)))
                )
              )
            ),
            modelInputTransform =
              ModelInputTransform.withRequest(ModelInputTransform.identity)((context, request) =>
                projections
                  .update(_ :+ (context, request))
                  .as(
                    ModelInputProjection(
                      request.history.map(message =>
                        if message.role == Role.User then Msg.user(s"request for ${request.model}")
                        else message
                      ),
                      ModelRequestSettings(
                        if request.model == "primary" then 0.2 else 0.4,
                        Some(42)
                      )
                    )
                  )
              )
          )
          events       <- agent.streamEvents(Msg.user("question")).runCollect
          history      <- agent.history
          requests     <- calls.get
          projected    <- projections.get
          actualInputs <- executed.get
          starts   = events.collect { case event: AgentEvent.ModelRequestStartEvent => event }
          ends     = events.collect { case event: AgentEvent.ModelResponseEndEvent => event }
          contexts = starts.flatMap(_.context)
          structured <- ZIO.foreach(Chunk(StructuredMode.Native, StructuredMode.SyntheticTool)):
            mode =>
              for
                requests <- Ref.make(Chunk.empty[Json])
                selected <- ChatCompletionsClient.make(
                  scripted(request =>
                    request.body.asString
                      .flatMap(body =>
                        ZIO
                          .fromEither(body.fromJson[Json])
                          .mapError(AgentError.DeserializationError(_))
                      )
                      .flatMap(body => requests.update(_ :+ body))
                      .as(response(mode match
                        case StructuredMode.SyntheticTool =>
                          frame(
                            """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"capture","function":{"name":"generate_response","arguments":"{\"response\":7}"}}]},"finish_reason":"tool_calls"}]}"""
                          ) + done
                        case _ => answer))
                  )
                )
                structuredAgent <- ReActAgent.make(
                  client = primary,
                  llmConfig = primaryConfig,
                  toolkit = toolkit,
                  modelInputTransform =
                    ModelInputTransform.withRequest(ModelInputTransform.identity)((_, request) =>
                      ZIO.succeed(
                        ModelInputProjection(
                          request.history,
                          ModelRequestSettings(0.3, Some(23))
                        )
                      )
                    ),
                  fallback = Some(
                    ModelTarget(
                      selected,
                      targetConfig.copy(
                        responseFormat = Some(Json.Obj("stale" -> Json.Bool(true))),
                        structuredOutputCapability =
                          StructuredOutputCapability.Native(NativeToolSupport.Supported)
                      )
                    )
                  )
                )
                result <- structuredAgent.runStructured[Int](
                  Chunk(Msg.user("number")),
                  Json.Obj("type" -> Json.Str("integer")),
                  mode
                )
                sent <- requests.get
                expectedFormat = mode match
                  case StructuredMode.Native =>
                    Some(
                      Json.Obj(
                        "type" -> Json.Str("json_schema"),
                        "json_schema" -> Json.Obj(
                          "name"   -> Json.Str("response"),
                          "schema" -> Json.Obj("type" -> Json.Str("integer"))
                        )
                      )
                    )
                  case _ => None
              yield assertTrue(
                result._2 == 7,
                sent.size == 1,
                sent.forall(
                  _.asObject.exists(fields =>
                    fields.get("response_format") == expectedFormat &&
                      fields.get("temperature").contains(Json.Num(0.3)) &&
                      fields.get("max_tokens").contains(Json.Num(23))
                  )
                )
              )
        yield assertTrue(
          requests
            .map(_._1) == Chunk("primary", "primary", "target", "primary", "primary", "target"),
          starts.map(_.model) == Chunk("primary", "target", "primary", "target"),
          ends.map(_.model) == Chunk("target", "target"),
          ends.flatMap(_.context) == Chunk.fromIterable(contexts.zipWithIndex.collect {
            case (context, index) if index % 2 == 1 => context
          }),
          contexts.map(_.requestId).distinct.size == 4,
          contexts.map(_.replyId).distinct.size == 1,
          projected.map(_._1) == contexts,
          projected.map(_._2.history).grouped(2).forall(pair => pair.distinct.size == 1),
          projected.map(_._2.model) == starts.map(_.model),
          projected.forall { case (_, request) =>
            request.tools == toolkit.definitions && request.settings ==
              (if request.model == "primary" then ModelRequestSettings(0.7, None)
               else ModelRequestSettings(1.1, Some(77)))
          },
          requests.forall { case (model, value) =>
            value.asObject.exists(fields =>
              fields.get("model").contains(Json.Str(model)) && fields
                .get("response_format")
                .isEmpty &&
                fields
                  .get("temperature")
                  .contains(Json.Num(if model == "primary" then 0.2 else 0.4)) &&
                fields.get("max_tokens").contains(Json.Num(42)) &&
                fields.get("messages").exists(_.toJson.contains(s"request for $model"))
            )
          },
          history.map(_.role) == Chunk(Role.User, Role.Assistant, Role.Tool, Role.Assistant),
          history.headOption.exists(_.text == "question"),
          actualInputs == Chunk(Json.Obj("tenant" -> Json.Str("host"))),
          history.flatMap(_.toolUses).map(_.input) == actualInputs,
          history.lastOption.exists(_.text == "7"),
          events.count(_.isInstanceOf[AgentEvent.AgentResultEvent]) == 1
        ) && structured.foldLeft(assertTrue(true))(_ && _)
    ,
    test("requires trusted pre-response rejection and compatible selected structured capabilities"):
      ZIO
        .foreach(Chunk.fromArray(Exclusion.values)): scenario =>
          ZIO.scoped:
            for
              targetCalls <- Ref.make(0)
              defect = new IllegalStateException("client cleanup failed")
              live <- ChatCompletionsClient.make(
                scripted(_ =>
                  ZIO.succeed(scenario match
                    case Exclusion.Malformed => response(frame("not-json") + done)
                    case Exclusion.Provider =>
                      response(frame("""{"error":{"message":"provider error"}}""") + done)
                    case Exclusion.Partial =>
                      response(frame("""{"choices":[{"index":0,"delta":{"content":"partial"}}]}"""))
                    case _ => rejected)
                )
              )
              legacy = LlmClient.mockEffectful((_, _, _) => ZIO.fail(rejection))
              withCleanup = new LlmClient:
                def streamTurn(
                    history: Chunk[Msg],
                    tools: Chunk[ToolDef],
                    config: LlmConfig,
                    eventHub: Hub[AgentEvent],
                    context: ModelStreamContext
                ): ZIO[Scope, AgentError, TurnOutput] =
                  live.streamTurn(history, tools, config, eventHub, context)
                override private[core] def attemptTurn(
                    history: Chunk[Msg],
                    tools: Chunk[ToolDef],
                    config: LlmConfig,
                    eventHub: Hub[AgentEvent],
                    context: ModelStreamContext
                ): ZIO[Scope, AgentError | BeforeResponseRejection, TurnOutput] =
                  ZIO.addFinalizer(ZIO.die(defect)) *> live
                    .attemptTurn(history, tools, config, eventHub, context)
              target <- ChatCompletionsClient.make(
                scripted(_ => targetCalls.update(_ + 1).as(response(answer)))
              )
              toolkit <- ZIO.fromEither(Toolkit.empty.register(lookup))
              capability = scenario match
                case Exclusion.NativeToolsUnsupported =>
                  StructuredOutputCapability.Native(NativeToolSupport.Unsupported)
                case _ => StructuredOutputCapability.Unsupported
              agent <- ReActAgent.make(
                client = scenario match
                  case Exclusion.Legacy        => legacy
                  case Exclusion.ClientCleanup => withCleanup
                  case _                       => live,
                llmConfig = primaryConfig,
                toolkit =
                  if scenario == Exclusion.NativeToolsUnsupported then toolkit else Toolkit.empty,
                fallback =
                  if scenario == Exclusion.Disabled then None
                  else
                    Some(
                      ModelTarget(
                        target,
                        targetConfig.copy(structuredOutputCapability = capability)
                      )
                    )
              )
              exit <- (scenario match
                case Exclusion.NativeUnsupported | Exclusion.NativeToolsUnsupported =>
                  agent
                    .runStructured[Int](
                      Chunk(Msg.user("q")),
                      Json.Obj("type" -> Json.Str("integer")),
                      StructuredMode.Native
                    )
                    .unit
                case _ => agent.run(Msg.user("q")).unit
              ).exit
              count <- targetCalls.get
            yield assertTrue(count == 0, exit.isFailure, !exit.isInterrupted) && (scenario match
              case Exclusion.ClientCleanup =>
                assertTrue(exit.causeOption.contains(Cause.fail(rejection) ++ Cause.die(defect)))
              case Exclusion.Malformed =>
                assertTrue(
                  exit.causeOption.exists(cause =>
                    cause.failures.size == 1 && cause.failures.exists(
                      _.isInstanceOf[AgentError.DeserializationError]
                    ) && cause.defects.isEmpty
                  )
                )
              case Exclusion.Provider =>
                assertTrue(
                  exit.causeOption
                    .contains(Cause.fail(AgentError.ModelError(200, "provider error")))
                )
              case Exclusion.Partial =>
                assertTrue(
                  exit.causeOption.contains(
                    Cause.fail(AgentError.ModelError(200, "Event stream ended before [DONE]"))
                  )
                )
              case _ => assertTrue(exit.causeOption.contains(Cause.fail(rejection))))
        .map(_.foldLeft(assertTrue(true))(_ && _))
    ,
    test(
      "joins primary cleanup before switching and retains cancellation, defects and target failures"
    ):
      ZIO
        .foreach(Chunk.fromArray(Cleanup.values)): scenario =>
          ZIO.scoped:
            for
              entered         <- Promise.make[Nothing, Unit]
              release         <- Promise.make[Nothing, Unit]
              cancelled       <- Promise.make[Nothing, Unit]
              closed          <- Ref.make(false)
              targetCalls     <- Ref.make(0)
              targetSawClosed <- Ref.make(false)
              projections     <- Ref.make(0)
              defect = new IllegalStateException("primary cleanup failed")
              targetRejection = AgentError
                .ModelError(400, "HTTP completion request was rejected", Some("target rejected"))
              waitForCancellation = scenario match
                case Cleanup.Cancel =>
                  ZIO.descriptor
                    .zip(release.isDone)
                    .flatMap:
                      case (descriptor, released) =>
                        if descriptor.interrupters.nonEmpty then cancelled.succeed(()).as(true)
                        else ZIO.yieldNow.as(released)
                    .repeatUntil(identity)
                    .unit
                case _ => ZIO.unit
              cleanup = entered.succeed(()) *> waitForCancellation *> release.await *> closed.set(
                true
              ) *>
                (scenario match
                  case Cleanup.Defect | Cleanup.Cancel => ZIO.die(defect)
                  case _                               => ZIO.unit)
              primary <- ChatCompletionsClient.make(
                scripted(_ => ZIO.addFinalizer(cleanup).as(rejected))
              )
              target <- ChatCompletionsClient.make(
                scripted(_ =>
                  targetCalls.update(_ + 1) *> closed.get.flatMap(targetSawClosed.set) *>
                    (scenario match
                      case Cleanup.FallbackCleanup => ZIO.addFinalizer(ZIO.die(defect))
                      case _                       => ZIO.unit
                    ).as(scenario match
                      case Cleanup.FallbackFailure | Cleanup.FallbackCleanup =>
                        Response(
                          status = Status.BadRequest,
                          body = Body.fromString("target rejected")
                        )
                      case _ => response(answer))
                )
              )
              agent <- ReActAgent.make(
                client = primary,
                llmConfig = primaryConfig,
                fallback = Some(
                  ModelTarget(
                    target,
                    targetConfig.copy(
                      structuredOutputCapability =
                        StructuredOutputCapability.Native(NativeToolSupport.Supported)
                    )
                  )
                ),
                modelInputTransform = ModelInputTransform((_, history) =>
                  projections
                    .updateAndGet(_ + 1)
                    .flatMap(count =>
                      if scenario == Cleanup.TargetProjection && count == 2 then
                        ZIO.fail(AgentError.ModelInputError("target projection failed"))
                      else ZIO.succeed(history)
                    )
                )
              )
              result <- (for
                fiber <- (scenario match
                  case Cleanup.FallbackFailure | Cleanup.FallbackCleanup =>
                    agent
                      .runStructured[Int](
                        Chunk(Msg.user("q")),
                        Json.Obj("type" -> Json.Str("integer")),
                        StructuredMode.Auto
                      )
                      .unit
                  case _ => agent.run(Msg.user("q")).unit
                ).forkScoped
                _ <- entered.await
                _ <- scenario match
                  case Cleanup.Cancel =>
                    ZIO.fiberId.flatMap(fiber.interruptAsFork) *> cancelled.await
                  case _ => ZIO.unit
                pending   <- fiber.poll
                before    <- targetCalls.get
                _         <- release.succeed(())
                exit      <- fiber.await
                count     <- targetCalls.get
                ordered   <- targetSawClosed.get
                projected <- projections.get
              yield assertTrue(pending.isEmpty, before == 0) && (scenario match
                case Cleanup.Release => assertTrue(exit.isSuccess, count == 1, ordered)
                case Cleanup.Defect =>
                  assertTrue(
                    count == 0,
                    exit.causeOption.contains(Cause.fail(rejection) ++ Cause.die(defect))
                  )
                case Cleanup.Cancel =>
                  assertTrue(
                    count == 0,
                    exit.isInterrupted,
                    exit.causeOption.exists(cause =>
                      cause.defects == List(defect) && cause.failures == List(rejection)
                    )
                  )
                case Cleanup.FallbackFailure =>
                  assertTrue(
                    count == 1,
                    projected == 2,
                    ordered,
                    exit.causeOption.contains(
                      Cause.fail(rejection) ++ Cause.fail(targetRejection)
                    )
                  )
                case Cleanup.FallbackCleanup =>
                  assertTrue(
                    count == 1,
                    projected == 2,
                    ordered,
                    exit.causeOption.contains(
                      Cause.fail(rejection) ++ Cause.fail(targetRejection) ++ Cause.die(defect)
                    )
                  )
                case Cleanup.TargetProjection =>
                  assertTrue(
                    count == 0,
                    exit.causeOption.contains(
                      Cause.fail(rejection) ++ Cause
                        .fail(AgentError.ModelInputError("target projection failed"))
                    )
                  ))).ensuring(release.succeed(()).unit)
            yield result
        .map(_.foldLeft(assertTrue(true))(_ && _))
  ) @@ TestAspect.timeout(20.seconds)

  private def frame(value: String): String = s"data: $value\n\n"

  private def response(value: String): Response =
    Response(
      headers = Headers("Content-Type", "text/event-stream"),
      body = Body.fromStreamChunked(
        ZStream.fromIterable(
          Chunk.fromArray(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))
        )
      )
    )

  private def rejected: Response =
    Response(status = Status.ServiceUnavailable, body = Body.fromString("busy"))
