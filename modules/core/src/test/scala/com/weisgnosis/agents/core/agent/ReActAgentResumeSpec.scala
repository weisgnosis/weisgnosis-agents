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
package com.weisgnosis.agents.core.agent

import com.weisgnosis.agents.core.client.{LlmClient, TurnOutput}
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.event.AgentEvent
import com.weisgnosis.agents.core.model.{ChatUsage, ContentBlock, Msg, Role, ToolResultState}
import com.weisgnosis.agents.core.tool.*
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

object ReActAgentResumeSpec extends ZIOSpecDefault:
  private enum Host:
    case Original, Restored

  private enum Boundary:
    case Native, Synthetic, Text, ThinkingOnly, BlankReason

  private val pause: PostReasoningHook = PostReasoningHook((_, message) =>
    ZIO.succeed(
      if message.toolUses.nonEmpty then ReasoningDecision.Suspend("review")
      else ReasoningDecision.Keep
    )
  )

  private def call(name: String): ContentBlock.ToolUse =
    ContentBlock.ToolUse("repeated", name, Json.Obj())

  private def tool(name: String, run: UIO[String]): Tool[Any, Json, String] =
    Tool.make(ToolDef(name, "Read", Json.Obj()))(_ => run)

  def spec: Spec[TestEnvironment & Scope, Any] = suite("ReActAgent resume")(
    test(
      "suspends before acting and resumes the exact mixed batch once on the original or restored host"
    ):
      ZIO
        .foreach(Chunk.fromArray(Host.values)): host =>
          ZIO.scoped:
            for
              modelCalls   <- Ref.make(0)
              executed     <- Ref.make(Chunk.empty[String])
              processed    <- Ref.make(Chunk.empty[String])
              authorized   <- Ref.make(0)
              scopesClosed <- Ref.make(0)
              hooks        <- Ref.make(0)
              earlier = Chunk(
                Msg.user("earlier"),
                Msg.assistant(Chunk(call("one"))),
                Msg.tool("repeated", "one", "previous")
              )
              inline = ContentBlock.ToolResult.text("repeated", "remote", "inline")
              reply = Msg
                .assistant(
                  Chunk(call("one"), call("remote").withServerTool(true), inline, call("two"))
                )
                .withUsage(Some(ChatUsage(5, 7)))
              client = LlmClient.mockEffectful((_, _, _) =>
                ZIO.addFinalizer(scopesClosed.update(_ + 1)) *> modelCalls
                  .getAndUpdate(_ + 1)
                  .flatMap:
                    case 0 => ZIO.succeed(TurnOutput.ToolCalls(reply))
                    case _ =>
                      executed.get.flatMap(done =>
                        if done == Chunk("one", "two") then
                          ZIO.succeed(
                            TurnOutput
                              .FinalText(Msg.assistant("done").withUsage(Some(ChatUsage(2, 3))))
                          )
                        else
                          ZIO.fail(
                            AgentError
                              .InvalidConfiguration("Model called before the saved batch completed")
                          )
                      )
              )
              toolkit = Toolkit(
                Map(
                  "one"    -> tool("one", executed.update(_ :+ "one").as("one")),
                  "two"    -> tool("two", executed.update(_ :+ "two").as("two")),
                  "remote" -> tool("remote", executed.update(_ :+ "unexpected remote").as("remote"))
                )
              )
              hook = PostReasoningHook((context, message) =>
                hooks.update(_ + 1) *> pause(context, message)
              )
              processor = ToolResultProcessor(result =>
                processed
                  .update(_ :+ result.name.getOrElse("missing"))
                  .as(ToolResultPayload.fromResult(result))
              )
              authorizer = ToolAuthorizer(request =>
                authorized.update(_ + 1).as(ToolAuthorization.Approved(request.call.input))
              )
              original <- ReActAgent.make(
                config = AgentConfig(toolExecution = ToolExecution.Sequential),
                client = client,
                toolkit = toolkit,
                initialHistory = earlier,
                postReasoningHook = hook,
                toolResultProcessor = processor,
                toolAuthorizer = authorizer
              )
              updates <- original.streamWithResult(Msg.user("run")).runCollect
              pending <- ZIO
                .fromOption(updates.collect { case AgentRunUpdate.Result(result) =>
                  result
                }.headOption)
                .orElseFail(AgentError.InvalidConfiguration("Missing suspended result"))
              beforeCalls         <- modelCalls.get
              beforeExecutions    <- executed.get
              beforeProcessing    <- processed.get
              beforeAuthorization <- authorized.get
              beforeClosed        <- scopesClosed.get
              blocked             <- original.run(Msg.user("must not enter history")).exit
              untouched           <- original.history
              decoded <- ZIO.fromEither(
                pending.toJson.fromJson[AgentResult].left.map(AgentError.DeserializationError(_))
              )
              selected <- host match
                case Host.Original => ZIO.succeed(original)
                case Host.Restored =>
                  ReActAgent
                    .make(
                      config = AgentConfig(toolExecution = ToolExecution.Sequential),
                      client = client,
                      toolkit = toolkit,
                      postReasoningHook = hook,
                      toolResultProcessor = processor,
                      toolAuthorizer = authorizer
                    )
                    .tap(_.restore(decoded))
              result    <- selected.resume()
              repeated  <- selected.resume().exit
              calls     <- modelCalls.get
              done      <- executed.get
              observed  <- processed.get
              admitted  <- authorized.get
              hookCalls <- hooks.get
              events = updates.collect { case AgentRunUpdate.Event(event) => event }
            yield assertTrue(
              pending.isSuspended,
              pending.reply.content == reply.content,
              pending.reply.usage == reply.usage,
              beforeCalls == 1,
              beforeExecutions.isEmpty,
              beforeProcessing.isEmpty,
              beforeAuthorization == 0,
              beforeClosed == 1,
              events.count(_.isInstanceOf[AgentEvent.RequestStopEvent]) == 1,
              !events.exists(_.isInstanceOf[AgentEvent.ToolResultStartEvent]),
              blocked.isFailure,
              untouched == pending.history,
              repeated.isFailure,
              calls == 2,
              hookCalls == 2,
              admitted == 2,
              done == Chunk("one", "two"),
              observed.sorted == Chunk("one", "remote", "two"),
              result.reply.text == "done",
              result.iterationCount == 2,
              result.usage.contains(ChatUsage(7, 10)),
              result.history.take(earlier.size) == earlier,
              result.history.count(_.role == Role.Tool) == 3,
              result.history.flatMap(_.toolResults).count(_.name.contains("remote")) == 1
            )
        .map(_.foldLeft(assertTrue(true))(_ && _))
    ,
    test("restores Meta activation but retains fresh host definitions and current authorization"):
      ZIO.scoped:
        for
          modelCalls <- Ref.make(0)
          executions <- Ref.make(0)
          hidden  = tool("hidden", executions.update(_ + 1).as("hidden"))
          toolkit = Toolkit(Map("hidden" -> hidden))
          groups <- ZIO.fromEither(
            ToolGroups.empty.register(
              ToolGroup(
                "extra",
                "Extra",
                ToolGroupActivation.Inactive,
                ToolGroupScope.Meta,
                Set("hidden")
              )
            )
          )
          client = LlmClient.mockEffectful((_, definitions, _) =>
            modelCalls
              .getAndUpdate(_ + 1)
              .flatMap:
                case 0 =>
                  ZIO.succeed(
                    TurnOutput.ToolCalls(
                      Msg.assistant(
                        Chunk(
                          ContentBlock.ToolUse(
                            "activate",
                            "reset_equipped_tools",
                            Json.Obj("to_activate" -> Json.Arr(Json.Str("extra")))
                          )
                        )
                      )
                    )
                  )
                case 1 =>
                  if definitions.exists(_.name == "hidden") then
                    ZIO.succeed(
                      TurnOutput.ToolCalls(
                        Msg.assistant(
                          Chunk(
                            call("hidden")
                              .copy(metadata = Map("thoughtSignature" -> Json.Str("opaque")))
                          )
                        )
                      )
                    )
                  else ZIO.fail(AgentError.InvalidConfiguration("Active tool missing"))
                case _ => ZIO.succeed(TurnOutput.FinalText(Msg.assistant("done")))
          )
          hook = PostReasoningHook((_, message) =>
            ZIO.succeed(
              if message.toolUses.exists(_.name == "hidden") then
                ReasoningDecision.Suspend("review")
              else ReasoningDecision.Keep
            )
          )
          original <- ReActAgent.make(
            client = client,
            toolkit = toolkit,
            toolGroups = Some(groups),
            postReasoningHook = hook
          )
          pending <- original.run(Msg.user("activate"))
          encoded <- ZIO.fromEither(
            pending.toJson.fromJson[AgentResult].left.map(AgentError.DeserializationError(_))
          )
          bounded <- ReActAgent.make(client = client, toolkit = toolkit, toolGroups = Some(groups))
          excessive      <- bounded.restore(encoded.copy(iterationCount = Int.MaxValue)).exit
          boundedHistory <- bounded.history
          changed <- ReActAgent.make(
            client = client,
            toolkit = Toolkit(
              Map(
                "hidden" -> Tool.make(hidden.definition.copy(description = "Changed"))(_ =>
                  ZIO.succeed("bad")
                )
              )
            ),
            toolGroups = Some(groups)
          )
          rejected <- changed.restore(encoded).exit
          empty    <- changed.history
          restored <- ReActAgent.make(
            client = client,
            toolkit = toolkit,
            toolGroups = Some(groups),
            toolAuthorizer = ToolAuthorizer(_ =>
              ZIO.succeed(ToolAuthorization.Approved(Json.Obj("changed" -> Json.Bool(true))))
            )
          )
          _                  <- restored.restore(encoded)
          again              <- restored.restore(encoded).exit
          result             <- restored.resume()
          calls              <- modelCalls.get
          count              <- executions.get
          unexpectedRequests <- Ref.make(0)
          atLimit <- ReActAgent.make(
            config = AgentConfig(maxIterations = Int.MaxValue),
            client = LlmClient.mockEffectful((_, _, _) =>
              unexpectedRequests.update(_ + 1).as(TurnOutput.FinalText(Msg.assistant("unexpected")))
            ),
            toolkit = toolkit,
            toolGroups = Some(groups)
          )
          _       <- atLimit.restore(encoded.copy(iterationCount = Int.MaxValue))
          limited <- atLimit.resume()
          extra   <- unexpectedRequests.get
        yield assertTrue(
          excessive.isFailure,
          boundedHistory.isEmpty,
          limited.iterationCount == Int.MaxValue,
          extra == 0,
          limited.reply.metadata
            .get(Msg.MetadataGenerateReason)
            .contains(Json.Str("max_iterations")),
          pending.isSuspended,
          pending.iterationCount == 2,
          rejected.isFailure,
          empty.isEmpty,
          again.isFailure,
          calls == 3,
          count == 0,
          result.iterationCount == 3,
          result.history
            .flatMap(_.toolResults)
            .exists(result =>
              result.name.contains("hidden") && result.state == ToolResultState.Denied
            ),
          result.history.flatMap(_.toolUses).find(_.name == "hidden").exists(_.input == Json.Obj())
        )
    ,
    test(
      "resumes inline-only batches without another model call and reset discards a pending batch"
    ):
      for
        calls     <- Ref.make(0)
        processed <- Ref.make(0)
        message = Msg
          .assistant(
            Chunk(
              call("remote").withServerTool(true),
              ContentBlock.ToolResult.text("repeated", "remote", "inline")
            )
          )
          .withMetadata("host_note", Json.Str("preserved"))
        client = LlmClient.mockEffectful((_, _, _) =>
          calls.update(_ + 1).as(TurnOutput.ToolCalls(message))
        )
        original <- ReActAgent.make(
          client = client,
          postReasoningHook = pause,
          toolResultProcessor = ToolResultProcessor(result =>
            processed.update(_ + 1).as(ToolResultPayload.fromResult(result))
          )
        )
        pending <- original.run(Msg.user("inline"))
        restored <- ReActAgent.make(
          client = client,
          toolResultProcessor = ToolResultProcessor(result =>
            processed.update(_ + 1).as(ToolResultPayload.fromResult(result))
          )
        )
        _            <- restored.restore(pending)
        completed    <- restored.resume()
        _            <- original.resetHistory
        cleared      <- original.history
        missing      <- original.resume().exit
        count        <- calls.get
        observations <- processed.get
      yield assertTrue(
        pending.isSuspended,
        completed.state == AgentRunState.Completed,
        completed.reply.content == message.content,
        completed.reply.metadata.get("host_note").contains(Json.Str("preserved")),
        completed.reply.metadata.get(Msg.MetadataGenerateReason).contains(Json.Str("tool_calls")),
        count == 1,
        observations == 1,
        completed.history.forall(_.role != Role.Tool),
        cleared.isEmpty,
        missing.isFailure
      )
    ,
    test("rejects unsupported suspension boundaries without persisting a pending assistant"):
      ZIO
        .foreach(Chunk.fromArray(Boundary.values)): boundary =>
          for
            client <- LlmClient.mock(
              Chunk(
                if boundary == Boundary.Text then TurnOutput.FinalText(Msg.assistant("text"))
                else if boundary == Boundary.ThinkingOnly then
                  TurnOutput.ThinkingOnly(Msg.assistant(Chunk(ContentBlock.Thinking("reasoning"))))
                else TurnOutput.ToolCalls(Msg.assistant(Chunk(call("one"))))
              )
            )
            hook = PostReasoningHook((_, _) =>
              ZIO.succeed(
                ReasoningDecision.Suspend(
                  if boundary == Boundary.BlankReason then " " else "review"
                )
              )
            )
            agent <- ReActAgent.make(client = client, postReasoningHook = hook)
            exit <- (boundary match
              case Boundary.Native =>
                agent
                  .runStructured[Int](
                    Chunk(Msg.user("q")),
                    Json.Obj("type" -> Json.Str("integer")),
                    StructuredMode.Native
                  )
                  .unit
              case Boundary.Synthetic =>
                agent
                  .runStructured[Int](
                    Chunk(Msg.user("q")),
                    Json.Obj("type" -> Json.Str("integer")),
                    StructuredMode.SyntheticTool
                  )
                  .unit
              case _ => agent.run(Msg.user("q")).unit
            ).exit
            history <- agent.history
            resumed <- agent.resume().exit
          yield assertTrue(
            exit.causeOption
              .exists(_.failures.exists(_.isInstanceOf[AgentError.ReasoningHookError])),
            history.forall(_.role != Role.Assistant),
            resumed.isFailure
          )
        .map(_.foldLeft(assertTrue(true))(_ && _))
    ,
    test(
      "cancelling resumed execution consumes its checkpoint and joins cleanup before reconciliation"
    ):
      ZIO.scoped:
        for
          entered    <- Promise.make[Nothing, Unit]
          cleaning   <- Promise.make[Nothing, Unit]
          release    <- Promise.make[Nothing, Unit]
          calls      <- Ref.make(0)
          executions <- Ref.make(0)
          defect = new IllegalStateException("tool cleanup failed")
          blocked = tool(
            "one",
            executions.update(_ + 1) *> entered.succeed(()) *> ZIO.never.ensuring(
              cleaning.succeed(()) *> release.await *> ZIO.die(defect)
            )
          )
          client = LlmClient.mockEffectful((_, _, _) =>
            calls.update(_ + 1).as(TurnOutput.ToolCalls(Msg.assistant(Chunk(call("one")))))
          )
          agent <- ReActAgent.make(
            client = client,
            toolkit = Toolkit(Map("one" -> blocked)),
            postReasoningHook = pause
          )
          suspended <- agent.run(Msg.user("q"))
          checked <- (for
            fiber    <- agent.resume().forkScoped
            _        <- entered.await
            _        <- ZIO.fiberId.flatMap(fiber.interruptAsFork)
            _        <- cleaning.await
            pending  <- fiber.poll
            _        <- release.succeed(())
            exit     <- fiber.await
            history  <- agent.history
            replay   <- agent.resume().exit
            count    <- executions.get
            requests <- calls.get
          yield assertTrue(
            suspended.isSuspended,
            pending.isEmpty,
            exit.isInterrupted,
            exit.causeOption.exists(_.defects.contains(defect)),
            replay.isFailure,
            count == 1,
            requests == 1,
            history.flatMap(_.toolResults).exists(_.state == ToolResultState.Error),
            history.count(
              _.metadata.get(Msg.MetadataGenerateReason).contains(Json.Str("interrupted"))
            ) == 1
          )).ensuring(release.succeed(()).unit)
        yield checked
  ) @@ TestAspect.timeout(20.seconds)
