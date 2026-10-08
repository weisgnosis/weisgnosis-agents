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
package com.weisgnosis.agents.storage.postgresql

import com.weisgnosis.agents.core.agent.AgentResult
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.model.Msg
import com.weisgnosis.agents.harness.subagent.*
import java.util.UUID
import zio.*
import zio.test.*

object PostgresSubagentTerminalStoreSpec extends ZIOSpecDefault:
  import PostgresFixture.*

  private val firstId: SubagentTaskId  = SubagentTaskId(new UUID(0L, 1L))
  private val secondId: SubagentTaskId = SubagentTaskId(new UUID(0L, 2L))

  private def accepted(
      admission: TaskAdmission[AgentResult]
  ): IO[AgentError, SupervisedTask[AgentResult]] =
    admission match
      case TaskAdmission.Accepted(task) => ZIO.succeed(task)
      case TaskAdmission.Closed =>
        ZIO.fail(AgentError.InvalidConfiguration("Fixture supervisor closed"))

  def spec: Spec[TestEnvironment, Any] = suite("PostgresSubagentTerminalStoreSpec")(
    test(
      "joined terminal observations reopen exactly across PostgreSQL adapters with owner isolation and idempotent concurrent publication"
    ):
      fixture: (_, backend, name) =>
        ZIO.scoped:
          for
            secondSource <- dataSource(name + "-reopened")
            second       <- store(secondSource)
            target       <- address(name, "owner-a")
            other        <- address(name, "owner-b")
            terminal = SubagentTerminalStore.make(backend, target)
            reopened = SubagentTerminalStore.make(second, target)
            supervisor <- TaskSupervisor.open[AgentResult]
            cleanup    <- Promise.make[Nothing, Unit]
            release    <- Promise.make[Nothing, Unit]
            reply = Msg.assistant(" exact output \n" + "λé" * 3000)
            admission <- supervisor.attach(
              ZIO.acquireRelease(ZIO.unit)(_ => cleanup.succeed(()).unit *> release.await) *>
                ZIO.succeed(AgentResult(reply, Chunk(reply), 1))
            )
            task <- accepted(admission)
            publishing <- ZIO.uninterruptible:
              for
                fiber <- terminal.publishJoined(firstId, task).interruptible.forkScoped
                _     <- ZIO.addFinalizer(release.succeed(()).unit)
              yield fiber
            _       <- cleanup.await
            before  <- reopened.read(firstId)
            pending <- publishing.poll
            _       <- release.succeed(())
            receipt <- publishing.join
            exact   <- reopened.read(firstId)
            failedAdmission <- supervisor.attach(
              ZIO.acquireRelease(ZIO.unit)(_ =>
                ZIO.die(new IllegalStateException("Task cleanup defect"))
              ) *>
                ZIO.fail(AgentError.StorageError("Execution failed exactly"))
            )
            failedTask <- accepted(failedAdmission)
            publications <- ZIO.foreachPar(Chunk(terminal, reopened))(
              _.publishJoined(secondId, failedTask)
            )
            recovered <- reopened.list
            isolated  <- SubagentTerminalStore.make(second, other).list
          yield assertTrue(
            before.isEmpty,
            pending.isEmpty,
            receipt match
              case SubagentTerminalPublication.Stored(_)        => true
              case SubagentTerminalPublication.AlreadyStored(_) => false
            ,
            exact.contains(
              SubagentTerminalRecord(firstId, SubagentTerminalOutcome.Completed(reply.text))
            ),
            recovered == Chunk(
              SubagentTerminalRecord(firstId, SubagentTerminalOutcome.Completed(reply.text)),
              SubagentTerminalRecord(
                secondId,
                SubagentTerminalOutcome.Failed(
                  Chunk("Execution failed exactly"),
                  SubagentTaskDefects.Present
                )
              )
            ),
            publications.count(value =>
              value match
                case SubagentTerminalPublication.Stored(_)        => true
                case SubagentTerminalPublication.AlreadyStored(_) => false
            ) == 1,
            publications.count(value =>
              value match
                case SubagentTerminalPublication.AlreadyStored(_) => true
                case SubagentTerminalPublication.Stored(_)        => false
            ) == 1,
            isolated.isEmpty
          )
  ) @@ TestAspect.withLiveRandom @@ TestAspect.withLiveSystem @@ TestAspect.withLiveClock @@ TestAspect
    .timeout(30.seconds) @@ TestAspect.ifEnvSet("WEISGNOSIS_POSTGRES_URL")
