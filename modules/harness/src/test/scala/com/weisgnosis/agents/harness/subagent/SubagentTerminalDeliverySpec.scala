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
import com.weisgnosis.agents.harness.storage.*
import java.util.UUID
import zio.*
import zio.test.*

object SubagentTerminalDeliverySpec extends ZIOSpecDefault:
  private def address(owner: String): IO[AgentError, StorageAddress] =
    ZIO.fromEither(StorageAddress.from(Chunk("terminal-delivery", owner), "tasks"))

  private def registry(store: SubagentTerminalStore): ZIO[Scope, AgentError, SubagentTaskRegistry] =
    SubagentTaskRegistry.openWith(
      (_, _, _) =>
        ZIO.fail(AgentError.InvalidConfiguration("Delivery fixture cannot execute tasks")),
      16,
      Some(store)
    )

  private def port(
      store: SubagentTerminalStore,
      pendingEffect: IO[AgentError, Chunk[SubagentTerminalRecord]],
      acknowledgeEffect: SubagentTaskId => IO[AgentError, SubagentTerminalAcknowledgeResult]
  ): SubagentTerminalStore = new SubagentTerminalStore:
    def read(id: SubagentTaskId): IO[AgentError, Option[SubagentTerminalRecord]] = store.read(id)
    def list: IO[AgentError, Chunk[SubagentTerminalRecord]]                      = store.list
    def publish(record: SubagentTerminalRecord): IO[AgentError, SubagentTerminalPublication] =
      store.publish(record)
    override def pending: IO[AgentError, Chunk[SubagentTerminalRecord]] = pendingEffect
    override def acknowledgement(
        id: SubagentTaskId
    ): IO[AgentError, Option[SubagentTerminalAcknowledgement]] = store.acknowledgement(id)
    override def acknowledge(
        id: SubagentTaskId
    ): IO[AgentError, SubagentTerminalAcknowledgeResult] = acknowledgeEffect(id)

  private val records: Chunk[SubagentTerminalRecord] = Chunk
    .fromIterable(1 to 12)
    .map(index =>
      SubagentTerminalRecord(
        SubagentTaskId(new UUID(0L, index.toLong)),
        SubagentTerminalOutcome.Completed(s"exact λ $index")
      )
    )

  def spec: Spec[TestEnvironment & Scope, Any] = suite("SubagentTerminalDeliverySpec")(
    test(
      "bounded delivery acknowledges only included observations; empty and corrupt snapshots skip delivery, concurrent duplicates and uncertain receipts remain explicit"
    ):
      ZIO.scoped:
        for
          backend <- SubagentTerminalStoreSpec.fixture
          target  <- address("bounded")
          store = SubagentTerminalStore.make(backend.store, target)
          _         <- ZIO.foreachDiscard(records)(store.publish)
          owner     <- registry(store)
          delivered <- Ref.make(Chunk.empty[Chunk[SubagentTerminalRecord]])
          receipts <- SubagentTerminalDelivery.deliverPending(owner)(batch =>
            delivered.update(_ :+ batch)
          )
          remaining <- store.pending
          _         <- ZIO.foreachDiscard(remaining)(record => store.acknowledge(record.id))
          empty <- SubagentTerminalDelivery.deliverPending(owner)(batch =>
            delivered.update(_ :+ batch)
          )
          callbacks     <- delivered.get
          duplicateAcks <- Ref.make(0)
          duplicateStore = port(
            store,
            ZIO.succeed(records.take(1) ++ records.take(1)),
            id => duplicateAcks.update(_ + 1) *> store.acknowledge(id)
          )
          duplicateOwner <- registry(duplicateStore)
          duplicate <- SubagentTerminalDelivery
            .deliverPending(duplicateOwner)(batch => delivered.update(_ :+ batch))
            .exit
          duplicateCount   <- duplicateAcks.get
          afterDuplicate   <- delivered.get
          concurrentTarget <- address("concurrent")
          concurrentStore = SubagentTerminalStore.make(backend.store, concurrentTarget)
          _               <- ZIO.foreachDiscard(records.take(1))(concurrentStore.publish)
          concurrentOwner <- registry(concurrentStore)
          calls           <- Ref.make(0)
          both            <- Promise.make[Nothing, Unit]
          concurrentDelivery = SubagentTerminalDelivery.deliverPending(concurrentOwner)(_ =>
            calls
              .updateAndGet(_ + 1)
              .flatMap(count => both.succeed(()).when(count == 2)) *> both.await
          )
          concurrent          <- concurrentDelivery.zipPar(concurrentDelivery)
          duplicateDeliveries <- calls.get
          uncertainTarget     <- address("uncertain")
          uncertainStore = SubagentTerminalStore.make(backend.store, uncertainTarget)
          _        <- ZIO.foreachDiscard(records.take(3))(uncertainStore.publish)
          attempts <- Ref.make(0)
          failure = AgentError.StorageError("Delivery acknowledgement receipt lost")
          uncertainPort = port(
            uncertainStore,
            uncertainStore.pending,
            id =>
              attempts
                .updateAndGet(_ + 1)
                .flatMap:
                  case 2 => uncertainStore.acknowledge(id) *> ZIO.fail(failure)
                  case _ => uncertainStore.acknowledge(id)
          )
          uncertainOwner <- registry(uncertainPort)
          uncertain <- SubagentTerminalDelivery.deliverPending(uncertainOwner)(_ => ZIO.unit).exit
          attempted <- attempts.get
          pending   <- uncertainStore.pending
          missingPort = port(
            store,
            ZIO.succeed(records.take(1)),
            _ => ZIO.succeed(SubagentTerminalAcknowledgeResult.Missing)
          )
          missingOwner <- registry(missingPort)
          missing      <- SubagentTerminalDelivery.deliverPending(missingOwner)(_ => ZIO.unit).exit
        yield assertTrue(
          callbacks == Chunk(records.take(10)),
          receipts.map(_.id) == records.take(10).map(_.id),
          remaining == records.drop(10),
          empty.isEmpty,
          duplicate.isFailure,
          duplicateCount == 0,
          afterDuplicate == callbacks,
          duplicateDeliveries == 2,
          (concurrent._1 ++ concurrent._2).count(_.acknowledgement match
            case SubagentTerminalAcknowledgeResult.Acknowledged(_) => true
            case _                                                 => false) == 1,
          (concurrent._1 ++ concurrent._2).count(_.acknowledgement match
            case SubagentTerminalAcknowledgeResult.AlreadyAcknowledged(_) => true
            case _                                                        => false) == 1,
          uncertain == Exit.fail(failure),
          attempted == 2,
          pending == records.slice(2, 3),
          missing.causeOption.exists(_.failures.exists:
            case AgentError.StorageError(_) => true
            case _                          => false)
        )
    ,
    test(
      "callback failure and cancellation retain causes, join owned cleanup and never acknowledge before successful scope closure"
    ):
      ZIO.scoped:
        for
          backend <- SubagentTerminalStoreSpec.fixture
          target  <- address("cleanup")
          store = SubagentTerminalStore.make(backend.store, target)
          _        <- ZIO.foreachDiscard(records.take(1))(store.publish)
          ackCount <- Ref.make(0)
          observedStore = port(
            store,
            store.pending,
            id => ackCount.update(_ + 1) *> store.acknowledge(id)
          )
          owner <- registry(observedStore)
          failure = AgentError.StorageError("Delivery rejected")
          failed <- SubagentTerminalDelivery.deliverPending(owner)(_ => ZIO.fail(failure)).exit
          defect = new IllegalStateException("Delivery cleanup defect")
          defective <- SubagentTerminalDelivery
            .deliverPending(owner)(_ => ZIO.acquireRelease(ZIO.unit)(_ => ZIO.die(defect)).unit)
            .exit
          cleanup <- Promise.make[Nothing, Unit]
          release <- Promise.make[Nothing, Unit]
          active  <- Promise.make[Nothing, Unit]
          worker <- ZIO.uninterruptibleMask: restore =>
            for
              fiber <- restore(
                SubagentTerminalDelivery.deliverPending(owner)(_ =>
                  ZIO.acquireRelease(ZIO.unit)(_ =>
                    cleanup.succeed(()) *> release.await *> ZIO.die(defect)
                  ) *> active.succeed(()) *> ZIO.never
                )
              ).forkScoped
              _ <- ZIO.addFinalizer(release.succeed(()).unit)
            yield fiber
          _ <- active.await
          cancelling <- ZIO.uninterruptibleMask: restore =>
            for
              fiber <- restore(worker.interrupt).forkScoped
              _     <- ZIO.addFinalizer(release.succeed(()).unit)
            yield fiber
          _                   <- cleanup.await
          early               <- cancelling.poll
          before              <- ackCount.get
          _                   <- release.succeed(())
          cancelled           <- cancelling.join
          pendingAfterFailure <- store.pending
          joined              <- Promise.make[Nothing, Unit]
          childCleanup        <- Promise.make[Nothing, Unit]
          finishChild         <- Promise.make[Nothing, Unit]
          childActive         <- Promise.make[Nothing, Unit]
          successful <- ZIO.uninterruptibleMask: restore =>
            for
              fiber <- restore(
                SubagentTerminalDelivery.deliverPending(owner)(_ =>
                  (ZIO.acquireRelease(ZIO.unit)(_ =>
                    childCleanup.succeed(()) *> finishChild.await *> joined.succeed(())
                  ) *> childActive.succeed(()) *> ZIO.never).forkScoped *> childActive.await
                )
              ).forkScoped
              _ <- ZIO.addFinalizer(finishChild.succeed(()).unit)
            yield fiber
          _             <- childCleanup.await
          beforeJoined  <- ackCount.get
          stillJoining  <- successful.poll
          _             <- finishChild.succeed(())
          delivered     <- successful.join
          cleanupJoined <- joined.isDone
          after         <- ackCount.get
        yield assertTrue(
          failed == Exit.fail(failure),
          defective.causeOption.exists(_.defects == List(defect)),
          early.isEmpty,
          before == 0,
          cancelled.isInterrupted,
          cancelled.causeOption.exists(cause =>
            cause.defects == List(defect) && cause.failures.isEmpty
          ),
          pendingAfterFailure == records.take(1),
          beforeJoined == 0,
          stillJoining.isEmpty,
          cleanupJoined,
          after == 1,
          delivered.map(_.id) == records.take(1).map(_.id)
        )
  ) @@ TestAspect.timeout(15.seconds)
