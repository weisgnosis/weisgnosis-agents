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
import com.weisgnosis.agents.core.model.{ContentBlock, Msg, ToolHistory, ToolResultState}
import com.weisgnosis.agents.core.tool.{Tool, ToolDef, Toolkit}
import zio.*
import zio.http.Client
import zio.json.*
import zio.json.ast.Json

/** Host gates measure local tool overlap, independently of inference latency. */
object LiveParallelTools extends ZIOAppDefault:
  final private[examples] case class Verification(
      peakActive: Int,
      releasedTools: Int,
      result: AgentResult
  )
  final private case class Activity(active: Int = 0, peak: Int = 0, released: Int = 0)

  private val names: Chunk[String]          = Chunk("evidence_left", "evidence_right")
  private val expected: Map[String, String] = Map("evidence_left" -> "17", "evidence_right" -> "20")

  def run: ZIO[Any, Throwable, Unit] = LiveProviderSmoke.configuration.flatMap:
    case None =>
      Console.printLine("Parallel tools disabled; set WEISGNOSIS_LIVE_SMOKE=enabled to opt in.")
    case Some(config) =>
      ZIO
        .serviceWithZIO[Client](http =>
          ChatCompletionsClient
            .make(http, CompletionTransportLimits(responseBytes = 1024L * 1024))
            .flatMap(exercise(_, config))
            .flatMap(_ =>
              ZIO.logInfo(
                "Live parallel tools passed: sequential peak=1, parallel peak=2; exact sum=37"
              )
            )
        )
        .provide(Client.default)

  private[examples] def exercise(client: LlmClient, config: LlmConfig): IO[AgentError, Unit] =
    exerciseMode(client, config, ToolExecution.Sequential) *>
      exerciseMode(client, config, ToolExecution.Parallel).unit

  private[examples] def exerciseMode(
      client: LlmClient,
      config: LlmConfig,
      mode: ToolExecution
  ): IO[AgentError, Verification] = ZIO.scoped:
    for
      timeout  <- LiveProviderSmoke.runTimeout
      entered  <- ZIO.foreach(names)(_ => Promise.make[Nothing, Unit])
      releases <- ZIO.foreach(names)(_ => Promise.make[Nothing, Unit])
      admitted <- Promise.make[Nothing, Chunk[String]]
      ready    <- Promise.make[Nothing, ReActAgent]
      first    <- Ref.make(true)
      activity <- Ref.make(Activity())
      tools = names.zipWithIndex.map: (name, index) =>
        Tool.make(
          ToolDef(
            name,
            "Read one independent fixed evidence number. Call once with no arguments in the same batch as the other evidence read.",
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
            ZIO.fail(AgentError.ToolExecutionError(name, "No arguments accepted"))
          else
            ZIO.acquireReleaseWith(
              activity.update(current =>
                current
                  .copy(active = current.active + 1, peak = current.peak.max(current.active + 1))
              )
            )(_ =>
              activity.update(current =>
                current.copy(active = current.active - 1, released = current.released + 1)
              )
            )(_ =>
              for
                runtime <- ready.await
                history <- runtime.history
                _ <- ZIO
                  .fail(
                    AgentError.InvalidMessage(
                      "Evidence calls were not persisted before local tool entry"
                    )
                  )
                  .unless(validBatch(history.flatMap(_.toolUses)))
                entry <- ZIO
                  .fromOption(entered.lift(index))
                  .orElseFail(AgentError.InvalidConfiguration("Missing entry gate"))
                release <- ZIO
                  .fromOption(releases.lift(index))
                  .orElseFail(AgentError.InvalidConfiguration("Missing release gate"))
                _ <- entry.succeed(())
                _ <- release.await
                output <- ZIO
                  .fromOption(expected.get(name))
                  .orElseFail(AgentError.InvalidConfiguration("Missing evidence value"))
              yield output
            )
        )
      runtime <- ReActAgent.make(
        AgentConfig(
          name = "parallel-evidence",
          maxIterations = 2,
          timeout = timeout,
          toolExecution = mode
        ),
        toolkit = tools.foldLeft(Toolkit.empty)((toolkit, tool) => toolkit.updated(tool)),
        client = client,
        llmConfig = config,
        postReasoningHook = PostReasoningHook((_, message) =>
          first
            .getAndSet(false)
            .flatMap: initial =>
              if initial && !validBatch(message.toolUses) then
                ZIO.fail(
                  AgentError.ReasoningHookError(
                    "Model did not produce the required single two-read batch; local concurrency was not exercised"
                  )
                )
              else if !initial && message.toolUses.nonEmpty then
                ZIO.fail(
                  AgentError.ReasoningHookError(
                    "Model repeated evidence calls instead of completing the sum task"
                  )
                )
              else if initial then
                admitted.succeed(message.toolUses.map(_.name)).as(ReasoningDecision.Keep)
              else ZIO.succeed(ReasoningDecision.Keep)
        )
      )
      _ <- ready.succeed(runtime)
      running <- runtime
        .run(
          Msg.user(
            "Call evidence_left and evidence_right exactly once each with no arguments in ONE assistant tool-call batch. " +
              "After both results, return only the JSON object {\"sum\": their numerical sum}. Do not guess the evidence numbers."
          )
        )
        .forkScoped
      admission <- admitted.await.raceEither(running.await)
      order <- admission match
        case Left(order)                => ZIO.succeed(order)
        case Right(Exit.Failure(cause)) => ZIO.refailCause(cause)
        case Right(Exit.Success(_)) =>
          ZIO.fail(AgentError.InvalidMessage("Evidence batch was not admitted"))
      control = mode match
        case ToolExecution.Parallel =>
          ZIO.foreachDiscard(entered)(_.await) *> ZIO.foreachDiscard(releases)(_.succeed(()))
        case ToolExecution.Sequential =>
          val gates = names.zip(entered.zip(releases)).toMap
          ZIO.foreachDiscard(order)(name =>
            ZIO
              .fromOption(gates.get(name))
              .orElseFail(AgentError.InvalidConfiguration("Missing sequential gate"))
              .flatMap((entry, release) => entry.await *> release.succeed(()))
          )
      _ <- control.timeoutFail(
        AgentError.InvalidMessage(s"$mode local tool entry watchdog expired")
      )(10.seconds)
      result   <- running.join
      observed <- activity.get
      results = result.history.flatMap(_.toolResults)
      _ <- ZIO
        .fail(
          AgentError.InvalidMessage(
            s"$mode evidence overlap, cleanup or exact sum assertions failed"
          )
        )
        .unless(
          result.state == AgentRunState.Completed &&
            result.reply.text.fromJson[Json] == Right(Json.Obj("sum" -> Json.Num(37))) &&
            observed.active == 0 && observed.released == 2 &&
            observed.peak == (if mode == ToolExecution.Parallel then 2 else 1) &&
            results.size == 2 && results.forall(result =>
              result.state == ToolResultState.Success && result.name
                .flatMap(expected.get)
                .contains(result.outputText)
            ) && ToolHistory.inspect(result.history).isComplete
        )
    yield Verification(observed.peak, observed.released, result)

  private def validBatch(calls: Chunk[ContentBlock.ToolUse]): Boolean =
    calls.size == 2 && calls.map(_.name).toSet == names.toSet && calls.forall(_.input == Json.Obj())
