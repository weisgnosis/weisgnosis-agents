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

object TaskResourceSpec extends ZIOSpecDefault:
  private enum Stop:
    case Caller, Supervisor

  private enum Closer:
    case Explicit, Caller, Supervisor

  private def owner: ZIO[Scope, Nothing, Scope.Closeable] =
    ZIO.acquireRelease(Scope.make)(_.close(Exit.unit))

  private def accepted[A](
      acquire: ZIO[Scope, AgentError, TaskResourceAdmission[A]]
  ): ZIO[Scope, AgentError, TaskResource[A]] =
    acquire.flatMap:
      case TaskResourceAdmission.Accepted(resource) => ZIO.succeed(resource)
      case TaskResourceAdmission.Closed =>
        ZIO.fail(AgentError.InvalidConfiguration("Unexpected closed resource admission"))

  private def attached[A](
      effect: ZIO[Scope, Nothing, TaskAdmission[A]]
  ): ZIO[Scope, AgentError, SupervisedTask[A]] =
    effect.flatMap:
      case TaskAdmission.Accepted(task) => ZIO.succeed(task)
      case TaskAdmission.Closed =>
        ZIO.fail(AgentError.InvalidConfiguration("Unexpected closed task admission"))

  def spec: Spec[TestEnvironment & Scope, Any] = suite("TaskResourceSpec")(
    test("typed resources outlive initialization and completed turns but close exactly once") {
      for
        supervisor <- TaskSupervisor.open[Int]
        caller     <- owner
        acquired   <- Ref.make(0)
        released   <- Ref.make(0)
        effects    <- Ref.make(0)
        resource <- caller.extend(
          accepted(
            supervisor.own(nested =>
              ZIO
                .acquireRelease(acquired.update(_ + 1).as("client"))(_ => released.update(_ + 1))
                .map(value => (nested, value))
            )
          )
        )
        first      <- attached(resource.value._1.attach(ZIO.succeed(resource.value._2.length)))
        result     <- first.await
        second     <- attached(resource.value._1.attach(ZIO.succeed(resource.value._2.length + 1)))
        next       <- second.await
        before     <- released.get
        _          <- resource.close
        closedTask <- resource.value._1.attach(effects.update(_ + 1).as(1))
        closedResource <- resource.value._1.own(_ => effects.update(_ + 1))
        _              <- caller.close(Exit.unit) *> resource.close
        builds         <- acquired.get
        cleanup        <- released.get
        calls          <- effects.get
      yield assertTrue(
        result == Exit.succeed(6),
        next == Exit.succeed(7),
        before == 0,
        builds == 1,
        cleanup == 1,
        calls == 0,
        closedTask == TaskAdmission.Closed,
        closedResource == TaskResourceAdmission.Closed
      )
    },
    test("competing explicit caller and root closure join the same cleanup and replay its defect") {
      val pairs = Chunk(
        (Closer.Explicit, Closer.Explicit),
        (Closer.Explicit, Closer.Caller),
        (Closer.Explicit, Closer.Supervisor),
        (Closer.Caller, Closer.Explicit),
        (Closer.Supervisor, Closer.Explicit),
        (Closer.Caller, Closer.Supervisor),
        (Closer.Supervisor, Closer.Caller)
      )
      ZIO
        .foreach(pairs) { case (first, second) =>
          val defect  = new IllegalStateException("resource finalizer defect")
          val failure = AgentError.InvalidConfiguration("caller failed")
          for
            host       <- owner
            caller     <- owner
            supervisor <- host.extend(TaskSupervisor.open[Int])
            finalized  <- Promise.make[Nothing, Exit[Any, Any]]
            count      <- Ref.make(0)
            resource <- caller.extend(
              accepted(
                supervisor.own(nested =>
                  ZIO
                    .acquireReleaseExit(ZIO.unit)((_, exit) =>
                      finalized.succeed(exit) *> count.update(_ + 1) *> ZIO.die(defect)
                    )
                    .as(nested)
                )
              )
            )
            entered  <- Promise.make[Nothing, Unit]
            cleaning <- Promise.make[Nothing, Unit]
            release  <- Promise.make[Nothing, Unit]
            worker <- attached(
              resource.value.attach(
                ZIO.addFinalizer(cleaning.succeed(()) *> release.await) *> entered.succeed(
                  ()
                ) *> ZIO.never
              )
            )
            _ <- entered.await
            close = (closer: Closer) =>
              closer match
                case Closer.Explicit   => resource.close
                case Closer.Caller     => caller.close(Exit.fail(failure))
                case Closer.Supervisor => host.close(Exit.unit)
            primary        <- close(first).exit.forkScoped
            _              <- cleaning.await
            rival          <- close(second).exit.forkScoped
            _              <- TestClock.adjust(0.seconds)
            pendingPrimary <- primary.poll
            pendingRival   <- rival.poll
            _              <- release.succeed(())
            primaryExit    <- primary.join
            rivalExit      <- rival.join
            workerExit     <- worker.await
            observed       <- finalized.await
            releases       <- count.get
            _              <- caller.close(Exit.unit).exit *> host.close(Exit.unit).exit
          yield assertTrue(
            pendingPrimary.isEmpty,
            pendingRival.isEmpty,
            primaryExit.causeOption.exists(_.defects.contains(defect)),
            rivalExit.causeOption.exists(_.defects.contains(defect)),
            workerExit.isInterrupted,
            releases == 1,
            observed == (if first == Closer.Caller then Exit.fail(failure) else Exit.unit)
          )
        }
        .map(results => results.reduceOption(_ && _).getOrElse(assertTrue(false)))
    },
    test(
      "failed or self-interrupted initialization returns no resource and joins partial acquisition cleanup"
    ) {
      val failure = AgentError.InvalidConfiguration("initialization failed")
      val defect  = new IllegalStateException("initializer defect")
      val cases: Chunk[
        (IO[AgentError, String], Exit[AgentError, TaskResourceAdmission[String]] => Boolean)
      ] = Chunk(
        (ZIO.fail(failure), _.causeOption.exists(_.failures.contains(failure))),
        (ZIO.die(defect), _.causeOption.exists(_.defects.contains(defect))),
        (ZIO.interrupt, _.isInterrupted)
      )
      ZIO
        .foreach(cases) { case (finish, valid) =>
          for
            supervisor <- TaskSupervisor.open[Int]
            cleaned    <- Ref.make(0)
            finalized  <- Promise.make[Nothing, Exit[Any, Any]]
            result <- supervisor
              .own(_ =>
                ZIO.acquireReleaseExit(ZIO.unit)((_, exit) =>
                  cleaned.update(_ + 1) *> finalized.succeed(exit)
                ) *> finish
              )
              .exit
            count    <- cleaned.get
            observed <- finalized.await
          yield assertTrue(valid(result), result.isFailure, count == 1, observed == result)
        }
        .map(results => results.reduceOption(_ && _).getOrElse(assertTrue(false)))
    },
    test(
      "caller interruption and supervisor shutdown stop initialization before releasing resources"
    ) {
      ZIO
        .foreach(Chunk(Stop.Caller, Stop.Supervisor)) { stop =>
          for
            host       <- owner
            supervisor <- host.extend(TaskSupervisor.open[Int])
            entered    <- Promise.make[Nothing, Unit]
            stopped    <- Ref.make(false)
            cleaning   <- Promise.make[Nothing, Boolean]
            finalized  <- Promise.make[Nothing, Exit[Any, Any]]
            release    <- Promise.make[Nothing, Unit]
            acquiring <- supervisor
              .own(_ =>
                ZIO.acquireReleaseExit(ZIO.unit)((_, exit) =>
                  finalized.succeed(exit) *> stopped.get.flatMap(cleaning.succeed) *> release.await
                ) *>
                  (entered.succeed(()) *> ZIO.never).onInterrupt(stopped.set(true))
              )
              .forkScoped
            _ <- entered.await
            closing <- (stop match
              case Stop.Caller     => acquiring.interrupt.unit
              case Stop.Supervisor => host.close(Exit.unit)
            ).forkScoped
            stoppedBeforeRelease <- cleaning.await
            pending              <- closing.poll
            unfinished           <- acquiring.poll
            _                    <- release.succeed(())
            _                    <- closing.join
            result               <- acquiring.await
            observed             <- finalized.await
            _                    <- host.close(Exit.unit)
            effects              <- Ref.make(0)
            rejected             <- supervisor.own(_ => effects.update(_ + 1))
            count                <- effects.get
          yield assertTrue(
            stoppedBeforeRelease,
            pending.isEmpty,
            unfinished.isEmpty,
            result.isInterrupted,
            stop != Stop.Caller || observed.isInterrupted,
            rejected == TaskResourceAdmission.Closed,
            count == 0
          )
        }
        .map(results => results.reduceOption(_ && _).getOrElse(assertTrue(false)))
    },
    test(
      "root closure joins running queued and promoted descendants before releasing nested resources"
    ) {
      for
        host       <- owner
        supervisor <- host.extend(TaskSupervisor.open[Int])
        released   <- Ref.make(0)
        resource <- accepted(
          supervisor.own(nested =>
            ZIO.acquireRelease(ZIO.unit)(_ => released.update(_ + 1)).as(nested)
          )
        )
        turnCaller     <- owner
        gate           <- Semaphore.make(1)
        entered        <- Promise.make[Nothing, Unit]
        queued         <- Promise.make[Nothing, Unit]
        runningCleanup <- Promise.make[Nothing, Unit]
        queuedCleanup  <- Promise.make[Nothing, Unit]
        release        <- Promise.make[Nothing, Unit]
        running <- turnCaller.extend(
          attached(
            resource.value.attach(
              ZIO.addFinalizer(runningCleanup.succeed(()) *> release.await) *>
                gate.withPermit(entered.succeed(()) *> ZIO.never)
            )
          )
        )
        _ <- entered.await
        waiting <- attached(
          resource.value.attach(
            ZIO.addFinalizer(queuedCleanup.succeed(()) *> release.await) *>
              queued.succeed(()) *> gate.withPermit(ZIO.never)
          )
        )
        _        <- queued.await
        promote  <- running.awaitOrPromote.forkScoped
        _        <- TestClock.adjust(30.seconds)
        status   <- promote.join
        _        <- turnCaller.close(Exit.unit)
        closing  <- host.close(Exit.unit).forkScoped
        _        <- runningCleanup.await *> queuedCleanup.await
        before   <- released.get
        pending  <- closing.poll
        _        <- release.succeed(())
        _        <- closing.join
        outcomes <- running.await.zip(waiting.await)
        after    <- released.get
        closed   <- resource.value.attach(ZIO.succeed(3))
      yield assertTrue(
        status == TaskWait.Promoted,
        before == 0,
        pending.isEmpty,
        outcomes._1.isInterrupted,
        outcomes._2.isInterrupted,
        after == 1,
        closed == TaskAdmission.Closed
      )
    }
  )
