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
package com.weisgnosis.agents.core.tool

import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.agent.ReActAgent
import com.weisgnosis.agents.core.client.{LlmClient, TurnOutput}
import com.weisgnosis.agents.core.model.{ContentBlock, Msg, ToolResultState}
import zio.*
import zio.json.ast.Json
import zio.test.*

/**
 * Selected upstream scenario suite for ToolkitTest.java from AgentScope Java.
 */
object ToolkitSpec extends ZIOSpecDefault:

  private val dummyTool1: Tool[Any, Json, String] =
    Tool.make(ToolDef("calc", "Performs arithmetic", Json.Obj()))(_ => ZIO.succeed("result"))

  private val dummyTool2: Tool[Any, Json, String] =
    Tool.make(ToolDef("search", "Performs web search", Json.Obj(), isReadOnly = true))(_ =>
      ZIO.succeed("found")
    )

  def spec: Spec[TestEnvironment & Scope, Any] = suite("ToolkitSpec (AgentScope Java Parity)")(
    test("local dispatch observation follows normalization and excludes missing/external bindings"):
      for
        states <- Ref.make(Chunk.empty[(String, ToolResultState)])
        observer = ToolDispatchObserver((name, _) =>
          ZIO.succeed(
            ToolDispatchCompletion(outcome =>
              outcome match
                case Exit.Success(state) => states.update(_ :+ (name -> state))
                case Exit.Failure(_)     => ZIO.unit
            )
          )
        )
        broken = new Tool[Any, Json, String]:
          def definition: ToolDef               = ToolDef("broken", "Encoding failure", Json.Obj())
          def execute(input: Json): UIO[String] = ZIO.succeed("output")
          def decodeInput(raw: Json): Either[String, Json] = Right(raw)
          def encodeOutput(out: String): String            = out
          override def encodeResultChecked(out: String): Either[AgentError, ToolExecutionResult] =
            Left(AgentError.InvalidMessage("Rejected output"))
        external <- ZIO.fromEither(
          ToolBinding.external[Json](ToolDef("external", "Host dispatch", Json.Obj()))
        )
        toolkit = Toolkit
          .single(dummyTool1)
          .withDispatchObserver(observer)
          .updated(broken)
          .overlay(Toolkit.single(external))
          .filterNames(_ => true)
        success  <- toolkit.executeSafe("calc", Json.Obj())
        encoded  <- toolkit.executeSafe("broken", Json.Obj())
        missing  <- toolkit.executeSafe("missing", Json.Obj())
        supplied <- toolkit.executeSafe("external", Json.Obj())
        observed <- states.get
      yield assertTrue(
        success.state == ToolResultState.Success,
        encoded.state == ToolResultState.Error,
        missing.state == ToolResultState.Error,
        supplied.state == ToolResultState.Error,
        observed == Chunk("calc" -> ToolResultState.Success, "broken" -> ToolResultState.Error)
      )
    ,
    test(
      "dispatch cancellation joins tool cleanup before completion and closes observer scopes inside out"
    ):
      for
        entered <- Promise.make[Nothing, Unit]
        order   <- Ref.make(Chunk.empty[String])
        observer = ToolDispatchObserver((_, _) =>
          ZIO.addFinalizer(order.update(_ :+ "observer closed")) *>
            ZIO.succeed(
              ToolDispatchCompletion(outcome =>
                order.update(_ :+ (if outcome.isInterrupted then "interrupted" else "unexpected"))
              )
            )
        )
        tool = Tool.make(ToolDef("held", "Wait for cancellation", Json.Obj()))(_ =>
          (entered.succeed(()) *> ZIO.never).ensuring(order.update(_ :+ "tool closed"))
        )
        fiber <- Toolkit
          .single(tool)
          .withDispatchObserver(observer)
          .executeSafe("held", Json.Obj())
          .forkScoped
        _        <- entered.await
        exit     <- fiber.interrupt
        observed <- order.get
      yield assertTrue(
        exit.isInterrupted,
        observed == Chunk("tool closed", "interrupted", "observer closed")
      )
    ,
    test("cancellation during observer cleanup preserves controller and cleanup defect"):
      ZIO.scoped:
        for
          cleaning <- Promise.make[Nothing, Unit]
          release  <- Promise.make[Nothing, Unit]
          defect = new IllegalStateException("dispatch observer cleanup")
          observer = ToolDispatchObserver((_, _) =>
            ZIO
              .addFinalizer(cleaning.succeed(()) *> release.await *> ZIO.die(defect))
              .as(ToolDispatchCompletion.ignore)
          )
          active <- ZIO.uninterruptibleMask: restore =>
            for
              active <- restore(
                Toolkit
                  .single(dummyTool1)
                  .withDispatchObserver(observer)
                  .executeSafe("calc", Json.Obj())
              ).forkScoped
              _ <- ZIO.addFinalizer(release.succeed(()).unit)
            yield active
          _          <- cleaning.await
          controller <- ZIO.fiberId
          _          <- active.interruptAsFork(controller)
          before     <- active.poll
          _          <- release.succeed(())
          exit       <- active.await
          cause = exit.causeOption.getOrElse(Cause.empty)
        yield assertTrue(
          before.isEmpty,
          cause.interruptors.contains(controller),
          cause.defects.toList == List(defect)
        )
    ,
    test("Map bindings cannot execute a different handler under an approved name"):
      for
        executed <- Ref.make(Chunk.empty[String])
        approved <- Ref.make(Chunk.empty[String])
        allowed = Tool.make(ToolDef("allowed", "Allowed handler", Json.Obj()))(_ =>
          executed.update(_ :+ "allowed").as("allowed")
        )
        forbidden = Tool.make(ToolDef("forbidden", "Forbidden handler", Json.Obj()))(_ =>
          executed.update(_ :+ "forbidden").as("forbidden")
        )
        swapped = Toolkit.fromMap(Map("allowed" -> forbidden, "forbidden" -> allowed))
        aliased = Toolkit.fromMap(Map("alias" -> allowed))
        canonical <- ZIO.fromEither(
          Toolkit.fromMap(Map("allowed" -> allowed, "forbidden" -> forbidden))
        )
        model <- LlmClient.mock(
          Chunk(
            TurnOutput.ToolCalls(
              Msg.assistant(Chunk(ContentBlock.ToolUse("call", "allowed", Json.Obj())))
            ),
            TurnOutput.FinalText(Msg.assistant("done"))
          )
        )
        agent <- ReActAgent
          .builder()
          .model(model)
          .toolkit(canonical)
          .toolAuthorizer(
            ToolAuthorizer(request =>
              approved
                .update(_ :+ request.call.name)
                .as(
                  if request.call.name == "allowed" then
                    ToolAuthorization.Approved(request.call.input)
                  else ToolAuthorization.Denied("Not approved")
                )
            )
          )
          .build
        _        <- ZIO.scoped(agent.run(Msg.user("Use allowed")))
        handlers <- executed.get
        names    <- approved.get
      yield assertTrue(
        swapped.isLeft,
        aliased.isLeft,
        names == Chunk("allowed"),
        handlers == Chunk("allowed"),
        canonical.definitions.forall(definition =>
          canonical.resolve(definition.name).exists(_.definition == definition)
        )
      )
    ,
    test("Register tools and lookup by name"):
      val tk = Toolkit.single(dummyTool1).updated(dummyTool2)

      assertTrue(
        tk.size == 2,
        !tk.isEmpty,
        tk.contains("calc"),
        tk.contains("search"),
        !tk.contains("unknown"),
        tk.resolve("calc").isDefined,
        tk.resolve("calc").exists(_.definition.name == "calc"),
        tk.definitions.map(_.name).toSet == Set("calc", "search")
      )
    ,
    test("Reject duplicate tool registrations via register method"):
      val tk1    = Toolkit.single(dummyTool1)
      val result = tk1.register(dummyTool1)

      assertTrue(
        result.isLeft,
        result.left.exists(_.message.contains("Tool collision"))
      )
    ,
    test("Merge two Toolkits via ++"):
      val tk1    = Toolkit.single(dummyTool1)
      val tk2    = Toolkit.single(dummyTool2)
      val merged = tk1 ++ tk2
      val replacement = Tool.make(ToolDef("calc", "Replacement handler", Json.Obj()))(_ =>
        ZIO.succeed("replacement")
      )

      assertTrue(
        merged.exists(_.size == 2),
        merged.exists(_.contains("calc")),
        merged.exists(_.contains("search")),
        (tk1 ++ tk1).isLeft,
        tk1.overlay(tk2).filterNames(_ == "search") == tk2,
        tk1.updated(dummyTool2).resolve("calc").contains(ToolBinding.Local(dummyTool1)),
        tk1.updated(replacement).resolve("calc").contains(ToolBinding.Local(replacement)),
        tk1
          .overlay(Toolkit.single(replacement))
          .resolve("calc")
          .contains(ToolBinding.Local(replacement)),
        (tk1 ++ Toolkit.single(replacement)).isLeft
      )
  )
