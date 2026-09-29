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
import java.nio.file.Files
import zio.*
import zio.test.*
import MemoryTestSupport.temporaryDirectory

object DailyMemoryLeaseSpec extends ZIOSpecDefault:
  def spec: Spec[TestEnvironment & Scope, Any] = suite("DailyMemoryLeaseSpec")(
    test("an existing lease is never removed or overwritten by failed acquisition") {
      for
        directory <- temporaryDirectory
        path = directory.resolve(".daily-writer.lease")
        _        <- ZIO.attemptBlocking(Files.writeString(path, "unfinished-owner"))
        result   <- ZIO.scoped(DailyMemoryLease.open(directory)).either
        retained <- ZIO.attemptBlocking(Files.readString(path))
      yield assertTrue(
        result.left.exists(_.isInstanceOf[AgentError.MemoryStorageError]),
        retained == "unfinished-owner"
      )
    },
    test(
      "closing waits for admitted work and rejects the closed handle before ownership is reused"
    ) {
      for
        directory <- temporaryDirectory
        scope     <- ZIO.acquireRelease(Scope.make)(_.close(Exit.unit))
        lease     <- scope.extend(DailyMemoryLease.open(directory))
        entered   <- Promise.make[Nothing, Unit]
        release   <- Promise.make[Nothing, Unit]
        worker    <- lease.protect(entered.succeed(()) *> release.await).forkScoped
        _         <- entered.await
        closing   <- scope.close(Exit.unit).forkScoped
        _         <- TestClock.adjust(0.seconds)
        pending   <- closing.poll
        contender <- ZIO.scoped(DailyMemoryLease.open(directory)).either
        _         <- release.succeed(())
        _         <- worker.join *> closing.join
        closed    <- lease.protect(ZIO.unit).either
        next <- ZIO.scoped(
          DailyMemoryLease.open(directory).flatMap(_.protect(ZIO.succeed("new owner")))
        )
      yield assertTrue(pending.isEmpty, contender.isLeft, closed.isLeft, next == "new owner")
    },
    test("a cancelled queued operation never enters the ownership boundary") {
      for
        directory <- temporaryDirectory
        lease     <- DailyMemoryLease.open(directory)
        entered   <- Promise.make[Nothing, Unit]
        release   <- Promise.make[Nothing, Unit]
        executed  <- Ref.make(false)
        first     <- lease.protect(entered.succeed(()) *> release.await).forkScoped
        _         <- entered.await
        second    <- lease.protect(executed.set(true)).forkScoped
        _         <- TestClock.adjust(0.seconds)
        exit      <- second.interrupt
        _         <- release.succeed(())
        _         <- first.join
        ran       <- executed.get
        reusable  <- lease.protect(ZIO.succeed("still owned"))
      yield assertTrue(exit.isInterrupted, !ran, reusable == "still owned")
    },
    test("cancellation waits for admitted work before the scope releases its lease") {
      for
        directory <- temporaryDirectory
        entered   <- Promise.make[Nothing, Unit]
        release   <- Promise.make[Nothing, Unit]
        completed <- Ref.make(false)
        worker <- ZIO
          .scoped(
            DailyMemoryLease
              .open(directory)
              .flatMap(lease =>
                lease.protect(entered.succeed(()) *> release.await *> completed.set(true))
              )
          )
          .forkScoped
        _         <- entered.await
        cancel    <- worker.interrupt.forkScoped
        _         <- TestClock.adjust(0.seconds)
        pending   <- cancel.poll
        contender <- ZIO.scoped(DailyMemoryLease.open(directory)).either
        _         <- release.succeed(())
        exit      <- cancel.join
        finished  <- completed.get
        next      <- ZIO.scoped(DailyMemoryLease.open(directory).flatMap(_.protect(ZIO.unit)))
      yield assertTrue(pending.isEmpty, contender.isLeft, exit.isInterrupted, finished, next == ())
    },
    test("a changed lease token prevents access and cleanup never deletes its replacement") {
      for
        directory <- temporaryDirectory
        path = directory.resolve(".daily-writer.lease")
        rejected <- Ref.make(false)
        outcome <- ZIO
          .scoped:
            for
              lease  <- DailyMemoryLease.open(directory)
              _      <- ZIO.attemptBlocking(Files.writeString(path, "replacement-owner"))
              result <- lease.protect(ZIO.unit).either
              _      <- rejected.set(result.isLeft)
            yield ()
          .exit
        denied   <- rejected.get
        retained <- ZIO.attemptBlocking(Files.readString(path))
      yield assertTrue(
        denied,
        outcome.causeOption.exists(_.defects.exists(_.isInstanceOf[AgentError.MemoryStorageError])),
        retained == "replacement-owner"
      )
    },
    test("an explicitly uninterruptible caller retains its interruption policy") {
      for
        directory <- temporaryDirectory
        lease     <- DailyMemoryLease.open(directory)
        entered   <- Promise.make[Nothing, Unit]
        release   <- Promise.make[Nothing, Unit]
        worker <- lease
          .protect(entered.succeed(()) *> release.await.as("completed"))
          .uninterruptible
          .forkScoped
        _       <- entered.await
        cancel  <- worker.interrupt.forkScoped
        _       <- TestClock.adjust(0.seconds)
        pending <- cancel.poll
        _       <- release.succeed(())
        exit    <- cancel.join
      yield assertTrue(pending.isEmpty, exit == Exit.succeed("completed"))
    }
  )
