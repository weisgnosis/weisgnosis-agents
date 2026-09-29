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

import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.model.ToolResultState
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

object PlanToolsSpec extends ZIOSpecDefault:
  private def prepared: IO[AgentError, PlanSession] = for
    session <- PlanSession.make
    draft   <- ZIO.fromEither(PlanDraft.fromString("original draft"))
    _       <- session.control.enter *> session.control.replaceDraft(draft)
  yield session

  def spec: Spec[TestEnvironment & Scope, Any] = suite("PlanToolsSpec")(
    test("registration is inert and marks all exact controls as mutating and sequential") {
      for
        session   <- PlanSession.make
        published <- Ref.make(0)
        tools = PlanTools.make(session.control, PlanReviewPublisher(_ => published.update(_ + 1)))
        state <- session.control.snapshot
        count <- published.get
      yield assertTrue(
        state == PlanSessionSnapshot.Build(None),
        count == 0,
        tools.tools.keySet == Set("plan_enter", "plan_write", "plan_exit"),
        tools.definitions.forall(definition =>
          !definition.isReadOnly && !definition.isConcurrencySafe
        ),
        PlanTools.access.values.forall(_ == PlanToolAccess.PlanControl)
      )
    },
    test(
      "strict envelopes reject duplicates extras nulls wrong types and blank drafts before mutation"
    ) {
      val bad = Chunk(
        "plan_enter" -> Json.Obj("approval" -> Json.Bool(true)),
        "plan_enter" -> Json.Str("enter"),
        "plan_write" -> Json.Obj(),
        "plan_write" -> Json.Obj("content" -> Json.Null),
        "plan_write" -> Json.Obj("content" -> Json.Str(" ")),
        "plan_write" -> Json.Obj("content" -> Json.Str("a"), "content" -> Json.Str("b")),
        "plan_write" -> Json.Obj("content" -> Json.Str("a"), "path" -> Json.Str("elsewhere")),
        "plan_exit"  -> Json.Obj("summary" -> Json.Null),
        "plan_exit"  -> Json.Obj("summary" -> Json.Num(1)),
        "plan_exit"  -> Json.Obj("summary" -> Json.Str("a"), "summary" -> Json.Str("b")),
        "plan_exit"  -> Json.Obj("approved" -> Json.Bool(true))
      )
      for
        session <- PlanSession.make
        count   <- Ref.make(0)
        tools = PlanTools.make(session.control, PlanReviewPublisher(_ => count.update(_ + 1)))
        results   <- ZIO.foreach(bad)((name, input) => tools.executeSafe(name, input))
        state     <- session.control.snapshot
        published <- count.get
      yield assertTrue(
        results.forall(_.state == ToolResultState.Error),
        published == 0,
        state == PlanSessionSnapshot.Build(None)
      )
    },
    test(
      "entry and exact draft replacement return typed observations without inventing file paths"
    ) {
      val content = "  # Draft\r\n- Step one.\n"
      for
        session <- PlanSession.make
        tools = PlanTools.make(session.control, PlanReviewPublisher(_ => ZIO.unit))
        rejected <- tools.executeSafe("plan_write", Json.Obj("content" -> Json.Str(content)))
        entered  <- tools.executeSafe("plan_enter", Json.Obj())
        written  <- tools.executeSafe("plan_write", Json.Obj("content" -> Json.Str(content)))
        snapshot <- session.control.snapshot
      yield assertTrue(
        rejected.state == ToolResultState.Error,
        entered.output.fromJson[PlanToolResult] == Right(PlanToolResult.EnteredPlan),
        written.output.fromJson[PlanToolResult] == Right(PlanToolResult.DraftReplaced),
        PlanDraft
          .fromString(content)
          .exists(value => snapshot == PlanSessionSnapshot.Planning(Some(value)))
      )
    },
    test("exit waits for a separate host decision and returns its exact approved draft") {
      for
        session   <- prepared
        published <- Promise.make[Nothing, PlanReviewRequest]
        tools = PlanTools.make(
          session.control,
          PlanReviewPublisher(request => published.succeed(request).unit)
        )
        fiber <- tools
          .executeSafe("plan_exit", Json.Obj("summary" -> Json.Str("approve this summary")))
          .forkScoped
        request <- published.await
        pending <- fiber.poll
        before  <- session.control.snapshot
        applied <- session.reviewer.resolve(request.review, PlanReviewDecision.Approve)
        result  <- fiber.join
      yield assertTrue(
        pending.isEmpty,
        request.summary.contains("approve this summary"),
        before == PlanSessionSnapshot.AwaitingReview(request.review.draft, request.review.revision),
        applied == PlanReviewResolution.Applied,
        result.state == ToolResultState.Success,
        result.output.fromJson[PlanToolResult] == Right(
          PlanToolResult.ReviewSettled(
            PlanReviewOutcome.Approved(ApprovedPlan(request.review.draft, request.review.revision))
          )
        )
      )
    },
    test("decline is a delivered observation while mode stays Plan") {
      for
        session   <- prepared
        published <- Promise.make[Nothing, PlanReviewRequest]
        tools = PlanTools.make(
          session.control,
          PlanReviewPublisher(request => published.succeed(request).unit)
        )
        fiber   <- tools.executeSafe("plan_exit", Json.Obj()).forkScoped
        request <- published.await
        _       <- session.reviewer.resolve(request.review, PlanReviewDecision.Decline)
        result  <- fiber.join
        mode    <- session.control.mode
      yield assertTrue(
        request.summary.isEmpty,
        mode == PlanMode.Plan,
        result.state == ToolResultState.Success,
        result.output.fromJson[PlanToolResult] == Right(
          PlanToolResult.ReviewSettled(PlanReviewOutcome.Declined)
        )
      )
    },
    test("supersession settles the ticket but waits for a blocked publisher to finish") {
      for
        session   <- prepared
        published <- Promise.make[Nothing, PlanReviewRequest]
        release   <- Promise.make[Nothing, Unit]
        tools = PlanTools.make(
          session.control,
          PlanReviewPublisher(request => published.succeed(request) *> release.await)
        )
        fiber   <- tools.executeSafe("plan_exit", Json.Obj()).forkScoped
        request <- published.await
        next    <- ZIO.fromEither(PlanDraft.fromString("new draft"))
        _       <- session.control.replaceDraft(next)
        settled <- request.review.await
        pending <- fiber.poll
        stale   <- session.reviewer.resolve(request.review, PlanReviewDecision.Approve)
        _       <- release.succeed(())
        result  <- fiber.join
      yield assertTrue(
        settled == PlanReviewOutcome.Superseded,
        pending.isEmpty,
        stale == PlanReviewResolution.NotCurrent,
        result.output.fromJson[PlanToolResult] == Right(
          PlanToolResult.ReviewSettled(PlanReviewOutcome.Superseded)
        )
      )
    },
    test("exit interruption closes publisher resources and cancels only its pending review") {
      for
        session   <- prepared
        published <- Promise.make[Nothing, PlanReviewRequest]
        cleaned   <- Ref.make(false)
        publisher = PlanReviewPublisher(request =>
          ZIO.acquireRelease(ZIO.unit)(_ => cleaned.set(true)) *> published.succeed(request).unit
        )
        fiber <- PlanTools
          .make(session.control, publisher)
          .executeSafe("plan_exit", Json.Obj())
          .forkScoped
        request <- published.await
        exit    <- fiber.interrupt
        outcome <- request.review.await
        stale   <- session.reviewer.resolve(request.review, PlanReviewDecision.Approve)
        cleanup <- cleaned.get
        mode    <- session.control.mode
      yield assertTrue(
        exit.isInterrupted,
        outcome == PlanReviewOutcome.Cancelled,
        stale == PlanReviewResolution.NotCurrent,
        cleanup,
        mode == PlanMode.Plan
      )
    },
    test("publisher failure becomes an error observation and invalidates its current ticket") {
      val error = AgentError.PlanStorageError("publisher unavailable")
      for
        session   <- prepared
        published <- Promise.make[Nothing, PlanReviewRequest]
        cleaned   <- Ref.make(false)
        publisher = PlanReviewPublisher(request =>
          ZIO.acquireRelease(ZIO.unit)(_ => cleaned.set(true)) *> published.succeed(request) *> ZIO
            .fail(error)
        )
        result  <- PlanTools.make(session.control, publisher).executeSafe("plan_exit", Json.Obj())
        request <- published.await
        outcome <- request.review.await
        mode    <- session.control.mode
        cleanup <- cleaned.get
      yield assertTrue(
        result.state == ToolResultState.Error,
        result.output.contains(error.message),
        outcome == PlanReviewOutcome.Cancelled,
        mode == PlanMode.Plan,
        cleanup
      )
    },
    test("failure after host approval cannot roll back already committed approval") {
      val error = AgentError.PlanStorageError("delivery acknowledgement lost")
      for
        session   <- prepared
        published <- Promise.make[Nothing, PlanReviewRequest]
        release   <- Promise.make[Nothing, Unit]
        publisher = PlanReviewPublisher(request =>
          published.succeed(request) *> release.await *> ZIO.fail(error)
        )
        fiber <- PlanTools
          .make(session.control, publisher)
          .executeSafe("plan_exit", Json.Obj())
          .forkScoped
        request  <- published.await
        _        <- session.reviewer.resolve(request.review, PlanReviewDecision.Approve)
        _        <- release.succeed(())
        result   <- fiber.join
        snapshot <- session.control.snapshot
      yield assertTrue(
        result.state == ToolResultState.Error,
        snapshot == PlanSessionSnapshot.Build(
          Some(ApprovedPlan(request.review.draft, request.review.revision))
        )
      )
    },
    test("stored write failure is an error observation and leaves no reviewable draft") {
      val error = AgentError.PlanStorageError("store unavailable")
      val backend = new PlanDraftStore:
        def read: IO[AgentError, Option[PlanDraft]]       = ZIO.none
        def write(draft: PlanDraft): IO[AgentError, Unit] = ZIO.fail(error)
      for
        session <- PlanSession.stored(backend)
        tools = PlanTools.make(session.control, PlanReviewPublisher(_ => ZIO.unit))
        result <- tools.executeSafe("plan_write", Json.Obj("content" -> Json.Str("candidate")))
        review <- tools.executeSafe("plan_exit", Json.Obj())
        state  <- session.control.snapshot
      yield assertTrue(
        result.state == ToolResultState.Error,
        result.output.contains(error.message),
        review.state == ToolResultState.Error,
        state == PlanSessionSnapshot.Planning(None)
      )
    }
  )
