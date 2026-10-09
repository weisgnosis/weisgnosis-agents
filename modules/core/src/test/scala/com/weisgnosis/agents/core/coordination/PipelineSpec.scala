/* Copyright 2024-2026 WeisGnosis. Licensed under the Apache License, Version 2.0. */
package com.weisgnosis.agents.core.coordination

import com.weisgnosis.agents.core.agent.*
import com.weisgnosis.agents.core.client.{LlmClient, TurnOutput}
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.model.{ContentBlock, Msg}
import com.weisgnosis.agents.core.tool.{Tool, ToolDef, Toolkit}
import zio.*
import zio.json.ast.Json
import zio.test.*

object PipelineSpec extends ZIOSpecDefault:
  private def agent(
      name: String,
      client: LlmClient,
      history: Chunk[Msg] = Chunk.empty
  ): IO[AgentError, ReActAgent] =
    ReActAgent.make(
      config = AgentConfig(name = name),
      client = client,
      initialHistory = history,
      modelInputTransform = ModelInputTransform.peerAware
    )

  def spec: Spec[TestEnvironment & Scope, Any] = suite("Pipeline")(
    test(
      "sequential handoff preserves attribution and private history, and suspension stops later stages"
    ):
      for
        requests <- Ref.make(Chunk.empty[(String, Chunk[Msg])])
        source = LlmClient.mockEffectful((history, _, _) =>
          requests
            .update(_ :+ ("source" -> history))
            .as(TurnOutput.FinalText(Msg.assistant("source answer")))
        )
        target = LlmClient.mockEffectful((history, _, _) =>
          requests
            .update(_ :+ ("target" -> history))
            .as(TurnOutput.FinalText(Msg.assistant("target answer")))
        )
        first <- agent("Source", source)
        second <- agent(
          "Target",
          target,
          Chunk(Msg.user("private context"), Msg.assistant("private answer"))
        )
        completed     <- Pipeline.sequential(Chunk(first, second), Chunk(Msg.user("start")))
        observed      <- requests.get
        firstHistory  <- first.history
        secondHistory <- second.history
        lastCalls     <- Ref.make(0)
        finalAgent <- agent(
          "Never started",
          LlmClient.mockEffectful((_, _, _) =>
            lastCalls.update(_ + 1).as(TurnOutput.FinalText(Msg.assistant("unexpected")))
          )
        )
        suspending <- ReActAgent.make(
          config = AgentConfig(name = "Review"),
          client = LlmClient.mockFunction((_, _) =>
            TurnOutput.ToolCalls(
              Msg.assistant(Chunk(ContentBlock.ToolUse("review-call", "review", Json.Obj())))
            )
          ),
          modelInputTransform = ModelInputTransform.peerAware,
          toolkit = Toolkit.single(
            Tool.make(ToolDef("review", "Review", Json.Obj()))((_: Json) => ZIO.succeed("reviewed"))
          ),
          postReasoningHook =
            PostReasoningHook((_, _) => ZIO.succeed(ReasoningDecision.Suspend("review")))
        )
        paused <- Pipeline.sequential(Chunk(suspending, finalAgent), Chunk(Msg.user("pause")))
        calls  <- lastCalls.get
        duplicate <- Pipeline
          .sequential(Chunk(finalAgent, finalAgent), Chunk(Msg.user("invalid")))
          .either
        afterRejected <- lastCalls.get
      yield assertTrue(
        completed.map(_.agentId) == Chunk(first.agentId, second.agentId),
        completed.map(_.name) == Chunk("Source", "Target"),
        completed.map(_.result.reply.text) == Chunk("source answer", "target answer"),
        completed.forall(_.result.state == AgentRunState.Completed),
        observed.map(_._1) == Chunk("source", "target"),
        observed.lift(1).exists(_._2.exists(_.text.contains("Peer message from \"Source\""))),
        secondHistory.exists(_.text == "private context"),
        secondHistory.exists(_.text == "private answer"),
        secondHistory.flatMap(_.content).exists {
          case ContentBlock.PeerObservation(id, name, message) =>
            id == first.agentId && name == "Source" && completed.headOption
              .map(_.result.reply)
              .contains(message)
          case _ => false
        },
        !firstHistory.flatMap(_.content).exists(_.isInstanceOf[ContentBlock.PeerObservation]),
        paused.size == 1,
        paused.headOption.exists(_.result.isSuspended),
        paused.headOption.exists(_.result.reply.toolUses.map(_.id) == Chunk("review-call")),
        calls == 0,
        duplicate.isLeft,
        afterRejected == 0
      )
    ,
    test(
      "bounded fanout retains ordered successes and typed failures, while caller cancellation joins cleanup"
    ):
      ZIO.scoped:
        for
          entered       <- Promise.make[Nothing, Unit]
          laterFinished <- Promise.make[Nothing, Unit]
          release       <- Promise.make[Nothing, Unit]
          inputs        <- Ref.make(Chunk.empty[Chunk[Msg]])
          error = AgentError.ModelError(400, "definite rejection")
          first <- agent(
            "First",
            LlmClient.mockEffectful((history, _, _) =>
              inputs.update(_ :+ history) *> entered.succeed(()) *> release.await
                .as(TurnOutput.FinalText(Msg.assistant("first")))
            )
          )
          failed <- agent(
            "Failed",
            LlmClient.mockEffectful((history, _, _) =>
              inputs.update(_ :+ history) *> ZIO.fail(error)
            )
          )
          last <- agent(
            "Last",
            LlmClient.mockEffectful((history, _, _) =>
              inputs.update(_ :+ history) *> laterFinished
                .succeed(())
                .as(TurnOutput.FinalText(Msg.assistant("last")))
            )
          )
          running <- ZIO.uninterruptible:
            for
              worker <- Pipeline
                .fanout(Chunk(first, failed, last), Chunk(Msg.user("shared")), 2)
                .interruptible
                .forkScoped
              _ <- ZIO.addFinalizer(release.succeed(()).unit)
            yield worker
          _            <- entered.await
          _            <- laterFinished.await
          pending      <- running.poll
          _            <- release.succeed(())
          results      <- running.join
          captured     <- inputs.get
          rejected     <- Pipeline.fanout(Chunk(first), Chunk(Msg.user("invalid")), 0).either
          unchanged    <- inputs.get
          cancellation <- cancellationCase
        yield assertTrue(
          pending.isEmpty,
          results.map(_.agentId) == Chunk(first.agentId, failed.agentId, last.agentId),
          results
            .lift(0)
            .exists(stage =>
              stage.outcome match
                case Exit.Success(result) => result.reply.text == "first"
                case _                    => false
            ),
          results.lift(1).flatMap(_.outcome.causeOption).exists(_.failures == List(error)),
          results
            .lift(2)
            .exists(stage =>
              stage.outcome match
                case Exit.Success(result) => result.reply.text == "last"
                case _                    => false
            ),
          captured.size == 3,
          captured.forall(_ == Chunk(Msg.user("shared"))),
          rejected.isLeft,
          unchanged == captured
        ) && cancellation
  ) @@ TestAspect.timeout(20.seconds)

  private def cancellationCase: ZIO[Scope, AgentError, TestResult] =
    ZIO.scoped:
      val defect = new IllegalStateException("joined pipeline model cleanup")
      for
        entered  <- Promise.make[Nothing, Unit]
        cleaning <- Promise.make[Nothing, Unit]
        release  <- Promise.make[Nothing, Unit]
        held <- agent(
          "Held",
          LlmClient.mockEffectful((_, _, _) =>
            ZIO.acquireRelease(entered.succeed(()))(_ =>
              cleaning.succeed(()) *> release.await *> ZIO.die(defect)
            ) *> ZIO.never
          )
        )
        running <- ZIO.uninterruptible:
          for
            worker <- Pipeline
              .fanout(Chunk(held), Chunk(Msg.user("cancel")), 1)
              .interruptible
              .forkScoped
            _ <- ZIO.addFinalizer(release.succeed(()).unit)
          yield worker
        _          <- entered.await
        controller <- ZIO.fiberId
        stopping <- ZIO.uninterruptible:
          for
            worker <- running.interruptAs(controller).interruptible.forkScoped
            _      <- ZIO.addFinalizer(release.succeed(()).unit)
          yield worker
        _       <- cleaning.await
        early   <- stopping.poll
        _       <- release.succeed(())
        stopped <- stopping.join
      yield assertTrue(
        early.isEmpty,
        stopped.causeOption.exists(cause =>
          cause.interruptors.contains(controller) && cause.defects == List(
            defect
          ) && cause.failures.isEmpty
        )
      )
