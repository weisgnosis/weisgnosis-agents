/* Copyright 2024-2026 WeisGnosis. Licensed under the Apache License, Version 2.0. */
package com.weisgnosis.agents.core.client

import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.json.JsonBoundary
import com.weisgnosis.agents.core.model.{ContentBlock, Msg, Role, ToolResultState}
import com.weisgnosis.agents.core.tool.ToolDef
import zio.Chunk
import zio.json.ast.Json

/** Explicit host selection; model output cannot enable provider execution. */
enum GeminiCodeExecution:
  case Disabled, Enabled

private[client] object GeminiMessagesRequest:
  def encode(
      history: Chunk[Msg],
      tools: Chunk[ToolDef],
      config: LlmConfig,
      execution: GeminiCodeExecution
  ): Either[AgentError, Json] =
    for
      _ <- Either.cond(
        !config.model.isBlank && config.maxTokens.forall(_ > 0) &&
          config.configuredTemperature.forall(value =>
            value.isFinite && value >= 0 && value <= 2
          ) &&
          config.topP.forall(value => value.isFinite && value >= 0 && value <= 1) &&
          config.responseFormat.isEmpty && config.toolChoice.isEmpty && config.parallelToolCalls.isEmpty &&
          (tools.isEmpty || execution == GeminiCodeExecution.Disabled) &&
          tools.map(_.name).distinct.size == tools.size && tools.forall(tool =>
            validFunctionName(
              tool.name
            ) && tool.parametersSchema.asObject.nonEmpty && tool.schemaStrictness.isEmpty
          ) &&
          config.frequencyPenalty.isEmpty && config.presencePenalty.isEmpty && config.seed.isEmpty &&
          config.reasoningEffort.isEmpty && config.thinkingBudget.isEmpty,
        (),
        AgentError.InvalidConfiguration(
          "Gemini does not support combined local/hosted tools, native response formats, tool choice, parallel preferences, strictness, penalties, seed or reasoning settings in this profile"
        )
      )
      system <- traverse(history.filter(_.role == Role.System))(message =>
        textParts(message.content)
      )
      contents <- encodeHistory(history.filterNot(_.role == Role.System))
      _ <- Either.cond(
        contents.nonEmpty,
        (),
        AgentError.InvalidMessage("Gemini contents are empty")
      )
      generation = Json.Obj(
        Chunk(
          "maxOutputTokens" -> Json.Num(config.maxTokens.getOrElse(1024))
        ) ++ Chunk.fromIterable(
          config.configuredTemperature.map(value => "temperature" -> Json.Num(value))
        ) ++
          Chunk.fromIterable(config.topP.map(value => "topP" -> Json.Num(value))) ++
          Chunk.fromIterable(
            config.stopSequences.map(value =>
              "stopSequences" -> Json.Arr(value.values.map(Json.Str(_)))
            )
          )
      )
      body = Json.Obj(
        Chunk("contents" -> Json.Arr(contents), "generationConfig" -> generation) ++
          (if system.flatten.isEmpty then Chunk.empty
           else Chunk("systemInstruction" -> Json.Obj("parts" -> Json.Arr(system.flatten)))) ++
          (if tools.nonEmpty then
             Chunk(
               "tools" -> Json.Arr(
                 Json.Obj(
                   "functionDeclarations" -> Json.Arr(
                     tools.map(tool =>
                       Json.Obj(
                         "name"                 -> Json.Str(tool.name),
                         "description"          -> Json.Str(tool.description),
                         "parametersJsonSchema" -> tool.parametersSchema
                       )
                     )
                   )
                 )
               )
             )
           else if execution == GeminiCodeExecution.Enabled then
             Chunk("tools" -> Json.Arr(Json.Obj("codeExecution" -> Json.Obj())))
           else Chunk.empty)
      )
      checked <- JsonBoundary.validate(body).left.map(AgentError.InvalidMessage(_))
      _ <- Either.cond(
        JsonBoundary.validUnicode(checked),
        (),
        AgentError.InvalidMessage("Malformed Unicode in Gemini request")
      )
    yield checked

  def validFunctionName(value: String): Boolean =
    value.headOption.exists(character =>
      character >= 'a' && character <= 'z' || character >= 'A' && character <= 'Z' || character == '_'
    ) && value.length <= 64 && value.forall(character =>
      character >= 'a' && character <= 'z' || character >= 'A' && character <= 'Z' || character >= '0' && character <= '9' || character == '_' || character == '-'
    )

  private def encodeHistory(history: Chunk[Msg]): Either[AgentError, Chunk[Json]] =
    history
      .foldLeft[Either[AgentError, (Chunk[Json], Chunk[ContentBlock.ToolUse])]](
        Right(Chunk.empty -> Chunk.empty)
      )((previous, message) =>
        previous.flatMap((contents, pending) =>
          for
            parts <-
              if message.role == Role.Tool then
                traverse(message.content):
                  case result: ContentBlock.ToolResult =>
                    for
                      call <- pending
                        .find(_.id == result.id)
                        .toRight(
                          AgentError.InvalidMessage("Gemini tool result has no pending call")
                        )
                      _ <- Either.cond(
                        result.name.forall(_ == call.name) && !result.isServerTool && Set(
                          ToolResultState.Success,
                          ToolResultState.Error
                        ).contains(result.state),
                        (),
                        AgentError.InvalidMessage("Gemini tool result identity or state is invalid")
                      )
                      _ <- Either.cond(
                        result.output.forall { case _: ContentBlock.Text => true; case _ => false },
                        (),
                        AgentError.InvalidMessage("Gemini tool responses require text output")
                      )
                      response = Json.Obj(
                        (if result.state == ToolResultState.Error then "error"
                         else "output") -> Json.Str(result.outputText)
                      )
                    yield Json.Obj(
                      "functionResponse" -> Json.Obj(
                        Chunk("name" -> Json.Str(call.name), "response" -> response) ++ Chunk
                          .fromIterable(
                            call.metadata.get("gemini_function_id").map(value => "id" -> value)
                          )
                      )
                    )
                  case _ =>
                    Left(AgentError.InvalidMessage("Gemini tool messages require function results"))
              else if message.role == Role.Assistant && message.metadata.contains("gemini_parts")
              then GeminiMessageStream.replay(message)
              else textParts(message.content)
            _ <- Either
              .cond(parts.nonEmpty, (), AgentError.InvalidMessage("Gemini content is empty"))
            results = message.toolResults
            _ <- Either.cond(
              results.map(_.id).distinct.size == results.size,
              (),
              AgentError.InvalidMessage("Duplicate Gemini function response identity")
            )
            next =
              if message.role == Role.Tool then
                pending.filterNot(call => results.exists(_.id == call.id))
              else pending ++ message.toolUses.filterNot(_.isServerTool)
            _ <- Either.cond(
              message.role == Role.Tool || pending.isEmpty,
              (),
              AgentError.InvalidMessage("Unresolved Gemini function calls before next content")
            )
          yield appendContent(contents, message.role, parts) -> next
        )
      )
      .flatMap((contents, pending) =>
        Either.cond(
          pending.isEmpty,
          contents,
          AgentError.InvalidMessage("Unresolved Gemini function calls")
        )
      )

  private def appendContent(contents: Chunk[Json], role: Role, parts: Chunk[Json]): Chunk[Json] =
    val previous      = contents.lastOption.flatMap(_.asObject)
    val previousParts = previous.flatMap(_.get("parts")).flatMap(_.asArray)
    if role == Role.Tool && previous.exists(
        _.get("role").contains(Json.Str("user"))
      ) && previousParts.exists(_.forall(_.asObject.exists(_.get("functionResponse").nonEmpty)))
    then
      contents.dropRight(1) :+ Json.Obj(
        "role"  -> Json.Str("user"),
        "parts" -> Json.Arr(previousParts.getOrElse(Chunk.empty) ++ parts)
      )
    else
      contents :+ Json.Obj(
        "role"  -> Json.Str(if role == Role.Assistant then "model" else "user"),
        "parts" -> Json.Arr(parts)
      )

  private def textParts(blocks: Chunk[ContentBlock]): Either[AgentError, Chunk[Json]] =
    traverse(blocks):
      case ContentBlock.Text(value) => Right(Json.Obj("text" -> Json.Str(value)))
      case _ =>
        Left(
          AgentError.InvalidMessage(
            "Gemini profile requires text or exact native replay; raw media and thinking are not supported"
          )
        )

  private def traverse[A, B](
      values: Chunk[A]
  )(f: A => Either[AgentError, B]): Either[AgentError, Chunk[B]] =
    values.foldLeft[Either[AgentError, Chunk[B]]](Right(Chunk.empty))((previous, value) =>
      for accumulated <- previous; next <- f(value) yield accumulated :+ next
    )
