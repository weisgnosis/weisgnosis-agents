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
package com.weisgnosis.agents.core.agent

import com.weisgnosis.agents.core.client.{LlmClient, TurnOutput}
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.model.*
import com.weisgnosis.agents.core.tool.*
import zio.*
import zio.json.ast.Json
import zio.test.*

object LocalToolResultObserverSpec extends ZIOSpecDefault:
  private enum Failure:
    case Typed, CleanupDefect, Parallel
  private enum Cancellation:
    case Fiber, Cooperative, FiberCleanupDefect, CooperativeCleanupDefect

  private def call(name: String): ContentBlock.ToolUse =
    ContentBlock.ToolUse("same", name, Json.Obj())

  private def tool(name: String, effect: IO[AgentError, String]): Tool[Any, Json, String] =
    Tool.make(ToolDef(name, "Observe local completion", Json.Obj()))(_ => effect)

  def spec: Spec[TestEnvironment & Scope, Any] = suite("LocalToolResultObserverSpec")(
    test(
      "parallel local dispatch observations preserve approved calls, raw results and atomic processed history before scoped composition and direct return"
    ):
      ZIO.scoped:
        for
          dispatched <- Ref.make(Chunk.empty[(ContentBlock.ToolUse, Int)])
          captured   <- Ref.make(Chunk.empty[PersistedLocalToolObservation])
          closed     <- Ref.make(Set.empty[Int])
          ordered    <- Ref.make(Set.empty[Int])
          requests   <- Ref.make(0)
          executable = new Tool[Any, Json, String]:
            def definition: ToolDef =
              ToolDef("lookup", "Read a value", Json.Obj(), returnMode = ToolReturnMode.Direct)
            def execute(input: Json): UIO[String] = ZIO.succeed("raw")
            override def executeWithContext(input: Json, invocation: ToolInvocation): UIO[String] =
              (invocation match
                case ToolInvocation.Active(_, approved, ordinal, _) =>
                  dispatched.update(_ :+ (approved, ordinal))
                case ToolInvocation.Detached => ZIO.unit
              ).as("raw")
            def decodeInput(raw: Json): Either[String, Json] = Right(raw)
            def encodeOutput(output: String): String         = output
          toolkit <- ZIO.fromEither(Toolkit.empty.register(executable))
          client = LlmClient.mockEffectful((_, _, _) =>
            requests
              .update(_ + 1)
              .as(TurnOutput.ToolCalls(Msg.assistant(Chunk(call("lookup"), call("lookup")))))
          )
          first = LocalToolResultObserver(observation =>
            ZIO.acquireRelease(ZIO.unit)(_ =>
              closed.update(_ + observation.callOrdinal)
            ) *> captured.update(_ :+ observation)
          )
          second = LocalToolResultObserver(observation =>
            closed.get.flatMap(done =>
              if done.contains(observation.callOrdinal) then
                ordered.update(_ + observation.callOrdinal)
              else
                ZIO.fail(
                  AgentError.CompletionObservationError("Prior observer cleanup was not joined")
                )
            )
          )
          agent <- ReActAgent
            .builder()
            .client(client)
            .toolkit(toolkit)
            .toolAuthorizer(
              ToolAuthorizer(_ =>
                ZIO.succeed(ToolAuthorization.Approved(Json.Obj("value" -> Json.Str("approved"))))
              )
            )
            .toolResultProcessor(
              ToolResultProcessor(result =>
                ZIO.succeed(
                  ToolResultPayload(
                    Chunk(ContentBlock.Text("saved")),
                    result.metadata.updated("processed", Json.Bool(true))
                  )
                )
              )
            )
            .localToolResultObserver(first.andThen(second))
            .build
          result       <- agent.run(Msg.user("lookup"))
          observations <- captured.get
          calls        <- dispatched.get
          scopeOrder   <- ordered.get
          count        <- requests.get
        yield assertTrue(
          count == 1,
          result.reply.text.contains("saved"),
          observations.size == 2,
          observations.map(_.callOrdinal).sorted == Chunk(0, 1),
          observations
            .map(observation => (observation.call, observation.callOrdinal))
            .toSet == calls.toSet,
          observations.forall(_.call.input == Json.Obj("value" -> Json.Str("approved"))),
          observations.forall(_.original.text == "raw"),
          observations.forall(_.persisted.text == "saved"),
          observations.forall(observation =>
            observation.history.flatMap(_.toolResults).contains(observation.persisted)
          ),
          observations.forall(_.persisted.metadata.get("processed").contains(Json.Bool(true))),
          observations.map(_.context).distinct.size == 1,
          observations.forall(_.context.invocation.nonEmpty),
          scopeOrder == Set(0, 1),
          result.history.flatMap(_.toolResults).map(_.text) == Chunk("saved", "saved")
        )
    ,
    test(
      "required observer failures preserve the completed result, reconcile only unfinished occurrences and skip later observers without another model request"
    ):
      ZIO
        .foreach(Chunk.fromIterable(Failure.values)): mode =>
          ZIO.scoped:
            for
              executions     <- Ref.make(0)
              observed       <- Ref.make(0)
              later          <- Ref.make(0)
              requests       <- Ref.make(0)
              siblingEntered <- Promise.make[Nothing, Unit]
              siblingCleanup <- Promise.make[Nothing, Unit]
              releaseSibling <- Promise.make[Nothing, Unit]
              executable = Tool.make(ToolDef("lookup", "Observe completion", Json.Obj()))(input =>
                executions.update(
                  _ + 1
                ) *> (if mode == Failure.Parallel && input == Json.Obj("wait" -> Json.Bool(true))
                      then
                        ZIO.scoped(
                          ZIO.acquireRelease(ZIO.unit)(_ =>
                            siblingCleanup.succeed(()) *> releaseSibling.await
                          ) *> siblingEntered.succeed(()) *> ZIO.never
                        )
                      else ZIO.succeed("completed"))
              )
              toolkit <- ZIO.fromEither(Toolkit.empty.register(executable))
              secondCall =
                if mode == Failure.Parallel then
                  call("lookup").copy(input = Json.Obj("wait" -> Json.Bool(true)))
                else call("lookup")
              client = LlmClient.mockEffectful((_, _, _) =>
                requests
                  .update(_ + 1)
                  .as(TurnOutput.ToolCalls(Msg.assistant(Chunk(call("lookup"), secondCall))))
              )
              error  = AgentError.CompletionObservationError("Required local observation failed")
              defect = new IllegalStateException("Local observer cleanup defect")
              observer = LocalToolResultObserver(_ =>
                observed.update(_ + 1) *> (mode match
                  case Failure.Typed    => ZIO.fail(error)
                  case Failure.Parallel => siblingEntered.await *> ZIO.fail(error)
                  case Failure.CleanupDefect =>
                    ZIO.acquireRelease(ZIO.unit)(_ => ZIO.die(defect)) *> ZIO.fail(error))
              ).andThen(LocalToolResultObserver(_ => later.update(_ + 1)))
              agent <- ReActAgent.make(
                config = AgentConfig(toolExecution =
                  if mode == Failure.Parallel then ToolExecution.Parallel
                  else ToolExecution.Sequential
                ),
                toolkit = toolkit,
                client = client,
                localToolResultObserver = observer
              )
              handle <- agent.prepareRun(Msg.user("lookup"))
              worker <- ZIO.uninterruptibleMask: restore =>
                for
                  fiber <- restore(handle.run).forkScoped
                  _     <- ZIO.addFinalizer(releaseSibling.succeed(()).unit)
                yield fiber
              before <-
                if mode == Failure.Parallel then siblingCleanup.await *> worker.poll else ZIO.none
              _                <- releaseSibling.succeed(())
              failed           <- worker.await
              termination      <- handle.termination
              status           <- handle.status
              history          <- agent.history
              executionCount   <- executions.get
              observationCount <- observed.get
              laterCount       <- later.get
              requestCount     <- requests.get
            yield assertTrue(
              failed.causeOption.exists(_.failures == List(error)),
              termination == failed,
              status == PreparedInvocation.Status.Failed,
              failed.causeOption.exists(
                _.defects == (if mode == Failure.CleanupDefect then List(defect) else List.empty)
              ),
              // Parallel failure preserves owned sibling cancellation without classifying the owner
              // as interrupted; exact failure/defect assertions and absent recovery remain required.
              failed.isInterrupted == (mode == Failure.Parallel),
              before.isEmpty,
              executionCount == (if mode == Failure.Parallel then 2 else 1),
              observationCount == 1,
              laterCount == 0,
              requestCount == 1,
              history.flatMap(_.toolResults).map(_.state) == Chunk(
                ToolResultState.Success,
                ToolResultState.Error
              ),
              history.flatMap(_.toolResults).headOption.exists(_.text == "completed"),
              !history.exists(_.generationReason == GenerateReason.Interrupted)
            )
        .map(_.foldLeft(assertTrue(true))(_ && _))
    ,
    test(
      "interruption joins observer cleanup and preserves completed results; denied, invalid, supplied, inline and recovery observations do not invoke the port"
    ):
      for
        cancellations <- ZIO.foreach(Chunk.fromIterable(Cancellation.values)): mode =>
          ZIO.scoped:
            for
              executions <- Ref.make(0)
              toolkit <- ZIO.fromEither(
                Toolkit.empty.register(tool("lookup", executions.update(_ + 1).as("completed")))
              )
              client <- LlmClient.mock(
                Chunk(TurnOutput.ToolCalls(Msg.assistant(Chunk(call("lookup"), call("lookup")))))
              )
              entered <- Promise.make[Nothing, Unit]
              cleanup <- Promise.make[Nothing, Unit]
              release <- Promise.make[Nothing, Unit]
              defect = new IllegalStateException("Interrupted local observer cleanup defect")
              observer = LocalToolResultObserver(_ =>
                ZIO.acquireRelease(ZIO.unit)(_ =>
                  cleanup.succeed(()) *> release.await *> (mode match
                    case Cancellation.FiberCleanupDefect | Cancellation.CooperativeCleanupDefect =>
                      ZIO.die(defect)
                    case _ => ZIO.unit)
                ) *> entered.succeed(()) *> ZIO.never
              )
              agent <- ReActAgent.make(
                config = AgentConfig(toolExecution = ToolExecution.Sequential),
                toolkit = toolkit,
                client = client,
                localToolResultObserver = observer
              )
              worker <- ZIO.uninterruptibleMask: restore =>
                for
                  fiber <- restore(agent.run(Msg.user("lookup"))).forkScoped
                  _     <- ZIO.addFinalizer(release.succeed(()).unit)
                yield fiber
              _ <- entered.await
              cancelling <- ZIO.uninterruptibleMask: restore =>
                for
                  fiber <- restore(mode match
                    case Cancellation.Fiber | Cancellation.FiberCleanupDefect => worker.interrupt
                    case Cancellation.Cooperative | Cancellation.CooperativeCleanupDefect =>
                      agent.interrupt *> worker.await).forkScoped
                  _ <- ZIO.addFinalizer(release.succeed(()).unit)
                yield fiber
              _        <- cleanup.await
              before   <- cancelling.poll
              _        <- release.succeed(())
              exit     <- cancelling.join
              history  <- agent.history
              executed <- executions.get
            yield assertTrue(
              before.isEmpty,
              mode match
                case Cancellation.Cooperative =>
                  exit match
                    case Exit.Success(result) => result.state == AgentRunState.Interrupted
                    case _                    => false
                case Cancellation.Fiber =>
                  exit.isInterrupted && exit.causeOption.exists(cause =>
                    cause.failures.isEmpty && cause.defects.isEmpty
                  )
                case _ =>
                  exit.isInterrupted && exit.causeOption.exists(cause =>
                    cause.failures.isEmpty && cause.defects == List(defect)
                  )
              ,
              executed == 1,
              history.flatMap(_.toolResults).map(_.state) == Chunk(
                ToolResultState.Success,
                ToolResultState.Error
              ),
              history.flatMap(_.toolResults).headOption.exists(_.text == "completed"),
              history.count(_.generationReason == GenerateReason.Interrupted) == 1
            )
        excluded <- ZIO.scoped:
          for
            calls <- Ref.make(Chunk.empty[PersistedLocalToolObservation])
            external <- ZIO.fromEither(
              ToolBinding.external[Json](ToolDef("external", "Host execution", Json.Obj()))
            )
            toolkit <- ZIO.fromEither(
              Toolkit.empty
                .register(tool("denied", ZIO.succeed("not dispatched")))
                .flatMap(
                  _.register(
                    Tool.make(
                      ToolDef(
                        "validated",
                        "Checked input",
                        Json.Obj(
                          "type" -> Json.Str("object"),
                          "properties" -> Json.Obj(
                            "value" -> Json.Obj("type" -> Json.Str("integer"))
                          ),
                          "required" -> Json.Arr(Json.Str("value"))
                        )
                      )
                    )(_ => ZIO.succeed("not dispatched"))
                  )
                )
                .flatMap(
                  _.register(
                    tool(
                      "failed",
                      ZIO.fail(AgentError.ToolExecutionError("failed", "execution failed"))
                    )
                  )
                )
                .flatMap(_.register(external))
            )
            inlineCall = call("server").withServerTool(true)
            inlineResult = ContentBlock.ToolResult
              .text("same", "server", "inline")
              .copy(metadata = Map("serverTool" -> Json.Bool(true)))
            invalid = call("validated").copy(input =
              Json.Obj("value" -> Json.Str("not an integer"))
            )
            client <- LlmClient.mock(
              Chunk(
                TurnOutput.ToolCalls(
                  Msg.assistant(
                    Chunk(
                      call("denied"),
                      invalid,
                      call("failed"),
                      call("external"),
                      inlineCall,
                      inlineResult
                    )
                  )
                ),
                TurnOutput.FinalText(Msg.assistant("done"))
              )
            )
            agent <- ReActAgent.make(
              toolkit = toolkit,
              client = client,
              toolAuthorizer = ToolAuthorizer(request =>
                ZIO.succeed(
                  if request.call.name == "denied" then ToolAuthorization.Denied("policy")
                  else ToolAuthorization.Approved(request.call.input)
                )
              ),
              localToolResultObserver =
                LocalToolResultObserver(observation => calls.update(_ :+ observation))
            )
            suspended <- agent.run(Msg.user("mixed"))
            resumed   <- agent.resume(Chunk(SuppliedToolResult(3, ToolExecutionResult("supplied"))))
            observed  <- calls.get
          yield assertTrue(
            suspended.isSuspended,
            resumed.reply.text == "done",
            observed.map(_.call.name) == Chunk("failed"),
            observed.forall(_.original.state == ToolResultState.Error),
            observed.forall(observation =>
              observation.history.flatMap(_.toolResults).contains(observation.persisted)
            )
          )
      yield cancellations.foldLeft(excluded)(_ && _)
  ) @@ TestAspect.timeout(30.seconds)
