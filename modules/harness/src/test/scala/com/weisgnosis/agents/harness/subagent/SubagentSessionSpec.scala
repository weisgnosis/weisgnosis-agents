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

import com.weisgnosis.agents.core.agent.{
  AgentConfig,
  AgentResult,
  AgentRunState,
  PendingActing,
  ReActAgent,
  SuppliedToolResult
}
import com.weisgnosis.agents.core.client.{LlmClient, LlmConfig, TurnOutput}
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.event.AgentEvent
import com.weisgnosis.agents.core.model.{ContentBlock, GenerateReason, Msg, Role, ToolResultState}
import com.weisgnosis.agents.core.tool.{
  Tool,
  ToolAuthorization,
  ToolAuthorizer,
  ToolDef,
  ToolExecutionResult,
  ToolInvocation,
  Toolkit
}
import com.weisgnosis.agents.harness.permission.{
  InteractivePermissionAuthorizer,
  ConfirmedBatchAuthorizer,
  ConfirmedToolCall,
  ConfirmResult,
  PermissionBehavior,
  PermissionEngine,
  PermissionRequests,
  PermissionRule
}
import com.weisgnosis.agents.harness.plan.{PlanMode, PlanPermissionGate, PlanToolAccess}
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

object SubagentSessionSpec extends ZIOSpecDefault:
  private enum ResumeCompletion:
    case Confirmed, Supplied

  private enum RestoreFailure:
    case NotSuspended, ChangedBindings

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
      "restored child sessions preserve completed and supplied results under the new host policy"
    ):
      for
        originalOwner <- TaskSupervisor.open[AgentResult]
        originalRoot  <- SubagentSpawner.root(originalOwner)
        originalModel <- Ref.make(0)
        originalCalls <- Ref.make(Chunk.empty[Json])
        sources       <- Ref.make(Chunk.empty[String])
        definition = ToolDef("lookup", "Read a host value", Json.Obj())
        originalTool = Tool.make(definition)(input =>
          originalCalls.update(_ :+ input).as("original")
        )
        originalFactory <- factory(
          child => sources.update(_ :+ child.source).as(Toolkit(Map("lookup" -> originalTool))),
          _ =>
            ZIO.succeed(
              LlmClient.mockEffectful((_, _, _) =>
                originalModel
                  .update(_ + 1)
                  .as(
                    TurnOutput.ToolCalls(
                      Msg.assistant(
                        Chunk
                          .fromIterable(0 until 3)
                          .map(ordinal =>
                            ContentBlock
                              .ToolUse("same", "lookup", Json.Obj("value" -> Json.Num(ordinal)))
                          )
                      )
                    )
                  )
              )
            ),
          ToolAuthorizer(request =>
            ZIO.succeed(
              if request.callOrdinal.contains(0) then ToolAuthorization.Approved(request.call.input)
              else ToolAuthorization.Deferred("Wait for host")
            )
          )
        )
        original <- accepted(SubagentSession.open(originalRoot, originalFactory))
        pending  <- run(original.value, "work")
        augmented <- ZIO.fromEither(
          PendingActing.withResults(
            pending,
            Chunk(SuppliedToolResult(1, ToolExecutionResult("external")))
          )
        )
        decoded <- ZIO
          .fromEither(augmented.toJson.fromJson[AgentResult])
          .mapError(AgentError.DeserializationError(_))
        _              <- original.close
        receivingOwner <- TaskSupervisor.open[AgentResult]
        receivingRoot  <- SubagentSpawner.root(receivingOwner)
        acquired       <- Ref.make(0)
        released       <- Ref.make(0)
        currentModel   <- Ref.make(0)
        currentCalls   <- Ref.make(0)
        currentTool = Tool.make(definition)(_ => currentCalls.update(_ + 1).as("must not execute"))
        currentFactory <- factory(
          child => sources.update(_ :+ child.source).as(Toolkit(Map("lookup" -> currentTool))),
          _ =>
            ZIO
              .acquireRelease(acquired.update(_ + 1))(_ => released.update(_ + 1))
              .as(
                LlmClient.mockEffectful((_, _, _) =>
                  currentModel.update(_ + 1).as(TurnOutput.FinalText(Msg.assistant("restored")))
                )
              )
        )
        restored <- accepted(
          SubagentSession.openRestored(
            receivingRoot,
            currentFactory.inheritingParentDenials(
              ZIO.succeed(
                PermissionEngine(Chunk(PermissionRule("lookup", PermissionBehavior.Deny)))
              )
            ),
            decoded
          )
        )
        recordsBefore <- restored.value.executions.list
        modelBefore   <- currentModel.get
        callsBefore   <- currentCalls.get
        acquisitions  <- acquired.get
        id            <- submitted(restored.value.resume())
        completed     <- result(restored.value, id)
        oldCount      <- originalModel.get
        oldInputs     <- originalCalls.get
        newCount      <- currentModel.get
        newCalls      <- currentCalls.get
        lineages      <- sources.get
        _             <- restored.close
        closed        <- released.get
      yield assertTrue(
        pending.isSuspended,
        PendingActing.pendingCalls(decoded).map(_.map(_.callOrdinal)) == Right(Chunk(2)),
        recordsBefore.isEmpty,
        modelBefore == 0 && callsBefore == 0,
        acquisitions == 1,
        oldCount == 1 && oldInputs == Chunk(Json.Obj("value" -> Json.Num(0))),
        newCount == 1 && newCalls == 0,
        completed.history.flatMap(_.toolResults).map(_.state) ==
          Chunk(ToolResultState.Success, ToolResultState.Success, ToolResultState.Denied),
        completed.history.flatMap(_.toolResults).take(2).map(_.text) == Chunk(
          "original",
          "external"
        ),
        completed.history
          .filter(_.role == Role.Assistant)
          .headOption
          .contains(pending.reply.withGenerateReason(GenerateReason.ToolCalls)),
        completed.reply.text == "restored" && !completed.isSuspended,
        lineages.size == 2 && lineages.distinct.size == 2,
        closed == 1
      )
    ,
    test("failed restoration joins acquired resource cleanup before returning its full failure"):
      for
        supervisor <- TaskSupervisor.open[AgentResult]
        root       <- SubagentSpawner.root(supervisor)
        definition = ToolDef("lookup", "Original host definition", Json.Obj())
        lookup     = Tool.make(definition)(_ => ZIO.succeed("unused"))
        sourceFactory <- factory(
          _ => ZIO.succeed(Toolkit(Map("lookup" -> lookup))),
          _ =>
            LlmClient.mock(
              Chunk(
                TurnOutput.ToolCalls(
                  Msg.assistant(Chunk(ContentBlock.ToolUse("call", "lookup", Json.Obj())))
                )
              )
            ),
          ToolAuthorizer(_ => ZIO.succeed(ToolAuthorization.Deferred("Wait")))
        )
        source     <- accepted(SubagentSession.open(root, sourceFactory))
        checkpoint <- run(source.value, "work")
        _          <- source.close
        assertions <- ZIO.foreach(
          Chunk(RestoreFailure.NotSuspended, RestoreFailure.ChangedBindings)
        ): failure =>
          for
            cleaning   <- Promise.make[Nothing, Unit]
            release    <- Promise.make[Nothing, Unit]
            cleanup    <- Ref.make(Chunk.empty[String])
            model      <- Ref.make(0)
            callerExit <- Ref.make(Option.empty[Exit[Nothing, Unit]])
            defect = new IllegalStateException("restoration cleanup marker")
            selected = failure match
              case RestoreFailure.NotSuspended => definition
              case RestoreFailure.ChangedBindings =>
                definition.copy(description = "Changed binding")
            candidate = failure match
              case RestoreFailure.NotSuspended => checkpoint.copy(state = AgentRunState.Completed)
              case RestoreFailure.ChangedBindings => checkpoint
            targetFactory <- factory(
              _ =>
                ZIO
                  .acquireRelease(ZIO.unit)(_ => cleanup.update(_ :+ "registration"))
                  .as(Toolkit(Map("lookup" -> Tool.make(selected)(_ => ZIO.succeed("unused"))))),
              _ =>
                ZIO
                  .acquireRelease(ZIO.unit)(_ =>
                    cleaning.succeed(()) *> release.await *> cleanup.update(_ :+ "client") *> ZIO
                      .die(defect)
                  )
                  .as(
                    LlmClient.mockEffectful((_, _, _) =>
                      model.update(_ + 1).as(TurnOutput.FinalText(Msg.assistant("must not run")))
                    )
                  )
            )
            assertion <- ZIO.scoped:
              (for
                caller <- ZIO.acquireRelease(Scope.make)(scope =>
                  scope.close(Exit.unit).exit.flatMap(exit => callerExit.set(Some(exit)))
                )
                opening <- caller
                  .extend(SubagentSession.openRestored(root, targetFactory, candidate))
                  .exit
                  .forkScoped
                _       <- cleaning.await
                waiting <- opening.poll
                before  <- cleanup.get
                _       <- release.succeed(())
                exit    <- opening.join
                after   <- cleanup.get
                calls   <- model.get
              yield assertTrue(
                waiting.isEmpty,
                before.isEmpty,
                after == Chunk("client", "registration"),
                calls == 0,
                exit.causeOption.exists(cause =>
                  cause.failures.exists(_.isInstanceOf[AgentError.InvalidConfiguration]) &&
                    cause.defects == List(defect)
                )
              )).ensuring(release.succeed(()).unit)
            replayed <- callerExit.get
          yield assertion && assertTrue(
            replayed.exists(_.causeOption.contains(Cause.die(defect)))
          )
      yield assertions.foldLeft(assertTrue(true))(_ && _)
    ,
    test("host resume retains partial checkpoints and reuses installed confirmation authority"):
      ZIO
        .foreach(Chunk(ResumeCompletion.Confirmed, ResumeCompletion.Supplied)): completion =>
          ZIO.scoped:
            for
              supervisor <- TaskSupervisor.open[AgentResult]
              root       <- SubagentSpawner.root(supervisor)
              requests   <- Ref.make(0)
              executed   <- Ref.make(Chunk.empty[Json])
              installed <- Ref.make[ToolAuthorizer](
                ToolAuthorizer(_ =>
                  ZIO.succeed(ToolAuthorization.Deferred("Host confirmation required"))
                )
              )
              lookup = Tool.make(ToolDef("lookup", "Record approved input", Json.Obj()))(input =>
                executed.update(_ :+ input).as("local")
              )
              configured <- factory(
                _ => ZIO.succeed(Toolkit(Map("lookup" -> lookup))),
                _ =>
                  ZIO.succeed(
                    LlmClient.mockEffectful((_, _, _) =>
                      requests
                        .updateAndGet(_ + 1)
                        .map(count =>
                          if count == 1 then
                            TurnOutput.ToolCalls(
                              Msg.assistant(
                                Chunk(
                                  ContentBlock
                                    .ToolUse("same", "lookup", Json.Obj("value" -> Json.Num(0))),
                                  ContentBlock
                                    .ToolUse("same", "lookup", Json.Obj("value" -> Json.Num(1)))
                                )
                              )
                            )
                          else TurnOutput.FinalText(Msg.assistant("done"))
                        )
                    )
                  ),
                ToolAuthorizer(request => installed.get.flatMap(_.authorize(request)))
              )
              resource <- accepted(SubagentSession.open(root, configured))
              session = resource.value
              first        <- submitted(session.send(Msg.user("work")))
              pending      <- result(session, first)
              deniedSend   <- submitted(session.send(Msg.user("cannot replace pending")))
              rejectedSend <- session.executions.await(deniedSend)
              invalid <- submitted(
                session.resume(Chunk(SuppliedToolResult(9, ToolExecutionResult("invalid"))))
              )
              invalidExit <- session.executions.await(invalid)
              partialAuthorizer <- ConfirmedBatchAuthorizer.makePartial(
                pending,
                Chunk(ConfirmedToolCall(0, ConfirmResult(true))),
                ZIO.succeed(PermissionEngine())
              )
              _               <- installed.set(partialAuthorizer)
              partial         <- submitted(session.resume())
              remaining       <- result(session, partial)
              beforeModel     <- requests.get
              beforeExecution <- executed.get
              supplied = completion match
                case ResumeCompletion.Confirmed => Chunk.empty[SuppliedToolResult]
                case ResumeCompletion.Supplied =>
                  Chunk(SuppliedToolResult(1, ToolExecutionResult("external")))
              augmented <- ZIO.fromEither(PendingActing.withResults(remaining, supplied))
              confirmations = completion match
                case ResumeCompletion.Confirmed => Chunk(ConfirmedToolCall(1, ConfirmResult(true)))
                case ResumeCompletion.Supplied  => Chunk.empty[ConfirmedToolCall]
              finalAuthorizer <- ConfirmedBatchAuthorizer
                .make(augmented, confirmations, ZIO.succeed(PermissionEngine()))
              _           <- installed.set(finalAuthorizer)
              resumed     <- submitted(session.resume(supplied))
              completed   <- result(session, resumed)
              next        <- run(session, "next")
              readyResume <- submitted(session.resume())
              readyExit   <- session.executions.await(readyResume)
              count       <- requests.get
              inputs      <- executed.get
              _           <- resource.close
              closed      <- session.resume()
            yield assertTrue(
              pending.isSuspended,
              rejectedSend.exists(
                _.causeOption
                  .exists(_.failures.exists(_.isInstanceOf[AgentError.InvalidConfiguration]))
              ),
              invalidExit.exists(
                _.causeOption
                  .exists(_.failures.exists(_.isInstanceOf[AgentError.InvalidConfiguration]))
              ),
              remaining.isSuspended,
              PendingActing.pendingCalls(remaining).map(_.map(_.callOrdinal)) == Right(Chunk(1)),
              beforeModel == 1,
              beforeExecution == Chunk(Json.Obj("value" -> Json.Num(0))),
              completed.reply.text == "done" && !completed.isSuspended,
              completed.history
                .filter(_.role == Role.Assistant)
                .headOption
                .contains(
                  pending.reply.withGenerateReason(GenerateReason.ToolCalls)
                ),
              completed.history.flatMap(_.toolResults).map(_.text) ==
                Chunk(
                  "local",
                  if completion == ResumeCompletion.Confirmed then "local" else "external"
                ),
              inputs == (if completion == ResumeCompletion.Confirmed then
                           Chunk(Json.Obj("value" -> Json.Num(0)), Json.Obj("value" -> Json.Num(1)))
                         else beforeExecution),
              next.history.filter(_.role == Role.User).map(_.text) == Chunk("work", "next"),
              count == 3,
              Chunk(first, deniedSend, invalid, partial, resumed, readyResume).distinct.size == 6,
              readyExit.exists(
                _.causeOption
                  .exists(_.failures.exists(_.isInstanceOf[AgentError.InvalidConfiguration]))
              ),
              closed == SubagentTaskSubmission.Closed
            )
        .map(_.foldLeft(assertTrue(true))(_ && _))
    ,
    test(
      "resumed cancellation joins cleanup and forwarded terminal events without poisoning the session"
    ):
      for
        supervisor    <- TaskSupervisor.open[AgentResult]
        root          <- SubagentSpawner.root(supervisor)
        requests      <- Ref.make(0)
        authorization <- Ref.make[ToolAuthorization](ToolAuthorization.Deferred("Wait"))
        entered       <- Promise.make[Nothing, Unit]
        cleaning      <- Promise.make[Nothing, Unit]
        release       <- Promise.make[Nothing, Unit]
        cleaned       <- Ref.make(false)
        resumedId     <- Promise.make[Nothing, SubagentTaskId]
        defect = new IllegalStateException("resumed cleanup marker")
        lookup = Tool.make(ToolDef("lookup", "Held resumed execution", Json.Obj()))(_ =>
          (entered.succeed(()) *> ZIO.never)
            .ensuring(cleaning.succeed(()) *> release.await *> cleaned.set(true) *> ZIO.die(defect))
        )
        configured <- factory(
          _ => ZIO.succeed(Toolkit(Map("lookup" -> lookup))),
          _ =>
            ZIO.succeed(
              LlmClient.mockEffectful((_, _, _) =>
                requests
                  .updateAndGet(_ + 1)
                  .map(count =>
                    if count == 1 then
                      TurnOutput.ToolCalls(
                        Msg.assistant(Chunk(ContentBlock.ToolUse("held", "lookup", Json.Obj())))
                      )
                    else TurnOutput.FinalText(Msg.assistant("done"))
                  )
              )
            ),
          ToolAuthorizer(_ => authorization.get)
        )
        resource <- accepted(SubagentSession.open(root, configured))
        session = resource.value
        pending <- run(session, "work")
        _       <- authorization.set(ToolAuthorization.Approved(Json.Obj()))
        relay = new Tool[Any, Json, String]:
          def definition: ToolDef = ToolDef("resume_child", "Host resume fixture", Json.Obj())
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
                ZIO.fail(AgentError.InvalidConfiguration("Active observer required"))
              case ToolInvocation.Active(_, _, _, destination) =>
                ZIO.scoped:
                  for
                    id <- submitted(session.resume(events = Some(destination)))
                    _  <- resumedId.succeed(id)
                    _  <- session.executions.await(id)
                  yield "joined"
        observer <- ReActAgent.make(
          toolkit = Toolkit(Map("resume_child" -> relay)),
          client = LlmClient.mockEffectful((history, _, _) =>
            ZIO.succeed(
              if history.lastOption.exists(_.role == Role.User) then
                TurnOutput.ToolCalls(
                  Msg.assistant(Chunk(ContentBlock.ToolUse("observe", "resume_child", Json.Obj())))
                )
              else TurnOutput.FinalText(Msg.assistant("observed"))
            )
          )
        )
        assertion <- ZIO.scoped:
          (for
            observing      <- observer.streamEvents(Msg.user("observe")).runCollect.forkScoped
            id             <- resumedId.await
            _              <- entered.await
            queued         <- submitted(session.resume())
            cancelledQueue <- session.executions.cancel(queued)
            stopping       <- session.executions.cancel(id).forkScoped
            _              <- cleaning.await
            waiting        <- stopping.poll
            _              <- release.succeed(())
            exit           <- stopping.join
            events         <- observing.join
            after          <- run(session, "fresh")
            released       <- cleaned.get
            count          <- requests.get
            childStarts = events.collect:
              case event: AgentEvent.AgentStartEvent if event.source.contains(id.value.toString) =>
                event
            childEnds = events.collect:
              case event: AgentEvent.AgentEndEvent if event.source.contains(id.value.toString) =>
                event
            pendingContext = pending.state match
              case AgentRunState.Suspended(checkpoint) => Some(checkpoint.context)
              case _                                   => None
          yield assertTrue(
            pending.isSuspended,
            cancelledQueue.exists(_.isInterrupted),
            waiting.isEmpty,
            exit.exists(value =>
              value.isInterrupted && value.causeOption.exists(_.defects == List(defect))
            ),
            released,
            childStarts.size == 1 && childEnds.size == 1,
            pendingContext.exists(_.replyId.nonEmpty),
            childStarts.headOption.map(_.replyId) == pendingContext.map(_.replyId),
            childEnds.headOption.map(_.replyId) == pendingContext.map(_.replyId),
            events.indexWhere(event => childEnds.contains(event)) < events.lastIndexWhere(
              _.isInstanceOf[AgentEvent.AgentEndEvent]
            ),
            after.history.flatMap(_.toolResults).map(_.state) == Chunk(ToolResultState.Error),
            after.history.exists(
              _.generationReason == GenerateReason.Interrupted
            ),
            after.reply.text == "done",
            count == 2
          )).ensuring(release.succeed(()).unit)
      yield assertion
    ,
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
