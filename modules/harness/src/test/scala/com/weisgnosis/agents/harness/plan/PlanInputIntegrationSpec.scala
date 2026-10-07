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
package com.weisgnosis.agents.harness.plan

import com.weisgnosis.agents.core.agent.ReActAgent
import com.weisgnosis.agents.core.client.{LlmClient, TurnOutput}
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.model.*
import com.weisgnosis.agents.core.tool.*
import com.weisgnosis.agents.harness.permission.*
import zio.*
import zio.json.ast.Json
import zio.test.*

object PlanInputIntegrationSpec extends ZIOSpecDefault:
  private val system: Msg = Msg.system("canonical system instructions")
  private val allow: PermissionEngine = PermissionEngine(
    Chunk(PermissionRule("*", PermissionBehavior.Allow))
  )

  def spec: Spec[TestEnvironment & Scope, Any] = suite("PlanInputIntegrationSpec")(
    test(
      "successive real requests project pending then approved context without changing canonical history or accumulating reminders"
    ) {
      val user = Msg.user("investigate").withId(Some("user"))
      val call = ContentBlock.ToolUse("read", "read", Json.Obj())
      for
        session  <- PlanSession.make
        draft    <- ZIO.fromEither(PlanDraft.fromString("reviewed draft"))
        _        <- session.control.enter
        _        <- session.control.replaceDraft(draft)
        review   <- session.control.requestReview
        entered  <- Promise.make[Nothing, Unit]
        release  <- Promise.make[Nothing, Unit]
        views    <- Ref.make(Chunk.empty[Chunk[Msg]])
        samples  <- Ref.make(0)
        calls    <- Ref.make(0)
        requests <- PermissionRequests.make
        authorizer <- InteractivePermissionAuthorizer.make(
          requests,
          _ => ZIO.unit,
          allow,
          interceptorGate =
            PlanPermissionGate.make(session.control.mode, Map("read" -> PlanToolAccess.ReadOnly))
        )
        client = LlmClient.mockEffectful((history, _, _) =>
          views.update(_ :+ history) *> calls
            .getAndUpdate(_ + 1)
            .map(index =>
              if index == 0 then TurnOutput.ToolCalls(Msg.assistant(Chunk(call)))
              else TurnOutput.FinalText(Msg.assistant("done"))
            )
        )
        toolkit = Toolkit.single(
          Tool.make(ToolDef("read", "Read", Json.Obj()))(_ =>
            entered.succeed(()) *> release.await.as("observation")
          )
        )
        runtime <- ReActAgent.make(
          client = client,
          toolkit = toolkit,
          initialHistory = Chunk(system),
          toolAuthorizer = authorizer,
          modelInputTransform =
            PlanInputTransform.make(samples.update(_ + 1) *> session.control.snapshot)
        )
        fiber     <- runtime.run(user).forkScoped
        _         <- entered.await
        _         <- session.reviewer.resolve(review, PlanReviewDecision.Approve)
        _         <- release.succeed(())
        _         <- fiber.join
        observed  <- views.get
        count     <- samples.get
        canonical <- runtime.history
      yield assertTrue(
        count == 2,
        observed.size == 2,
        observed
          .lift(0)
          .exists(_.exists(_.text.contains(s"draft revision ${review.revision.value} is pending"))),
        observed
          .lift(1)
          .exists(
            _.exists(
              _.text.contains(s"host approved the exact draft revision ${review.revision.value}")
            )
          ),
        observed.forall(view =>
          view.filter(_.role == Role.System) == Chunk(system) && view.count(
            _.text.contains("<system-reminder>")
          ) == 1
        ),
        canonical.filter(_.role == Role.System) == Chunk(system),
        canonical.filter(_.role == Role.User) == Chunk(user),
        canonical.forall(message =>
          !message.text.contains("<system-reminder>") && !message.metadata.contains(
            "agentscope_synthetic"
          )
        ),
        canonical.flatMap(_.toolUses) == Chunk(call),
        canonical.flatMap(_.toolResults).map(_.text) == Chunk("observation")
      )
    },
    test(
      "mode changes during reasoning are enforced by the live gate even when the earlier request had no reminder"
    ) {
      val call = ContentBlock.ToolUse("write", "write", Json.Obj())
      for
        session  <- PlanSession.make
        views    <- Ref.make(Chunk.empty[Chunk[Msg]])
        calls    <- Ref.make(0)
        executed <- Ref.make(0)
        requests <- PermissionRequests.make
        authorizer <- InteractivePermissionAuthorizer.make(
          requests,
          _ => ZIO.unit,
          allow,
          interceptorGate =
            PlanPermissionGate.make(session.control.mode, Map("write" -> PlanToolAccess.Mutation))
        )
        client = LlmClient.mockEffectful((history, _, _) =>
          views.update(_ :+ history) *> calls
            .getAndUpdate(_ + 1)
            .flatMap(index =>
              if index == 0 then
                session.control.enter.as(TurnOutput.ToolCalls(Msg.assistant(Chunk(call))))
              else ZIO.succeed(TurnOutput.FinalText(Msg.assistant("blocked")))
            )
        )
        toolkit = Toolkit.single(
          Tool.make(ToolDef("write", "Write", Json.Obj()))(_ => executed.update(_ + 1).as("wrong"))
        )
        runtime <- ReActAgent.make(
          client = client,
          toolkit = toolkit,
          toolAuthorizer = authorizer,
          modelInputTransform = PlanInputTransform.make(session.control.snapshot)
        )
        _        <- runtime.run(Msg.user("work"))
        observed <- views.get
        count    <- executed.get
        history  <- runtime.history
      yield assertTrue(
        count == 0,
        observed.lift(0).exists(_.forall(message => !message.text.contains("<system-reminder>"))),
        observed.lift(1).exists(_.exists(_.text.contains("PLAN MODE is active"))),
        history.flatMap(_.toolResults).map(_.state) == Chunk(ToolResultState.Denied)
      )
    },
    test("projection retains core rejection of incomplete tool history before any model request") {
      val dangling = Msg.assistant(Chunk(ContentBlock.ToolUse("unfinished", "read", Json.Obj())))
      for
        session <- PlanSession.make
        _       <- session.control.enter
        calls   <- Ref.make(0)
        client = LlmClient.mockEffectful((_, _, _) =>
          calls.update(_ + 1).as(TurnOutput.FinalText(Msg.assistant("wrong")))
        )
        runtime <- ReActAgent.make(
          client = client,
          initialHistory = Chunk(system, dangling),
          modelInputTransform = PlanInputTransform.make(session.control.snapshot)
        )
        exit    <- runtime.run(Msg.user("q")).exit
        count   <- calls.get
        history <- runtime.history
      yield assertTrue(
        exit.causeOption.exists(_.failures.exists(_.isInstanceOf[AgentError.ModelInputError])),
        count == 0,
        history.filter(_.role == Role.System) == Chunk(system),
        history.forall(message => !message.text.contains("<system-reminder>"))
      )
    }
  )
