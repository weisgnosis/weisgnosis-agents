/*
 * Copyright 2024-2026 WeisGnosis (https://weisgnosis.com)
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.weisgnosis.agents.examples

import com.weisgnosis.agents.core.agent.{AgentRunState, ReActAgent}
import com.weisgnosis.agents.core.client.{
  AnthropicLlmClient,
  AnthropicWebSearch,
  GeminiCodeExecution,
  GeminiLlmClient,
  ModelTarget
}
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.model.{Msg, Role, ToolResultState}
import zio.*
import zio.http.Client

/** Explicit opt-in native hosted-tool verification; ordinary builds make no provider requests. */
object NativeProviderSmoke extends ZIOAppDefault:
  private enum Provider:
    case Anthropic, Gemini

  def run: ZIO[Any, Throwable, Unit] =
    System
      .env("WEISGNOSIS_NATIVE_PROVIDER")
      .mapError(_ => AgentError.InvalidConfiguration("Cannot read WEISGNOSIS_NATIVE_PROVIDER"))
      .flatMap:
        case None =>
          Console.printLine(
            "Native provider smoke disabled; select anthropic or gemini explicitly."
          )
        case Some(value) =>
          for
            provider <- ZIO.fromEither(value match
              case "anthropic" => Right(Provider.Anthropic)
              case "gemini"    => Right(Provider.Gemini)
              case _ =>
                Left(
                  AgentError
                    .InvalidConfiguration("WEISGNOSIS_NATIVE_PROVIDER must be anthropic or gemini")
                ))
            configured <- LiveProviderSmoke.configuration
            config <- ZIO
              .fromOption(configured)
              .orElseFail(
                AgentError.InvalidConfiguration(
                  "Enable WEISGNOSIS_LIVE_SMOKE and supply native endpoint/model credentials"
                )
              )
            _ <- ZIO
              .serviceWithZIO[Client](http =>
                ZIO.scoped:
                  for
                    client <- provider match
                      case Provider.Anthropic =>
                        ZIO
                          .fromEither(AnthropicWebSearch.make(1))
                          .flatMap(search =>
                            AnthropicLlmClient.make(http, webSearch = Some(search))
                          )
                      case Provider.Gemini =>
                        GeminiLlmClient.make(http, execution = GeminiCodeExecution.Enabled)
                    target = ModelTarget(client, config)
                    timeout <- LiveProviderSmoke.runTimeout
                    agent <- ReActAgent
                      .builder()
                      .model(target.client)
                      .llmConfig(target.config)
                      .timeout(timeout)
                      .maxIterations(2)
                      .build
                    prompt = provider match
                      case Provider.Anthropic =>
                        "Use web_search once to find the official Scala programming language website. Give a brief answer with a citation."
                      case Provider.Gemini =>
                        "Use Python code execution to calculate 2 + 3, then answer with the result."
                    result <- agent.run(Msg.user(prompt))
                    _ <- ZIO
                      .fail(
                        AgentError.InvalidMessage(
                          "Native smoke requires completed hosted work with inline successful results and no local Tool messages"
                        )
                      )
                      .unless(
                        result.state == AgentRunState.Completed && result.reply.text.trim.nonEmpty &&
                          result.history.exists(_.toolUses.exists(_.isServerTool)) &&
                          result.history
                            .flatMap(_.toolResults)
                            .exists(_.state == ToolResultState.Success) &&
                          !result.history.exists(_.role == Role.Tool)
                      )
                    _ <- ZIO.logInfo(s"Native $provider hosted-tool smoke passed")
                  yield ()
              )
              .provide(Client.default)
          yield ()
