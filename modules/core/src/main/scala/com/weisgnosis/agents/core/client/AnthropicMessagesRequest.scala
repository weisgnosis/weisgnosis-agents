/* Copyright 2024-2026 WeisGnosis. Licensed under the Apache License, Version 2.0. */
package com.weisgnosis.agents.core.client

import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.json.JsonBoundary
import com.weisgnosis.agents.core.model.{ContentBlock, Msg, Role, ToolResultState}
import com.weisgnosis.agents.core.tool.ToolDef
import zio.Chunk
import zio.json.*
import zio.json.ast.Json

/** Native Messages encoding; canonical history is never rewritten into provider wire roles. */
private[client] object AnthropicMessagesRequest:
  def encode(
      history: Chunk[Msg],
      tools: Chunk[ToolDef],
      config: LlmConfig,
      webSearch: Option[AnthropicWebSearch] = None
  ): Either[AgentError, Json] =
    for
      _ <- check(config)
      _ <- Either.cond(
        webSearch.isEmpty || !tools.exists(_.name == "web_search"),
        (),
        AgentError.InvalidConfiguration(
          "Native web_search conflicts with a local tool of the same name"
        )
      )
      _ <- Either.cond(history.nonEmpty, (), AgentError.InvalidMessage("Messages history is empty"))
      system <- traverse(history.filter(_.role == Role.System))(
        _.content.foldLeft[Either[AgentError, Chunk[Json]]](Right(Chunk.empty)):
          case (previous, ContentBlock.Text(value)) => previous.map(_ :+ text(value))
          case _ => Left(AgentError.InvalidMessage("Anthropic System content must be text"))
      )
      projected <- traverse(history.filterNot(_.role == Role.System))(message(_, webSearch))
      messages = projected.flatten
      _ <- Either.cond(
        messages.nonEmpty,
        (),
        AgentError.InvalidMessage("Anthropic history has no replayable messages")
      )
      definitions <- traverse(tools)(tool)
      body = Json.Obj(
        Chunk(
          "model"      -> Json.Str(config.model),
          "max_tokens" -> Json.Num(config.maxTokens.getOrElse(1024)),
          "stream"     -> Json.Bool(true),
          "messages"   -> Json.Arr(coalesce(messages)),
          "system"     -> Json.Arr(system.flatten),
          "tools"      -> Json.Arr(definitions ++ Chunk.fromIterable(webSearch.map(_.declaration)))
        ) ++ options(config)
      )
      checked <- JsonBoundary.validate(body).left.map(AgentError.InvalidMessage(_))
      _ <- Either.cond(
        JsonBoundary.validUnicode(checked),
        (),
        AgentError.InvalidMessage("Malformed Unicode in Anthropic request")
      )
    yield checked

  private def check(config: LlmConfig): Either[AgentError, Unit] =
    Either.cond(
      !config.model.isBlank && config.maxTokens.forall(_ > 0) &&
        config.temperature.isFinite && config.temperature >= 0 && config.temperature <= 1 &&
        config.topP.forall(value => value.isFinite && value >= 0 && value <= 1) &&
        config.responseFormat.isEmpty && config.frequencyPenalty.isEmpty &&
        config.presencePenalty.isEmpty && config.seed.isEmpty && config.reasoningEffort.isEmpty &&
        config.thinkingBudget.forall(budget =>
          budget.tokens >= 1024 && budget.tokens < config.maxTokens.getOrElse(1024)
        ) &&
        (config.thinkingBudget.isEmpty || (config.temperature == 1 && config.topP.isEmpty &&
          config.toolChoice
            .forall(choice => choice == ToolChoice.Auto || choice == ToolChoice.None))),
      (),
      AgentError.InvalidConfiguration(
        "Unsupported or invalid Anthropic configuration; native responseFormat, penalties, seed and reasoningEffort are not supported; thinking needs temperature 1 and a budget >=1024 below maxTokens"
      )
    )

  private def options(config: LlmConfig): Chunk[(String, Json)] =
    Chunk("temperature" -> Json.Num(config.temperature)) ++
      Chunk.fromIterable(config.topP.map(value => "top_p" -> Json.Num(value))) ++
      Chunk.fromIterable(
        config.stopSequences.map(value =>
          "stop_sequences" -> Json.Arr(value.values.map(Json.Str(_)))
        )
      ) ++
      Chunk.fromIterable(
        config.thinkingBudget.map(value =>
          "thinking" -> Json.Obj(
            "type"          -> Json.Str("enabled"),
            "budget_tokens" -> Json.Num(value.tokens)
          )
        )
      ) ++
      Chunk.fromIterable(
        config.toolChoice
          .orElse(config.parallelToolCalls.map(_ => ToolChoice.Auto))
          .map(choice =>
            val fields: Chunk[(String, Json)] = choice match
              case ToolChoice.Auto     => Chunk("type" -> Json.Str("auto"))
              case ToolChoice.None     => Chunk("type" -> Json.Str("none"))
              case ToolChoice.Required => Chunk("type" -> Json.Str("any"))
              case ToolChoice.Specific(name) =>
                Chunk("type" -> Json.Str("tool"), "name" -> Json.Str(name))
            "tool_choice" -> Json.Obj(
              fields ++ Chunk.fromIterable(
                config.parallelToolCalls.map(value =>
                  "disable_parallel_tool_use" -> Json.Bool(value == ToolCallParallelism.Disabled)
                )
              )
            )
          )
      )

  private def tool(value: ToolDef): Either[AgentError, Json] =
    Either.cond(
      !value.name.isBlank && value.parametersSchema
        .isInstanceOf[Json.Obj] && value.schemaStrictness.isEmpty,
      Json.Obj(
        "name"         -> Json.Str(value.name),
        "description"  -> Json.Str(value.description),
        "input_schema" -> value.parametersSchema
      ),
      AgentError.InvalidConfiguration(
        "Anthropic tools need a name and object input schema; schemaStrictness is not supported by this profile"
      )
    )

  private def message(
      value: Msg,
      webSearch: Option[AnthropicWebSearch]
  ): Either[AgentError, Option[Json]] =
    (if value.role == Role.Assistant then AnthropicNativeReplay.content(value, webSearch)
     else Right(None)).flatMap:
      case Some(content) =>
        Right(Some(Json.Obj("role" -> Json.Str("assistant"), "content" -> Json.Arr(content))))
      case None => ordinaryMessage(value, webSearch)

  /** Incomplete observations are projected as data; canonical history remains unchanged. */
  private def ordinaryMessage(
      value: Msg,
      webSearch: Option[AnthropicWebSearch]
  ): Either[AgentError, Option[Json]] =
    for
      observed <- projectInterruptedHosted(value, webSearch)
      projected = observed.filterNot:
        case ContentBlock.Thinking(text, metadata) if value.role == Role.Assistant =>
          metadata == Map("anthropic_incomplete_thinking" -> Json.Bool(true)) &&
          JsonBoundary.validUnicode(Json.Str(text))
        case _ => false
      output <-
        if projected.isEmpty && value.content.nonEmpty then Right(None)
        else
          traverse(projected)(block(_, value.role)).flatMap(content =>
            Either.cond(
              content.flatten.nonEmpty,
              Some(
                Json.Obj(
                  "role" -> Json.Str(if value.role == Role.Assistant then "assistant" else "user"),
                  "content" -> Json.Arr(content.flatten)
                )
              ),
              AgentError.InvalidMessage("Anthropic message has no supported content")
            )
          )
    yield output

  private def projectInterruptedHosted(
      value: Msg,
      webSearch: Option[AnthropicWebSearch]
  ): Either[AgentError, Chunk[ContentBlock]] =
    val hosted = value.content.filter:
      case call: ContentBlock.ToolUse      => call.isServerTool
      case result: ContentBlock.ToolResult => result.isServerTool
      case _                               => false
    if hosted.isEmpty then Right(value.content)
    else
      for
        _ <- Either.cond(
          value.role == Role.Assistant && webSearch.nonEmpty &&
            value.metadata.get("anthropic_interrupted_stream").contains(Json.Bool(true)),
          (),
          AgentError.InvalidMessage(
            "Hosted partial replay requires an interrupted native search observation"
          )
        )
        wire <- traverse(hosted):
          case call: ContentBlock.ToolUse =>
            Right(
              Json.Obj(
                "type"  -> Json.Str("server_tool_use"),
                "id"    -> Json.Str(call.id),
                "name"  -> Json.Str(call.name),
                "input" -> call.input
              )
            )
          case result: ContentBlock.ToolResult =>
            result.metadata
              .get("anthropic_web_search_result")
              .flatMap(_.asObject)
              .toRight(
                AgentError.InvalidMessage(
                  "Hosted partial result lacks its original native observation"
                )
              )
          case _ => Left(AgentError.InvalidMessage("Unsupported hosted partial observation"))
        canonical <- AnthropicMessageStream.canonicalBlocks(wire, webSearch)
        _ <- Either.cond(
          canonical.map(normalizeHosted) == hosted.map(normalizeHosted),
          (),
          AgentError.InvalidMessage("Hosted partial observation differs from its native data")
        )
        projected <- traverse(value.content):
          case call: ContentBlock.ToolUse if call.isServerTool =>
            passiveObservation(
              "Interrupted provider-hosted proposal; execution outcome unknown",
              call.copy(content = None)
            )
          case result: ContentBlock.ToolResult if result.isServerTool =>
            passiveObservation("Received provider-hosted observation", result)
          case other => Right(other)
      yield projected

  private def normalizeHosted(value: ContentBlock): ContentBlock = value match
    case call: ContentBlock.ToolUse => call.copy(content = None)
    case other                      => other

  private def passiveObservation(
      description: String,
      value: ContentBlock
  ): Either[AgentError, ContentBlock] =
    for
      data <- JsonBoundary.decode(value.toJson).left.map(AgentError.InvalidMessage(_))
      _ <- Either.cond(
        JsonBoundary.validUnicode(data),
        (),
        AgentError.InvalidMessage("Malformed Unicode in hosted partial observation")
      )
    yield ContentBlock.Text(s"$description: ${JsonBoundary.render(data)}")

  private def block(value: ContentBlock, role: Role): Either[AgentError, Chunk[Json]] = value match
    case ContentBlock.Text(value) => Right(Chunk(text(value)))
    case ContentBlock.Thinking(value, metadata) if role == Role.Assistant =>
      metadata.get("anthropic_incomplete_thinking") match
        case Some(Json.Bool(true)) =>
          Left(
            AgentError.InvalidMessage(
              "Incomplete Anthropic thinking cannot be replayed as a signed block"
            )
          )
        case _ =>
          metadata.get("thoughtSignature") match
            case Some(Json.Str(signature)) if !signature.isBlank =>
              Right(
                Chunk(
                  Json.Obj(
                    "type"      -> Json.Str("thinking"),
                    "thinking"  -> Json.Str(value),
                    "signature" -> Json.Str(signature)
                  )
                )
              )
            case _ =>
              Left(
                AgentError.InvalidMessage("Anthropic thinking replay requires its exact signature")
              )
    case call: ContentBlock.ToolUse if role == Role.Assistant && !call.isServerTool =>
      Right(
        Chunk(
          Json.Obj(
            "type"  -> Json.Str("tool_use"),
            "id"    -> Json.Str(call.id),
            "name"  -> Json.Str(call.name),
            "input" -> call.input
          )
        )
      )
    case result: ContentBlock.ToolResult
        if role == Role.Tool && result.state != ToolResultState.Running =>
      traverse(result.output):
        case ContentBlock.Text(value) => Right(text(value))
        case _ => Left(AgentError.InvalidMessage("Anthropic tool results support text only"))
      .map(output =>
        Chunk(
          Json.Obj(
            "type"        -> Json.Str("tool_result"),
            "tool_use_id" -> Json.Str(result.id),
            "content"     -> Json.Arr(output),
            "is_error"    -> Json.Bool(result.state != ToolResultState.Success)
          )
        )
      )
    case _ =>
      Left(
        AgentError.InvalidMessage(
          "Unsupported Anthropic content or role; media, redacted thinking and hosted tools require another profile"
        )
      )

  private def text(value: String): Json =
    Json.Obj("type" -> Json.Str("text"), "text" -> Json.Str(value))

  private def coalesce(messages: Chunk[Json]): Chunk[Json] =
    messages.foldLeft(Chunk.empty[Json]): (previous, current) =>
      (previous.lastOption, current) match
        case (Some(left: Json.Obj), right: Json.Obj) if left.get("role") == right.get("role") =>
          val content = left.get("content").flatMap(_.asArray).getOrElse(Chunk.empty) ++
            right.get("content").flatMap(_.asArray).getOrElse(Chunk.empty)
          previous.dropRight(1) :+ Json.Obj(
            "role"    -> left.get("role").getOrElse(Json.Str("user")),
            "content" -> Json.Arr(content)
          )
        case _ => previous :+ current

  private def traverse[A, B](
      values: Chunk[A]
  )(f: A => Either[AgentError, B]): Either[AgentError, Chunk[B]] =
    values.foldLeft[Either[AgentError, Chunk[B]]](Right(Chunk.empty))((previous, value) =>
      for accumulated <- previous; next <- f(value) yield accumulated :+ next
    )
