/* Copyright 2024-2026 WeisGnosis. Licensed under the Apache License, Version 2.0. */
package com.weisgnosis.agents.core.client

import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.json.JsonBoundary
import com.weisgnosis.agents.core.model.{ContentBlock, Msg, Role, ToolHistory, ToolResultState}
import com.weisgnosis.agents.core.tool.{ToolDef, ToolSchemaStrictness}
import zio.Chunk
import zio.json.ast.Json

private[client] object ResponsesRequest:
  import ResponsesNativeReplay.*

  def encode(
      history: Chunk[Msg],
      tools: Chunk[ToolDef],
      config: LlmConfig
  ): Either[AgentError, Json.Obj] =
    for
      _ <- configuration(
        history.nonEmpty && config.model.nonEmpty,
        "Responses requires history and an explicit model"
      )
      _ <- configuration(
        config.frequencyPenalty.isEmpty && config.presencePenalty.isEmpty && config.seed.isEmpty &&
          config.stopSequences.isEmpty && config.thinkingBudget.isEmpty && config.responseFormat.isEmpty,
        "Responses profile does not support penalties, seed, stop sequences, thinking budget or native response format"
      )
      _ <- configuration(
        config.maxTokens.forall(_ > 0) && config.configuredTemperature.forall(value =>
          value.isFinite && value >= 0 && value <= 2
        ) &&
          config.topP.forall(value => value.isFinite && value >= 0 && value <= 1),
        "Invalid Responses generation settings"
      )
      _ <- ensure(
        ToolHistory.inspect(history).isComplete,
        "Responses history requires complete local tool pairs"
      )
      _ <- configuration(
        tools.map(_.name).distinct.size == tools.size,
        "Responses tools require unique names"
      )
      definitions <- traverse(tools)(definition)
      choice      <- toolChoice(config.toolChoice, tools)
      input       <- traverse(history)(message(_, config.model))
      body = Json.Obj(
        Chunk(
          "model"   -> Json.Str(config.model),
          "input"   -> Json.Arr(input.flatten),
          "stream"  -> Json.Bool(true),
          "store"   -> Json.Bool(false),
          "include" -> Json.Arr(Json.Str("reasoning.encrypted_content"))
        ) ++ Chunk.fromIterable(
          config.configuredTemperature.map(value => "temperature" -> Json.Num(value))
        ) ++
          Chunk.fromIterable(
            config.maxTokens.map(value => "max_output_tokens" -> Json.Num(value))
          ) ++
          Chunk.fromIterable(config.topP.map(value => "top_p" -> Json.Num(value))) ++
          Chunk.fromIterable(
            config.reasoningEffort.map(value =>
              "reasoning" -> Json.Obj("effort" -> Json.Str(value match
                case ReasoningEffort.Low    => "low"
                case ReasoningEffort.Medium => "medium"
                case ReasoningEffort.High   => "high"))
            )
          ) ++ Chunk.fromIterable(
            config.parallelToolCalls.map(value =>
              "parallel_tool_calls" -> Json.Bool(value == ToolCallParallelism.Enabled)
            )
          ) ++
          Chunk.fromIterable(choice.map(value => "tool_choice" -> value)) ++
          (if definitions.isEmpty then Chunk.empty else Chunk("tools" -> Json.Arr(definitions)))
      )
      _ <- JsonBoundary.validate(body).left.map(AgentError.InvalidConfiguration(_))
      _ <- configuration(JsonBoundary.validUnicode(body), "Malformed Unicode in Responses request")
    yield body

  private def definition(tool: ToolDef): Either[AgentError, Json] =
    configuration(
      tool.name.matches("[A-Za-z0-9_-]{1,64}") && tool.parametersSchema.isInstanceOf[Json.Obj],
      "Invalid Responses function definition"
    )
      .map(_ =>
        Json.Obj(
          "type"        -> Json.Str("function"),
          "name"        -> Json.Str(tool.name),
          "description" -> Json.Str(tool.description),
          "parameters"  -> tool.parametersSchema,
          "strict"      -> Json.Bool(tool.schemaStrictness.contains(ToolSchemaStrictness.Strict))
        )
      )

  private def toolChoice(
      choice: Option[ToolChoice],
      tools: Chunk[ToolDef]
  ): Either[AgentError, Option[Json]] = choice match
    case None                  => Right(None)
    case Some(ToolChoice.Auto) => Right(Some(Json.Str("auto")))
    case Some(ToolChoice.None) => Right(Some(Json.Str("none")))
    case Some(ToolChoice.Required) =>
      configuration(tools.nonEmpty, "Required Responses choice requires tools").map(_ =>
        Some(Json.Str("required"))
      )
    case Some(ToolChoice.Specific(name)) =>
      configuration(tools.exists(_.name == name), "Unknown Responses function choice")
        .map(_ => Some(Json.Obj("type" -> Json.Str("function"), "name" -> Json.Str(name))))

  private def message(value: Msg, model: String): Either[AgentError, Chunk[Json]] =
    for
      _ <- ensure(
        !value.metadata.contains(Msg.MetadataResponsesIncompleteReplay),
        "Interrupted Responses reasoning requires an explicit host context replacement"
      )
      native <- ResponsesNativeReplay.content(value, model)
      result <- native match
        case Some(items) =>
          ensure(
            value.role == Role.Assistant,
            "Responses native replay must belong to an assistant"
          ).map(_ => items)
        case None =>
          value.role match
            case Role.Tool =>
              traverse(value.content):
                case result: ContentBlock.ToolResult =>
                  for _ <- ensure(
                      !result.isServerTool && result.state != ToolResultState.Running && result.output
                        .forall(_.isInstanceOf[ContentBlock.Text]),
                      "Unsupported Responses tool observation"
                    )
                  yield Json.Obj(
                    "type"    -> Json.Str("function_call_output"),
                    "call_id" -> Json.Str(result.id),
                    "output"  -> Json.Str(result.text)
                  )
                case _ => Left(invalid("Responses tool messages require text tool results"))
            case role =>
              traverse(value.content):
                case ContentBlock.Text(text) =>
                  Right(
                    Json.Obj(
                      "role" -> Json.Str(role match
                        case Role.System    => "system"
                        case Role.User      => "user"
                        case Role.Assistant => "assistant"
                        case Role.Tool      => "tool"),
                      "content" -> Json.Str(text)
                    )
                  )
                case call: ContentBlock.ToolUse if role == Role.Assistant =>
                  ensure(
                    !call.isServerTool && call.input
                      .isInstanceOf[Json.Obj] && !value.preservesToolArguments,
                    "Cannot synthesize protected Responses function history"
                  ).map(_ =>
                    Json.Obj(
                      "type"      -> Json.Str("function_call"),
                      "call_id"   -> Json.Str(call.id),
                      "name"      -> Json.Str(call.name),
                      "arguments" -> Json.Str(JsonBoundary.render(call.input))
                    )
                  )
                case _ =>
                  Left(
                    invalid(
                      "Responses profile supports text history and checked native reasoning only"
                    )
                  )
    yield result

  private def configuration(condition: Boolean, message: String): Either[AgentError, Unit] =
    Either.cond(condition, (), AgentError.InvalidConfiguration(message))
