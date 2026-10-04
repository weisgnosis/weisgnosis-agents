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
package com.weisgnosis.agents.harness.permission

import com.weisgnosis.agents.core.agent.*
import com.weisgnosis.agents.core.client.{LlmClient, TurnOutput}
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.model.*
import com.weisgnosis.agents.core.tool.*
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

object ConfirmedBatchAuthorizerSpec extends ZIOSpecDefault:
  private val original: Json             = Json.Obj("value" -> Json.Str("original"))
  private val changed: Json              = Json.Obj("value" -> Json.Str("confirmed"))
  private val call: ContentBlock.ToolUse = ContentBlock.ToolUse("same", "read", original)
  private val pause: PostReasoningHook =
    PostReasoningHook((_, _) => ZIO.succeed(ReasoningDecision.Suspend("host review")))

  private def checkpoint(message: Msg, toolkit: Toolkit): ZIO[Scope, AgentError, AgentResult] =
    for
      client <- LlmClient.mock(Chunk(TurnOutput.ToolCalls(message)))
      agent  <- ReActAgent.make(client = client, toolkit = toolkit, postReasoningHook = pause)
      result <- agent.run(Msg.user("review tools"))
    yield result

  private def readTool(run: Json => UIO[String]): Tool[Any, Json, String] =
    Tool.make(ToolDef("read", "Read", Json.Obj(), isReadOnly = true))(run)

  private def pending(result: AgentResult): IO[AgentError, PendingActing] = result.state match
    case AgentRunState.Suspended(value) => ZIO.succeed(value)
    case _ => ZIO.fail(AgentError.InvalidConfiguration("Expected a suspended checkpoint"))

  private enum Guard:
    case Modified, Prepared, FixedEdit, SignedEdit, PolicyDeny, HeldDeny, GateDeny, BatchDeny

  def spec: Spec[TestEnvironment & Scope, Any] = suite("ConfirmedBatchAuthorizer")(
    test("restored sequential and parallel batches bind repeated calls by original occurrence"):
      ZIO
        .foreach(Chunk.fromArray(ToolExecution.values)): execution =>
          for
            inputs      <- Ref.make(Chunk.empty[Json])
            policyReads <- Ref.make(0)
            gatesClosed <- Ref.make(0)
            accesses    <- Ref.make(Chunk.empty[Option[AdmittedToolAccess]])
            tools  = Toolkit(Map("read" -> readTool(input => inputs.update(_ :+ input).as("ok"))))
            inline = call.copy(name = "remote").withServerTool(true)
            saved <- checkpoint(
              Msg.assistant(
                Chunk(
                  call,
                  inline,
                  ContentBlock.ToolResult.text("same", "remote", "server"),
                  call,
                  call
                )
              ),
              tools
            )
            roundTrip <- ZIO.fromEither(saved.toJson.fromJson[AgentResult])
            firstRule = PermissionRule("future-first", PermissionBehavior.Allow)
            lastRule  = PermissionRule("future-last", PermissionBehavior.Ask)
            lastInput = Json.Obj("value" -> Json.Str("last"))
            decisions = Chunk(
              ConfirmedToolCall(3, ConfirmResult(true, Some(lastInput), Chunk(lastRule))),
              ConfirmedToolCall(
                2,
                ConfirmResult(
                  false,
                  newRules = Chunk(PermissionRule("*", PermissionBehavior.Deny)),
                  reason = Some("declined")
                )
              ),
              ConfirmedToolCall(0, ConfirmResult(true, Some(changed), Chunk(firstRule)))
            )
            decoded <- ZIO.fromEither(decisions.toJson.fromJson[Chunk[ConfirmedToolCall]])
            gate = new PermissionGate:
              def evaluate(
                  name: String,
                  input: Json
              ): ZIO[Scope, AgentError.PermissionDenied, Option[PermissionBehavior]] =
                evaluate(name, input, None)
              override def evaluate(name: String, input: Json, access: Option[AdmittedToolAccess])
                  : ZIO[Scope, AgentError.PermissionDenied, Option[PermissionBehavior]] =
                ZIO.addFinalizer(gatesClosed.update(_ + 1)) *> accesses
                  .update(_ :+ access)
                  .as(Some(PermissionBehavior.Ask))
            authorizer <- ConfirmedBatchAuthorizer.make(
              roundTrip,
              decoded,
              policyReads.update(_ + 1).as(PermissionEngine()),
              gate,
              PermissionGate.abstain
            )
            inlineRejected <- ConfirmedBatchAuthorizer
              .make(
                roundTrip,
                decoded :+ ConfirmedToolCall(1, ConfirmResult(true)),
                policyReads.update(_ + 1).as(PermissionEngine()),
                gate,
                PermissionGate.abstain
              )
              .exit
            before      <- inputs.get
            readsBefore <- policyReads.get
            requests    <- Ref.make(0)
            client = LlmClient.mockEffectful((_, _, _) =>
              requests.update(_ + 1) *> inputs.get.flatMap(done =>
                if done.toSet == Set(changed, lastInput) then
                  ZIO.succeed(TurnOutput.FinalText(Msg.assistant("done")))
                else
                  ZIO.fail(
                    AgentError.InvalidConfiguration("Model requested before confirmed execution")
                  )
              )
            )
            restored <- ReActAgent.make(
              config = AgentConfig(toolExecution = execution),
              client = client,
              toolkit = tools,
              toolAuthorizer = authorizer
            )
            _          <- restored.restore(roundTrip)
            result     <- restored.resume()
            repeated   <- restored.resume().exit
            done       <- inputs.get
            modelCalls <- requests.get
            reads      <- policyReads.get
            closed     <- gatesClosed.get
            admitted   <- accesses.get
          yield assertTrue(
            before.isEmpty,
            readsBefore == 0,
            inlineRejected.isFailure,
            done.size == 2,
            done.toSet == Set(changed, lastInput),
            authorizer.ruleUpdates == Chunk(firstRule, lastRule),
            reads == 2,
            closed == 2,
            admitted == Chunk.fill(2)(Some(AdmittedToolAccess.ReadOnly)),
            modelCalls == 1,
            result.reply.text == "done",
            repeated.isFailure,
            result.history.count(_.role == Role.Tool) == 3,
            result.history.flatMap(_.toolResults).count(_.state == ToolResultState.Denied) == 1,
            result.history.flatMap(_.toolUses).filter(_.name == "read").map(_.input) == Chunk(
              changed,
              original,
              lastInput
            )
          )
        .map(_.foldLeft(assertTrue(true))(_ && _))
    ,
    test(
      "confirmed input retains fresh fixed arguments and cannot override signed or current denial guards"
    ):
      ZIO
        .foreach(Chunk.fromArray(Guard.values)): guard =>
          for
            executed <- Ref.make(Chunk.empty[Json])
            raw = readTool(input => executed.update(_ :+ input).as("ok"))
            fixed <- ZIO.fromEither(
              ToolFixedArguments.make(Json.Obj("host" -> Json.Str("trusted")))
            )
            bound <- ZIO.fromEither(Tool.withFixedArguments(raw, fixed))
            tools = Toolkit(
              Map(
                "read" -> (if guard == Guard.Prepared || guard == Guard.FixedEdit then bound
                           else raw)
              )
            )
            selected =
              if guard == Guard.SignedEdit then
                call.copy(metadata = Map("thoughtSignature" -> Json.Str("opaque")))
              else call
            saved   <- checkpoint(Msg.assistant(Chunk(selected)), tools)
            current <- Ref.make(PermissionEngine())
            entered <- Promise.make[Nothing, Unit]
            release <- Promise.make[Nothing, Unit]
            confirmation = ConfirmResult(
              true,
              if guard == Guard.Prepared then None else Some(changed),
              if guard == Guard.BatchDeny then
                Chunk(PermissionRule("read", PermissionBehavior.Deny))
              else Chunk.empty
            )
            authorizer <- ConfirmedBatchAuthorizer.make(
              saved,
              Chunk(ConfirmedToolCall(0, confirmation)),
              current.get,
              PermissionGate((_, _) =>
                (entered.succeed(()) *> release.await).when(guard == Guard.HeldDeny) *>
                  ZIO.some(
                    if guard == Guard.GateDeny then PermissionBehavior.Deny
                    else PermissionBehavior.Ask
                  )
              ),
              PermissionGate.abstain
            )
            _ <- current
              .set(PermissionEngine(Chunk(PermissionRule("read", PermissionBehavior.Deny))))
              .when(guard == Guard.PolicyDeny)
            client <- LlmClient.mock(Chunk(TurnOutput.FinalText(Msg.assistant("done"))))
            restored <- ReActAgent
              .make(client = client, toolkit = tools, toolAuthorizer = authorizer)
            _ <- restored.restore(saved)
            result <- (for
              fiber <- restored.resume().forkScoped
              _ <- (entered.await *> current.set(
                PermissionEngine(Chunk(PermissionRule("read", PermissionBehavior.Deny)))
              ) *>
                release.succeed(())).when(guard == Guard.HeldDeny)
              value <- fiber.join
            yield value).ensuring(release.succeed(()).unit)
            done <- executed.get
            expected =
              if guard == Guard.Prepared then
                Json.Obj("value" -> Json.Str("original"), "host" -> Json.Str("trusted"))
              else changed
            permitted = guard == Guard.Modified || guard == Guard.Prepared
          yield assertTrue(
            done == (if permitted then Chunk(expected) else Chunk.empty),
            result.history.flatMap(_.toolResults).map(_.state) == Chunk(
              if permitted then ToolResultState.Success else ToolResultState.Denied
            ),
            result.history.flatMap(_.toolUses).map(_.input) == Chunk(
              if permitted then expected else selected.input
            )
          )
        .map(_.foldLeft(assertTrue(true))(_ && _))
    ,
    test(
      "construction rejects incomplete batches and unrelated or legacy authorization requests fail closed"
    ):
      for
        reads <- Ref.make(0)
        saved <- checkpoint(Msg.assistant(Chunk(call)), Toolkit())
        value <- pending(saved)
        valid  = Chunk(ConfirmedToolCall(0, ConfirmResult(true)))
        policy = reads.update(_ + 1).as(PermissionEngine())
        invalid <- ZIO.foreach(
          Chunk(
            Chunk.empty[ConfirmedToolCall],
            valid ++ valid,
            Chunk(ConfirmedToolCall(-1, ConfirmResult(true))),
            Chunk(ConfirmedToolCall(1, ConfirmResult(true)))
          )
        )(decisions =>
          ConfirmedBatchAuthorizer
            .make(saved, decisions, policy, PermissionGate.abstain, PermissionGate.abstain)
            .exit
        )
        invalidState <- ConfirmedBatchAuthorizer
          .make(
            saved.copy(state = AgentRunState.Completed),
            valid,
            policy,
            PermissionGate.abstain,
            PermissionGate.abstain
          )
          .exit
        authorizer <- ConfirmedBatchAuthorizer.make(
          saved,
          valid,
          policy,
          PermissionGate.abstain,
          PermissionGate.abstain
        )
        request = ToolAuthorizationRequest("fresh", value.context, call, Some(0))
        rejected <- ZIO.foreach(
          Chunk(
            request.copy(callOrdinal = None),
            request.copy(callOrdinal = Some(-1)),
            request.copy(callOrdinal = Some(1)),
            request.copy(context = value.context.copy(requestId = "other")),
            request.copy(call = call.copy(id = "other")),
            request.copy(call = call.copy(name = "other"))
          )
        )(authorizer.authorize(_))
        supplied <- ZIO.fromEither(
          PendingActing.withResults(
            saved,
            Chunk(SuppliedToolResult(0, ToolExecutionResult("already executed")))
          )
        )
        suppliedOnly <- ConfirmedBatchAuthorizer.make(supplied, Chunk.empty, policy)
        extra        <- ConfirmedBatchAuthorizer.make(supplied, valid, policy).exit
        replay       <- suppliedOnly.authorize(request)
        untouched    <- reads.get
        allowed      <- authorizer.authorize(request)
      yield assertTrue(
        invalid.forall(_.isFailure),
        invalidState.isFailure,
        extra.isFailure,
        replay.isInstanceOf[ToolAuthorization.Denied],
        untouched == 0,
        rejected.forall(_.isInstanceOf[ToolAuthorization.Denied]),
        allowed == ToolAuthorization.Approved(original)
      )
    ,
    test(
      "resumed gate cancellation joins cleanup and preserves interruption plus defects without execution"
    ):
      ZIO.scoped:
        for
          entered    <- Promise.make[Nothing, Unit]
          cleaning   <- Promise.make[Nothing, Unit]
          release    <- Promise.make[Nothing, Unit]
          executions <- Ref.make(0)
          modelCalls <- Ref.make(0)
          defect = new IllegalStateException("gate cleanup failed")
          tools  = Toolkit(Map("read" -> readTool(_ => executions.update(_ + 1).as("unexpected"))))
          saved <- checkpoint(Msg.assistant(Chunk(call)), tools)
          gate = PermissionGate((_, _) =>
            ZIO.addFinalizer(cleaning.succeed(()) *> release.await *> ZIO.die(defect)) *>
              entered.succeed(()) *> ZIO.never
          )
          authorizer <- ConfirmedBatchAuthorizer.make(
            saved,
            Chunk(ConfirmedToolCall(0, ConfirmResult(true))),
            ZIO.succeed(PermissionEngine()),
            gate,
            PermissionGate.abstain
          )
          restored <- ReActAgent.make(
            toolkit = tools,
            toolAuthorizer = authorizer,
            client = LlmClient.mockEffectful((_, _, _) =>
              modelCalls.update(_ + 1).as(TurnOutput.FinalText(Msg.assistant("unexpected")))
            )
          )
          _ <- restored.restore(saved)
          checked <- (for
            fiber    <- restored.resume().forkScoped
            _        <- entered.await
            _        <- ZIO.fiberId.flatMap(fiber.interruptAsFork)
            _        <- cleaning.await
            waiting  <- fiber.poll
            _        <- release.succeed(())
            exit     <- fiber.await
            history  <- restored.history
            repeated <- restored.resume().exit
            calls    <- modelCalls.get
            done     <- executions.get
          yield assertTrue(
            waiting.isEmpty,
            exit.causeOption.exists(cause => cause.isInterrupted && cause.defects.contains(defect)),
            repeated.isFailure,
            calls == 0,
            done == 0,
            history.flatMap(_.toolResults).map(_.state) == Chunk(ToolResultState.Error),
            history.count(_.generationReason == GenerateReason.Interrupted) == 1
          )).ensuring(release.succeed(()).unit)
        yield checked
  ) @@ TestAspect.timeout(20.seconds)
