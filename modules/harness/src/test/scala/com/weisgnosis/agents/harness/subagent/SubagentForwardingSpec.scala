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

import com.weisgnosis.agents.core.agent.{AgentConfig, AgentResult, ReActAgent}
import com.weisgnosis.agents.core.client.{LlmClient, LlmConfig, TurnOutput}
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.event.{AgentEvent, EventAdmission}
import com.weisgnosis.agents.core.model.{ContentBlock, Msg, Role}
import com.weisgnosis.agents.core.tool.{
  SubagentLifecycleTestPort,
  Tool,
  ToolAuthorizer,
  ToolDef,
  ToolInvocation,
  Toolkit
}
import zio.*
import zio.json.ast.Json
import zio.test.*

object SubagentForwardingSpec extends ZIOSpecDefault:
  private def configured(
      name: String,
      register: SubagentSpawner[AgentResult] => ZIO[Scope, AgentError, Toolkit],
      client: ZIO[Scope, AgentError, LlmClient]
  ): IO[AgentError, SubagentFactory] = ZIO.fromEither(
    SubagentFactory.make(
      AgentConfig(name = name),
      LlmConfig("scripted"),
      Toolkit.empty,
      SubagentToolBoundary(),
      SubagentToolBoundary(),
      register,
      _ => client,
      ToolAuthorizer.allow
    )
  )

  private def invoke(name: String, prompt: String, extra: (String, Json)*): ContentBlock.ToolUse =
    ContentBlock.ToolUse(
      "model-call",
      name,
      Json.Obj(Chunk("prompt" -> Json.Str(prompt)) ++ Chunk.fromIterable(extra))
    )

  private def model(
      reply: String,
      call: Option[ContentBlock.ToolUse],
      requests: Ref[Int]
  ): LlmClient = LlmClient.mockEffectful((history, _, _) =>
    requests
      .update(_ + 1)
      .as(
        call.filter(_ => history.lastOption.exists(_.role == Role.User)) match
          case Some(value) => TurnOutput.ToolCalls(Msg.assistant(Chunk(value)))
          case None        => TurnOutput.FinalText(Msg.assistant(reply))
      )
  )

  private def completed(
      registry: SubagentTaskRegistry
  ): IO[AgentError, (SubagentTaskId, AgentResult)] =
    registry.list.flatMap {
      case Chunk(SubagentTaskSnapshot(id, SubagentTaskState.Completed(Exit.Success(result)))) =>
        ZIO.succeed((id, result))
      case _ => ZIO.fail(AgentError.InvalidConfiguration("Expected one completed child"))
    }

  def spec: Spec[TestEnvironment & Scope, Any] = suite("SubagentForwardingSpec")(
    test("fixed and catalog tools forward nested execution identities before parent completion") {
      for
        requests      <- Ref.make(0)
        supervisor    <- TaskSupervisor.open[AgentResult]
        root          <- SubagentSpawner.root(supervisor)
        grandRegistry <- Promise.make[Nothing, SubagentTaskRegistry]
        grand <- configured(
          "grand",
          _ => ZIO.succeed(Toolkit.empty),
          ZIO.succeed(model("grand", None, requests))
        )
        child <- configured(
          "child",
          child =>
            SubagentCatalog
              .open(child, Chunk(SubagentEntry("grand", "Grandchild", grand)))
              .flatMap(catalog =>
                ZIO
                  .foreachDiscard(catalog.lookup("grand"))(grandRegistry.succeed(_))
                  .as(SubagentTools.forCatalog(catalog))
              ),
          ZIO.succeed(
            model(
              "child",
              Some(invoke("agent_spawn", "grand work", "agent_id" -> Json.Str("grand"))),
              requests
            )
          )
        )
        registry <- SubagentTaskRegistry.open(root, child)
        parent <- ReActAgent.make(
          client = model("parent", Some(invoke("agent_spawn", "child work")), requests),
          toolkit = SubagentTools.make(registry)
        )
        events      <- parent.streamEvents(Msg.user("work")).runCollect
        childResult <- completed(registry)
        grandTasks  <- grandRegistry.await
        grandResult <- completed(grandTasks)
        count       <- requests.get
        childSource = childResult._1.value.toString
        grandSource = childSource + "/" + grandResult._1.value.toString
        starts      = events.collect { case event: AgentEvent.AgentStartEvent => event.source }
        ends        = events.collect { case event: AgentEvent.AgentEndEvent => event.source }
        childToolEnds = events.collect {
          case event: AgentEvent.ToolResultEndEvent if event.source.contains(childSource) => event
        }
        lifecycleStarts = events.collect { case event: AgentEvent.AgentStartEvent => event }
        lifecycleEnds   = events.collect { case event: AgentEvent.AgentEndEvent => event }
      yield assertTrue(
        starts == Chunk(None, Some(childSource), Some(grandSource)),
        ends == Chunk(Some(grandSource), Some(childSource), None),
        events.headOption == lifecycleStarts.headOption,
        events.lastOption == lifecycleEnds.lastOption,
        lifecycleStarts.forall(start =>
          start.replyId.nonEmpty && lifecycleEnds.count(end =>
            end.source == start.source && end.replyId == start.replyId
          ) == 1
        ),
        count == 5,
        childResult._2.reply.text == "child",
        grandResult._2.reply.text == "grand",
        childResult._2.history.flatMap(_.toolResults).size == 1,
        childToolEnds.forall(_.context.nonEmpty),
        childToolEnds.size == 1
      )
    },
    test("parent cancellation delivers the child's final lifecycle event to a retained observer"):
      for
        supervisor  <- TaskSupervisor.open[AgentResult]
        root        <- SubagentSpawner.root(supervisor)
        entered     <- Promise.make[Nothing, Unit]
        cleaning    <- Promise.make[Nothing, Unit]
        release     <- Promise.make[Nothing, Unit]
        cleaned     <- Ref.make(false)
        parentFiber <- Promise.make[Nothing, Fiber.Runtime[AgentError, AgentResult]]
        requests    <- Ref.make(0)
        observed    <- Ref.make(Chunk.empty[AgentEvent])
        child <- configured(
          "cancelled-child",
          _ => ZIO.succeed(Toolkit.empty),
          ZIO.succeed(
            LlmClient.mockEffectful((_, _, _) =>
              ZIO.acquireRelease(entered.succeed(()))(_ =>
                cleaning.succeed(()) *> release.await *> cleaned.set(true)
              ) *> ZIO.never
            )
          )
        )
        metadata <- ZIO.fromEither(
          SubagentLifecycleMetadata.from("owner-session", "cancelled-session", "child")
        )
        registry <- SubagentTaskRegistry.open(root, child, lifecycle = Some(metadata))
        parent <- ReActAgent.make(
          client = model("parent", Some(invoke("agent_spawn", "child work")), requests),
          toolkit = SubagentTools.make(registry)
        )
        relay = new Tool[Any, Json, String]:
          def definition: ToolDef =
            ToolDef("observe_parent", "Retain parent event delivery", Json.Obj())
          def decodeInput(raw: Json): Either[String, Json] = Right(raw)
          def encodeOutput(output: String): String         = output
          def execute(input: Json): IO[AgentError, String] =
            executeWithContext(input, ToolInvocation.Detached)
          override def executeWithContext(
              input: Json,
              invocation: ToolInvocation
          ): IO[AgentError, String] =
            invocation match
              case ToolInvocation.Detached =>
                ZIO.fail(
                  AgentError.InvalidConfiguration("The observer requires an active invocation")
                )
              case ToolInvocation.Active(_, _, _, destination) =>
                ZIO.scoped:
                  for
                    fiber <- parent
                      .runForwarding(Msg.user(input.toString), destination, "parent")
                      .forkScoped
                    _ <- parentFiber.succeed(fiber)
                    // Retain this observer independently of the interrupted parent execution.
                    _ <- fiber.await
                  yield "parent joined"
        observer <- ReActAgent.make(
          client = model("observer complete", Some(invoke("observe_parent", "work")), requests),
          toolkit = Toolkit.single(relay)
        )
        assertion <- ZIO.scoped:
          (for
            collecting <- observer
              .streamEvents(Msg.user("observe"))
              .tap(event => observed.update(_ :+ event))
              .runCollect
              .forkScoped
            _       <- entered.await
            running <- parentFiber.await
            task <- registry.list.flatMap:
              case Chunk(value) => ZIO.succeed(value.id)
              case _ => ZIO.fail(AgentError.InvalidConfiguration("Expected one running child"))
            stopping        <- running.interrupt.forkScoped
            _               <- cleaning.await
            pending         <- stopping.poll
            before          <- observed.get
            _               <- release.succeed(())
            cancelled       <- stopping.join
            events          <- collecting.join
            terminal        <- registry.await(task)
            finishedCleanup <- cleaned.get
            childSource = s"parent/${task.value}"
            starts = events.collect:
              case event: AgentEvent.AgentStartEvent if event.source.contains(childSource) => event
            ends = events.collect:
              case event: AgentEvent.AgentEndEvent if event.source.contains(childSource) => event
            nestedEnds = events.collect:
              case event: AgentEvent.AgentEndEvent
                  if event.source.exists(source => source == childSource || source == "parent") =>
                event.source
          yield assertTrue(
            pending.isEmpty,
            !before.exists {
              case event: AgentEvent.AgentEndEvent => event.source.contains(childSource)
              case _                               => false
            },
            !before.exists(_.isInstanceOf[AgentEvent.SubagentEndEvent]),
            events.collect { case event: AgentEvent.SubagentEndEvent => event } == Chunk(
              AgentEvent
                .SubagentEndEvent("cancelled-session", "child", "interrupted", Some(childSource))
            ),
            events.collect { case event: AgentEvent.SubagentSpawnEvent => event } == Chunk(
              AgentEvent.SubagentSpawnEvent(
                "owner-session",
                "cancelled-session",
                "child",
                None,
                Some(childSource)
              )
            ),
            cancelled.isInterrupted,
            terminal.exists(_.isInterrupted),
            finishedCleanup,
            starts.size == 1 && ends.size == 1,
            starts.headOption.exists(start =>
              start.replyId.nonEmpty && ends.headOption.exists(_.replyId == start.replyId)
            ),
            nestedEnds == Chunk(Some(childSource), Some("parent")),
            events.lastOption.exists {
              case event: AgentEvent.AgentEndEvent => event.source.isEmpty
              case _                               => false
            }
          )).ensuring(release.succeed(()).unit)
      yield assertion
    ,
    test("caller cancellation joins a completed child's pending lifecycle admission"):
      checkAll(Gen.boolean): duringAdmission =>
        for
          supervisor   <- TaskSupervisor.open[AgentResult]
          root         <- SubagentSpawner.root(supervisor)
          entered      <- Promise.make[Nothing, Unit]
          spawning     <- Promise.make[Nothing, Unit]
          releaseSpawn <- Promise.make[Nothing, Unit]
          ending       <- Promise.make[Nothing, Unit]
          releaseEnd   <- Promise.make[Nothing, Unit]
          observed     <- Ref.make(Chunk.empty[AgentEvent])
          factory <- configured(
            "held-end-child",
            _ => ZIO.succeed(Toolkit.empty),
            ZIO.succeed(LlmClient.mockEffectful((_, _, _) => entered.succeed(()) *> ZIO.never))
          )
          metadata <- ZIO.fromEither(SubagentLifecycleMetadata.from("parent", "child", "agent"))
          registry <- SubagentTaskRegistry.open(root, factory, lifecycle = Some(metadata))
          port <- SubagentLifecycleTestPort.open(event =>
            (event match
              case _: AgentEvent.SubagentSpawnEvent if duringAdmission =>
                spawning.succeed(()) *> releaseSpawn.await
              case _: AgentEvent.SubagentEndEvent => ending.succeed(()) *> releaseEnd.await
              case _                              => ZIO.unit
            ) *>
              observed.update(_ :+ event).as(EventAdmission.Accepted)
          )
          assertion <- ZIO.scoped:
            (for
              running <- ZIO
                .scoped(
                  registry.submit(Msg.user("work"), Some(port)) *> ZIO.never
                )
                .forkScoped
              _        <- if duringAdmission then spawning.await else entered.await
              stopping <- running.interrupt.forkScoped
              _ <- ZIO.when(duringAdmission)(
                TestClock.adjust(1.second) *> releaseSpawn.succeed(())
              )
              _         <- ending.await
              _         <- TestClock.adjust(1.second)
              pending   <- stopping.poll
              _         <- releaseEnd.succeed(())
              cancelled <- stopping.join
              events    <- observed.get
            yield assertTrue(
              pending.isEmpty,
              cancelled.isInterrupted,
              events.count(_.isInstanceOf[AgentEvent.SubagentEndEvent]) == 1
            )).ensuring(releaseSpawn.succeed(()) *> releaseEnd.succeed(()).unit)
        yield assertion
    ,
    test("promotion closes the parent invocation lease while the retained child finishes once") {
      for
        supervisor  <- TaskSupervisor.open[AgentResult]
        root        <- SubagentSpawner.root(supervisor)
        entered     <- Promise.make[Nothing, Unit]
        resume      <- Promise.make[Nothing, Unit]
        childCalls  <- Ref.make(0)
        parentCalls <- Ref.make(0)
        observed    <- Ref.make(Chunk.empty[AgentEvent])
        factory <- configured(
          "promoted",
          _ => ZIO.succeed(Toolkit.empty),
          ZIO.succeed(
            LlmClient.mockEffectful((_, _, hub) =>
              childCalls.update(_ + 1) *> hub.publish(
                AgentEvent.TextBlockDeltaEvent("before", 0)
              ) *>
                entered.succeed(()) *> resume.await *>
                hub
                  .publish(AgentEvent.TextBlockDeltaEvent("after", 0))
                  .as(TurnOutput.FinalText(Msg.assistant("finished")))
            )
          )
        )
        registry <- SubagentTaskRegistry.open(root, factory)
        parent <- ReActAgent.make(
          client = model("parent", Some(invoke("agent_spawn", "work")), parentCalls),
          toolkit = SubagentTools.make(registry)
        )
        result <- ZIO.scoped {
          (for
            running <- parent
              .streamEvents(Msg.user("work"))
              .tap(event => observed.update(_ :+ event))
              .runCollect
              .forkScoped
            _      <- entered.await
            _      <- TestClock.adjust(30.seconds)
            events <- running.join
            tasks  <- registry.list
            id <- ZIO
              .fromOption(tasks.headOption.map(_.id))
              .orElseFail(AgentError.InvalidConfiguration("Missing promoted task"))
            _       <- resume.succeed(())
            exit    <- registry.await(id)
            after   <- observed.get
            calls   <- childCalls.get
            history <- parent.history
          yield assertTrue(
            tasks.map(_.state) == Chunk(SubagentTaskState.Running),
            history.flatMap(_.toolResults).exists(_.text.contains("Promoted")),
            events.exists {
              case AgentEvent.TextBlockDeltaEvent("before", _, _, _) => true
              case _                                                 => false
            },
            !events.exists {
              case AgentEvent.TextBlockDeltaEvent("after", _, _, _) => true
              case _                                                => false
            },
            after == events,
            exit.exists {
              case Exit.Success(result) => result.reply.text == "finished"
              case Exit.Failure(_)      => false
            },
            calls == 1
          )).ensuring(resume.succeed(()).unit)
        }
      yield result
    },
    test(
      "session and directory sends use fresh execution leases and removal joins cancelled cleanup"
    ) {
      val defect = new IllegalStateException("child cleanup")
      for
        supervisor  <- TaskSupervisor.open[AgentResult]
        root        <- SubagentSpawner.root(supervisor)
        builds      <- Ref.make(0)
        parentCalls <- Ref.make(0)
        entered     <- Promise.make[Nothing, Unit]
        cleaning    <- Promise.make[Nothing, Unit]
        release     <- Promise.make[Nothing, Unit]
        factory <- configured(
          "session",
          _ => builds.update(_ + 1).as(Toolkit.empty),
          ZIO.succeed(
            LlmClient.mockEffectful((history, _, _) =>
              if history.lastOption.exists(_.text == "blocked") then
                ZIO.acquireRelease(entered.succeed(()))(_ =>
                  cleaning.succeed(()) *> release.await *> ZIO.die(defect)
                ) *> ZIO.never
              else
                ZIO.succeed(
                  TurnOutput.FinalText(
                    Msg.assistant(history.filter(_.role == Role.User).map(_.text).mkString("|"))
                  )
                )
            )
          )
        )
        admission <- SubagentSession.open(root, factory)
        resource <- admission match
          case TaskResourceAdmission.Accepted(value) => ZIO.succeed(value)
          case TaskResourceAdmission.Closed =>
            ZIO.fail(AgentError.InvalidConfiguration("Closed owner"))
        directory    <- SubagentSessionDirectory.open()
        registration <- directory.register(resource)
        sessionId <- registration match
          case SubagentSessionRegistration.Accepted(id) => ZIO.succeed(id)
          case _ => ZIO.fail(AgentError.InvalidConfiguration("Rejected session"))
        first <- ReActAgent
          .make(
            client = model("one", Some(invoke("agent_send", "first")), parentCalls),
            toolkit = SubagentTools.forSession(resource.value)
          )
          .flatMap(_.streamEvents(Msg.user("work")).runCollect)
        second <- ReActAgent
          .make(
            client = model(
              "two",
              Some(
                invoke("agent_send", "second", "session_id" -> Json.Str(sessionId.value.toString))
              ),
              parentCalls
            ),
            toolkit = SubagentTools.forDirectory(directory)
          )
          .flatMap(_.streamEvents(Msg.user("work")).runCollect)
        records <- resource.value.executions.list
        blocked <- ReActAgent.make(
          client = model(
            "three",
            Some(
              invoke("agent_send", "blocked", "session_id" -> Json.Str(sessionId.value.toString))
            ),
            parentCalls
          ),
          toolkit = SubagentTools.forDirectory(directory)
        )
        cancellation <- ZIO.scoped {
          (for
            running       <- blocked.run(Msg.user("work")).forkScoped
            _             <- entered.await
            removing      <- directory.remove(sessionId).forkScoped
            _             <- cleaning.await
            stopping      <- running.interrupt.forkScoped
            pendingRemove <- removing.poll
            pendingCancel <- stopping.poll
            _             <- release.succeed(())
            _             <- removing.join
            stopped       <- stopping.join
            retained      <- resource.value.executions.list
            membership    <- directory.lookup(sessionId)
          yield assertTrue(
            pendingRemove.isEmpty,
            pendingCancel.isEmpty,
            stopped.isInterrupted,
            membership.isEmpty,
            retained.exists(_.state match
              case SubagentTaskState.Completed(Exit.Failure(cause)) =>
                cause.isInterrupted && cause.defects.contains(defect)
              case _ => false)
          )).ensuring(release.succeed(()).unit)
        }
        built <- builds.get
        firstSources = first.collect { case event: AgentEvent.AgentStartEvent =>
          event.source
        }.flatten
        secondSources = second.collect { case event: AgentEvent.AgentStartEvent =>
          event.source
        }.flatten
      yield assertTrue(
        built == 1,
        records.size == 2,
        firstSources.size == 1,
        secondSources.size == 1,
        firstSources != secondSources,
        records.map(_.id.value.toString).toSet == (firstSources ++ secondSources).toSet,
        records.exists(_.state match
          case SubagentTaskState.Completed(Exit.Success(result)) =>
            result.reply.text == "first|second"
          case _ => false)
      ) && cancellation
    }
  ) @@ TestAspect.timeout(20.seconds)
