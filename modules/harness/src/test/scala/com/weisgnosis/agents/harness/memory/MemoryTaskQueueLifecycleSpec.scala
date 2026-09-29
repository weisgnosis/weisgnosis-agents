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
import zio.*
import zio.test.*

object MemoryTaskQueueLifecycleSpec extends ZIOSpecDefault:
  private def owner: ZIO[Scope, Nothing, Scope.Closeable] =
    ZIO.acquireRelease(Scope.make)(_.close(Exit.unit))

  private def accepted(submission: UIO[MemoryTaskSubmission]): IO[AgentError, MemoryTaskTicket] =
    submission.flatMap:
      case MemoryTaskSubmission.Accepted(ticket) => ZIO.succeed(ticket)
      case MemoryTaskSubmission.Rejected(reason) =>
        ZIO.fail(AgentError.InvalidConfiguration(s"Unexpected rejection: $reason"))

  def spec: Spec[TestEnvironment & Scope, Any] = suite("MemoryTaskQueueLifecycleSpec")(
    test("closing cancels pending work and joins running cleanup before returning") {
      for
        scope          <- owner
        queue          <- scope.extend(MemoryTaskQueue.open())
        a              <- MemoryConversationToken.make
        b              <- MemoryConversationToken.make
        entered        <- Promise.make[Nothing, Unit]
        cleanupEntered <- Promise.make[Nothing, Unit]
        cleanupRelease <- Promise.make[Nothing, Unit]
        ranPending     <- Ref.make(false)
        running <- accepted(
          queue.submit(a)(
            ZIO.addFinalizer(cleanupEntered.succeed(()) *> cleanupRelease.await) *> entered.succeed(
              ()
            ) *> ZIO.never
          )
        )
        _              <- entered.await
        pending        <- accepted(queue.submit(b)(ranPending.set(true)))
        closing        <- scope.close(Exit.unit).forkScoped
        _              <- cleanupEntered.await
        unfinished     <- closing.poll
        pendingOutcome <- pending.await
        rejected       <- queue.submit(a)(ZIO.unit)
        _              <- cleanupRelease.succeed(())
        _              <- closing.join
        outcome        <- running.await
        ran            <- ranPending.get
        _              <- queue.awaitIdle
      yield assertTrue(
        unfinished.isEmpty,
        pendingOutcome == MemoryTaskOutcome.CancelledBeforeStart,
        rejected == MemoryTaskSubmission.Rejected(MemoryTaskRejection.Closed),
        !ran,
        outcome match
          case MemoryTaskOutcome.Completed(exit) => exit.isInterrupted
          case _                                 => false
      )
    },
    test("running cancellation retains finalizer defects in its ticket") {
      val defect = new IllegalStateException("cleanup failed")
      for
        scope   <- owner
        queue   <- scope.extend(MemoryTaskQueue.open())
        token   <- MemoryConversationToken.make
        entered <- Promise.make[Nothing, Unit]
        ticket <- accepted(
          queue.submit(token)(ZIO.addFinalizer(ZIO.die(defect)) *> entered.succeed(()) *> ZIO.never)
        )
        _       <- entered.await
        _       <- scope.close(Exit.unit)
        outcome <- ticket.await
      yield assertTrue(outcome match
        case MemoryTaskOutcome.Completed(Exit.Failure(cause)) =>
          cause.isInterrupted && cause.defects.contains(defect)
        case _ => false)
    },
    test("task finalizer defects do not prevent later tasks from executing") {
      val defect = new IllegalStateException("cleanup failed")
      for
        queue      <- MemoryTaskQueue.open()
        first      <- MemoryConversationToken.make
        second     <- MemoryConversationToken.make
        ticket     <- accepted(queue.submit(first)(ZIO.addFinalizer(ZIO.die(defect)).unit))
        next       <- accepted(queue.submit(second)(ZIO.unit))
        failed     <- ticket.await
        successful <- next.await
      yield assertTrue(
        failed match
          case MemoryTaskOutcome.Completed(Exit.Failure(cause)) => cause.defects.contains(defect)
          case _                                                => false,
        successful == MemoryTaskOutcome.Completed(Exit.unit)
      )
    },
    test("closing an idle queue terminates its waiting worker and rejects escaped handles") {
      for
        scope    <- owner
        queue    <- scope.extend(MemoryTaskQueue.open())
        token    <- MemoryConversationToken.make
        _        <- queue.awaitIdle
        _        <- scope.close(Exit.unit)
        rejected <- queue.submit(token)(ZIO.unit)
        _        <- queue.awaitIdle
      yield assertTrue(rejected == MemoryTaskSubmission.Rejected(MemoryTaskRejection.Closed))
    },
    test("submissions racing scope closure always reject or receive a terminal ticket") {
      for
        scope  <- owner
        queue  <- scope.extend(MemoryTaskQueue.open(128))
        tokens <- ZIO.foreach(1 to 100)(_ => MemoryConversationToken.make)
        submissions <- ZIO
          .foreachPar(tokens)(token => queue.submit(token)(ZIO.never))
          .zipPar(scope.close(Exit.unit))
        outcomes <- ZIO.foreach(submissions.collect { case MemoryTaskSubmission.Accepted(ticket) =>
          ticket
        })(_.await)
        _ <- queue.awaitIdle
      yield assertTrue(
        submissions.size == 100,
        outcomes.forall {
          case MemoryTaskOutcome.CancelledBeforeStart => true
          case MemoryTaskOutcome.Completed(exit)      => exit.isInterrupted
          case MemoryTaskOutcome.Superseded           => false
        }
      )
    },
    test("closing immediately after admission settles a task even before execution starts") {
      ZIO
        .foreach(1 to 50) { _ =>
          ZIO.scoped:
            for
              scope      <- owner
              queue      <- scope.extend(MemoryTaskQueue.open())
              token      <- MemoryConversationToken.make
              submission <- queue.submit(token)(ZIO.never)
              _          <- scope.close(Exit.unit)
              settled <- submission match
                case MemoryTaskSubmission.Accepted(ticket) =>
                  ticket.await.map {
                    case MemoryTaskOutcome.CancelledBeforeStart => true
                    case MemoryTaskOutcome.Completed(exit)      => exit.isInterrupted
                    case MemoryTaskOutcome.Superseded           => false
                  }
                case MemoryTaskSubmission.Rejected(_) => ZIO.succeed(false)
            yield settled
        }
        .map(values => assertTrue(values.forall(identity)))
    },
    test("masked acquisition does not mask the long-lived worker or block shutdown") {
      for
        scope    <- owner
        queue    <- scope.extend(MemoryTaskQueue.open().uninterruptible)
        token    <- MemoryConversationToken.make
        entered  <- Promise.make[Nothing, Unit]
        release  <- Promise.make[Nothing, Unit]
        ticket   <- accepted(queue.submit(token)(entered.succeed(()) *> release.await))
        _        <- entered.await
        closing  <- scope.close(Exit.unit).forkScoped
        _        <- TestClock.adjust(0.seconds)
        finished <- closing.poll
        _        <- release.succeed(())
        _        <- closing.join
        outcome  <- ticket.await
      yield assertTrue(
        finished.exists(_.isSuccess),
        outcome match
          case MemoryTaskOutcome.Completed(exit) => exit.isInterrupted
          case _                                 => false
      )
    },
    test("closing an invocation scope does not stop jobs owned by the host scope") {
      for
        queue   <- MemoryTaskQueue.open()
        token   <- MemoryConversationToken.make
        entered <- Promise.make[Nothing, Unit]
        release <- Promise.make[Nothing, Unit]
        cleaned <- Ref.make(false)
        ticket <- ZIO.scoped(
          accepted(
            queue.submit(token)(
              ZIO.addFinalizer(cleaned.set(true)) *> entered.succeed(()) *> release.await
            )
          )
        )
        _       <- entered.await
        before  <- cleaned.get
        _       <- release.succeed(())
        outcome <- ticket.await
        after   <- cleaned.get
      yield assertTrue(!before, after, outcome == MemoryTaskOutcome.Completed(Exit.unit))
    }
  )
