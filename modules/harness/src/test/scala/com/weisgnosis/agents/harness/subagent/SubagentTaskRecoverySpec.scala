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
import com.weisgnosis.agents.core.model.Msg
import com.weisgnosis.agents.harness.storage.*
import java.time.Instant
import java.util.UUID
import zio.*
import zio.test.*

object SubagentTaskRecoverySpec extends ZIOSpecDefault:
  private enum RetainedPhase:
    case Paused, Publishing

  private val time: Instant = Instant.parse("2026-10-08T00:00:00Z")
  private val input: Msg    = Msg.user("work")

  private val allow: SubagentTaskRecovery.Gate = new SubagentTaskRecovery.Gate:
    def claim(policy: SubagentTaskRecovery.Policy): IO[AgentError, SubagentTaskRecovery.Claim] =
      ZIO.succeed(SubagentTaskRecovery.Claim.Admitted)

  private val local: SubagentTaskRecovery.Authority = new SubagentTaskRecovery.Authority:
    def acquire(
        registry: SubagentTaskRegistry,
        expected: SubagentTaskRecord
    ): ZIO[Scope, AgentError, SubagentTaskRecovery.Decision] =
      ZIO
        .fromEither(SubagentTaskRecovery.Permit.local(expected))
        .map(SubagentTaskRecovery.Decision.Local(_))

  private def registry(
      store: SubagentTerminalStore,
      started: Option[Promise[Nothing, Unit]] = None
  ): ZIO[Scope, AgentError, SubagentTaskRegistry] =
    for
      supervisor <- TaskSupervisor.open[AgentResult]
      result <- SubagentTaskRegistry.openPreparedInput[Msg](
        identity,
        (_, _, _, beforeStart) =>
          supervisor.attach(beforeStart *> ZIO.foreachDiscard(started)(_.succeed(())) *> ZIO.never),
        16,
        Some(store)
      )
    yield result

  private def record(
      store: SubagentTerminalStore,
      id: SubagentTaskId
  ): IO[AgentError, SubagentTaskRecord] =
    store.taskRecord(id).someOrFail(AgentError.StorageError("Missing recovery fixture record"))

  private def countedClock(underlying: Clock, calls: Ref[Int]): Clock = new Clock:
    export underlying.{
      currentTime,
      currentDateTime,
      javaClock,
      localDateTime,
      nanoTime,
      scheduler,
      sleep
    }
    def instant(using Trace): UIO[Instant] = calls.update(_ + 1) *> underlying.instant

  def spec: Spec[TestEnvironment & Scope, Any] = suite("SubagentTaskRecoverySpec")(
    test(
      "host claims precede scans; stale equality recovers while fresh, terminal and retained local owners remain untouched"
    ):
      ZIO.scoped:
        for
          backend <- SubagentTerminalStoreSpec.fixture
          address <- ZIO.fromEither(StorageAddress.from(Chunk("recovery"), "public"))
          store    = SubagentTerminalStore.make(backend.store, address)
          stale    = SubagentTaskId(new UUID(0L, 701L))
          fresh    = SubagentTaskId(new UUID(0L, 702L))
          terminal = SubagentTaskId(new UUID(0L, 703L))
          _         <- TestClock.setTime(time)
          admission <- store.admit(stale)
          staleIdentity <- admission match
            case SubagentTaskAdmissionResult.Created(value) => ZIO.succeed(value)
            case other => ZIO.fail(AgentError.StorageError(s"Unexpected admission: $other"))
          _ <- store.markRunning(staleIdentity)
          _ <- store.admit(terminal)
          _ <- store.publish(
            SubagentTerminalRecord(terminal, SubagentTerminalOutcome.Completed("kept"))
          )
          _              <- store.acknowledge(terminal)
          terminalBefore <- record(store, terminal)
          _              <- TestClock.adjust(1.nanos)
          _              <- store.admit(fresh)
          _              <- TestClock.setTime(time)
          started        <- Promise.make[Nothing, Unit]
          owner          <- registry(store, Some(started))
          live <- owner
            .submit(input)
            .flatMap:
              case SubagentTaskSubmission.Accepted(id) => ZIO.succeed(id)
              case other => ZIO.fail(AgentError.StorageError(s"Unexpected admission: $other"))
          _          <- started.await
          liveBefore <- record(store, live)
          policy     <- ZIO.fromEither(SubagentTaskRecovery.Policy.make(Duration.Zero, 1.second))
          gate       <- SubagentTaskRecovery.Gate.local
          claims     <- Ref.make(0)
          scans      <- Ref.make(0)
          observed = new SubagentTerminalStore:
            export store.{read, list, publish}
            override def taskRecords: IO[AgentError, Chunk[SubagentTaskRecord]] =
              scans.update(_ + 1) *> store.taskRecords
          empty <- registry(observed)
          denied = new SubagentTaskRecovery.Gate:
            def claim(
                policy: SubagentTaskRecovery.Policy
            ): IO[AgentError, SubagentTaskRecovery.Claim] =
              claims.update(_ + 1).as(SubagentTaskRecovery.Claim.Throttled)
          clock      <- ZIO.clock
          clockCalls <- Ref.make(0)
          skipped <- empty
            .recoverStaleTasks(policy, denied, local)
            .withClock(countedClock(clock, clockCalls))
          deniedClockCalls <- clockCalls.get
          scanCount        <- scans.get
          claimCount       <- claims.get
          authorized       <- Ref.make(Chunk.empty[SubagentTaskId])
          countedAuthority = new SubagentTaskRecovery.Authority:
            def acquire(
                registry: SubagentTaskRegistry,
                expected: SubagentTaskRecord
            ): ZIO[Scope, AgentError, SubagentTaskRecovery.Decision] =
              authorized.update(_ :+ expected.id) *> local.acquire(registry, expected)
          swept         <- owner.recoverStaleTasks(policy, gate, countedAuthority)
          authorizedIds <- authorized.get
          throttled     <- owner.recoverStaleTasks(policy, gate, local)
          _             <- TestClock.setTime(time.minusSeconds(1L))
          backward      <- gate.claim(policy)
          gapGate       <- SubagentTaskRecovery.Gate.local
          firstGapClaim <- gapGate.claim(policy)
          _             <- TestClock.adjust(1.second.minusNanos(1L))
          beforeGap     <- gapGate.claim(policy)
          _             <- TestClock.adjust(1.nanos)
          exactGap      <- gapGate.claim(policy)
          defaultPolicy <- ZIO.fromEither(SubagentTaskRecovery.Policy.make())
          recovered     <- record(store, stale)
          freshAfter    <- record(store, fresh)
          terminalAfter <- record(store, terminal)
          liveAfter     <- record(store, live)
          pending       <- store.pending
          retainedChecks <- ZIO.foreach(Chunk.fromIterable(RetainedPhase.values)): phase =>
            ZIO.scoped:
              for
                heldBackend <- SubagentTerminalStoreSpec.fixture
                entered     <- Promise.make[Nothing, Unit]
                release     <- Promise.make[Nothing, Unit]
                calls       <- Ref.make(0)
                controlled = new VersionedJsonStore:
                  def read(address: StorageAddress): IO[AgentError, Option[VersionedJson]] =
                    heldBackend.store.read(address)
                  def compareAndSet(
                      address: StorageAddress,
                      expected: ExpectedRevision,
                      value: zio.json.ast.Json
                  ): IO[AgentError, ConditionalWrite] =
                    calls
                      .updateAndGet(_ + 1)
                      .flatMap: index =>
                        val held = phase match
                          case RetainedPhase.Paused     => index == 2
                          case RetainedPhase.Publishing => index == 3
                        (if held then entered.succeed(()) *> release.await else ZIO.unit) *>
                          heldBackend.store.compareAndSet(address, expected, value)
                heldStore = SubagentTerminalStore.make(controlled, address)
                supervisor <- TaskSupervisor.open[AgentResult]
                reply = Msg.assistant("joined")
                heldOwner <- SubagentTaskRegistry.openPreparedInput[Msg](
                  identity,
                  (_, _, _, start) =>
                    supervisor.attach(start.as(AgentResult(reply, Chunk(reply), 1))),
                  4,
                  Some(heldStore)
                )
                _          <- ZIO.addFinalizer(release.succeed(()).unit)
                submission <- heldOwner.submit(input)
                id <- submission match
                  case SubagentTaskSubmission.Accepted(value) => ZIO.succeed(value)
                  case other =>
                    ZIO.fail(AgentError.StorageError(s"Unexpected retained admission: $other"))
                _ <- entered.await
                _ <- phase match
                  case RetainedPhase.Paused     => ZIO.unit
                  case RetainedPhase.Publishing => heldOwner.await(id).unit
                before <- heldBackend.store.read(address)
                forbidden = new SubagentTaskRecovery.Authority:
                  def acquire(
                      registry: SubagentTaskRegistry,
                      expected: SubagentTaskRecord
                  ): ZIO[Scope, AgentError, SubagentTaskRecovery.Decision] =
                    ZIO.fail(AgentError.StorageError("Retained owner must not acquire authority"))
                result <- heldOwner.recoverStaleTasks(policy, allow, forbidden)
                after  <- heldBackend.store.read(address)
                _      <- release.succeed(())
              yield result == SubagentTaskRecovery.Result.Swept(
                Chunk(
                  SubagentTaskRecovery.Receipt(id, SubagentTaskRecovery.Disposition.RetainedLocal)
                )
              ) && before == after
          receipts = swept match
            case SubagentTaskRecovery.Result.Swept(values) => values
            case _                                         => Chunk.empty
        yield assertTrue(
          skipped == SubagentTaskRecovery.Result.Throttled,
          claimCount == 1,
          scanCount == 0,
          deniedClockCalls == 0,
          throttled == SubagentTaskRecovery.Result.Throttled,
          backward == SubagentTaskRecovery.Claim.Throttled,
          firstGapClaim == SubagentTaskRecovery.Claim.Admitted,
          beforeGap == SubagentTaskRecovery.Claim.Throttled,
          exactGap == SubagentTaskRecovery.Claim.Admitted,
          defaultPolicy.staleAfter == 10.minutes,
          defaultPolicy.minGap == 5.minutes,
          SubagentTaskRecovery.Policy.make((-1).seconds).isLeft,
          SubagentTaskRecovery.Policy.make(Duration.Infinity, Duration.Zero).isLeft,
          SubagentTaskRecovery.Policy.make(Duration.Zero, Duration.Infinity).isLeft,
          SubagentTaskRecovery.Policy.make(Duration.Zero, Duration.Zero).isRight,
          SubagentTaskRecovery.Policy.make(Duration.fromNanos(Long.MaxValue - 1L)).isRight,
          recovered.state == SubagentTaskRecordState.Terminal(
            SubagentTerminalOutcome.Failed(
              Chunk(SubagentTaskFailure("executor lost", None)),
              SubagentTaskDefects.Absent
            ),
            SubagentTerminalAcknowledgement.Pending
          ),
          freshAfter.state == SubagentTaskRecordState.Pending,
          terminalAfter == terminalBefore,
          liveAfter == liveBefore,
          !authorizedIds.contains(live),
          pending.map(_.id) == Chunk(stale),
          receipts.exists(receipt =>
            receipt.id == live && receipt.disposition == SubagentTaskRecovery.Disposition.RetainedLocal
          ),
          !receipts.exists(_.id == fresh),
          !receipts.exists(_.id == terminal),
          retainedChecks.forall(identity)
        )
    ,
    test(
      "scoped authority joins cleanup, final ownership recheck protects new admissions, and uncertain mutation never retries"
    ):
      ZIO.scoped:
        for
          backend <- SubagentTerminalStoreSpec.fixture
          address <- ZIO.fromEither(StorageAddress.from(Chunk("recovery"), "races"))
          store = SubagentTerminalStore.make(backend.store, address)
          _        <- TestClock.setTime(time)
          policy   <- ZIO.fromEither(SubagentTaskRecovery.Policy.make(Duration.Zero, Duration.Zero))
          launches <- Ref.make(0)
          supervisor <- TaskSupervisor.open[AgentResult]
          // Terminal-only fixture admissions are intentionally unconfirmed, but retained owner
          // capabilities must still be protected if they appear after the sweep snapshot.
          owner <- SubagentTaskRegistry.openWith(
            (_, _, _) => launches.update(_ + 1) *> supervisor.attach(ZIO.never),
            16,
            Some(store)
          )
          _        <- TestRandom.feedLongs(11L, 22L)
          upcoming <- Random.nextUUID.map(SubagentTaskId(_))
          _        <- store.admit(upcoming)
          entered  <- Promise.make[Nothing, Unit]
          release  <- Promise.make[Nothing, Unit]
          _        <- ZIO.addFinalizer(release.succeed(()).unit)
          authority = new SubagentTaskRecovery.Authority:
            def acquire(
                registry: SubagentTaskRegistry,
                expected: SubagentTaskRecord
            ): ZIO[Scope, AgentError, SubagentTaskRecovery.Decision] =
              entered.succeed(()) *> release.await *> local.acquire(registry, expected)
          sweep <- owner.recoverStaleTasks(policy, allow, authority).forkScoped
          _     <- entered.await
          // Authority acquisition must not hold registry admission while the host decides.
          _               <- TestRandom.feedLongs(11L, 22L)
          concurrent      <- owner.submit(input)
          _               <- release.succeed(())
          swept           <- sweep.join
          retained        <- record(store, upcoming)
          _               <- TestRandom.feedLongs(33L, 44L)
          reconciledId    <- Random.nextUUID.map(SubagentTaskId(_))
          _               <- store.admit(reconciledId)
          _               <- owner.recoverStaleTasks(policy, allow, local)
          beforeCollision <- launches.get
          _               <- TestRandom.feedLongs(33L, 44L)
          collision       <- owner.submit(input)
          afterCollision  <- launches.get
          writes          <- Ref.make(0)
          lost = AgentError.StorageError("Recovery receipt lost")
          uncertain = new VersionedJsonStore:
            def read(address: StorageAddress): IO[AgentError, Option[VersionedJson]] =
              backend.store.read(address)
            def compareAndSet(
                address: StorageAddress,
                expected: ExpectedRevision,
                value: zio.json.ast.Json
            ): IO[AgentError, ConditionalWrite] =
              writes.update(_ + 1) *> backend.store.compareAndSet(address, expected, value) *> ZIO
                .fail(lost)
          next = SubagentTaskId(new UUID(0L, 704L))
          _              <- store.admit(next)
          uncertainOwner <- registry(SubagentTerminalStore.make(uncertain, address))
          uncertaintyAuthority = new SubagentTaskRecovery.Authority:
            def acquire(
                registry: SubagentTaskRegistry,
                expected: SubagentTaskRecord
            ): ZIO[Scope, AgentError, SubagentTaskRecovery.Decision] =
              if expected.id == upcoming then
                ZIO.succeed(SubagentTaskRecovery.Decision.OwnerPresent)
              else local.acquire(registry, expected)
          uncertainResult <- uncertainOwner
            .recoverStaleTasks(policy, allow, uncertaintyAuthority)
            .either
          attempts       <- writes.get
          persisted      <- record(store, next)
          cleanupEntered <- Promise.make[Nothing, Unit]
          cleanupRelease <- Promise.make[Nothing, Unit]
          _              <- ZIO.addFinalizer(cleanupRelease.succeed(()).unit)
          defect = new IllegalStateException("Recovery authority cleanup")
          acquiring <- Promise.make[Nothing, Unit]
          held = new SubagentTaskRecovery.Authority:
            def acquire(
                registry: SubagentTaskRegistry,
                expected: SubagentTaskRecord
            ): ZIO[Scope, AgentError, SubagentTaskRecovery.Decision] =
              ZIO.acquireRelease(ZIO.unit)(_ =>
                cleanupEntered.succeed(()).unit *> cleanupRelease.await *> ZIO.die(defect)
              ) *> acquiring.succeed(()) *> ZIO.never
          last = SubagentTaskId(new UUID(0L, 705L))
          _               <- store.admit(last)
          beforeDecisions <- backend.store.read(address)
          decisions <- ZIO.foreach(
            Chunk(
              SubagentTaskRecovery.Decision.Remote -> SubagentTaskRecovery.Disposition.Remote,
              SubagentTaskRecovery.Decision.OwnerPresent -> SubagentTaskRecovery.Disposition.OwnerPresent,
              SubagentTaskRecovery.Decision.Unknown -> SubagentTaskRecovery.Disposition.Unknown
            )
          ): (decision, expectedDisposition) =>
            val classify = new SubagentTaskRecovery.Authority:
              def acquire(
                  registry: SubagentTaskRegistry,
                  expected: SubagentTaskRecord
              ): ZIO[Scope, AgentError, SubagentTaskRecovery.Decision] =
                ZIO.succeed(decision)
            owner
              .recoverStaleTasks(policy, allow, classify)
              .map:
                case SubagentTaskRecovery.Result.Swept(values) =>
                  values.exists(receipt =>
                    receipt.id == last && receipt.disposition == expectedDisposition
                  )
                case _ => false
          afterDecisions <- backend.store.read(address)
          cancelled <- ZIO.uninterruptibleMask: restore =>
            for
              fiber <- restore(owner.recoverStaleTasks(policy, allow, held)).forkScoped
              _     <- ZIO.addFinalizer(cleanupRelease.succeed(()).unit)
            yield fiber
          _             <- acquiring.await
          controller    <- ZIO.fiberId
          _             <- cancelled.interruptAsFork(controller)
          _             <- cleanupEntered.await
          early         <- cancelled.poll
          _             <- cleanupRelease.succeed(())
          cancelledExit <- cancelled.await
          cause = cancelledExit.causeOption.getOrElse(Cause.empty)
        yield assertTrue(
          concurrent == SubagentTaskSubmission.Accepted(upcoming),
          collision == SubagentTaskSubmission.IdentityCollision,
          beforeCollision == afterCollision,
          retained.state == SubagentTaskRecordState.Pending,
          swept match
            case SubagentTaskRecovery.Result.Swept(values) =>
              values.exists(receipt =>
                receipt.id == upcoming && receipt.disposition == SubagentTaskRecovery.Disposition.RetainedLocal
              )
            case _ => false,
          uncertainResult == Left(lost),
          attempts == 1,
          persisted.terminal.nonEmpty,
          decisions.forall(identity),
          beforeDecisions == afterDecisions,
          early.isEmpty,
          cause.interruptors.contains(controller),
          cause.defects == List(defect)
        )
  )
