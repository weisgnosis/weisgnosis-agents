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
import com.weisgnosis.agents.core.model.{Msg, Role}
import com.weisgnosis.agents.core.tool.{ToolAuthorizer, Toolkit}
import zio.*
import zio.test.*

object SubagentSessionCatalogSpec extends ZIOSpecDefault:
  private enum Stop:
    case Failure, Caller, Owner

  private def entry(
      name: String,
      acquire: SubagentSpawner[AgentResult] => ZIO[Scope, AgentError, LlmClient]
  ): IO[AgentError, SubagentEntry] =
    ZIO
      .fromEither(
        SubagentFactory.make(
          AgentConfig(name = name),
          LlmConfig("scripted"),
          Toolkit.empty,
          SubagentToolBoundary(),
          SubagentToolBoundary(),
          _ => ZIO.succeed(Toolkit.empty),
          acquire,
          ToolAuthorizer.allow
        )
      )
      .map(SubagentEntry(name, s"Tasks for $name", _))

  private def accepted[A](
      effect: ZIO[Scope, AgentError, TaskResourceAdmission[A]]
  ): ZIO[Scope, AgentError, TaskResource[A]] =
    effect.flatMap:
      case TaskResourceAdmission.Accepted(resource) => ZIO.succeed(resource)
      case TaskResourceAdmission.Closed =>
        ZIO.fail(AgentError.InvalidConfiguration("Unexpected closed owner"))

  private def lookup(
      catalog: SubagentSessionCatalog,
      name: String
  ): IO[AgentError, (SubagentSessionId, SubagentSession)] =
    catalog.lookup(name).someOrFail(AgentError.InvalidConfiguration(s"Missing session $name"))

  private def submit(
      session: SubagentSession,
      prompt: String
  ): ZIO[Scope, AgentError, SubagentTaskId] =
    session
      .send(Msg.user(prompt))
      .flatMap:
        case SubagentTaskSubmission.Accepted(id) => ZIO.succeed(id)
        case _ => ZIO.fail(AgentError.InvalidConfiguration("Rejected test task"))

  private def run(
      session: SubagentSession,
      prompt: String
  ): ZIO[Scope, AgentError, (SubagentTaskId, AgentResult)] =
    for
      id <- submit(session, prompt)
      result <- session.executions
        .await(id)
        .someOrFail(AgentError.InvalidConfiguration("Missing test task"))
      value <- result
    yield (id, value)

  def spec: Spec[TestEnvironment & Scope, Any] = suite("SubagentSessionCatalogSpec")(
    test(
      "finite host installation validates first and reuses exact names without consuming an extra depth"
    ) {
      for
        supervisor <- TaskSupervisor.open[AgentResult]
        parent     <- SubagentSpawner.root(supervisor)
        built      <- Ref.make(Chunk.empty[(Int, String)])
        calls      <- Ref.make(0)
        entries <- ZIO.foreach(Chunk("alpha", "beta"))(name =>
          entry(
            name,
            child =>
              built
                .update(_ :+ (child.depth, child.source))
                .as(
                  LlmClient.mockEffectful((history, _, _) =>
                    calls
                      .update(_ + 1)
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
        )
        duplicate    <- SubagentSessionCatalog.open(parent, entries ++ entries).exit
        overCapacity <- SubagentSessionCatalog.open(parent, entries, maxSessions = 1).exit
        invalidTaskCapacity <- SubagentSessionCatalog
          .open(parent, entries, maxTrackedPerSession = 0)
          .exit
        before       <- built.get
        resource     <- accepted(SubagentSessionCatalog.open(parent, entries))
        initialCalls <- calls.get
        alpha        <- lookup(resource.value, "alpha")
        beta         <- lookup(resource.value, "beta")
        first        <- run(alpha._2, "one")
        again        <- lookup(resource.value, "alpha")
        second       <- run(again._2, "two")
        isolated     <- run(beta._2, "separate")
        other        <- accepted(SubagentSessionCatalog.open(parent, entries.take(1)))
        otherAlpha   <- lookup(other.value, "alpha")
        independent  <- run(otherAlpha._2, "other")
        lineage      <- built.get
        unknown      <- resource.value.lookup("Alpha")
      yield assertTrue(
        duplicate.isFailure,
        overCapacity.isFailure,
        invalidTaskCapacity.isFailure,
        before.isEmpty,
        initialCalls == 0,
        alpha._1 == again._1,
        alpha._1 != beta._1,
        alpha._1 != otherAlpha._1,
        first._1 != second._1,
        first._2.reply.text == "one",
        second._2.reply.text == "one|two",
        isolated._2.reply.text == "separate",
        independent._2.reply.text == "other",
        lineage.size == 3,
        lineage.forall { case (depth, source) =>
          depth == parent.depth + 1 && source.startsWith(parent.source + "/")
        },
        unknown.isEmpty
      )
    },
    test(
      "partial initialization failure, caller cancellation and external owner closure join rollback"
    ) {
      val failure = AgentError.InvalidConfiguration("initialization failed")
      val defect  = new IllegalStateException("initializer defect")
      val cause   = Cause.fail(failure) ++ Cause.die(defect)
      ZIO
        .foreach(Chunk(Stop.Failure, Stop.Caller, Stop.Owner))(stop =>
          for
            host       <- ZIO.acquireRelease(Scope.make)(_.close(Exit.unit))
            supervisor <- host.extend(TaskSupervisor.open[AgentResult])
            parent     <- SubagentSpawner.root(supervisor)
            built      <- Ref.make(0)
            cleaned    <- Ref.make(0)
            entered    <- Promise.make[Nothing, Unit]
            cleaning   <- Promise.make[Nothing, Unit]
            release    <- Promise.make[Nothing, Unit]
            first <- entry(
              "first",
              _ =>
                ZIO
                  .acquireRelease(built.update(_ + 1))(_ => cleaned.update(_ + 1))
                  .as(
                    LlmClient.mockFunction((_, _) => TurnOutput.FinalText(Msg.assistant("unused")))
                  )
            )
            second <- entry(
              "second",
              _ =>
                ZIO.acquireRelease(built.update(_ + 1))(_ =>
                  cleaning.succeed(()) *> release.await *> cleaned.update(_ + 1)
                ) *> entered.succeed(()) *> (stop match
                  case Stop.Failure => ZIO.refailCause(cause)
                  case _            => ZIO.never)
            )
            result <- ZIO.scoped {
              (for
                installing <- ZIO
                  .scoped(SubagentSessionCatalog.open(parent, Chunk(first, second)))
                  .forkScoped
                _ <- entered.await
                stopping <- (stop match
                  case Stop.Failure => installing.await.unit
                  case Stop.Caller  => installing.interrupt.unit
                  case Stop.Owner   => host.close(Exit.unit)
                ).forkScoped
                _           <- cleaning.await
                pending     <- stopping.poll
                unfinished  <- installing.poll
                _           <- release.succeed(())
                _           <- stopping.join
                exit        <- installing.await
                acquired    <- built.get
                released    <- cleaned.get
                _           <- host.close(Exit.unit)
                closed      <- SubagentSessionCatalog.open(parent, Chunk(first))
                afterClosed <- built.get
              yield assertTrue(
                pending.isEmpty,
                unfinished.isEmpty,
                acquired == 2,
                released == 2,
                afterClosed == 2,
                closed == TaskResourceAdmission.Closed,
                stop match
                  case Stop.Failure => exit.causeOption.contains(cause)
                  case _            => exit.isInterrupted
              )).ensuring(release.succeed(()).unit)
            }
          yield result
        )
        .map(_.foldLeft(assertTrue(true))(_ && _))
    },
    test("named removal and aggregate closure join different active sessions in parallel") {
      val defect = new IllegalStateException("session client cleanup")
      for
        supervisor    <- TaskSupervisor.open[AgentResult]
        parent        <- SubagentSpawner.root(supervisor)
        caller        <- ZIO.acquireRelease(Scope.make)(_.close(Exit.unit))
        enteredA      <- Promise.make[Nothing, Unit]
        enteredB      <- Promise.make[Nothing, Unit]
        cleaningA     <- Promise.make[Nothing, Unit]
        cleaningB     <- Promise.make[Nothing, Unit]
        release       <- Promise.make[Nothing, Unit]
        clientsClosed <- Ref.make(0)
        entries <- ZIO.foreach(
          Chunk(("alpha", enteredA, cleaningA), ("beta", enteredB, cleaningB))
        ) { case (name, entered, cleaning) =>
          entry(
            name,
            _ =>
              ZIO
                .acquireRelease(ZIO.unit)(_ =>
                  clientsClosed.update(_ + 1) *>
                    (if name == "beta" then ZIO.die(defect) else ZIO.unit)
                )
                .as(
                  LlmClient.mockEffectful((_, _, _) =>
                    ZIO.acquireRelease(entered.succeed(()))(_ =>
                      cleaning.succeed(()) *> release.await
                    ) *> ZIO.never
                  )
                )
          )
        }
        resource <- caller.extend(accepted(SubagentSessionCatalog.open(parent, entries)))
        alpha    <- lookup(resource.value, "alpha")
        beta     <- lookup(resource.value, "beta")
        result <- ZIO.scoped {
          (for
            a              <- submit(alpha._2, "work")
            b              <- submit(beta._2, "work")
            _              <- enteredA.await *> enteredB.await
            removing       <- resource.value.remove("alpha").forkScoped
            _              <- cleaningA.await
            closing        <- resource.close.exit.forkScoped
            _              <- cleaningB.await
            hidden         <- resource.value.lookup("alpha")
            pendingRemoval <- removing.poll
            pendingClose   <- closing.poll
            before         <- clientsClosed.get
            _              <- release.succeed(())
            removed        <- removing.join
            closeExit      <- closing.join
            one            <- alpha._2.executions.await(a)
            two            <- beta._2.executions.await(b)
            missing        <- resource.value.lookup("beta")
            stale          <- beta._2.send(Msg.user("late"))
            after          <- clientsClosed.get
            callerExit     <- caller.close(Exit.unit).exit
          yield assertTrue(
            hidden.isEmpty,
            pendingRemoval.isEmpty,
            pendingClose.isEmpty,
            before == 0,
            removed == SubagentSessionRemoval.Removed,
            one.exists(_.isInterrupted),
            two.exists(_.isInterrupted),
            missing.isEmpty,
            stale == SubagentTaskSubmission.Closed,
            after == 2,
            closeExit.causeOption.contains(Cause.die(defect)),
            callerExit.causeOption.contains(Cause.die(defect))
          )).ensuring(release.succeed(()).unit)
        }
      yield result
    }
  ) @@ TestAspect.timeout(20.seconds)
