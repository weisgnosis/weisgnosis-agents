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
import zio.json.*
import zio.json.ast.Json
import zio.test.*

object ReActAgentSuppliedResultSpec extends ZIOSpecDefault:
  private enum Host:
    case Live, Restored
  private enum Supplied:
    case Partial, All
  private val pause: PostReasoningHook = PostReasoningHook((_, message) =>
    ZIO.succeed(
      if message.toolUses.nonEmpty then ReasoningDecision.Suspend("external execution")
      else ReasoningDecision.Keep
    )
  )
  private def call(index: Int): ContentBlock.ToolUse =
    ContentBlock.ToolUse("same", "read", Json.Num(index))
  private val reply: Msg = Msg.assistant(
    Chunk(
      call(0),
      call(1).withServerTool(true),
      ContentBlock.ToolResult.text("same", "read", "inline"),
      call(2)
    )
  )
  private def toolkit(run: Json => UIO[String]): Toolkit =
    Toolkit.single(Tool.make(ToolDef("read", "Read", Json.Obj()))(run))
  private def supplied(index: Int): SuppliedToolResult =
    SuppliedToolResult(index, ToolExecutionResult(s"provided-$index"))

  def spec: Spec[TestEnvironment & Scope, Any] = suite("ReActAgent supplied results")(
    test(
      "live and restored batches skip supplied occurrences and request final reasoning after remaining execution"
    ):
      ZIO
        .foreach(
          Chunk
            .fromArray(Host.values)
            .flatMap(host => Chunk.fromArray(Supplied.values).map(host -> _))
        ):
          case (host, supply) =>
            for
              executed   <- Ref.make(Chunk.empty[Json])
              authorized <- Ref.make(Chunk.empty[Option[Int]])
              processed  <- Ref.make(Chunk.empty[String])
              calls      <- Ref.make(0)
              expected =
                if supply == Supplied.Partial then Chunk[Json](Json.Num(0)) else Chunk.empty[Json]
              tools = toolkit(input => executed.update(_ :+ input).as("local-0"))
              client = LlmClient.mockEffectful((_, _, _) =>
                calls
                  .getAndUpdate(_ + 1)
                  .flatMap:
                    case 0 => ZIO.succeed(TurnOutput.ToolCalls(reply))
                    case _ =>
                      executed.get.flatMap(done =>
                        if done == expected then
                          ZIO.succeed(TurnOutput.FinalText(Msg.assistant("done")))
                        else
                          ZIO.fail(
                            AgentError
                              .InvalidConfiguration("Unexpected local replay or early reasoning")
                          )
                      )
              )
              processor = ToolResultProcessor(result =>
                processed.update(_ :+ result.text).as(ToolResultPayload.fromResult(result))
              )
              authorizer = ToolAuthorizer(request =>
                authorized
                  .update(_ :+ request.callOrdinal)
                  .as(ToolAuthorization.Approved(request.call.input))
              )
              original <- ReActAgent.make(
                client = client,
                toolkit = tools,
                postReasoningHook = pause,
                toolResultProcessor = processor,
                toolAuthorizer = authorizer
              )
              checkpoint <- original.run(Msg.user("q"))
              values =
                if supply == Supplied.Partial then Chunk(supplied(2))
                else Chunk(supplied(2), supplied(0))
              result <- host match
                case Host.Live => original.resume(values)
                case Host.Restored =>
                  for
                    augmented <- ZIO.fromEither(PendingActing.withResults(checkpoint, values))
                    decoded   <- ZIO.fromEither(augmented.toJson.fromJson[AgentResult])
                    restored <- ReActAgent.make(
                      client = client,
                      toolkit = tools,
                      toolResultProcessor = processor,
                      toolAuthorizer = authorizer
                    )
                    _         <- restored.restore(decoded)
                    completed <- restored.resume()
                  yield completed
              done         <- executed.get
              admitted     <- authorized.get
              observations <- processed.get
              count        <- calls.get
            yield assertTrue(
              done == expected,
              admitted == (if supply == Supplied.Partial then Chunk(Some(0)) else Chunk.empty),
              observations.sorted == (if supply == Supplied.Partial then
                                        Chunk("inline", "local-0", "provided-2")
                                      else Chunk("inline", "provided-0", "provided-2")),
              count == 2,
              result.reply.text == "done",
              result.iterationCount == 2,
              result.history.flatMap(_.toolUses) == reply.toolUses,
              result.history.count(_.role == Role.Tool) == 2,
              result.history.flatMap(_.toolResults).forall(_.state == ToolResultState.Success)
            )
        .map(_.foldLeft(assertTrue(true))(_ && _))
    ,
    test(
      "invalid live results leave the checkpoint intact and supplied stop decisions drain in original ordinal order"
    ):
      for
        calls      <- Ref.make(0)
        executions <- Ref.make(0)
        observed   <- Ref.make(Chunk.empty[String])
        client = LlmClient.mockEffectful((_, _, _) =>
          calls.update(_ + 1).as(TurnOutput.ToolCalls(reply))
        )
        agent <- ReActAgent.make(
          client = client,
          toolkit = toolkit(_ => executions.update(_ + 1).as("unexpected")),
          postReasoningHook = pause,
          toolResultProcessor = ToolResultProcessor.deciding(result =>
            observed
              .update(_ :+ result.text)
              .as(
                if result.text.startsWith("provided") then
                  ToolResultDecision.Stop(ToolResultPayload.fromResult(result), result.text)
                else ToolResultDecision.Continue(ToolResultPayload.fromResult(result))
              )
          )
        )
        checkpoint <- agent.run(Msg.user("q"))
        rejected   <- agent.resume(Chunk(supplied(1))).exit
        unchanged  <- agent.history
        result     <- agent.resume(Chunk(supplied(2), supplied(0)))
        count      <- calls.get
        executed   <- executions.get
        processed  <- observed.get
      yield assertTrue(
        rejected.isFailure,
        unchanged == checkpoint.history,
        result.state == AgentRunState.Stopped("provided-0"),
        count == 1,
        executed == 0,
        processed.sorted == Chunk("inline", "provided-0", "provided-2"),
        result.history
          .filter(_.role == Role.Tool)
          .flatMap(_.toolResults)
          .map(_.text)
          .sorted == Chunk("provided-0", "provided-2")
      )
    ,
    test(
      "cancellation during supplied-result processing preserves every supplied result and reconciles only unsupplied work"
    ):
      ZIO.scoped:
        for
          entered    <- Promise.make[Nothing, Unit]
          cleaning   <- Promise.make[Nothing, Unit]
          release    <- Promise.make[Nothing, Unit]
          calls      <- Ref.make(0)
          executions <- Ref.make(0)
          defect = new IllegalStateException("supplied processor cleanup failed")
          batch  = Msg.assistant(reply.content :+ call(3))
          client = LlmClient.mockEffectful((_, _, _) =>
            calls.update(_ + 1).as(TurnOutput.ToolCalls(batch))
          )
          processor = ToolResultProcessor(result =>
            if result.text.startsWith("provided") then
              ZIO.addFinalizer(cleaning.succeed(()) *> release.await *> ZIO.die(defect)) *>
                entered.succeed(()) *> ZIO.never
            else ZIO.succeed(ToolResultPayload.fromResult(result))
          )
          agent <- ReActAgent.make(
            client = client,
            toolkit = toolkit(_ => executions.update(_ + 1).as("unexpected")),
            postReasoningHook = pause,
            toolResultProcessor = processor
          )
          _ <- agent.run(Msg.user("q"))
          checked <- (for
            fiber    <- agent.resume(Chunk(supplied(3), supplied(2))).forkScoped
            _        <- entered.await
            before   <- agent.history
            _        <- ZIO.fiberId.flatMap(fiber.interruptAsFork)
            _        <- cleaning.await
            pending  <- fiber.poll
            _        <- release.succeed(())
            exit     <- fiber.await
            history  <- agent.history
            repeated <- agent.resume().exit
            count    <- calls.get
            executed <- executions.get
          yield assertTrue(
            before.filter(_.role == Role.Tool).flatMap(_.toolResults).map(_.text).sorted == Chunk(
              "provided-2",
              "provided-3"
            ),
            pending.isEmpty,
            exit.causeOption.exists(cause => cause.isInterrupted && cause.defects.contains(defect)),
            history
              .filter(_.role == Role.Tool)
              .flatMap(_.toolResults)
              .filter(_.state == ToolResultState.Success)
              .map(_.text)
              .sorted == Chunk("provided-2", "provided-3"),
            history.flatMap(_.toolResults).count(_.state == ToolResultState.Error) == 1,
            history.count(_.generationReason == GenerateReason.Interrupted) == 1,
            repeated.isFailure,
            count == 1,
            executed == 0
          )).ensuring(release.succeed(()).unit)
        yield checked
  ) @@ TestAspect.timeout(20.seconds)
