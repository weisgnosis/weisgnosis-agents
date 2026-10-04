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
import com.weisgnosis.agents.core.model.*
import com.weisgnosis.agents.core.tool.*
import zio.*
import zio.json.ast.Json
import zio.test.*

object ReActAgentToolAuthorizerSpec extends ZIOSpecDefault:
  private val originalInput: Json = Json.Obj("value" -> Json.Str("original"))
  private val approvedInput: Json = Json.Obj("value" -> Json.Str("approved"))
  private val call: ContentBlock.ToolUse =
    ContentBlock.ToolUse("call", "lookup", originalInput, Some("cached arguments"))
  private def toolkit(run: Json => UIO[String]): Toolkit =
    Toolkit(Map("lookup" -> Tool.make(ToolDef("lookup", "Read", Json.Obj()))(run)))
  private def client(calls: Chunk[ContentBlock.ToolUse]): UIO[LlmClient] =
    LlmClient.mock(
      Chunk(TurnOutput.ToolCalls(Msg.assistant(calls)), TurnOutput.FinalText(Msg.assistant("done")))
    )

  def spec: Spec[TestEnvironment & Scope, Any] = suite("ReActAgentToolAuthorizerSpec")(
    test(
      "fixed arguments are visible to authorization and accepted input is persisted before contextual execution"
    ) {
      val candidate = Json.Obj("value" -> Json.Str("original"), "tenant" -> Json.Str("host"))
      val approved  = Json.Obj("tenant" -> Json.Str("host"), "value" -> Json.Str("approved"))
      for
        agentRef          <- Ref.make(Option.empty[ReActAgent])
        observedBefore    <- Ref.make(Chunk.empty[ContentBlock.ToolUse])
        observedExecution <- Ref.make(Chunk.empty[ContentBlock.ToolUse])
        requested         <- Ref.make(Option.empty[ContentBlock.ToolUse])
        invoked           <- Ref.make(Option.empty[ContentBlock.ToolUse])
        closed            <- Ref.make(false)
        wasClosed         <- Ref.make(false)
        received          <- Ref.make(Option.empty[Json])
        fixed <- ZIO.fromEither(ToolFixedArguments.make(Json.Obj("tenant" -> Json.Str("host"))))
        authorizer = ToolAuthorizer(request =>
          requested.set(Some(request.call)) *> agentRef.get
            .flatMap(ZIO.foreach(_)(_.history))
            .flatMap(history =>
              observedBefore.set(Chunk.fromIterable(history).flatten.flatMap(_.toolUses))
            ) *>
            ZIO.addFinalizer(closed.set(true)).as(ToolAuthorization.Approved(approved))
        )
        contextual = new Tool[Any, Json, String]:
          def definition: ToolDef                          = ToolDef("lookup", "Read", Json.Obj())
          def decodeInput(raw: Json): Either[String, Json] = Right(raw)
          def encodeOutput(out: String): String            = out
          def execute(input: Json): UIO[String] =
            received.set(Some(input)) *> closed.get.flatMap(wasClosed.set) *>
              agentRef.get
                .flatMap(ZIO.foreach(_)(_.history))
                .flatMap(history =>
                  observedExecution.set(Chunk.fromIterable(history).flatten.flatMap(_.toolUses))
                )
                .as("ok")
          override def executeWithContext(input: Json, invocation: ToolInvocation): UIO[String] =
            val active = invocation match
              case ToolInvocation.Active(_, value, _, _) => Some(value)
              case ToolInvocation.Detached               => None
            invoked.set(active) *> execute(input)
        bound <- ZIO.fromEither(Tool.withFixedArguments(contextual, fixed))
        model <- client(Chunk(call))
        agent <- ReActAgent
          .builder()
          .model(model)
          .toolAuthorizer(authorizer)
          .toolkit(Toolkit(Map("lookup" -> bound)))
          .build
        _          <- agentRef.set(Some(agent))
        result     <- agent.run(Msg.user("q"))
        before     <- observedBefore.get
        during     <- observedExecution.get
        input      <- received.get
        released   <- wasClosed.get
        request    <- requested.get
        invocation <- invoked.get
      yield assertTrue(
        before == Chunk(call),
        request.contains(
          call.copy(
            input = candidate,
            content = None,
            metadata = Map("agentscope_original_tool_input" -> originalInput)
          )
        ),
        input.contains(approved),
        released,
        during == Chunk(
          call.copy(
            input = approved,
            content = None,
            metadata = Map("agentscope_original_tool_input" -> originalInput)
          )
        ),
        invocation == during.headOption,
        result.history.flatMap(_.toolUses) == during,
        result.history.flatMap(_.toolResults).map(_.state) == Chunk(ToolResultState.Success)
      )
    },
    test("fixed arguments reject conflicting approval and signed rewrites before execution") {
      val candidate = Json.Obj("value" -> Json.Str("original"), "tenant" -> Json.Str("host"))
      val conflict  = Json.Obj("value" -> Json.Str("original"), "tenant" -> Json.Str("other"))
      val signed    = call.copy(input = candidate).withThoughtSignature("signature")
      val malformed = call.copy(input =
        Json.Obj(
          "tenant" -> Json.Obj("same" -> Json.Num(1), "same" -> Json.Num(2))
        )
      )
      val scenarios = Chunk(
        (call, ToolAuthorization.Approved(originalInput), ToolResultState.Denied, 1),
        (call, ToolAuthorization.Approved(conflict), ToolResultState.Denied, 1),
        (call, ToolAuthorization.Denied("declined"), ToolResultState.Denied, 1),
        (
          call.withThoughtSignature("signature"),
          ToolAuthorization.Approved(candidate),
          ToolResultState.Denied,
          0
        ),
        (
          call.copy(metadata = Map("reasoningDetails" -> Json.Arr(Json.Str("opaque")))),
          ToolAuthorization.Approved(candidate),
          ToolResultState.Denied,
          0
        ),
        (malformed, ToolAuthorization.Approved(candidate), ToolResultState.Error, 0),
        (signed, ToolAuthorization.Approved(candidate), ToolResultState.Success, 1),
        (
          signed,
          ToolAuthorization.Approved(
            Json.Obj("value" -> Json.Str("edited"), "tenant" -> Json.Str("host"))
          ),
          ToolResultState.Denied,
          1
        )
      )
      val protectedScenarios = scenarios.map { case (original, decision, expected, requests) =>
        (original, decision, expected, requests, Option.empty[ContentBlock.Thinking])
      } ++ Chunk(
        Json.Arr(),
        Json.Arr(
          Json.Obj(
            "type" -> Json.Str("reasoning.encrypted"),
            "data" -> Json.Str("opaque")
          )
        )
      ).map(details =>
        (
          call,
          ToolAuthorization.Approved(candidate),
          ToolResultState.Denied,
          0,
          Some(ContentBlock.Thinking("", Map("reasoningDetails" -> details)))
        )
      )
      for
        fixed <- ZIO.fromEither(ToolFixedArguments.make(Json.Obj("tenant" -> Json.Str("host"))))
        results <- ZIO.foreach(protectedScenarios) {
          case (original, decision, expected, expectedRequests, thought) =>
            for
              executions <- Ref.make(0)
              requests   <- Ref.make(0)
              bound <- ZIO.fromEither(
                Tool.withFixedArguments(
                  Tool.make(ToolDef("lookup", "Read", Json.Obj()))(_ =>
                    executions.update(_ + 1).as("ok")
                  ),
                  fixed
                )
              )
              model <- LlmClient.mock(
                Chunk(
                  TurnOutput.ToolCalls(Msg.assistant(Chunk.fromIterable(thought) :+ original)),
                  TurnOutput.FinalText(Msg.assistant("done"))
                )
              )
              agent <- ReActAgent.make(
                client = model,
                toolkit = Toolkit(Map("lookup" -> bound)),
                toolAuthorizer = ToolAuthorizer(_ => requests.update(_ + 1).as(decision))
              )
              result <- agent.run(Msg.user("q"))
              count  <- executions.get
              asked  <- requests.get
            yield assertTrue(
              result.history.flatMap(_.toolUses) == Chunk(original),
              result.history.flatMap(_.toolResults).map(_.state) == Chunk(expected),
              count == (if expected == ToolResultState.Success then 1 else 0),
              asked == expectedRequests,
              thought.forall(value => result.history.exists(_.content.contains(value)))
            )
        }
      yield results.reduceOption(_ && _).getOrElse(assertTrue(false))
    },
    test("denial is a correlated completed observation without toolkit execution") {
      for
        executions <- Ref.make(0)
        model      <- client(Chunk(call))
        agent <- ReActAgent.make(
          client = model,
          toolkit = toolkit(_ => executions.update(_ + 1).as("wrong")),
          toolAuthorizer = ToolAuthorizer(_ => ZIO.succeed(ToolAuthorization.Denied("declined")))
        )
        events  <- agent.streamEvents(Msg.user("q")).runCollect
        history <- agent.history
        count   <- executions.get
        ends = events.collect { case event: AgentEvent.ToolResultEndEvent => event }
      yield assertTrue(
        count == 0,
        history.flatMap(_.toolResults).map(_.state) == Chunk(ToolResultState.Denied),
        history.flatMap(_.toolResults).map(_.text) == Chunk("declined"),
        ends.size == 1,
        ends.forall(event => event.state == ToolResultState.Denied && event.context.nonEmpty),
        events.count(_.isInstanceOf[AgentEvent.ToolResultStartEvent]) == 1
      )
    },
    test("typed permission failure uses runtime identity and preserves its reason") {
      for
        model <- client(Chunk(call))
        agent <- ReActAgent.make(
          client = model,
          toolAuthorizer =
            ToolAuthorizer(_ => ZIO.fail(AgentError.PermissionDenied("spoof", "restricted")))
        )
        result <- agent.run(Msg.user("q"))
      yield assertTrue(
        result.history.flatMap(_.toolResults) == Chunk(
          ContentBlock.ToolResult.text("call", "lookup", "restricted", ToolResultState.Denied)
        )
      )
    },
    test("authorizer defects and finalizer defects fail closed without dangling calls") {
      val defect = new IllegalStateException("host failure")
      val authorizers = Chunk(
        ToolAuthorizer(_ => ZIO.die(defect)),
        ToolAuthorizer(_ =>
          ZIO.addFinalizer(ZIO.die(defect)).as(ToolAuthorization.Approved(originalInput))
        )
      )
      for
        executions <- Ref.make(0)
        states <- ZIO.foreach(authorizers)(authorizer =>
          for
            model <- client(Chunk(call))
            agent <- ReActAgent.make(
              client = model,
              toolkit = toolkit(_ => executions.update(_ + 1).as("wrong")),
              toolAuthorizer = authorizer
            )
            result <- agent.run(Msg.user("q"))
          yield result.history.flatMap(_.toolResults).map(_.state)
        )
        count <- executions.get
      yield assertTrue(count == 0, states == Chunk.fill(2)(Chunk(ToolResultState.Denied)))
    },
    test("invalid duplicate-key approved input and modified signed arguments are denied") {
      val signed        = call.withThoughtSignature("signature")
      val invalid: Json = Json.Obj("value" -> Json.Str("one"), "value" -> Json.Str("two"))
      for
        executions <- Ref.make(0)
        results <- ZIO.foreach(Chunk((call, invalid), (signed, approvedInput))) {
          case (original, input) =>
            for
              model <- client(Chunk(original))
              agent <- ReActAgent.make(
                client = model,
                toolkit = toolkit(_ => executions.update(_ + 1).as("wrong")),
                toolAuthorizer = ToolAuthorizer(_ => ZIO.succeed(ToolAuthorization.Approved(input)))
              )
              result <- agent.run(Msg.user("q"))
            yield (result.history.flatMap(_.toolUses), result.history.flatMap(_.toolResults))
        }
        count <- executions.get
      yield assertTrue(
        count == 0,
        results.map(_._1) == Chunk(Chunk(call), Chunk(signed)),
        results.forall(_._2.map(_.state) == Chunk(ToolResultState.Denied))
      )
    },
    test("modified input with opaque or malformed reasoning metadata is denied") {
      val metadata = Chunk(
        Map("thoughtSignature" -> Json.Bool(true)),
        Map("reasoningDetails" -> Json.Arr(Json.Str("opaque")))
      )
      for
        executions <- Ref.make(0)
        results <- ZIO.foreach(metadata)(fields =>
          for
            model <- client(Chunk(call.copy(metadata = fields)))
            agent <- ReActAgent.make(
              client = model,
              toolkit = toolkit(_ => executions.update(_ + 1).as("wrong")),
              toolAuthorizer =
                ToolAuthorizer(_ => ZIO.succeed(ToolAuthorization.Approved(approvedInput)))
            )
            result <- agent.run(Msg.user("q"))
          yield result.history
        )
        count <- executions.get
      yield assertTrue(
        count == 0,
        results.forall(_.flatMap(_.toolUses).forall(_.input == originalInput)),
        results.forall(_.flatMap(_.toolResults).map(_.state) == Chunk(ToolResultState.Denied))
      )
    },
    test("unchanged signed approval preserves raw content and metadata") {
      val signed = call.withThoughtSignature("signature")
      for
        model  <- client(Chunk(signed))
        agent  <- ReActAgent.make(client = model, toolkit = toolkit(_ => ZIO.succeed("ok")))
        result <- agent.run(Msg.user("q"))
      yield assertTrue(
        result.history.flatMap(_.toolUses) == Chunk(signed),
        result.history.flatMap(_.toolResults).forall(_.state == ToolResultState.Success)
      )
    },
    test("cooperative cancellation during approval closes resources and performs no execution") {
      for
        entered    <- Promise.make[Nothing, Unit]
        closed     <- Ref.make(false)
        executions <- Ref.make(0)
        fixed <- ZIO.fromEither(ToolFixedArguments.make(Json.Obj("tenant" -> Json.Str("host"))))
        bound <- ZIO.fromEither(
          Tool.withFixedArguments(
            Tool.make(ToolDef("lookup", "Read", Json.Obj()))(_ =>
              executions.update(_ + 1).as("wrong")
            ),
            fixed
          )
        )
        model <- client(Chunk(call))
        agent <- ReActAgent.make(
          client = model,
          toolkit = Toolkit(Map("lookup" -> bound)),
          toolAuthorizer = ToolAuthorizer(_ =>
            ZIO.addFinalizer(closed.set(true)) *> entered.succeed(()) *> ZIO.never
          )
        )
        fiber    <- agent.run(Msg.user("q")).forkScoped
        _        <- entered.await
        _        <- agent.interrupt
        result   <- fiber.join
        count    <- executions.get
        released <- closed.get
      yield assertTrue(
        count == 0,
        released,
        result.state == AgentRunState.Interrupted,
        result.history.flatMap(_.toolUses) == Chunk(call),
        result.history.flatMap(_.toolResults).map(_.state) == Chunk(ToolResultState.Error)
      )
    },
    test("internally interrupted authorization preserves its joined cleanup defect") {
      val defect = new IllegalStateException("Authorization child cleanup defect")
      for
        entered    <- Promise.make[Nothing, Unit]
        cleaning   <- Promise.make[Nothing, Unit]
        release    <- Promise.make[Nothing, Unit]
        executions <- Ref.make(0)
        model      <- client(Chunk(call))
        authorizer = ToolAuthorizer(_ =>
          for
            child <- (entered.succeed(()) *> ZIO.never.as[ToolAuthorization](
              ToolAuthorization.Approved(originalInput)
            )).ensuring(cleaning.succeed(()) *> release.await *> ZIO.die(defect)).forkScoped
            _ <- entered.await
            result <- child.interrupt.flatMap {
              case Exit.Success(value) => ZIO.succeed(value)
              case Exit.Failure(cause) => ZIO.refailCause(cause)
            }
          yield result
        )
        agent <- ReActAgent.make(
          client = model,
          toolkit = toolkit(_ => executions.update(_ + 1).as("wrong")),
          toolAuthorizer = authorizer
        )
        observed <- ZIO.scoped {
          (for
            running <- agent.run(Msg.user("q")).forkScoped
            _       <- cleaning.await
            pending <- running.poll
            _       <- release.succeed(())
            exit    <- running.await
          yield (pending, exit)).ensuring(release.succeed(()).unit)
        }
        history <- agent.history
        count   <- executions.get
      yield assertTrue(
        observed._1.isEmpty,
        observed._2.isInterrupted,
        observed._2.causeOption.exists(_.defects == List(defect)),
        count == 0,
        history.flatMap(_.toolResults).map(_.state) == Chunk(ToolResultState.Error)
      )
    } @@ TestAspect.timeout(10.seconds),
    test("fiber cancellation preserves completed sibling and reconciles only the approval waiter") {
      val waiting = call.copy(id = "second")
      for
        entered    <- Promise.make[Nothing, Unit]
        executions <- Ref.make(0)
        model      <- client(Chunk(call, waiting))
        agent <- ReActAgent.make(
          config = AgentConfig(toolExecution = ToolExecution.Sequential),
          client = model,
          toolkit = toolkit(_ => executions.update(_ + 1).as("ok")),
          toolAuthorizer = ToolAuthorizer(request =>
            if request.call.id == "second" then entered.succeed(()) *> ZIO.never
            else ZIO.succeed(ToolAuthorization.Approved(request.call.input))
          )
        )
        fiber   <- agent.run(Msg.user("q")).forkScoped
        _       <- entered.await
        exit    <- fiber.interrupt
        history <- agent.history
        count   <- executions.get
      yield assertTrue(
        exit.isInterrupted,
        count == 1,
        history.flatMap(_.toolResults).map(result => result.id -> result.state) == Chunk(
          "call"   -> ToolResultState.Success,
          "second" -> ToolResultState.Error
        )
      )
    },
    test("parallel identical calls use distinct approval identities and exact rewrite positions") {
      for
        requests <- Queue.unbounded[ToolAuthorizationRequest]
        first    <- Promise.make[Nothing, ToolAuthorization]
        second   <- Promise.make[Nothing, ToolAuthorization]
        counter  <- Ref.make(0)
        executed <- Queue.unbounded[Json]
        model    <- client(Chunk(call, call))
        authorizer = ToolAuthorizer(request =>
          counter
            .getAndUpdate(_ + 1)
            .flatMap(index =>
              requests.offer(request) *> (if index == 0 then first.await else second.await)
            )
        )
        agent <- ReActAgent.make(
          client = model,
          toolkit = toolkit(input => executed.offer(input).as("ok")),
          toolAuthorizer = authorizer
        )
        fiber       <- agent.run(Msg.user("q")).forkScoped
        requestOne  <- requests.take
        requestTwo  <- requests.take
        _           <- second.succeed(ToolAuthorization.Approved(approvedInput))
        secondInput <- executed.take
        _           <- first.succeed(ToolAuthorization.Approved(originalInput))
        firstInput  <- executed.take
        result      <- fiber.join
        calls = result.history.flatMap(_.toolUses)
      yield assertTrue(
        requestOne.requestId != requestTwo.requestId,
        requestOne.context == requestTwo.context,
        secondInput == approvedInput,
        firstInput == originalInput,
        calls.count(_.input == approvedInput) == 1,
        calls.count(_.input == originalInput) == 1,
        calls.filter(_.input == approvedInput).forall(_.content.isEmpty),
        result.history.flatMap(_.toolResults).size == 2
      )
    },
    test(
      "only matched inline results bypass authorization and cancellation keeps occurrence pairing"
    ) {
      val server = call.withServerTool(true)
      val inline = ContentBlock.ToolResult
        .text(call.id, call.name, "inline", metadata = Map("serverTool" -> Json.Bool(true)))
      for
        entered  <- Promise.make[Nothing, Unit]
        requests <- Ref.make(Chunk.empty[ToolAuthorizationRequest])
        model <- LlmClient.mock(
          Chunk(TurnOutput.ToolCalls(Msg.assistant(Chunk(call, server, inline))))
        )
        agent <- ReActAgent.make(
          client = model,
          toolAuthorizer = ToolAuthorizer(request =>
            requests.update(_ :+ request) *> entered.succeed(()) *> ZIO.never
          )
        )
        fiber   <- agent.run(Msg.user("q")).forkScoped
        _       <- entered.await
        _       <- fiber.interrupt
        history <- agent.history
        seen    <- requests.get
      yield assertTrue(
        seen.map(_.call) == Chunk(call),
        history.flatMap(_.toolResults).count(_.text == "inline") == 1,
        history.filter(_.role == Role.Tool).flatMap(_.toolResults).map(_.state) == Chunk(
          ToolResultState.Error
        )
      )
    },
    test("unmatched provider-marked calls are gated and reconciled when locally dispatched") {
      val server = call.withServerTool(true)
      for
        entered <- Promise.make[Nothing, Unit]
        model   <- client(Chunk(server))
        agent <- ReActAgent.make(
          client = model,
          toolAuthorizer = ToolAuthorizer(_ => entered.succeed(()) *> ZIO.never)
        )
        fiber   <- agent.run(Msg.user("q")).forkScoped
        _       <- entered.await
        _       <- fiber.interrupt
        history <- agent.history
      yield assertTrue(history.flatMap(_.toolResults).map(_.state) == Chunk(ToolResultState.Error))
    }
  )
