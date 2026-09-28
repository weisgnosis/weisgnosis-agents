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
package com.weisgnosis.agents.core.event

import com.weisgnosis.agents.core.model.ToolResultState
import zio.json.*
import zio.json.ast.Json

/**
 * Sealed algebraic hierarchy of 31 typed events emitted across an agent turn.
 *
 * Designed for real-time Server-Sent Events (SSE) streaming and telemetry.
 */
sealed trait AgentEvent:
  def source: Option[String]
  def withSource(src: String): AgentEvent

object AgentEvent:

  // 1. Agent Lifecycle
  final case class AgentStartEvent(
      sessionId: String,
      replyId: String,
      agentId: String,
      source: Option[String] = None
  ) extends AgentEvent:
    def withSource(src: String): AgentEvent = copy(source = Some(src))

  final case class AgentEndEvent(
      replyId: String,
      source: Option[String] = None
  ) extends AgentEvent:
    def withSource(src: String): AgentEvent = copy(source = Some(src))

  final case class AgentProgressEvent(
      message: String,
      currentStep: Int,
      totalSteps: Option[Int] = None,
      source: Option[String] = None
  ) extends AgentEvent:
    def withSource(src: String): AgentEvent = copy(source = Some(src))

  // 2. Text Streaming Deltas
  final case class TextBlockStartEvent(
      blockIndex: Int,
      source: Option[String] = None
  ) extends AgentEvent:
    def withSource(src: String): AgentEvent = copy(source = Some(src))

  final case class TextBlockDeltaEvent(
      delta: String,
      blockIndex: Int,
      source: Option[String] = None
  ) extends AgentEvent:
    def withSource(src: String): AgentEvent = copy(source = Some(src))

  final case class TextBlockEndEvent(
      blockIndex: Int,
      fullText: String,
      source: Option[String] = None
  ) extends AgentEvent:
    def withSource(src: String): AgentEvent = copy(source = Some(src))

  // 3. Reasoning / Thinking Deltas
  final case class ThinkingBlockStartEvent(
      blockIndex: Int,
      source: Option[String] = None
  ) extends AgentEvent:
    def withSource(src: String): AgentEvent = copy(source = Some(src))

  final case class ThinkingBlockDeltaEvent(
      delta: String,
      blockIndex: Int,
      source: Option[String] = None
  ) extends AgentEvent:
    def withSource(src: String): AgentEvent = copy(source = Some(src))

  final case class ThinkingBlockEndEvent(
      blockIndex: Int,
      fullThought: String,
      source: Option[String] = None
  ) extends AgentEvent:
    def withSource(src: String): AgentEvent = copy(source = Some(src))

  // 4. Tool Calls
  final case class ToolCallStartEvent(
      callId: String,
      name: String,
      blockIndex: Int,
      streamIndex: Option[Int] = None,
      source: Option[String] = None
  ) extends AgentEvent:
    def withSource(src: String): AgentEvent = copy(source = Some(src))

  final case class ToolCallDeltaEvent(
      callId: String,
      delta: String,
      blockIndex: Int,
      streamIndex: Option[Int] = None,
      source: Option[String] = None
  ) extends AgentEvent:
    def withSource(src: String): AgentEvent = copy(source = Some(src))

  final case class ToolCallEndEvent(
      callId: String,
      name: String,
      arguments: Json,
      blockIndex: Int,
      source: Option[String] = None
  ) extends AgentEvent:
    def withSource(src: String): AgentEvent = copy(source = Some(src))

  // 5. Tool Results
  final case class ToolResultStartEvent(
      callId: String,
      name: String,
      source: Option[String] = None
  ) extends AgentEvent:
    def withSource(src: String): AgentEvent = copy(source = Some(src))

  final case class ToolResultDeltaEvent(
      callId: String,
      delta: String,
      source: Option[String] = None
  ) extends AgentEvent:
    def withSource(src: String): AgentEvent = copy(source = Some(src))

  final case class ToolResultEndEvent(
      callId: String,
      name: String,
      output: String,
      state: ToolResultState,
      source: Option[String] = None
  ) extends AgentEvent:
    def withSource(src: String): AgentEvent = copy(source = Some(src))

  // 6. Raw Model Invocations
  final case class ModelRequestStartEvent(
      model: String,
      promptTokens: Int,
      source: Option[String] = None
  ) extends AgentEvent:
    def withSource(src: String): AgentEvent = copy(source = Some(src))

  final case class ModelResponseChunkEvent(
      chunk: String,
      source: Option[String] = None
  ) extends AgentEvent:
    def withSource(src: String): AgentEvent = copy(source = Some(src))

  final case class ModelResponseEndEvent(
      model: String,
      completionTokens: Int,
      totalTokens: Int,
      source: Option[String] = None
  ) extends AgentEvent:
    def withSource(src: String): AgentEvent = copy(source = Some(src))

  // 7. Subagent Operations
  final case class SubagentSpawnEvent(
      parentSessionId: String,
      childSessionId: String,
      agentId: String,
      label: Option[String] = None,
      source: Option[String] = None
  ) extends AgentEvent:
    def withSource(src: String): AgentEvent = copy(source = Some(src))

  final case class SubagentStartEvent(
      childSessionId: String,
      agentId: String,
      source: Option[String] = None
  ) extends AgentEvent:
    def withSource(src: String): AgentEvent = copy(source = Some(src))

  final case class SubagentEndEvent(
      childSessionId: String,
      agentId: String,
      status: String,
      source: Option[String] = None
  ) extends AgentEvent:
    def withSource(src: String): AgentEvent = copy(source = Some(src))

  final case class SubagentExposedEvent(
      subagentId: String,
      agentId: String,
      sessionId: String,
      label: Option[String] = None,
      source: Option[String] = None
  ) extends AgentEvent:
    def withSource(src: String): AgentEvent = copy(source = Some(src))

  // 8. Security & Human-in-the-Loop
  final case class PermissionAskEvent(
      requestId: String,
      toolName: String,
      input: Json,
      source: Option[String] = None
  ) extends AgentEvent:
    def withSource(src: String): AgentEvent = copy(source = Some(src))

  final case class PermissionDecisionEvent(
      requestId: String,
      allowed: Boolean,
      reason: Option[String] = None,
      source: Option[String] = None
  ) extends AgentEvent:
    def withSource(src: String): AgentEvent = copy(source = Some(src))

  // 9. External Execution
  final case class ExternalExecutionAskEvent(
      requestId: String,
      toolName: String,
      input: Json,
      source: Option[String] = None
  ) extends AgentEvent:
    def withSource(src: String): AgentEvent = copy(source = Some(src))

  final case class ExternalExecutionResultEvent(
      requestId: String,
      output: String,
      isError: Boolean,
      source: Option[String] = None
  ) extends AgentEvent:
    def withSource(src: String): AgentEvent = copy(source = Some(src))

  // 10. Context & Compaction
  final case class CompactionStartEvent(
      triggerTokens: Int,
      messageCount: Int,
      source: Option[String] = None
  ) extends AgentEvent:
    def withSource(src: String): AgentEvent = copy(source = Some(src))

  final case class CompactionEndEvent(
      evictedTokens: Int,
      remainingMessages: Int,
      source: Option[String] = None
  ) extends AgentEvent:
    def withSource(src: String): AgentEvent = copy(source = Some(src))

  final case class HintBlockEvent(
      hint: String,
      source: Option[String] = None
  ) extends AgentEvent:
    def withSource(src: String): AgentEvent = copy(source = Some(src))

  // 11. Errors & Custom Extensibility
  final case class ErrorEvent(
      message: String,
      code: Option[String] = None,
      source: Option[String] = None
  ) extends AgentEvent:
    def withSource(src: String): AgentEvent = copy(source = Some(src))

  final case class CustomEvent(
      kind: String,
      payload: Json,
      source: Option[String] = None
  ) extends AgentEvent:
    def withSource(src: String): AgentEvent = copy(source = Some(src))

  // Codecs for individual events
  given JsonCodec[AgentStartEvent]              = DeriveJsonCodec.gen
  given JsonCodec[AgentEndEvent]                = DeriveJsonCodec.gen
  given JsonCodec[AgentProgressEvent]           = DeriveJsonCodec.gen
  given JsonCodec[TextBlockStartEvent]          = DeriveJsonCodec.gen
  given JsonCodec[TextBlockDeltaEvent]          = DeriveJsonCodec.gen
  given JsonCodec[TextBlockEndEvent]            = DeriveJsonCodec.gen
  given JsonCodec[ThinkingBlockStartEvent]      = DeriveJsonCodec.gen
  given JsonCodec[ThinkingBlockDeltaEvent]      = DeriveJsonCodec.gen
  given JsonCodec[ThinkingBlockEndEvent]        = DeriveJsonCodec.gen
  given JsonCodec[ToolCallStartEvent]           = DeriveJsonCodec.gen
  given JsonCodec[ToolCallDeltaEvent]           = DeriveJsonCodec.gen
  given JsonCodec[ToolCallEndEvent]             = DeriveJsonCodec.gen
  given JsonCodec[ToolResultStartEvent]         = DeriveJsonCodec.gen
  given JsonCodec[ToolResultDeltaEvent]         = DeriveJsonCodec.gen
  given JsonCodec[ToolResultEndEvent]           = DeriveJsonCodec.gen
  given JsonCodec[ModelRequestStartEvent]       = DeriveJsonCodec.gen
  given JsonCodec[ModelResponseChunkEvent]      = DeriveJsonCodec.gen
  given JsonCodec[ModelResponseEndEvent]        = DeriveJsonCodec.gen
  given JsonCodec[SubagentSpawnEvent]           = DeriveJsonCodec.gen
  given JsonCodec[SubagentStartEvent]           = DeriveJsonCodec.gen
  given JsonCodec[SubagentEndEvent]             = DeriveJsonCodec.gen
  given JsonCodec[SubagentExposedEvent]         = DeriveJsonCodec.gen
  given JsonCodec[PermissionAskEvent]           = DeriveJsonCodec.gen
  given JsonCodec[PermissionDecisionEvent]      = DeriveJsonCodec.gen
  given JsonCodec[ExternalExecutionAskEvent]    = DeriveJsonCodec.gen
  given JsonCodec[ExternalExecutionResultEvent] = DeriveJsonCodec.gen
  given JsonCodec[CompactionStartEvent]         = DeriveJsonCodec.gen
  given JsonCodec[CompactionEndEvent]           = DeriveJsonCodec.gen
  given JsonCodec[HintBlockEvent]               = DeriveJsonCodec.gen
  given JsonCodec[ErrorEvent]                   = DeriveJsonCodec.gen
  given JsonCodec[CustomEvent]                  = DeriveJsonCodec.gen

  private def tagWithEvent(ast: Json, eventType: String): Json =
    ast.asObject match
      case Some(obj) => Json.Obj(obj.fields.prepended("event" -> Json.Str(eventType)))
      case None      => ast

  given JsonCodec[AgentEvent] = summon[JsonCodec[Json]].transformOrFail(
    json =>
      val obj = json.asObject.getOrElse(Json.Obj.empty)
      obj.get("event").flatMap(_.asString) match
        case Some("agent_start")               => json.as[AgentStartEvent]
        case Some("agent_end")                 => json.as[AgentEndEvent]
        case Some("agent_progress")            => json.as[AgentProgressEvent]
        case Some("text_block_start")          => json.as[TextBlockStartEvent]
        case Some("text_block_delta")          => json.as[TextBlockDeltaEvent]
        case Some("text_block_end")            => json.as[TextBlockEndEvent]
        case Some("thinking_block_start")      => json.as[ThinkingBlockStartEvent]
        case Some("thinking_block_delta")      => json.as[ThinkingBlockDeltaEvent]
        case Some("thinking_block_end")        => json.as[ThinkingBlockEndEvent]
        case Some("tool_call_start")           => json.as[ToolCallStartEvent]
        case Some("tool_call_delta")           => json.as[ToolCallDeltaEvent]
        case Some("tool_call_end")             => json.as[ToolCallEndEvent]
        case Some("tool_result_start")         => json.as[ToolResultStartEvent]
        case Some("tool_result_delta")         => json.as[ToolResultDeltaEvent]
        case Some("tool_result_end")           => json.as[ToolResultEndEvent]
        case Some("model_request_start")       => json.as[ModelRequestStartEvent]
        case Some("model_response_chunk")      => json.as[ModelResponseChunkEvent]
        case Some("model_response_end")        => json.as[ModelResponseEndEvent]
        case Some("subagent_spawn")            => json.as[SubagentSpawnEvent]
        case Some("subagent_start")            => json.as[SubagentStartEvent]
        case Some("subagent_end")              => json.as[SubagentEndEvent]
        case Some("subagent_exposed")          => json.as[SubagentExposedEvent]
        case Some("permission_ask")            => json.as[PermissionAskEvent]
        case Some("permission_decision")       => json.as[PermissionDecisionEvent]
        case Some("external_execution_ask")    => json.as[ExternalExecutionAskEvent]
        case Some("external_execution_result") => json.as[ExternalExecutionResultEvent]
        case Some("compaction_start")          => json.as[CompactionStartEvent]
        case Some("compaction_end")            => json.as[CompactionEndEvent]
        case Some("hint_block")                => json.as[HintBlockEvent]
        case Some("error")                     => json.as[ErrorEvent]
        case Some("custom")                    => json.as[CustomEvent]
        case other =>
          Left(s"Unknown agent event discriminator: ${other.getOrElse("missing 'event' field")}"),
    {
      case e: AgentStartEvent    => tagWithEvent(e.toJsonAST.getOrElse(Json.Null), "agent_start")
      case e: AgentEndEvent      => tagWithEvent(e.toJsonAST.getOrElse(Json.Null), "agent_end")
      case e: AgentProgressEvent => tagWithEvent(e.toJsonAST.getOrElse(Json.Null), "agent_progress")
      case e: TextBlockStartEvent =>
        tagWithEvent(e.toJsonAST.getOrElse(Json.Null), "text_block_start")
      case e: TextBlockDeltaEvent =>
        tagWithEvent(e.toJsonAST.getOrElse(Json.Null), "text_block_delta")
      case e: TextBlockEndEvent => tagWithEvent(e.toJsonAST.getOrElse(Json.Null), "text_block_end")
      case e: ThinkingBlockStartEvent =>
        tagWithEvent(e.toJsonAST.getOrElse(Json.Null), "thinking_block_start")
      case e: ThinkingBlockDeltaEvent =>
        tagWithEvent(e.toJsonAST.getOrElse(Json.Null), "thinking_block_delta")
      case e: ThinkingBlockEndEvent =>
        tagWithEvent(e.toJsonAST.getOrElse(Json.Null), "thinking_block_end")
      case e: ToolCallStartEvent =>
        tagWithEvent(e.toJsonAST.getOrElse(Json.Null), "tool_call_start")
      case e: ToolCallDeltaEvent =>
        tagWithEvent(e.toJsonAST.getOrElse(Json.Null), "tool_call_delta")
      case e: ToolCallEndEvent => tagWithEvent(e.toJsonAST.getOrElse(Json.Null), "tool_call_end")
      case e: ToolResultStartEvent =>
        tagWithEvent(e.toJsonAST.getOrElse(Json.Null), "tool_result_start")
      case e: ToolResultDeltaEvent =>
        tagWithEvent(e.toJsonAST.getOrElse(Json.Null), "tool_result_delta")
      case e: ToolResultEndEvent =>
        tagWithEvent(e.toJsonAST.getOrElse(Json.Null), "tool_result_end")
      case e: ModelRequestStartEvent =>
        tagWithEvent(e.toJsonAST.getOrElse(Json.Null), "model_request_start")
      case e: ModelResponseChunkEvent =>
        tagWithEvent(e.toJsonAST.getOrElse(Json.Null), "model_response_chunk")
      case e: ModelResponseEndEvent =>
        tagWithEvent(e.toJsonAST.getOrElse(Json.Null), "model_response_end")
      case e: SubagentSpawnEvent => tagWithEvent(e.toJsonAST.getOrElse(Json.Null), "subagent_spawn")
      case e: SubagentStartEvent => tagWithEvent(e.toJsonAST.getOrElse(Json.Null), "subagent_start")
      case e: SubagentEndEvent   => tagWithEvent(e.toJsonAST.getOrElse(Json.Null), "subagent_end")
      case e: SubagentExposedEvent =>
        tagWithEvent(e.toJsonAST.getOrElse(Json.Null), "subagent_exposed")
      case e: PermissionAskEvent => tagWithEvent(e.toJsonAST.getOrElse(Json.Null), "permission_ask")
      case e: PermissionDecisionEvent =>
        tagWithEvent(e.toJsonAST.getOrElse(Json.Null), "permission_decision")
      case e: ExternalExecutionAskEvent =>
        tagWithEvent(e.toJsonAST.getOrElse(Json.Null), "external_execution_ask")
      case e: ExternalExecutionResultEvent =>
        tagWithEvent(e.toJsonAST.getOrElse(Json.Null), "external_execution_result")
      case e: CompactionStartEvent =>
        tagWithEvent(e.toJsonAST.getOrElse(Json.Null), "compaction_start")
      case e: CompactionEndEvent => tagWithEvent(e.toJsonAST.getOrElse(Json.Null), "compaction_end")
      case e: HintBlockEvent     => tagWithEvent(e.toJsonAST.getOrElse(Json.Null), "hint_block")
      case e: ErrorEvent         => tagWithEvent(e.toJsonAST.getOrElse(Json.Null), "error")
      case e: CustomEvent        => tagWithEvent(e.toJsonAST.getOrElse(Json.Null), "custom")
    }
  )
