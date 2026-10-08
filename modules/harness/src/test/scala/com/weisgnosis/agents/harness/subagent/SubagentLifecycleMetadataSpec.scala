/*
 * Copyright 2024-2026 WeisGnosis (https://weisgnosis.com)
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 */
package com.weisgnosis.agents.harness.subagent

import com.weisgnosis.agents.core.agent.{AgentConfig, AgentResult, ReActAgent}
import com.weisgnosis.agents.core.client.{LlmClient, LlmConfig, TurnOutput}
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.event.AgentEvent
import com.weisgnosis.agents.core.model.{ContentBlock, Msg}
import com.weisgnosis.agents.core.tool.{Tool, ToolAuthorizer, ToolDef, ToolInvocation, Toolkit}
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

object SubagentLifecycleMetadataSpec extends ZIOSpecDefault:
  private enum FixedCase:
    case Completed, Failed, Disabled, OwnerClosed

  private def configured(client: LlmClient): IO[AgentError, SubagentFactory] =
    ZIO.fromEither(
      SubagentFactory.make(
        AgentConfig(),
        LlmConfig("scripted-child"),
        Toolkit.empty,
        SubagentToolBoundary(),
        SubagentToolBoundary(),
        _ => ZIO.succeed(Toolkit.empty),
        _ => ZIO.succeed(client),
        ToolAuthorizer.allow
      )
    )

  private def accepted[A](
      effect: ZIO[Scope, AgentError, TaskResourceAdmission[A]]
  ): ZIO[Scope, AgentError, TaskResource[A]] =
    effect.flatMap:
      case TaskResourceAdmission.Accepted(resource) => ZIO.succeed(resource)
      case TaskResourceAdmission.Closed =>
        ZIO.fail(AgentError.InvalidConfiguration("Fixture owner is closed"))

  private def submitted(
      effect: ZIO[Scope, AgentError, SubagentTaskSubmission]
  ): ZIO[Scope, AgentError, SubagentTaskId] =
    effect.flatMap:
      case SubagentTaskSubmission.Accepted(id) => ZIO.succeed(id)
      case other => ZIO.fail(AgentError.InvalidConfiguration(s"Fixture admission: $other"))

  private def parent(
      tools: Toolkit,
      calls: Chunk[ContentBlock.ToolUse]
  ): IO[AgentError, ReActAgent] =
    LlmClient
      .mock(
        calls.map(call => TurnOutput.ToolCalls(Msg.assistant(Chunk(call)))) :+
          TurnOutput.FinalText(Msg.assistant("parent complete"))
      )
      .flatMap(client => ReActAgent.make(client = client, toolkit = tools))

  private def call(name: String, extra: Chunk[(String, Json)] = Chunk.empty): ContentBlock.ToolUse =
    ContentBlock.ToolUse(name, name, Json.Obj(Chunk("prompt" -> Json.Str("work")) ++ extra))

  def spec: Spec[TestEnvironment & Scope, Any] = suite("SubagentLifecycleMetadataSpec")(
    test(
      "checked identities and opt-in fixed lifecycle retain exact correlation and terminal status"
    ):
      val invalid = Chunk("", "  ", "\u2003", "x" * 1025, "\ud800")
      val invalidAccepted =
        invalid.map(value => SubagentLifecycleMetadata.from(value, "child", "worker").isRight)
      for rows <- ZIO.foreach(Chunk.fromIterable(FixedCase.values)): mode =>
          ZIO.scoped:
            for
              host         <- ZIO.acquireRelease(Scope.make)(_.close(Exit.unit))
              supervisor   <- host.extend(TaskSupervisor.open[AgentResult])
              root         <- SubagentSpawner.root(supervisor)
              admitEntered <- Promise.make[Nothing, Unit]
              allowAdmit   <- Promise.make[Nothing, Unit]
              _            <- ZIO.addFinalizer(allowAdmit.succeed(()).unit)
              metadata <- ZIO.fromEither(
                SubagentLifecycleMetadata.from(
                  "parent-session",
                  "child-session",
                  "worker",
                  Some("label & exact")
                )
              )
              child <- configured(
                LlmClient.mockEffectful((_, _, _) =>
                  if mode == FixedCase.Failed then ZIO.fail(AgentError.StorageError("child failed"))
                  else ZIO.succeed(TurnOutput.FinalText(Msg.assistant("child complete")))
                )
              )
              backend <- SubagentTerminalStoreSpec.fixture
              address <- ZIO.fromEither(
                com.weisgnosis.agents.harness.storage.StorageAddress
                  .from(Chunk("event-order"), "tasks")
              )
              base = SubagentTerminalStore.make(backend.store, address)
              held = new SubagentTerminalStore:
                def read(id: SubagentTaskId): IO[AgentError, Option[SubagentTerminalRecord]] =
                  base.read(id)
                def list: IO[AgentError, Chunk[SubagentTerminalRecord]] = base.list
                def publish(
                    record: SubagentTerminalRecord
                ): IO[AgentError, SubagentTerminalPublication] = base.publish(record)
                override def admit(
                    id: SubagentTaskId,
                    workerId: Option[SubagentWorkerId]
                ): IO[AgentError, SubagentTaskAdmissionResult] =
                  admitEntered.succeed(()) *> allowAdmit.await *> base.admit(id, workerId)
              registry <-
                if mode == FixedCase.OwnerClosed then
                  SubagentTaskRegistry.openDurable(root, child, held, lifecycle = Some(metadata))
                else
                  SubagentTaskRegistry.open(
                    root,
                    child,
                    lifecycle = Option.when(mode != FixedCase.Disabled)(metadata)
                  )
              runtime    <- parent(SubagentTools.make(registry), Chunk(call("agent_spawn")))
              collecting <- runtime.streamEvents(Msg.user("delegate")).runCollect.forkScoped
              _ <- ZIO.when(mode == FixedCase.OwnerClosed)(
                admitEntered.await *> host.close(Exit.unit) *> allowAdmit.succeed(())
              )
              events <- collecting.join
              tasks  <- registry.list
              source = tasks.map(_.id.value.toString).headOption
              milestones = events.filter:
                case _: AgentEvent.SubagentSpawnEvent | _: AgentEvent.SubagentStartEvent |
                    _: AgentEvent.SubagentEndEvent =>
                  true
                case _ => false
              status = mode match
                case FixedCase.Failed      => "failed"
                case FixedCase.OwnerClosed => "interrupted"
                case _                     => "completed"
              expected = Chunk[AgentEvent](
                AgentEvent.SubagentSpawnEvent(
                  "parent-session",
                  "child-session",
                  "worker",
                  Some("label & exact"),
                  source
                )
              ) ++
                (if mode == FixedCase.OwnerClosed then Chunk.empty
                 else
                   Chunk[AgentEvent](
                     AgentEvent.SubagentStartEvent("child-session", "worker", source)
                   )) ++
                Chunk[AgentEvent](
                  AgentEvent.SubagentEndEvent("child-session", "worker", status, source)
                )
            yield assertTrue(
              milestones == (if mode != FixedCase.Disabled then expected else Chunk.empty),
              tasks.size == 1,
              events.lastOption.exists:
                case event: AgentEvent.AgentEndEvent => event.source.isEmpty
                case _                               => false
            )
      yield rows.foldLeft(
        assertTrue(
          invalidAccepted.forall(accepted => !accepted),
          SubagentLifecycleMetadata.from("parent", "child", "worker", Some(" ")).isLeft,
          SubagentLifecycleMetadata.from("parent", "child", "worker", Some("\u00e9" * 512)).isRight,
          SubagentLifecycleMetadata.from("parent", "child", "worker", Some("\u00e9" * 513)).isLeft
        )
      )(_ && _)
    ,
    test(
      "queued session commands emit Start only after their turn and cancelled queued work never starts"
    ):
      val cases = Chunk(false, true)
      ZIO
        .foreach(cases): cancelQueued =>
          ZIO.scoped:
            for
              supervisor  <- TaskSupervisor.open[AgentResult]
              root        <- SubagentSpawner.root(supervisor)
              entered     <- Promise.make[Nothing, Unit]
              release     <- Promise.make[Nothing, Unit]
              secondSpawn <- Promise.make[Nothing, Unit]
              observed    <- Ref.make(Chunk.empty[AgentEvent])
              requests    <- Ref.make(0)
              child <- configured(
                LlmClient.mockEffectful((_, _, _) =>
                  requests
                    .updateAndGet(_ + 1)
                    .flatMap:
                      case 1 =>
                        entered.succeed(()) *> release.await
                          .as(TurnOutput.FinalText(Msg.assistant("first")))
                      case _ => ZIO.succeed(TurnOutput.FinalText(Msg.assistant("second")))
                )
              )
              metadata <- ZIO.fromEither(
                SubagentLifecycleMetadata.from("parent", "session", "worker")
              )
              resource <- accepted(SubagentSession.open(root, child, lifecycle = Some(metadata)))
              _        <- ZIO.addFinalizer(release.succeed(()).unit)
              driver = new Tool[Any, Json, String]:
                def definition: ToolDef = ToolDef("queue", "Queue two session commands", Json.Obj())
                def decodeInput(raw: Json): Either[String, Json] = Right(raw)
                def encodeOutput(value: String): String          = value
                def execute(input: Json): IO[AgentError, String] =
                  ZIO.fail(AgentError.InvalidConfiguration("Active invocation required"))
                override def executeWithContext(input: Json, invocation: ToolInvocation)
                    : IO[AgentError, String] = invocation match
                  case ToolInvocation.Detached => execute(input)
                  case ToolInvocation.Active(_, _, _, destination) =>
                    ZIO.scoped:
                      for
                        one <- submitted(resource.value.send(Msg.user("first"), Some(destination)))
                        _   <- entered.await
                        two <- submitted(resource.value.send(Msg.user("second"), Some(destination)))
                        _   <- ZIO.when(cancelQueued)(resource.value.executions.cancel(two))
                        _   <- resource.value.executions.await(one)
                        _   <- resource.value.executions.await(two)
                      yield "joined"
              runtime <- parent(Toolkit.single(driver), Chunk(call("queue")))
              collecting <- runtime
                .streamEvents(Msg.user("queue"))
                .tap(event =>
                  observed
                    .updateAndGet(_ :+ event)
                    .flatMap(current =>
                      secondSpawn
                        .succeed(())
                        .when(current.count(_.isInstanceOf[AgentEvent.SubagentSpawnEvent]) == 2)
                        .unit
                    )
                )
                .runCollect
                .forkScoped
              _           <- secondSpawn.await
              _           <- TestClock.adjust(0.seconds)
              before      <- observed.get
              countBefore <- requests.get
              _           <- release.succeed(())
              events      <- collecting.join
              count       <- requests.get
              spawns = events.collect { case event: AgentEvent.SubagentSpawnEvent => event }
              starts = events.collect { case event: AgentEvent.SubagentStartEvent => event }
              ends   = events.collect { case event: AgentEvent.SubagentEndEvent => event }
            yield assertTrue(
              before.count(_.isInstanceOf[AgentEvent.SubagentStartEvent]) == 1,
              countBefore == 1,
              spawns.size == 2,
              starts.size == (if cancelQueued then 1 else 2),
              count == (if cancelQueued then 1 else 2),
              ends.map(_.status).sorted == (if cancelQueued then Chunk("completed", "interrupted")
                                            else Chunk("completed", "completed")),
              starts.forall(_.childSessionId == "session"),
              ends.forall(_.childSessionId == "session"),
              events.lastOption.exists:
                case event: AgentEvent.AgentEndEvent => event.source.isEmpty
                case _                               => false
            )
        .map(_.foldLeft(assertTrue(true))(_ && _))
    ,
    test("named spawn and send bind the actual registered identity once and reuse it"):
      for
        backend <- SubagentTerminalStoreSpec.fixture
        store <- ZIO.fromEither(
          SubagentNamedSessionStore.make(backend.store, Chunk("named-events"))
        )
        supervisor <- TaskSupervisor.open[AgentResult]
        root       <- SubagentSpawner.root(supervisor)
        child <- configured(
          LlmClient.mockEffectful((_, _, _) =>
            ZIO.succeed(TurnOutput.FinalText(Msg.assistant("saved")))
          )
        )
        authority = new SubagentNamedSessions.Authority:
          def acquire(
              key: SubagentNamedSessionKey,
              address: com.weisgnosis.agents.harness.storage.StorageAddress
          ): ZIO[Scope, AgentError, Unit] = ZIO.unit
        resource <- accepted(
          SubagentNamedSessions.open(
            root,
            "parent",
            Chunk(SubagentEntry("worker", "Worker", child)),
            store,
            authority,
            lifecycleEvents = SubagentLifecycleEvents.Enabled
          )
        )
        extra = Chunk("agent_id" -> Json.Str("worker"), "label" -> Json.Str("same"))
        runtime <- parent(
          SubagentTools.forNamedSessions(resource.value),
          Chunk(call("agent_spawn", extra), call("agent_send", extra))
        )
        updates <- runtime.streamWithResult(Msg.user("reuse")).runCollect
        events = updates.collect {
          case com.weisgnosis.agents.core.agent.AgentRunUpdate.Event(event) => event
        }
        spawns = events.collect { case event: AgentEvent.SubagentSpawnEvent => event }
        starts = events.collect { case event: AgentEvent.SubagentStartEvent => event }
        ends   = events.collect { case event: AgentEvent.SubagentEndEvent => event }
        result <- ZIO
          .fromOption(updates.collect {
            case com.weisgnosis.agents.core.agent.AgentRunUpdate.Result(value) => value
          }.headOption)
          .orElseFail(AgentError.InvalidConfiguration("Missing parent result"))
        projected <- ZIO.foreach(result.history.flatMap(_.toolResults))(observation =>
          ZIO
            .fromEither(observation.outputText.fromJson[SubagentNamedToolResult])
            .mapError(AgentError.InvalidConfiguration(_))
        )
        taskIds = projected.collect {
          case SubagentNamedToolResult(_, _, SubagentToolResult.Task(id, _)) => id
        }
      yield assertTrue(
        spawns.size == 2,
        spawns.map(_.childSessionId).distinct.size == 1,
        spawns.forall(event =>
          event.parentSessionId == "parent" && event.agentId == "worker" && event.label.contains(
            "same"
          ) && !taskIds.contains(event.childSessionId)
        ),
        starts.map(_.childSessionId) == spawns.map(_.childSessionId),
        ends.map(_.childSessionId) == spawns.map(_.childSessionId),
        ends.forall(_.status == "completed"),
        taskIds.distinct.size == 2
      )
  ) @@ TestAspect.timeout(20.seconds)
