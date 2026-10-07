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
import com.weisgnosis.agents.core.model.{ChatUsage, ContentBlock, Msg}
import zio.Chunk
import zio.json.ast.Json

/** Immutable indexed assembly with distinct completed and interrupted materialization. */
final class StreamAccumulator private (state: StreamAccumulator.State):
  import StreamAccumulator.*

  private[client] def completionReason: Option[String] = state.finishReason

  /** Accepts protocol deltas without exposing an invalid intermediate accumulator. */
  def feed(chunk: LlmChunk): Either[AgentError, StreamAccumulator] =
    val next = chunk match
      case LlmChunk.UsageChunk(usage)       => Right(state.copy(usage = Some(usage)))
      case _ if state.finishReason.nonEmpty => Left("Content arrived after the finish marker")
      case LlmChunk.TextDelta(delta, index) =>
        validIndex(index).map(_ =>
          state.copy(
            textBlocks =
              state.textBlocks.updated(index, state.textBlocks.getOrElse(index, "") + delta),
            order = appendKey(ContentKey.Text(index))
          )
        )
      // A single plaintext channel keeps canonical block ordering aligned with published deltas.
      case _: LlmChunk.ThinkingDelta
          if state.reasoningDetails.exists(_.exists(_.plaintext.nonEmpty)) =>
        Left("Cannot mix scalar thinking and plaintext reasoning details")
      case LlmChunk.ThinkingDelta(delta, index, signature) =>
        validIndex(index).map: _ =>
          val previous = state.thinkingBlocks.getOrElse(index, ThinkingFragments())
          state.copy(
            thinkingBlocks = state.thinkingBlocks.updated(
              index,
              ThinkingFragments(
                previous.text + delta,
                (previous.signature, signature) match
                  case (None, None) => None
                  case _ => Some(previous.signature.getOrElse("") + signature.getOrElse(""))
              )
            ),
            order = appendKey(ContentKey.Thinking(index))
          )
      case LlmChunk.ReasoningDetailsDelta(details)
          if state.thinkingBlocks.nonEmpty && details.exists(_.plaintext.nonEmpty) =>
        Left("Cannot mix scalar thinking and plaintext reasoning details")
      case LlmChunk.ReasoningDetailsDelta(details) =>
        Right(
          state.copy(
            reasoningDetails = Some(state.reasoningDetails.getOrElse(Chunk.empty) ++ details),
            order = appendKey(ContentKey.ReasoningDetails)
          )
        )
      case tool: LlmChunk.ToolCallChunk => appendTool(tool)
      case LlmChunk.FinishReason(reason) =>
        Either.cond(reason.nonEmpty, state.copy(finishReason = Some(reason)), "Empty finish reason")
    next.left.map(AgentError.DeserializationError(_)).map(new StreamAccumulator(_))

  /** Requires successful protocol termination and valid complete tool arguments. */
  def toTurnOutput: Either[AgentError, TurnOutput] =
    for
      _ <- state.finishReason match
        case Some("stop") if state.toolCalls.isEmpty        => Right(())
        case Some("tool_calls") if state.toolCalls.nonEmpty => Right(())
        case Some(reason) =>
          Left(AgentError.DeserializationError(s"Incomplete or inconsistent completion: $reason"))
        case None =>
          Left(AgentError.DeserializationError("Completion ended without a finish marker"))
      tools <- orderedTools.foldLeft[Either[AgentError, Chunk[ContentBlock.ToolUse]]](
        Right(Chunk.empty)
      )((acc, tool) =>
        for
          previous <- acc
          id <- tool.callId.toRight(
            AgentError.DeserializationError("Completed tool call is missing its ID")
          )
          name <- tool.name.toRight(
            AgentError.DeserializationError("Completed tool call is missing its name")
          )
          arguments <- objectArguments(tool.arguments).left.map(error =>
            AgentError.DeserializationError(s"Invalid completed tool arguments: $error")
          )
        yield previous :+ ContentBlock.ToolUse(
          id,
          name,
          arguments,
          content = Some(tool.arguments),
          metadata = signatureMetadata(tool.thoughtSignature)
        )
      )
      _ <- Either.cond(
        tools.map(_.id).distinct.size == tools.size,
        (),
        AgentError.DeserializationError("Completed tool calls have duplicate IDs")
      )
      message = materialize(tools)
    yield
      if tools.nonEmpty then TurnOutput.ToolCalls(message)
      else if message.text.trim.nonEmpty then TurnOutput.FinalText(message)
      else if message.hasReasoning then TurnOutput.ThinkingOnly(message)
      else TurnOutput.FinalText(message)

  /** Preserves received data for reconciliation; incomplete arguments cannot become executable. */
  def toInterrupted: TurnOutput.Interrupted =
    val tools = orderedTools.map: tool =>
      val arguments = objectArguments(tool.arguments).toOption
      ContentBlock.ToolUse(
        tool.callId.getOrElse(s"stream_call_${tool.index}"),
        tool.name.getOrElse(""),
        arguments.getOrElse(Json.Obj()),
        content = Some(arguments.fold("{}")(_ => tool.arguments)),
        metadata = signatureMetadata(tool.thoughtSignature)
      )
    TurnOutput.Interrupted(materialize(tools))

  private def appendKey(key: ContentKey): Chunk[ContentKey] =
    if state.order.contains(key) then state.order else state.order :+ key

  private def appendTool(tool: LlmChunk.ToolCallChunk): Either[String, State] =
    val previous = state.toolCalls.getOrElse(tool.streamIndex, ToolFragments(tool.streamIndex))
    for
      _ <- validIndex(tool.streamIndex)
      _ <- Either.cond(
        tool.callId.forall(_.trim.nonEmpty) && tool.name.forall(_.trim.nonEmpty),
        (),
        "Tool identity fields must not be blank"
      )
      _ <- Either.cond(
        compatible(previous.callId, tool.callId),
        (),
        "Tool ID changed within one stream index"
      )
      _ <- Either.cond(
        compatible(previous.name, tool.name),
        (),
        "Tool name changed within one stream index"
      )
      _ <- Either.cond(
        tool.thoughtSignature.forall(value => !value.isBlank),
        (),
        "Tool thought signature must not be blank"
      )
      _ <- Either.cond(
        compatible(previous.thoughtSignature, tool.thoughtSignature),
        (),
        "Tool thought signature changed within one stream index"
      )
    yield state.copy(toolCalls =
      state.toolCalls.updated(
        tool.streamIndex,
        previous.copy(
          callId = tool.callId.orElse(previous.callId),
          name = tool.name.orElse(previous.name),
          arguments = previous.arguments + tool.argumentsDelta,
          thoughtSignature = tool.thoughtSignature.orElse(previous.thoughtSignature)
        )
      )
    )

  private def orderedTools: Chunk[ToolFragments] =
    Chunk.fromIterable(state.toolCalls.toList.sortBy(_._1).map(_._2))

  private def signatureMetadata(signature: Option[String]): Map[String, Json] =
    signature.fold(Map.empty[String, Json])(value => Map("thoughtSignature" -> Json.Str(value)))

  private def materialize(tools: Chunk[ContentBlock.ToolUse]): Msg =
    val content: Chunk[ContentBlock] = state.order.flatMap:
      case ContentKey.ReasoningDetails =>
        Chunk.fromIterable(
          state.reasoningDetails.map(details =>
            ContentBlock.Thinking(
              details.map(_.plaintext).mkString,
              Map("reasoningDetails" -> Json.Arr(details.map(_.value)))
            )
          )
        )
      case ContentKey.Text(index) =>
        Chunk.fromIterable(state.textBlocks.get(index).filter(_.nonEmpty).map(ContentBlock.Text(_)))
      case ContentKey.Thinking(index) =>
        Chunk.fromIterable(
          state.thinkingBlocks
            .get(index)
            .filter(value => value.text.nonEmpty || value.signature.nonEmpty)
            .map(value =>
              ContentBlock.Thinking(
                value.text,
                value.signature
                  .map(signature => Map("thoughtSignature" -> Json.Str(signature)))
                  .getOrElse(Map.empty)
              )
            )
        )
    Msg.assistant(content ++ tools, name = Some("assistant"), usage = state.usage)

object StreamAccumulator:
  private enum ContentKey:
    case Text(index: Int)
    case Thinking(index: Int)
    case ReasoningDetails

  final private case class ThinkingFragments(text: String = "", signature: Option[String] = None)

  final private case class ToolFragments(
      index: Int,
      callId: Option[String] = None,
      name: Option[String] = None,
      arguments: String = "",
      thoughtSignature: Option[String] = None
  )

  final private case class State(
      textBlocks: Map[Int, String] = Map.empty,
      thinkingBlocks: Map[Int, ThinkingFragments] = Map.empty,
      reasoningDetails: Option[Chunk[ReasoningDetail]] = None,
      order: Chunk[ContentKey] = Chunk.empty,
      toolCalls: Map[Int, ToolFragments] = Map.empty,
      usage: Option[ChatUsage] = None,
      finishReason: Option[String] = None
  )

  private def objectArguments(raw: String): Either[String, Json.Obj] =
    JsonBoundary
      .decode(raw)
      .flatMap:
        case value: Json.Obj => Right(value)
        case _               => Left("Function arguments must be a JSON object")

  private def validIndex(index: Int): Either[String, Unit] =
    Either.cond(index >= 0, (), "Stream index must be nonnegative")

  private def compatible(previous: Option[String], incoming: Option[String]): Boolean =
    previous.forall(value => incoming.forall(_ == value))

  val empty: StreamAccumulator = new StreamAccumulator(State())
