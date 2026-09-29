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

object MemoryTaskQueueSpec extends ZIOSpecDefault:
  private def accepted(submission: UIO[MemoryTaskSubmission]): IO[AgentError, MemoryTaskTicket] =
    submission.flatMap:
      case MemoryTaskSubmission.Accepted(ticket) => ZIO.succeed(ticket)
      case MemoryTaskSubmission.Rejected(reason) =>
        ZIO.fail(AgentError.InvalidConfiguration(s"Unexpected rejection: $reason"))

  private def success(outcome: MemoryTaskOutcome): Boolean = outcome match
    case MemoryTaskOutcome.Completed(exit) => exit.isSuccess
    case _                                 => false

  def spec: Spec[TestEnvironment & Scope, Any] = suite("MemoryTaskQueueSpec")(
    test("rejects invalid capacity and starts idle without work") {
      for
        invalid <- MemoryTaskQueue.open(0).exit
        queue   <- MemoryTaskQueue.open()
        _       <- queue.awaitIdle
      yield assertTrue(
        invalid.causeOption.exists(
          _.failures.exists(_.isInstanceOf[AgentError.InvalidConfiguration])
        )
      )
    },
    test("submission returns while task execution is blocked and idle includes running work") {
      for
        queue   <- MemoryTaskQueue.open()
        token   <- MemoryConversationToken.make
        entered <- Promise.make[Nothing, Unit]
        release <- Promise.make[Nothing, Unit]
        ticket  <- accepted(queue.submit(token)(entered.succeed(()) *> release.await))
        _       <- entered.await
        idle    <- queue.awaitIdle.forkScoped
        _       <- TestClock.adjust(0.seconds)
        pending <- idle.poll
        _       <- release.succeed(())
        outcome <- ticket.await
        _       <- idle.join
      yield assertTrue(pending.isEmpty, success(outcome))
    },
    test("only pending work coalesces and replacement keeps its FIFO position") {
      for
        queue   <- MemoryTaskQueue.open()
        first   <- MemoryConversationToken.make
        second  <- MemoryConversationToken.make
        third   <- MemoryConversationToken.make
        entered <- Promise.make[Nothing, Unit]
        release <- Promise.make[Nothing, Unit]
        seen    <- Ref.make(Chunk.empty[String])
        running <- accepted(
          queue.submit(first)(entered.succeed(()) *> release.await *> seen.update(_ :+ "running"))
        )
        _           <- entered.await
        old         <- accepted(queue.submit(second)(seen.update(_ :+ "old")))
        other       <- accepted(queue.submit(third)(seen.update(_ :+ "other")))
        replacement <- accepted(queue.submit(second)(seen.update(_ :+ "replacement")))
        displaced   <- old.await
        _           <- release.succeed(())
        outcomes    <- ZIO.collectAll(List(running.await, replacement.await, other.await))
        order       <- seen.get
      yield assertTrue(
        displaced == MemoryTaskOutcome.Superseded,
        outcomes.forall(success),
        order == Chunk("running", "replacement", "other")
      )
    },
    test("running and pending jobs of the same conversation remain distinct") {
      for
        queue   <- MemoryTaskQueue.open()
        token   <- MemoryConversationToken.make
        entered <- Promise.make[Nothing, Unit]
        release <- Promise.make[Nothing, Unit]
        count   <- Ref.make(0)
        running <- accepted(
          queue.submit(token)(entered.succeed(()) *> release.await *> count.update(_ + 1))
        )
        _          <- entered.await
        old        <- accepted(queue.submit(token)(count.update(_ + 10)))
        latest     <- accepted(queue.submit(token)(count.update(_ + 1)))
        superseded <- old.await
        _          <- release.succeed(())
        first      <- running.await
        last       <- latest.await
        observed   <- count.get
      yield assertTrue(
        superseded == MemoryTaskOutcome.Superseded,
        success(first),
        success(last),
        observed == 2
      )
    },
    test("distinct conversations retain pending work and read state at execution time") {
      for
        queue   <- MemoryTaskQueue.open()
        a       <- MemoryConversationToken.make
        b       <- MemoryConversationToken.make
        entered <- Promise.make[Nothing, Unit]
        release <- Promise.make[Nothing, Unit]
        source  <- Ref.make("old")
        seen    <- Ref.make(Chunk.empty[String])
        _       <- accepted(queue.submit(a)(entered.succeed(()) *> release.await))
        _       <- entered.await
        oldB <- accepted(
          queue.submit(b)(source.get.flatMap(value => seen.update(_ :+ s"old-b:$value")))
        )
        pendingA <- accepted(
          queue.submit(a)(source.get.flatMap(value => seen.update(_ :+ s"a:$value")))
        )
        pendingB <- accepted(
          queue.submit(b)(source.get.flatMap(value => seen.update(_ :+ s"b:$value")))
        )
        _        <- source.set("latest")
        _        <- release.succeed(())
        _        <- queue.awaitIdle
        resultA  <- pendingA.await
        resultB  <- pendingB.await
        replaced <- oldB.await
        values   <- seen.get
      yield assertTrue(
        success(resultA),
        success(resultB),
        replaced == MemoryTaskOutcome.Superseded,
        values == Chunk("b:latest", "a:latest")
      )
    },
    test("capacity rejects new pending identities but allows replacing an existing slot") {
      for
        queue       <- MemoryTaskQueue.open(1)
        running     <- MemoryConversationToken.make
        pending     <- MemoryConversationToken.make
        excess      <- MemoryConversationToken.make
        entered     <- Promise.make[Nothing, Unit]
        release     <- Promise.make[Nothing, Unit]
        _           <- accepted(queue.submit(running)(entered.succeed(()) *> release.await))
        _           <- entered.await
        original    <- accepted(queue.submit(pending)(ZIO.unit))
        rejected    <- queue.submit(excess)(ZIO.unit)
        replacement <- accepted(queue.submit(pending)(ZIO.unit))
        old         <- original.await
        _           <- release.succeed(())
        result      <- replacement.await
      yield assertTrue(
        rejected == MemoryTaskSubmission.Rejected(MemoryTaskRejection.Capacity),
        old == MemoryTaskOutcome.Superseded,
        success(result)
      )
    },
    test("pending tasks do not overlap the current task or its scoped cleanup") {
      for
        queue          <- MemoryTaskQueue.open()
        a              <- MemoryConversationToken.make
        b              <- MemoryConversationToken.make
        cleanupEntered <- Promise.make[Nothing, Unit]
        cleanupRelease <- Promise.make[Nothing, Unit]
        nextStarted    <- Promise.make[Nothing, Unit]
        first <- accepted(
          queue.submit(a)(ZIO.addFinalizer(cleanupEntered.succeed(()) *> cleanupRelease.await).unit)
        )
        _             <- cleanupEntered.await
        second        <- accepted(queue.submit(b)(nextStarted.succeed(()).unit))
        _             <- TestClock.adjust(0.seconds)
        premature     <- nextStarted.poll
        idle          <- queue.awaitIdle.forkScoped
        _             <- TestClock.adjust(0.seconds)
        incomplete    <- idle.poll
        _             <- cleanupRelease.succeed(())
        firstOutcome  <- first.await
        secondOutcome <- second.await
        _             <- idle.join
      yield assertTrue(
        premature.isEmpty,
        incomplete.isEmpty,
        success(firstOutcome),
        success(secondOutcome)
      )
    },
    test(
      "typed errors, defects and task self-interruption preserve causes without stranding the queue"
    ) {
      val error  = AgentError.MemoryStorageError("write failed")
      val defect = new IllegalStateException("task failed")
      for
        queue  <- MemoryTaskQueue.open()
        tokens <- ZIO.foreach(1 to 4)(_ => MemoryConversationToken.make)
        jobs = Chunk[ZIO[Scope, AgentError, Unit]](
          ZIO.fail(error),
          ZIO.die(defect),
          ZIO.interrupt,
          ZIO.unit
        )
        tickets <- ZIO.foreach(tokens.zip(jobs)) { case (token, job) =>
          accepted(queue.submit(token)(job))
        }
        outcomes <- ZIO.foreach(tickets)(_.await)
        _        <- queue.awaitIdle
      yield assertTrue(
        outcomes
          .collect { case MemoryTaskOutcome.Completed(exit) => exit }
          .map(_.isSuccess) == Chunk(false, false, false, true),
        outcomes.exists {
          case MemoryTaskOutcome.Completed(Exit.Failure(cause)) => cause.failures.contains(error);
          case _                                                => false
        },
        outcomes.exists {
          case MemoryTaskOutcome.Completed(Exit.Failure(cause)) => cause.defects.contains(defect);
          case _                                                => false
        },
        outcomes.exists {
          case MemoryTaskOutcome.Completed(exit) => exit.isInterrupted; case _ => false
        }
      )
    },
    test("separate owner queues execute independently even with the same conversation token") {
      for
        first   <- MemoryTaskQueue.open()
        second  <- MemoryTaskQueue.open()
        token   <- MemoryConversationToken.make
        entered <- Promise.make[Nothing, Unit]
        _       <- accepted(first.submit(token)(entered.succeed(()) *> ZIO.never))
        _       <- entered.await
        ticket  <- accepted(second.submit(token)(ZIO.unit))
        outcome <- ticket.await
      yield assertTrue(success(outcome))
    }
  )
