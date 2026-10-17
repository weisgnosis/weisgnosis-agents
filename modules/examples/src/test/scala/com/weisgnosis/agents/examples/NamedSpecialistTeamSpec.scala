/* Copyright 2024-2026 WeisGnosis. Licensed under the Apache License, Version 2.0. */
package com.weisgnosis.agents.examples

import com.weisgnosis.agents.core.agent.ReActAgent
import com.weisgnosis.agents.core.client.{ScriptedLlmClient, TurnOutput}
import com.weisgnosis.agents.core.model.{Msg, ToolResultState}
import zio.*
import zio.test.*

object NamedSpecialistTeamSpec extends ZIOSpecDefault:
  def spec: Spec[TestEnvironment & Scope, Any] = suite("NamedSpecialistTeamSpec")(
    test("one leader turn runs independent specialists and retains private follow-up context") {
      NamedSpecialistTeam.demonstrate.map(evidence =>
        assertTrue(
          evidence.firstReply == "Release review collected from researcher and reviewer.",
          evidence.followupReply == "Add the migration note before release.",
          evidence.participants.take(2).sorted == Chunk("researcher", "reviewer"),
          evidence.participants.drop(2) == Chunk("researcher"),
          evidence.parallelMembersStarted,
          evidence.privateContextRetained,
          evidence.privateObservationHidden,
          evidence.summariesBounded,
          evidence.omittedCharacters > 0,
          evidence.unknownMemberRejected
        )
      )
    },
    test("leader cancellation joins synchronous specialist cleanup before returning") {
      for
        entered  <- Promise.make[Nothing, Unit]
        cleaning <- Promise.make[Nothing, Unit]
        release  <- Promise.make[Nothing, Unit]
        returned <- Promise.make[Nothing, Unit]
        memberClient <- ScriptedLlmClient.makeEffectful(
          Chunk(
            ZIO.acquireRelease(entered.succeed(()))(_ =>
              cleaning.succeed(()) *> release.await
            ) *> ZIO.never
          ),
          maxRecordedRequests = 1
        )
        entry <- NamedSpecialistTeam.member("researcher", memberClient)
        tools <- NamedSpecialistTeam.install(Chunk(entry))
        leaderClient <- ScriptedLlmClient.make(
          Chunk(
            TurnOutput.ToolCalls(
              Msg.assistant(
                Chunk(
                  NamedSpecialistTeam.assignment("work", "researcher", "Wait for cancellation.")
                )
              )
            )
          ),
          maxRecordedRequests = 1
        )
        leader <- ReActAgent.make(client = leaderClient, toolkit = tools)
        result <- (for
          running               <- leader.run(Msg.user("Delegate.")).forkScoped
          _                     <- entered.await
          cancelling            <- running.interrupt.ensuring(returned.succeed(()).unit).forkScoped
          _                     <- cleaning.await
          returnedBeforeCleanup <- returned.isDone
          _                     <- release.succeed(())
          outcome               <- cancelling.join
          history               <- leader.history
          _                     <- leaderClient.verifyConsumed *> memberClient.verifyConsumed
        yield assertTrue(
          !returnedBeforeCleanup,
          outcome.causeOption.exists(_.isInterrupted),
          history.flatMap(_.toolResults).size == 1,
          history.flatMap(_.toolResults).forall(_.state == ToolResultState.Error),
          history.count(_.metadata.contains("agentscope_generate_reason")) == 1
        )).ensuring(release.succeed(()).unit)
      yield result
    }
  ) @@ TestAspect.timeout(20.seconds)
