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
import com.weisgnosis.agents.core.model.{ContentBlock, Msg, Role, ToolResultState}
import com.weisgnosis.agents.core.tool.{Tool, ToolAuthorizer, ToolDef, Toolkit}
import com.weisgnosis.agents.harness.permission.{
  InteractivePermissionAuthorizer,
  PermissionBehavior,
  PermissionEngine,
  PermissionRequests,
  PermissionRule
}
import com.weisgnosis.agents.harness.plan.{PlanMode, PlanPermissionGate, PlanToolAccess}
import zio.*
import zio.json.ast.Json
import zio.test.*

object SubagentSessionSpec extends ZIOSpecDefault:
  private def owner: ZIO[Scope, Nothing, Scope.Closeable] =
    ZIO.acquireRelease(Scope.make)(_.close(Exit.unit))

  private def factory(
      register: SubagentSpawner[AgentResult] => ZIO[Scope, AgentError, Toolkit],
      client: SubagentSpawner[AgentResult] => ZIO[Scope, AgentError, LlmClient],
      authorizer: ToolAuthorizer = ToolAuthorizer.allow
  ): IO[AgentError, SubagentFactory] =
    ZIO.fromEither(
      SubagentFactory.make(
        AgentConfig(name = "session-child"),
        LlmConfig("session-model"),
        Toolkit.empty,
        SubagentToolBoundary(denied = Set("forbidden")),
        SubagentToolBoundary(),
        register,
        client,
        authorizer
      )
    )

  private def accepted[A](
      effect: ZIO[Scope, AgentError, TaskResourceAdmission[A]]
  ): ZIO[Scope, AgentError, TaskResource[A]] =
    effect.flatMap:
      case TaskResourceAdmission.Accepted(resource) => ZIO.succeed(resource)
      case TaskResourceAdmission.Closed =>
        ZIO.fail(AgentError.InvalidConfiguration("Unexpected closed resource owner"))

  private def submitted(
      effect: ZIO[Scope, AgentError, SubagentTaskSubmission]
  ): ZIO[Scope, AgentError, SubagentTaskId] =
    effect.flatMap:
      case SubagentTaskSubmission.Accepted(id) => ZIO.succeed(id)
      case other => ZIO.fail(AgentError.InvalidConfiguration(s"Unexpected submission: $other"))

  private def result(session: SubagentSession, id: SubagentTaskId): IO[AgentError, AgentResult] =
    session.executions
      .await(id)
      .flatMap(value =>
        ZIO
          .fromOption(value)
          .orElseFail(AgentError.InvalidConfiguration("Missing retained execution"))
      )
      .flatMap(exit => exit)

  private def run(session: SubagentSession, text: String): ZIO[Scope, AgentError, AgentResult] =
    submitted(session.send(Msg.user(text))).flatMap(result(session, _))

  def spec: Spec[TestEnvironment & Scope, Any] = suite("SubagentSessionSpec")(
    test(
      "reused depth-three children keep one filtered runtime and resource lifetime with separate execution IDs"
    ) {
      for
        supervisor <- TaskSupervisor.open[AgentResult]
        root       <- SubagentSpawner.root(supervisor)
        levelOne   <- accepted(root.ownChild((child, _) => ZIO.succeed(child)))
        levelTwo   <- accepted(levelOne.value.ownChild((child, _) => ZIO.succeed(child)))
        built      <- Ref.make(0)
        released   <- Ref.make(0)
        lineage    <- Promise.make[Nothing, SubagentSpawner[AgentResult]]
        schemas    <- Ref.make(Chunk.empty[Set[String]])
        forbidden = Tool.make(ToolDef("forbidden", "Filtered fixture", Json.Obj()))(_ =>
          ZIO.succeed("forbidden")
        )
        configured <- factory(
          child => lineage.succeed(child).as(Toolkit(Map("forbidden" -> forbidden))),
          _ =>
            ZIO
              .acquireRelease(built.update(_ + 1))(_ => released.update(_ + 1))
              .as(
                LlmClient.mockEffectful((history, tools, _) =>
                  schemas
                    .update(_ :+ tools.map(_.name).toSet)
                    .as(
                      TurnOutput.FinalText(
                        Msg.assistant(
                          history.filter(_.role == Role.User).map(_.text).mkString("|")
                        )
                      )
                    )
                )
              )
        )
        resource <- accepted(SubagentSession.open(levelTwo.value, configured, 2))
        child    <- lineage.await
        first    <- submitted(resource.value.send(Msg.user("first")))
        before   <- result(resource.value, first)
        second   <- submitted(resource.value.send(Msg.user("second")))
        after    <- result(resource.value, second)
        full     <- resource.value.send(Msg.user("third"))
        rejected <- SubagentSession.open(child, configured).exit
        seen     <- schemas.get
        builds   <- built.get
        live     <- released.get
        _        <- resource.close
        cleanup  <- released.get
        closed   <- resource.value.send(Msg.user("after close"))
      yield assertTrue(
        first != second,
        before.reply.text == "first",
        after.reply.text == "first|second",
        child.depth == 3,
        child.source.startsWith(levelTwo.value.source + "/"),
        builds == 1,
        live == 0,
        cleanup == 1,
        seen == Chunk(Set.empty[String], Set.empty[String]),
        full == SubagentTaskSubmission.Capacity,
        rejected.causeOption.exists(_.failures.contains(AgentError.SpawnDepthExceeded(3))),
        closed == SubagentTaskSubmission.Closed
      )
    },
    test(
      "queued cancellation changes no history and the next turn waits for the active model call"
    ) {
      for
        supervisor <- TaskSupervisor.open[AgentResult]
        root       <- SubagentSpawner.root(supervisor)
        entered    <- Promise.make[Nothing, Unit]
        release    <- Promise.make[Nothing, Unit]
        histories  <- Ref.make(Chunk.empty[Chunk[String]])
        configured <- factory(
          _ => ZIO.succeed(Toolkit.empty),
          _ =>
            ZIO.succeed(
              LlmClient.mockEffectful((history, _, _) =>
                val users = history.filter(_.role == Role.User).map(_.text)
                histories.update(_ :+ users) *>
                  (if users == Chunk("first") then entered.succeed(()) *> release.await
                   else ZIO.unit)
                    .as(TurnOutput.FinalText(Msg.assistant("done")))
              )
            )
        )
        resource  <- accepted(SubagentSession.open(root, configured))
        first     <- submitted(resource.value.send(Msg.user("first")))
        _         <- entered.await
        queued    <- submitted(resource.value.send(Msg.user("cancelled")))
        _         <- TestClock.adjust(0.seconds)
        cancelled <- resource.value.executions.cancel(queued)
        last      <- submitted(resource.value.send(Msg.user("last")))
        _         <- TestClock.adjust(0.seconds)
        before    <- histories.get
        waiting   <- resource.value.executions.inspect(last)
        _         <- release.succeed(())
        _         <- result(resource.value, first) *> result(resource.value, last)
        after     <- histories.get
      yield assertTrue(
        cancelled.exists(_.isInterrupted),
        before == Chunk(Chunk("first")),
        waiting.contains(SubagentTaskSnapshot(last, SubagentTaskState.Running)),
        after == Chunk(Chunk("first"), Chunk("first", "last"))
      )
    },
    test(
      "reused runtimes consult current parent denials and live Plan gates without transferring approvals"
    ) {
      for
        supervisor <- TaskSupervisor.open[AgentResult]
        root       <- SubagentSpawner.root(supervisor)
        policy     <- Ref.make(PermissionEngine())
        parentMode <- Ref.make(PlanMode.Build)
        childMode  <- Ref.make(PlanMode.Build)
        executed   <- Ref.make(0)
        approvals  <- Ref.make(0)
        requests   <- PermissionRequests.make
        authorizer <- InteractivePermissionAuthorizer.make(
          requests,
          _ => approvals.update(_ + 1),
          initialPolicy =
            PermissionEngine(Chunk(PermissionRule("write", PermissionBehavior.Allow))),
          toolGate = PlanPermissionGate.inherited(
            parentMode.get,
            childMode.get,
            Map("write" -> PlanToolAccess.Mutation)
          )
        )
        write = Tool.make(ToolDef("write", "Count mutations", Json.Obj()))(_ =>
          executed.update(_ + 1).as("written")
        )
        base <- factory(
          _ => ZIO.succeed(Toolkit(Map("write" -> write))),
          _ =>
            ZIO.succeed(
              LlmClient.mockEffectful((history, _, _) =>
                ZIO.succeed(
                  if history.lastOption.exists(_.role == Role.User) then
                    TurnOutput.ToolCalls(
                      Msg.assistant(Chunk(ContentBlock.ToolUse("write-call", "write", Json.Obj())))
                    )
                  else TurnOutput.FinalText(Msg.assistant("done"))
                )
              )
            ),
          authorizer
        )
        resource <- accepted(SubagentSession.open(root, base.inheritingParentDenials(policy.get)))
        allowed  <- run(resource.value, "allowed")
        _ <- policy.set(PermissionEngine(Chunk(PermissionRule("write", PermissionBehavior.Deny))))
        denied    <- run(resource.value, "parent denial")
        _         <- policy.set(PermissionEngine()) *> parentMode.set(PlanMode.Plan)
        planned   <- run(resource.value, "parent plan")
        _         <- parentMode.set(PlanMode.Build) *> childMode.set(PlanMode.Plan)
        childPlan <- run(resource.value, "child plan")
        count     <- executed.get
        asks      <- approvals.get
      yield assertTrue(
        allowed.history
          .flatMap(_.toolResults)
          .lastOption
          .exists(_.state == ToolResultState.Success),
        Chunk(denied, planned, childPlan).forall(
          _.history.flatMap(_.toolResults).lastOption.exists(_.state == ToolResultState.Denied)
        ),
        count == 1,
        asks == 0
      )
    },
    test("closing a session joins promoted and queued work before releasing its persistent client") {
      for
        supervisor    <- TaskSupervisor.open[AgentResult]
        root          <- SubagentSpawner.root(supervisor)
        caller        <- owner
        entered       <- Promise.make[Nothing, Unit]
        cleaning      <- Promise.make[Nothing, Unit]
        childStarted  <- Promise.make[Nothing, Unit]
        childCleaning <- Promise.make[Nothing, Unit]
        lineage       <- Promise.make[Nothing, SubagentSpawner[AgentResult]]
        release       <- Promise.make[Nothing, Unit]
        freed         <- Ref.make(0)
        calls         <- Ref.make(0)
        configured <- factory(
          child => lineage.succeed(child).as(Toolkit.empty),
          _ =>
            ZIO
              .acquireRelease(ZIO.unit)(_ => freed.update(_ + 1))
              .as(
                LlmClient.mockEffectful((_, _, _) =>
                  (calls.update(_ + 1) *> entered.succeed(()) *> ZIO.never)
                    .onInterrupt(cleaning.succeed(()) *> release.await)
                )
              )
        )
        resource <- accepted(SubagentSession.open(root, configured))
        child    <- lineage.await
        active   <- caller.extend(submitted(resource.value.send(Msg.user("active"))))
        _        <- entered.await
        descendant <- caller
          .extend(
            child.spawn(_ =>
              ZIO.addFinalizer(childCleaning.succeed(()) *> release.await) *>
                childStarted.succeed(()) *> ZIO.never
            )
          )
          .flatMap:
            case TaskAdmission.Accepted(task) => ZIO.succeed(task)
            case TaskAdmission.Closed =>
              ZIO.fail(AgentError.InvalidConfiguration("Unexpected closed descendant owner"))
        _ <- childStarted.await
        wait <- resource.value.executions
          .awaitOrPromote(active)
          .zipPar(descendant.awaitOrPromote)
          .forkScoped
        _         <- TestClock.adjust(30.seconds)
        promoted  <- wait.join
        _         <- caller.close(Exit.unit)
        queued    <- submitted(resource.value.send(Msg.user("queued")))
        closing   <- resource.close.forkScoped
        _         <- cleaning.await *> childCleaning.await
        before    <- freed.get
        pending   <- closing.poll
        _         <- release.succeed(())
        _         <- closing.join
        exits     <- ZIO.foreach(Chunk(active, queued))(resource.value.executions.await)
        childExit <- descendant.await
        after     <- freed.get
        count     <- calls.get
      yield assertTrue(
        promoted._1.contains(TaskWait.Promoted),
        promoted._2 == TaskWait.Promoted,
        before == 0,
        pending.isEmpty,
        exits.forall(_.exists(_.isInterrupted)),
        childExit.isInterrupted,
        after == 1,
        count == 1
      )
    },
    test(
      "invalid capacity closed ownership and failed initialization perform no leaked acquisition"
    ) {
      val failure = AgentError.InvalidConfiguration("client acquisition failed")
      for
        host       <- owner
        supervisor <- host.extend(TaskSupervisor.open[AgentResult])
        root       <- SubagentSpawner.root(supervisor)
        acquired   <- Ref.make(0)
        released   <- Ref.make(0)
        configured <- factory(
          _ =>
            ZIO
              .acquireRelease(acquired.update(_ + 1))(_ => released.update(_ + 1))
              .as(Toolkit.empty),
          _ => ZIO.fail(failure)
        )
        invalid <- SubagentSession.open(root, configured, 0).exit
        before  <- acquired.get
        failed  <- SubagentSession.open(root, configured).exit
        cleanup <- released.get
        _       <- host.close(Exit.unit)
        closed  <- SubagentSession.open(root, configured)
        count   <- acquired.get
      yield assertTrue(
        invalid.isFailure,
        before == 0,
        failed.causeOption.exists(_.failures.contains(failure)),
        cleanup == 1,
        closed == TaskResourceAdmission.Closed,
        count == 1
      )
    }
  )
