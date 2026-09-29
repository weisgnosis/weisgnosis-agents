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

import com.weisgnosis.agents.core.agent.{AgentConfig, ReActAgent, ToolExecution}
import com.weisgnosis.agents.core.client.{LlmClient, TurnOutput}
import com.weisgnosis.agents.core.event.AgentEvent
import com.weisgnosis.agents.core.model.*
import com.weisgnosis.agents.core.tool.*
import com.weisgnosis.agents.harness.eviction.WorkspaceTrust
import com.weisgnosis.agents.harness.memory.MemoryTestSupport.temporaryDirectory
import com.weisgnosis.agents.harness.permission.*
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

object PlanToolsIntegrationSpec extends ZIOSpecDefault:
  private def flow(decision: PlanReviewDecision): ZIO[Scope, Throwable, TestResult] =
    val calls = Chunk(
      ContentBlock.ToolUse("enter", "plan_enter", Json.Obj()),
      ContentBlock.ToolUse(
        "draft",
        "plan_write",
        Json.Obj("content" -> Json.Str("# Exact plan\nInspect, then apply."))
      ),
      ContentBlock.ToolUse("before", "mutate", Json.Obj()),
      ContentBlock.ToolUse("review", "plan_exit", Json.Obj("summary" -> Json.Str("model summary"))),
      ContentBlock.ToolUse("after", "mutate", Json.Obj())
    )
    for
      root           <- temporaryDirectory
      store          <- LocalPlanDraftStore.open(root, WorkspaceTrust.HostControlled)
      session        <- PlanSession.stored(store)
      published      <- Promise.make[Nothing, PlanReviewRequest]
      mutations      <- Ref.make(0)
      genericPrompts <- Ref.make(0)
      controls = PlanTools.make(
        session.control,
        PlanReviewPublisher(request => published.succeed(request).unit)
      )
      toolkit <- ZIO.fromEither(
        controls.register(
          Tool.make(
            ToolDef(
              "mutate",
              "Perform the separately permitted action",
              Json.Obj(),
              isConcurrencySafe = true
            )
          )(_ => mutations.update(_ + 1).as("performed"))
        )
      )
      requests <- PermissionRequests.make
      authorizer <- InteractivePermissionAuthorizer.make(
        requests,
        _ => genericPrompts.update(_ + 1),
        PermissionEngine(Chunk(PermissionRule("*", PermissionBehavior.Allow))),
        interceptorGate = PlanPermissionGate.make(
          session.control.mode,
          PlanTools.access.updated("mutate", PlanToolAccess.Mutation)
        )
      )
      client <- LlmClient.mock(
        Chunk(
          TurnOutput.ToolCalls(Msg.assistant(calls)),
          TurnOutput.FinalText(Msg.assistant("finished"))
        )
      )
      runtime <- ReActAgent.make(
        client = client,
        toolkit = toolkit,
        toolAuthorizer = authorizer,
        modelInputTransform = PlanInputTransform.make(session.control.snapshot),
        config = AgentConfig(toolExecution = ToolExecution.Parallel)
      )
      fiber           <- runtime.streamEvents(Msg.user("prepare a plan")).runCollect.forkScoped
      request         <- published.await
      before          <- runtime.history
      beforeMutations <- mutations.get
      pending         <- fiber.poll
      saved           <- store.read
      applied         <- session.reviewer.resolve(request.review, decision)
      events          <- fiber.join
      history         <- runtime.history
      executed        <- mutations.get
      prompts         <- genericPrompts.get
      mode            <- session.control.mode
      results = history.flatMap(_.toolResults)
      expectedOutcome = decision match
        case PlanReviewDecision.Approve =>
          PlanReviewOutcome.Approved(ApprovedPlan(request.review.draft, request.review.revision))
        case PlanReviewDecision.Decline => PlanReviewOutcome.Declined
      expectedFinal = decision match
        case PlanReviewDecision.Approve => ToolResultState.Success
        case PlanReviewDecision.Decline => ToolResultState.Denied
    yield assertTrue(
      before.flatMap(_.toolUses) == calls,
      before.flatMap(_.toolResults).map(_.id) == Chunk("enter", "draft", "before"),
      beforeMutations == 0,
      pending.isEmpty,
      saved.contains(request.review.draft),
      request.review.draft.content == "# Exact plan\nInspect, then apply.",
      applied == PlanReviewResolution.Applied,
      prompts == 0,
      executed == (if decision == PlanReviewDecision.Approve then 1 else 0),
      mode == (if decision == PlanReviewDecision.Approve then PlanMode.Build else PlanMode.Plan),
      results.map(_.id) == calls.map(_.id),
      results.map(_.state) == Chunk(
        ToolResultState.Success,
        ToolResultState.Success,
        ToolResultState.Denied,
        ToolResultState.Success,
        expectedFinal
      ),
      results
        .find(_.id == "review")
        .exists(
          _.text.fromJson[PlanToolResult] ==
            Right(PlanToolResult.ReviewSettled(expectedOutcome))
        ),
      events.collect { case event: AgentEvent.ToolResultEndEvent => event.callId -> event.state } ==
        results.map(result => result.id -> result.state)
    )

  def spec: Spec[TestEnvironment & Scope, Any] = suite("PlanToolsIntegrationSpec")(
    test("Allow rules and parallel configuration preserve exact host review before execution") {
      flow(PlanReviewDecision.Approve)
    },
    test("host decline leaves later execution denied without corrupting calls results or events") {
      flow(PlanReviewDecision.Decline)
    }
  )
