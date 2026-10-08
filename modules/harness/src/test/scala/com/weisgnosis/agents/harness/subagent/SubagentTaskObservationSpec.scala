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
import com.weisgnosis.agents.harness.storage.*
import zio.*
import zio.json.ast.Json
import zio.test.*

object SubagentTaskObservationSpec extends ZIOSpecDefault:
  private enum SnapshotTiming:
    case Completed, BeforeCompletion, BeforeAcknowledgement

  private val result: AgentResult = AgentResult(Msg.assistant("exact terminal"), Chunk.empty, 1)

  private def owner: ZIO[Scope, Nothing, Scope.Closeable] =
    ZIO.acquireRelease(Scope.make)(_.close(Exit.unit))

  private def address: IO[AgentError, StorageAddress] =
    ZIO.fromEither(StorageAddress.from(Chunk("observed-tasks"), "terminal"))

  private def accepted(registry: SubagentTaskRegistry): ZIO[Scope, AgentError, SubagentTaskId] =
    registry
      .submit(Msg.user("work"))
      .flatMap:
        case SubagentTaskSubmission.Accepted(id) => ZIO.succeed(id)
        case other => ZIO.fail(AgentError.InvalidConfiguration(s"Unexpected admission: $other"))

  def spec: Spec[TestEnvironment & Scope, Any] = suite("SubagentTaskObservationSpec")(
    test(
      "publication is owned before admission returns; cancelled observers and forgetting cannot discard pending receipts"
    ):
      ZIO.scoped:
        for
          scope      <- owner
          supervisor <- scope.extend(TaskSupervisor.open[AgentResult])
          backend    <- SubagentTerminalStoreSpec.fixture
          target     <- address
          writing    <- Promise.make[Nothing, Unit]
          release    <- Promise.make[Nothing, Unit]
          _          <- ZIO.addFinalizer(release.succeed(()).unit)
          held = new VersionedJsonStore:
            def read(address: StorageAddress): IO[AgentError, Option[VersionedJson]] =
              backend.store.read(address)
            def compareAndSet(
                address: StorageAddress,
                expected: ExpectedRevision,
                value: Json
            ): IO[AgentError, ConditionalWrite] =
              writing.succeed(()).unit *> release.await *> backend.store
                .compareAndSet(address, expected, value)
          worker <- ZIO.fromEither(SubagentWorkerId.from("registry worker λ"))
          terminal = SubagentTerminalStore.make(held, target)
          registry <- scope.extend(
            SubagentTaskRegistry.openWith(
              (_, _, _) => supervisor.attach(ZIO.succeed(result)),
              1,
              Some(terminal),
              Some(worker)
            )
          )
          id                <- accepted(registry)
          _                 <- writing.await
          finished          <- registry.await(id)
          pending           <- registry.forgetCompleted(id)
          waiting           <- registry.observe(id).forkScoped
          cancelled         <- waiting.interrupt
          stillPending      <- registry.forgetCompleted(id)
          before            <- backend.store.read(target)
          _                 <- release.succeed(())
          receipt           <- registry.publication(id)
          observed          <- registry.observe(id)
          forgotten         <- registry.forgetCompleted(id)
          restored          <- registry.observe(id)
          deliveries        <- registry.pendingDeliveries.either
          acknowledgement   <- registry.acknowledge(id).either
          inboxBefore       <- registry.durablePendingDeliveries
          ackTime           <- Clock.instant
          acknowledged      <- registry.acknowledgeTerminal(id)
          acknowledgedAgain <- registry.acknowledgeTerminal(id)
          persistedStamp    <- SubagentTerminalStore.make(backend.store, target).acknowledgement(id)
          inboxAfter        <- registry.durablePendingDeliveries
          legacy <- SubagentTaskRegistry.openWith(
            (_, _, _) => supervisor.attach(ZIO.succeed(result)),
            1,
            workerId = Some(worker)
          )
          legacyId       <- accepted(legacy)
          _              <- legacy.await(legacyId)
          legacyView     <- legacy.observe(legacyId)
          legacyWait     <- SubagentTaskBarrier.await(legacy, SubagentTaskSelection.CurrentRunning)
          selected       <- ZIO.fromEither(SubagentTaskSelection.selected(Chunk(legacyId)))
          legacySelected <- SubagentTaskBarrier.await(legacy, selected)
          legacyReceipt  <- legacy.publication(legacyId).either
          legacyInbox    <- legacy.durablePendingDeliveries.either
          legacyAck      <- legacy.acknowledgeTerminal(legacyId).either
          finish         <- Promise.make[Nothing, Unit]
          running <- SubagentTaskRegistry.openWith(
            (_, _, _) => supervisor.attach(finish.await.as(result)),
            1,
            Some(terminal)
          )
          runningId         <- accepted(running)
          active            <- running.observe(runningId)
          emptyWhileRunning <- running.durablePendingDeliveries
          _ <- SubagentTerminalStore
            .make(backend.store, target)
            .publish(
              SubagentTerminalRecord(runningId, SubagentTerminalOutcome.Completed("exact terminal"))
            )
          preferred           <- running.observe(runningId)
          listed              <- running.observations
          storedWhileRunning  <- running.durablePendingDeliveries
          runningAcknowledged <- running.acknowledgeTerminal(runningId)
          _                   <- finish.succeed(())
          reconciled          <- running.publication(runningId)
          retainedStamp       <- running.terminalAcknowledgement(runningId)
          stillEmpty          <- running.durablePendingDeliveries
        yield assertTrue(
          finished.contains(Exit.succeed(result)),
          pending == SubagentTaskRemoval.Unpublished,
          stillPending == SubagentTaskRemoval.Unpublished,
          cancelled.isInterrupted,
          before.isEmpty,
          receipt.nonEmpty,
          observed.contains(
            SubagentTaskObservation(
              id,
              SubagentTaskObservationState.Terminal(
                SubagentTerminalOutcome.Completed("exact terminal")
              )
            )
          ),
          forgotten == SubagentTaskRemoval.Removed,
          restored == observed,
          deliveries.isLeft,
          acknowledgement.isLeft,
          inboxBefore == Chunk(
            SubagentTerminalRecord(
              id,
              SubagentTerminalOutcome.Completed("exact terminal"),
              Some(worker)
            )
          ),
          acknowledged == SubagentTerminalAcknowledgeResult.Acknowledged(ackTime),
          acknowledgedAgain == SubagentTerminalAcknowledgeResult.AlreadyAcknowledged(ackTime),
          persistedStamp.contains(SubagentTerminalAcknowledgement.Acknowledged(ackTime)),
          inboxAfter.isEmpty,
          legacyInbox.isLeft,
          legacyAck.isLeft,
          emptyWhileRunning.isEmpty,
          storedWhileRunning.map(_.id) == Chunk(runningId),
          runningAcknowledged == SubagentTerminalAcknowledgeResult.Acknowledged(ackTime),
          retainedStamp == persistedStamp,
          stillEmpty.isEmpty,
          legacyView.contains(
            SubagentTaskObservation(
              legacyId,
              SubagentTaskObservationState.Terminal(
                SubagentTerminalOutcome.Completed("exact terminal")
              )
            )
          ),
          legacyReceipt.isLeft,
          legacyWait == SubagentTaskBarrierResult.Empty,
          legacySelected == SubagentTaskBarrierResult.Completed(
            Chunk(
              SubagentTerminalRecord(
                legacyId,
                SubagentTerminalOutcome.Completed("exact terminal"),
                Some(worker)
              )
            )
          ),
          active.contains(SubagentTaskObservation(runningId, SubagentTaskObservationState.Running)),
          preferred.contains(
            SubagentTaskObservation(
              runningId,
              SubagentTaskObservationState.Terminal(
                SubagentTerminalOutcome.Completed("exact terminal")
              )
            )
          ),
          listed.find(_.id == runningId) == preferred,
          reconciled.nonEmpty
        )
    ,
    test(
      "closing joins every worker before every publisher and retains all shutdown publication failures"
    ):
      ZIO.scoped:
        for
          scope      <- owner
          supervisor <- scope.extend(TaskSupervisor.open[AgentResult])
          joined     <- Ref.make(0)
          started    <- Queue.unbounded[Unit]
          allJoined  <- Promise.make[Nothing, Unit]
          writes     <- Ref.make(0)
          target     <- address
          failing = new VersionedJsonStore:
            def read(address: StorageAddress): IO[AgentError, Option[VersionedJson]] = ZIO.none
            def compareAndSet(
                address: StorageAddress,
                expected: ExpectedRevision,
                value: Json
            ): IO[AgentError, ConditionalWrite] =
              writes.update(_ + 1) *> allJoined.await *> ZIO.fail(
                AgentError.StorageError("shutdown publication failed")
              )
          registry <- scope.extend(
            SubagentTaskRegistry.openWith(
              (_, _, _) =>
                supervisor.attach(
                  ZIO.acquireRelease(started.offer(()))(_ =>
                    joined
                      .updateAndGet(_ + 1)
                      .flatMap(count => if count == 2 then allJoined.succeed(()).unit else ZIO.unit)
                  ) *> ZIO.never
                ),
              2,
              Some(SubagentTerminalStore.make(failing, target))
            )
          )
          first        <- accepted(registry)
          second       <- accepted(registry)
          _            <- started.take.zipRight(started.take)
          _            <- scope.close(Exit.unit)
          completed    <- joined.get
          attempts     <- writes.get
          failures     <- registry.flushPublications.exit
          retained     <- ZIO.foreach(Chunk(first, second))(id => registry.publication(id).exit)
          observations <- ZIO.foreach(Chunk(first, second))(id => registry.observe(id).exit)
          removals     <- ZIO.foreach(Chunk(first, second))(registry.forgetCompleted)
          taskExits    <- ZIO.foreach(Chunk(first, second))(registry.await)
          control <- SubagentTools
            .make(registry)
            .executeSafe(
              "agent_cancel",
              Json.Obj("task_id" -> Json.Str(first.value.toString))
            )
            .exit
        yield assertTrue(
          completed == 2,
          attempts == 2,
          failures.causeOption.exists(
            _.failures
              .map(_.message) == List("shutdown publication failed", "shutdown publication failed")
          ),
          retained.forall(
            _.causeOption.exists(_.failures.map(_.message) == List("shutdown publication failed"))
          ),
          observations.forall(_.isFailure),
          removals == Chunk(SubagentTaskRemoval.Unpublished, SubagentTaskRemoval.Unpublished),
          taskExits.forall(_.exists(_.isInterrupted)),
          control.isSuccess,
          control match
            case Exit.Success(value) =>
              value.state == ToolResultState.Error &&
              value.output == "Execution failed for 'agent_cancel': shutdown publication failed"
            case Exit.Failure(_) => false
        )
    ,
    test(
      "committed terminal bytes cannot bypass a held and subsequently failed publication acknowledgement"
    ):
      ZIO
        .foreach(Chunk.fromArray(SnapshotTiming.values)): timing =>
          ZIO.scoped:
            for
              supervisor <- TaskSupervisor.open[AgentResult]
              backend    <- SubagentTerminalStoreSpec.fixture
              target     <- address
              committed  <- Promise.make[Nothing, Unit]
              release    <- Promise.make[Nothing, Unit]
              finish     <- Promise.make[Nothing, Unit]
              reads      <- Queue.unbounded[Unit]
              heldReads  <- Queue.unbounded[Unit]
              readCount  <- Ref.make(0)
              _          <- ZIO.addFinalizer(release.succeed(()).unit *> finish.succeed(()).unit)
              uncertain = new VersionedJsonStore:
                def read(address: StorageAddress): IO[AgentError, Option[VersionedJson]] =
                  readCount
                    .getAndUpdate(_ + 1)
                    .flatMap: count =>
                      (if (count == 1 || count == 2) && timing != SnapshotTiming.Completed
                       then heldReads.offer(()) *> committed.await
                       else ZIO.unit) *>
                        backend.store
                          .read(address)
                          .tap(value => if value.nonEmpty then reads.offer(()).unit else ZIO.unit)
                def compareAndSet(address: StorageAddress, expected: ExpectedRevision, value: Json)
                    : IO[AgentError, ConditionalWrite] =
                  backend.store.compareAndSet(address, expected, value) *> committed.succeed(
                    ()
                  ) *> release.await *> ZIO.fail(
                    AgentError.StorageError("Committed acknowledgement lost")
                  )
              registry <- SubagentTaskRegistry.openWith(
                (_, _, _) => supervisor.attach(finish.await.as(result)),
                1,
                Some(SubagentTerminalStore.make(uncertain, target))
              )
              id <- accepted(registry)
              _ <- ZIO.when(timing == SnapshotTiming.Completed)(
                finish.succeed(()) *> committed.await *> registry.await(id)
              )
              firstObservation =
                if timing == SnapshotTiming.BeforeAcknowledgement then
                  registry.terminalAcknowledgement(id).unit
                else registry.observe(id).unit
              secondObservation =
                if timing == SnapshotTiming.BeforeAcknowledgement then
                  registry.acknowledgeTerminal(id).unit
                else registry.observations.unit
              one <- firstObservation.timeout(1.second).forkScoped
              all <- secondObservation.timeout(1.second).forkScoped
              _ <- ZIO.when(timing != SnapshotTiming.Completed)(
                heldReads.take *> heldReads.take *> finish.succeed(
                  ()
                ) *> committed.await *> registry.await(id)
              )
              _ <- reads.take.zipRight(reads.take)
              toolkit   = SubagentTools.make(registry)
              arguments = Json.Obj("task_id" -> Json.Str(id.value.toString))
              control <- toolkit.executeSafe("agent_cancel", arguments).timeout(1.second).forkScoped
              inbox   <- registry.durablePendingDeliveries.timeout(1.second).forkScoped
              stamp   <- registry.terminalAcknowledgement(id).timeout(1.second).forkScoped
              acknowledge          <- registry.acknowledgeTerminal(id).timeout(1.second).forkScoped
              _                    <- TestClock.adjust(1.second)
              earlyOne             <- one.join
              earlyAll             <- all.join
              earlyControl         <- control.join
              earlyInbox           <- inbox.join
              earlyStamp           <- stamp.join
              earlyAcknowledgement <- acknowledge.join
              _                    <- release.succeed(())
              receipt              <- registry.publication(id).exit
              laterOne             <- registry.observe(id).exit
              laterAll             <- registry.observations.exit
              laterControl         <- toolkit.executeSafe("agent_cancel", arguments)
              laterInbox           <- registry.durablePendingDeliveries.exit
              laterStamp           <- registry.terminalAcknowledgement(id).exit
              laterAcknowledgement <- registry.acknowledgeTerminal(id).exit
              raw                  <- SubagentTerminalStore.make(backend.store, target).read(id)
              attempts             <- backend.writes.get
            yield assertTrue(
              earlyOne.isEmpty,
              earlyAll.isEmpty,
              earlyControl.isEmpty,
              earlyInbox.isEmpty,
              earlyStamp.isEmpty,
              earlyAcknowledgement.isEmpty,
              Chunk(laterInbox, laterStamp, laterAcknowledgement).forall(
                _.causeOption
                  .exists(_.failures.map(_.message) == List("Committed acknowledgement lost"))
              ),
              laterControl.state == ToolResultState.Error,
              laterControl.output == "Execution failed for 'agent_cancel': Committed acknowledgement lost",
              receipt.causeOption
                .exists(_.failures.map(_.message) == List("Committed acknowledgement lost")),
              laterOne.causeOption
                .exists(_.failures.map(_.message) == List("Committed acknowledgement lost")),
              laterAll.causeOption
                .exists(_.failures.map(_.message) == List("Committed acknowledgement lost")),
              raw.contains(
                SubagentTerminalRecord(id, SubagentTerminalOutcome.Completed("exact terminal"))
              ),
              attempts == 1
            )
        .map(_.foldLeft(assertTrue(true))(_ && _))
    ,
    test(
      "cancelled durable admission releases the gate without launching and persisted identity collisions fail before execution"
    ):
      ZIO.scoped:
        for
          supervisor <- TaskSupervisor.open[AgentResult]
          entered    <- Promise.make[Nothing, Unit]
          hold       <- Ref.make(true)
          launches   <- Ref.make(0)
          terminal = new SubagentTerminalStore:
            def read(id: SubagentTaskId): IO[AgentError, Option[SubagentTerminalRecord]] =
              hold.get.flatMap(value =>
                if value then entered.succeed(()) *> ZIO.never
                else
                  ZIO.some(
                    SubagentTerminalRecord(id, SubagentTerminalOutcome.Completed("prior truth"))
                  )
              )
            def list: IO[AgentError, Chunk[SubagentTerminalRecord]] = ZIO.succeed(Chunk.empty)
            def publish(
                record: SubagentTerminalRecord
            ): IO[AgentError, SubagentTerminalPublication] =
              ZIO.fail(AgentError.StorageError("Unexpected publication"))
          registry <- SubagentTaskRegistry.openWith(
            (_, _, _) => launches.update(_ + 1) *> supervisor.attach(ZIO.succeed(result)),
            1,
            Some(terminal)
          )
          admitting   <- registry.submit(Msg.user("work")).forkScoped
          _           <- entered.await
          interrupted <- admitting.interrupt
          before      <- launches.get
          _           <- hold.set(false)
          rejected    <- registry.submit(Msg.user("work"))
          after       <- launches.get
        yield assertTrue(
          interrupted.isInterrupted,
          before == 0,
          after == 0,
          rejected == SubagentTaskSubmission.IdentityCollision
        )
  ) @@ TestAspect.timeout(15.seconds)
