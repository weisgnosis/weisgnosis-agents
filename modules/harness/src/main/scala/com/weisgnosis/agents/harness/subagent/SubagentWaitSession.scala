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
package com.weisgnosis.agents.harness.subagent

import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.tool.Toolkit
import zio.*

enum SubagentExternalWorkState:
  case Idle, Running

trait SubagentExternalWorkProbe:
  def inspect: IO[AgentError, SubagentExternalWorkState]

object SubagentExternalWorkProbe:
  val idle: SubagentExternalWorkProbe = new SubagentExternalWorkProbe:
    def inspect: IO[AgentError, SubagentExternalWorkState] =
      ZIO.succeed(SubagentExternalWorkState.Idle)
  def apply(probe: => IO[AgentError, SubagentExternalWorkState]): SubagentExternalWorkProbe =
    new SubagentExternalWorkProbe:
      def inspect: IO[AgentError, SubagentExternalWorkState] = ZIO.suspendSucceed(probe)

enum SubagentWaitRequest:
  case InboxAny
  case Tasks(selection: SubagentTaskSelection)

enum SubagentWaitOutcome:
  case Barrier(result: SubagentTaskBarrierResult)
  case InboxArrived, NoTasks, AllTerminal, BudgetExhausted
  case InboxTimedOut(emptyWaits: Int)

/** One trusted host session; close cancels and joins only its waits, not tasks or the borrowed inbox. */
final class SubagentWaitSession private (
    registry: Option[SubagentTaskRegistry],
    inbox: SubagentInbox,
    externalWork: SubagentExternalWorkProbe,
    state: Ref[SubagentWaitSession.State],
    gate: Semaphore,
    closing: Promise[Nothing, Unit]
):
  import SubagentWaitSession.*

  def toolkit: Toolkit = SubagentTools.waitSessionTools(this)

  def waitFor(
      request: SubagentWaitRequest,
      timeout: SubagentTaskWaitTimeout = SubagentTaskWaitTimeout.fromSeconds()
  ): IO[AgentError, SubagentWaitOutcome] = ZIO.scoped:
    for
      deadline <- Promise.make[Nothing, Unit]
      _        <- (ZIO.sleep(timeout.duration) *> deadline.succeed(())).forkScoped
      entered  <- bounded(gate.withPermitScoped, deadline)
      outcome <- entered match
        case None => ZIO.succeed(admissionTimedOut)
        case Some(_) =>
          ensureOpen *> (request match
            case SubagentWaitRequest.InboxAny         => legacy(deadline)
            case SubagentWaitRequest.Tasks(selection) => barrier(selection, deadline))
    yield outcome

  private def bounded[R, A](
      effect: ZIO[R, AgentError, A],
      deadline: Promise[Nothing, Unit]
  ): ZIO[R, AgentError, Option[A]] =
    SubagentTaskBarrier.raceWithSignal(
      effect,
      deadline.await.as(Exit.unit).raceFirst(closing.await.as(Exit.fail(closed)))
    )

  private def ensureOpen: IO[AgentError, Unit] = state.get.flatMap:
    case State.Closed => ZIO.fail(closed)
    case _            => ZIO.unit

  private def reset: IO[AgentError, Unit] = state
    .modify:
      case State.Open(_) => (Right(()), State.Open(Budget.Fresh))
      case State.Closed  => (Left(closed), State.Closed)
    .flatMap(ZIO.fromEither(_))

  private def charge: IO[AgentError, Int] = state
    .modify:
      case State.Open(Budget.Fresh)                   => (Right(1), State.Open(Budget.Once))
      case State.Open(Budget.Once | Budget.Exhausted) => (Right(2), State.Open(Budget.Exhausted))
      case State.Closed                               => (Left(closed), State.Closed)
    .flatMap(ZIO.fromEither(_))

  private def exhausted: IO[AgentError, Boolean] = state.get.flatMap:
    case State.Open(budget) => ZIO.succeed(budget == Budget.Exhausted)
    case State.Closed       => ZIO.fail(closed)

  private def arrival: IO[AgentError, SubagentWaitOutcome] =
    reset.as(SubagentWaitOutcome.InboxArrived)

  private def barrier(
      selection: SubagentTaskSelection,
      deadline: Promise[Nothing, Unit]
  ): IO[AgentError, SubagentWaitOutcome] =
    val admission = ZIO
      .fromOption(registry)
      .orElseFail(
        AgentError.InvalidConfiguration("Task wait barriers require a task registry")
      )
      .flatMap(_.captureWait(selection))
      .flatMap(entries => ZIO.foreach(entries)(entry => entry.poll.map(entry -> _)))
    bounded(admission, deadline).flatMap:
      case None => ZIO.succeed(admissionTimedOut)
      case Some(entries) if entries.isEmpty =>
        reset.as(SubagentWaitOutcome.Barrier(SubagentTaskBarrierResult.Empty))
      case Some(entries) if entries.forall(_._2.nonEmpty) =>
        reset.as(
          SubagentWaitOutcome.Barrier(SubagentTaskBarrierResult.Completed(entries.flatMap(_._2)))
        )
      case Some(entries) =>
        bounded(
          exhausted.flatMap(value => if value then inbox.snapshot.map(Some(_)) else ZIO.none),
          deadline
        ).flatMap:
          case None => ZIO.succeed(admissionTimedOut)
          case Some(Some(snapshot)) if snapshot.presence == SubagentInboxPresence.Pending => arrival
          case Some(Some(_)) => ZIO.succeed(SubagentWaitOutcome.BudgetExhausted)
          case Some(None) =>
            deadline.poll.flatMap:
              case Some(_) => ZIO.succeed(admissionTimedOut)
              case None =>
                for
                  completed <- Ref.make(entries.collect { case (entry, Some(_)) => entry.id }.toSet)
                  result <- bounded(
                    SubagentTaskBarrier.awaitAll(entries)((entry, ready) =>
                      ready
                        .fold(entry.await)(ZIO.succeed(_))
                        .tap(_ => completed.update(_ + entry.id))
                    ),
                    deadline
                  )
                  outcome <- result match
                    case Some(records) =>
                      reset.as(
                        SubagentWaitOutcome.Barrier(SubagentTaskBarrierResult.Completed(records))
                      )
                    case None =>
                      charge *> completed.get.map(done =>
                        SubagentWaitOutcome.Barrier(
                          SubagentTaskBarrierResult.TimedOut(
                            entries.map(_._1.id),
                            entries.map(_._1.id).filterNot(done.contains)
                          )
                        )
                      )
                yield outcome
  private def legacy(deadline: Promise[Nothing, Unit]): IO[AgentError, SubagentWaitOutcome] =
    bounded(inbox.snapshot.zip(exhausted), deadline).flatMap:
      case None => ZIO.succeed(admissionTimedOut)
      case Some((snapshot, _)) if snapshot.presence == SubagentInboxPresence.Pending => arrival
      case Some((_, true)) => ZIO.succeed(SubagentWaitOutcome.BudgetExhausted)
      case Some((snapshot, false)) =>
        val admission = externalWork.inspect.flatMap:
          case SubagentExternalWorkState.Running => ZIO.none
          case SubagentExternalWorkState.Idle =>
            registry match
              case None => ZIO.none
              case Some(tasks) =>
                tasks.waitWorkState.map:
                  case SubagentWaitWorkState.Empty       => Some(SubagentWaitOutcome.NoTasks)
                  case SubagentWaitWorkState.AllTerminal => Some(SubagentWaitOutcome.AllTerminal)
                  case SubagentWaitWorkState.Running     => None
        bounded(admission, deadline).flatMap:
          case None => ZIO.succeed(admissionTimedOut)
          case Some(Some(result)) =>
            bounded(snapshot.arrivalState, deadline).flatMap:
              case None                                => ZIO.succeed(admissionTimedOut)
              case Some(SubagentInboxArrival.Arrived)  => arrival
              case Some(SubagentInboxArrival.Awaiting) => reset.as(result)
          case Some(None) =>
            deadline.poll.flatMap:
              case Some(_) => ZIO.succeed(admissionTimedOut)
              case None =>
                bounded(snapshot.awaitArrival, deadline).flatMap:
                  case Some(_) => arrival
                  case None    => charge.map(SubagentWaitOutcome.InboxTimedOut(_))

  /** Signal closed before joining the gate, so held arrival waits and queued callers can finish. */
  def close: UIO[Unit] = ZIO.uninterruptible(
    state.set(State.Closed) *> closing.succeed(()) *> gate.withPermit(ZIO.unit)
  )

object SubagentWaitSession:
  private enum Budget:
    case Fresh, Once, Exhausted
  private enum State:
    case Open(budget: Budget)
    case Closed
  private val closed: AgentError.InvalidConfiguration =
    AgentError.InvalidConfiguration("Async wait session is closed")
  private val admissionTimedOut: SubagentWaitOutcome =
    SubagentWaitOutcome.Barrier(SubagentTaskBarrierResult.AdmissionTimedOut)

  def open(
      inbox: SubagentInbox,
      registry: Option[SubagentTaskRegistry] = None,
      externalWork: SubagentExternalWorkProbe = SubagentExternalWorkProbe.idle
  ): ZIO[Scope, Nothing, SubagentWaitSession] =
    ZIO.acquireRelease(allocate(inbox, registry, externalWork))(_.close)

  private[subagent] def allocate(
      inbox: SubagentInbox,
      registry: Option[SubagentTaskRegistry],
      externalWork: SubagentExternalWorkProbe
  ): UIO[SubagentWaitSession] =
    for
      state   <- Ref.make[State](State.Open(Budget.Fresh))
      gate    <- Semaphore.make(1)
      closing <- Promise.make[Nothing, Unit]
    yield new SubagentWaitSession(registry, inbox, externalWork, state, gate, closing)
