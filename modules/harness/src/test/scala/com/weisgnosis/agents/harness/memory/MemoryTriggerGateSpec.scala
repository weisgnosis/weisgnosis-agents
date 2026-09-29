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
package com.weisgnosis.agents.harness.memory

import com.weisgnosis.agents.core.error.AgentError
import java.time.Instant
import zio.*
import zio.test.*

object MemoryTriggerGateSpec extends ZIOSpecDefault:
  private val admitted: MemoryTriggerDecision  = MemoryTriggerDecision.Admitted
  private val throttled: MemoryTriggerDecision = MemoryTriggerDecision.Throttled
  private val user: MemoryScope                = MemoryScope.User(Some("alice"))

  private def gate(gap: Duration = 1.hour): IO[AgentError, MemoryTriggerGate] =
    ZIO
      .fromEither(MemoryTrigger.throttled(gap))
      .flatMap(trigger => MemoryTriggerGate.inMemory(MemoryTriggerPolicy(trigger, trigger)))

  private def claim(
      gate: MemoryTriggerGate,
      scope: MemoryScope = user
  ): IO[AgentError, MemoryTriggerDecision] =
    gate.claim(MemoryOperation.Flush, scope)

  def spec: Spec[TestEnvironment & Scope, Any] = suite("MemoryTriggerGateSpec")(
    test("always and never decide every call without allocating reservations") {
      for
        gate <- MemoryTriggerGate.inMemory(
          MemoryTriggerPolicy(MemoryTrigger.Always, MemoryTrigger.Never)
        )
        flush       <- ZIO.collectAll(List.fill(5)(gate.claim(MemoryOperation.Flush, user)))
        maintenance <- ZIO.collectAll(List.fill(5)(gate.claim(MemoryOperation.Maintenance, user)))
        retention   <- ZIO.fromEither(MemoryInterval.fromDuration(1.second))
        _           <- TestClock.adjust(2.seconds)
        removed     <- gate.cleanup(retention)
      yield assertTrue(
        flush.forall(_ == admitted),
        maintenance.forall(_ == MemoryTriggerDecision.Disabled),
        removed == 0
      )
    },
    test("zero throttling behaves as always") {
      for
        instance <- gate(Duration.Zero)
        outcomes <- ZIO.collectAll(List.fill(3)(claim(instance)))
      yield assertTrue(outcomes.forall(_ == admitted))
    },
    test("first claim wins at clock origin and exact expiry starts the next window") {
      for
        _         <- TestClock.setTime(Instant.EPOCH)
        instance  <- gate(5.seconds)
        first     <- claim(instance)
        immediate <- claim(instance)
        _         <- TestClock.adjust(5.seconds.minusNanos(1))
        before    <- claim(instance)
        _         <- TestClock.adjust(1.nanosecond)
        exact     <- claim(instance)
        repeated  <- claim(instance)
      yield assertTrue(
        first == admitted,
        immediate == throttled,
        before == throttled,
        exact == admitted,
        repeated == throttled
      )
    },
    test("different users and sessions have independent structural slots") {
      val scopes = List(
        MemoryScope.User(Some("alice")),
        MemoryScope.User(Some("bob")),
        MemoryScope.Session(Some("alice")),
        MemoryScope.Session(Some("bob"))
      )
      for
        instance <- gate()
        first    <- ZIO.foreach(scopes)(claim(instance, _))
        next     <- ZIO.foreach(scopes)(claim(instance, _))
      yield assertTrue(first.forall(_ == admitted), next.forall(_ == throttled))
    },
    test("absent and blank identities share the anonymous slot within each scope") {
      val scopes = List(
        MemoryScope.User(None),
        MemoryScope.User(Some("")),
        MemoryScope.User(Some("  ")),
        MemoryScope.Session(None),
        MemoryScope.Session(Some("")),
        MemoryScope.Session(Some("  "))
      )
      for
        instance <- gate()
        outcomes <- ZIO.foreach(scopes)(claim(instance, _))
      yield assertTrue(
        outcomes == List(admitted, throttled, throttled, admitted, throttled, throttled)
      )
    },
    test("agent and global each share a constant slot inside their injected owner") {
      for
        instance     <- gate()
        firstAgent   <- claim(instance, MemoryScope.Agent)
        secondAgent  <- claim(instance, MemoryScope.Agent)
        firstGlobal  <- claim(instance, MemoryScope.Global)
        secondGlobal <- claim(instance, MemoryScope.Global)
      yield assertTrue(
        firstAgent == admitted,
        secondAgent == throttled,
        firstGlobal == admitted,
        secondGlobal == throttled
      )
    },
    test("reconstructed callers share the same authority while other owners remain independent") {
      for
        shared <- gate()
        other  <- gate()
        firstCaller  = (scope: MemoryScope) => claim(shared, scope)
        secondCaller = (scope: MemoryScope) => claim(shared, scope)
        first         <- firstCaller(user)
        second        <- secondCaller(user)
        separateUser  <- secondCaller(MemoryScope.User(Some("bob")))
        separateOwner <- claim(other)
      yield assertTrue(
        first == admitted,
        second == throttled,
        separateUser == admitted,
        separateOwner == admitted
      )
    },
    test("flush and maintenance are independent in every scope and both claim orders") {
      val scopes =
        List(user, MemoryScope.Session(Some("session")), MemoryScope.Agent, MemoryScope.Global)
      val orders = List(
        List(MemoryOperation.Flush, MemoryOperation.Maintenance),
        List(MemoryOperation.Maintenance, MemoryOperation.Flush)
      )
      ZIO
        .foreach(scopes.flatMap(scope => orders.map(scope -> _))) { case (scope, order) =>
          for
            instance <- gate()
            first    <- ZIO.foreach(order)(instance.claim(_, scope))
            again    <- ZIO.foreach(order)(instance.claim(_, scope))
          yield first.forall(_ == admitted) && again.forall(_ == throttled)
        }
        .map(values => assertTrue(values.forall(identity)))
    },
    test("identifier delimiters and nonblank whitespace do not collide") {
      val scopes = List(
        MemoryScope.User(Some("alice:SESSION:bob")),
        MemoryScope.Session(Some("alice:SESSION:bob")),
        MemoryScope.User(Some("alice")),
        MemoryScope.User(Some(" alice "))
      )
      for
        instance <- gate()
        values   <- ZIO.foreach(scopes)(claim(instance, _))
      yield assertTrue(values.forall(_ == admitted))
    },
    test("simultaneous same-key claims have exactly one winner") {
      for
        instance <- gate()
        values   <- ZIO.foreachPar(1 to 100)(_ => claim(instance))
      yield assertTrue(values.count(_ == admitted) == 1, values.count(_ == throttled) == 99)
    },
    test("cleanup removes stale elapsed claims but preserves recent and exact-retention claims") {
      for
        instance  <- gate(1.second)
        retention <- ZIO.fromEither(MemoryInterval.fromDuration(10.seconds))
        _         <- claim(instance)
        _         <- TestClock.adjust(10.seconds)
        boundary  <- instance.cleanup(retention)
        _         <- TestClock.adjust(1.nanosecond)
        _         <- claim(instance, MemoryScope.User(Some("bob")))
        removed   <- instance.cleanup(retention)
        recent    <- claim(instance, MemoryScope.User(Some("bob")))
        old       <- claim(instance)
      yield assertTrue(boundary == 0, removed == 1, recent == throttled, old == admitted)
    },
    test("cleanup cannot reopen an active interval even when retention is shorter") {
      for
        instance  <- gate(1.hour)
        retention <- ZIO.fromEither(MemoryInterval.fromDuration(1.second))
        _         <- claim(instance)
        _         <- TestClock.adjust(2.seconds)
        removed   <- instance.cleanup(retention)
        next      <- claim(instance)
        _         <- TestClock.adjust(1.hour.minus(2.seconds))
        expired   <- instance.cleanup(retention)
        fresh     <- claim(instance)
      yield assertTrue(removed == 0, next == throttled, expired == 1, fresh == admitted)
    },
    test("cleanup racing renewed admission cannot remove the new active reservation") {
      for
        instance  <- gate(5.seconds)
        retention <- ZIO.fromEither(MemoryInterval.fromDuration(1.second))
        _         <- claim(instance)
        _         <- TestClock.adjust(5.seconds)
        raced     <- claim(instance).zipPar(instance.cleanup(retention))
        next      <- claim(instance)
      yield assertTrue(raced._1 == admitted, next == throttled, raced._2 == 0 || raced._2 == 1)
    },
    test("creating a new gate explicitly resets windows for its immutable policy") {
      for
        original      <- gate(1.hour)
        _             <- claim(original)
        updated       <- gate(1.second)
        newClaim      <- claim(updated)
        originalClaim <- claim(original)
      yield assertTrue(newClaim == admitted, originalClaim == throttled)
    },
    test("elapsed subtraction preserves the window across signed monotonic wraparound") {
      for
        underlying <- ZIO.clock
        nanos      <- Ref.make(Long.MaxValue - 5)
        clock = new Clock:
          export underlying.{
            currentTime,
            currentDateTime,
            instant,
            javaClock,
            localDateTime,
            scheduler,
            sleep
          }
          def nanoTime(using Trace): UIO[Long] = nanos.get
        instance <- gate(10.nanoseconds)
        first    <- claim(instance).withClock(clock)
        _        <- nanos.set(Long.MinValue + 3)
        before   <- claim(instance).withClock(clock)
        _        <- nanos.set(Long.MinValue + 4)
        exact    <- claim(instance).withClock(clock)
      yield assertTrue(first == admitted, before == throttled, exact == admitted)
    },
    test("backward clock movement fails closed without replacing the previous claim") {
      for
        _         <- TestClock.setTime(Instant.EPOCH.plusSeconds(100))
        instance  <- gate(5.seconds)
        _         <- claim(instance)
        retention <- ZIO.fromEither(MemoryInterval.fromDuration(1.second))
        _         <- TestClock.setTime(Instant.EPOCH.plusSeconds(90))
        backward  <- claim(instance)
        removed   <- instance.cleanup(retention)
        _         <- TestClock.setTime(Instant.EPOCH.plusSeconds(105))
        ready     <- claim(instance)
      yield assertTrue(backward == throttled, removed == 0, ready == admitted)
    }
  )
