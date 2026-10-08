/*
 * Copyright 2024-2026 WeisGnosis (https://weisgnosis.com)
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations.
 */
package com.weisgnosis.agents.examples

import com.weisgnosis.agents.core.agent.*
import com.weisgnosis.agents.core.client.{
  ChatCompletionsClient,
  CompletionTransportLimits,
  LlmClient,
  LlmConfig
}
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.model.*
import com.weisgnosis.agents.core.tool.{Tool, ToolDef, Toolkit}
import zio.*
import zio.http.Client
import zio.json.*
import zio.json.ast.Json

/** Opt-in selected-endpoint checks; cancellation covers an admitted local tool, not HTTP cancellation. */
object LiveProviderProfiles extends ZIOAppDefault:
  private[examples] enum Profile:
    case All, Native, Synthetic, Cancellation

  final private[examples] case class NumberResult(value: Int) derives JsonCodec

  final private[examples] case class CancellationVerification(
      exit: Exit[AgentError, AgentResult],
      history: Chunk[Msg],
      cleanupJoined: Boolean,
      executions: Int
  )

  final private[examples] case class Verification(
      native: AgentResult,
      synthetic: AgentResult,
      cancellation: CancellationVerification
  )

  private val numberSchema: Json = Json.Obj(
    "type" -> Json.Str("object"),
    "properties" -> Json.Obj(
      "value" -> Json.Obj("type" -> Json.Str("integer"), "const" -> Json.Num(7))
    ),
    "required"             -> Json.Arr(Json.Str("value")),
    "additionalProperties" -> Json.Bool(false)
  )

  def run: ZIO[Any, Throwable, Unit] = LiveProviderSmoke.configuration.flatMap:
    case None =>
      Console.printLine("Provider profiles disabled; set WEISGNOSIS_LIVE_SMOKE=enabled to opt in.")
    case Some(config) =>
      selectedProfile.flatMap(profile =>
        ZIO
          .serviceWithZIO[Client](http =>
            ChatCompletionsClient
              .make(http, CompletionTransportLimits(responseBytes = 1024L * 1024))
              .flatMap(exerciseProfile(_, config, profile))
              .flatMap(_ => ZIO.logInfo(s"Selected provider profile passed: $profile"))
          )
          .provide(Client.default)
      )

  private def selectedProfile: IO[AgentError, Profile] =
    System
      .env("WEISGNOSIS_LIVE_PROFILE")
      .mapError(_ => AgentError.InvalidConfiguration("Cannot read WEISGNOSIS_LIVE_PROFILE"))
      .flatMap:
        case None | Some("all")   => ZIO.succeed(Profile.All)
        case Some("native")       => ZIO.succeed(Profile.Native)
        case Some("synthetic")    => ZIO.succeed(Profile.Synthetic)
        case Some("cancellation") => ZIO.succeed(Profile.Cancellation)
        case Some(_) =>
          ZIO.fail(
            AgentError.InvalidConfiguration(
              "WEISGNOSIS_LIVE_PROFILE must be all, native, synthetic or cancellation"
            )
          )

  private[examples] def exerciseProfile(
      client: LlmClient,
      config: LlmConfig,
      profile: Profile
  ): IO[AgentError, Unit] = profile match
    case Profile.All          => exercise(client, config).unit
    case Profile.Native       => structured(client, config, StructuredMode.Native).unit
    case Profile.Synthetic    => structured(client, config, StructuredMode.SyntheticTool).unit
    case Profile.Cancellation => cancelLocalTool(client, config).unit

  /** All profiles use the same explicitly supplied client/configuration and actual ReAct execution. */
  private[examples] def exercise(
      client: LlmClient,
      config: LlmConfig
  ): IO[AgentError, Verification] =
    for
      native <- structured(client, config, StructuredMode.Native).tap(_ =>
        ZIO.logInfo("Native structured profile passed")
      )
      synthetic <- structured(client, config, StructuredMode.SyntheticTool).tap(_ =>
        ZIO.logInfo("Synthetic structured profile passed")
      )
      cancellation <- cancelLocalTool(client, config)
    yield Verification(native, synthetic, cancellation)

  private def agent(
      client: LlmClient,
      config: LlmConfig,
      toolkit: Toolkit = Toolkit.empty
  ): IO[AgentError, ReActAgent] =
    ReActAgent
      .builder()
      .client(client)
      .llmConfig(config.copy(maxTokens = Some(1024)))
      .toolkit(toolkit)
      .maxIterations(3)
      .timeout(1.minute)
      .build

  private def structured(
      client: LlmClient,
      config: LlmConfig,
      mode: StructuredMode
  ): IO[AgentError, AgentResult] = ZIO.scoped:
    for
      runtime <- agent(client, config)
      result <- runtime.runStructured[NumberResult](
        Chunk(
          Msg.user(
            "Return exactly the object {\"value\":7} using the supplied structured output mechanism."
          )
        ),
        numberSchema,
        mode
      )
      _ <- ZIO
        .fail(AgentError.InvalidMessage(s"$mode profile requires Completed and exact value 7"))
        .unless(
          result._1.state == AgentRunState.Completed && result._2 == NumberResult(7) &&
            result._1.reply.getStructuredData[Json] == Right(Json.Obj("value" -> Json.Num(7)))
        )
    yield result._1

  private def cancelLocalTool(
      client: LlmClient,
      config: LlmConfig
  ): IO[AgentError, CancellationVerification] = ZIO.scoped:
    for
      entered        <- Promise.make[Nothing, Unit]
      cleanupEntered <- Promise.make[Nothing, Unit]
      releaseCleanup <- Promise.make[Nothing, Unit]
      cleaned        <- Ref.make(false)
      executions     <- Ref.make(0)
      tool = Tool.make(
        ToolDef(
          "profile_hold",
          "Enter a host-controlled wait. Call exactly once with no arguments; the host cancels it.",
          Json.Obj(
            "type"                 -> Json.Str("object"),
            "properties"           -> Json.Obj(),
            "additionalProperties" -> Json.Bool(false)
          ),
          isReadOnly = true
        )
      )((input: Json) =>
        if input != Json.Obj() then
          ZIO.fail(AgentError.ToolExecutionError("profile_hold", "No arguments are accepted"))
        else
          ZIO.acquireReleaseWith(executions.update(_ + 1) *> entered.succeed(()))(_ =>
            cleanupEntered.succeed(()) *> releaseCleanup.await *> cleaned.set(true)
          )(_ => ZIO.never)
      )
      runtime <- agent(client, config, Toolkit.single(tool))
      running <- ZIO.uninterruptibleMask: restore =>
        for
          fiber <- restore(
            runtime.run(
              Msg.user("Call profile_hold exactly once with no arguments. Wait for its result.")
            )
          ).forkScoped
          _ <- ZIO.addFinalizer(releaseCleanup.succeed(()).unit)
        yield fiber
      ready <- entered.await.raceEither(running.await)
      _ <- ready match
        case Left(_)                    => ZIO.unit
        case Right(Exit.Failure(cause)) => ZIO.refailCause(cause)
        case Right(Exit.Success(_)) =>
          ZIO.fail(AgentError.InvalidMessage("Cancellation profile did not enter profile_hold"))
      stopping <- ZIO.uninterruptibleMask: restore =>
        for
          fiber <- restore(running.interrupt).forkScoped
          _     <- ZIO.addFinalizer(releaseCleanup.succeed(()).unit)
        yield fiber
      _       <- cleanupEntered.await
      early   <- stopping.poll
      _       <- releaseCleanup.succeed(())
      exit    <- stopping.join
      history <- runtime.history
      joined  <- cleaned.get
      count   <- executions.get
      results = history.flatMap(_.toolResults)
      calls   = history.flatMap(_.toolUses)
      recovered = history.lastOption.exists(message =>
        message.text == "I noticed that you have interrupted me. What can I do for you?" &&
          message.metadata.get(Msg.MetadataGenerateReason).contains(Json.Str("interrupted"))
      )
      _ <- ZIO
        .fail(
          AgentError.InvalidMessage(
            "Cancellation profile requires joined tool cleanup, Error observation and interrupted recovery"
          )
        )
        .unless(
          early.isEmpty && joined && count == 1 && exit.causeOption.exists(cause =>
            cause.isInterrupted && cause.failures.isEmpty && cause.defects.isEmpty
          ) &&
            calls.size == 1 && calls.forall(_.name == "profile_hold") &&
            results.size == 1 && results.forall(result =>
              result.id == calls.headOption.fold("")(_.id) && result.name
                .contains("profile_hold") && result.state == ToolResultState.Error
            ) && ToolHistory.inspect(history).isComplete && recovered
        )
    yield CancellationVerification(exit, history, joined, count)
