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
import com.weisgnosis.agents.core.event.AgentEvent
import com.weisgnosis.agents.core.model.{ContentBlock, Msg, ToolResultState}
import com.weisgnosis.agents.core.tool.*
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

object ReActAgentExternalToolSpec extends ZIOSpecDefault:
  final private case class Arguments(value: String) derives JsonCodec
  private enum Admission:
    case Valid, ValidRewrite, Invalid, SchemaInvalid, Inactive, InvalidRewrite,
      SchemaInvalidRewrite,
      Denied, Deferred

  private val definition: ToolDef        = ToolDef("external", "External execution", Json.Obj())
  private def input(value: String): Json = Json.Obj("value" -> Json.Str(value))
  private def call(name: String, value: String = "original"): ContentBlock.ToolUse =
    ContentBlock.ToolUse("same", name, input(value))

  private def pending(result: AgentResult): IO[AgentError, PendingActing] = result.state match
    case AgentRunState.Suspended(value) => ZIO.succeed(value)
    case _ => ZIO.fail(AgentError.InvalidConfiguration("Expected suspended external work"))

  private def collected(
      updates: Chunk[AgentRunUpdate[AgentResult]]
  ): IO[AgentError, AgentResult] =
    ZIO
      .fromOption(updates.collect { case AgentRunUpdate.Result(value) => value }.lastOption)
      .orElseFail(AgentError.InvalidConfiguration("Missing runtime result"))

  def spec: Spec[TestEnvironment & Scope, Any] = suite("ReActAgentExternalToolSpec")(
    test("external admission validates input and groups before asking or awaiting execution"):
      ZIO
        .foreach(Chunk.fromIterable(Admission.values)): mode =>
          for
            schema <- ZIO.fromEither(
              """{"type":"object","properties":{"value":{"enum":["original","edited"]}},"required":["value"],"additionalProperties":false}"""
                .fromJson[Json]
            )
            binding <- ZIO.fromEither(
              ToolBinding.external[Arguments](definition.copy(parametersSchema = schema))
            )
            groups <- ZIO.fromEither(
              ToolGroups.empty.register(
                ToolGroup(
                  "external-group",
                  "External",
                  ToolGroupActivation.Inactive,
                  ToolGroupScope.External,
                  Set("external")
                )
              )
            )
            asks  <- Ref.make(0)
            calls <- Ref.make(0)
            authorizer = ToolAuthorizer(request =>
              asks
                .update(_ + 1)
                .as(mode match
                  case Admission.InvalidRewrite =>
                    ToolAuthorization.Approved(Json.Obj("value" -> Json.Num(1)))
                  case Admission.SchemaInvalidRewrite =>
                    ToolAuthorization.Approved(input("rejected"))
                  case Admission.ValidRewrite => ToolAuthorization.Approved(input("edited"))
                  case Admission.Inactive     => ToolAuthorization.Deferred("Inactive must not ask")
                  case Admission.Denied       => ToolAuthorization.Denied("Host denied")
                  case Admission.Deferred => ToolAuthorization.Deferred("Host approval required")
                  case _                  => ToolAuthorization.Approved(request.call.input))
            )
            client = LlmClient.mockEffectful((_, _, _) =>
              calls
                .getAndUpdate(_ + 1)
                .map(index =>
                  if index == 0 then
                    TurnOutput.ToolCalls(
                      Msg.assistant(
                        Chunk(
                          if mode == Admission.Invalid then
                            call("external").copy(input = Json.Obj("value" -> Json.Num(1)))
                          else if mode == Admission.SchemaInvalid then call("external", "rejected")
                          else call("external")
                        )
                      )
                    )
                  else TurnOutput.FinalText(Msg.assistant("done"))
                )
            )
            agent <- ReActAgent.make(
              toolkit = Toolkit.single(binding),
              client = client,
              toolAuthorizer = authorizer,
              toolGroups = if mode == Admission.Inactive then Some(groups) else None
            )
            updates   <- agent.streamWithResult(Msg.user("execute")).runCollect
            result    <- collected(updates)
            decisions <- asks.get
            requests  <- calls.get
            results = result.history.flatMap(_.toolResults)
            events  = updates.collect { case AgentRunUpdate.Event(value) => value }
            waiting =
              mode == Admission.Valid || mode == Admission.ValidRewrite || mode == Admission.Deferred
            expectedInput = input(if mode == Admission.ValidRewrite then "edited" else "original")
            rewritten <- mode match
              case Admission.ValidRewrite =>
                for
                  fresh <- ReActAgent.make(
                    toolkit = Toolkit.single(binding),
                    client = client,
                    toolAuthorizer = authorizer
                  )
                  _ <- fresh.restore(result)
                  resumed <- fresh.resume(
                    Chunk(SuppliedToolResult(0, ToolExecutionResult("edited result")))
                  )
                yield Some(resumed)
              case _ => ZIO.none
            finalDecisions <- asks.get
            finalRequests  <- calls.get
          yield assertTrue(
            if waiting then result.state.isInstanceOf[AgentRunState.Suspended]
            else result.state == AgentRunState.Completed,
            requests == (if waiting then 1 else 2),
            decisions == (if mode == Admission.Invalid || mode == Admission.SchemaInvalid || mode == Admission.Inactive
                          then 0
                          else 1),
            if waiting then results.isEmpty
            else
              results.map(result => (result.id, result.name, result.state)) == Chunk(
                (
                  "same",
                  Some("external"),
                  mode match
                    case Admission.Invalid | Admission.SchemaInvalid | Admission.Inactive =>
                      ToolResultState.Error
                    case _ => ToolResultState.Denied
                )
              )
            ,
            if mode == Admission.Valid || mode == Admission.ValidRewrite then
              result.state match
                case AgentRunState.Suspended(value) =>
                  value.externalRequests == Chunk(
                    PendingExternalCall(0, expectedInput)
                  ) && value.permissionRequests.isEmpty && result.reply.toolUses
                    .map(_.input) == Chunk(expectedInput)
                case _ => false
            else true,
            if mode == Admission.Deferred then
              result.state match
                case AgentRunState.Suspended(value) =>
                  value.externalRequests.isEmpty && value.permissionRequests
                    .map(_.callOrdinal) == Chunk(0)
                case _ => false
            else true,
            !waiting || !events.exists {
              case _: AgentEvent.ToolResultStartEvent | _: AgentEvent.ToolResultEndEvent => true
              case _                                                                     => false
            },
            if mode == Admission.ValidRewrite then
              rewritten.exists(value =>
                value.state == AgentRunState.Completed &&
                  value.history.flatMap(_.toolUses).map(_.input) == Chunk(expectedInput) &&
                  value.history.flatMap(_.toolResults).map(_.text) == Chunk("edited result")
              ) && finalDecisions == 1 && finalRequests == 2
            else rewritten.isEmpty
          )
        .map(_.foldLeft(assertTrue(true))(_ && _))
    ,
    test("trusted already-issued external waits retain pre-validator input and accept completion"):
      for
        schema <- ZIO.fromEither(
          """{"type":"object","properties":{"value":{"enum":["original"]}},"required":["value"]}"""
            .fromJson[Json]
        )
        binding <- ZIO.fromEither(
          ToolBinding.external[Arguments](definition.copy(parametersSchema = schema))
        )
        asks     <- Ref.make(0)
        requests <- Ref.make(0)
        client = LlmClient.mockEffectful((_, _, _) =>
          requests
            .getAndUpdate(_ + 1)
            .map(index =>
              if index == 0 then TurnOutput.ToolCalls(Msg.assistant(Chunk(call("external"))))
              else TurnOutput.FinalText(Msg.assistant("done"))
            )
        )
        authorizer = ToolAuthorizer(request =>
          asks.update(_ + 1).as(ToolAuthorization.Approved(request.call.input))
        )
        initial <- ReActAgent.make(
          toolkit = Toolkit.single(binding),
          client = client,
          toolAuthorizer = authorizer
        )
        issued <- initial.run(Msg.user("execute"))
        saved  <- pending(issued)
        // Represents authenticated data issued by a version without schema admission.
        legacyInput = input("legacy")
        legacyReply <- ZIO.fromEither(issued.reply.withContent(Chunk(call("external", "legacy"))))
        legacy = issued.copy(
          reply = legacyReply,
          history = issued.history.updated(saved.assistantPosition, legacyReply),
          state = AgentRunState.Suspended(
            saved.copy(externalRequests = Chunk(PendingExternalCall(0, legacyInput)))
          )
        )
        fresh <- ReActAgent.make(
          toolkit = Toolkit.single(binding),
          client = client,
          toolAuthorizer = authorizer
        )
        _         <- fresh.restore(legacy)
        waiting   <- fresh.resume()
        unchanged <- pending(waiting)
        completed <- fresh.resume(
          Chunk(SuppliedToolResult(0, ToolExecutionResult("legacy result")))
        )
        decisions     <- asks.get
        modelRequests <- requests.get
      yield assertTrue(
        unchanged.externalRequests == Chunk(PendingExternalCall(0, legacyInput)),
        completed.state == AgentRunState.Completed,
        completed.history.flatMap(_.toolUses).map(_.input) == Chunk(legacyInput),
        completed.history.flatMap(_.toolResults).map(_.text) == Chunk("legacy result"),
        decisions == 1,
        modelRequests == 2
      )
    ,
    test("mixed waits preserve occurrence identity across partial resume and binding restoration"):
      ZIO
        .foreach(Chunk(ToolExecution.Sequential, ToolExecution.Parallel)): execution =>
          for
            binding    <- ZIO.fromEither(ToolBinding.external[Arguments](definition))
            executions <- Ref.make(0)
            requests   <- Ref.make(0)
            approvals  <- Ref.make(Chunk.empty[String])
            local = Tool.make(ToolDef("local", "Local", Json.Obj()))(_ =>
              executions.update(_ + 1).as("local result")
            )
            approve = Tool.make(ToolDef("approval", "Approval", Json.Obj()))(_ =>
              ZIO.succeed("must not run")
            )
            toolkit = Toolkit.single(binding).updated(local).updated(approve)
            authorizer = ToolAuthorizer(request =>
              approvals
                .update(_ :+ request.call.name)
                .as(
                  if request.call.name == "approval" then ToolAuthorization.Deferred("approval")
                  else ToolAuthorization.Approved(request.call.input)
                )
            )
            client = LlmClient.mockEffectful((_, _, _) =>
              requests
                .getAndUpdate(_ + 1)
                .map(index =>
                  if index == 0 then
                    TurnOutput.ToolCalls(
                      Msg.assistant(
                        Chunk(
                          call("local"),
                          call("external", "one"),
                          call("external", "two"),
                          call("approval")
                        )
                      )
                    )
                  else TurnOutput.FinalText(Msg.assistant("complete"))
                )
            )
            agent <- ReActAgent.make(
              config = AgentConfig(toolExecution = execution),
              toolkit = toolkit,
              client = client,
              toolAuthorizer = authorizer
            )
            first        <- agent.run(Msg.user("mixed"))
            firstPending <- pending(first)
            empty        <- agent.resume()
            afterEmpty   <- pending(empty)
            partial <- agent.resume(Chunk(SuppliedToolResult(1, ToolExecutionResult("one result"))))
            partialPending        <- pending(partial)
            beforeFinalRequests   <- requests.get
            beforeFinalExecutions <- executions.get
            beforeFinalApprovals  <- approvals.get
            localReplacement = toolkit.updated(
              Tool.make(definition)(_ => ZIO.succeed("local replacement"))
            )
            wrong <- ReActAgent.make(
              toolkit = localReplacement,
              client = client,
              toolAuthorizer = authorizer
            )
            wrongRestore        <- wrong.restore(partial).exit
            externalReplacement <- ZIO.fromEither(ToolBinding.external[Arguments](local.definition))
            wrongLocal <- ReActAgent.make(
              toolkit = toolkit.updated(externalReplacement),
              client = client,
              toolAuthorizer = authorizer
            )
            wrongLocalRestore <- wrongLocal.restore(partial).exit
            fresh <- ReActAgent.make(
              config = AgentConfig(toolExecution = execution),
              toolkit = toolkit,
              client = client,
              toolAuthorizer = authorizer
            )
            _ <- fresh.restore(partial)
            finalResult <- fresh.resume(
              Chunk(
                SuppliedToolResult(2, ToolExecutionResult("two result")),
                SuppliedToolResult(3, ToolExecutionResult("approved externally"))
              )
            )
            finalExecutions <- executions.get
            finalRequests   <- requests.get
          yield assertTrue(
            first.toJson.fromJson[AgentResult] == Right(first),
            firstPending.externalTools == Chunk("external"),
            firstPending.externalRequests == Chunk(
              PendingExternalCall(1, input("one")),
              PendingExternalCall(2, input("two"))
            ),
            firstPending.permissionRequests.map(_.callOrdinal) == Chunk(3),
            PendingActing.completed(firstPending) == Chunk(CompletedToolCall(0, 2)),
            afterEmpty.externalRequests == firstPending.externalRequests,
            partialPending.externalRequests == Chunk(PendingExternalCall(2, input("two"))),
            partialPending.permissionRequests.map(_.callOrdinal) == Chunk(3),
            PendingActing.completed(partialPending).map(_.callOrdinal).toSet == Set(0, 1),
            beforeFinalRequests == 1,
            beforeFinalExecutions == 1,
            beforeFinalApprovals.count(_ == "external") == 2,
            wrongRestore.isFailure,
            wrongLocalRestore.isFailure,
            finalResult.state == AgentRunState.Completed,
            finalResult.history.flatMap(_.toolResults).map(_.text) == Chunk(
              "local result",
              "one result",
              "two result",
              "approved externally"
            ),
            finalExecutions == 1,
            finalRequests == 2
          )
        .map(_.foldLeft(assertTrue(true))(_ && _))
    ,
    test(
      "cancellation closes external proposals truthfully after joined local and processor cleanup"
    ):
      for
        binding        <- ZIO.fromEither(ToolBinding.external[Arguments](definition))
        started        <- Promise.make[Nothing, Unit]
        cleanupStarted <- Promise.make[Nothing, Unit]
        releaseCleanup <- Promise.make[Nothing, Unit]
        cleaned        <- Ref.make(false)
        processed      <- Ref.make(false)
        local = Tool.make(ToolDef("local", "Local", Json.Obj()))(_ => ZIO.succeed("preserved"))
        slow = Tool.make(ToolDef("slow", "Slow", Json.Obj()))(_ =>
          ZIO.scoped(
            ZIO.acquireRelease(started.succeed(()))(_ =>
              cleanupStarted.succeed(()) *> releaseCleanup.await *> cleaned.set(true)
            ) *> ZIO.never
          )
        )
        processor = new ToolResultProcessor:
          def process(result: ContentBlock.ToolResult): ZIO[Scope, AgentError, ToolResultPayload] =
            ZIO
              .acquireRelease(ZIO.unit)(_ => processed.set(true))
              .as(ToolResultPayload.fromResult(result))
        client = LlmClient.mockFunction((_, _) =>
          TurnOutput.ToolCalls(Msg.assistant(Chunk(call("local"), call("external"), call("slow"))))
        )
        agent <- ReActAgent.make(
          config = AgentConfig(toolExecution = ToolExecution.Sequential),
          toolkit = Toolkit.single(binding).updated(local).updated(slow),
          client = client,
          toolResultProcessor = processor
        )
        fiber           <- agent.streamWithResult(Msg.user("cancel")).runCollect.forkScoped
        _               <- started.await
        _               <- agent.interrupt
        _               <- cleanupStarted.await
        held            <- fiber.poll
        _               <- releaseCleanup.succeed(())
        updates         <- fiber.join
        result          <- collected(updates)
        joined          <- cleaned.get
        processorJoined <- processed.get
        late <- agent.resume(Chunk(SuppliedToolResult(1, ToolExecutionResult("late")))).exit
        observations = result.history.flatMap(_.toolResults)
        events       = updates.collect { case AgentRunUpdate.Event(value) => value }
      yield assertTrue(
        held.isEmpty,
        joined,
        processorJoined,
        result.isInterrupted,
        observations.map(_.state) == Chunk(
          ToolResultState.Success,
          ToolResultState.Error,
          ToolResultState.Error
        ),
        observations.headOption.exists(_.text == "preserved"),
        observations.lift(1).exists(_.text.contains("external execution status is unknown")),
        result.history.count(_.generateReason.contains("interrupted")) == 1,
        !events.exists {
          case value: AgentEvent.ToolResultStartEvent => value.name == "external"
          case value: AgentEvent.ToolResultEndEvent   => value.name == "external"
          case _                                      => false
        },
        late.isFailure
      )
  ) @@ TestAspect.timeout(20.seconds)
