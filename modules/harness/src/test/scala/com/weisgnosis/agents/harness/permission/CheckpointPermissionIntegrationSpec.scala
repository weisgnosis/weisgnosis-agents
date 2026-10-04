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
package com.weisgnosis.agents.harness.permission

import com.weisgnosis.agents.core.agent.*
import com.weisgnosis.agents.core.client.{LlmClient, TurnOutput}
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.event.AgentEvent
import com.weisgnosis.agents.core.model.*
import com.weisgnosis.agents.core.tool.*
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

object CheckpointPermissionIntegrationSpec extends ZIOSpecDefault:
  private enum Admission:
    case Ask, Allow

  def spec: Spec[TestEnvironment & Scope, Any] = suite("Checkpoint permission integration")(
    test(
      "policy admission checkpoints only unresolved calls and confirmed restoration does not repeat reasoning"
    ):
      ZIO
        .foreach(Chunk.fromArray(Admission.values)): admission =>
          for
            executions <- Ref.make(Chunk.empty[(String, Json)])
            requests   <- Ref.make(0)
            raw      = Json.Obj("query" -> Json.Str("original"))
            approved = Json.Obj("query" -> Json.Str("approved"), "tenant" -> Json.Str("host"))
            fixed <- ZIO.fromEither(ToolFixedArguments.make(Json.Obj("tenant" -> Json.Str("host"))))
            tools <- ZIO.foreach(Chunk("read", "blocked", "review"))(name =>
              ZIO
                .fromEither(
                  Tool.withFixedArguments(
                    Tool.make(ToolDef(name, "Read", Json.Obj(), isReadOnly = true))((input: Json) =>
                      executions.update(_ :+ (name -> input)).as(name)
                    ),
                    fixed
                  )
                )
                .map(name -> _)
            )
            toolkit = Toolkit(tools.toMap)
            policy = PermissionEngine(
              Chunk(
                PermissionRule("read", PermissionBehavior.Allow),
                PermissionRule("blocked", PermissionBehavior.Deny)
              ) ++ (if admission == Admission.Allow then
                      Chunk(PermissionRule("review", PermissionBehavior.Allow))
                    else Chunk.empty)
            )
            reply = Msg.assistant(
              Chunk("read", "blocked", "review")
                .map(name => ContentBlock.ToolUse("same", name, raw))
            )
            client = LlmClient.mockEffectful((_, _, _) =>
              requests
                .getAndUpdate(_ + 1)
                .map:
                  case 0 => TurnOutput.ToolCalls(reply)
                  case _ => TurnOutput.FinalText(Msg.assistant("done"))
            )
            agent <- ReActAgent.make(
              config = AgentConfig(toolExecution = ToolExecution.Sequential),
              client = client,
              toolkit = toolkit,
              toolAuthorizer = CheckpointPermissionAuthorizer(ZIO.succeed(policy))
            )
            updates <- agent.streamWithResult(Msg.user("q")).runCollect
            first <- ZIO
              .fromOption(updates.collect { case AgentRunUpdate.Result(result) =>
                result
              }.headOption)
              .orElseFail(AgentError.InvalidConfiguration("Missing run result"))
            before         <- executions.get
            beforeRequests <- requests.get
            result <- admission match
              case Admission.Allow => ZIO.succeed(first)
              case Admission.Ask =>
                for
                  saved <- ZIO.fromEither(
                    first.toJson.fromJson[AgentResult].left.map(AgentError.DeserializationError(_))
                  )
                  choices <- ZIO.fromEither(PendingActing.pendingCalls(saved))
                  _ <- ZIO
                    .fail(AgentError.InvalidConfiguration("Incorrect pending projection"))
                    .unless(
                      choices.map(_.callOrdinal) == Chunk(2) && choices
                        .map(_.call) == Chunk(ContentBlock.ToolUse("same", "review", raw))
                    )
                  authorizer <- ConfirmedBatchAuthorizer.make(
                    saved,
                    Chunk(ConfirmedToolCall(2, ConfirmResult(true, Some(approved)))),
                    ZIO.succeed(policy)
                  )
                  restored <- ReActAgent
                    .make(client = client, toolkit = toolkit, toolAuthorizer = authorizer)
                  _       <- restored.restore(saved)
                  resumed <- restored.resume()
                yield resumed
            done  <- executions.get
            count <- requests.get
            candidate = Json.Obj("query" -> Json.Str("original"), "tenant" -> Json.Str("host"))
            events    = updates.collect { case AgentRunUpdate.Event(event) => event }
          yield assertTrue(
            first.isSuspended == (admission == Admission.Ask),
            beforeRequests == (if admission == Admission.Ask then 1 else 2),
            before.map(_._1) == (if admission == Admission.Ask then Chunk("read")
                                 else Chunk("read", "review")),
            admission != Admission.Ask || (first.reply.generationReason == GenerateReason.PermissionAsking && first.history
              .flatMap(_.toolResults)
              .size == 2),
            admission != Admission.Allow || (!events
              .exists(_.isInstanceOf[AgentEvent.RequestStopEvent]) &&
              !events.exists(_.isInstanceOf[AgentEvent.PermissionAskEvent]) &&
              events.collect {
                case event: AgentEvent.ToolResultEndEvent
                    if event.state == ToolResultState.Success =>
                  event.name
              }.toSet == Set("read", "review")),
            result.state == AgentRunState.Completed,
            result.reply.text == "done",
            count == 2,
            done == Chunk(
              "read"   -> candidate,
              "review" -> (if admission == Admission.Ask then approved else candidate)
            ),
            result.history.flatMap(_.toolResults).map(_.state) == Chunk(
              ToolResultState.Success,
              ToolResultState.Denied,
              ToolResultState.Success
            )
          )
        .map(_.foldLeft(assertTrue(true))(_ && _))
  ) @@ TestAspect.timeout(10.seconds)
