/*
 * Copyright 2024-2026 WeisGnosis (https://weisgnosis.com)
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.weisgnosis.agents.core.agent

import com.weisgnosis.agents.core.client.{LlmClient, TurnOutput}
import com.weisgnosis.agents.core.coordination.{HubMode, MsgHub}
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.model.{ContentBlock, Msg}
import com.weisgnosis.agents.core.tool.{
  Tool,
  ToolDef,
  Toolkit,
  ToolResultDecision,
  ToolResultPayload,
  ToolResultProcessor
}
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

object MsgHubSpec extends ZIOSpecDefault:
  private enum Entry:
    case Ordinary, Structured, Streamed, StructuredStream, PreparedStream, Resume, ResumeStream

  private enum Ending:
    case Interrupted, Stopped, Failed

  final private case class NumberReply(value: Int) derives JsonCodec

  private def resultFrom[A](updates: Chunk[AgentRunUpdate[A]]): IO[AgentError, A] =
    ZIO
      .fromOption(updates.collect { case AgentRunUpdate.Result(value) => value }.headOption)
      .orElseFail(AgentError.InvalidConfiguration("Missing terminal stream result"))

  private def peerCount(history: Chunk[Msg]): Int =
    history
      .flatMap(_.content)
      .count:
        case _: ContentBlock.PeerObservation => true
        case _                               => false

  private def agent(name: String, client: LlmClient): IO[AgentError, ReActAgent] =
    ReActAgent.make(
      config = AgentConfig(name = name),
      client = client,
      modelInputTransform = ModelInputTransform.peerAware
    )

  def spec: Spec[TestEnvironment & Scope, Any] = suite("Scoped persistent MsgHub")(
    test("automatic final replies are passive, retain private continuity and stop at scope exit"):
      for
        calls <- Ref.make(0)
        client = LlmClient.mockEffectful((_, _, _) =>
          calls.update(_ + 1).as(TurnOutput.FinalText(Msg.assistant("reply")))
        )
        alice <- agent("Alice", client)
        bob   <- agent("Bob", client)
        receipts <- ZIO.scoped:
          for
            _                <- MsgHub.make(Chunk(alice, bob))
            first            <- alice.run(Msg.user("Alice's private input"))
            observed         <- bob.history
            afterObservation <- calls.get
            second           <- bob.run(Msg.user("Bob's private input"))
            aliceHistory     <- alice.history
          yield (first, second, observed, afterObservation, aliceHistory)
        before <- bob.history
        _      <- alice.run(Msg.user("after close"))
        after  <- bob.history
      yield assertTrue(
        receipts._1.peerDeliveries.map(_.outcome) == Chunk(PeerDeliveryOutcome.Delivered),
        receipts._2.peerDeliveries.map(_.outcome) == Chunk(PeerDeliveryOutcome.Delivered),
        peerCount(receipts._3) == 1,
        receipts._4 == 1,
        peerCount(receipts._5) == 1,
        !receipts._3.exists(_.text.contains("Alice's private input")),
        before == after
      )
    ,
    test(
      "manual delivery, announcements, add, removal and mode changes affect only current members"
    ):
      for
        client <- LlmClient.mock(Chunk.fill(5)(TurnOutput.FinalText(Msg.assistant("reply"))))
        alice  <- agent("Alice", client)
        bob    <- agent("Bob", client)
        carol  <- agent("Carol", client)
        hub    <- MsgHub.make(Chunk(alice, bob), HubMode.Manual)
        quiet  <- alice.run(Msg.user("manual"))
        before <- bob.history
        manual <- hub.broadcast(
          alice,
          Chunk(Msg.assistant("manual first"), Msg.assistant("manual second"))
        )
        manualHistory          <- bob.history
        _                      <- hub.add(carol)
        added                  <- carol.history
        announcement           <- hub.announce(Chunk(Msg.system("shared announcement")))
        _                      <- hub.setMode(HubMode.Automatic)
        _                      <- hub.remove(bob)
        frozenBob              <- bob.history
        sent                   <- alice.run(Msg.user("automatic"))
        afterSend              <- bob.history
        carolBeforeRemovedSend <- carol.history
        _                      <- bob.run(Msg.user("removed sender"))
        carolAfterRemovedSend  <- carol.history
        _                      <- hub.setMode(HubMode.Manual)
        _                      <- alice.run(Msg.user("quiet again"))
        carolAfterManual       <- carol.history
      yield assertTrue(
        quiet.peerDeliveries.isEmpty,
        before.isEmpty,
        manual.size == 2,
        manual.forall(_.outcome == PeerDeliveryOutcome.Delivered),
        peerCount(manualHistory) == 2,
        added.isEmpty,
        announcement.size == 3,
        sent.peerDeliveries.map(_.recipientId) == Chunk(Some(carol.agentId)),
        frozenBob == afterSend,
        carolBeforeRemovedSend == carolAfterRemovedSend,
        carolAfterRemovedSend == carolAfterManual
      )
    ,
    test("overlapping hubs retain independent delivery and remove only their own subscriptions"):
      for
        client       <- LlmClient.mock(Chunk.fill(2)(TurnOutput.FinalText(Msg.assistant("reply"))))
        alice        <- agent("Alice", client)
        bob          <- agent("Bob", client)
        first        <- MsgHub.make(Chunk(alice, bob))
        second       <- MsgHub.make(Chunk(alice, bob))
        both         <- alice.run(Msg.user("both"))
        observedBoth <- bob.history
        _            <- first.close
        one          <- alice.run(Msg.user("one"))
        observedOne  <- bob.history
      yield assertTrue(
        both.peerDeliveries.map(_.hubId) == Chunk(first.id, second.id),
        peerCount(observedBoth) == 2,
        one.peerDeliveries.map(_.hubId) == Chunk(second.id),
        peerCount(observedOne) == 3
      )
    ,
    test("all public invocation forms publish one final response after execution"):
      ZIO
        .foreach(Chunk.fromArray(Entry.values)): entry =>
          ZIO.scoped:
            for
              client <- LlmClient.mock(
                (entry match
                  case Entry.Resume | Entry.ResumeStream =>
                    Chunk(
                      TurnOutput.ToolCalls(
                        Msg.assistant(Chunk(ContentBlock.ToolUse("pending", "tool", Json.Obj())))
                      )
                    )
                  case _ => Chunk.empty
                ) ++ Chunk(TurnOutput.FinalText(Msg.assistant("{\"value\":7}")))
              )
              alice <- ReActAgent.make(
                client = client,
                toolkit = Toolkit.single(
                  Tool.make(ToolDef("tool", "Read", Json.Obj()))(_ => ZIO.succeed("result"))
                ),
                postReasoningHook = PostReasoningHook((_, message) =>
                  ZIO.succeed(
                    if message.toolUses.nonEmpty then ReasoningDecision.Suspend("review")
                    else ReasoningDecision.Keep
                  )
                )
              )
              bob <- agent("Bob", client)
              _   <- MsgHub.make(Chunk(alice, bob))
              input = Msg.user("start")
              schema = Json.Obj(
                "type" -> Json.Str("object"),
                "properties" -> Json.Obj(
                  "value" -> Json.Obj("type" -> Json.Str("integer"))
                )
              )
              result <- entry match
                case Entry.Ordinary => alice.run(input)
                case Entry.Structured =>
                  alice
                    .runStructured[NumberReply](Chunk(input), schema, StructuredMode.Native)
                    .map(_._1)
                case Entry.Streamed => alice.streamWithResult(input).runCollect.flatMap(resultFrom)
                case Entry.StructuredStream =>
                  alice
                    .streamStructuredWithResult[NumberReply](
                      Chunk(input),
                      schema,
                      StructuredMode.Native
                    )
                    .runCollect
                    .flatMap(resultFrom)
                    .map(_._1)
                case Entry.PreparedStream =>
                  alice
                    .prepareRun(input)
                    .flatMap(prepared =>
                      prepared.streamWithResult.runCollect
                        .flatMap(resultFrom)
                        .flatMap(value =>
                          prepared.termination.flatMap(exit =>
                            ZIO
                              .fail(
                                AgentError
                                  .InvalidConfiguration("Prepared delivery outcome mismatch")
                              )
                              .unless(exit == Exit.succeed(value))
                              .as(value)
                          )
                        )
                    )
                case Entry.Resume | Entry.ResumeStream =>
                  for
                    suspended <- alice.run(input)
                    observed  <- bob.history
                    _ <- ZIO
                      .fail(AgentError.InvalidConfiguration("Suspension was broadcast"))
                      .unless(
                        suspended.isSuspended && suspended.peerDeliveries.isEmpty && observed.isEmpty
                      )
                    resumed <-
                      if entry == Entry.Resume then alice.resume()
                      else alice.streamResumeWithResult().runCollect.flatMap(resultFrom)
                  yield resumed
              observed <- bob.history
            yield assertTrue(
              result.state == AgentRunState.Completed,
              result.peerDeliveries.map(_.outcome) == Chunk(PeerDeliveryOutcome.Delivered),
              peerCount(observed) == 1
            )
        .map(results => results.reduceOption(_ && _).getOrElse(assertTrue(false)))
    ,
    test("simultaneous publishers return explicit outcomes without cross-gate waits"):
      ZIO.scoped:
        for
          entered <- Ref.make(0)
          ready   <- Promise.make[Nothing, Unit]
          client = LlmClient.mockEffectful((_, _, _) =>
            entered.updateAndGet(_ + 1).flatMap(count => ready.succeed(()).when(count == 2)) *>
              ready.await.as(TurnOutput.FinalText(Msg.assistant("reply")))
          )
          alice   <- agent("Alice", client)
          bob     <- agent("Bob", client)
          _       <- MsgHub.make(Chunk(alice, bob))
          results <- alice.run(Msg.user("a")).zipPar(bob.run(Msg.user("b")))
          a       <- alice.history
          b       <- bob.history
          receipts = results._1.peerDeliveries ++ results._2.peerDeliveries
        yield assertTrue(
          results._1.state == AgentRunState.Completed,
          results._2.state == AgentRunState.Completed,
          receipts.size == 2,
          peerCount(a) + peerCount(b) == receipts.count(_.outcome == PeerDeliveryOutcome.Delivered)
        )
    ,
    test("cooperative recovery is published while stopped and failed execution stay private"):
      ZIO
        .foreach(Chunk.fromArray(Ending.values)): ending =>
          ZIO.scoped:
            val client = LlmClient.mockEffectful((_, _, _) =>
              ending match
                case Ending.Interrupted =>
                  ZIO.succeed(TurnOutput.Interrupted(Msg.assistant("partial")))
                case Ending.Stopped =>
                  ZIO.succeed(
                    TurnOutput.ToolCalls(
                      Msg.assistant(Chunk(ContentBlock.ToolUse("tool", "tool", Json.Obj())))
                    )
                  )
                case Ending.Failed => ZIO.fail(AgentError.ModelError(500, "unavailable"))
            )
            for
              alice <- ReActAgent.make(
                client = client,
                toolkit = Toolkit.single(
                  Tool.make(ToolDef("tool", "Read", Json.Obj()))(_ => ZIO.succeed("result"))
                ),
                toolResultProcessor = ToolResultProcessor.deciding(result =>
                  ZIO.succeed(
                    ToolResultDecision.Stop(ToolResultPayload.fromResult(result), "host stop")
                  )
                )
              )
              bob      <- agent("Bob", client)
              _        <- MsgHub.make(Chunk(alice, bob))
              outcome  <- alice.run(Msg.user("start")).exit
              observed <- bob.history
              outcomeState = outcome match
                case Exit.Success(value) => Some(value.state)
                case Exit.Failure(_)     => None
            yield ending match
              case Ending.Interrupted =>
                assertTrue(
                  outcomeState.contains(AgentRunState.Interrupted),
                  peerCount(observed) == 1,
                  observed
                    .flatMap(_.content)
                    .exists:
                      case ContentBlock.PeerObservation(_, _, message) =>
                        message.generateReason.contains("interrupted")
                      case _ => false
                )
              case Ending.Stopped =>
                assertTrue(
                  outcomeState.contains(AgentRunState.Stopped("host stop")),
                  observed.isEmpty
                )
              case Ending.Failed => assertTrue(outcome.isFailure, observed.isEmpty)
        .map(results => results.reduceOption(_ && _).getOrElse(assertTrue(false)))
    ,
    test("caller cancellation joins request cleanup without broadcasting recovery"):
      ZIO.scoped:
        for
          entered <- Promise.make[Nothing, Unit]
          cleanup <- Promise.make[Nothing, Unit]
          release <- Promise.make[Nothing, Unit]
          client = LlmClient.mockEffectful((_, _, _) =>
            ZIO.addFinalizer(cleanup.succeed(()) *> release.await) *>
              entered.succeed(()) *> ZIO.never
          )
          alice <- agent("Alice", client)
          bob   <- agent("Bob", client)
          _     <- MsgHub.make(Chunk(alice, bob))
          running <- ZIO.uninterruptible:
            for
              fiber <- alice.run(Msg.user("start")).interruptible.forkScoped
              _     <- ZIO.addFinalizer(release.succeed(()).unit)
            yield fiber
          _ <- entered.await
          stopping <- ZIO.uninterruptible:
            for
              fiber <- running.interrupt.interruptible.forkScoped
              _     <- ZIO.addFinalizer(release.succeed(()).unit)
            yield fiber
          _         <- cleanup.await
          premature <- stopping.poll
          before    <- bob.history
          _         <- release.succeed(())
          stopped   <- stopping.join
          after     <- bob.history
        yield assertTrue(premature.isEmpty, stopped.isInterrupted, before.isEmpty, after.isEmpty)
    ,
    test("failed acquisition removes earlier subscriptions and leaves peer history untouched"):
      for
        client   <- LlmClient.mock(Chunk(TurnOutput.FinalText(Msg.assistant("reply"))))
        alice    <- agent("Alice", client)
        bob      <- agent("Bob", client)
        _        <- ZIO.foreachDiscard(1 to 32)(_ => MsgHub.make(Chunk(bob), HubMode.Manual))
        rejected <- ZIO.scoped(MsgHub.make(Chunk(alice, bob))).either
        reply    <- alice.run(Msg.user("after rejected registration"))
        observed <- bob.history
      yield assertTrue(rejected.isLeft, reply.peerDeliveries.isEmpty, observed.isEmpty)
    ,
    test(
      "busy recipients produce delivery receipts without changing authoritative prepared completion"
    ):
      ZIO.scoped:
        for
          entered <- Promise.make[Nothing, Unit]
          release <- Promise.make[Nothing, Unit]
          fast    <- LlmClient.mock(Chunk(TurnOutput.FinalText(Msg.assistant("reply"))))
          held = LlmClient.mockEffectful((_, _, _) =>
            entered.succeed(()) *> release.await.as(
              TurnOutput.FinalText(Msg.assistant("held reply"))
            )
          )
          alice        <- agent("Alice", fast)
          bob          <- agent("Bob", held)
          carol        <- agent("Carol", fast)
          _            <- MsgHub.make(Chunk(alice, bob, carol))
          running      <- bob.run(Msg.user("hold")).forkScoped
          _            <- entered.await
          before       <- bob.history
          prepared     <- alice.prepareRun(Msg.user("send"))
          completed    <- prepared.run
          status       <- prepared.status
          after        <- bob.history
          carolHistory <- carol.history
          _            <- release.succeed(())
          _            <- running.join
        yield assertTrue(
          completed.state == AgentRunState.Completed,
          status == PreparedInvocation.Status.Returned,
          completed.peerDeliveries.exists(delivery =>
            delivery.recipientId == Some(bob.agentId) &&
              (delivery.outcome match
                case PeerDeliveryOutcome.Rejected(_) => true
                case _                               => false)
          ),
          completed.peerDeliveries.exists(delivery =>
            delivery.recipientId == Some(carol.agentId) &&
              delivery.outcome == PeerDeliveryOutcome.Delivered
          ),
          before == after,
          peerCount(carolHistory) == 1
        )
  )
