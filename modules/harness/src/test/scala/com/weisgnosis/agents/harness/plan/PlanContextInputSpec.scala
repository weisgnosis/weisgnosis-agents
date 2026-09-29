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
import com.weisgnosis.agents.core.event.ModelStreamContext
import com.weisgnosis.agents.core.model.*
import com.weisgnosis.agents.harness.eviction.WorkspaceTrust
import com.weisgnosis.agents.harness.memory.MemoryTestSupport.temporaryDirectory
import com.weisgnosis.agents.harness.permission.*
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

object PlanContextInputSpec extends ZIOSpecDefault:
  private val request: ModelStreamContext = ModelStreamContext("reply", "request")

  def spec: Spec[TestEnvironment & Scope, Any] = suite("PlanContextInputSpec")(
    test(
      "one lazy context sample supplies location without altering canonical or System messages"
    ) {
      val system  = Msg.system("system")
      val user    = Msg.user("question").withId(Some("original"))
      val history = Chunk(system, user)
      for
        location <- ZIO.fromEither(PlanLocation.fromString("configured/PLAN.md"))
        samples  <- Ref.make(0)
        context   = PlanContext(PlanSessionSnapshot.Planning(None), Some(location))
        transform = PlanInputTransform.fromContext(samples.update(_ + 1).as(context))
        effect    = transform(request, history)
        before <- samples.get
        view   <- effect
        after  <- samples.get
      yield assertTrue(
        before == 0,
        after == 1,
        view.size == history.size,
        view.filter(_.role == Role.System) == Chunk(system),
        view.lastOption.exists(message =>
          message.id == user.id && message.text.contains(location.text.toJson)
        ),
        history.lastOption.contains(user),
        user.text == "question"
      )
    },
    test(
      "legacy snapshot input remains location-free and fresh Build context returns exact history"
    ) {
      val history = Chunk(Msg.user("question"))
      for
        root    <- temporaryDirectory
        store   <- LocalPlanDraftStore.open(root, WorkspaceTrust.HostControlled)
        session <- PlanSession.stored(store)
        legacy  <- PlanInputTransform.make(session.control.snapshot)(request, history)
        current <- PlanInputTransform.fromContext(session.control.context)(request, history)
        fresh <- PlanInputTransform.fromContext(
          ZIO.succeed(PlanContext(PlanSessionSnapshot.Build(None), store.location))
        )(request, history)
      yield assertTrue(
        legacy.forall(message => !message.text.contains("Configured draft destination")),
        current.exists(_.text.contains("Configured draft destination")),
        fresh == history
      )
    },
    test(
      "real model requests retain configured and approved location without changing canonical history"
    ) {
      val system = Msg.system("canonical system")
      val user   = Msg.user("prepare a plan")
      val calls = Chunk(
        ContentBlock
          .ToolUse("write", "plan_write", Json.Obj("content" -> Json.Str("# Reviewed plan"))),
        ContentBlock.ToolUse("review", "plan_exit", Json.Obj())
      )
      for
        root      <- temporaryDirectory
        store     <- LocalPlanDraftStore.open(root, WorkspaceTrust.HostControlled)
        session   <- PlanSession.stored(store)
        published <- Promise.make[Nothing, PlanReviewRequest]
        views     <- Ref.make(Chunk.empty[Chunk[Msg]])
        count     <- Ref.make(0)
        samples   <- Ref.make(0)
        client = LlmClient.mockEffectful((history, _, _) =>
          views.update(_ :+ history) *> count
            .getAndUpdate(_ + 1)
            .map(index =>
              if index == 0 then TurnOutput.ToolCalls(Msg.assistant(calls))
              else TurnOutput.FinalText(Msg.assistant("done"))
            )
        )
        approvals <- PermissionRequests.make
        authorizer <- InteractivePermissionAuthorizer.make(
          approvals,
          _ => ZIO.unit,
          PermissionEngine(Chunk(PermissionRule("*", PermissionBehavior.Allow))),
          interceptorGate = PlanPermissionGate.make(session.control.mode, PlanTools.access)
        )
        runtime <- ReActAgent.make(
          client = client,
          toolkit = PlanTools
            .make(session.control, PlanReviewPublisher(value => published.succeed(value).unit)),
          initialHistory = Chunk(system),
          toolAuthorizer = authorizer,
          modelInputTransform =
            PlanInputTransform.fromContext(samples.update(_ + 1) *> session.control.context)
        )
        fiber     <- runtime.run(user).forkScoped
        review    <- published.await
        _         <- session.reviewer.resolve(review.review, PlanReviewDecision.Approve)
        _         <- fiber.join
        observed  <- views.get
        sampled   <- samples.get
        canonical <- runtime.history
      yield assertTrue(
        sampled == 2,
        observed.size == 2,
        observed.lift(0).exists(_.exists(_.text.contains("Configured draft destination"))),
        observed.lift(1).exists(_.exists(_.text.contains("BUILD MODE"))),
        observed
          .lift(1)
          .exists(_.exists(_.text.contains("last successful session read or publication"))),
        store.location.exists(location =>
          observed.forall(_.exists(_.text.contains(location.text.toJson)))
        ),
        observed.forall(_.filter(_.role == Role.System) == Chunk(system)),
        canonical.filter(_.role == Role.System) == Chunk(system),
        canonical.filter(_.role == Role.User) == Chunk(user),
        canonical.flatMap(_.toolUses) == calls,
        canonical.flatMap(_.toolResults).size == 2,
        canonical.forall(message =>
          !message.text.contains("Configured draft destination") &&
            !message.text.contains("last successful session read or publication")
        )
      )
    }
  )
