/* Copyright 2024-2026 WeisGnosis. Licensed under the Apache License, Version 2.0. */
package consumer.live

import com.weisgnosis.agents.core.agent.{
  AgentConfig,
  AgentRunState,
  AgentSnapshot,
  PendingActing,
  ReActAgent,
  SuppliedToolResult
}
import com.weisgnosis.agents.core.client.{
  LlmConfig,
  ResponsesLlmClient,
  StructuredOutputCapability,
  TemperaturePolicy
}
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.model.{Msg, ToolResultState}
import com.weisgnosis.agents.core.tool.{Tool, ToolBinding, ToolExecutionResult, ToolSchema, Toolkit}
import zio.*
import zio.http.Client
import zio.json.*

object LiveNativeResponses extends ZIOAppDefault:
  final private case class SumInput(a: Int, b: Int) derives JsonCodec, ToolSchema
  final private case class Receipt(
      profile: String,
      model: String,
      completed: Boolean,
      calls: Int,
      paired: Boolean,
      nativeReplay: Boolean,
      checkpointRoundTrip: Boolean,
      correctReply: Boolean,
      usageReported: Boolean
  ) derives JsonCodec

  private def required(name: String): IO[AgentError, String] =
    System
      .env(name)
      .mapError(_ => AgentError.InvalidConfiguration("Cannot read live configuration"))
      .flatMap(value =>
        ZIO
          .fromOption(value.filterNot(_.isBlank))
          .orElseFail(AgentError.InvalidConfiguration(s"Missing $name"))
      )

  private def emit(receipt: Receipt): IO[Throwable, Unit] =
    Console.printLine(receipt.toJson) *> ZIO
      .fail(AgentError.InvalidMessage("Live Responses assertions failed"))
      .unless(
        receipt.completed && receipt.calls == 1 && receipt.paired && receipt.nativeReplay && receipt.correctReply && receipt.usageReported && (receipt.profile != "native-checkpoint-resume" || receipt.checkpointRoundTrip)
      )
      .unit

  private def paired(history: Chunk[Msg]): Boolean =
    val calls   = history.flatMap(_.toolUses)
    val results = history.flatMap(_.toolResults)
    calls.size == 1 && results.size == 1 && calls
      .zip(results)
      .forall((call, result) =>
        call.id == result.id && call.name == "live_sum" && result.name
          .contains("live_sum") && result.state == ToolResultState.Success
      )

  private def exercise(http: Client, config: LlmConfig): ZIO[Scope, Throwable, Unit] =
    for
      client     <- ResponsesLlmClient.make(http)
      executions <- Ref.make(0)
      tool = Tool.derived[SumInput, Int]("live_sum", "Add two integers.", isReadOnly = true)(
        input =>
          if input.a == 2 && input.b == 3 then executions.update(_ + 1).as(5)
          else ZIO.fail(AgentError.InvalidMessage("Expected live_sum inputs 2 and 3"))
      )
      agent <- ReActAgent.make(
        client = client,
        toolkit = Toolkit.single(tool),
        llmConfig = config,
        config = AgentConfig(maxIterations = 4, timeout = 180.seconds)
      )
      result <- agent.run(
        Msg.user(
          "Call live_sum exactly once with a=2 and b=3. Then reply with only the returned number, 5. Do not call any other tool."
        )
      )
      count <- executions.get
      _ <- emit(
        Receipt(
          "native-local-tool",
          config.model,
          result.state == AgentRunState.Completed,
          count,
          paired(result.history),
          result.history.exists(_.metadata.contains(Msg.MetadataResponsesNative)),
          false,
          result.reply.text.trim == "5",
          result.usage.exists(_.outputTokens > 0)
        )
      )
      external <- ZIO.fromEither(ToolBinding.external[SumInput](tool.definition))
      original <- ReActAgent.make(
        client = client,
        toolkit = Toolkit.single(external),
        llmConfig = config,
        config = AgentConfig(maxIterations = 4, timeout = 180.seconds)
      )
      suspended <- original.run(
        Msg.user(
          "Call live_sum exactly once with a=2 and b=3. Wait for its result, then reply with only the returned number."
        )
      )
      _ <- ZIO
        .fail(AgentError.InvalidMessage("Real model did not suspend for external tool"))
        .unless(suspended.isSuspended)
      pending <- ZIO.fromEither(PendingActing.pendingCalls(suspended))
      proposed <- ZIO
        .fromOption(
          pending.headOption.filter(value =>
            pending.size == 1 && value.call.name == "live_sum" && value.call.input
              .as[SumInput] == Right(SumInput(2, 3))
          )
        )
        .orElseFail(AgentError.InvalidMessage("External proposal must be exactly live_sum(2,3)"))
      snapshot <- original.snapshot
      decoded <- ZIO.fromEither(
        snapshot.toJson.fromJson[AgentSnapshot].left.map(AgentError.DeserializationError(_))
      )
      restored <- ReActAgent.make(
        client = client,
        toolkit = Toolkit.single(external),
        llmConfig = config,
        config = AgentConfig(maxIterations = 4, timeout = 180.seconds)
      )
      _ <- restored.restoreSnapshot(decoded)
      resumed <- restored.resume(
        Chunk(SuppliedToolResult(proposed.callOrdinal, ToolExecutionResult("5")))
      )
      _ <- emit(
        Receipt(
          "native-checkpoint-resume",
          config.model,
          resumed.state == AgentRunState.Completed,
          resumed.history.flatMap(_.toolUses).size,
          paired(resumed.history),
          resumed.history.exists(_.metadata.contains(Msg.MetadataResponsesNative)),
          decoded == snapshot,
          resumed.reply.text.trim == "5",
          resumed.usage.exists(_.outputTokens > 0)
        )
      )
    yield ()

  def run: ZIO[Any, Throwable, Unit] =
    (for
      endpoint <- required("WEISGNOSIS_LIVE_ENDPOINT")
      model    <- required("WEISGNOSIS_LIVE_MODEL")
      key      <- required("WEISGNOSIS_LIVE_API_KEY")
      http     <- ZIO.service[Client]
      _ <- ZIO.scoped(
        exercise(
          http,
          LlmConfig(
            model,
            endpoint = endpoint,
            apiKey = Some(key),
            maxTokens = Some(4096),
            temperaturePolicy = TemperaturePolicy.Omit,
            structuredOutputCapability = StructuredOutputCapability.Unsupported
          )
        )
      )
    yield ()).provide(Client.default)
