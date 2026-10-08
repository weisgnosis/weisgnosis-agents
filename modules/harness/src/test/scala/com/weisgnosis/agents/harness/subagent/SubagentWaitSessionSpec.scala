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

import com.weisgnosis.agents.core.agent.AgentResult
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.model.{Msg, ToolResultState}
import com.weisgnosis.agents.harness.storage.StorageAddress
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

object SubagentWaitSessionSpec extends ZIOSpecDefault:
  private val result: AgentResult = AgentResult(Msg.assistant("terminal"), Chunk.empty, 1)
  private val oneSecond: SubagentTaskWaitTimeout = SubagentTaskWaitTimeout.fromSeconds(Some(1))
  private val advice: Chunk[String] = Chunk(
    "Inspect task_list if the host installed it",
    "Inspect agent_task if the host installed it"
  )

  private def store: IO[AgentError, SubagentTerminalStore] =
    for
      backend <- SubagentTerminalStoreSpec.fixture
      address <- ZIO.fromEither(StorageAddress.from(Chunk("wait-session"), "tasks"))
    yield SubagentTerminalStore.make(backend.store, address)

  private def registry(
      task: ZIO[Scope, AgentError, AgentResult],
      terminal: Option[SubagentTerminalStore]
  ): ZIO[Scope, AgentError, SubagentTaskRegistry] =
    TaskSupervisor
      .open[AgentResult]
      .flatMap(supervisor =>
        SubagentTaskRegistry.openWith((_, _, _) => supervisor.attach(task), 8, terminal)
      )

  private def submit(tasks: SubagentTaskRegistry): ZIO[Scope, AgentError, SubagentTaskId] =
    tasks
      .submit(Msg.user("work"))
      .flatMap:
        case SubagentTaskSubmission.Accepted(id) => ZIO.succeed(id)
        case other => ZIO.fail(AgentError.InvalidConfiguration(s"Unexpected submission: $other"))

  private def timerStarted: URIO[TestEnvironment, Unit] =
    TestClock.sleeps.repeatUntil(_.nonEmpty).unit

  private def timeout[A](
      effect: IO[AgentError, A],
      duration: Duration = 1.second
  ): ZIO[TestEnvironment & Scope, AgentError, A] =
    for
      waiting <- effect.forkScoped
      _       <- timerStarted.race(waiting.await.unit)
      _       <- TestClock.adjust(duration)
      value   <- waiting.join
    yield value

  private enum CleanupTrigger:
    case Deadline, Close, Caller, Self, Foreign, DuringCleanup

  private enum WorkShape:
    case Empty, Terminal, Drained

  def spec: Spec[TestEnvironment & Scope, Any] = suite("SubagentWaitSessionSpec")(
    test(
      "barrier and inbox waits share two empty timeouts; arrivals and immediately ready barriers reset the budget"
    ):
      ZIO.scoped:
        for
          terminal    <- store
          firstFinish <- Promise.make[Nothing, Unit]
          admissions  <- Ref.make(0)
          tasks <- registry(
            admissions
              .updateAndGet(_ + 1)
              .flatMap(count => if count == 1 then firstFinish.await.as(result) else ZIO.never),
            Some(terminal)
          )
          id        <- submit(tasks)
          selection <- ZIO.fromEither(SubagentTaskSelection.selected(Chunk(id)))
          inbox     <- SubagentInboxSignal.open
          session   <- SubagentWaitSession.open(inbox, Some(tasks))
          first     <- timeout(session.waitFor(SubagentWaitRequest.Tasks(selection), oneSecond))
          second <- timeout(
            session.toolkit
              .executeSafe("wait_async_results", Json.Obj("timeout_seconds" -> Json.Num(1)))
          )
          rejected <- session.waitFor(SubagentWaitRequest.Tasks(selection), oneSecond)
          rejectedTool <- session.toolkit
            .executeSafe("wait_async_results", Json.Obj("task_ids" -> Json.Str(id.value.toString)))
          _       <- inbox.setPresence(SubagentInboxPresence.Pending)
          arrived <- session.waitFor(SubagentWaitRequest.Tasks(selection), oneSecond)
          _       <- inbox.setPresence(SubagentInboxPresence.Empty)
          again   <- timeout(session.waitFor(SubagentWaitRequest.InboxAny, oneSecond))
          _       <- timeout(session.waitFor(SubagentWaitRequest.Tasks(selection), oneSecond))
          record = SubagentTerminalRecord(id, SubagentTerminalOutcome.Completed("terminal"))
          _     <- terminal.publish(record)
          ready <- session.waitFor(SubagentWaitRequest.Tasks(selection), oneSecond)
          currentTruth <- session.waitFor(
            SubagentWaitRequest.Tasks(SubagentTaskSelection.CurrentRunning),
            oneSecond
          )
          _              <- firstFinish.succeed(())
          _              <- tasks.publication(id)
          secondId       <- submit(tasks)
          afterCompleted <- timeout(session.waitFor(SubagentWaitRequest.InboxAny, oneSecond))
          _              <- timeout(session.waitFor(SubagentWaitRequest.InboxAny, oneSecond))
          _              <- tasks.cancel(secondId)
          _              <- tasks.publication(secondId)
          empty <- session.waitFor(
            SubagentWaitRequest.Tasks(SubagentTaskSelection.CurrentRunning),
            oneSecond
          )
          _          <- submit(tasks)
          afterEmpty <- timeout(session.waitFor(SubagentWaitRequest.InboxAny, oneSecond))
          pending    <- terminal.pending
          ordinary   <- SubagentTools.make(tasks).executeSafe("wait_async_results", Json.Obj())
          defaultAndCap <- ZIO.foreach(
            Chunk((Json.Obj(), 60), (Json.Obj("timeout_seconds" -> Json.Num(600)), 120))
          ): (input, seconds) =>
            ZIO.scoped:
              for
                source   <- SubagentInboxSignal.open
                isolated <- SubagentWaitSession.open(source)
                observed <- timeout(
                  isolated.toolkit.executeSafe("wait_async_results", input),
                  seconds.seconds
                )
              yield observed.state == ToolResultState.Success && observed.output ==
                (SubagentWaitToolResult.InboxTimedOut(1, advice): SubagentWaitToolResult).toJson
        yield assertTrue(
          first == SubagentWaitOutcome.Barrier(
            SubagentTaskBarrierResult.TimedOut(Chunk(id), Chunk(id))
          ),
          second.state == ToolResultState.Success,
          second.output == (SubagentWaitToolResult
            .InboxTimedOut(2, advice): SubagentWaitToolResult).toJson,
          rejected == SubagentWaitOutcome.BudgetExhausted,
          rejectedTool.state == ToolResultState.Success,
          rejectedTool.output == (SubagentWaitToolResult.BudgetExhausted(
            advice
          ): SubagentWaitToolResult).toJson,
          arrived == SubagentWaitOutcome.InboxArrived,
          again == SubagentWaitOutcome.InboxTimedOut(1),
          ready == SubagentWaitOutcome.Barrier(SubagentTaskBarrierResult.Completed(Chunk(record))),
          currentTruth == SubagentWaitOutcome.Barrier(
            SubagentTaskBarrierResult.Completed(Chunk(record))
          ),
          afterCompleted == SubagentWaitOutcome.InboxTimedOut(1),
          empty == SubagentWaitOutcome.Barrier(SubagentTaskBarrierResult.Empty),
          afterEmpty == SubagentWaitOutcome.InboxTimedOut(1),
          pending.exists(_ == record),
          ordinary.state == ToolResultState.Error,
          defaultAndCap.forall(identity)
        )
    ,
    test(
      "truthful no-work and explicit repository/probe contracts preserve arrival races, cancellation and joined close"
    ):
      ZIO.scoped:
        for
          inbox          <- SubagentInboxSignal.open
          emptyTasks     <- registry(ZIO.never, None)
          emptySession   <- SubagentWaitSession.open(inbox, Some(emptyTasks))
          noTasks        <- emptySession.waitFor(SubagentWaitRequest.InboxAny)
          completedCount <- Ref.make(0)
          completedTasks <- registry(
            completedCount
              .updateAndGet(_ + 1)
              .flatMap(count =>
                if count == 1 then ZIO.succeed(result)
                else ZIO.fail(AgentError.InvalidConfiguration("Failed child"))
              ),
            None
          )
          completedId       <- submit(completedTasks)
          failedId          <- submit(completedTasks)
          _                 <- completedTasks.await(completedId)
          _                 <- completedTasks.await(failedId)
          completedSession  <- SubagentWaitSession.open(inbox, Some(completedTasks))
          allTerminal       <- completedSession.waitFor(SubagentWaitRequest.InboxAny)
          unresolvedStore   <- store
          unresolvedId      <- Random.nextUUID.map(SubagentTaskId(_))
          _                 <- unresolvedStore.admit(unresolvedId)
          unresolvedTasks   <- registry(ZIO.never, Some(unresolvedStore))
          unresolvedSession <- SubagentWaitSession.open(inbox, Some(unresolvedTasks))
          unresolved <- timeout(unresolvedSession.waitFor(SubagentWaitRequest.InboxAny, oneSecond))
          _          <- inbox.setPresence(SubagentInboxPresence.Pending)
          unresolvedArrival <- unresolvedSession.waitFor(SubagentWaitRequest.InboxAny, oneSecond)
          _                 <- inbox.setPresence(SubagentInboxPresence.Empty)
          external <- SubagentWaitSession.open(
            inbox,
            Some(emptyTasks),
            SubagentExternalWorkProbe(ZIO.succeed(SubagentExternalWorkState.Running))
          )
          externallyWaiting <- timeout(external.waitFor(SubagentWaitRequest.InboxAny, oneSecond))
          probeFailure = AgentError.StorageError("External work probe failed")
          failingProbe <- SubagentWaitSession.open(
            inbox,
            Some(emptyTasks),
            SubagentExternalWorkProbe(ZIO.fail(probeFailure))
          )
          failed           <- failingProbe.waitFor(SubagentWaitRequest.InboxAny, oneSecond).exit
          arrivalAdmission <- Queue.bounded[Unit](1)
          observedInbox = new SubagentInbox:
            def snapshot: IO[AgentError, SubagentInboxSnapshot] = inbox.snapshot.map(value =>
              value.presence match
                case SubagentInboxPresence.Pending => SubagentInboxSnapshot.pending
                case SubagentInboxPresence.Empty =>
                  SubagentInboxSnapshot.awaiting(
                    arrivalAdmission.offer(()).unit *> value.awaitArrival,
                    value.arrivalState
                  )
            )
          noRepository <- SubagentWaitSession.open(observedInbox)
          missing <- noRepository
            .waitFor(SubagentWaitRequest.Tasks(SubagentTaskSelection.CurrentRunning))
            .either
          racedInbox = new SubagentInbox:
            def snapshot: IO[AgentError, SubagentInboxSnapshot] = inbox.snapshot.tap(_ =>
              inbox.setPresence(SubagentInboxPresence.Pending) *> inbox.setPresence(
                SubagentInboxPresence.Empty
              )
            )
          racing         <- SubagentWaitSession.open(racedInbox)
          raced          <- racing.waitFor(SubagentWaitRequest.InboxAny, oneSecond)
          waiting        <- noRepository.waitFor(SubagentWaitRequest.InboxAny, oneSecond).forkScoped
          _              <- arrivalAdmission.take
          cancelled      <- waiting.interrupt
          next           <- timeout(noRepository.waitFor(SubagentWaitRequest.InboxAny, oneSecond))
          _              <- arrivalAdmission.take
          held           <- noRepository.waitFor(SubagentWaitRequest.InboxAny).forkScoped
          _              <- arrivalAdmission.take
          _              <- noRepository.close
          closed         <- held.await
          escaped        <- noRepository.waitFor(SubagentWaitRequest.InboxAny).either
          liveId         <- submit(emptyTasks)
          adapter        <- SubagentDeliveryMiddleware.open(emptyTasks, observedInbox)
          attached       <- adapter.tools().executeSafe("wait_async_results", Json.Obj()).forkScoped
          _              <- arrivalAdmission.take
          _              <- adapter.close
          attachedClosed <- attached.await
          borrowed       <- inbox.snapshot
          selection      <- ZIO.fromEither(SubagentTaskSelection.selected(Chunk(liveId)))
          liveWait       <- emptySession.waitFor(SubagentWaitRequest.Tasks(selection)).forkScoped
          _              <- timerStarted
          _              <- emptySession.close
          liveClosed     <- liveWait.await
          stillRunning   <- emptyTasks.inspect(liveId)
        yield assertTrue(
          noTasks == SubagentWaitOutcome.NoTasks,
          allTerminal == SubagentWaitOutcome.AllTerminal,
          unresolved == SubagentWaitOutcome.InboxTimedOut(1),
          unresolvedArrival == SubagentWaitOutcome.InboxArrived,
          externallyWaiting == SubagentWaitOutcome.InboxTimedOut(1),
          failed match
            case Exit.Failure(cause) =>
              cause.failures == List(probeFailure) && cause.defects.isEmpty && !cause.isInterrupted
            case _ => false,
          missing == Left(
            AgentError.InvalidConfiguration("Task wait barriers require a task registry")
          ),
          raced == SubagentWaitOutcome.InboxArrived,
          cancelled.isInterrupted,
          next == SubagentWaitOutcome.InboxTimedOut(1),
          closed match
            case Exit.Failure(cause) =>
              cause.failures == List(
                AgentError.InvalidConfiguration("Async wait session is closed")
              ) && !cause.isInterrupted
            case _ => false,
          escaped == Left(AgentError.InvalidConfiguration("Async wait session is closed")),
          attachedClosed match
            case Exit.Success(value) =>
              value.state == ToolResultState.Error && value.output.contains(
                "Async wait session is closed"
              )
            case _ => false,
          borrowed.presence == SubagentInboxPresence.Empty,
          liveClosed.isFailure,
          stillRunning.exists(_.state == SubagentTaskState.Running)
        )
    ,
    test("cleanup causes survive timeout, service close and caller interruption"):
      ZIO.scoped:
        for
          inbox <- SubagentInboxSignal.open
          cleanupDefect = new IllegalStateException("Named external probe cleanup defect")
          cleanupExits <- ZIO.foreach(Chunk.fromIterable(CleanupTrigger.values)): trigger =>
            ZIO.scoped:
              for
                probeEntered   <- Promise.make[Nothing, Unit]
                releaseProbe   <- Promise.make[Nothing, Unit]
                cleanupEntered <- Promise.make[Nothing, Unit]
                releaseCleanup <- Promise.make[Nothing, Unit]
                foreignId      <- ZIO.fiberId
                probe = probeEntered.succeed(()) *> (trigger match
                  case CleanupTrigger.Self    => ZIO.interrupt
                  case CleanupTrigger.Foreign => ZIO.interruptAs(foreignId)
                  case _ =>
                    releaseProbe.await *> ZIO.fail(
                      AgentError.StorageError("Fixture close unblock")
                    ))
                cleanupSession <- SubagentWaitSession.open(
                  inbox,
                  None,
                  SubagentExternalWorkProbe(
                    probe.ensuring(
                      cleanupEntered.succeed(()) *>
                        (if trigger == CleanupTrigger.DuringCleanup then releaseCleanup.await
                         else ZIO.unit) *> ZIO.die(cleanupDefect)
                    )
                  )
                )
                active <- ZIO.uninterruptibleMask: restore =>
                  for
                    fiber <- restore(
                      cleanupSession.waitFor(
                        SubagentWaitRequest.InboxAny,
                        if trigger == CleanupTrigger.Close then
                          SubagentTaskWaitTimeout.fromSeconds()
                        else oneSecond
                      )
                    ).forkScoped
                    _ <- ZIO.addFinalizer(
                      releaseProbe.succeed(()) *> releaseCleanup.succeed(()).unit
                    )
                  yield fiber
                _ <- probeEntered.await
                earlyClose <- trigger match
                  case CleanupTrigger.Deadline => TestClock.adjust(1.second).as(Some(Exit.unit))
                  case CleanupTrigger.Close =>
                    for
                      closing <- cleanupSession.close.forkScoped
                      probe   <- closing.await.timeout(1.second).forkScoped
                      _       <- timerStarted
                      _       <- TestClock.adjust(1.second)
                      early   <- probe.join
                      _       <- releaseProbe.succeed(())
                      _       <- closing.join
                    yield early
                  case CleanupTrigger.Caller => active.interrupt.as(Some(Exit.unit))
                  case CleanupTrigger.Self | CleanupTrigger.Foreign => ZIO.succeed(Some(Exit.unit))
                  case CleanupTrigger.DuringCleanup =>
                    for
                      _      <- TestClock.adjust(1.second)
                      _      <- cleanupEntered.await
                      _      <- active.interruptAsFork(foreignId)
                      before <- active.poll
                      _      <- releaseCleanup.succeed(())
                    yield if before.isEmpty then Some(Exit.unit) else None
                exit <- active.await
              yield (trigger, exit, earlyClose, foreignId)
        yield assertTrue(
          cleanupExits.forall:
            case (trigger, Exit.Failure(cause), earlyClose, foreignId) =>
              (!Set(CleanupTrigger.Foreign, CleanupTrigger.DuringCleanup).contains(
                trigger
              ) || cause.interruptors.contains(foreignId)) && earlyClose.contains(
                Exit.unit
              ) && cause.isInterrupted == Set(
                CleanupTrigger.Caller,
                CleanupTrigger.Self,
                CleanupTrigger.Foreign,
                CleanupTrigger.DuringCleanup
              ).contains(trigger) && cause.defects == List(
                cleanupDefect
              ) && cause.failures.forall(
                _ == AgentError.InvalidConfiguration("Async wait session is closed")
              )
            case _ => false
        )
    ,
    test(
      "exhausted readiness does not wait for a publisher after a held stored read observes child completion"
    ):
      ZIO.scoped:
        for
          terminal           <- store
          readEntered        <- Promise.make[Nothing, Unit]
          releaseRead        <- Promise.make[Nothing, Unit]
          readGate           <- Ref.make(Option.empty[Promise[Nothing, Unit]])
          publicationEntered <- Promise.make[Nothing, Unit]
          releasePublication <- Promise.make[Nothing, Unit]
          finish             <- Promise.make[Nothing, Unit]
          wrapped = new SubagentTerminalStore:
            def read(id: SubagentTaskId): IO[AgentError, Option[SubagentTerminalRecord]] =
              readGate.get.flatMap(gate =>
                ZIO.foreachDiscard(gate)(_.succeed(()) *> releaseRead.await)
              ) *> terminal.read(id)
            def list: IO[AgentError, Chunk[SubagentTerminalRecord]] = terminal.list
            def publish(
                record: SubagentTerminalRecord
            ): IO[AgentError, SubagentTerminalPublication] =
              publicationEntered.succeed(()) *> releasePublication.await *> terminal.publish(record)
          tasks <- registry(finish.await.as(result), Some(wrapped))
          _     <- ZIO.addFinalizer(releaseRead.succeed(()) *> releasePublication.succeed(()).unit)
          id    <- submit(tasks)
          inbox <- SubagentInboxSignal.open
          session <- SubagentWaitSession.open(inbox, Some(tasks))
          _       <- timeout(session.waitFor(SubagentWaitRequest.InboxAny, oneSecond))
          _       <- timeout(session.waitFor(SubagentWaitRequest.InboxAny, oneSecond))
          _ <- terminal.publish(
            SubagentTerminalRecord(id, SubagentTerminalOutcome.Completed("terminal"))
          )
          _         <- readGate.set(Some(readEntered))
          selection <- ZIO.fromEither(SubagentTaskSelection.selected(Chunk(id)))
          observing <- session.waitFor(SubagentWaitRequest.Tasks(selection), oneSecond).forkScoped
          _         <- readEntered.await
          _         <- finish.succeed(())
          _         <- publicationEntered.await
          _         <- tasks.await(id)
          _         <- releaseRead.succeed(())
          _         <- timerStarted.race(observing.await.unit)
          _         <- TestClock.adjust(1.second)
          observed  <- observing.join
          terminalSession <- SubagentWaitSession.open(inbox, Some(tasks))
          legacy <- timeout(terminalSession.waitFor(SubagentWaitRequest.InboxAny, oneSecond))
          _      <- releasePublication.succeed(())
          arrivalRaces <- ZIO.foreach(Chunk.fromIterable(WorkShape.values)): shape =>
            ZIO.scoped:
              for
                source <- SubagentInboxSignal.open
                owned  <- registry(ZIO.succeed(result), None)
                _ <- shape match
                  case WorkShape.Empty | WorkShape.Drained => ZIO.unit
                  case WorkShape.Terminal => submit(owned).flatMap(owned.await).unit
                entered <- Promise.make[Nothing, Unit]
                release <- Promise.make[Nothing, Unit]
                service <- SubagentWaitSession.open(
                  source,
                  Some(owned),
                  SubagentExternalWorkProbe(
                    entered.succeed(()) *> release.await.as(SubagentExternalWorkState.Idle)
                  )
                )
                waiting <- service.waitFor(SubagentWaitRequest.InboxAny, oneSecond).forkScoped
                _       <- entered.await
                _       <- source.setPresence(SubagentInboxPresence.Pending)
                _ <-
                  if shape == WorkShape.Drained then source.setPresence(SubagentInboxPresence.Empty)
                  else ZIO.unit
                _       <- release.succeed(())
                outcome <- waiting.join
              yield outcome
        yield assertTrue(
          observed == SubagentWaitOutcome.BudgetExhausted,
          legacy == SubagentWaitOutcome.AllTerminal,
          arrivalRaces.forall(_ == SubagentWaitOutcome.InboxArrived)
        )
  ) @@ TestAspect.timeout(30.seconds)
