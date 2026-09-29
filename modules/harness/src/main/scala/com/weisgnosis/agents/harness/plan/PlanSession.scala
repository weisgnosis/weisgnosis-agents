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
import zio.*
import zio.stm.*

/** Model-facing planning capabilities; no method grants execution authority. */
trait PlanSessionControl:
  def location: Option[PlanLocation] = None
  def context: UIO[PlanContext]      = snapshot.map(current => PlanContext(current, location))
  def snapshot: UIO[PlanSessionSnapshot]
  def mode: UIO[PlanMode] = snapshot.map(_.mode)
  def enter: UIO[Unit]
  def replaceDraft(draft: PlanDraft): IO[AgentError, Unit]
  def requestReview: ZIO[Scope, AgentError.InvalidMessage, PlanReview]

/** Host-only decision authority; authenticated user delivery is the host's responsibility. */
trait PlanReviewAuthority:
  def resolve(review: PlanReview, decision: PlanReviewDecision): UIO[PlanReviewResolution]

/** Scoped identity capability, not a serialized approval credential. */
final class PlanReview private[plan] (
    val draft: PlanDraft,
    val revision: PlanRevision,
    private[plan] val completion: TPromise[Nothing, PlanReviewOutcome]
):
  def await: UIO[PlanReviewOutcome] = completion.await.commit

final class PlanSession private (
    val control: PlanSessionControl,
    val reviewer: PlanReviewAuthority
)

object PlanSession:
  private enum DraftPersistence:
    case Memory
    case Stored(store: PlanDraftStore)

  private enum Status:
    case Build(approved: Option[ApprovedPlan])
    case Planning(current: Option[PlanDraft])
    case Awaiting(review: PlanReview)
    case Publishing

    def draft: Option[PlanDraft] = this match
      case Build(approved)   => approved.map(_.draft)
      case Planning(current) => current
      case Awaiting(review)  => Some(review.draft)
      case Publishing        => None

    def snapshot: PlanSessionSnapshot = this match
      case Build(approved)  => PlanSessionSnapshot.Build(approved)
      case Planning(draft)  => PlanSessionSnapshot.Planning(draft)
      case Awaiting(review) => PlanSessionSnapshot.AwaitingReview(review.draft, review.revision)
      case Publishing       => PlanSessionSnapshot.Planning(None)

    def supersede: USTM[Unit] = this match
      case Awaiting(review) => review.completion.succeed(PlanReviewOutcome.Superseded).unit
      case _                => STM.unit

  final private case class State(revision: PlanRevision, status: Status)

  def make: UIO[PlanSession] = create(Status.Build(None), DraftPersistence.Memory, None)

  /** Existing stored content starts unapproved in Plan mode. The host retains the store's scope. */
  def stored(store: PlanDraftStore): IO[AgentError, PlanSession] =
    ZIO.suspendSucceed:
      val location = store.location
      store.read.flatMap(draft =>
        create(Status.Planning(draft), DraftPersistence.Stored(store), location)
      )

  private def create(
      initial: Status,
      persistence: DraftPersistence,
      configuredLocation: Option[PlanLocation]
  ): UIO[PlanSession] =
    for
      state     <- TRef.make(State(PlanRevision.Initial, initial)).commit
      mutations <- Semaphore.make(1)
    yield
      val control = new PlanSessionControl:
        override val location: Option[PlanLocation] = configuredLocation
        def snapshot: UIO[PlanSessionSnapshot]      = state.get.map(_.status.snapshot).commit

        def enter: UIO[Unit] = mutations.withPermit((for
          current <- state.get
          _       <- current.status.supersede
          _       <- state.set(State(current.revision.next, Status.Planning(current.status.draft)))
        yield ()).commit)

        def replaceDraft(draft: PlanDraft): IO[AgentError, Unit] = mutations.withPermit:
          persistence match
            case DraftPersistence.Memory => prepare(state, Status.Planning(Some(draft))).commit
            case DraftPersistence.Stored(store) => publish(state, store, draft)

        def requestReview: ZIO[Scope, AgentError.InvalidMessage, PlanReview] =
          mutations.withPermit(
            ZIO.acquireRelease(register(state).commit)(review => cancel(state, review).commit)
          )

      val reviewer = new PlanReviewAuthority:
        def resolve(review: PlanReview, decision: PlanReviewDecision): UIO[PlanReviewResolution] =
          mutations.withPermit((for
            current <- state.get
            resolution <- current.status match
              case Status.Awaiting(pending) if pending eq review =>
                val (status, outcome) = decision match
                  case PlanReviewDecision.Approve =>
                    val approved = ApprovedPlan(review.draft, review.revision)
                    Status.Build(Some(approved)) -> PlanReviewOutcome.Approved(approved)
                  case PlanReviewDecision.Decline =>
                    Status.Planning(Some(review.draft)) -> PlanReviewOutcome.Declined
                state.set(State(current.revision.next, status)) *>
                  review.completion.succeed(outcome).as(PlanReviewResolution.Applied)
              case _ => STM.succeed(PlanReviewResolution.NotCurrent)
          yield resolution).commit)

      new PlanSession(control, reviewer)

  private def prepare(state: TRef[State], next: Status): STM[AgentError.InvalidMessage, Unit] =
    for
      current <- state.get
      _ <- current.status match
        case Status.Build(_) =>
          STM.fail(AgentError.InvalidMessage("Enter Plan mode before replacing its draft"))
        case _ =>
          current.status.supersede *> state.set(State(current.revision.next, next))
    yield ()

  private def publish(
      state: TRef[State],
      store: PlanDraftStore,
      draft: PlanDraft
  ): IO[AgentError, Unit] =
    ZIO.checkInterruptible: interruptibility =>
      ZIO.uninterruptibleMask: restore =>
        for
          _      <- prepare(state, Status.Publishing).commit
          result <- restore(ZIO.suspendSucceed(store.write(draft))).exit
          _ <- result match
            case Exit.Success(_) =>
              state
                .update(current => State(current.revision.next, Status.Planning(Some(draft))))
                .commit
            case Exit.Failure(cause) =>
              state.update(current => State(current.revision.next, Status.Planning(None))).commit *>
                ZIO.refailCause(cause)
          _ <- ZIO.allowInterrupt.when(interruptibility.isInterruptible)
        yield ()

  private def register(state: TRef[State]): STM[AgentError.InvalidMessage, PlanReview] =
    for
      current <- state.get
      draft <- current.status match
        case Status.Planning(Some(draft)) => STM.succeed(draft)
        case Status.Awaiting(review)      => STM.succeed(review.draft)
        case _ =>
          STM.fail(
            AgentError.InvalidMessage("A concrete draft in Plan mode is required for review")
          )
      completion <- TPromise.make[Nothing, PlanReviewOutcome]
      review = new PlanReview(draft, current.revision.next, completion)
      _ <- current.status.supersede
      _ <- state.set(State(review.revision, Status.Awaiting(review)))
    yield review

  private def cancel(state: TRef[State], review: PlanReview): USTM[Unit] =
    for
      current <- state.get
      _ <- current.status match
        case Status.Awaiting(pending) if pending eq review =>
          state.set(State(current.revision.next, Status.Planning(Some(review.draft)))) *>
            review.completion.succeed(PlanReviewOutcome.Cancelled).unit
        case _ => STM.unit
    yield ()
