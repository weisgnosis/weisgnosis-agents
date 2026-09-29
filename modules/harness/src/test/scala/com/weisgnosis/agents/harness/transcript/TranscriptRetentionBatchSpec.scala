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
package com.weisgnosis.agents.harness.transcript

import com.weisgnosis.agents.core.error.AgentError
import java.time.Instant
import zio.*
import zio.json.*
import zio.test.*

object TranscriptRetentionBatchSpec extends ZIOSpecDefault:
  private val now: Instant    = Instant.parse("2026-04-01T00:00:00Z")
  private val cutoff: Instant = Instant.parse("2025-10-03T00:00:00Z")

  private def target(id: String)(
      run: Instant => IO[AgentError, TranscriptPruneResult]
  ): IO[AgentError, TranscriptRetentionTarget] =
    ZIO.fromEither(TranscriptTargetId.fromString(id)).map(TranscriptRetentionTarget(_)(run))

  private def clock(underlying: Clock, read: UIO[Instant]): Clock = new Clock:
    export underlying.{
      currentTime,
      currentDateTime,
      javaClock,
      localDateTime,
      nanoTime,
      scheduler,
      sleep
    }
    def instant(using Trace): UIO[Instant] = read

  def spec: Spec[TestEnvironment & Scope, Any] = suite("TranscriptRetentionBatchSpec")(
    test("duplicate target IDs fail before invoking any capability") {
      for
        calls  <- Ref.make(0)
        first  <- target("same")(_ => calls.update(_ + 1).as(TranscriptPruneResult.Missing))
        second <- target("same")(_ => calls.update(_ + 1).as(TranscriptPruneResult.Missing))
        batch  <- TranscriptRetentionBatch.make(Chunk(first, second)).exit
        count  <- calls.get
      yield assertTrue(
        batch.causeOption.exists(
          _.failures.exists(_.isInstanceOf[AgentError.InvalidConfiguration])
        ),
        count == 0
      )
    },
    test("KeepAll bypasses the clock and every target") {
      for
        selected <- target("unused")(_ => ZIO.fail(AgentError.MemoryStorageError("must not run")))
        batch <- TranscriptRetentionBatch.make(Chunk(selected), TranscriptRetentionPolicy.KeepAll)
        underlying <- ZIO.clock
        result <- batch.run.withClock(
          clock(underlying, ZIO.die(new IllegalStateException("clock must not run")))
        )
      yield assertTrue(result == TranscriptBatchResult.Disabled)
    },
    test(
      "preparation samples once without pruning and every ordered target receives the same cutoff"
    ) {
      for
        calls  <- Ref.make(0)
        events <- Ref.make(Chunk.empty[(String, Instant)])
        first <- target(" first ")(before =>
          events.update(_ :+ ("first" -> before)).as(TranscriptPruneResult.Missing)
        )
        second <- target("second")(before =>
          events.update(_ :+ ("second" -> before)).as(TranscriptPruneResult.Retained(before))
        )
        batch      <- TranscriptRetentionBatch.make(Chunk(first, second))
        underlying <- ZIO.clock
        prepared   <- batch.prepare.withClock(clock(underlying, calls.update(_ + 1).as(now)))
        before     <- events.get
        _          <- TestClock.setTime(now.plusSeconds(86400))
        result     <- prepared.execute
        count      <- calls.get
        observed   <- events.get
      yield assertTrue(
        before.isEmpty,
        count == 1,
        observed == Chunk("first" -> cutoff, "second" -> cutoff),
        result == TranscriptBatchResult.Evaluated(
          cutoff,
          Chunk(
            TranscriptTargetResult(first.id, TranscriptPruneResult.Missing),
            TranscriptTargetResult(second.id, TranscriptPruneResult.Retained(cutoff))
          )
        ),
        result.toJson.fromJson[TranscriptBatchResult] == Right(result)
      )
    },
    test(
      "a later target failure preserves earlier progress and skips following targets with the complete cause"
    ) {
      val failure = AgentError.MemoryStorageError("second failed")
      val defect  = new IllegalStateException("second cleanup failed")
      for
        _     <- TestClock.setTime(now)
        calls <- Ref.make(Chunk.empty[String])
        first <- target("first")(_ =>
          calls.update(_ :+ "first").as(TranscriptPruneResult.Pruned(Instant.EPOCH, 1))
        )
        second <- target("second")(_ =>
          ZIO.scoped(ZIO.addFinalizer(ZIO.die(defect)) *> ZIO.fail(failure))
        )
        third <- target("third")(_ => calls.update(_ :+ "third").as(TranscriptPruneResult.Missing))
        batch <- TranscriptRetentionBatch.make(Chunk(first, second, third))
        exit  <- batch.run.exit
        observed <- calls.get
      yield assertTrue(
        observed == Chunk("first"),
        exit.causeOption.exists(cause =>
          cause.failures.contains(failure) && cause.defects.contains(defect)
        )
      )
    },
    test("cancellation waits for the current target cleanup and never starts its successor") {
      for
        _       <- TestClock.setTime(now)
        entered <- Promise.make[Nothing, Unit]
        cleaned <- Ref.make(false)
        calls   <- Ref.make(Chunk.empty[String])
        first <- target("first")(_ => calls.update(_ :+ "first").as(TranscriptPruneResult.Missing))
        second <- target("second")(_ =>
          (entered.succeed(()) *> ZIO.never).ensuring(cleaned.set(true))
        )
        third <- target("third")(_ => calls.update(_ :+ "third").as(TranscriptPruneResult.Missing))
        batch <- TranscriptRetentionBatch.make(Chunk(first, second, third))
        fiber <- batch.run.forkScoped
        _     <- entered.await
        exit  <- fiber.interrupt
        observed <- calls.get
        closed   <- cleaned.get
      yield assertTrue(exit.isInterrupted, observed == Chunk("first"), closed)
    },
    test("prepared effects are replayable rather than exactly-once receipts") {
      for
        _        <- TestClock.setTime(now)
        calls    <- Ref.make(0)
        selected <- target("selected")(_ => calls.update(_ + 1).as(TranscriptPruneResult.Missing))
        batch    <- TranscriptRetentionBatch.make(Chunk(selected))
        plan     <- batch.prepare
        _        <- plan.execute
        _        <- plan.execute
        count    <- calls.get
      yield assertTrue(count == 2)
    },
    test("an enabled empty batch reports its evaluated cutoff instead of disabled policy") {
      for
        _      <- TestClock.setTime(now)
        batch  <- TranscriptRetentionBatch.make(Chunk.empty)
        result <- batch.run
      yield assertTrue(result == TranscriptBatchResult.Evaluated(cutoff, Chunk.empty))
    }
  )
