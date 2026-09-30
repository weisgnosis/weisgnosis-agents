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

import com.weisgnosis.agents.core.agent.{AgentConfig, AgentResult}
import com.weisgnosis.agents.core.client.{LlmClient, LlmConfig, TurnOutput}
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.model.Msg
import com.weisgnosis.agents.core.tool.{Tool, ToolAuthorizer, ToolDef, Toolkit}
import zio.*
import zio.json.ast.Json
import zio.test.*

object SubagentTaskRegistrySpec extends ZIOSpecDefault:
  private val input: Msg = Msg.user("work")

  private enum WaitMode:
    case Attached, Promoted

  private def owner: ZIO[Scope, Nothing, Scope.Closeable] =
    ZIO.acquireRelease(Scope.make)(_.close(Exit.unit))

  private def factory(
      register: SubagentSpawner[AgentResult] => ZIO[Scope, AgentError, Toolkit],
      client: SubagentSpawner[AgentResult] => ZIO[Scope, AgentError, LlmClient] = _ =>
        LlmClient.mock(Chunk(TurnOutput.FinalText(Msg.assistant("done"))))
  ): IO[AgentError, SubagentFactory] =
    ZIO.fromEither(
      SubagentFactory.make(
        AgentConfig(name = "registry-child"),
        LlmConfig("registry-model"),
        Toolkit.empty,
        SubagentToolBoundary(denied = Set("forbidden")),
        SubagentToolBoundary(),
        register,
        client,
        ToolAuthorizer.allow
      )
    )

  private def accepted(
      submission: ZIO[Scope, AgentError, SubagentTaskSubmission]
  ): ZIO[Scope, AgentError, SubagentTaskId] =
    submission.flatMap:
      case SubagentTaskSubmission.Accepted(id) => ZIO.succeed(id)
      case rejected => ZIO.fail(AgentError.InvalidConfiguration(s"Unexpected rejection: $rejected"))

  private def completed(
      snapshot: Option[SubagentTaskSnapshot]
  ): Option[Exit[AgentError, AgentResult]] =
    snapshot.flatMap:
      case SubagentTaskSnapshot(_, SubagentTaskState.Completed(exit)) => Some(exit)
      case SubagentTaskSnapshot(_, SubagentTaskState.Running)         => None

  def spec: Spec[TestEnvironment & Scope, Any] = suite("SubagentTaskRegistrySpec")(
    test("concurrent admission bounds retained results and executes fresh configured children") {
      for
        supervisor <- TaskSupervisor.open[AgentResult]
        root       <- SubagentSpawner.root(supervisor)
        builds     <- Ref.make(0)
        visible    <- Ref.make(Chunk.empty[(Int, Set[String])])
        forbidden = Tool.make(ToolDef("forbidden", "Must be filtered", Json.Obj()))(_ =>
          ZIO.succeed("forbidden")
        )
        configured <- factory(
          _ => builds.update(_ + 1).as(Toolkit(Map("forbidden" -> forbidden))),
          _ =>
            ZIO.succeed(
              LlmClient.mockEffectful((history, tools, _) =>
                visible
                  .update(_ :+ (history.count(_.text == "work"), tools.map(_.name).toSet))
                  .as(TurnOutput.FinalText(Msg.assistant("done")))
              )
            )
        )
        registry  <- SubagentTaskRegistry.open(root, configured, 2)
        submitted <- ZIO.foreachPar(Chunk.fromIterable(1 to 8))(_ => registry.submit(input))
        ids = submitted.collect { case SubagentTaskSubmission.Accepted(id) => id }
        results  <- ZIO.foreach(ids)(registry.await)
        full     <- registry.submit(input)
        listed   <- registry.list
        count    <- builds.get
        seen     <- visible.get
        removals <- ZIO.foreach(ids)(registry.forgetCompleted)
        next     <- accepted(registry.submit(input))
        _        <- registry.await(next)
      yield assertTrue(
        ids.size == 2,
        ids.toSet.size == 2,
        submitted.count(_ == SubagentTaskSubmission.Capacity) == 6,
        results.forall(_.exists(_.isSuccess)),
        full == SubagentTaskSubmission.Capacity,
        listed.map(_.id).toSet == ids.toSet,
        count == 2,
        seen == Chunk((1, Set.empty[String]), (1, Set.empty[String])),
        removals.forall(_ == SubagentTaskRemoval.Removed)
      )
    },
    test("identity collisions cannot replace work and lookup is isolated to a registry") {
      for
        supervisor <- TaskSupervisor.open[AgentResult]
        root       <- SubagentSpawner.root(supervisor)
        entered    <- Promise.make[Nothing, Unit]
        release    <- Promise.make[Nothing, Unit]
        builds     <- Ref.make(0)
        configured <- factory(_ =>
          builds.update(_ + 1) *> entered.succeed(()) *> release.await.as(Toolkit.empty)
        )
        registry        <- SubagentTaskRegistry.open(root, configured, 2)
        other           <- SubagentTaskRegistry.open(root, configured, 2)
        _               <- TestRandom.feedLongs(11L, 22L, 33L, 44L)
        id              <- accepted(registry.submit(input))
        _               <- entered.await
        _               <- TestRandom.feedLongs(11L, 22L)
        collision       <- registry.submit(input)
        live            <- registry.inspect(id)
        removal         <- registry.forgetCompleted(id)
        missing         <- other.inspect(id)
        absentWait      <- other.await(id)
        absentPromotion <- other.awaitOrPromote(id)
        absentCancel    <- other.cancel(id)
        absentRemoval   <- other.forgetCompleted(id)
        _               <- release.succeed(())
        result          <- registry.await(id)
        same            <- registry.cancel(id)
        count           <- builds.get
        removed         <- registry.forgetCompleted(id)
        forgotten       <- registry.inspect(id)
      yield assertTrue(
        collision == SubagentTaskSubmission.IdentityCollision,
        live.contains(SubagentTaskSnapshot(id, SubagentTaskState.Running)),
        removal == SubagentTaskRemoval.Running,
        missing.isEmpty && absentWait.isEmpty && absentPromotion.isEmpty && absentCancel.isEmpty,
        absentRemoval == SubagentTaskRemoval.Missing,
        result.exists(_.isSuccess),
        same == result,
        count == 1,
        removed == SubagentTaskRemoval.Removed,
        forgotten.isEmpty
      )
    },
    test("snapshots retain typed failures, defects and interruption as complete exits") {
      val failure = AgentError.InvalidConfiguration("registration failed")
      val defect  = new IllegalStateException("registration cleanup failed")
      val cases
          : Chunk[(ZIO[Scope, AgentError, Toolkit], Exit[AgentError, AgentResult] => Boolean)] =
        Chunk(
          (ZIO.succeed(Toolkit.empty), _.isSuccess),
          (
            ZIO.addFinalizer(ZIO.die(defect)) *> ZIO.fail(failure),
            _.causeOption.exists(cause =>
              cause.failures.contains(failure) && cause.defects.contains(defect)
            )
          ),
          (ZIO.interrupt, _.isInterrupted)
        )
      ZIO
        .foreach(cases) { case (registration, valid) =>
          for
            supervisor <- TaskSupervisor.open[AgentResult]
            root       <- SubagentSpawner.root(supervisor)
            configured <- factory(_ => registration)
            registry   <- SubagentTaskRegistry.open(root, configured, 1)
            id         <- accepted(registry.submit(input))
            result     <- registry.await(id)
            observed   <- registry.inspect(id)
            cancelled  <- registry.cancel(id)
          yield assertTrue(result.exists(valid), completed(observed) == result, cancelled == result)
        }
        .map(results => results.reduceOption(_ && _).getOrElse(assertTrue(false)))
    },
    test("caller closure joins attached cleanup while promoted tasks remain retrievable") {
      ZIO
        .foreach(Chunk(WaitMode.Attached, WaitMode.Promoted)) { mode =>
          for
            supervisor <- TaskSupervisor.open[AgentResult]
            root       <- SubagentSpawner.root(supervisor)
            host       <- owner
            caller     <- owner
            entered    <- Promise.make[Nothing, Unit]
            cleaning   <- Promise.make[Nothing, Unit]
            release    <- Promise.make[Nothing, Unit]
            configured <- factory(_ =>
              ZIO.addFinalizer(cleaning.succeed(()) *> release.await) *>
                entered.succeed(()) *> ZIO.never
            )
            registry <- host.extend(SubagentTaskRegistry.open(root, configured, 1))
            id       <- caller.extend(accepted(registry.submit(input)))
            _        <- entered.await
            promoted <-
              if mode == WaitMode.Promoted then
                for
                  waiting <- registry.awaitOrPromote(id).forkScoped
                  _       <- TestClock.adjust(30.seconds)
                  result  <- waiting.join
                yield result.contains(TaskWait.Promoted)
              else ZIO.succeed(false)
            closing      <- caller.close(Exit.unit).forkScoped
            _            <- if mode == WaitMode.Promoted then closing.join else cleaning.await
            current      <- registry.inspect(id)
            pending      <- closing.poll
            hostClosing  <- host.close(Exit.unit).forkScoped
            _            <- cleaning.await
            stillClosing <- hostClosing.poll
            _            <- release.succeed(())
            _            <- closing.join *> hostClosing.join
            result       <- registry.await(id)
          yield assertTrue(
            promoted == (mode == WaitMode.Promoted),
            current.contains(SubagentTaskSnapshot(id, SubagentTaskState.Running)),
            pending.isDefined == (mode == WaitMode.Promoted),
            stillClosing.isEmpty,
            result.exists(_.isInterrupted)
          )
        }
        .map(results => results.reduceOption(_ && _).getOrElse(assertTrue(false)))
    },
    test(
      "registry shutdown closes admission and interrupts workers in parallel before joining cleanup"
    ) {
      for
        supervisor <- TaskSupervisor.open[AgentResult]
        root       <- SubagentSpawner.root(supervisor)
        host       <- owner
        entered    <- Queue.unbounded[Unit]
        cleaning   <- Queue.unbounded[Unit]
        release    <- Promise.make[Nothing, Unit]
        builds     <- Ref.make(0)
        configured <- factory(_ =>
          ZIO.addFinalizer(cleaning.offer(()).unit *> release.await) *>
            builds.update(_ + 1) *> entered.offer(()) *> ZIO.never
        )
        registry <- host.extend(SubagentTaskRegistry.open(root, configured, 16))
        initial  <- ZIO.foreach(Chunk(1, 2))(_ => accepted(registry.submit(input)))
        _        <- entered.take *> entered.take
        closing  <- host.close(Exit.unit).forkScoped
        racing   <- ZIO.foreachPar(Chunk.fromIterable(1 to 8))(_ => registry.submit(input))
        _        <- cleaning.take *> cleaning.take
        pending  <- closing.poll
        rejected <- registry.submit(input)
        _        <- release.succeed(())
        _        <- closing.join
        ids = initial ++ racing.collect { case SubagentTaskSubmission.Accepted(id) => id }
        results      <- ZIO.foreach(ids)(registry.await)
        listed       <- registry.list
        before       <- builds.get
        closed       <- registry.submit(input)
        after        <- builds.get
        closedCaller <- closedCallerAdmission
      yield assertTrue(
        pending.isEmpty,
        rejected == SubagentTaskSubmission.Closed,
        closed == SubagentTaskSubmission.Closed,
        before == after,
        listed.map(_.id).toSet == ids.toSet,
        results.forall(_.exists(_.isInterrupted))
      ) && closedCaller
    },
    test(
      "invalid capacity, rejected depth and a closed supervisor never consume registry capacity"
    ) {
      for
        supervisorHost <- owner
        supervisor     <- supervisorHost.extend(TaskSupervisor.open[AgentResult])
        root           <- SubagentSpawner.root(supervisor)
        calls          <- Ref.make(0)
        configured     <- factory(_ => calls.update(_ + 1).as(Toolkit.empty))
        invalid        <- SubagentTaskRegistry.open(root, configured, 0).exit
        deepest        <- Promise.make[Nothing, SubagentSpawner[AgentResult]]
        _              <- root.spawn(child => descend(child, deepest))
        leaf           <- deepest.await
        registry       <- SubagentTaskRegistry.open(leaf, configured, 1)
        rejected       <- registry.submit(input).exit
        retry          <- registry.submit(input).exit
        empty          <- registry.list
        _              <- supervisorHost.close(Exit.unit)
        closedRegistry <- SubagentTaskRegistry.open(root, configured, 1)
        closed         <- closedRegistry.submit(input)
        closedRetry    <- closedRegistry.submit(input)
        closedEntries  <- closedRegistry.list
        count          <- calls.get
      yield assertTrue(
        invalid.isFailure,
        rejected.causeOption.exists(_.failures.contains(AgentError.SpawnDepthExceeded(3))),
        retry.causeOption.exists(_.failures.contains(AgentError.SpawnDepthExceeded(3))),
        empty.isEmpty,
        closed == SubagentTaskSubmission.Closed,
        closedRetry == SubagentTaskSubmission.Closed,
        closedEntries.isEmpty,
        count == 0
      )
    }
  )

  private def descend(
      parent: SubagentSpawner[AgentResult],
      deepest: Promise[Nothing, SubagentSpawner[AgentResult]]
  ): ZIO[Scope, AgentError, AgentResult] =
    if parent.depth == SubagentSpawner.MaximumDepth then deepest.succeed(parent) *> ZIO.never
    else
      parent
        .spawn(child => descend(child, deepest))
        .flatMap:
          case TaskAdmission.Accepted(task) => task.await.flatMap(exit => exit)
          case TaskAdmission.Closed =>
            ZIO.fail(AgentError.InvalidConfiguration("Unexpected closure"))

  private def closedCallerAdmission: ZIO[Scope, AgentError, TestResult] =
    for
      supervisor <- TaskSupervisor.open[AgentResult]
      entered    <- Promise.make[Nothing, Unit]
      cleaning   <- Promise.make[Nothing, Unit]
      release    <- Promise.make[Nothing, Unit]
      // Force the valid schedule where the worker starts before its caller lease is registered.
      coordinated = new TaskSupervisor[AgentResult]:
        def attach(
            task: => ZIO[Scope, AgentError, AgentResult]
        ): ZIO[Scope, Nothing, TaskAdmission[AgentResult]] =
          ZIO.uninterruptibleMask: restore =>
            for
              lease     <- Scope.make
              admission <- lease.extend(supervisor.attach(task))
              _         <- restore(entered.await).onInterrupt(lease.close(Exit.unit))
              _         <- ZIO.addFinalizer(lease.close(Exit.unit))
            yield admission
      root   <- SubagentSpawner.root(coordinated)
      caller <- owner
      _      <- caller.close(Exit.unit)
      configured <- factory(_ =>
        ZIO.addFinalizer(cleaning.succeed(()) *> release.await) *>
          entered.succeed(()) *> ZIO.never
      )
      registry   <- SubagentTaskRegistry.open(root, configured, 1)
      submitting <- caller.extend(registry.submit(input)).forkScoped
      _          <- cleaning.await
      visible    <- registry.list
      contender  <- registry.submit(input).forkScoped
      _          <- TestClock.adjust(0.seconds)
      admission  <- contender.poll
      // Release every barrier before asserting, including on the defective implementation.
      _         <- release.succeed(())
      _         <- submitting.join *> contender.join
      remaining <- registry.list
    yield assertTrue(
      visible.size == 1,
      admission.contains(Exit.succeed(SubagentTaskSubmission.Capacity)),
      remaining.size == 1,
      remaining.forall(snapshot => completed(Some(snapshot)).exists(_.isInterrupted))
    )
