/* Copyright 2024-2026 WeisGnosis. Licensed under the Apache License, Version 2.0. */
package com.weisgnosis.agents.examples

import com.weisgnosis.agents.core.agent.*
import com.weisgnosis.agents.core.client.{LlmClient, LlmConfig, TurnOutput}
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.model.*
import com.weisgnosis.agents.core.tool.{Tool, ToolDef, Toolkit}
import zio.*
import zio.json.*
import zio.json.ast.Json

/** Offline public-runtime contracts for a matched comparison, without inference or speed claims. */
object SyntheticRuntimeComparison extends ZIOAppDefault:
  final private[examples] case class Receipt(
      implementation: String,
      parallelOverlap: Boolean,
      callsPersistedBeforeEntry: Boolean,
      completedResultPreserved: Boolean,
      unfinishedOnlyReconciled: Boolean,
      cancellationJoinedCleanup: Boolean,
      interruptionPreserved: Boolean,
      recoveryExactlyOnce: Boolean,
      historyComplete: Boolean,
      sameRuntimeContinuation: Boolean
  ) derives JsonCodec:
    def allPassed: Boolean =
      parallelOverlap && callsPersistedBeforeEntry && completedResultPreserved &&
        unfinishedOnlyReconciled && cancellationJoinedCleanup && interruptionPreserved &&
        recoveryExactlyOnce && historyComplete && sameRuntimeContinuation

  final private case class Activity(
      active: Int = 0,
      peak: Int = 0,
      entries: Chunk[String] = Chunk.empty,
      persisted: Boolean = true
  )

  private val recoveryText: String =
    "I noticed that you have interrupted me. What can I do for you?"
  private val calls: Chunk[ContentBlock.ToolUse] = Chunk(
    ContentBlock.ToolUse("completed-call", "completed_read", Json.Obj()),
    ContentBlock.ToolUse("held-call", "held_read", Json.Obj())
  )

  def run: ZIO[Any, Throwable, Unit] =
    exercise.flatMap(receipt => Console.printLine(receipt.toJson))

  private[examples] def exercise: IO[AgentError, Receipt] =
    ZIO
      .scoped:
        for
          runtimeReady         <- Promise.make[Nothing, ReActAgent]
          heldEntered          <- Promise.make[Nothing, Unit]
          committed            <- Promise.make[Nothing, Unit]
          cleanupEntered       <- Promise.make[Nothing, Unit]
          releaseCleanup       <- Promise.make[Nothing, Unit]
          repeatedCancellation <- Promise.make[Nothing, Unit]
          cleaned              <- Ref.make(false)
          activity             <- Ref.make(Activity())
          enter = (name: String) =>
            runtimeReady.await
              .flatMap(_.history)
              .flatMap(history =>
                activity.update(current =>
                  current.copy(
                    active = current.active + 1,
                    peak = current.peak.max(current.active + 1),
                    entries = current.entries :+ name,
                    persisted = current.persisted && history.exists(_.toolUses == calls)
                  )
                )
              )
          completed = Tool.make(
            ToolDef(
              "completed_read",
              "Return fixed evidence",
              Json.Obj(),
              isConcurrencySafe = true,
              isReadOnly = true
            )
          )((_: Json) =>
            ZIO.acquireReleaseWith(enter("completed_read"))(_ =>
              activity.update(current => current.copy(active = current.active - 1))
            )(_ => heldEntered.await.as("completed-evidence"))
          )
          held = Tool.make(
            ToolDef(
              "held_read",
              "Hold until cancellation",
              Json.Obj(),
              isConcurrencySafe = true,
              isReadOnly = true
            )
          )((_: Json) =>
            ZIO.acquireReleaseWith(enter("held_read"))(_ =>
              cleanupEntered.succeed(()) *> releaseCleanup.await *> cleaned.set(true) *>
                activity.update(current => current.copy(active = current.active - 1))
            )(_ => heldEntered.succeed(()) *> ZIO.never)
          )
          client <- LlmClient.mock(
            Chunk(
              TurnOutput.ToolCalls(Msg.assistant(Chunk.fromIterable(calls))),
              TurnOutput.FinalText(Msg.assistant("continued-after-cancellation"))
            )
          )
          runtime <- ReActAgent.make(
            config =
              AgentConfig(name = "synthetic-comparison", maxIterations = 2, timeout = 20.seconds),
            toolkit = Toolkit.single(completed).updated(held),
            client = client,
            llmConfig = LlmConfig("scripted-offline"),
            localToolResultObserver = LocalToolResultObserver(observation =>
              if observation.call.id != "completed-call" then ZIO.unit
              else
                ZIO
                  .fail(
                    AgentError
                      .CompletionObservationError("Expected completed evidence was not persisted")
                  )
                  .unless(
                    observation.persisted.state == ToolResultState.Success &&
                      observation.persisted.outputText == "completed-evidence" &&
                      observation.history.flatMap(_.toolResults).contains(observation.persisted)
                  ) *> committed.succeed(()).unit
            )
          )
          _ <- runtimeReady.succeed(runtime)
          running <- ZIO.uninterruptibleMask: restore =>
            for
              worker <- restore(runtime.run(Msg.user("Run the two evidence reads"))).forkScoped
              _      <- ZIO.addFinalizer(releaseCleanup.succeed(()).unit)
            yield worker
          persisted <- committed.await.raceEither(running.await)
          _ <- persisted match
            case Left(_)                    => ZIO.unit
            case Right(Exit.Failure(cause)) => ZIO.refailCause(cause)
            case Right(Exit.Success(_)) =>
              ZIO.fail(
                AgentError.InvalidMessage("Run ended before completed evidence was persisted")
              )
          stopping <- ZIO.uninterruptibleMask: restore =>
            for
              worker <- restore(running.interrupt).forkScoped
              _      <- ZIO.addFinalizer(releaseCleanup.succeed(()).unit)
            yield worker
          _ <- cleanupEntered.await
          stoppingAgain <- ZIO.uninterruptibleMask: restore =>
            for
              worker <- restore(
                ZIO.fiberId.flatMap(running.interruptAsFork) *>
                  repeatedCancellation.succeed(()) *> running.await
              ).forkScoped
              _ <- ZIO.addFinalizer(releaseCleanup.succeed(()).unit)
            yield worker
          _             <- repeatedCancellation.await
          early         <- stopping.poll
          earlyAgain    <- stoppingAgain.poll
          beforeRelease <- cleaned.get
          _             <- releaseCleanup.succeed(())
          exit          <- stopping.join
          exitAgain     <- stoppingAgain.join
          history       <- runtime.history
          joined        <- cleaned.get
          observed      <- activity.get
          continued     <- runtime.run(Msg.user("Continue without repeating the evidence reads"))
          after         <- activity.get
          results    = history.flatMap(_.toolResults)
          recoveries = history.filter(_.generateReason.contains("interrupted"))
          receipt = Receipt(
            implementation = "scala-zio-react",
            parallelOverlap = observed.peak == 2,
            callsPersistedBeforeEntry =
              observed.persisted && observed.entries.toSet == Set("completed_read", "held_read"),
            completedResultPreserved = results.count(result =>
              result.id == "completed-call" && result.name.contains("completed_read") &&
                result.state == ToolResultState.Success && result.outputText == "completed-evidence"
            ) == 1,
            unfinishedOnlyReconciled = results.size == 2 && results.count(result =>
              result.id == "held-call" && result.name
                .contains("held_read") && result.state == ToolResultState.Error
            ) == 1,
            cancellationJoinedCleanup =
              early.isEmpty && earlyAgain.isEmpty && !beforeRelease && joined && observed.active == 0,
            interruptionPreserved = Chunk(exit, exitAgain).forall(
              _.causeOption.exists(cause =>
                cause.isInterrupted && cause.failures.isEmpty && cause.defects.isEmpty
              )
            ),
            recoveryExactlyOnce = recoveries.size == 1 && recoveries.forall(message =>
              message.text == recoveryText && message.metadata
                .get(Msg.MetadataGenerateReason)
                .contains(Json.Str("interrupted"))
            ),
            historyComplete = ToolHistory
              .inspect(history)
              .isComplete && ToolHistory.inspect(continued.history).isComplete,
            sameRuntimeContinuation = continued.state == AgentRunState.Completed &&
              continued.reply.text == "continued-after-cancellation" && after == observed &&
              continued.history.count(_.generateReason.contains("interrupted")) == 1
          )
          _ <- ZIO
            .fail(AgentError.InvalidMessage("Synthetic runtime comparison assertions failed"))
            .unless(receipt.allPassed)
        yield receipt
      .timeoutFail(AgentError.TimeoutError(20, "Synthetic runtime comparison watchdog expired"))(
        20.seconds
      )
