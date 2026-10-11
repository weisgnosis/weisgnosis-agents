/* Copyright 2024-2026 WeisGnosis. Licensed under the Apache License, Version 2.0. */
package com.weisgnosis.agents.examples

import com.weisgnosis.agents.core.agent.*
import com.weisgnosis.agents.core.client.{
  ChatCompletionsClient,
  CompletionTransportLimits,
  LlmClient,
  LlmConfig
}
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.event.AgentEvent
import com.weisgnosis.agents.core.model.{ContentBlock, Msg, ToolHistory, ToolResultState}
import com.weisgnosis.agents.core.tool.{Tool, ToolDef, Toolkit}
import zio.*
import zio.http.Client
import zio.json.*
import zio.json.ast.Json

/** Explicitly enabled, matched public-runtime observations; inference timing is provider-dependent. */
object LiveRuntimeComparison extends ZIOAppDefault:
  private[examples] enum Scenario:
    case Text, Sequential, Parallel, Cancel, CooperativeCancel

    def cancels: Boolean = this == Cancel || this == CooperativeCancel

    def label: String = this match
      case CooperativeCancel => "cooperative-cancel"
      case _                 => toString.toLowerCase

  /** interruptionPreserved checks the selected contract: parent interruption or delivered cooperative result. */
  final private[examples] case class Receipt(
      implementation: String,
      scenario: String,
      model: String,
      firstVisibleMillis: Option[Double],
      totalMillis: Double,
      toolPhaseMillis: Option[Double],
      peakActive: Int,
      executions: Chunk[String],
      expectedReply: Boolean,
      scenarioAccepted: Boolean,
      state: String,
      failureCategory: Option[String],
      callsPersistedBeforeEntry: Option[Boolean],
      completedResultPreserved: Option[Boolean],
      unfinishedOnlyReconciled: Option[Boolean],
      cancellationJoinedCleanup: Option[Boolean],
      interruptionPreserved: Option[Boolean],
      recoveryExactlyOnce: Option[Boolean],
      historyComplete: Option[Boolean],
      cancelJoinedMillis: Option[Double],
      sameRuntimeContinuation: Option[Boolean],
      continuationMillis: Option[Double]
  ) derives JsonCodec

  final private case class Activity(
      active: Int = 0,
      peak: Int = 0,
      executions: Chunk[String] = Chunk.empty,
      persisted: Boolean = true,
      firstEntry: Option[Long] = None,
      lastExit: Option[Long] = None
  )

  private val names: Chunk[String] = Chunk("evidence_left", "evidence_right")
  private val systemPrompt: String =
    "You are an evidence assistant. Follow the user task exactly. Use tools when requested. After reading both values, reply with only their sum as digits."
  private val toolPrompt: String =
    "Call evidence_left and evidence_right exactly once each in one batch with empty arguments, then reply with only their sum."
  private val recovery: String =
    "I noticed that you have interrupted me. What can I do for you?"

  def run: ZIO[ZIOAppArgs, Throwable, Unit] = LiveProviderSmoke.configuration.flatMap:
    case None =>
      Console.printLine("Runtime comparison disabled; set WEISGNOSIS_LIVE_SMOKE=enabled to opt in.")
    case Some(config) =>
      for
        arguments <- getArgs
        selected <-
          if arguments.nonEmpty then ZIO.succeed(arguments)
          else
            System
              .env("WEISGNOSIS_COMPARISON_SCENARIO")
              .map(value => Chunk(value.getOrElse("text")))
        scenarios <- ZIO.foreach(selected)(parseScenario)
        timeout   <- LiveProviderSmoke.runTimeout
        _ <- ZIO
          .serviceWithZIO[Client](http =>
            ChatCompletionsClient
              .make(http, CompletionTransportLimits(responseBytes = 1024L * 1024))
              .flatMap(client =>
                ZIO.foreachDiscard(scenarios)(scenario =>
                  exercise(
                    client,
                    config.copy(temperature = 0, parallelToolCalls = None),
                    scenario,
                    timeout
                  )
                    .flatMap(receipt => Console.printLine(receipt.toJson))
                )
              )
          )
          .provide(Client.default)
      yield ()

  private def parseScenario(value: String): IO[AgentError, Scenario] = value match
    case "text"               => ZIO.succeed(Scenario.Text)
    case "sequential"         => ZIO.succeed(Scenario.Sequential)
    case "parallel"           => ZIO.succeed(Scenario.Parallel)
    case "cancel"             => ZIO.succeed(Scenario.Cancel)
    case "cooperative-cancel" => ZIO.succeed(Scenario.CooperativeCancel)
    case _ =>
      ZIO.fail(
        AgentError.InvalidConfiguration(
          "Comparison scenario must be text, sequential, parallel, cancel or cooperative-cancel"
        )
      )

  private[examples] def exercise(
      client: LlmClient,
      config: LlmConfig,
      scenario: Scenario,
      timeout: Duration
  ): IO[AgentError, Receipt] = ZIO.scoped:
    for
      firstVisible   <- Ref.make(Option.empty[Long])
      result         <- Ref.make(Option.empty[AgentResult])
      activity       <- Ref.make(Activity())
      ready          <- Promise.make[Nothing, ReActAgent]
      committed      <- Promise.make[Nothing, Unit]
      rightEntered   <- Promise.make[Nothing, Unit]
      cleanupEntered <- Promise.make[Nothing, Unit]
      releaseCleanup <- Promise.make[Nothing, Unit]
      cleaned        <- Ref.make(false)
      cancellationAt <- Ref.make(Option.empty[Long])
      firstTurn      <- Ref.make(true)
      tools = names.map: name =>
        Tool.make(
          ToolDef(
            name,
            "Read one independent fixed evidence number.",
            Json.Obj(
              "type"                 -> Json.Str("object"),
              "properties"           -> Json.Obj(),
              "additionalProperties" -> Json.Bool(false)
            ),
            isConcurrencySafe = true,
            isReadOnly = true
          )
        )((input: Json) =>
          if input != Json.Obj() then
            ZIO.fail(AgentError.ToolExecutionError(name, "Empty arguments required"))
          else
            ZIO.acquireReleaseWith(
              ready.await
                .flatMap(_.history)
                .flatMap(history =>
                  Clock.nanoTime.flatMap(now =>
                    activity.update(current =>
                      current.copy(
                        active = current.active + 1,
                        peak = current.peak.max(current.active + 1),
                        executions = current.executions :+ name,
                        persisted = current.persisted && validBatch(history.flatMap(_.toolUses)),
                        firstEntry = Some(current.firstEntry.fold(now)(_.min(now)))
                      )
                    )
                  )
                )
            )(_ =>
              (if scenario.cancels && name == "evidence_right" then
                 cleanupEntered.succeed(()) *> releaseCleanup.await *> cleaned.set(true)
               else ZIO.unit) *>
                Clock.nanoTime.flatMap(now =>
                  activity.update(current =>
                    current.copy(
                      active = current.active - 1,
                      lastExit = Some(current.lastExit.fold(now)(_.max(now)))
                    )
                  )
                )
            )(_ =>
              if scenario.cancels && name == "evidence_right" then
                rightEntered.succeed(()) *> ZIO.never
              else ZIO.sleep(500.millis).as(if name == "evidence_left" then "17" else "20")
            )
        )
      runtime <- ReActAgent
        .builder()
        .name("live-comparison")
        .systemPrompt(systemPrompt)
        .client(client)
        .llmConfig(config)
        .maxIterations(3)
        .timeout(timeout)
        .toolExecution(
          if scenario == Scenario.Sequential then ToolExecution.Sequential
          else ToolExecution.Parallel
        )
        .toolkit(
          if scenario == Scenario.Text then Toolkit.empty
          else tools.foldLeft(Toolkit.empty)((kit, tool) => kit.updated(tool))
        )
        .postReasoningHook(
          PostReasoningHook((_, message) =>
            firstTurn
              .getAndSet(false)
              .flatMap(initial =>
                if scenario != Scenario.Text && ((initial && !validBatch(
                    message.toolUses
                  )) || (!initial && message.toolUses.nonEmpty))
                then
                  ZIO.fail(
                    AgentError.ReasoningHookError(
                      "Expected one batch of both evidence tools, followed by a final reply"
                    )
                  )
                else ZIO.succeed(ReasoningDecision.Keep)
              )
          )
        )
        .localToolResultObserver(
          LocalToolResultObserver(observation =>
            if scenario.cancels && observation.call.name == "evidence_left" then
              ZIO
                .fail(AgentError.CompletionObservationError("Completed evidence was not persisted"))
                .unless(
                  observation.persisted.state == ToolResultState.Success && observation.persisted.outputText == "17" &&
                    observation.history.flatMap(_.toolResults).contains(observation.persisted)
                ) *> committed.succeed(()).unit
            else ZIO.unit
          )
        )
        .build
      _       <- ready.succeed(runtime)
      started <- Clock.nanoTime
      running <- ZIO.uninterruptibleMask: restore =>
        for
          worker <- restore(
            runtime
              .streamWithResult(
                Msg.user(
                  if scenario == Scenario.Text then "Reply with exactly READY." else toolPrompt
                )
              )
              .tap:
                case AgentRunUpdate.Event(event: AgentEvent.TextBlockDeltaEvent)
                    if event.delta.nonEmpty =>
                  Clock.nanoTime.flatMap(now => firstVisible.update(_.orElse(Some(now))))
                case AgentRunUpdate.Result(value) => result.set(Some(value))
                case _                            => ZIO.unit
              .runDrain
          ).forkScoped
          _ <- ZIO.addFinalizer(releaseCleanup.succeed(()).unit)
        yield worker
      completion <- (if scenario.cancels then
                       for
                         gate <- (committed.await *> rightEntered.await).raceEither(running.await)
                         _ <- gate match
                           case Left(_)                    => ZIO.unit
                           case Right(Exit.Failure(cause)) => ZIO.refailCause(cause)
                           case Right(Exit.Success(_)) =>
                             ZIO.fail(
                               AgentError.InvalidMessage("Run ended before cancellation barrier")
                             )
                         cancelStarted <- Clock.nanoTime
                         _             <- cancellationAt.set(Some(cancelStarted))
                         stopping <- ZIO.uninterruptibleMask: restore =>
                           for
                             worker <- restore(
                               if scenario == Scenario.CooperativeCancel then
                                 runtime.interrupt *> running.await
                               else running.interrupt
                             ).forkScoped
                             _ <- ZIO.addFinalizer(releaseCleanup.succeed(()).unit)
                           yield worker
                         _     <- cleanupEntered.await
                         early <- stopping.poll
                         _ <-
                           if scenario == Scenario.Cancel then
                             ZIO.fiberId.flatMap(running.interruptAsFork) *> ZIO.sleep(100.millis)
                           else ZIO.unit
                         _       <- releaseCleanup.succeed(())
                         exit    <- stopping.join
                         joined  <- Clock.nanoTime
                         cleanup <- cleaned.get
                       yield (exit, Some(millis(joined - cancelStarted)), early.isEmpty && cleanup)
                     else running.await.map(exit => (exit, Option.empty[Double], true)))
        .timeoutFail(AgentError.TimeoutError(timeout.toSeconds, "Comparison deadline exceeded"))(
          timeout
        )
        .exit
      finished          <- Clock.nanoTime
      observed          <- activity.get
      history           <- runtime.history
      output            <- result.get
      visible           <- firstVisible.get
      cancellationStart <- cancellationAt.get
      results    = history.flatMap(_.toolResults)
      recoveries = history.filter(_.generateReason.contains("interrupted"))
      preserved = history
        .flatMap(_.toolUses)
        .find(_.name == "evidence_left")
        .exists(call =>
          results.count(value =>
            value.id == call.id && value.name.contains("evidence_left") &&
              value.state == ToolResultState.Success && value.outputText == "17"
          ) == 1
        )
      reconciled = results.size == 2 && history
        .flatMap(_.toolUses)
        .find(_.name == "evidence_right")
        .exists(call =>
          results.count(value =>
            value.id == call.id && value.name
              .contains("evidence_right") && value.state == ToolResultState.Error
          ) == 1
        )
      recovered = recoveries.size == 1 && recoveries.forall(_.text == recovery)
      joined    = completion.toEither.toOption.exists(_._3) && observed.active == 0
      interrupted =
        if scenario == Scenario.CooperativeCancel then
          completion.toEither.toOption.exists(_._1.isSuccess) &&
          output.exists(_.state == AgentRunState.Interrupted)
        else
          completion.toEither.toOption.exists(
            _._1.causeOption.exists(cause =>
              cause.isInterrupted && cause.failures.isEmpty && cause.defects.isEmpty
            )
          )
      complete   = ToolHistory.inspect(history).isComplete
      successful = completion.toEither.toOption.exists(_._1.isSuccess)
      exact = output.exists(value =>
        value.state == AgentRunState.Completed && value.reply.text.trim == (if scenario == Scenario.Text
                                                                            then "READY"
                                                                            else "37")
      )
      continuation <-
        if scenario.cancels && interrupted then
          for
            begin     <- Clock.nanoTime
            continued <- runtime.run(Msg.user("Reply with exactly READY.")).exit
            end       <- Clock.nanoTime
            after     <- activity.get
          yield (
            Some(
              continued.toEither.toOption.exists(value =>
                value.state == AgentRunState.Completed && value.reply.text.trim == "READY" && ToolHistory
                  .inspect(value.history)
                  .isComplete && value.history.flatMap(_.toolResults) == results &&
                  value.history.filter(_.generateReason.contains("interrupted")) == recoveries
              ) && after == observed
            ),
            Some(millis(end - begin))
          )
        else ZIO.succeed((Option.empty[Boolean], Option.empty[Double]))
      expected =
        if scenario.cancels then
          interrupted && joined && preserved && reconciled && recovered && complete && observed.persisted && observed.peak == 2 && continuation._1
            .contains(true)
        else
          successful && exact && complete && observed.persisted && (scenario == Scenario.Text ||
            (results.size == 2 && history
              .flatMap(_.toolUses)
              .forall(call =>
                results.count(result =>
                  result.id == call.id && result.name.contains(call.name) &&
                    result.state == ToolResultState.Success && result.outputText ==
                    (if call.name == "evidence_left" then "17" else "20")
                ) == 1
              ))) && observed.executions.size == (if scenario == Scenario.Text
                                                  then 0
                                                  else 2) && observed.peak == (scenario match
            case Scenario.Text       => 0
            case Scenario.Sequential => 1
            case _                   => 2)
      failure =
        if expected then None
        else
          Some(
            if completion.causeOption.exists(_.isInterrupted) then "interrupted"
            else if !successful && !scenario.cancels then
              completion.causeOption
                .orElse(completion.toEither.toOption.flatMap(_._1.causeOption))
                .flatMap(_.failureOption)
                .fold("runtime_failure"):
                  case _: AgentError.ReasoningHookError   => "model_batch_contract"
                  case _: AgentError.ModelError           => "provider_failure"
                  case _: AgentError.DeserializationError => "response_deserialization"
                  case _: AgentError.TimeoutError         => "timeout"
                  case _                                  => "runtime_failure"
            else "contract_mismatch"
          )
    yield Receipt(
      "scala-zio-react",
      scenario.label,
      config.model,
      visible.map(now => millis(now - started)),
      millis(finished - started),
      observed.firstEntry.zip(observed.lastExit).map((begin, end) => millis(end - begin)),
      observed.peak,
      observed.executions,
      exact,
      expected,
      if interrupted then "Interrupted" else output.fold("Failed")(_.state.toString),
      failure,
      Option.when(observed.executions.size == 2 && observed.executions.toSet == names.toSet)(
        observed.persisted
      ),
      Option.when(cancellationStart.nonEmpty)(preserved),
      Option.when(cancellationStart.nonEmpty)(reconciled),
      Option.when(cancellationStart.nonEmpty)(joined),
      Option.when(cancellationStart.nonEmpty)(interrupted),
      Option.when(cancellationStart.nonEmpty)(recovered),
      Option.when(output.nonEmpty || history.flatMap(_.toolUses).nonEmpty)(complete),
      completion.toEither.toOption.flatMap(_._2),
      continuation._1,
      continuation._2
    )

  private def millis(nanos: Long): Double = nanos.toDouble / 1000000.0

  private def validBatch(calls: Chunk[ContentBlock.ToolUse]): Boolean =
    calls.size == 2 && calls.map(_.name).toSet == names.toSet && calls.forall(_.input == Json.Obj())
