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

import com.weisgnosis.agents.core.agent.{AgentConfig, AgentResult}
import com.weisgnosis.agents.core.client.{LlmClient, LlmConfig, TurnOutput}
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.model.Msg
import com.weisgnosis.agents.core.tool.{ToolAuthorizer, Toolkit}
import com.weisgnosis.agents.harness.storage.*
import zio.*
import zio.json.ast.Json
import zio.test.*

object SubagentCompletionNotificationSpec extends ZIOSpecDefault:
  private enum Outcome:
    case Failed, Cancelled

  private enum ReceiptCase:
    case TypedFailure, Defect, FailureWithCleanupDefect, Interrupted, PublicationFailed, SelfJoin,
      ChildSelfJoin, ObserverCancelled

  private def factory(register: ZIO[Scope, AgentError, Toolkit]): IO[AgentError, SubagentFactory] =
    ZIO.fromEither(
      SubagentFactory.make(
        AgentConfig(name = "notification-child"),
        LlmConfig("notification-model"),
        Toolkit.empty,
        SubagentToolBoundary(),
        SubagentToolBoundary(),
        _ => register,
        _ => LlmClient.mock(Chunk(TurnOutput.FinalText(Msg.assistant("exact result λ")))),
        ToolAuthorizer.allow
      )
    )

  private def accepted(
      effect: ZIO[Scope, AgentError, SubagentTaskSubmission]
  ): ZIO[Scope, AgentError, SubagentTaskId] =
    effect.flatMap:
      case SubagentTaskSubmission.Accepted(id) => ZIO.succeed(id)
      case other =>
        ZIO.fail(AgentError.InvalidConfiguration(s"Expected accepted notification task: $other"))

  private def terminalWrite(value: Json): Boolean = value match
    case Json.Obj(fields) =>
      fields
        .find(_._1 == "records")
        .exists(_._2 match
          case Json.Arr(records) =>
            records.exists:
              case Json.Obj(entry) =>
                entry
                  .find(_._1 == "state")
                  .exists(_._2 match
                    case Json.Obj(state) => state.exists(_._1 == "Terminal")
                    case _               => false)
              case _ => false
          case _ => false)
    case _ => false

  private def orderedNotification(
      outcome: Outcome
  ): ZIO[TestEnvironment & Scope, AgentError, TestResult] =
    ZIO.scoped:
      for
        backend <- SubagentTerminalStoreSpec.fixture
        address <- ZIO.fromEither(
          StorageAddress.from(Chunk("notification", outcome.toString), "tasks")
        )
        childCleaning      <- Promise.make[Nothing, Unit]
        releaseChild       <- Promise.make[Nothing, Unit]
        publishing         <- Promise.make[Nothing, Unit]
        releasePublication <- Promise.make[Nothing, Unit]
        callbackCleaning   <- Promise.make[Nothing, Unit]
        releaseCallback    <- Promise.make[Nothing, Unit]
        childEntered       <- Promise.make[Nothing, Unit]
        runs               <- Ref.make(0)
        callbacks          <- Ref.make(Chunk.empty[(SubagentTerminalRecord, Boolean)])
        controlled = new VersionedJsonStore:
          def read(address: StorageAddress): IO[AgentError, Option[VersionedJson]] =
            backend.store.read(address)
          def compareAndSet(
              address: StorageAddress,
              expected: ExpectedRevision,
              value: Json
          ): IO[AgentError, ConditionalWrite] =
            (if terminalWrite(value) then publishing.succeed(()) *> releasePublication.await
             else ZIO.unit) *>
              backend.store.compareAndSet(address, expected, value)
        store  = SubagentTerminalStore.make(controlled, address)
        reader = SubagentTerminalStore.make(backend.store, address)
        configured <- factory(
          runs
            .updateAndGet(_ + 1)
            .flatMap:
              case 1 =>
                ZIO
                  .acquireRelease(ZIO.unit)(_ => childCleaning.succeed(()) *> releaseChild.await)
                  .as(Toolkit.empty)
              case _ =>
                outcome match
                  case Outcome.Failed =>
                    ZIO.fail(
                      AgentError
                        .ToolExecutionError("child", "exact failure", Some("direct cause λ"))
                    )
                  case Outcome.Cancelled => childEntered.succeed(()) *> ZIO.never
        )
        callback = SubagentCompletionNotifier((record, _) =>
          ZIO.acquireRelease(ZIO.unit)(_ =>
            callbackCleaning.succeed(()) *> releaseCallback.await
          ) *>
            (for
              persisted <- reader.read(record.id)
              pending   <- reader.pending
              _ <- callbacks.update(
                _ :+ (record -> (persisted.contains(record) && pending.contains(record)))
              )
            yield ()).mapError(error => AgentError.CompletionObservationError(error.message))
        )
        supervisor <- TaskSupervisor.open[AgentResult]
        parent     <- SubagentSpawner.root(supervisor)
        lifetime   <- ZIO.acquireRelease(Scope.make)(_.close(Exit.unit))
        registry <- lifetime.extend(
          SubagentTaskRegistry.openDurable(
            parent,
            configured,
            store,
            completionNotification = SubagentCompletionNotification.Notify(callback)
          )
        )
        _ <- ZIO.addFinalizer(
          releaseChild.succeed(()) *> releasePublication
            .succeed(()) *> releaseCallback.succeed(()).unit
        )
        first <- accepted(
          registry.submitWithCompletionNotification(
            Msg.user("suppressed"),
            SubagentCompletionNotification.Suppress
          )
        )
        _                 <- childCleaning.await
        beforeCleanup     <- callbacks.get
        beforePublication <- publishing.poll
        receiptWaiter     <- registry.completionNotification(first).forkScoped
        _                 <- releaseChild.succeed(())
        _                 <- publishing.await
        pendingReceipt    <- receiptWaiter.poll
        beforeStored      <- reader.read(first)
        _                 <- releasePublication.succeed(())
        suppressed        <- receiptWaiter.join
        afterSuppressed   <- callbacks.get
        firstPending      <- reader.pending
        second            <- accepted(registry.submit(Msg.user("notify")))
        _ <- outcome match
          case Outcome.Failed    => ZIO.unit
          case Outcome.Cancelled => childEntered.await *> registry.cancelTask(second).unit
        _            <- callbackCleaning.await
        captured     <- callbacks.get
        unnotified   <- registry.forgetCompleted(second)
        heldReceipt  <- registry.completionNotification(second).forkScoped
        closeEntered <- Promise.make[Nothing, Unit]
        closing <- ZIO.uninterruptibleMask: restore =>
          for
            fiber <- restore(closeEntered.succeed(()) *> lifetime.close(Exit.unit)).forkScoped
            _     <- ZIO.addFinalizer(releaseCallback.succeed(()).unit)
          yield fiber
        _            <- closeEntered.await
        _            <- TestClock.adjust(0.seconds)
        earlyClose   <- closing.poll
        earlyReceipt <- heldReceipt.poll
        _            <- releaseCallback.succeed(())
        _            <- closing.join
        observed     <- heldReceipt.join
        afterClose   <- registry.completionNotification(second)
        receipts     <- registry.flushCompletionNotifications
        pending      <- reader.pending
        forgotten    <- registry.forgetCompleted(second)
        reopened <- SubagentTaskRegistry.openDurable(
          parent,
          configured,
          reader,
          completionNotification = SubagentCompletionNotification.Notify(callback)
        )
        freshReceipts  <- reopened.flushCompletionNotifications
        finalCallbacks <- callbacks.get
        expected = outcome match
          case Outcome.Failed =>
            SubagentTerminalOutcome.Failed(
              Chunk(SubagentTaskFailure("exact failure", Some("direct cause λ"))),
              SubagentTaskDefects.Absent
            )
          case Outcome.Cancelled =>
            SubagentTerminalOutcome.Interrupted(Chunk.empty, SubagentTaskDefects.Absent)
      yield assertTrue(
        beforeCleanup.isEmpty,
        beforePublication.isEmpty,
        pendingReceipt.isEmpty,
        beforeStored.isEmpty,
        suppressed.contains(SubagentCompletionReceipt.Suppressed),
        afterSuppressed.isEmpty,
        firstPending.map(_.id) == Chunk(first),
        captured == Chunk(SubagentTerminalRecord(second, expected) -> true),
        unnotified == SubagentTaskRemoval.Unnotified,
        earlyClose.isEmpty,
        earlyReceipt.isEmpty,
        observed.contains(SubagentCompletionReceipt.Observed),
        afterClose == observed,
        receipts == Chunk(
          SubagentTaskCompletionReceipt(first, SubagentCompletionReceipt.Suppressed),
          SubagentTaskCompletionReceipt(second, SubagentCompletionReceipt.Observed)
        ),
        pending.map(_.id) == Chunk(first, second),
        forgotten == SubagentTaskRemoval.Removed,
        freshReceipts.isEmpty,
        finalCallbacks == captured
      )

  private def notificationReceipt(
      mode: ReceiptCase
  ): ZIO[TestEnvironment & Scope, AgentError, TestResult] =
    ZIO.scoped:
      for
        backend <- SubagentTerminalStoreSpec.fixture
        address <- ZIO.fromEither(
          StorageAddress.from(Chunk("notification-receipt", mode.toString), "tasks")
        )
        callbackEntered <- Promise.make[Nothing, Unit]
        release         <- Promise.make[Nothing, Unit]
        calls           <- Ref.make(0)
        registryRef     <- Ref.make(Option.empty[SubagentTaskRegistry])
        reentrant       <- Ref.make(Chunk.empty[Exit[AgentError, Unit]])
        error            = AgentError.CompletionObservationError("Callback failed exactly")
        publicationError = AgentError.StorageError("Publication failed exactly")
        defect           = new IllegalStateException("Callback defect exactly")
        controlled = new VersionedJsonStore:
          def read(address: StorageAddress): IO[AgentError, Option[VersionedJson]] =
            backend.store.read(address)
          def compareAndSet(
              address: StorageAddress,
              expected: ExpectedRevision,
              value: Json
          ): IO[AgentError, ConditionalWrite] =
            if mode == ReceiptCase.PublicationFailed && terminalWrite(value) then
              ZIO.fail(publicationError)
            else backend.store.compareAndSet(address, expected, value)
        store = SubagentTerminalStore.make(controlled, address)
        callback = SubagentCompletionNotifier((record, _) =>
          calls.update(_ + 1) *> callbackEntered.succeed(()) *> (mode match
            case ReceiptCase.TypedFailure => ZIO.fail(error)
            case ReceiptCase.Defect       => ZIO.die(defect)
            case ReceiptCase.FailureWithCleanupDefect =>
              ZIO.addFinalizer(ZIO.die(defect)) *> ZIO.fail(error)
            case ReceiptCase.Interrupted       => ZIO.interrupt
            case ReceiptCase.ObserverCancelled => release.await
            case ReceiptCase.SelfJoin | ReceiptCase.ChildSelfJoin =>
              registryRef.get.flatMap:
                case None => ZIO.fail(error)
                case Some(registry) =>
                  val inspect = for
                    one <- registry.completionNotification(record.id).unit.exit
                    all <- registry.flushCompletionNotifications.unit.exit
                    _   <- reentrant.set(Chunk(one, all))
                  yield ()
                  if mode == ReceiptCase.ChildSelfJoin then inspect.forkScoped.flatMap(_.join)
                  else inspect
            case ReceiptCase.PublicationFailed => ZIO.unit)
        )
        configured <- factory(ZIO.succeed(Toolkit.empty))
        supervisor <- TaskSupervisor.open[AgentResult]
        parent     <- SubagentSpawner.root(supervisor)
        registry <- SubagentTaskRegistry.openDurable(
          parent,
          configured,
          store,
          maxTracked = 1,
          completionNotification = SubagentCompletionNotification.Notify(callback)
        )
        _  <- registryRef.set(Some(registry))
        _  <- ZIO.addFinalizer(release.succeed(()).unit)
        id <- accepted(registry.submit(Msg.user("receipt")))
        cancelledWaiter <-
          if mode == ReceiptCase.ObserverCancelled then
            for
              _      <- callbackEntered.await
              waiter <- registry.completionNotification(id).forkScoped
              exit   <- waiter.interrupt
              _      <- release.succeed(())
            yield exit.isInterrupted
          else ZIO.succeed(true)
        receipt     <- registry.completionNotification(id).exit
        repeated    <- registry.completionNotification(id).exit
        flushed     <- registry.flushCompletionNotifications.exit
        publication <- registry.publication(id).exit
        removal     <- registry.forgetCompleted(id)
        blocked <- mode match
          case ReceiptCase.TypedFailure | ReceiptCase.Defect |
              ReceiptCase.FailureWithCleanupDefect | ReceiptCase.Interrupted =>
            registry.submit(Msg.user("capacity"))
          case _ => ZIO.succeed(SubagentTaskSubmission.Capacity)
        callbackCalls <- calls.get
        guarded       <- reentrant.get
        expectedFailure = mode match
          case ReceiptCase.TypedFailure => receipt == Exit.fail(error)
          case ReceiptCase.Defect =>
            receipt.causeOption.exists(cause =>
              cause.defects == List(defect) && cause.failures.isEmpty && !cause.isInterrupted
            )
          case ReceiptCase.FailureWithCleanupDefect =>
            receipt.causeOption.exists(cause =>
              cause.failures == List(error) && cause.defects == List(defect) && !cause.isInterrupted
            )
          case ReceiptCase.Interrupted => receipt.isInterrupted
          case ReceiptCase.PublicationFailed =>
            receipt match
              case Exit.Success(Some(SubagentCompletionReceipt.NotPublished(cause))) =>
                publication.causeOption.contains(cause) && cause.failures == List(
                  publicationError
                ) && cause.defects.isEmpty && !cause.isInterrupted
              case _ => false
          case _ => receipt == Exit.succeed(Some(SubagentCompletionReceipt.Observed))
        failureRetained = mode match
          case ReceiptCase.TypedFailure | ReceiptCase.Defect |
              ReceiptCase.FailureWithCleanupDefect | ReceiptCase.Interrupted =>
            removal == SubagentTaskRemoval.Unnotified && flushed.causeOption == receipt.causeOption
          case ReceiptCase.PublicationFailed =>
            removal == SubagentTaskRemoval.Unpublished && flushed.isSuccess && publication == Exit
              .fail(publicationError)
          case _ => removal == SubagentTaskRemoval.Removed && flushed.isSuccess
      yield assertTrue(
        expectedFailure,
        repeated == receipt,
        failureRetained,
        blocked == SubagentTaskSubmission.Capacity,
        cancelledWaiter,
        callbackCalls == (if mode == ReceiptCase.PublicationFailed then 0 else 1),
        if mode == ReceiptCase.SelfJoin || mode == ReceiptCase.ChildSelfJoin then
          guarded.size == 2 && guarded.forall(
            _.causeOption.exists(
              _.failures.exists(
                _.message == "Completion callbacks cannot await this registry's notification receipts"
              )
            )
          )
        else guarded.isEmpty
      )

  private def flushAll: ZIO[TestEnvironment & Scope, AgentError, TestResult] =
    ZIO.scoped:
      for
        backend <- SubagentTerminalStoreSpec.fixture
        address <- ZIO.fromEither(StorageAddress.from(Chunk("notification-flush"), "tasks"))
        calls   <- Ref.make(0)
        held    <- Promise.make[Nothing, Unit]
        release <- Promise.make[Nothing, Unit]
        error = AgentError.CompletionObservationError("First callback failed exactly")
        callback = SubagentCompletionNotifier((_, _) =>
          calls
            .updateAndGet(_ + 1)
            .flatMap:
              case 1 => ZIO.fail(error)
              case _ => held.succeed(()) *> release.await
        )
        configured <- factory(ZIO.succeed(Toolkit.empty))
        supervisor <- TaskSupervisor.open[AgentResult]
        parent     <- SubagentSpawner.root(supervisor)
        registry <- SubagentTaskRegistry.openDurable(
          parent,
          configured,
          SubagentTerminalStore.make(backend.store, address),
          completionNotification = SubagentCompletionNotification.Notify(callback)
        )
        _             <- ZIO.addFinalizer(release.succeed(()).unit)
        first         <- accepted(registry.submit(Msg.user("first")))
        firstReceipt  <- registry.completionNotification(first).exit
        second        <- accepted(registry.submit(Msg.user("second")))
        _             <- held.await
        flushing      <- registry.flushCompletionNotifications.exit.forkScoped
        early         <- flushing.poll
        _             <- release.succeed(())
        flushed       <- flushing.join
        secondReceipt <- registry.completionNotification(second)
        volatile      <- SubagentTaskRegistry.open(parent, configured)
        unsupportedSubmit <- volatile
          .submitWithCompletionNotification(
            Msg.user("unsupported"),
            SubagentCompletionNotification.Notify(callback)
          )
          .either
        unsupportedReceipt <- volatile.completionNotification(first).either
        unsupportedFlush   <- volatile.flushCompletionNotifications.either
        callbackCalls      <- calls.get
      yield assertTrue(
        firstReceipt == Exit.fail(error),
        early.isEmpty,
        flushed.causeOption == firstReceipt.causeOption,
        secondReceipt.contains(SubagentCompletionReceipt.Observed),
        callbackCalls == 2,
        unsupportedSubmit.isLeft,
        unsupportedReceipt.isLeft,
        unsupportedFlush.isLeft
      )

  def spec: Spec[TestEnvironment & Scope, Any] = suite("SubagentCompletionNotificationSpec")(
    test(
      "joined cleanup and confirmed publication precede same-owner suppression and notification; owner closure joins callback cleanup without replay"
    ):
      ZIO
        .foreach(Chunk(Outcome.Failed, Outcome.Cancelled))(orderedNotification)
        .map(_.foldLeft(assertTrue(true))(_ && _))
    ,
    test(
      "notification failures remain separate exact receipts; retained callbacks reject self-joins and observer cancellation never cancels notification"
    ):
      ZIO
        .foreach(
          Chunk(
            ReceiptCase.TypedFailure,
            ReceiptCase.Defect,
            ReceiptCase.FailureWithCleanupDefect,
            ReceiptCase.Interrupted,
            ReceiptCase.PublicationFailed,
            ReceiptCase.SelfJoin,
            ReceiptCase.ChildSelfJoin,
            ReceiptCase.ObserverCancelled
          )
        )(notificationReceipt)
        .flatMap(results => flushAll.map(extra => results.foldLeft(extra)(_ && _)))
  ) @@ TestAspect.timeout(20.seconds)
