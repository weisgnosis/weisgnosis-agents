/*
 * Copyright 2024-2026 WeisGnosis (https://weisgnosis.com)
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations.
 */
package com.weisgnosis.agents.examples

import com.weisgnosis.agents.core.agent.{AgentResult, AgentRunState, AgentRunUpdate, ReActAgent}
import com.weisgnosis.agents.core.client.{
  ChatCompletionsClient,
  CompletionTransportLimits,
  LlmConfig
}
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.event.AgentEvent
import com.weisgnosis.agents.core.model.Msg
import com.weisgnosis.agents.core.tool.{Tool, Toolkit}
import zio.*
import zio.http.Client
import zio.json.*
import zio.json.ast.Json

/** Explicitly enabled external validation; ordinary verification never sends provider requests. */
object LiveProviderSmoke extends ZIOAppDefault:
  def run: ZIO[Any, Throwable, Unit] =
    configuration.flatMap:
      case None =>
        Console.printLine(
          "Live provider smoke disabled; set WEISGNOSIS_LIVE_SMOKE=enabled to opt in."
        )
      case Some(config) =>
        ZIO.serviceWithZIO[Client](http => exercise(http, config)).provide(Client.default)

  private[examples] def configuration: IO[AgentError, Option[LlmConfig]] =
    environment("WEISGNOSIS_LIVE_SMOKE").flatMap:
      case None => ZIO.none
      case Some("enabled") =>
        for
          endpoint <- required("WEISGNOSIS_LIVE_ENDPOINT")
          model    <- required("WEISGNOSIS_LIVE_MODEL")
          key      <- environment("WEISGNOSIS_LIVE_API_KEY")
          tokens   <- boundedInteger("WEISGNOSIS_LIVE_MAX_TOKENS", 4096, 64, 32768)
        yield Some(
          LlmConfig(
            model,
            endpoint = endpoint,
            apiKey = key.filterNot(_.isBlank),
            maxTokens = Some(tokens)
          )
        )
      case Some(_) =>
        ZIO.fail(AgentError.InvalidConfiguration("WEISGNOSIS_LIVE_SMOKE must be enabled or unset"))

  private[examples] def runTimeout: IO[AgentError, Duration] =
    boundedInteger("WEISGNOSIS_LIVE_TIMEOUT_SECONDS", 300, 10, 1800).map(_.seconds)

  private def boundedInteger(
      name: String,
      default: Int,
      minimum: Int,
      maximum: Int
  ): IO[AgentError, Int] =
    environment(name).flatMap:
      case None => ZIO.succeed(default)
      case Some(value) =>
        ZIO
          .fromOption(value.toIntOption.filter(number => number >= minimum && number <= maximum))
          .orElseFail(
            AgentError.InvalidConfiguration(
              s"$name must be an integer between $minimum and $maximum"
            )
          )

  private def environment(name: String): IO[AgentError, Option[String]] =
    System.env(name).mapError(_ => AgentError.InvalidConfiguration(s"Cannot read $name"))

  private def required(name: String): IO[AgentError, String] =
    environment(name).flatMap(value =>
      ZIO
        .fromOption(value.filterNot(_.isBlank))
        .orElseFail(AgentError.InvalidConfiguration(s"Set $name before enabling live validation"))
    )

  final private case class SumInput(a: Int, b: Int) derives JsonCodec
  final private case class Observation(textDeltas: Int = 0, result: Option[AgentResult] = None)

  private[examples] def exercise(http: Client, config: LlmConfig): IO[AgentError, Unit] =
    ZIO.scoped:
      for
        executions <- Ref.make(0)
        tool = Tool.fromFunction[SumInput, Int](
          "smoke_sum",
          "Add the two supplied integers.",
          Json.Obj(
            "type" -> Json.Str("object"),
            "properties" -> Json.Obj(
              "a" -> Json.Obj("type" -> Json.Str("integer")),
              "b" -> Json.Obj("type" -> Json.Str("integer"))
            ),
            "required"             -> Json.Arr(Json.Str("a"), Json.Str("b")),
            "additionalProperties" -> Json.Bool(false)
          ),
          isReadOnly = true
        )(input =>
          if input.a == 2 && input.b == 3 then executions.update(_ + 1).as(5)
          else
            ZIO.fail(
              AgentError.ToolExecutionError("smoke_sum", "Expected the requested inputs 2 and 3")
            )
        )
        toolkit <- ZIO.fromEither(Toolkit.empty.register(tool))
        client <- ChatCompletionsClient.make(
          http,
          CompletionTransportLimits(responseBytes = 1024L * 1024)
        )
        timeout <- runTimeout
        agent <- ReActAgent
          .builder()
          .client(client)
          .llmConfig(config)
          .toolkit(toolkit)
          .maxIterations(3)
          .timeout(timeout)
          .build
        observed <- agent
          .streamWithResult(
            Msg.user(
              "Call smoke_sum exactly once with a=2 and b=3. Then answer with the returned number."
            )
          )
          .runFold(Observation()): (state, update) =>
            update match
              case AgentRunUpdate.Event(event: AgentEvent.TextBlockDeltaEvent)
                  if event.delta.nonEmpty =>
                state.copy(textDeltas = state.textDeltas + 1)
              case AgentRunUpdate.Result(result) => state.copy(result = Some(result))
              case _                             => state
        count <- executions.get
        _ <- ZIO
          .fail(
            AgentError.InvalidMessage(
              "Live smoke requires one successful tool call, streamed text and a completed reply"
            )
          )
          .unless(
            count == 1 && observed.textDeltas > 0 && observed.result.exists(result =>
              result.state == AgentRunState.Completed && result.reply.text.trim == "5"
            )
          )
        _ <- ZIO.logInfo(
          s"Live provider smoke passed: one tool call, ${observed.textDeltas} text deltas, completed reply"
        )
      yield ()
