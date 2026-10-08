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
import com.weisgnosis.agents.core.event.ModelStreamContext
import com.weisgnosis.agents.core.model.*
import com.weisgnosis.agents.core.tool.*
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

object PermissionAdvisorySpec extends ZIOSpecDefault:
  private enum ProviderFailure:
    case Typed, Cancelled

  private val raw: Json      = Json.Obj("query" -> Json.Str("model"))
  private val prepared: Json = Json.Obj("query" -> Json.Str("model"), "tenant" -> Json.Str("host"))

  private def inspection(executions: Ref[Chunk[Json]]): IO[AgentError, Tool[Any, Json, String]] =
    ZIO.fromEither:
      for
        fixed <- ToolFixedArguments.make(Json.Obj("tenant" -> Json.Str("host")))
        tool <- Tool.withFixedArguments(
          Tool.make(ToolDef("inspect", "Read", Json.Obj(), isReadOnly = true))((input: Json) =>
            executions.update(_ :+ input).as("read")
          ),
          fixed
        )
      yield tool

  private def providerFailure(mode: ProviderFailure): ZIO[Scope, AgentError, TestResult] =
    for
      requests <- PermissionRequests.make
      entered  <- Promise.make[Nothing, Unit]
      cleaning <- Promise.make[Nothing, Unit]
      release  <- Promise.make[Nothing, Unit]
      notices  <- Ref.make(0)
      failure = AgentError.PermissionDenied("inspect", "Suggestion provider failed")
      defect  = AgentError.CompletionObservationError("Suggestion cleanup failed")
      provider = PermissionSuggestions(_ =>
        ZIO.addFinalizer(
          cleaning.succeed(()) *> release.await *> ZIO.die(defect)
        ) *> entered.succeed(()) *>
          (if mode == ProviderFailure.Typed then ZIO.fail(failure) else ZIO.never)
      )
      authorizer <- InteractivePermissionAuthorizer.makeWithAdvisories(
        requests,
        _ => notices.update(_ + 1),
        suggestions = provider
      )
      request = ToolAuthorizationRequest(
        "request",
        ModelStreamContext("reply", "model"),
        ContentBlock.ToolUse("call", "inspect", prepared),
        Some(0)
      )
      active <- ZIO.uninterruptibleMask: restore =>
        for
          fiber <- restore(ZIO.scoped(authorizer.authorize(request))).forkScoped
          _     <- ZIO.addFinalizer(release.succeed(()).unit)
        yield fiber
      _             <- entered.await
      controller    <- ZIO.fiberId
      _             <- active.interruptAsFork(controller).when(mode == ProviderFailure.Cancelled)
      _             <- cleaning.await
      early         <- active.poll
      pendingBefore <- requests.pendingIds
      _             <- release.succeed(())
      exit          <- active.await
      pendingAfter  <- requests.pendingIds
      count         <- notices.get
      cause = exit.causeOption.getOrElse(Cause.empty)
    yield assertTrue(
      early.isEmpty,
      pendingBefore.isEmpty && pendingAfter.isEmpty,
      count == 0,
      cause.defects.toList == List(defect),
      cause.failures.toList == (if mode == ProviderFailure.Typed then List(failure) else Nil),
      if mode == ProviderFailure.Cancelled then cause.interruptors.contains(controller)
      else !cause.isInterrupted
    )

  def spec: Spec[TestEnvironment & Scope, Any] = suite("PermissionAdvisorySpec")(
    test(
      "interactive advice is scoped and immutable, does not grant permission, and never suggests a wildcard rule"
    ):
      for
        requests   <- PermissionRequests.make
        queue      <- Queue.unbounded[(PermissionAdvisory, Boolean)]
        closed     <- Ref.make(false)
        inputs     <- Ref.make(Chunk.empty[Json])
        executions <- Ref.make(Chunk.empty[Json])
        modelCalls <- Ref.make(0)
        provider = PermissionSuggestions(request =>
          ZIO.acquireRelease(closed.set(false))(_ => closed.set(true)) *>
            inputs.update(_ :+ request.call.input) *>
            PermissionSuggestions.default.suggest(request)
        )
        authorizer <- InteractivePermissionAuthorizer.makeWithAdvisories(
          requests,
          advisory => closed.get.flatMap(value => queue.offer(advisory -> value).unit),
          suggestions = provider
        )
        tool <- inspection(executions)
        client = LlmClient.mockEffectful((_, _, _) =>
          modelCalls
            .getAndUpdate(_ + 1)
            .map(index =>
              if index % 2 == 0 then
                TurnOutput
                  .ToolCalls(Msg.assistant(Chunk(ContentBlock.ToolUse("repeated", "inspect", raw))))
              else TurnOutput.FinalText(Msg.assistant("done"))
            )
        )
        agent <- ReActAgent.make(
          client = client,
          toolkit = Toolkit.single(tool),
          toolAuthorizer = authorizer
        )
        first        <- agent.run(Msg.user("first")).forkScoped
        one          <- queue.take
        pending      <- requests.pendingIds
        before       <- executions.get
        beforePolicy <- authorizer.policy
        _            <- requests.resolve(one._1.request.requestId, ConfirmResult(true))
        firstResult  <- first.join
        afterFirst   <- authorizer.policy
        second       <- agent.run(Msg.user("second")).forkScoped
        two          <- queue.take
        _ <- requests.resolve(
          two._1.request.requestId,
          ConfirmResult(true, newRules = two._1.suggestedRules)
        )
        secondResult <- second.join
        updated      <- authorizer.policy
        thirdResult  <- agent.run(Msg.user("third"))
        extra        <- queue.poll
        actual       <- executions.get
        calls        <- modelCalls.get
        seen         <- inputs.get
        deniedVisits <- Ref.make(0)
        denied <- InteractivePermissionAuthorizer.makeWithAdvisories(
          requests,
          _ => deniedVisits.update(_ + 1),
          PermissionEngine(Chunk(PermissionRule("inspect", PermissionBehavior.Deny))),
          suggestions = PermissionSuggestions(_ => deniedVisits.update(_ + 1).as(Chunk.empty))
        )
        rejected    <- ZIO.scoped(denied.authorize(one._1.request))
        deniedCount <- deniedVisits.get
        ambiguous <- ZIO.foreach(Chunk("*", "", "\u2003"))(name =>
          ZIO.scoped(
            PermissionSuggestions.default.suggest(
              one._1.request.copy(
                call = one._1.request.call.copy(name = name)
              )
            )
          )
        )
        explicitVotes <- ZIO.foreach(Chunk(PermissionBehavior.Ask, PermissionBehavior.Passthrough)):
          vote =>
            for
              notices <- Queue.unbounded[PermissionAdvisory]
              review <- InteractivePermissionAuthorizer.makeWithAdvisories(
                requests,
                value => notices.offer(value).unit,
                PermissionEngine(mode = PermissionMode.Bypass),
                toolGate = PermissionGate((_, _) => ZIO.some(vote))
              )
              request = one._1.request.copy(requestId = s"explicit:$vote")
              active     <- ZIO.scoped(review.authorize(request)).forkScoped
              notice     <- notices.take
              unfinished <- active.poll
              _          <- requests.resolve(request.requestId, ConfirmResult(false))
              denied     <- active.join
            yield notice.suggestedRules == one._1.suggestedRules && unfinished.isEmpty &&
              denied.isInstanceOf[ToolAuthorization.Denied]
        failures <- ZIO.foreach(ProviderFailure.values)(providerFailure)
        expected = Chunk(PermissionRule("inspect", PermissionBehavior.Allow, source = "suggested"))
      yield failures.foldLeft(
        assertTrue(
          one._2 && two._2,
          one._1.suggestedRules == expected && two._1.suggestedRules == expected,
          one._1.request.call.input == prepared,
          pending == Set(one._1.request.requestId),
          before.isEmpty && beforePolicy.rules.isEmpty,
          firstResult.state == AgentRunState.Completed && afterFirst.rules.isEmpty,
          secondResult.state == AgentRunState.Completed && updated.rules == expected,
          thirdResult.state == AgentRunState.Completed && extra.isEmpty,
          seen == Chunk(prepared, prepared),
          actual == Chunk(prepared, prepared, prepared) && calls == 6,
          rejected.isInstanceOf[ToolAuthorization.Denied] && deniedCount == 0,
          ambiguous.forall(_.isEmpty),
          explicitVotes.forall(identity)
        )
      )(_ && _)
    ,
    test(
      "serialized checkpoints expose only saved permission occurrences and resume exact prepared inputs without another initial model request"
    ):
      for
        executions <- Ref.make(Chunk.empty[Json])
        modelCalls <- Ref.make(0)
        tool       <- inspection(executions)
        blocked = Tool.make(ToolDef("blocked", "Never execute", Json.Obj()))((_: Json) =>
          ZIO.fail(AgentError.ToolExecutionError("blocked", "Unexpected dispatch")).as("unexpected")
        )
        toolkit = Toolkit.single(tool).updated(blocked)
        policy  = PermissionEngine(Chunk(PermissionRule("blocked", PermissionBehavior.Deny)))
        client = LlmClient.mockEffectful((_, _, _) =>
          modelCalls
            .getAndUpdate(_ + 1)
            .map:
              case 0 =>
                TurnOutput.ToolCalls(
                  Msg.assistant(
                    Chunk(
                      ContentBlock.ToolUse("same", "blocked", raw),
                      ContentBlock.ToolUse("same", "inspect", raw)
                    )
                  )
                )
              case _ => TurnOutput.FinalText(Msg.assistant("done"))
        )
        agent <- ReActAgent.make(
          client = client,
          toolkit = toolkit,
          toolAuthorizer = CheckpointPermissionAuthorizer(ZIO.succeed(policy))
        )
        initial <- agent.run(Msg.user("question"))
        saved <- ZIO.fromEither(
          initial.toJson.fromJson[AgentResult].left.map(AgentError.DeserializationError(_))
        )
        inputs <- Ref.make(Chunk.empty[Json])
        customRule = PermissionRule(
          "inspect",
          PermissionBehavior.Allow,
          Some("host"),
          "suggested",
          Chunk("tenant")
        )
        provider = PermissionSuggestions(request =>
          inputs.update(_ :+ request.call.input).as(Chunk(customRule))
        )
        advice          <- CheckpointPermissionAuthorizer.advisories(saved, provider)
        pending         <- ZIO.fromEither(PendingActing.validate(saved))
        beforeModel     <- modelCalls.get
        beforeExecution <- executions.get
        legacy = saved.copy(state =
          AgentRunState.Suspended(pending.copy(permissionRequests = Chunk.empty))
        )
        unrecorded <- CheckpointPermissionAuthorizer.advisories(legacy, provider)
        invalidStage <- CheckpointPermissionAuthorizer
          .advisories(saved.copy(state = AgentRunState.Completed), provider)
          .either
        invalidOrdinal <- CheckpointPermissionAuthorizer
          .advisories(
            saved.copy(state =
              AgentRunState.Suspended(
                pending.copy(permissionRequests =
                  pending.permissionRequests.map(_.copy(callOrdinal = 0))
                )
              )
            ),
            provider
          )
          .either
        seen <- inputs.get
        confirmations <- ZIO.foreach(advice)(value =>
          ZIO
            .fromOption(value.request.callOrdinal)
            .orElseFail(AgentError.InvalidConfiguration("Missing advisory occurrence"))
            .map(ordinal =>
              ConfirmedToolCall(ordinal, ConfirmResult(true, newRules = value.suggestedRules))
            )
        )
        authorizer <- ConfirmedBatchAuthorizer.make(saved, confirmations, ZIO.succeed(policy))
        fresh <- ReActAgent.make(client = client, toolkit = toolkit, toolAuthorizer = authorizer)
        _     <- fresh.restore(saved)
        completed      <- fresh.resume()
        afterModel     <- modelCalls.get
        afterExecution <- executions.get
      yield assertTrue(
        initial.isSuspended && beforeModel == 1 && beforeExecution.isEmpty,
        advice.size == 1 && advice.forall(value =>
          value.request.callOrdinal.contains(1) && value.request.call.input == prepared &&
            value.request.context == pending.context && value.suggestedRules == Chunk(customRule) &&
            pending.permissionRequests.exists(_.requestId == value.request.requestId)
        ),
        seen == Chunk(prepared),
        unrecorded.isEmpty && invalidStage.isLeft && invalidOrdinal.isLeft,
        policy.rules == Chunk(PermissionRule("blocked", PermissionBehavior.Deny)),
        authorizer.ruleUpdates == Chunk(customRule),
        completed.state == AgentRunState.Completed && afterModel == 2,
        afterExecution == Chunk(prepared),
        completed.history.flatMap(_.toolResults).map(_.state) == Chunk(
          ToolResultState.Denied,
          ToolResultState.Success
        )
      )
  ) @@ TestAspect.timeout(20.seconds)
