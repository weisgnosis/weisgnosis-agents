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
package com.weisgnosis.agents.storage.postgresql

import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.harness.memory.*
import com.weisgnosis.agents.harness.storage.*
import java.time.Instant
import zio.*
import zio.json.ast.Json
import zio.test.*

object VersionedMemoryTriggerGateSpec extends ZIOSpecDefault:
  import PostgresFixture.*

  private val user: MemoryScope                = MemoryScope.User(Some("alice"))
  private val admitted: MemoryTriggerDecision  = MemoryTriggerDecision.Admitted
  private val throttled: MemoryTriggerDecision = MemoryTriggerDecision.Throttled

  private def policy(gap: Duration): IO[AgentError, MemoryTriggerPolicy] =
    ZIO.fromEither(MemoryTrigger.throttled(gap)).map(value => MemoryTriggerPolicy(value, value))

  private def gate(
      backend: VersionedJsonStore,
      target: StorageAddress,
      selected: MemoryTriggerPolicy,
      now: UIO[Instant],
      attempts: Int = 32
  ): IO[AgentError, MemoryTriggerGate] =
    ZIO.fromEither(VersionedMemoryTriggerGate.make(backend, target, selected, attempts, now))

  private def claim(
      instance: MemoryTriggerGate,
      scope: MemoryScope = user
  ): IO[AgentError, MemoryTriggerDecision] =
    instance.claim(MemoryOperation.Flush, scope)

  def spec: Spec[TestEnvironment, Any] = suite("VersionedMemoryTriggerGateSpec")(
    test("independent database clients admit one contender and retain shared structural slots"):
      fixture: (source, backend, name) =>
        for
          target        <- address(name, "gate")
          other         <- address(name + "-other", "gate")
          secondSource  <- dataSource(name + "-second")
          secondBackend <- store(secondSource)
          selected      <- policy(1.hour)
          first         <- gate(backend, target, selected, ZIO.succeed(Instant.EPOCH))
          second        <- gate(secondBackend, target, selected, ZIO.succeed(Instant.EPOCH))
          outcomes <- ZIO.foreachPar(Chunk.fromIterable(1 to 16))(index =>
            claim(if index % 2 == 0 then first else second)
          )
          reopenedBackend <- store(source)
          reopened        <- gate(reopenedBackend, target, selected, ZIO.succeed(Instant.EPOCH))
          repeated        <- claim(reopened)
          isolated        <- gate(secondBackend, other, selected, ZIO.succeed(Instant.EPOCH))
          separate        <- claim(isolated)
          scopes = Chunk(
            MemoryScope.User(None),
            MemoryScope.User(Some(" ")),
            MemoryScope.Session(Some("alice")),
            MemoryScope.Agent,
            MemoryScope.Global
          )
          firstSlots  <- ZIO.foreach(scopes)(claim(first, _))
          secondSlots <- ZIO.foreach(scopes)(claim(second, _))
          maintenance <- second.claim(MemoryOperation.Maintenance, user)
          mismatch <- policy(2.hours)
            .flatMap(gate(backend, target, _, ZIO.succeed(Instant.EPOCH)))
            .flatMap(claim(_))
            .either
        yield assertTrue(
          outcomes.count(_ == admitted) == 1,
          outcomes.count(_ == throttled) == 15,
          repeated == throttled,
          separate == admitted,
          firstSlots == Chunk(admitted, throttled, admitted, admitted, admitted),
          secondSlots.forall(_ == throttled),
          maintenance == admitted,
          mismatch.left.exists(_.isInstanceOf[AgentError.MemoryStorageError])
        )
    ,
    test(
      "exact expiry, backward time and cleanup preserve active windows without eager clock work"
    ):
      fixture: (_, backend, name) =>
        for
          target            <- address(name, "clock")
          selected          <- policy(5.seconds)
          time              <- Ref.make(Instant.EPOCH)
          reads             <- Ref.make(0)
          instance          <- gate(backend, target, selected, reads.update(_ + 1) *> time.get)
          constructionReads <- reads.get
          initial           <- claim(instance)
          _                 <- time.set(Instant.EPOCH.minusSeconds(1))
          backward          <- claim(instance)
          retention         <- ZIO.fromEither(MemoryInterval.fromDuration(1.second))
          rollbackCleanup   <- instance.cleanup(retention)
          _                 <- time.set(Instant.EPOCH.plusSeconds(5).minusNanos(1))
          before            <- claim(instance)
          activeCleanup     <- instance.cleanup(retention)
          _                 <- time.set(Instant.EPOCH.plusSeconds(5))
          exact             <- claim(instance)
          _                 <- time.set(Instant.EPOCH.plusSeconds(10))
          longerRetention   <- ZIO.fromEither(MemoryInterval.fromDuration(10.seconds))
          retainedExpired   <- instance.cleanup(longerRetention)
          removed           <- instance.cleanup(retention)
          again             <- claim(instance)
          unchangedBefore   <- backend.read(target)
          emptyCleanup      <- instance.cleanup(retention)
          unchangedAfter    <- backend.read(target)
          noIo = new VersionedJsonStore:
            def read(target: StorageAddress): IO[AgentError, Option[VersionedJson]] =
              ZIO.fail(AgentError.StorageError("unexpected read"))
            def compareAndSet(target: StorageAddress, expected: ExpectedRevision, value: Json)
                : IO[AgentError, ConditionalWrite] =
              ZIO.fail(AgentError.StorageError("unexpected write"))
          fixed <- gate(
            noIo,
            target,
            MemoryTriggerPolicy(MemoryTrigger.Always, MemoryTrigger.Never),
            ZIO.dieMessage("unexpected clock")
          )
          always <- claim(fixed)
          never  <- fixed.claim(MemoryOperation.Maintenance, user)
        yield assertTrue(
          constructionReads == 0,
          initial == admitted,
          backward == throttled,
          rollbackCleanup == 0,
          before == throttled,
          activeCleanup == 0,
          exact == admitted,
          retainedExpired == 0,
          removed == 1,
          again == admitted,
          emptyCleanup == 0,
          unchangedBefore == unchangedAfter,
          always == admitted,
          never == MemoryTriggerDecision.Disabled
        )
    ,
    test(
      "strict persisted schema and uncertain publication cannot silently reopen or retry a reservation"
    ):
      fixture: (_, backend, name) =>
        for
          target     <- address(name, "failures")
          selected   <- policy(1.hour)
          clockReads <- Ref.make(0)
          now = clockReads.update(_ + 1).as(Instant.EPOCH)
          attempts <- Ref.make(0)
          conflicts = new VersionedJsonStore:
            def read(address: StorageAddress): IO[AgentError, Option[VersionedJson]] =
              backend.read(address)
            def compareAndSet(address: StorageAddress, expected: ExpectedRevision, value: Json)
                : IO[AgentError, ConditionalWrite] =
              attempts
                .getAndUpdate(_ + 1)
                .flatMap(previous =>
                  if previous < 2 then ZIO.succeed(ConditionalWrite.Conflict)
                  else backend.compareAndSet(address, expected, value)
                )
          retried <- gate(conflicts, target, selected, now).flatMap(claim(_))
          calls   <- attempts.get
          sampled <- clockReads.get
          initial <- backend.read(target).someOrFail(AgentError.StorageError("missing gate"))
          duplicated = initial.value match
            case obj: Json.Obj =>
              Json.Obj(
                obj.fields.map((key, value) =>
                  key -> (value match
                    case Json.Arr(entries) if key == "reservations" => Json.Arr(entries ++ entries)
                    case other                                      => other)
                )
              )
            case other => other
          malformed = Chunk(
            duplicated,
            Json.Obj("schemaVersion" -> Json.Num(1)),
            initial.value match
              case obj: Json.Obj => Json.Obj(obj.fields :+ ("extra" -> Json.Bool(true)))
              case other         => other,
            initial.value match
              case obj: Json.Obj =>
                Json.Obj(
                  obj.fields.map((key, value) =>
                    key -> (if key == "schemaVersion" then Json.Num(2) else value)
                  )
                )
              case other => other
          )
          rejected <- ZIO.foreach(malformed.zipWithIndex): (value, index) =>
            for
              bad         <- address(name, s"bad-$index")
              publication <- backend.compareAndSet(bad, ExpectedRevision.Absent, value)
              loaded      <- gate(backend, bad, selected, ZIO.succeed(Instant.EPOCH))
              result      <- claim(loaded).either
              after       <- backend.read(bad)
            yield publication.isInstanceOf[ConditionalWrite.Applied] && result.left.exists(
              _.isInstanceOf[AgentError.MemoryStorageError]
            ) && (publication match
              case ConditionalWrite.Applied(revision) =>
                after.contains(VersionedJson(value, revision))
              case ConditionalWrite.Conflict => false)
          cancelTarget <- address(name, "cancelled")
          cancelCalls  <- Ref.make(0)
          entered      <- Promise.make[Nothing, Unit]
          cleaned      <- Ref.make(false)
          cancelled = new VersionedJsonStore:
            def read(address: StorageAddress): IO[AgentError, Option[VersionedJson]] =
              backend.read(address)
            def compareAndSet(address: StorageAddress, expected: ExpectedRevision, value: Json)
                : IO[AgentError, ConditionalWrite] =
              (cancelCalls.update(_ + 1) *> backend.compareAndSet(address, expected, value) *>
                entered.succeed(()) *> ZIO.never).ensuring(cleaned.set(true))
          cancellation <- ZIO.scoped:
            for
              instance <- gate(cancelled, cancelTarget, selected, ZIO.succeed(Instant.EPOCH))
              fiber    <- claim(instance).forkScoped
              _        <- entered.await
              exit     <- fiber.interrupt
              closed   <- cleaned.get
              calls    <- cancelCalls.get
            yield exit.isInterrupted && closed && calls == 1
          afterCancellation <- gate(backend, cancelTarget, selected, ZIO.succeed(Instant.EPOCH))
            .flatMap(claim(_))
          lostTarget <- address(name, "lost")
          lostCalls  <- Ref.make(0)
          lost = new VersionedJsonStore:
            def read(address: StorageAddress): IO[AgentError, Option[VersionedJson]] =
              backend.read(address)
            def compareAndSet(address: StorageAddress, expected: ExpectedRevision, value: Json)
                : IO[AgentError, ConditionalWrite] =
              lostCalls.update(_ + 1) *> backend
                .compareAndSet(address, expected, value)
                .flatMap:
                  case ConditionalWrite.Applied(_) =>
                    ZIO.fail(AgentError.StorageError("lost acknowledgement"))
                  case ConditionalWrite.Conflict =>
                    ZIO.fail(AgentError.StorageError("unexpected fixture conflict"))
          uncertain <- gate(lost, lostTarget, selected, ZIO.succeed(Instant.EPOCH))
            .flatMap(claim(_))
            .either
          uncertainCalls <- lostCalls.get
          recovered <- gate(backend, lostTarget, selected, ZIO.succeed(Instant.EPOCH))
            .flatMap(claim(_))
          exhaustedTarget <- address(name, "exhausted")
          exhausted       <- gate(conflicts, exhaustedTarget, selected, now, attempts = 1)
          _               <- attempts.set(0)
          exhaustion      <- claim(exhausted).either
          exhaustedCalls  <- attempts.get
          noPublication   <- backend.read(exhaustedTarget)
        yield assertTrue(
          retried == admitted,
          calls == 3,
          sampled == 1,
          rejected.forall(identity),
          uncertain.left.exists(_.isInstanceOf[AgentError.StorageError]),
          uncertainCalls == 1,
          cancellation,
          afterCancellation == throttled,
          recovered == throttled,
          exhaustion.isLeft,
          exhaustedCalls == 1,
          noPublication.isEmpty,
          VersionedMemoryTriggerGate.make(backend, target, selected, maxAttempts = 0).isLeft
        )
  ) @@ TestAspect.sequential @@ TestAspect.withLiveRandom @@ TestAspect.withLiveSystem @@ TestAspect.withLiveClock @@
    TestAspect.timeout(30.seconds) @@ TestAspect.ifEnvSet("WEISGNOSIS_POSTGRES_URL")
