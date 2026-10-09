/* Copyright 2024-2026 WeisGnosis. Licensed under the Apache License, Version 2.0. */
package com.weisgnosis.agents.core.client

import com.weisgnosis.agents.core.agent.{
  AgentRunState,
  AgentRunUpdate,
  PostReasoningHook,
  ReasoningDecision,
  ReActAgent,
  StructuredMode
}
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.event.{AgentEvent, ModelStreamContext}
import com.weisgnosis.agents.core.model.{ContentBlock, Msg, Role}
import com.weisgnosis.agents.core.tool.{Tool, ToolDef, Toolkit}
import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

object TokenBudgetSpec extends ZIOSpecDefault:
  private enum Provider:
    case OpenAI, OpenAIJson, Anthropic, Gemini

  private enum Delivery:
    case Ordinary, Streaming

  private val config: LlmConfig =
    LlmConfig("fixture", endpoint = "http://localhost/messages", maxTokens = Some(64))
  private val context: ModelStreamContext = ModelStreamContext("reply", "request")
  private val stopped: AgentRunState = AgentRunState.Stopped("Model output token budget exhausted")

  private def anthropic(kind: String, fields: String): String =
    s"event: $kind\ndata: {\"type\":\"$kind\",$fields}\n\n"

  private def wire(provider: Provider, arguments: String): String = provider match
    case Provider.OpenAI =>
      "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"partial\",\"tool_calls\":[{\"index\":0,\"id\":\"call\",\"type\":\"function\",\"function\":{\"name\":\"lookup\",\"arguments\":" + Json
        .Str(arguments)
        .toJson + "}}]}}]}\n\n" +
        "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"length\"}],\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":5}}\n\n" + "data: [DONE]\n\n"
    case Provider.OpenAIJson =>
      "{\"object\":\"chat.completion\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"partial\",\"tool_calls\":[{\"id\":\"call\",\"type\":\"function\",\"function\":{\"name\":\"lookup\",\"arguments\":" + Json
        .Str(arguments)
        .toJson + "}}]},\"finish_reason\":\"length\"}],\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":5}}"
    case Provider.Anthropic =>
      anthropic(
        "message_start",
        "\"message\":{\"role\":\"assistant\",\"content\":[],\"usage\":{\"input_tokens\":12,\"output_tokens\":0}}"
      ) +
        anthropic(
          "content_block_start",
          "\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"partial\"}"
        ) +
        anthropic("content_block_stop", "\"index\":0") +
        anthropic(
          "content_block_start",
          "\"index\":1,\"content_block\":{\"type\":\"tool_use\",\"id\":\"call\",\"name\":\"lookup\",\"input\":{}}"
        ) +
        anthropic(
          "content_block_delta",
          "\"index\":1,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":" + Json
            .Str(arguments)
            .toJson + "}"
        ) +
        anthropic("content_block_stop", "\"index\":1") +
        anthropic(
          "message_delta",
          "\"delta\":{\"stop_reason\":\"max_tokens\"},\"usage\":{\"output_tokens\":5}"
        ) +
        "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n"
    case Provider.Gemini =>
      "data: {\"candidates\":[{\"index\":0,\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"partial\"},{\"executableCode\":{\"language\":\"PYTHON\",\"code\":\"print(1)\"}}]},\"finishReason\":\"MAX_TOKENS\"}],\"usageMetadata\":{\"promptTokenCount\":12,\"candidatesTokenCount\":5}}\n\n"

  private def client(
      provider: Provider,
      body: String,
      requests: Ref[Int]
  ): IO[AgentError, LlmClient] =
    val http = HttpClientTestSupport.scripted(_ =>
      requests
        .update(_ + 1)
        .as(
          Response(
            headers = Headers(
              Header.ContentType
                .parse(
                  if provider == Provider.OpenAIJson then "application/json"
                  else "text/event-stream"
                )
                .toOption
                .toList
            ),
            body = Body.fromString(body)
          )
        )
    )
    provider match
      case Provider.OpenAI     => ChatCompletionsClient.make(http)
      case Provider.OpenAIJson => ChatCompletionsClient.makeNonStreaming(http)
      case Provider.Anthropic  => AnthropicLlmClient.make(http)
      case Provider.Gemini     => GeminiLlmClient.make(http, GeminiCodeExecution.Enabled)

  def spec: Spec[TestEnvironment & Scope, Any] = suite("TokenBudget")(
    test("explicit exhaustion stops the public provider-to-agent path without dispatch or rewrite"):
      ZIO
        .foreach(
          Chunk(Provider.OpenAI, Provider.OpenAIJson, Provider.Anthropic, Provider.Gemini).flatMap(
            provider =>
              (if provider == Provider.Gemini then Chunk("{}") else Chunk("{}", "{"))
                .flatMap(arguments =>
                  Chunk(Delivery.Ordinary, Delivery.Streaming).map((provider, arguments, _))
                )
          )
        ): (provider, arguments, delivery) =>
          ZIO.scoped:
            for
              requests   <- Ref.make(0)
              dispatched <- Ref.make(0)
              rewrites   <- Ref.make(0)
              model      <- client(provider, wire(provider, arguments), requests)
              lookup = Tool.make(
                ToolDef("lookup", "Fixture", Json.Obj("type" -> Json.Str("object")))
              )(_ => dispatched.update(_ + 1).as("unexpected"))
              tools <- ZIO.fromEither(
                if provider == Provider.Gemini then Right(Toolkit.empty) else Toolkit(lookup)
              )
              agent <- ReActAgent.make(
                client = model,
                toolkit = tools,
                llmConfig = config,
                postReasoningHook = PostReasoningHook((_, _) =>
                  rewrites.update(_ + 1).as(ReasoningDecision.Replace(Msg.assistant("completed")))
                )
              )
              updates <- delivery match
                case Delivery.Ordinary =>
                  agent.run(Msg.user("work")).map(result => Chunk(AgentRunUpdate.Result(result)))
                case Delivery.Streaming => agent.streamWithResult(Msg.user("work")).runCollect
              results = updates.collect { case AgentRunUpdate.Result(result) => result }
              events  = updates.collect { case AgentRunUpdate.Event(event) => event }
              result <- ZIO.fromEither(
                results.headOption
                  .toRight(AgentError.InvalidMessage("Missing streamed runtime result"))
              )
              history      <- agent.history
              requestCount <- requests.get
              toolCount    <- dispatched.get
              hookCount    <- rewrites.get
            yield assertTrue(
              result.state == stopped,
              result.reply.text == "partial",
              result.reply.content == Chunk(ContentBlock.Text("partial")),
              result.usage.exists(usage => usage.inputTokens == 12 && usage.outputTokens == 5),
              result.reply.metadata.keySet == Set("agentscope_stop_reason"),
              requestCount == 1,
              toolCount == 0,
              hookCount == 0,
              results.size == 1,
              !events.exists(_.isInstanceOf[AgentEvent.ToolCallEndEvent]),
              delivery == Delivery.Ordinary || (
                events.collect { case event: AgentEvent.TextBlockEndEvent =>
                  event.fullText
                } == Chunk("partial") &&
                  events.collect { case event: AgentEvent.ModelResponseEndEvent =>
                    event.completionTokens
                  } == Chunk(5) &&
                  events.count(_.isInstanceOf[AgentEvent.AgentEndEvent]) == 1 &&
                  updates.lastOption.contains(AgentRunUpdate.Result(result))
              ),
              history.lastOption.contains(result.reply),
              !history.exists(message =>
                message.toolUses.nonEmpty || message.toolResults.nonEmpty || message.role == Role.Tool
              )
            )
        .map(_.foldLeft(assertTrue(true))(_ && _))
    ,
    test("protocol EOF still fails and valid partial JSON cannot become structured success"):
      for
        failures <- ZIO.foreach(Chunk(Provider.OpenAI, Provider.Anthropic, Provider.Gemini)):
          provider =>
            ZIO.scoped:
              for
                requests <- Ref.make(0)
                body = wire(provider, "{")
                missing = provider match
                  case Provider.OpenAI => body.stripSuffix("data: [DONE]\n\n")
                  case Provider.Anthropic =>
                    body.stripSuffix("event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n")
                  case _ => body.replace(",\"finishReason\":\"MAX_TOKENS\"", "")
                model <- client(provider, missing, requests)
                hub   <- ZIO.acquireRelease(Hub.unbounded[AgentEvent])(_.shutdown)
                output <- model
                  .streamTurn(Chunk(Msg.user("work")), Chunk.empty, config, hub, context)
                  .either
              yield assertTrue(output.isLeft)
        structured <- ZIO.foreach(Chunk(StructuredMode.Native, StructuredMode.SyntheticTool)):
          mode =>
            for
              calls <- Ref.make(0)
              model = LlmClient.mockEffectful((_, _, _) =>
                calls.update(_ + 1).as(TurnOutput.Truncated(Msg.assistant("7")))
              )
              agent <- ReActAgent.make(client = model)
              result <- agent
                .runStructured[Int](
                  Chunk(Msg.user("number")),
                  Json.Obj("type" -> Json.Str("integer")),
                  mode
                )
                .either
              count <- calls.get
            yield assertTrue(
              result == Left(AgentError.ExecutionStopped("Model output token budget exhausted")),
              count == 1
            )
        rejected <- ZIO.foreach(
          Chunk(
            Msg.assistant(Chunk(ContentBlock.ToolUse("x", "lookup", Json.Obj()))),
            Msg.assistant("partial").withMetadata("agentscope_stop_reason", Json.Obj()),
            Msg.assistant("partial").withMetadata("anthropic_content", Json.Arr())
          )
        ): message =>
          for
            forged <- ReActAgent.make(client =
              LlmClient.mockFunction((_, _) => TurnOutput.Truncated(message))
            )
            result <- forged.run(Msg.user("work")).either
          yield result.isLeft
      yield (failures ++ structured).foldLeft(assertTrue(rejected.forall(identity)))(_ && _)
  )
