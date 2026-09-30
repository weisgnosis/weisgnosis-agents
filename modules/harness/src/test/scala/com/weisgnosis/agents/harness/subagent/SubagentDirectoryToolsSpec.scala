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
import com.weisgnosis.agents.core.model.{Msg, Role, ToolResultState}
import com.weisgnosis.agents.core.tool.{ToolAuthorizer, Toolkit}
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

object SubagentDirectoryToolsSpec extends ZIOSpecDefault:
  private def session(
      parent: SubagentSpawner[AgentResult],
      client: ZIO[Scope, AgentError, LlmClient]
  ): ZIO[Scope, AgentError, TaskResource[SubagentSession]] =
    for
      factory <- ZIO.fromEither(
        SubagentFactory.make(
          AgentConfig(name = "routed-child"),
          LlmConfig("routed-model"),
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
            ZIO.fail(AgentError.InvalidConfiguration("Unexpected closed session"))
    yield resource

  private def registered(
      directory: SubagentSessionDirectory,
      resource: TaskResource[SubagentSession]
  ): IO[AgentError, String] =
    directory
      .register(resource)
      .flatMap:
        case SubagentSessionRegistration.Accepted(id) => ZIO.succeed(id.value.toString)
        case other => ZIO.fail(AgentError.InvalidConfiguration(s"Unexpected registration: $other"))

  private def send(sessionId: String, prompt: String): Json =
    Json.Obj("session_id" -> Json.Str(sessionId), "prompt" -> Json.Str(prompt))

  private def task(sessionId: String, taskId: String): Json =
    Json.Obj("session_id" -> Json.Str(sessionId), "task_id" -> Json.Str(taskId))

  private def observation(output: String): IO[AgentError, SubagentSessionToolResult] =
    ZIO
      .fromEither(output.dropWhile(_ != '{').fromJson[SubagentSessionToolResult])
      .mapError(AgentError.InvalidConfiguration(_))

  private def observedTask(output: String): IO[AgentError, (String, String, SubagentToolState)] =
    observation(output).flatMap:
      case SubagentSessionToolResult.Session(sessionId, SubagentToolResult.Task(id, state)) =>
        ZIO.succeed((sessionId, id, state))
      case other => ZIO.fail(AgentError.InvalidConfiguration(s"Expected routed task: $other"))

  private def historyClient(label: String, calls: Ref[Int]): LlmClient =
    LlmClient.mockEffectful((history, _, _) =>
      val prompts = history.filter(_.role == Role.User).map(_.text)
      calls.update(_ + 1) *>
        (if prompts.lastOption.contains("fail") then
           ZIO.fail(AgentError.InvalidConfiguration("child failed"))
         else ZIO.succeed(TurnOutput.FinalText(Msg.assistant(label + ":" + prompts.mkString("|")))))
    )

  def spec: Spec[TestEnvironment & Scope, Any] = suite("SubagentDirectoryToolsSpec")(
    test(
      "routing preserves fixed session histories and selected identities for results and failures"
    ) {
      for
        supervisor <- TaskSupervisor.open[AgentResult]
        parent     <- SubagentSpawner.root(supervisor)
        leftCalls  <- Ref.make(0)
        rightCalls <- Ref.make(0)
        left       <- session(parent, ZIO.succeed(historyClient("left", leftCalls)))
        right      <- session(parent, ZIO.succeed(historyClient("right", rightCalls)))
        directory  <- SubagentSessionDirectory.open()
        leftId     <- registered(directory, left)
        rightId    <- registered(directory, right)
        tools = SubagentTools.forDirectory(directory)
        first    <- tools.executeSafe("agent_send", send(leftId, "one"))
        one      <- observedTask(first.output)
        separate <- tools.executeSafe("agent_send", send(rightId, "other"))
        other    <- observedTask(separate.output)
        second <- tools.executeSafe(
          "agent_send",
          Json.Obj("prompt" -> Json.Str("two"), "session_id" -> Json.Str(leftId))
        )
        two       <- observedTask(second.output)
        inspected <- tools.executeSafe("agent_task", task(leftId, two._2))
        retained  <- observedTask(inspected.output)
        foreign <- ZIO.foreach(Chunk("agent_task", "agent_cancel"))(name =>
          tools.executeSafe(name, task(rightId, two._2))
        )
        crossed         <- ZIO.foreach(foreign)(value => observedTask(value.output))
        failed          <- tools.executeSafe("agent_send", send(leftId, "fail"))
        failure         <- observedTask(failed.output)
        failedStatus    <- tools.executeSafe("agent_task", task(leftId, failure._2))
        failureRetained <- observedTask(failedStatus.output)
        leftCount       <- leftCalls.get
        rightCount      <- rightCalls.get
      yield assertTrue(
        tools.tools.keySet == Set("agent_send", "agent_task", "agent_cancel"),
        tools.definitions.forall(definition =>
          definition.isReadOnly == (definition.name == "agent_task")
        ),
        tools.definitions.forall(_.isConcurrencySafe),
        first.state == ToolResultState.Success,
        one._1 == leftId && one._3 == SubagentToolState.Completed(SubagentToolText("left:one", 0)),
        other._1 == rightId && other._3 == SubagentToolState.Completed(
          SubagentToolText("right:other", 0)
        ),
        two._1 == leftId && two._3 == SubagentToolState.Completed(
          SubagentToolText("left:one|two", 0)
        ),
        Set(one._2, other._2, two._2).size == 3,
        retained == two,
        foreign.forall(_.state == ToolResultState.Error),
        crossed.forall(_ == (rightId, two._2, SubagentToolState.Missing)),
        failed.state == ToolResultState.Error,
        failure._1 == leftId && failure._3 == SubagentToolState.Failed(
          SubagentToolText("child failed", 0)
        ),
        failedStatus.state == ToolResultState.Success,
        failureRetained == failure,
        leftCount == 3 && rightCount == 1
      )
    },
    test(
      "strict routed inputs reject authority injection and memberships outside the bound directory before work"
    ) {
      val unknown = "00000000-0000-0000-0000-000000000001"
      for
        supervisor <- TaskSupervisor.open[AgentResult]
        parent     <- SubagentSpawner.root(supervisor)
        calls      <- Ref.make(0)
        resource   <- session(parent, ZIO.succeed(historyClient("child", calls)))
        directory  <- SubagentSessionDirectory.open()
        elsewhere  <- SubagentSessionDirectory.open()
        id         <- registered(directory, resource)
        foreignId  <- registered(elsewhere, resource)
        tools = SubagentTools.forDirectory(directory)
        malformed = Chunk(
          "agent_send" -> Json.Obj("prompt" -> Json.Str("missing")),
          "agent_send" -> send("1-1-1-1-1", "short UUID"),
          "agent_send" -> Json.Obj("session_id" -> Json.Str(id), "prompt" -> Json.Num(1)),
          "agent_send" -> Json.Obj("session_id" -> Json.Str(id), "session_id" -> Json.Str(id)),
          "agent_send" -> Json.Obj(
            "session_id" -> Json.Str(id),
            "prompt"     -> Json.Str("a"),
            "prompt"     -> Json.Str("b")
          ),
          "agent_send" -> Json.Obj(
            "session_id" -> Json.Str(id),
            "prompt"     -> Json.Str("work"),
            "factory"    -> Json.Str("other")
          ),
          "agent_task" -> Json.Obj("session_id" -> Json.Num(1), "task_id" -> Json.Str(unknown)),
          "agent_task" -> Json.Obj(
            "session_id" -> Json.Str(id),
            "task_id"    -> Json.Str(unknown),
            "parent"     -> Json.Str("other")
          ),
          "agent_cancel" -> task(id, "1-1-1-1-1"),
          "agent_cancel" -> Json.Obj(
            "session_id" -> Json.Str(id),
            "task_id"    -> Json.Str(unknown),
            "task_id"    -> Json.Str(unknown)
          )
        )
        invalid <- ZIO.foreach(malformed)((name, input) => tools.executeSafe(name, input))
        missing <- ZIO.foreach(Chunk("agent_send", "agent_task", "agent_cancel"))(name =>
          tools.executeSafe(
            name,
            if name == "agent_send" then send(foreignId, "unavailable")
            else task(foreignId, unknown)
          )
        )
        missingValues <- ZIO.foreach(missing)(value => observation(value.output))
        forbidden <- ZIO.foreach(Chunk("agent_spawn", "agent_register", "agent_remove"))(name =>
          tools.executeSafe(name, send(id, "forbidden"))
        )
        before      <- calls.get
        _           <- resource.close
        closed      <- tools.executeSafe("agent_send", send(id, "closed"))
        closedValue <- observation(closed.output)
        tracked     <- resource.value.executions.list
      yield assertTrue(
        invalid.forall(_.state == ToolResultState.Error),
        missing.forall(_.state == ToolResultState.Error),
        missingValues.forall(_ == SubagentSessionToolResult.Missing(foreignId)),
        forbidden.forall(_.state == ToolResultState.Error),
        before == 0 && tracked.isEmpty,
        closed.state == ToolResultState.Error,
        closedValue == SubagentSessionToolResult
          .Session(id, SubagentToolResult.Rejected(SubagentToolRejection.Closed)),
        tools.definitions.forall(
          _.parametersSchema.asObject
            .exists(_.get("additionalProperties").contains(Json.Bool(false)))
        ),
        tools.definitions.forall(definition =>
          definition.parametersSchema.asObject.exists(_.get("required").exists {
            case Json.Arr(fields) =>
              fields.toSet == Set(
                Json.Str("session_id"),
                Json.Str(if definition.name == "agent_send" then "prompt" else "task_id")
              )
            case _ => false
          })
        )
      )
    },
    test(
      "promoted task control joins cleanup while removal revokes later routing without another model request"
    ) {
      for
        supervisor <- TaskSupervisor.open[AgentResult]
        parent     <- SubagentSpawner.root(supervisor)
        entered    <- Promise.make[Nothing, Unit]
        cleaning   <- Promise.make[Nothing, Unit]
        release    <- Promise.make[Nothing, Unit]
        calls      <- Ref.make(0)
        freed      <- Ref.make(0)
        resource <- session(
          parent,
          ZIO
            .acquireRelease(ZIO.unit)(_ => freed.update(_ + 1))
            .as(
              LlmClient.mockEffectful((_, _, _) =>
                (calls.update(_ + 1) *> entered.succeed(()) *> ZIO.never)
                  .onInterrupt(cleaning.succeed(()) *> release.await)
              )
            )
        )
        directory <- SubagentSessionDirectory.open()
        id        <- registered(directory, resource)
        tools = SubagentTools.forDirectory(directory)
        sending    <- tools.executeSafe("agent_send", send(id, "block")).forkScoped
        _          <- entered.await
        _          <- TestClock.adjust(30.seconds)
        sent       <- sending.join
        promoted   <- observedTask(sent.output)
        inspected  <- tools.executeSafe("agent_task", task(id, promoted._2))
        running    <- observedTask(inspected.output)
        cancelling <- tools.executeSafe("agent_cancel", task(id, promoted._2)).forkScoped
        _          <- cleaning.await
        sessionId <- ZIO
          .fromEither(Json.Str(id).as[java.util.UUID])
          .mapError(AgentError.InvalidConfiguration(_))
        removing      <- directory.remove(SubagentSessionId(sessionId)).forkScoped
        _             <- TestClock.adjust(0.seconds)
        cancelPending <- cancelling.poll
        removePending <- removing.poll
        revoked <- ZIO.foreach(Chunk("agent_send", "agent_task", "agent_cancel"))(name =>
          tools.executeSafe(
            name,
            if name == "agent_send" then send(id, "revoked") else task(id, promoted._2)
          )
        )
        unavailable <- ZIO.foreach(revoked)(value => observation(value.output))
        before      <- freed.get
        _           <- release.succeed(())
        cancelled   <- cancelling.join
        terminal    <- observedTask(cancelled.output)
        removed     <- removing.join
        _           <- directory.close
        closed      <- tools.executeSafe("agent_send", send(id, "closed directory"))
        closedValue <- observation(closed.output)
        count       <- calls.get
        after       <- freed.get
      yield assertTrue(
        promoted._1 == id && promoted._3 == SubagentToolState.Promoted,
        running == (id, promoted._2, SubagentToolState.Running),
        cancelPending.isEmpty && removePending.isEmpty,
        revoked.forall(_.state == ToolResultState.Error),
        unavailable.forall(_ == SubagentSessionToolResult.Missing(id)),
        before == 0 && after == 1,
        cancelled.state == ToolResultState.Success,
        terminal == (id, promoted._2, SubagentToolState.Interrupted),
        removed == SubagentSessionRemoval.Removed,
        closed.state == ToolResultState.Error,
        closedValue == SubagentSessionToolResult.Missing(id),
        count == 1
      )
    }
  )
