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

object SubagentSessionDirectorySpec extends ZIOSpecDefault:
  private enum Cleanup:
    case Normal, Defect

  private enum FirstCloser:
    case Directory, Host

  private def owner: ZIO[Scope, Nothing, Scope.Closeable] =
    ZIO.acquireRelease(Scope.make)(_.close(Exit.unit))

  private def historyClient(label: String): LlmClient =
    LlmClient.mockEffectful((history, _, _) =>
      ZIO.succeed(
        TurnOutput.FinalText(
          Msg.assistant(
            label + ":" + history.filter(_.role == Role.User).map(_.text).mkString("|")
          )
        )
      )
    )

  private def session(
      parent: SubagentSpawner[AgentResult],
      client: ZIO[Scope, AgentError, LlmClient]
  ): ZIO[Scope, AgentError, TaskResource[SubagentSession]] =
    for
      factory <- ZIO.fromEither(
        SubagentFactory.make(
          AgentConfig(name = "directory-child"),
          LlmConfig("directory-model"),
          Toolkit.empty,
          SubagentToolBoundary(),
          SubagentToolBoundary(),
          _ => ZIO.succeed(Toolkit.empty),
          _ => client,
          ToolAuthorizer.allow
        )
      )
      resource <- SubagentSession
        .open(parent, factory)
        .flatMap:
          case TaskResourceAdmission.Accepted(value) => ZIO.succeed(value)
          case TaskResourceAdmission.Closed =>
            ZIO.fail(AgentError.InvalidConfiguration("Unexpected closed session owner"))
    yield resource

  private def registered(
      directory: SubagentSessionDirectory,
      resource: TaskResource[SubagentSession]
  ): IO[AgentError, SubagentSessionId] =
    directory
      .register(resource)
      .flatMap:
        case SubagentSessionRegistration.Accepted(id) => ZIO.succeed(id)
        case other => ZIO.fail(AgentError.InvalidConfiguration(s"Unexpected registration: $other"))

  private def member(
      directory: SubagentSessionDirectory,
      id: SubagentSessionId
  ): IO[AgentError, SubagentSession] =
    directory
      .lookup(id)
      .flatMap(value =>
        ZIO
          .fromOption(value)
          .orElseFail(AgentError.InvalidConfiguration("Missing session membership"))
      )

  private def submitted(
      session: SubagentSession,
      text: String
  ): ZIO[Scope, AgentError, SubagentTaskId] =
    session
      .send(Msg.user(text))
      .flatMap:
        case SubagentTaskSubmission.Accepted(id) => ZIO.succeed(id)
        case other => ZIO.fail(AgentError.InvalidConfiguration(s"Unexpected submission: $other"))

  private def run(session: SubagentSession, text: String): ZIO[Scope, AgentError, String] =
    submitted(session, text)
      .flatMap(id => session.executions.await(id))
      .flatMap(value =>
        ZIO.fromOption(value).orElseFail(AgentError.InvalidConfiguration("Missing execution"))
      )
      .flatMap(exit => exit)
      .map(_.reply.text)

  private def promote(
      session: SubagentSession,
      id: SubagentTaskId
  ): ZIO[Scope, Nothing, Option[TaskWait[AgentResult]]] =
    for
      waiting <- session.executions.awaitOrPromote(id).forkScoped
      _       <- TestClock.adjust(30.seconds)
      result  <- waiting.join
    yield result

  def spec: Spec[TestEnvironment & Scope, Any] = suite("SubagentSessionDirectorySpec")(
    test(
      "bounded IDs isolate directories and every rejected registration leaves caller ownership intact"
    ) {
      for
        supervisor     <- TaskSupervisor.open[AgentResult]
        parent         <- SubagentSpawner.root(supervisor)
        accepted       <- session(parent, ZIO.succeed(historyClient("accepted")))
        rejected       <- session(parent, ZIO.succeed(historyClient("rejected")))
        invalid        <- SubagentSessionDirectory.open(0).exit
        directory      <- SubagentSessionDirectory.open(2)
        other          <- SubagentSessionDirectory.open()
        _              <- TestRandom.feedLongs(11L, 22L)
        first          <- registered(directory, accepted)
        _              <- TestRandom.feedLongs(11L, 22L)
        collision      <- directory.register(rejected)
        afterCollision <- run(rejected.value, "collision")
        _              <- TestRandom.feedLongs(33L, 44L)
        alias          <- registered(directory, accepted)
        ids            <- directory.list
        full           <- directory.register(rejected)
        afterCapacity  <- run(rejected.value, "capacity")
        isolated       <- other.lookup(first)
        _              <- directory.close
        closed         <- directory.register(rejected)
        afterClosed    <- run(rejected.value, "closed")
        revoked        <- accepted.value.send(Msg.user("revoked"))
        empty          <- directory.list
      yield assertTrue(
        invalid.isFailure,
        first != alias,
        ids.toSet == Set(first, alias),
        collision == SubagentSessionRegistration.IdentityCollision,
        full == SubagentSessionRegistration.Capacity,
        closed == SubagentSessionRegistration.Closed,
        afterCollision == "rejected:collision",
        afterCapacity == "rejected:collision|capacity",
        afterClosed == "rejected:collision|capacity|closed",
        isolated.isEmpty,
        revoked == SubagentTaskSubmission.Closed,
        empty.isEmpty
      )
    },
    test(
      "lookup preserves each fixed session history while original acquiring scopes still bound liveness"
    ) {
      for
        supervisor <- TaskSupervisor.open[AgentResult]
        parent     <- SubagentSpawner.root(supervisor)
        caller     <- owner
        left       <- caller.extend(session(parent, ZIO.succeed(historyClient("left"))))
        right      <- session(parent, ZIO.succeed(historyClient("right")))
        directory  <- SubagentSessionDirectory.open()
        leftId     <- registered(directory, left)
        rightId    <- registered(directory, right)
        leftMember <- member(directory, leftId)
        first      <- run(leftMember, "one")
        second     <- member(directory, leftId).flatMap(run(_, "two"))
        separate   <- member(directory, rightId).flatMap(run(_, "other"))
        _          <- caller.close(Exit.unit)
        retained   <- directory.lookup(leftId)
        closed     <- leftMember.send(Msg.user("external close"))
        live       <- member(directory, rightId).flatMap(run(_, "still live"))
      yield assertTrue(
        first == "left:one",
        second == "left:one|two",
        separate == "right:other",
        retained.nonEmpty,
        closed == SubagentTaskSubmission.Closed,
        live == "right:other|still live"
      )
    },
    test(
      "overlapping removals join promoted cleanup and retain capacity until normal or defective release ends"
    ) {
      ZIO
        .foreach(Chunk(Cleanup.Normal, Cleanup.Defect)): cleanup =>
          val defect = new IllegalStateException("session release defect")
          for
            supervisor <- TaskSupervisor.open[AgentResult]
            parent     <- SubagentSpawner.root(supervisor)
            caller     <- owner
            entered    <- Promise.make[Nothing, Unit]
            cleaning   <- Promise.make[Nothing, Unit]
            release    <- Promise.make[Nothing, Unit]
            freed      <- Ref.make(0)
            active <- caller.extend(
              session(
                parent,
                ZIO
                  .acquireRelease(ZIO.unit)(_ =>
                    freed.update(_ + 1) *> (cleanup match
                      case Cleanup.Normal => ZIO.unit
                      case Cleanup.Defect => ZIO.die(defect))
                  )
                  .as(
                    LlmClient.mockEffectful((_, _, _) =>
                      (entered.succeed(()) *> ZIO.never)
                        .onInterrupt(cleaning.succeed(()) *> release.await)
                    )
                  )
              )
            )
            spare            <- session(parent, ZIO.succeed(historyClient("spare")))
            directory        <- SubagentSessionDirectory.open(1)
            id               <- registered(directory, active)
            execution        <- submitted(active.value, "active")
            _                <- entered.await
            promoted         <- promote(active.value, execution)
            first            <- directory.remove(id).exit.forkScoped
            _                <- cleaning.await
            duplicate        <- directory.remove(id).exit.forkScoped
            _                <- TestClock.adjust(0.seconds)
            pendingFirst     <- first.poll
            pendingDuplicate <- duplicate.poll
            hidden           <- directory.lookup(id)
            retained         <- directory.list
            full             <- directory.register(spare)
            before           <- freed.get
            _                <- release.succeed(())
            firstExit        <- first.join
            duplicateExit    <- duplicate.join
            missing          <- directory.remove(id)
            replacement      <- registered(directory, spare)
            usable           <- run(spare.value, "usable")
            after            <- freed.get
            _                <- caller.close(Exit.unit).exit
          yield assertTrue(
            promoted.contains(TaskWait.Promoted),
            pendingFirst.isEmpty,
            pendingDuplicate.isEmpty,
            hidden.isEmpty,
            retained == Chunk(id),
            full == SubagentSessionRegistration.Capacity,
            before == 0,
            after == 1,
            missing == SubagentSessionRemoval.Missing,
            replacement != id,
            usable == "spare:usable",
            cleanup match
              case Cleanup.Normal =>
                firstExit == Exit.succeed(
                  SubagentSessionRemoval.Removed
                ) && duplicateExit == firstExit
              case Cleanup.Defect =>
                firstExit.causeOption.exists(
                  _.defects.contains(defect)
                ) && duplicateExit.causeOption == firstExit.causeOption
          )
        .map(results => results.reduceOption(_ && _).getOrElse(assertTrue(false)))
    },
    test(
      "directory and host closure join parallel resource cleanup and reject admission even under a closed host"
    ) {
      ZIO
        .foreach(Chunk(FirstCloser.Directory, FirstCloser.Host)): firstCloser =>
          val defect = new IllegalStateException("directory cleanup defect")
          for
            supervisor <- TaskSupervisor.open[AgentResult]
            parent     <- SubagentSpawner.root(supervisor)
            caller     <- owner
            host       <- owner
            entered    <- ZIO.foreach(Chunk(0, 1))(_ => Promise.make[Nothing, Unit])
            cleaning   <- ZIO.foreach(Chunk(0, 1))(_ => Promise.make[Nothing, Unit])
            release    <- Promise.make[Nothing, Unit]
            freed      <- Ref.make(0)
            resources <- ZIO.foreach(entered.zip(cleaning).zipWithIndex): (signals, index) =>
              val (started, stopping) = signals
              caller.extend(
                session(
                  parent,
                  ZIO
                    .acquireRelease(ZIO.unit)(_ =>
                      freed.update(_ + 1) *> (if index == 0 then ZIO.die(defect) else ZIO.unit)
                    )
                    .as(
                      LlmClient.mockEffectful((_, _, _) =>
                        (started.succeed(()) *> ZIO.never)
                          .onInterrupt(stopping.succeed(()) *> release.await)
                      )
                    )
                )
              )
            spare      <- session(parent, ZIO.succeed(historyClient("spare")))
            directory  <- host.extend(SubagentSessionDirectory.open())
            ids        <- ZIO.foreach(resources)(registered(directory, _))
            executions <- ZIO.foreach(resources)(resource => submitted(resource.value, "active"))
            _          <- ZIO.foreachDiscard(entered)(_.await)
            promoted <- ZIO.foreach(resources.zip(executions))((resource, id) =>
              promote(resource.value, id)
            )
            firstEffect = firstCloser match
              case FirstCloser.Directory => directory.close
              case FirstCloser.Host      => host.close(Exit.unit)
            secondEffect = firstCloser match
              case FirstCloser.Directory => host.close(Exit.unit)
              case FirstCloser.Host      => directory.close
            first           <- firstEffect.exit.forkScoped
            _               <- ZIO.foreachDiscard(cleaning)(_.await)
            second          <- secondEffect.exit.forkScoped
            _               <- TestClock.adjust(0.seconds)
            firstPending    <- first.poll
            secondPending   <- second.poll
            retained        <- directory.list
            hidden          <- ZIO.foreach(ids)(directory.lookup)
            rejected        <- directory.register(spare)
            before          <- freed.get
            _               <- release.succeed(())
            firstExit       <- first.join
            secondExit      <- second.join
            after           <- freed.get
            empty           <- directory.list
            _               <- caller.close(Exit.unit).exit
            closedHost      <- owner
            _               <- closedHost.close(Exit.unit)
            closedDirectory <- closedHost.extend(SubagentSessionDirectory.open())
            alreadyClosed   <- closedDirectory.register(spare)
            usable          <- run(spare.value, "still owned")
          yield assertTrue(
            promoted.forall(_.contains(TaskWait.Promoted)),
            firstPending.isEmpty,
            secondPending.isEmpty,
            retained.toSet == ids.toSet,
            hidden.forall(_.isEmpty),
            rejected == SubagentSessionRegistration.Closed,
            alreadyClosed == SubagentSessionRegistration.Closed,
            before == 0,
            after == 2,
            firstExit.causeOption.exists(_.defects.contains(defect)),
            firstExit.causeOption == secondExit.causeOption,
            empty.isEmpty,
            usable == "spare:still owned"
          )
        .map(results => results.reduceOption(_ && _).getOrElse(assertTrue(false)))
    }
  )
