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

object PartialConfirmationSpec extends ZIOSpecDefault:
  private enum FinalDecision:
    case Approve, Deny
  private val config: AgentConfig        = AgentConfig(toolExecution = ToolExecution.Sequential)
  private def input(value: String): Json = Json.Obj("value" -> Json.Str(value))
  private def call(value: String): ContentBlock.ToolUse =
    ContentBlock.ToolUse("same", "read", input(value))
  private def toolkit(run: Json => UIO[String]): Toolkit =
    Toolkit(Map("read" -> Tool.make(ToolDef("read", "Read", Json.Obj(), isReadOnly = true))(run)))
  private def decoded(result: AgentResult): IO[AgentError, AgentResult] =
    ZIO.fromEither(result.toJson.fromJson[AgentResult].left.map(AgentError.DeserializationError(_)))

  def spec: Spec[TestEnvironment & Scope, Any] = suite("Partial confirmations")(
    test(
      "partial restored confirmations preserve original admission and share latest activation with deferred controls"
    ):
      ZIO
        .foreach(Chunk.fromArray(FinalDecision.values)): decision =>
          for
            executions <- Ref.make(Chunk.empty[Json])
            processed  <- Ref.make(Chunk.empty[String])
            calls      <- Ref.make(0)
            hooks      <- Ref.make(0)
            groups <- ZIO.fromEither(
              ToolGroups.empty.register(
                ToolGroup(
                  "extra",
                  "Extra",
                  ToolGroupActivation.Active,
                  ToolGroupScope.Meta,
                  Set("read")
                )
              )
            )
            tools = toolkit(value => executions.update(_ :+ value).as("read output"))
            deactivate = ContentBlock
              .ToolUse("deactivate", "reset_equipped_tools", Json.Obj("to_activate" -> Json.Arr()))
            activate = ContentBlock.ToolUse(
              "activate",
              "reset_equipped_tools",
              Json.Obj("to_activate" -> Json.Arr(Json.Str("extra")))
            )
            server = ContentBlock.ToolUse("remote", "remote", Json.Obj()).withServerTool(true)
            reply = Msg.assistant(
              Chunk(
                deactivate,
                call("first"),
                server,
                ContentBlock.ToolResult.text("remote", "remote", "inline"),
                activate,
                call("last")
              )
            )
            expected =
              if decision == FinalDecision.Approve then
                Chunk(input("first approved"), input("last approved"))
              else Chunk(input("first approved"))
            client = LlmClient.mockEffectful((_, definitions, _) =>
              calls
                .getAndUpdate(_ + 1)
                .flatMap:
                  case 0 => ZIO.succeed(TurnOutput.ToolCalls(reply))
                  case _ =>
                    executions.get.flatMap(done =>
                      if done == expected && definitions.exists(_.name == "read") then
                        ZIO.succeed(TurnOutput.FinalText(Msg.assistant("done")))
                      else
                        ZIO.fail(
                          AgentError.InvalidConfiguration(
                            "Replayed work, early reasoning, or detached activation"
                          )
                        )
                    )
            )
            processor = ToolResultProcessor(result =>
              processed
                .update(_ :+ result.name.getOrElse("missing"))
                .as(ToolResultPayload.fromResult(result))
            )
            hook = PostReasoningHook((_, _) => hooks.update(_ + 1).as(ReasoningDecision.Keep))
            initial <- ReActAgent.make(
              config = config,
              client = client,
              toolkit = tools,
              toolGroups = Some(groups),
              toolResultProcessor = processor,
              postReasoningHook = hook,
              toolAuthorizer = ToolAuthorizer(request =>
                ZIO.succeed(
                  if request.call.id == "deactivate" then
                    ToolAuthorization.Approved(request.call.input)
                  else ToolAuthorization.Deferred("approval required")
                )
              )
            )
            updates <- initial.streamWithResult(Msg.user("q")).runCollect
            first <- ZIO
              .fromOption(updates.collect { case AgentRunUpdate.Result(result) =>
                result
              }.headOption)
              .orElseFail(AgentError.InvalidConfiguration("Missing partial checkpoint"))
            saved        <- decoded(first)
            firstChoices <- ZIO.fromEither(PendingActing.pendingCalls(saved))
            partialAuth <- ConfirmedBatchAuthorizer.makePartial(
              saved,
              Chunk(ConfirmedToolCall(1, ConfirmResult(true, Some(input("first approved"))))),
              ZIO.succeed(PermissionEngine())
            )
            partial <- ReActAgent.make(
              config = config,
              client = client,
              toolkit = tools,
              toolGroups = Some(groups),
              toolResultProcessor = processor,
              postReasoningHook = hook,
              toolAuthorizer = partialAuth
            )
            _              <- partial.restore(saved)
            second         <- partial.resume()
            secondSaved    <- decoded(second)
            secondChoices  <- ZIO.fromEither(PendingActing.pendingCalls(secondSaved))
            before         <- executions.get
            requestsBefore <- calls.get
            finalAuth <- ConfirmedBatchAuthorizer.makePartial(
              secondSaved,
              Chunk(
                ConfirmedToolCall(3, ConfirmResult(true)),
                ConfirmedToolCall(
                  4,
                  ConfirmResult(
                    decision == FinalDecision.Approve,
                    Some(input("last approved")),
                    reason = Some("declined")
                  )
                )
              ),
              ZIO.succeed(PermissionEngine())
            )
            finalHost <- ReActAgent.make(
              config = config,
              client = client,
              toolkit = tools,
              toolGroups = Some(groups),
              toolResultProcessor = processor,
              postReasoningHook = hook,
              toolAuthorizer = finalAuth
            )
            _            <- finalHost.restore(secondSaved)
            result       <- finalHost.resume()
            done         <- executions.get
            observations <- processed.get
            requests     <- calls.get
            hookCount    <- hooks.get
            events = updates.collect { case AgentRunUpdate.Event(event) => event }
          yield assertTrue(
            first.isSuspended,
            second.isSuspended,
            first.reply.generationReason == GenerateReason.PermissionAsking,
            events.collect { case event: AgentEvent.ToolResultStartEvent =>
              event.callId
            }.toSet == Set("deactivate", "remote"),
            firstChoices.map(_.callOrdinal) == Chunk(1, 3, 4),
            secondChoices.map(_.callOrdinal) == Chunk(3, 4),
            before == Chunk(input("first approved")),
            requestsBefore == 1,
            done == expected,
            requests == 2,
            hookCount == 2,
            result.reply.text == "done",
            result.iterationCount == 2,
            observations.count(_ == "remote") == 1,
            observations.count(_ == "reset_equipped_tools") == 2,
            observations.count(_ == "read") == 2,
            first.history.count(_.role == Role.Tool) == 1,
            second.history.count(_.role == Role.Tool) == 2,
            result.history.count(_.role == Role.Tool) == 4,
            result.history
              .flatMap(_.toolResults)
              .count(_.state == ToolResultState.Denied) == (if decision == FinalDecision.Deny then 1
                                                            else 0)
          )
        .map(_.foldLeft(assertTrue(true))(_ && _))
    ,
    test(
      "an earlier stop survives partial checkpoints and is applied only after the remaining call resolves"
    ):
      for
        executions <- Ref.make(Chunk.empty[Json])
        processed  <- Ref.make(Chunk.empty[String])
        calls      <- Ref.make(0)
        tools = toolkit(value => executions.update(_ :+ value).as(value.toJson))
        reply = Msg.assistant(Chunk(call("first"), call("last")))
        client = LlmClient.mockEffectful((_, _, _) =>
          calls.update(_ + 1).as(TurnOutput.ToolCalls(reply))
        )
        processor = ToolResultProcessor.deciding(result =>
          processed
            .update(_ :+ result.text)
            .as(
              ToolResultDecision.Stop(
                ToolResultPayload.fromResult(result),
                if result.text.contains("first") then "" else "later"
              )
            )
        )
        initial <- ReActAgent.make(
          config = config.copy(toolExecution = ToolExecution.Parallel),
          client = client,
          toolkit = tools,
          toolResultProcessor = processor,
          toolAuthorizer = ToolAuthorizer(request =>
            ZIO.succeed(
              if request.callOrdinal.contains(0) then ToolAuthorization.Approved(request.call.input)
              else ToolAuthorization.Deferred("later approval")
            )
          )
        )
        first <- initial.run(Msg.user("q"))
        saved <- decoded(first)
        auth <- ConfirmedBatchAuthorizer.makePartial(
          saved,
          Chunk(ConfirmedToolCall(1, ConfirmResult(true))),
          ZIO.succeed(PermissionEngine())
        )
        restored <- ReActAgent.make(
          config = config.copy(toolExecution = ToolExecution.Parallel),
          client = client,
          toolkit = tools,
          toolResultProcessor = processor,
          toolAuthorizer = auth
        )
        _            <- restored.restore(saved)
        result       <- restored.resume()
        done         <- executions.get
        observations <- processed.get
        count        <- calls.get
      yield assertTrue(
        first.isSuspended,
        result.state == AgentRunState.Stopped(""),
        count == 1,
        done == Chunk(input("first"), input("last")),
        observations.size == 2,
        result.history.flatMap(_.toolResults).size == 2
      )
    ,
    test(
      "cancellation of restored approval preserves completed observations and reconciles only remaining calls"
    ):
      ZIO.scoped:
        for
          entered    <- Promise.make[Nothing, Unit]
          cleaning   <- Promise.make[Nothing, Unit]
          release    <- Promise.make[Nothing, Unit]
          executions <- Ref.make(Chunk.empty[Json])
          calls      <- Ref.make(0)
          tools = toolkit(value => executions.update(_ :+ value).as("completed first"))
          client = LlmClient.mockEffectful((_, _, _) =>
            calls
              .update(_ + 1)
              .as(TurnOutput.ToolCalls(Msg.assistant(Chunk(call("first"), call("last")))))
          )
          initial <- ReActAgent.make(
            config = config,
            client = client,
            toolkit = tools,
            toolAuthorizer = ToolAuthorizer(request =>
              ZIO.succeed(
                if request.callOrdinal.contains(0) then
                  ToolAuthorization.Approved(request.call.input)
                else ToolAuthorization.Deferred("approval")
              )
            )
          )
          first <- initial.run(Msg.user("q"))
          saved <- decoded(first)
          defect = new IllegalStateException("approval cleanup failed")
          gate = PermissionGate((_, _) =>
            ZIO.addFinalizer(cleaning.succeed(()) *> release.await *> ZIO.die(defect)) *>
              entered.succeed(()) *> ZIO.never
          )
          auth <- ConfirmedBatchAuthorizer.makePartial(
            saved,
            Chunk(ConfirmedToolCall(1, ConfirmResult(true))),
            ZIO.succeed(PermissionEngine()),
            gate
          )
          restored <- ReActAgent.make(
            config = config,
            client = client,
            toolkit = tools,
            toolAuthorizer = auth
          )
          _ <- restored.restore(saved)
          checked <- (for
            fiber    <- restored.resume().forkScoped
            _        <- entered.await
            _        <- ZIO.fiberId.flatMap(fiber.interruptAsFork)
            _        <- cleaning.await
            waiting  <- fiber.poll
            _        <- release.succeed(())
            exit     <- fiber.await
            history  <- restored.history
            repeated <- restored.resume().exit
            done     <- executions.get
            count    <- calls.get
          yield assertTrue(
            waiting.isEmpty,
            exit.causeOption.exists(cause => cause.isInterrupted && cause.defects.contains(defect)),
            history
              .flatMap(_.toolResults)
              .filter(_.state == ToolResultState.Success)
              .map(_.text) == Chunk("completed first"),
            history.flatMap(_.toolResults).count(_.state == ToolResultState.Error) == 1,
            history.count(_.generationReason == GenerateReason.Interrupted) == 1,
            done == Chunk(input("first")),
            count == 1,
            repeated.isFailure
          )).ensuring(release.succeed(()).unit)
        yield checked
  ) @@ TestAspect.timeout(20.seconds)
