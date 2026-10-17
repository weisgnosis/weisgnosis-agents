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

import com.weisgnosis.agents.core.agent.{AgentRunState, StructuredOutput}
import com.weisgnosis.agents.core.client.{LlmClient, LlmConfig, TurnOutput}
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.event.{EventPublisher, ModelStreamContext}
import com.weisgnosis.agents.core.model.*
import com.weisgnosis.agents.core.tool.ToolDef
import zio.*
import zio.json.ast.Json
import zio.test.*

object LiveProviderProfilesSpec extends ZIOSpecDefault:
  private enum Response:
    case Valid, NativeRejected, WrongValue, ExtraNativeField, MissingHold

  private def client(
      response: Response,
      requests: Ref[Chunk[(Chunk[String], LlmConfig, ModelStreamContext)]]
  ): LlmClient = new LlmClient:
    def streamTurn(
        history: Chunk[Msg],
        tools: Chunk[ToolDef],
        config: LlmConfig,
        hub: EventPublisher,
        context: ModelStreamContext
    ): ZIO[Scope, AgentError, TurnOutput] =
      requests.update(_ :+ ((tools.map(_.name), config, context))) *>
        (if config.responseFormat.nonEmpty then
           response match
             case Response.NativeRejected =>
               ZIO.fail(AgentError.ModelError(400, "native unsupported"))
             case Response.WrongValue =>
               ZIO.succeed(TurnOutput.FinalText(Msg.assistant("{\"value\":8}")))
             case Response.ExtraNativeField =>
               ZIO.succeed(TurnOutput.FinalText(Msg.assistant("{\"value\":7,\"unexpected\":true}")))
             case _ => ZIO.succeed(TurnOutput.FinalText(Msg.assistant("{\"value\":7}")))
         else if tools.exists(_.name == StructuredOutput.ToolName) then
           ZIO.succeed(
             TurnOutput.ToolCalls(
               Msg.assistant(
                 Chunk(
                   ContentBlock.ToolUse(
                     "synthetic",
                     StructuredOutput.ToolName,
                     Json.Obj("response" -> Json.Obj("value" -> Json.Num(7)))
                   )
                 )
               )
             )
           )
         else if response == Response.MissingHold then
           ZIO.succeed(TurnOutput.FinalText(Msg.assistant("I did not call the tool")))
         else
           ZIO.succeed(
             TurnOutput.ToolCalls(
               Msg.assistant(
                 Chunk(
                   ContentBlock.ToolUse("held", "profile_hold", Json.Obj())
                 )
               )
             )
           ))

  def spec: Spec[TestEnvironment & Scope, Any] = suite("LiveProviderProfilesSpec")(
    test(
      "actual sequential ReAct profiles enforce exact structured output and join interrupted tool cleanup"
    ):
      for
        requests <- Ref.make(Chunk.empty[(Chunk[String], LlmConfig, ModelStreamContext)])
        report <- LiveProviderProfiles.exercise(
          client(Response.Valid, requests),
          LlmConfig("offline-profile", maxTokens = Some(4096))
        )
        observed <- requests.get
        cancellation = report.cancellation
        selectedRequests <- Ref.make(Chunk.empty[(Chunk[String], LlmConfig, ModelStreamContext)])
        _ <- LiveProviderProfiles.exerciseProfile(
          client(Response.NativeRejected, selectedRequests),
          LlmConfig("offline-profile"),
          LiveProviderProfiles.Profile.Synthetic
        )
        _ <- LiveProviderProfiles.exerciseProfile(
          client(Response.NativeRejected, selectedRequests),
          LlmConfig("offline-profile"),
          LiveProviderProfiles.Profile.Cancellation
        )
        selected <- selectedRequests.get
      yield assertTrue(
        selected.map(_._1) == Chunk(Chunk(StructuredOutput.ToolName), Chunk("profile_hold")),
        selected.forall(_._2.responseFormat.isEmpty),
        report.native.state == AgentRunState.Completed,
        report.synthetic.state == AgentRunState.Completed,
        report.native.reply.getStructuredData[LiveProviderProfiles.NumberResult] == Right(
          LiveProviderProfiles.NumberResult(7)
        ),
        report.synthetic.reply.getStructuredData[LiveProviderProfiles.NumberResult] == Right(
          LiveProviderProfiles.NumberResult(7)
        ),
        observed.size == 3,
        observed.headOption.exists(_._2.responseFormat.nonEmpty),
        observed.lift(1).exists(_._1 == Chunk(StructuredOutput.ToolName)),
        observed.lastOption.exists(_._1 == Chunk("profile_hold")),
        observed.forall(_._2.maxTokens.contains(4096)),
        observed.flatMap(_._3.invocation).distinct.size == 3,
        cancellation.exit.isInterrupted,
        cancellation.cleanupJoined,
        cancellation.executions == 1,
        cancellation.history.flatMap(_.toolResults).map(_.state) == Chunk(ToolResultState.Error),
        cancellation.history.lastOption.exists(
          _.text == "I noticed that you have interrupted me. What can I do for you?"
        )
      )
    ,
    test(
      "unsupported native, incorrect value and missing tool cannot be reported as passed profiles"
    ):
      for checks <- ZIO.foreach(
          Chunk(
            Response.NativeRejected,
            Response.WrongValue,
            Response.ExtraNativeField,
            Response.MissingHold
          )
        ): response =>
          for
            requests <- Ref.make(Chunk.empty[(Chunk[String], LlmConfig, ModelStreamContext)])
            outcome <- LiveProviderProfiles
              .exercise(client(response, requests), LlmConfig("offline-profile"))
              .either
            observed <- requests.get
          yield assertTrue(
            response match
              case Response.NativeRejected =>
                outcome == Left(
                  AgentError.ModelError(400, "native unsupported")
                ) && observed.size == 1
              case Response.WrongValue | Response.ExtraNativeField =>
                outcome.left.exists {
                  case AgentError.DeserializationError(message) =>
                    message.startsWith("Structured output violates the requested schema:")
                  case _ => false
                } && observed.size == 1
              case Response.MissingHold =>
                outcome == Left(
                  AgentError.InvalidMessage("Cancellation profile did not enter profile_hold")
                ) && observed.size == 3
              case Response.Valid => false
          )
      yield checks.foldLeft(assertTrue(true))(_ && _)
  ) @@ TestAspect.timeout(20.seconds)
