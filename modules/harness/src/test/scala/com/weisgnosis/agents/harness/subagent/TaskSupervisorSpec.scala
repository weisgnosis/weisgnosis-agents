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
import zio.*
import zio.test.*

object TaskSupervisorSpec extends ZIOSpecDefault:
  private def owner: ZIO[Scope, Nothing, Scope.Closeable] =
    ZIO.acquireRelease(Scope.make)(_.close(Exit.unit))

  private def accepted[A](
      admission: ZIO[Scope, Nothing, TaskAdmission[A]]
  ): ZIO[Scope, AgentError, SupervisedTask[A]] =
    admission.flatMap:
      case TaskAdmission.Accepted(task) => ZIO.succeed(task)
      case TaskAdmission.Closed =>
        ZIO.fail(AgentError.InvalidConfiguration("Unexpected closed supervisor"))

  def spec: Spec[TestEnvironment & Scope, Any] = suite("TaskSupervisorSpec")(
    test("completion includes scoped cleanup and repeated observers receive the same exit") {
      for
        supervisor <- TaskSupervisor.open[Int]
        cleaned    <- Ref.make(0)
        task       <- accepted(supervisor.attach(ZIO.addFinalizer(cleaned.update(_ + 1)).as(42)))
        results    <- ZIO.foreachPar(Chunk(1, 2, 3))(_ => task.await)
        count      <- cleaned.get
        waited     <- task.awaitOrPromote
      yield assertTrue(
        results.forall(_ == Exit.succeed(42)),
        count == 1,
        waited == TaskWait.Completed(Exit.succeed(42))
      )
    },
    test("typed failure and cleanup defect remain in the full exit") {
      val failure = AgentError.InvalidMessage("task failed")
      val defect  = new IllegalStateException("cleanup failed")
      for
        supervisor <- TaskSupervisor.open[Unit]
        task <- accepted(supervisor.attach(ZIO.addFinalizer(ZIO.die(defect)) *> ZIO.fail(failure)))
        result <- task.await
      yield assertTrue(
        result.causeOption.exists(cause =>
          cause.failures.contains(failure) && cause.defects.contains(defect)
        )
      )
    },
    test("self interruption completes without stranding observers") {
      for
        supervisor <- TaskSupervisor.open[Unit]
        task       <- accepted(supervisor.attach(ZIO.interrupt))
        result     <- task.await
      yield assertTrue(result.isInterrupted)
    },
    test("parent closure waits for interrupted task cleanup") {
      for
        supervisor <- TaskSupervisor.open[Unit]
        parent     <- owner
        started    <- Promise.make[Nothing, Unit]
        cleaning   <- Promise.make[Nothing, Unit]
        release    <- Promise.make[Nothing, Unit]
        task <- parent.extend(
          accepted(
            supervisor.attach(
              ZIO.addFinalizer(cleaning.succeed(()) *> release.await) *> started.succeed(
                ()
              ) *> ZIO.never
            )
          )
        )
        _       <- started.await
        closing <- parent.close(Exit.unit).forkScoped
        _       <- cleaning.await
        _       <- TestClock.adjust(0.seconds)
        pending <- closing.poll
        _       <- release.succeed(())
        _       <- closing.join
        result  <- task.await
      yield assertTrue(pending.isEmpty, result.isInterrupted)
    },
    test("thirty-second promotion keeps one execution alive after parent closure") {
      for
        supervisor <- TaskSupervisor.open[Int]
        parent     <- owner
        entered    <- Promise.make[Nothing, Unit]
        release    <- Promise.make[Nothing, Unit]
        count      <- Ref.make(0)
        cleaned    <- Ref.make(0)
        task <- parent.extend(
          accepted(
            supervisor.attach(
              ZIO.addFinalizer(cleaned.update(_ + 1)) *> count.update(_ + 1) *> entered.succeed(
                ()
              ) *> release.await.as(9)
            )
          )
        )
        _            <- entered.await
        waiting      <- task.awaitOrPromote.forkScoped
        _            <- TestClock.adjust(29.seconds)
        before       <- waiting.poll
        _            <- TestClock.adjust(1.second)
        promoted     <- waiting.join
        _            <- parent.close(Exit.unit)
        stillRunning <- cleaned.get
        _            <- release.succeed(())
        result       <- task.await
        runs         <- count.get
        finalCleaned <- cleaned.get
      yield assertTrue(
        before.isEmpty,
        promoted == TaskWait.Promoted,
        stillRunning == 0,
        result == Exit.succeed(9),
        runs == 1,
        finalCleaned == 1
      )
    },
    test("owner closure cancels promoted work and closed admission executes nothing") {
      for
        host       <- owner
        supervisor <- host.extend(TaskSupervisor.open[Unit])
        entered    <- Promise.make[Nothing, Unit]
        cleaned    <- Ref.make(0)
        attempts   <- Ref.make(0)
        task <- accepted(
          supervisor.attach(
            ZIO.addFinalizer(cleaned.update(_ + 1)) *> entered.succeed(()) *> ZIO.never
          )
        )
        _            <- entered.await
        waiting      <- task.awaitOrPromote.forkScoped
        _            <- TestClock.adjust(30.seconds)
        promoted     <- waiting.join
        _            <- host.close(Exit.unit)
        result       <- task.await
        rejected     <- supervisor.attach(attempts.update(_ + 1))
        effects      <- attempts.get
        finalCleaned <- cleaned.get
      yield assertTrue(
        promoted == TaskWait.Promoted,
        result.isInterrupted,
        rejected == TaskAdmission.Closed,
        effects == 0,
        finalCleaned == 1
      )
    },
    test("cancelling the waiting parent before the deadline cancels attached work") {
      for
        supervisor <- TaskSupervisor.open[Unit]
        entered    <- Promise.make[Nothing, SupervisedTask[Unit]]
        cleaned    <- Ref.make(0)
        parent <- ZIO
          .scoped(
            accepted(supervisor.attach(ZIO.addFinalizer(cleaned.update(_ + 1)) *> ZIO.never))
              .flatMap(task => entered.succeed(task) *> task.awaitOrPromote)
          )
          .forkScoped
        task   <- entered.await
        _      <- TestClock.adjust(0.seconds)
        _      <- parent.interrupt
        _      <- TestClock.adjust(30.seconds)
        result <- task.await
        count  <- cleaned.get
      yield assertTrue(result.isInterrupted, count == 1)
    },
    test("explicit cancellation of promoted work is retained for every observer") {
      for
        supervisor <- TaskSupervisor.open[Unit]
        entered    <- Promise.make[Nothing, Unit]
        task       <- accepted(supervisor.attach(entered.succeed(()) *> ZIO.never))
        _          <- entered.await
        waiting    <- task.awaitOrPromote.forkScoped
        _          <- TestClock.adjust(30.seconds)
        _          <- waiting.join
        result     <- task.cancel
        observed   <- task.await
        again      <- task.awaitOrPromote
      yield assertTrue(
        result.isInterrupted,
        observed == result,
        again == TaskWait.Completed(result)
      )
    },
    test("masked acquisition does not make child execution uninterruptible") {
      for
        supervisor <- TaskSupervisor.open[Unit].uninterruptible
        entered    <- Promise.make[Nothing, Unit]
        task       <- accepted(supervisor.attach(entered.succeed(()) *> ZIO.never)).uninterruptible
        _          <- entered.await
        result     <- task.cancel
      yield assertTrue(result.isInterrupted)
    },
    test("each wait starts its own budget even after prior promotion") {
      for
        supervisor    <- TaskSupervisor.open[Unit]
        entered       <- Promise.make[Nothing, Unit]
        task          <- accepted(supervisor.attach(entered.succeed(()) *> ZIO.never))
        _             <- entered.await
        _             <- TestClock.adjust(1.minute)
        first         <- task.awaitOrPromote.forkScoped
        _             <- TestClock.adjust(29.seconds)
        firstPending  <- first.poll
        _             <- TestClock.adjust(1.second)
        firstResult   <- first.join
        second        <- task.awaitOrPromote.forkScoped
        _             <- TestClock.adjust(29.seconds)
        secondPending <- second.poll
        _             <- TestClock.adjust(1.second)
        secondResult  <- second.join
        _             <- task.cancel
      yield assertTrue(
        firstPending.isEmpty,
        secondPending.isEmpty,
        firstResult == TaskWait.Promoted,
        secondResult == TaskWait.Promoted
      )
    },
    test("parent cancellation after committed promotion retains supervisor ownership") {
      for
        supervisor <- TaskSupervisor.open[Unit]
        started    <- Promise.make[Nothing, Unit]
        published  <- Promise.make[Nothing, (SupervisedTask[Unit], TaskWait[Unit])]
        cleaned    <- Ref.make(0)
        parent <- ZIO
          .scoped(for
            task <- accepted(
              supervisor.attach(
                ZIO.addFinalizer(cleaned.update(_ + 1)) *> started.succeed(()) *> ZIO.never
              )
            )
            outcome <- task.awaitOrPromote
            _       <- published.succeed((task, outcome))
            _       <- ZIO.never
          yield ())
          .forkScoped
        _       <- started.await
        _       <- TestClock.adjust(30.seconds)
        result  <- published.await
        _       <- parent.interrupt
        before  <- cleaned.get
        stopped <- result._1.cancel
        after   <- cleaned.get
      yield assertTrue(
        result._2 == TaskWait.Promoted,
        before == 0,
        stopped.isInterrupted,
        after == 1
      )
    },
    test("parent closure also joins cancellation already requested by another fiber") {
      for
        supervisor <- TaskSupervisor.open[Unit]
        parent     <- owner
        entered    <- Promise.make[Nothing, Unit]
        cleaning   <- Promise.make[Nothing, Unit]
        release    <- Promise.make[Nothing, Unit]
        task <- parent.extend(
          accepted(
            supervisor.attach(
              ZIO.addFinalizer(cleaning.succeed(()) *> release.await) *> entered.succeed(
                ()
              ) *> ZIO.never
            )
          )
        )
        _          <- entered.await
        cancelling <- task.cancel.forkScoped
        _          <- cleaning.await
        closing    <- parent.close(Exit.unit).forkScoped
        _          <- TestClock.adjust(0.seconds)
        pending    <- closing.poll
        _          <- release.succeed(())
        _          <- closing.join
        result     <- cancelling.join
      yield assertTrue(pending.isEmpty, result.isInterrupted)
    },
    test("shutdown interrupts every child before waiting for blocked cleanup") {
      for
        host           <- owner
        supervisor     <- host.extend(TaskSupervisor.open[Unit])
        firstEntered   <- Promise.make[Nothing, Unit]
        secondEntered  <- Promise.make[Nothing, Unit]
        firstCleaning  <- Promise.make[Nothing, Unit]
        secondCleaning <- Promise.make[Nothing, Unit]
        release        <- Promise.make[Nothing, Unit]
        first <- accepted(
          supervisor.attach(
            ZIO.addFinalizer(firstCleaning.succeed(()) *> release.await) *> firstEntered.succeed(
              ()
            ) *> ZIO.never
          )
        )
        second <- accepted(
          supervisor.attach(
            ZIO.addFinalizer(secondCleaning.succeed(()) *> release.await) *> secondEntered.succeed(
              ()
            ) *> ZIO.never
          )
        )
        _       <- firstEntered.await *> secondEntered.await
        closing <- host.close(Exit.unit).forkScoped
        _       <- firstCleaning.await *> secondCleaning.await
        pending <- closing.poll
        _       <- release.succeed(())
        _       <- closing.join
        results <- first.await.zip(second.await)
      yield assertTrue(pending.isEmpty, results._1.isInterrupted, results._2.isInterrupted)
    },
    test("cancelled task retains interruption and its cleanup defect after owner shutdown") {
      val defect = new IllegalStateException("cancel cleanup")
      for
        host       <- owner
        supervisor <- host.extend(TaskSupervisor.open[Unit])
        entered    <- Promise.make[Nothing, Unit]
        task <- accepted(
          supervisor.attach(ZIO.addFinalizer(ZIO.die(defect)) *> entered.succeed(()) *> ZIO.never)
        )
        _    <- entered.await
        _    <- host.close(Exit.unit)
        exit <- task.await
      yield assertTrue(exit.isInterrupted, exit.causeOption.exists(_.defects.contains(defect)))
    },
    test("explicit cancellation racing the promotion deadline always settles the task") {
      ZIO
        .foreach(Chunk.fromIterable(1 to 20)) { _ =>
          for
            supervisor <- TaskSupervisor.open[Unit]
            entered    <- Promise.make[Nothing, Unit]
            task       <- accepted(supervisor.attach(entered.succeed(()) *> ZIO.never))
            _          <- entered.await
            waiting    <- task.awaitOrPromote.forkScoped
            cancelled  <- task.cancel.zipPar(TestClock.adjust(30.seconds))
            result     <- waiting.join
            exit       <- task.await
            settled = result match
              case TaskWait.Completed(outcome) => outcome.isInterrupted
              case TaskWait.Promoted           => true
          yield cancelled.isInterrupted && exit.isInterrupted && settled
        }
        .map(results => assertTrue(results.forall(identity)))
    },
    test("admission racing owner closure leaves no live task") {
      ZIO
        .foreach(Chunk.fromIterable(1 to 20)) { _ =>
          for
            host       <- owner
            supervisor <- host.extend(TaskSupervisor.open[Unit])
            cleaned    <- Ref.make(0)
            admission <- supervisor
              .attach(ZIO.addFinalizer(cleaned.update(_ + 1)) *> ZIO.never)
              .zipPar(host.close(Exit.unit))

            stopped <- admission match
              case TaskAdmission.Accepted(task) => task.await.map(_.isInterrupted)
              case TaskAdmission.Closed         => ZIO.succeed(true)
            count <- cleaned.get
          yield stopped && count <= 1
        }
        .map(results => assertTrue(results.forall(identity)))
    }
  )
