/*
 * Copyright 2024-2026 WeisGnosis (https://weisgnosis.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.weisgnosis.agents.core.client

import com.weisgnosis.agents.core.json.JsonBoundary
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.model.{ContentBlock, Msg, Role, ToolResultState}
import com.weisgnosis.agents.core.tool.ToolDef
import zio.Chunk
import zio.json.*
import zio.json.ast.Json

/** Explicit replay policy for the reasoning_content protocol extension. */
enum ReasoningReplay derives JsonCodec:
  case Omit, LatestAssistant

/** Pure serialization of the supported streaming text/function-call request surface. */
object ChatCompletionsRequest:
  private val namePattern = "[A-Za-z0-9_-]{1,64}".r

  def encode(
      history: Chunk[Msg],
      tools: Chunk[ToolDef],
      config: LlmConfig,
      reasoningReplay: ReasoningReplay = ReasoningReplay.Omit
  ): Either[AgentError, Json.Obj] =
    val lastAssistant = history.lastIndexWhere(_.role == Role.Assistant)
    for
      _ <- message(history.nonEmpty, "At least one message is required")
      _ <- validatePairing(history)
      _ <- configuration(config.model.trim.nonEmpty, "Model name must not be blank")
      _ <- configuration(
        config.temperature.isFinite && config.temperature >= 0 && config.temperature <= 2,
        "Temperature must be finite and between zero and two"
      )
      _ <- configuration(config.maxTokens.forall(_ > 0), "Maximum tokens must be positive")
      _ <- configuration(
        config.responseFormat.forall(_.isInstanceOf[Json.Obj]),
        "Response format must be an object"
      )
      _ <- configuration(
        tools.map(_.name).distinct.size == tools.size,
        "Tool definitions must have unique names"
      )
      definitions <- traverse(tools)(encodeDefinition)
      messages <- traverse(history.zipWithIndex): (msg, index) =>
        val replay = reasoningReplay match
          case ReasoningReplay.LatestAssistant if index == lastAssistant =>
            ReasoningReplay.LatestAssistant
          case _ => ReasoningReplay.Omit
        encodeMessage(msg, replay)
      body = Json.Obj(
        Chunk(
          "model"          -> Json.Str(config.model),
          "messages"       -> Json.Arr(messages.flatten),
          "stream"         -> Json.Bool(true),
          "stream_options" -> Json.Obj("include_usage" -> Json.Bool(true)),
          "temperature"    -> Json.Num(config.temperature)
        ) ++
          Chunk.fromIterable(config.maxTokens.map(value => "max_tokens" -> Json.Num(value))) ++
          Chunk.fromIterable(config.responseFormat.map(value => "response_format" -> value)) ++
          (if definitions.isEmpty then Chunk.empty else Chunk("tools" -> Json.Arr(definitions)))
      )
      _ <- JsonBoundary.validate(body).left.map(AgentError.InvalidConfiguration(_))
    yield body

  private def validatePairing(history: Chunk[Msg]): Either[AgentError, Unit] =
    history
      .foldLeft[Either[AgentError, Set[String]]](Right(Set.empty))((acc, msg) =>
        acc.flatMap: pending =>
          if msg.role == Role.Tool then
            msg.toolResults.foldLeft[Either[AgentError, Set[String]]](Right(pending))(
              (results, result) =>
                results.flatMap(ids =>
                  Either.cond(
                    ids.contains(result.id),
                    ids - result.id,
                    AgentError.InvalidMessage("Orphan or duplicate tool result")
                  )
                )
            )
          else
            message(
              pending.isEmpty,
              "Tool calls must receive results before the next conversation message"
            )
              .map(_ => msg.toolUses.map(_.id).toSet)
      )
      .flatMap(pending => message(pending.isEmpty, "Conversation ends with unresolved tool calls"))

  private def encodeDefinition(tool: ToolDef): Either[AgentError, Json] =
    for
      _ <- configuration(namePattern.matches(tool.name), "Invalid function tool name")
      _ <- configuration(
        tool.parametersSchema.isInstanceOf[Json.Obj],
        "Function parameters schema must be an object"
      )
    yield Json.Obj(
      "type" -> Json.Str("function"),
      "function" -> Json.Obj(
        "name"        -> Json.Str(tool.name),
        "description" -> Json.Str(tool.description),
        "parameters"  -> tool.parametersSchema
      )
    )

  private def encodeMessage(msg: Msg, replay: ReasoningReplay): Either[AgentError, Chunk[Json]] =
    msg.role match
      case Role.Tool =>
        for
          _ <- message(
            msg.content.nonEmpty && msg.content.forall(_.isInstanceOf[ContentBlock.ToolResult]),
            "Tool messages require explicit tool-result blocks"
          )
          results <- traverse(msg.toolResults)(encodeResult)
        yield results
      case Role.Assistant => encodeAssistant(msg, replay).map(Chunk(_))
      case Role.System | Role.User =>
        for
          _ <- message(
            msg.content.forall(_.isInstanceOf[ContentBlock.Text]),
            "This request codec only supports text in system/user messages"
          )
          name <- messageName(msg)
        yield Chunk(
          Json.Obj(
            Chunk(
              "role"    -> Json.Str(if msg.role == Role.System then "system" else "user"),
              "content" -> Json.Str(msg.text)
            ) ++ name
          )
        )

  private def encodeAssistant(msg: Msg, replay: ReasoningReplay): Either[AgentError, Json] =
    for
      _ <- message(
        msg.content.forall(assistantContent),
        "Unsupported assistant content for the text/function protocol"
      )
      _ <- message(
        msg.toolUses.map(_.id).distinct.size == msg.toolUses.size,
        "Assistant tool call IDs must be unique"
      )
      name  <- messageName(msg)
      calls <- traverse(msg.toolUses)(encodeCall)
      reasoning <- replay match
        case ReasoningReplay.Omit => Right(Chunk.empty)
        case ReasoningReplay.LatestAssistant =>
          for _ <- message(
              msg.content
                .collect { case thought: ContentBlock.Thinking => thought }
                .forall(_.metadata.isEmpty),
              "Signed or detailed reasoning requires a provider-specific replay codec"
            )
          yield
            if msg.thinking.isEmpty then Chunk.empty
            else Chunk("reasoning_content" -> Json.Str(msg.thinking))
    yield Json.Obj(
      Chunk(
        "role"    -> Json.Str("assistant"),
        "content" -> Json.Str(msg.text)
      ) ++ name ++ reasoning ++
        (if calls.isEmpty then Chunk.empty else Chunk("tool_calls" -> Json.Arr(calls)))
    )

  private def encodeCall(call: ContentBlock.ToolUse): Either[AgentError, Json] =
    for
      _ <- message(
        call.id.trim.nonEmpty && namePattern.matches(call.name),
        "Tool calls require a nonblank ID and valid function name"
      )
      _ <- message(
        !call.isServerTool && !call.metadata.contains("thoughtSignature") && !call.metadata
          .contains("reasoningDetails"),
        "Server tools and signed calls require a provider-specific request codec"
      )
      _ <- message(call.input.isInstanceOf[Json.Obj], "Function call arguments must be an object")
      _ <- JsonBoundary.validate(call.input).left.map(AgentError.InvalidMessage(_))
    yield Json.Obj(
      "id"   -> Json.Str(call.id),
      "type" -> Json.Str("function"),
      "function" -> Json.Obj(
        "name"      -> Json.Str(call.name),
        "arguments" -> Json.Str(call.input.toJson)
      )
    )

  private def encodeResult(result: ContentBlock.ToolResult): Either[AgentError, Json] =
    for
      _ <- message(result.id.trim.nonEmpty, "Tool results require a call ID")
      _ <- message(
        !result.isServerTool && !result.isSuspended,
        "Server or suspended tool results require a different protocol"
      )
      _ <- message(
        result.output.forall(_.isInstanceOf[ContentBlock.Text]),
        "This request codec only supports text tool output"
      )
      output <- result.state match
        case ToolResultState.Success => Right(result.outputText)
        case ToolResultState.Error   => Right(classifiedOutput("ERROR", result.outputText))
        case ToolResultState.Interrupted =>
          Right(classifiedOutput("INTERRUPTED", result.outputText))
        case ToolResultState.Denied => Right(classifiedOutput("DENIED", result.outputText))
        case ToolResultState.Running =>
          Left(
            AgentError.InvalidMessage("In-flight tool results cannot be serialized as observations")
          )
    yield Json.Obj(
      "role"         -> Json.Str("tool"),
      "tool_call_id" -> Json.Str(result.id),
      "content"      -> Json.Str(output)
    )

  private def classifiedOutput(label: String, text: String): String =
    val prefix = s"[$label] "
    if text.startsWith(prefix) then text else prefix + text

  private def assistantContent(block: ContentBlock): Boolean = block match
    case _: ContentBlock.Text | _: ContentBlock.Thinking | _: ContentBlock.ToolUse => true
    case _                                                                         => false

  private def messageName(msg: Msg): Either[AgentError, Chunk[(String, Json)]] =
    message(msg.name.forall(namePattern.matches), "Invalid protocol message name")
      .map(_ => Chunk.fromIterable(msg.name.map(value => "name" -> Json.Str(value))))

  private def configuration(condition: Boolean, reason: String): Either[AgentError, Unit] =
    Either.cond(condition, (), AgentError.InvalidConfiguration(reason))

  private def message(condition: Boolean, reason: String): Either[AgentError, Unit] =
    Either.cond(condition, (), AgentError.InvalidMessage(reason))

  private def traverse[A, B](
      values: Chunk[A]
  )(f: A => Either[AgentError, B]): Either[AgentError, Chunk[B]] =
    values.foldLeft[Either[AgentError, Chunk[B]]](Right(Chunk.empty))((acc, value) =>
      for
        previous <- acc
        next     <- f(value)
      yield previous :+ next
    )
