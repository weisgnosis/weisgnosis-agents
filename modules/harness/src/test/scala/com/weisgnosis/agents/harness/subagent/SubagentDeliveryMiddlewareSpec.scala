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
import com.weisgnosis.agents.core.client.*
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.model.*
import com.weisgnosis.agents.core.tool.*
import com.weisgnosis.agents.harness.storage.*
import java.util.UUID
import zio.*
import zio.json.ast.Json
import zio.test.*

object SubagentDeliveryMiddlewareSpec extends ZIOSpecDefault:
  private enum Scenario:
    case ModelFailure, Discard, ProjectedAway, Tampered, PriorFailure, ThinkingOnly,
      TerminalFailure, UncertainAck

  private enum ClosePhase:
    case History, Acceptance, Completion

  private val records: Chunk[SubagentTerminalRecord] = Chunk
    .fromIterable(1 to 12)
    .map: index =>
      val outcome = index match
        case 1 => SubagentTerminalOutcome.Completed("the-result <task> & \"quoted\"")
        case 2 => SubagentTerminalOutcome.Failed(Chunk("boom"), SubagentTaskDefects.Absent)
        case 3 => SubagentTerminalOutcome.Interrupted(Chunk.empty, SubagentTaskDefects.Absent)
        case 4 => SubagentTerminalOutcome.Interrupted(Chunk("failure"), SubagentTaskDefects.Present)
        case 5 => SubagentTerminalOutcome.Completed("😀" * 3000)
        case _ => SubagentTerminalOutcome.Completed(s"result $index")
      SubagentTerminalRecord(SubagentTaskId(new UUID(0L, index.toLong)), outcome)

  private def store(owner: String): IO[AgentError, SubagentTerminalStore] =
    for
      backend <- SubagentTerminalStoreSpec.fixture
      address <- ZIO.fromEither(StorageAddress.from(Chunk("delivery-middleware", owner), "tasks"))
    yield SubagentTerminalStore.make(backend.store, address)

  private def registry(store: SubagentTerminalStore): ZIO[Scope, AgentError, SubagentTaskRegistry] =
    SubagentTaskRegistry.openWith(
      (_, _, _) =>
        ZIO.fail(AgentError.InvalidConfiguration("Reminder fixture cannot execute children")),
      16,
      Some(store)
    )

  private def countingStore(
      underlying: SubagentTerminalStore,
      acknowledgeRecord: SubagentTaskId => IO[AgentError, SubagentTerminalAcknowledgeResult]
  ): SubagentTerminalStore = new SubagentTerminalStore:
    def read(id: SubagentTaskId): IO[AgentError, Option[SubagentTerminalRecord]] =
      underlying.read(id)
    def list: IO[AgentError, Chunk[SubagentTerminalRecord]] = underlying.list
    def publish(record: SubagentTerminalRecord): IO[AgentError, SubagentTerminalPublication] =
      underlying.publish(record)
    override def pending: IO[AgentError, Chunk[SubagentTerminalRecord]] = underlying.pending
    override def acknowledgement(
        id: SubagentTaskId
    ): IO[AgentError, Option[SubagentTerminalAcknowledgement]] = underlying.acknowledgement(id)
    override def acknowledge(
        id: SubagentTaskId
    ): IO[AgentError, SubagentTerminalAcknowledgeResult] = acknowledgeRecord(id)

  private def reminders(history: Chunk[Msg]): Chunk[Msg] =
    history.filter(_.reminderKind.contains("task_delivery"))

  def spec: Spec[TestEnvironment & Scope, Any] = suite("SubagentDeliveryMiddlewareSpec")(
    test(
      "synthetic reminders preserve framing, escape bounded previews, aggregate ten observations and consume only seen records before fresh subsequent rounds"
    ):
      ZIO
        .foreach(Chunk(1, 12)): size =>
          ZIO.scoped:
            for
              terminal       <- store(s"format-$size")
              _              <- ZIO.foreachDiscard(records.take(size))(terminal.publish)
              owner          <- registry(terminal)
              adapter        <- SubagentDeliveryMiddleware.make(owner)
              seen           <- Ref.make(Chunk.empty[Chunk[Msg]])
              pendingAtModel <- Ref.make(Chunk.empty[Int])
              client = LlmClient.mockEffectful((history, _, _) =>
                seen.update(_ :+ history) *> terminal.pending
                  .flatMap(pending => pendingAtModel.update(_ :+ pending.size)) *>
                  ZIO.succeed(TurnOutput.FinalText(Msg.assistant("accepted")))
              )
              agent      <- adapter.configure(ReActAgent.builder().client(client)).build
              first      <- agent.run(Msg.user("first"))
              afterFirst <- terminal.pending
              second     <- agent.run(Msg.user("second"))
              _          <- agent.run(Msg.user("third"))
              afterAll   <- terminal.pending
              canonical  <- agent.history
              requests   <- seen.get
              pending    <- pendingAtModel.get
              persisted  <- terminal.list
              text = reminders(first.history).map(_.text).mkString
            yield assertTrue(
              text.startsWith("<system-reminder>"),
              text.endsWith("</system-reminder>"),
              text.contains(
                if size == 1 then "1 background subagent task has completed"
                else "12 background subagent tasks have completed"
              ),
              text.contains("<task_result>"),
              text.contains("the-result &lt;task&gt; &amp; &quot;quoted&quot;"),
              !text.contains("the-result <task>"),
              text.split("<task ", -1).length - 1 == size.min(10),
              size == 1 || (text.contains("<task_error>") && text.contains("boom") && text.contains(
                "state=\"cancelled\""
              ) && text.contains("cancelled before producing a result") && text
                .contains("... and 2 more") && text
                .contains("Task was interrupted; failure; task also reported an internal defect")),
              reminders(first.history).forall(message =>
                message.role == Role.User && message.name.contains("system") && message.isSynthetic
              ),
              requests.headOption.exists(history => reminders(history) == reminders(first.history)),
              afterFirst.size == (size - 10).max(0),
              afterAll.isEmpty,
              pending == Chunk(size, (size - 10).max(0), 0),
              reminders(canonical).size == (if size == 1 then 1 else 2),
              reminders(second.history).size == reminders(canonical).size,
              persisted == records.take(size),
              !text.contains("\uFFFD"),
              size == 1 || text.contains(
                "Output truncated; the exact record remains available to the host."
              ),
              text.zipWithIndex.forall((character, index) =>
                if Character.isHighSurrogate(character) then
                  text.lift(index + 1).exists(Character.isLowSurrogate)
                else if Character.isLowSurrogate(character) then
                  text.lift(index - 1).exists(Character.isHighSurrogate)
                else true
              )
            )
        .map(_.foldLeft(assertTrue(true))(_ && _))
    ,
    test(
      "failed, discarded and altered observations retain an exact capture; successful thinking and retry acknowledge only its original records"
    ):
      ZIO
        .foreach(Chunk.fromIterable(Scenario.values)): scenario =>
          ZIO.scoped:
            for
              terminal         <- store(scenario.toString)
              _                <- ZIO.foreachDiscard(records.take(2))(terminal.publish)
              acknowledgements <- Ref.make(Chunk.empty[SubagentTaskId])
              uncertain        <- Ref.make(true)
              tracked = countingStore(
                terminal,
                id =>
                  acknowledgements.update(_ :+ id) *> terminal
                    .acknowledge(id)
                    .flatMap(receipt =>
                      if scenario == Scenario.UncertainAck && id == SubagentTaskId(new UUID(0L, 2L))
                      then
                        uncertain
                          .getAndSet(false)
                          .flatMap(first =>
                            if first then
                              ZIO.fail(AgentError.StorageError("Lost acknowledgement receipt"))
                            else ZIO.succeed(receipt)
                          )
                      else ZIO.succeed(receipt)
                    )
              )
              owner          <- registry(tracked)
              adapter        <- SubagentDeliveryMiddleware.make(owner)
              requests       <- Ref.make(0)
              pendingAtModel <- Ref.make(Chunk.empty[Int])
              histories      <- Ref.make(Chunk.empty[Chunk[Msg]])
              client = LlmClient.mockEffectful((history, _, _) =>
                for
                  count   <- requests.updateAndGet(_ + 1)
                  pending <- terminal.pending
                  _       <- pendingAtModel.update(_ :+ pending.size)
                  _       <- histories.update(_ :+ history)
                  output <-
                    if scenario == Scenario.ModelFailure && count == 1 then
                      ZIO.fail(AgentError.ModelError(503, "Model unavailable"))
                    else if scenario == Scenario.ThinkingOnly && count == 1 then
                      ZIO.succeed(
                        TurnOutput
                          .ThinkingOnly(Msg.assistant(Chunk(ContentBlock.Thinking("thought"))))
                      )
                    else ZIO.succeed(TurnOutput.FinalText(Msg.assistant("accepted")))
                yield output
              )
              priorCalls <- Ref.make(0)
              prior = ModelResponseObserver(_ =>
                priorCalls
                  .updateAndGet(_ + 1)
                  .flatMap(count =>
                    if count == 1 && (scenario == Scenario.PriorFailure || scenario == Scenario.TerminalFailure)
                    then ZIO.fail(AgentError.CompletionObservationError("Prior observer failed"))
                    else ZIO.unit
                  )
              )
              completions <- Ref.make(0)
              completion = TurnCompletionObserver(_ =>
                completions
                  .updateAndGet(_ + 1)
                  .flatMap(count =>
                    if scenario == Scenario.TerminalFailure && count == 1 then
                      ZIO.fail(AgentError.CompletionObservationError("Terminal observer failed"))
                    else ZIO.unit
                  )
              )
              transform = ModelInputTransform((_, history) =>
                requests.get.map(count =>
                  if count != 0 then history
                  else
                    scenario match
                      case Scenario.ProjectedAway =>
                        history.filterNot(_.reminderKind.contains("task_delivery"))
                      case Scenario.Tampered =>
                        history.map(message =>
                          if message.reminderKind.contains("task_delivery") then
                            message.withTextContent("tampered same identity")
                          else message
                        )
                      case _ => history
                )
              )
              agent <- adapter
                .configure(
                  ReActAgent
                    .builder()
                    .client(client)
                    .modelInputTransform(transform)
                    .postReasoningHook(
                      PostReasoningHook((_, _) =>
                        requests.get.map(count =>
                          if scenario == Scenario.Discard && count == 1 then
                            ReasoningDecision.Discard
                          else ReasoningDecision.Keep
                        )
                      )
                    ),
                  priorModelResponseObserver = prior,
                  priorCompletionObserver = completion
                )
                .build
              first              <- agent.run(Msg.user("first")).exit
              pendingAfterFirst  <- terminal.pending
              second             <- agent.run(Msg.user("second")).exit
              pendingAfterSecond <- terminal.pending
              canonical          <- agent.history
              acks               <- acknowledgements.get
              pendingViews       <- pendingAtModel.get
              views              <- histories.get
            yield assertTrue(
              first.isFailure == Set(
                Scenario.ModelFailure,
                Scenario.PriorFailure,
                Scenario.TerminalFailure,
                Scenario.UncertainAck
              ).contains(scenario),
              second.isSuccess,
              pendingAfterFirst.size == (if Set(
                                             Scenario.Discard,
                                             Scenario.ThinkingOnly,
                                             Scenario.UncertainAck
                                           ).contains(scenario)
                                         then 0
                                         else 2),
              pendingAfterSecond.isEmpty,
              reminders(canonical).size == 1,
              acks.size == (if scenario == Scenario.UncertainAck then 4 else 2),
              acks.toSet == records.take(2).map(_.id).toSet,
              scenario != Scenario.Discard || pendingViews.take(2) == Chunk(2, 2),
              scenario != Scenario.ThinkingOnly || pendingViews.take(2) == Chunk(2, 0),
              scenario != Scenario.Tampered || views.headOption.exists(history =>
                reminders(history).exists(message =>
                  message.text == "tampered same identity" && reminders(canonical)
                    .exists(_.id == message.id)
                )
              )
            )
        .map(_.foldLeft(assertTrue(true))(_ && _))
    ,
    test(
      "cancellation joins prior observer cleanup without acknowledgement, releases capture ownership, and closed adapters reject escaped hooks"
    ):
      ZIO.scoped:
        for
          terminal <- store("cancel")
          _        <- ZIO.foreachDiscard(records.take(1))(terminal.publish)
          owner    <- registry(terminal)
          adapter  <- SubagentDeliveryMiddleware.make(owner)
          entered  <- Promise.make[Nothing, Unit]
          cleanup  <- Promise.make[Nothing, Unit]
          release  <- Promise.make[Nothing, Unit]
          calls    <- Ref.make(0)
          prior = ModelResponseObserver(_ =>
            calls
              .updateAndGet(_ + 1)
              .flatMap(count =>
                if count == 1 then
                  ZIO.acquireRelease(ZIO.unit)(_ => cleanup.succeed(()) *> release.await) *> entered
                    .succeed(()) *> ZIO.never
                else ZIO.unit
              )
          )
          client <- LlmClient.mock(
            Chunk(
              TurnOutput.FinalText(Msg.assistant("accepted")),
              TurnOutput.FinalText(Msg.assistant("retried"))
            )
          )
          agent <- adapter
            .configure(ReActAgent.builder().client(client), priorModelResponseObserver = prior)
            .build
          worker <- ZIO.uninterruptibleMask: restore =>
            for
              fiber <- restore(agent.run(Msg.user("first"))).forkScoped
              _     <- ZIO.addFinalizer(release.succeed(()).unit)
            yield fiber
          _ <- entered.await
          cancelling <- ZIO.uninterruptibleMask: restore =>
            for
              fiber <- restore(worker.interrupt).forkScoped
              _     <- ZIO.addFinalizer(release.succeed(()).unit)
            yield fiber
          _             <- cleanup.await
          before        <- cancelling.poll
          pendingBefore <- terminal.pending
          _             <- release.succeed(())
          interrupted   <- cancelling.join
          retried       <- agent.run(Msg.user("retry"))
          after         <- terminal.pending
          _             <- adapter.close
          escaped       <- agent.run(Msg.user("closed")).exit
          suspendedRecord = SubagentTerminalRecord(
            SubagentTaskId(new UUID(0L, 13L)),
            SubagentTerminalOutcome.Completed("before suspension")
          )
          nextRecord = SubagentTerminalRecord(
            SubagentTaskId(new UUID(0L, 14L)),
            SubagentTerminalOutcome.Completed("after resumption")
          )
          _           <- terminal.publish(suspendedRecord)
          nextAdapter <- SubagentDeliveryMiddleware.make(owner)
          executions  <- Ref.make(0)
          toolkit <- ZIO.fromEither(
            Toolkit.empty.register(
              Tool.make(ToolDef("publish", "Publish next result", Json.Obj()))((_: Json) =>
                executions.update(_ + 1) *> terminal.publish(nextRecord).as("done")
              )
            )
          )
          toolCall = ContentBlock.ToolUse("publish-call", "publish", Json.Obj())
          suspendedClient <- LlmClient.mock(
            Chunk(
              TurnOutput.ToolCalls(Msg.assistant(Chunk(toolCall))),
              TurnOutput.FinalText(Msg.assistant("resumed"))
            )
          )
          suspendedAgent <- nextAdapter
            .configure(
              ReActAgent
                .builder()
                .client(suspendedClient)
                .toolkit(toolkit)
                .postReasoningHook(
                  PostReasoningHook((_, message) =>
                    ZIO.succeed(
                      if message.toolUses.nonEmpty then ReasoningDecision.Suspend("approval")
                      else ReasoningDecision.Keep
                    )
                  )
                )
            )
            .build
          suspended      <- suspendedAgent.run(Msg.user("suspend"))
          beforeResume   <- terminal.acknowledgement(suspendedRecord.id)
          resumed        <- suspendedAgent.resume()
          afterResume    <- terminal.acknowledgement(suspendedRecord.id)
          remaining      <- terminal.pending
          executionCount <- executions.get
          closeChecks <- ZIO.foreach(Chunk.fromIterable(ClosePhase.values)): phase =>
            ZIO.scoped:
              for
                closeAdapter   <- SubagentDeliveryMiddleware.make(owner)
                cleanupEntered <- Promise.make[Nothing, Unit]
                releaseCleanup <- Promise.make[Nothing, Unit]
                priorHistory = HistoryPreparer(history =>
                  if phase == ClosePhase.History then
                    ZIO
                      .acquireRelease(ZIO.unit)(_ =>
                        cleanupEntered.succeed(()) *> releaseCleanup.await
                      )
                      .as(history)
                  else ZIO.succeed(history)
                )
                priorAccepted = ModelResponseObserver(_ =>
                  if phase == ClosePhase.Acceptance then
                    ZIO
                      .acquireRelease(ZIO.unit)(_ =>
                        cleanupEntered.succeed(()) *> releaseCleanup.await
                      )
                      .unit
                  else ZIO.unit
                )
                priorCompleted = TurnCompletionObserver(_ =>
                  if phase == ClosePhase.Completion then
                    ZIO
                      .acquireRelease(ZIO.unit)(_ =>
                        cleanupEntered.succeed(()) *> releaseCleanup.await
                      )
                      .unit
                  else ZIO.unit
                )
                closeClient <- LlmClient.mock(
                  Chunk(TurnOutput.FinalText(Msg.assistant("closed after cleanup")))
                )
                closeAgent <- closeAdapter
                  .configure(
                    ReActAgent.builder().client(closeClient),
                    priorHistory,
                    priorAccepted,
                    priorCompleted
                  )
                  .build
                active <- ZIO.uninterruptibleMask: restore =>
                  for
                    fiber <- restore(closeAgent.run(Msg.user("active"))).forkScoped
                    _     <- ZIO.addFinalizer(releaseCleanup.succeed(()).unit)
                  yield fiber
                _          <- cleanupEntered.await
                earlyClose <- closeAdapter.close.exit.timeout(1.second)
                _          <- releaseCleanup.succeed(())
                completed  <- active.await
                _          <- closeAdapter.close
              yield assertTrue(earlyClose.isEmpty, completed.isSuccess)
        yield assertTrue(
          before.isEmpty,
          pendingBefore.size == 1,
          interrupted.isInterrupted,
          retried.reply.text == "retried",
          reminders(retried.history).size == 1,
          after.isEmpty,
          escaped.isFailure,
          escaped.causeOption.exists(_.failures.exists(_.message.contains("closed"))),
          suspended.isSuspended,
          beforeResume.exists:
            case SubagentTerminalAcknowledgement.Acknowledged(_) => true
            case _                                               => false
          ,
          beforeResume == afterResume,
          resumed.reply.text == "resumed",
          reminders(resumed.history).size == 2,
          remaining.isEmpty,
          executionCount == 1
        ) && closeChecks.foldLeft(assertTrue(true))(_ && _)
  ) @@ TestAspect.timeout(30.seconds) @@ TestAspect.withLiveClock
