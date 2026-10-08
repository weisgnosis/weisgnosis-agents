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

import com.weisgnosis.agents.core.agent.*
import com.weisgnosis.agents.core.client.{LlmClient, TurnOutput}
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.model.{ContentBlock, Msg, ToolResultState}
import com.weisgnosis.agents.core.tool.*
import com.weisgnosis.agents.harness.storage.StorageAddress
import java.util.UUID
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

object SubagentTaskBarrierSpec extends ZIOSpecDefault:
  private def result(text: String): AgentResult = AgentResult(Msg.assistant(text), Chunk.empty, 1)

  private def store: IO[AgentError, SubagentTerminalStore] =
    for
      backend <- SubagentTerminalStoreSpec.fixture
      address <- ZIO.fromEither(StorageAddress.from(Chunk("wait-barrier"), "tasks"))
    yield SubagentTerminalStore.make(backend.store, address)

  private def registry(
      task: Msg => ZIO[Scope, AgentError, AgentResult],
      terminal: Option[SubagentTerminalStore]
  ): ZIO[Scope, AgentError, SubagentTaskRegistry] =
    TaskSupervisor
      .open[AgentResult]
      .flatMap(supervisor =>
        SubagentTaskRegistry
          .openWith((input, _, _) => supervisor.attach(task(input)), 128, terminal)
      )

  private def submit(
      registry: SubagentTaskRegistry,
      text: String
  ): ZIO[Scope, AgentError, SubagentTaskId] =
    registry
      .submit(Msg.user(text))
      .flatMap:
        case SubagentTaskSubmission.Accepted(id) => ZIO.succeed(id)
        case other =>
          ZIO.fail(AgentError.InvalidConfiguration(s"Unexpected task admission: $other"))

  private def selected(ids: SubagentTaskId*): IO[AgentError, SubagentTaskSelection] =
    ZIO.fromEither(SubagentTaskSelection.selected(Chunk.fromIterable(ids)))

  private def hooks(
      underlying: SubagentTerminalStore,
      beforeList: IO[AgentError, Unit] = ZIO.unit,
      beforeRead: IO[AgentError, Unit] = ZIO.unit,
      beforePublish: IO[AgentError, Unit] = ZIO.unit,
      afterAcknowledge: SubagentTaskId => IO[AgentError, Unit] = _ => ZIO.unit
  ): SubagentTerminalStore = new SubagentTerminalStore:
    def read(id: SubagentTaskId): IO[AgentError, Option[SubagentTerminalRecord]] =
      beforeRead *> underlying.read(id)
    def list: IO[AgentError, Chunk[SubagentTerminalRecord]] = beforeList *> underlying.list
    def publish(record: SubagentTerminalRecord): IO[AgentError, SubagentTerminalPublication] =
      beforePublish *> underlying.publish(record)
    override def pending: IO[AgentError, Chunk[SubagentTerminalRecord]] = underlying.pending
    override def acknowledgement(
        id: SubagentTaskId
    ): IO[AgentError, Option[SubagentTerminalAcknowledgement]] =
      underlying.acknowledgement(id)
    override def acknowledge(
        id: SubagentTaskId
    ): IO[AgentError, SubagentTerminalAcknowledgeResult] =
      underlying.acknowledge(id).tap(_ => afterAcknowledge(id))

  private def timerStarted: URIO[TestEnvironment, Unit] =
    TestClock.sleeps.repeatUntil(_.nonEmpty).unit

  private def direct[In, Out](underlying: Tool[Any, In, Out]): Tool[Any, In, Out] =
    new Tool[Any, In, Out]:
      def definition: ToolDef = underlying.definition.copy(returnMode = ToolReturnMode.Direct)
      override def fixedArguments: ToolFixedArguments  = underlying.fixedArguments
      def execute(input: In): ZIO[Any, Throwable, Out] = underlying.execute(input)
      override def executeWithContext(
          input: In,
          invocation: ToolInvocation
      ): ZIO[Any, Throwable, Out] =
        underlying.executeWithContext(input, invocation)
      def decodeInput(input: Json): Either[String, In] = underlying.decodeInput(input)
      def encodeOutput(output: Out): String            = underlying.encodeOutput(output)
      override def encodeResultChecked(output: Out): Either[AgentError, ToolExecutionResult] =
        underlying.encodeResultChecked(output)

  private def directWait(toolkit: Toolkit): IO[AgentError, Toolkit] =
    toolkit.resolve("wait_async_results") match
      case Some(ToolBinding.Local(tool)) => ZIO.succeed(toolkit.updated(direct(tool)))
      case _ => ZIO.fail(AgentError.InvalidConfiguration("Missing wait tool"))

  private enum PublicationFailure:
    case Typed, Self, Foreign

  private enum Consumption:
    case Unchanged, Redacted, PriorFailure, PartialUncertain, Arrival

  def spec: Spec[TestEnvironment & Scope, Any] = suite("SubagentTaskBarrierSpec")(
    test(
      "selected order, stable forgotten references and initial running membership survive backend admission races"
    ):
      ZIO.scoped:
        for
          terminal    <- store
          readEntered <- Promise.make[Nothing, Unit]
          releaseRead <- Promise.make[Nothing, Unit]
          _           <- ZIO.addFinalizer(releaseRead.succeed(()).unit)
          readCount   <- Ref.make(0)
          first       <- Promise.make[Nothing, Unit]
          second      <- Promise.make[Nothing, Unit]
          tracked = hooks(
            terminal,
            beforeRead = readCount
              .updateAndGet(_ + 1)
              .flatMap(count =>
                if count == 2 then readEntered.succeed(()) *> releaseRead.await else ZIO.unit
              )
          )
          tasks <- registry(
            input =>
              input.text match
                case "one" => first.await.as(result("one"))
                case "two" => second.await.as(result("two"))
                case _     => ZIO.never
            ,
            Some(tracked)
          )
          one <- submit(tasks, "one")
          waiting <- SubagentTools
            .make(tasks)
            .executeSafe("wait_async_results", Json.Obj("wait_all" -> Json.Bool(true)))
            .forkScoped
          _                <- readEntered.await
          two              <- submit(tasks, "two")
          _                <- first.succeed(())
          _                <- tasks.publication(one)
          _                <- releaseRead.succeed(())
          current          <- waiting.join
          twoSelection     <- selected(two)
          captured         <- tasks.captureWait(twoSelection)
          _                <- second.succeed(())
          _                <- tasks.publication(two)
          forgotten        <- tasks.forgetCompleted(two)
          stable           <- ZIO.foreach(captured)(_.await)
          orderedSelection <- selected(two, one)
          ordered          <- SubagentTaskBarrier.await(tasks, orderedSelection)
          running          <- submit(tasks, "never")
          unknown = SubagentTaskId(new UUID(9L, 9L))
          unknownSelection <- selected(running, unknown)
          rejected         <- SubagentTaskBarrier.await(tasks, unknownSelection).either
          rejectedTool <- SubagentTools
            .make(tasks)
            .executeSafe(
              "wait_async_results",
              Json.Obj(
                "task_ids" -> Json.Str(s"${running.value},${unknown.value}")
              )
            )
          authoritative = SubagentTerminalRecord(
            running,
            SubagentTerminalOutcome.Completed("external truth")
          )
          _                <- terminal.publish(authoritative)
          runningSelection <- selected(running)
          truth            <- SubagentTaskBarrier.await(tasks, runningSelection)
          rawState         <- tasks.inspect(running)
          noArgument <- SubagentTools.make(tasks).executeSafe("wait_async_results", Json.Obj())
        yield assertTrue(
          current.state == ToolResultState.Success,
          current.output == (SubagentWaitToolResult.Completed(
            Chunk(
              SubagentToolResult
                .Task(one.value.toString, SubagentToolState.Completed(SubagentToolText("one", 0)))
            )
          ): SubagentWaitToolResult).toJson,
          forgotten == SubagentTaskRemoval.Removed,
          stable == Chunk(SubagentTerminalRecord(two, SubagentTerminalOutcome.Completed("two"))),
          ordered == SubagentTaskBarrierResult.Completed(
            stable ++ Chunk(SubagentTerminalRecord(one, SubagentTerminalOutcome.Completed("one")))
          ),
          rejected.left.exists(_.message.contains(unknown.value.toString)),
          rejectedTool.state == ToolResultState.Error,
          rejectedTool.output.contains(unknown.value.toString),
          truth == SubagentTaskBarrierResult.Completed(Chunk(authoritative)),
          rawState.exists(_.state == SubagentTaskState.Running),
          noArgument.state == ToolResultState.Error,
          SubagentTaskSelection.selected(Chunk.empty).isLeft,
          SubagentTaskSelection.selected(Chunk(one, one)).isLeft,
          SubagentTaskWaitTimeout.fromSeconds().seconds == 60,
          SubagentTaskWaitTimeout.fromSeconds(Some(-1)).seconds == 60,
          SubagentTaskWaitTimeout.fromSeconds(Some(600)).seconds == 120
        )
    ,
    test(
      "one deadline includes admission and joined publication; cancellation and timeout leave workers alive"
    ):
      ZIO.scoped:
        for
          terminal     <- store
          attachedRead <- Promise.make[Nothing, Unit]
          readSignal   <- Ref.make(Option.empty[Promise[Nothing, Unit]])
          observedTerminal = hooks(
            terminal,
            beforeRead = readSignal.get.flatMap(signal => ZIO.foreachDiscard(signal)(_.succeed(())))
          )
          finished <- Ref.make(Set.empty[String])
          signals <- ZIO
            .foreach(Chunk("one", "two"))(name => Promise.make[Nothing, Unit].map(name -> _))
            .map(_.toMap)
          tasks <- registry(
            input =>
              ZIO
                .fromOption(signals.get(input.text))
                .orElseFail(
                  AgentError.InvalidConfiguration("Unknown fixture input")
                )
                .flatMap(_.await)
                .as(result(input.text))
                .ensuring(finished.update(_ + input.text)),
            Some(observedTerminal)
          )
          one       <- submit(tasks, "one")
          two       <- submit(tasks, "two")
          selection <- selected(one, two)
          waiting <- SubagentTaskBarrier
            .await(tasks, selection, SubagentTaskWaitTimeout.fromSeconds(Some(60)))
            .forkScoped
          _            <- timerStarted
          _            <- TestClock.adjust(30.seconds)
          _            <- ZIO.foreachDiscard(signals.get("one"))(_.succeed(()))
          _            <- tasks.await(one)
          _            <- TestClock.adjust(30.seconds)
          timed        <- waiting.join
          afterTimeout <- tasks.inspect(two)
          toolWait <- SubagentTools
            .make(tasks)
            .executeSafe(
              "wait_async_results",
              Json.Obj(
                "task_ids"        -> Json.Str(s"${one.value},${two.value}"),
                "timeout_seconds" -> Json.Num(1)
              )
            )
            .forkScoped
          _           <- timerStarted
          _           <- TestClock.adjust(1.second)
          toolTimed   <- toolWait.join
          observer    <- SubagentTaskBarrier.await(tasks, selection).forkScoped
          _           <- timerStarted
          cancelled   <- observer.interrupt
          afterCancel <- tasks.inspect(two)
          cleanup     <- finished.get
          adapter     <- SubagentDeliveryMiddleware.make(tasks)
          model = LlmClient.mockEffectful((_, _, _) =>
            ZIO.succeed(
              TurnOutput.ToolCalls(
                Msg.assistant(
                  Chunk(
                    ContentBlock.ToolUse(
                      "held-wait",
                      "wait_async_results",
                      Json.Obj(
                        "task_ids" -> Json.Str(two.value.toString)
                      )
                    )
                  )
                )
              )
            )
          )
          parent <- adapter
            .configure(ReActAgent.builder().client(model).toolkit(adapter.tools()))
            .build
          _                 <- readSignal.set(Some(attachedRead))
          parentRun         <- parent.run(Msg.user("wait")).forkScoped
          _                 <- attachedRead.await
          adapterClosing    <- adapter.close.timeout(1.second).forkScoped
          _                 <- TestClock.adjust(1.second)
          earlyClose        <- adapterClosing.poll
          parentCancelled   <- parentRun.interrupt
          _                 <- adapterClosing.join
          afterParentCancel <- tasks.inspect(two)
          _                 <- adapter.close
          _                 <- ZIO.foreachDiscard(signals.get("two"))(_.succeed(()))
          joined            <- SubagentTaskBarrier.await(tasks, selection)
          admissionEntered  <- Promise.make[Nothing, Unit]
          listReads         <- Ref.make(0)
          suspended = hooks(
            terminal,
            beforeList = listReads.update(_ + 1) *> admissionEntered.succeed(()) *> ZIO.never
          )
          admissionRegistry <- registry(_ => ZIO.never, Some(suspended))
          unknownSelection  <- selected(SubagentTaskId(new UUID(5L, 1L)))
          admission <- SubagentTaskBarrier.await(admissionRegistry, unknownSelection).forkScoped
          _         <- admissionEntered.await
          _         <- timerStarted
          _         <- TestClock.adjust(60.seconds)
          admissionResult <- admission.join
          beforeEmpty     <- listReads.get
          emptyWait <- SubagentTaskBarrier
            .await(admissionRegistry, SubagentTaskSelection.CurrentRunning)
            .forkScoped
          _           <- timerStarted.race(emptyWait.await.unit)
          _           <- TestClock.adjust(60.seconds)
          emptyResult <- emptyWait.join
          emptyTool <- SubagentTools
            .make(admissionRegistry)
            .executeSafe("wait_async_results", Json.Obj("wait_all" -> Json.Bool(true)))
          afterEmpty         <- listReads.get
          publicationEntered <- Promise.make[Nothing, Unit]
          releasePublication <- Promise.make[Nothing, Unit]
          publicationRead    <- Promise.make[Nothing, Unit]
          releaseRead        <- Promise.make[Nothing, Unit]
          childFinish        <- Promise.make[Nothing, Unit]
          readCount          <- Ref.make(0)
          heldStore = hooks(
            terminal,
            beforePublish = publicationEntered.succeed(()) *> releasePublication.await,
            beforeRead = readCount
              .updateAndGet(_ + 1)
              .flatMap(count =>
                if count == 2 then publicationRead.succeed(()) *> releaseRead.await else ZIO.unit
              )
          )
          publishedTasks <- registry(
            _ => childFinish.await.as(result("published")),
            Some(heldStore)
          )
          _ <- ZIO.addFinalizer(releasePublication.succeed(()) *> releaseRead.succeed(()).unit)
          publishedId <- submit(publishedTasks, "published")
          _ <- terminal.publish(
            SubagentTerminalRecord(publishedId, SubagentTerminalOutcome.Completed("published"))
          )
          publicationSelection <- selected(publishedId)
          publicationWait <- SubagentTaskBarrier
            .await(
              publishedTasks,
              publicationSelection,
              SubagentTaskWaitTimeout.fromSeconds(Some(1))
            )
            .forkScoped
          _                  <- publicationRead.await
          _                  <- childFinish.succeed(())
          _                  <- publicationEntered.await
          _                  <- publishedTasks.await(publishedId)
          _                  <- releaseRead.succeed(())
          _                  <- timerStarted
          _                  <- TestClock.adjust(1.second)
          publicationTimeout <- publicationWait.join
          _                  <- releasePublication.succeed(())
          publicationDone    <- SubagentTaskBarrier.await(publishedTasks, publicationSelection)
          failure     = AgentError.StorageError("Exact publication failure")
          failedStore = hooks(terminal, beforePublish = ZIO.fail(failure))
          failedTasks     <- registry(_ => ZIO.interrupt, Some(failedStore))
          failedId        <- submit(failedTasks, "interrupt")
          _               <- failedTasks.publication(failedId).exit
          failedSelection <- selected(failedId)
          failedWait      <- SubagentTaskBarrier.await(failedTasks, failedSelection).exit
          parallelChecks <- ZIO.foreach(Chunk.fromIterable(PublicationFailure.values)): mode =>
            ZIO.scoped:
              for
                parallelPublicationEntered <- Promise.make[Nothing, Unit]
                releaseParallelFailure     <- Promise.make[Nothing, Unit]
                parallelWaitEntered        <- Promise.make[Nothing, Unit]
                parallelReadArmed          <- Ref.make(false)
                foreignId                  <- ZIO.fiberId
                publishFailure = mode match
                  case PublicationFailure.Typed   => ZIO.fail(failure)
                  case PublicationFailure.Self    => ZIO.interrupt
                  case PublicationFailure.Foreign => ZIO.interruptAs(foreignId)
                parallelStore = hooks(
                  terminal,
                  beforePublish = parallelPublicationEntered.succeed(
                    ()
                  ) *> releaseParallelFailure.await *> publishFailure,
                  beforeRead = parallelReadArmed.get.flatMap(armed =>
                    if armed then parallelWaitEntered.succeed(()).unit else ZIO.unit
                  )
                )
                parallelTasks <- registry(
                  input => if input.text == "failed" then ZIO.interrupt else ZIO.never,
                  Some(parallelStore)
                )
                failedParallelId  <- submit(parallelTasks, "failed")
                runningParallelId <- submit(parallelTasks, "running")
                _                 <- parallelPublicationEntered.await
                _                 <- ZIO.addFinalizer(releaseParallelFailure.succeed(()).unit)
                _                 <- parallelReadArmed.set(true)
                parallelTool <- SubagentTools
                  .make(parallelTasks)
                  .executeSafe(
                    "wait_async_results",
                    Json.Obj(
                      "task_ids" -> Json.Str(
                        s"${failedParallelId.value},${runningParallelId.value}"
                      )
                    )
                  )
                  .exit
                  .forkScoped
                _                    <- parallelWaitEntered.await
                _                    <- releaseParallelFailure.succeed(())
                parallelToolExit     <- parallelTool.join
                parallelStillRunning <- parallelTasks.inspect(runningParallelId)
              yield assertTrue(
                parallelToolExit match
                  case Exit.Success(value) =>
                    mode == PublicationFailure.Typed && value.state == ToolResultState.Error && value.output == s"Execution failed for 'wait_async_results': ${failure.message}"
                  case Exit.Failure(cause) =>
                    mode != PublicationFailure.Typed && cause.isInterrupted && cause.failures.isEmpty && cause.defects.isEmpty && (mode != PublicationFailure.Foreign || cause.interruptors
                      .contains(foreignId)),
                parallelStillRunning.exists(_.state == SubagentTaskState.Running)
              )
          admissionCleanupEntered <- Promise.make[Nothing, Unit]
          admissionCleanupDefect = new IllegalStateException(
            "Named barrier admission cleanup defect"
          )
          admissionCleanupStore = hooks(
            terminal,
            beforeList = (admissionCleanupEntered.succeed(()) *> ZIO.never)
              .ensuring(ZIO.die(admissionCleanupDefect))
          )
          admissionCleanupTasks <- registry(_ => ZIO.never, Some(admissionCleanupStore))
          admissionCleanup <- SubagentTaskBarrier
            .await(
              admissionCleanupTasks,
              unknownSelection,
              SubagentTaskWaitTimeout.fromSeconds(Some(1))
            )
            .exit
            .forkScoped
          _                    <- admissionCleanupEntered.await
          _                    <- TestClock.adjust(1.second)
          admissionCleanupExit <- admissionCleanup.join
        yield assertTrue(
          admissionCleanupExit match
            case Exit.Failure(cause) =>
              cause.defects == List(
                admissionCleanupDefect
              ) && cause.failures.isEmpty && !cause.isInterrupted
            case _ => false,
          timed == SubagentTaskBarrierResult.TimedOut(Chunk(one, two), Chunk(two)),
          afterTimeout.exists(_.state == SubagentTaskState.Running),
          toolTimed.state == ToolResultState.Success,
          toolTimed.output == (SubagentWaitToolResult.TimedOut(
            Chunk(one.value.toString, two.value.toString),
            Chunk(two.value.toString)
          ): SubagentWaitToolResult).toJson,
          cancelled.isInterrupted,
          afterCancel.exists(_.state == SubagentTaskState.Running),
          cleanup == Set("one"),
          earlyClose.contains(Exit.succeed(None)),
          parentCancelled.isInterrupted,
          afterParentCancel.exists(_.state == SubagentTaskState.Running),
          joined == SubagentTaskBarrierResult.Completed(
            Chunk(
              SubagentTerminalRecord(one, SubagentTerminalOutcome.Completed("one")),
              SubagentTerminalRecord(two, SubagentTerminalOutcome.Completed("two"))
            )
          ),
          admissionResult == SubagentTaskBarrierResult.AdmissionTimedOut,
          emptyResult == SubagentTaskBarrierResult.Empty,
          afterEmpty == beforeEmpty,
          emptyTool.state == ToolResultState.Success,
          emptyTool.output == (SubagentWaitToolResult.Empty: SubagentWaitToolResult).toJson,
          publicationTimeout == SubagentTaskBarrierResult
            .TimedOut(Chunk(publishedId), Chunk(publishedId)),
          publicationDone == SubagentTaskBarrierResult.Completed(
            Chunk(
              SubagentTerminalRecord(publishedId, SubagentTerminalOutcome.Completed("published"))
            )
          ),
          failedWait match
            case Exit.Failure(cause) =>
              cause.failures == List(failure) && cause.defects.isEmpty && !cause.isInterrupted
            case _ => false
        ) && parallelChecks.foldLeft(assertTrue(true))(_ && _)
    ,
    test(
      "only exact persisted complete wait results acknowledge selected tasks; failed or uncertain receipts are not retried"
    ):
      ZIO
        .foreach(Chunk.fromIterable(Consumption.values)): mode =>
          ZIO.scoped:
            for
              terminal <- store
              attempts <- Ref.make(Chunk.empty[SubagentTaskId])
              records = Chunk(
                SubagentTerminalRecord(
                  SubagentTaskId(new UUID(4L, 1L)),
                  SubagentTerminalOutcome
                    .Failed(Chunk(SubagentTaskFailure("failed child")), SubagentTaskDefects.Absent)
                ),
                SubagentTerminalRecord(
                  SubagentTaskId(new UUID(4L, 2L)),
                  SubagentTerminalOutcome.Completed("success")
                )
              )
              tracked = hooks(
                terminal,
                afterAcknowledge = id =>
                  attempts.update(_ :+ id) *>
                    (if mode == Consumption.PartialUncertain && records.lastOption
                         .exists(_.id == id)
                     then ZIO.fail(AgentError.StorageError("Uncertain second acknowledgement"))
                     else ZIO.unit)
              )
              tasks    <- registry(_ => ZIO.never, Some(tracked))
              inbox    <- SubagentInboxSignal.open
              adapter  <- SubagentDeliveryMiddleware.open(tasks, inbox)
              requests <- Ref.make(0)
              client = LlmClient.mockEffectful((_, _, _) =>
                requests
                  .updateAndGet(_ + 1)
                  .flatMap(count =>
                    if count == 1 then
                      ZIO
                        .foreachDiscard(records)(terminal.publish)
                        .zipRight(
                          if mode == Consumption.Arrival then
                            inbox.setPresence(SubagentInboxPresence.Pending)
                          else ZIO.unit
                        )
                        .as(
                          TurnOutput.ToolCalls(
                            Msg.assistant(
                              Chunk(
                                ContentBlock.ToolUse(
                                  "wait",
                                  "wait_async_results",
                                  if mode == Consumption.Arrival then Json.Obj()
                                  else
                                    Json.Obj(
                                      "task_ids" -> Json
                                        .Str(records.map(_.id.value.toString).mkString(","))
                                    )
                                )
                              )
                            )
                          )
                        )
                    else ZIO.succeed(TurnOutput.FinalText(Msg.assistant("done")))
                  )
              )
              prior = LocalToolResultObserver(_ =>
                if mode == Consumption.PriorFailure then
                  ZIO.fail(AgentError.CompletionObservationError("Prior callback failed"))
                else ZIO.unit
              )
              processor = ToolResultProcessor(value =>
                ZIO.succeed(
                  if mode == Consumption.Redacted then
                    ToolResultPayload(Chunk(ContentBlock.Text("redacted")))
                  else ToolResultPayload.fromResult(value)
                )
              )
              configuredTools <-
                if mode == Consumption.Unchanged then directWait(adapter.tools())
                else ZIO.succeed(adapter.tools())
              agent <- adapter
                .configure(
                  ReActAgent
                    .builder()
                    .client(client)
                    .toolkit(configuredTools)
                    .toolResultProcessor(processor)
                    .maxIterations(1),
                  priorLocalToolResultObserver = prior
                )
                .build
              outcome     <- agent.run(Msg.user("wait")).exit
              pending     <- terminal.pending
              ackAttempts <- attempts.get
              stamps      <- ZIO.foreach(records)(record => terminal.acknowledgement(record.id))
              history     <- agent.history
              callbacks   <- requests.get
            yield assertTrue(
              outcome match
                case Exit.Failure(cause) if mode == Consumption.PriorFailure =>
                  cause.failures == List(
                    AgentError.CompletionObservationError("Prior callback failed")
                  ) && cause.defects.isEmpty && !cause.isInterrupted
                case Exit.Failure(cause) if mode == Consumption.PartialUncertain =>
                  cause.failures == List(
                    AgentError.CompletionObservationError("Uncertain second acknowledgement")
                  ) && cause.defects.isEmpty && !cause.isInterrupted
                case Exit.Success(value) =>
                  !Set(Consumption.PriorFailure, Consumption.PartialUncertain).contains(mode) &&
                  (mode != Consumption.Unchanged || value.state == AgentRunState.Completed)
                case _ => false,
              ackAttempts == (if Set(Consumption.Unchanged, Consumption.PartialUncertain)
                                  .contains(mode)
                              then records.map(_.id)
                              else Chunk.empty),
              pending.size == (if ackAttempts.isEmpty then 2 else 0),
              stamps.forall(_.exists:
                case SubagentTerminalAcknowledgement.Acknowledged(_) => ackAttempts.nonEmpty
                case SubagentTerminalAcknowledgement.Pending         => ackAttempts.isEmpty),
              history.flatMap(_.toolResults).size == 1,
              history.flatMap(_.toolResults).forall(_.state == ToolResultState.Success),
              callbacks == 1,
              mode != Consumption.Arrival || history
                .flatMap(_.toolResults)
                .exists(
                  _.outputText == (SubagentWaitToolResult.InboxArrived: SubagentWaitToolResult).toJson
                )
            )
        .map(_.foldLeft(assertTrue(true))(_ && _))
  ) @@ TestAspect.timeout(30.seconds)
