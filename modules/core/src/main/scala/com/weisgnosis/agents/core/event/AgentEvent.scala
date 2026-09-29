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

import com.weisgnosis.agents.core.model.{ContentBlock, GenerateReason, Msg, ToolResultState}
import zio.Chunk
import zio.json.*
import zio.json.ast.Json

/**
 * Sealed algebraic hierarchy of 39 typed event payloads for agent turns.
 *
 * Designed for real-time Server-Sent Events (SSE) streaming and telemetry.
 */
@jsonDiscriminator("event")
sealed trait AgentEvent:
  def source: Option[String]
  def withSource(src: String): AgentEvent

object AgentEvent:

  // 1. Agent Lifecycle
  @jsonHint("agent_start")
  final case class AgentStartEvent(
      sessionId: String,
      replyId: String,
      agentId: String,
      source: Option[String] = None
  ) extends AgentEvent:
    def withSource(src: String): AgentEvent = copy(source = Some(src))

  object AgentStartEvent:
    given JsonCodec[AgentStartEvent] = taggedCodec("agent_start", PayloadCodecs.agentStartEvent)

  @jsonHint("agent_end")
  final case class AgentEndEvent(
      replyId: String,
      source: Option[String] = None
  ) extends AgentEvent:
    def withSource(src: String): AgentEvent = copy(source = Some(src))

  object AgentEndEvent:
    given JsonCodec[AgentEndEvent] = taggedCodec("agent_end", PayloadCodecs.agentEndEvent)

  @jsonHint("agent_progress")
  final case class AgentProgressEvent(
      message: String,
      currentStep: Int,
      totalSteps: Option[Int] = None,
      source: Option[String] = None
  ) extends AgentEvent:
    def withSource(src: String): AgentEvent = copy(source = Some(src))

  object AgentProgressEvent:
    given JsonCodec[AgentProgressEvent] =
      taggedCodec("agent_progress", PayloadCodecs.agentProgressEvent)

  // 2. Text Streaming Deltas
  @jsonHint("text_block_start")
  final case class TextBlockStartEvent(
      blockIndex: Int,
      source: Option[String] = None,
      context: Option[ModelStreamContext] = None
  ) extends AgentEvent:
    def withSource(src: String): AgentEvent = copy(source = Some(src))

  object TextBlockStartEvent:
    given JsonCodec[TextBlockStartEvent] =
      taggedCodec("text_block_start", PayloadCodecs.textBlockStartEvent)

  @jsonHint("text_block_delta")
  final case class TextBlockDeltaEvent(
      delta: String,
      blockIndex: Int,
      source: Option[String] = None,
      context: Option[ModelStreamContext] = None
  ) extends AgentEvent:
    def withSource(src: String): AgentEvent = copy(source = Some(src))

  object TextBlockDeltaEvent:
    given JsonCodec[TextBlockDeltaEvent] =
      taggedCodec("text_block_delta", PayloadCodecs.textBlockDeltaEvent)

  @jsonHint("text_block_end")
  final case class TextBlockEndEvent(
      blockIndex: Int,
      fullText: String,
      source: Option[String] = None,
      context: Option[ModelStreamContext] = None
  ) extends AgentEvent:
    def withSource(src: String): AgentEvent = copy(source = Some(src))

  object TextBlockEndEvent:
    given JsonCodec[TextBlockEndEvent] =
      taggedCodec("text_block_end", PayloadCodecs.textBlockEndEvent)

  // 3. Reasoning / Thinking Deltas
  @jsonHint("thinking_block_start")
  final case class ThinkingBlockStartEvent(
      blockIndex: Int,
      source: Option[String] = None,
      context: Option[ModelStreamContext] = None
  ) extends AgentEvent:
    def withSource(src: String): AgentEvent = copy(source = Some(src))

  object ThinkingBlockStartEvent:
    given JsonCodec[ThinkingBlockStartEvent] =
      taggedCodec("thinking_block_start", PayloadCodecs.thinkingBlockStartEvent)

  @jsonHint("thinking_block_delta")
  final case class ThinkingBlockDeltaEvent(
      delta: String,
      blockIndex: Int,
      source: Option[String] = None,
      context: Option[ModelStreamContext] = None
  ) extends AgentEvent:
    def withSource(src: String): AgentEvent = copy(source = Some(src))

  object ThinkingBlockDeltaEvent:
    given JsonCodec[ThinkingBlockDeltaEvent] =
      taggedCodec("thinking_block_delta", PayloadCodecs.thinkingBlockDeltaEvent)

  @jsonHint("thinking_block_end")
  final case class ThinkingBlockEndEvent(
      blockIndex: Int,
      fullThought: String,
      source: Option[String] = None,
      context: Option[ModelStreamContext] = None
  ) extends AgentEvent:
    def withSource(src: String): AgentEvent = copy(source = Some(src))

  object ThinkingBlockEndEvent:
    given JsonCodec[ThinkingBlockEndEvent] =
      taggedCodec("thinking_block_end", PayloadCodecs.thinkingBlockEndEvent)

  // 4. Tool Calls
  @jsonHint("tool_call_start")
  final case class ToolCallStartEvent(
      callId: String,
      name: String,
      blockIndex: Int,
      streamIndex: Option[Int] = None,
      source: Option[String] = None,
      context: Option[ModelStreamContext] = None
  ) extends AgentEvent:
    def withSource(src: String): AgentEvent = copy(source = Some(src))

  object ToolCallStartEvent:
    given JsonCodec[ToolCallStartEvent] =
      taggedCodec("tool_call_start", PayloadCodecs.toolCallStartEvent)

  @jsonHint("tool_call_delta")
  final case class ToolCallDeltaEvent(
      callId: String,
      delta: String,
      blockIndex: Int,
      streamIndex: Option[Int] = None,
      source: Option[String] = None,
      context: Option[ModelStreamContext] = None
  ) extends AgentEvent:
    def withSource(src: String): AgentEvent = copy(source = Some(src))

  object ToolCallDeltaEvent:
    given JsonCodec[ToolCallDeltaEvent] =
      taggedCodec("tool_call_delta", PayloadCodecs.toolCallDeltaEvent)

  @jsonHint("tool_call_end")
  final case class ToolCallEndEvent(
      callId: String,
      name: String,
      arguments: Json,
      blockIndex: Int,
      source: Option[String] = None,
      context: Option[ModelStreamContext] = None
  ) extends AgentEvent:
    def withSource(src: String): AgentEvent = copy(source = Some(src))

  object ToolCallEndEvent:
    given JsonCodec[ToolCallEndEvent] = taggedCodec("tool_call_end", PayloadCodecs.toolCallEndEvent)

  // 5. Tool Results
  @jsonHint("tool_result_start")
  final case class ToolResultStartEvent(
      callId: String,
      name: String,
      source: Option[String] = None,
      metadata: Map[String, Json] = Map.empty
  ) extends AgentEvent:
    def withSource(src: String): AgentEvent = copy(source = Some(src))

  object ToolResultStartEvent:
    given JsonCodec[ToolResultStartEvent] =
      taggedCodec("tool_result_start", PayloadCodecs.toolResultStartEvent)

  @jsonHint("tool_result_delta")
  final case class ToolResultDeltaEvent(
      callId: String,
      delta: String,
      source: Option[String] = None,
      metadata: Map[String, Json] = Map.empty
  ) extends AgentEvent:
    def withSource(src: String): AgentEvent = copy(source = Some(src))

  object ToolResultDeltaEvent:
    given JsonCodec[ToolResultDeltaEvent] =
      taggedCodec("tool_result_delta", PayloadCodecs.toolResultDeltaEvent)

  @jsonHint("tool_result_end")
  final case class ToolResultEndEvent(
      callId: String,
      name: String,
      output: String,
      state: ToolResultState,
      source: Option[String] = None,
      metadata: Map[String, Json] = Map.empty
  ) extends AgentEvent:
    def withSource(src: String): AgentEvent = copy(source = Some(src))

  object ToolResultEndEvent:
    given JsonCodec[ToolResultEndEvent] =
      taggedCodec("tool_result_end", PayloadCodecs.toolResultEndEvent)

  // 6. Raw Model Invocations
  @jsonHint("model_request_start")
  final case class ModelRequestStartEvent(
      model: String,
      promptTokens: Int,
      source: Option[String] = None,
      context: Option[ModelStreamContext] = None
  ) extends AgentEvent:
    def withSource(src: String): AgentEvent = copy(source = Some(src))

  object ModelRequestStartEvent:
    given JsonCodec[ModelRequestStartEvent] =
      taggedCodec("model_request_start", PayloadCodecs.modelRequestStartEvent)

  @jsonHint("model_response_chunk")
  final case class ModelResponseChunkEvent(
      chunk: String,
      source: Option[String] = None,
      context: Option[ModelStreamContext] = None
  ) extends AgentEvent:
    def withSource(src: String): AgentEvent = copy(source = Some(src))

  object ModelResponseChunkEvent:
    given JsonCodec[ModelResponseChunkEvent] =
      taggedCodec("model_response_chunk", PayloadCodecs.modelResponseChunkEvent)

  @jsonHint("model_response_end")
  final case class ModelResponseEndEvent(
      model: String,
      completionTokens: Int,
      totalTokens: Int,
      source: Option[String] = None,
      context: Option[ModelStreamContext] = None
  ) extends AgentEvent:
    def withSource(src: String): AgentEvent = copy(source = Some(src))

  object ModelResponseEndEvent:
    given JsonCodec[ModelResponseEndEvent] =
      taggedCodec("model_response_end", PayloadCodecs.modelResponseEndEvent)

  // 7. Subagent Operations
  @jsonHint("subagent_spawn")
  final case class SubagentSpawnEvent(
      parentSessionId: String,
      childSessionId: String,
      agentId: String,
      label: Option[String] = None,
      source: Option[String] = None
  ) extends AgentEvent:
    def withSource(src: String): AgentEvent = copy(source = Some(src))

  object SubagentSpawnEvent:
    given JsonCodec[SubagentSpawnEvent] =
      taggedCodec("subagent_spawn", PayloadCodecs.subagentSpawnEvent)

  @jsonHint("subagent_start")
  final case class SubagentStartEvent(
      childSessionId: String,
      agentId: String,
      source: Option[String] = None
  ) extends AgentEvent:
    def withSource(src: String): AgentEvent = copy(source = Some(src))

  object SubagentStartEvent:
    given JsonCodec[SubagentStartEvent] =
      taggedCodec("subagent_start", PayloadCodecs.subagentStartEvent)

  @jsonHint("subagent_end")
  final case class SubagentEndEvent(
      childSessionId: String,
      agentId: String,
      status: String,
      source: Option[String] = None
  ) extends AgentEvent:
    def withSource(src: String): AgentEvent = copy(source = Some(src))

  object SubagentEndEvent:
    given JsonCodec[SubagentEndEvent] = taggedCodec("subagent_end", PayloadCodecs.subagentEndEvent)

  @jsonHint("subagent_exposed")
  final case class SubagentExposedEvent(
      subagentId: String,
      agentId: String,
      sessionId: String,
      label: Option[String] = None,
      source: Option[String] = None
  ) extends AgentEvent:
    def withSource(src: String): AgentEvent = copy(source = Some(src))

  object SubagentExposedEvent:
    given JsonCodec[SubagentExposedEvent] =
      taggedCodec("subagent_exposed", PayloadCodecs.subagentExposedEvent)

  // 8. Security & Human-in-the-Loop
  @jsonHint("permission_ask")
  final case class PermissionAskEvent(
      requestId: String,
      toolName: String,
      input: Json,
      source: Option[String] = None
  ) extends AgentEvent:
    def withSource(src: String): AgentEvent = copy(source = Some(src))

  object PermissionAskEvent:
    given JsonCodec[PermissionAskEvent] =
      taggedCodec("permission_ask", PayloadCodecs.permissionAskEvent)

  @jsonHint("permission_decision")
  final case class PermissionDecisionEvent(
      requestId: String,
      allowed: Boolean,
      reason: Option[String] = None,
      source: Option[String] = None
  ) extends AgentEvent:
    def withSource(src: String): AgentEvent = copy(source = Some(src))

  object PermissionDecisionEvent:
    given JsonCodec[PermissionDecisionEvent] =
      taggedCodec("permission_decision", PayloadCodecs.permissionDecisionEvent)

  // 9. External Execution
  @jsonHint("external_execution_ask")
  final case class ExternalExecutionAskEvent(
      requestId: String,
      toolName: String,
      input: Json,
      source: Option[String] = None
  ) extends AgentEvent:
    def withSource(src: String): AgentEvent = copy(source = Some(src))

  object ExternalExecutionAskEvent:
    given JsonCodec[ExternalExecutionAskEvent] =
      taggedCodec("external_execution_ask", PayloadCodecs.externalExecutionAskEvent)

  @jsonHint("external_execution_result")
  final case class ExternalExecutionResultEvent(
      requestId: String,
      output: String,
      isError: Boolean,
      source: Option[String] = None
  ) extends AgentEvent:
    def withSource(src: String): AgentEvent = copy(source = Some(src))

  object ExternalExecutionResultEvent:
    given JsonCodec[ExternalExecutionResultEvent] =
      taggedCodec("external_execution_result", PayloadCodecs.externalExecutionResultEvent)

  // 10. Context & Compaction
  @jsonHint("compaction_start")
  final case class CompactionStartEvent(
      triggerTokens: Int,
      messageCount: Int,
      source: Option[String] = None
  ) extends AgentEvent:
    def withSource(src: String): AgentEvent = copy(source = Some(src))

  object CompactionStartEvent:
    given JsonCodec[CompactionStartEvent] =
      taggedCodec("compaction_start", PayloadCodecs.compactionStartEvent)

  @jsonHint("compaction_end")
  final case class CompactionEndEvent(
      evictedTokens: Int,
      remainingMessages: Int,
      source: Option[String] = None
  ) extends AgentEvent:
    def withSource(src: String): AgentEvent = copy(source = Some(src))

  object CompactionEndEvent:
    given JsonCodec[CompactionEndEvent] =
      taggedCodec("compaction_end", PayloadCodecs.compactionEndEvent)

  @jsonHint("hint_block")
  final case class HintBlockEvent(
      hint: String,
      source: Option[String] = None
  ) extends AgentEvent:
    def withSource(src: String): AgentEvent = copy(source = Some(src))

  object HintBlockEvent:
    given JsonCodec[HintBlockEvent] = taggedCodec("hint_block", PayloadCodecs.hintBlockEvent)

  // 11. Errors & Custom Extensibility
  @jsonHint("error")
  final case class ErrorEvent(
      message: String,
      code: Option[String] = None,
      source: Option[String] = None
  ) extends AgentEvent:
    def withSource(src: String): AgentEvent = copy(source = Some(src))

  object ErrorEvent:
    given JsonCodec[ErrorEvent] = taggedCodec("error", PayloadCodecs.errorEvent)

  @jsonHint("custom")
  final case class CustomEvent(
      kind: String,
      payload: Json,
      source: Option[String] = None
  ) extends AgentEvent:
    def withSource(src: String): AgentEvent = copy(source = Some(src))

  object CustomEvent:
    given JsonCodec[CustomEvent] = taggedCodec("custom", PayloadCodecs.customEvent)

  // Payload support is distinct from runtime emission, phase stopping, and resumption.
  @jsonHint("agent_result")
  final case class AgentResultEvent(
      result: Msg,
      source: Option[String] = None
  ) extends AgentEvent:
    def withSource(src: String): AgentEvent = copy(source = Some(src))

  object AgentResultEvent:
    given JsonCodec[AgentResultEvent] = taggedCodec("agent_result", PayloadCodecs.agentResultEvent)

  @jsonHint("data_block_start")
  final case class DataBlockStartEvent(
      replyId: String,
      blockId: String,
      source: Option[String] = None
  ) extends AgentEvent:
    def withSource(src: String): AgentEvent = copy(source = Some(src))

  object DataBlockStartEvent:
    given JsonCodec[DataBlockStartEvent] =
      taggedCodec("data_block_start", PayloadCodecs.dataBlockStartEvent)

  @jsonHint("data_block_delta")
  final case class DataBlockDeltaEvent(
      replyId: String,
      blockId: String,
      delta: String,
      source: Option[String] = None
  ) extends AgentEvent:
    def withSource(src: String): AgentEvent = copy(source = Some(src))

  object DataBlockDeltaEvent:
    given JsonCodec[DataBlockDeltaEvent] =
      taggedCodec("data_block_delta", PayloadCodecs.dataBlockDeltaEvent)

  @jsonHint("data_block_end")
  final case class DataBlockEndEvent(
      replyId: String,
      blockId: String,
      source: Option[String] = None
  ) extends AgentEvent:
    def withSource(src: String): AgentEvent = copy(source = Some(src))

  object DataBlockEndEvent:
    given JsonCodec[DataBlockEndEvent] =
      taggedCodec("data_block_end", PayloadCodecs.dataBlockEndEvent)

  @jsonHint("tool_result_data_delta")
  final case class ToolResultDataDeltaEvent(
      replyId: String,
      toolCallId: String,
      toolCallName: String,
      data: ContentBlock,
      metadata: Map[String, Json] = Map.empty,
      source: Option[String] = None
  ) extends AgentEvent:
    def withSource(src: String): AgentEvent = copy(source = Some(src))

  object ToolResultDataDeltaEvent:
    given JsonCodec[ToolResultDataDeltaEvent] =
      taggedCodec("tool_result_data_delta", PayloadCodecs.toolResultDataDeltaEvent)

  @jsonHint("exceed_max_iters")
  final case class ExceedMaxItersEvent(
      replyId: String,
      maxIters: Int,
      currentIter: Int,
      source: Option[String] = None
  ) extends AgentEvent:
    def withSource(src: String): AgentEvent = copy(source = Some(src))

  object ExceedMaxItersEvent:
    given JsonCodec[ExceedMaxItersEvent] =
      taggedCodec("exceed_max_iters", PayloadCodecs.exceedMaxItersEvent)

  @jsonHint("request_stop")
  final case class RequestStopEvent(
      reason: String,
      generateReason: GenerateReason = GenerateReason.MiddlewareStopRequested,
      source: Option[String] = None
  ) extends AgentEvent:
    def withSource(src: String): AgentEvent = copy(source = Some(src))

  object RequestStopEvent:
    given JsonCodec[RequestStopEvent] = taggedCodec("request_stop", PayloadCodecs.requestStopEvent)

  @jsonHint("all_tools_denied")
  final case class AllToolsDeniedEvent(
      deniedToolCalls: Chunk[ContentBlock.ToolUse] = Chunk.empty,
      source: Option[String] = None
  ) extends AgentEvent:
    def withSource(src: String): AgentEvent = copy(source = Some(src))

  object AllToolsDeniedEvent:
    given JsonCodec[AllToolsDeniedEvent] =
      taggedCodec("all_tools_denied", PayloadCodecs.allToolsDeniedEvent)

  final private case class StopPayload(
      reason: String,
      generateReason: Option[GenerateReason],
      source: Option[String]
  ) derives JsonCodec

  final private case class DeniedPayload(
      deniedToolCalls: Option[Chunk[ContentBlock.ToolUse]],
      source: Option[String]
  ) derives JsonCodec

  // Isolate payload derivation so the root never selects a public leaf codec that delegates back to it.
  private object PayloadCodecs:
    given agentResultEvent: JsonCodec[AgentResultEvent] = DeriveJsonCodec.gen[AgentResultEvent]
    given dataBlockStartEvent: JsonCodec[DataBlockStartEvent] =
      DeriveJsonCodec.gen[DataBlockStartEvent]
    given dataBlockDeltaEvent: JsonCodec[DataBlockDeltaEvent] =
      DeriveJsonCodec.gen[DataBlockDeltaEvent]
    given dataBlockEndEvent: JsonCodec[DataBlockEndEvent] = DeriveJsonCodec.gen[DataBlockEndEvent]
    given toolResultDataDeltaEvent: JsonCodec[ToolResultDataDeltaEvent] =
      DeriveJsonCodec.gen[ToolResultDataDeltaEvent]
    given exceedMaxItersEvent: JsonCodec[ExceedMaxItersEvent] =
      DeriveJsonCodec.gen[ExceedMaxItersEvent]
    given requestStopEvent: JsonCodec[RequestStopEvent] = JsonCodec(
      DeriveJsonEncoder.gen[RequestStopEvent],
      summon[JsonDecoder[StopPayload]].map(value =>
        RequestStopEvent(
          value.reason,
          value.generateReason.getOrElse(GenerateReason.MiddlewareStopRequested),
          value.source
        )
      )
    )
    given allToolsDeniedEvent: JsonCodec[AllToolsDeniedEvent] = JsonCodec(
      DeriveJsonEncoder.gen[AllToolsDeniedEvent],
      summon[JsonDecoder[DeniedPayload]].map(value =>
        AllToolsDeniedEvent(value.deniedToolCalls.getOrElse(Chunk.empty), value.source)
      )
    )
    given agentStartEvent: JsonCodec[AgentStartEvent] = DeriveJsonCodec.gen[AgentStartEvent]
    given agentEndEvent: JsonCodec[AgentEndEvent]     = DeriveJsonCodec.gen[AgentEndEvent]
    given agentProgressEvent: JsonCodec[AgentProgressEvent] =
      DeriveJsonCodec.gen[AgentProgressEvent]
    given textBlockStartEvent: JsonCodec[TextBlockStartEvent] =
      DeriveJsonCodec.gen[TextBlockStartEvent]
    given textBlockDeltaEvent: JsonCodec[TextBlockDeltaEvent] =
      DeriveJsonCodec.gen[TextBlockDeltaEvent]
    given textBlockEndEvent: JsonCodec[TextBlockEndEvent] = DeriveJsonCodec.gen[TextBlockEndEvent]
    given thinkingBlockStartEvent: JsonCodec[ThinkingBlockStartEvent] =
      DeriveJsonCodec.gen[ThinkingBlockStartEvent]
    given thinkingBlockDeltaEvent: JsonCodec[ThinkingBlockDeltaEvent] =
      DeriveJsonCodec.gen[ThinkingBlockDeltaEvent]
    given thinkingBlockEndEvent: JsonCodec[ThinkingBlockEndEvent] =
      DeriveJsonCodec.gen[ThinkingBlockEndEvent]
    given toolCallStartEvent: JsonCodec[ToolCallStartEvent] =
      DeriveJsonCodec.gen[ToolCallStartEvent]
    given toolCallDeltaEvent: JsonCodec[ToolCallDeltaEvent] =
      DeriveJsonCodec.gen[ToolCallDeltaEvent]
    given toolCallEndEvent: JsonCodec[ToolCallEndEvent] = DeriveJsonCodec.gen[ToolCallEndEvent]
    given toolResultStartEvent: JsonCodec[ToolResultStartEvent] =
      DeriveJsonCodec.gen[ToolResultStartEvent]
    given toolResultDeltaEvent: JsonCodec[ToolResultDeltaEvent] =
      DeriveJsonCodec.gen[ToolResultDeltaEvent]
    given toolResultEndEvent: JsonCodec[ToolResultEndEvent] =
      DeriveJsonCodec.gen[ToolResultEndEvent]
    given modelRequestStartEvent: JsonCodec[ModelRequestStartEvent] =
      DeriveJsonCodec.gen[ModelRequestStartEvent]
    given modelResponseChunkEvent: JsonCodec[ModelResponseChunkEvent] =
      DeriveJsonCodec.gen[ModelResponseChunkEvent]
    given modelResponseEndEvent: JsonCodec[ModelResponseEndEvent] =
      DeriveJsonCodec.gen[ModelResponseEndEvent]
    given subagentSpawnEvent: JsonCodec[SubagentSpawnEvent] =
      DeriveJsonCodec.gen[SubagentSpawnEvent]
    given subagentStartEvent: JsonCodec[SubagentStartEvent] =
      DeriveJsonCodec.gen[SubagentStartEvent]
    given subagentEndEvent: JsonCodec[SubagentEndEvent] = DeriveJsonCodec.gen[SubagentEndEvent]
    given subagentExposedEvent: JsonCodec[SubagentExposedEvent] =
      DeriveJsonCodec.gen[SubagentExposedEvent]
    given permissionAskEvent: JsonCodec[PermissionAskEvent] =
      DeriveJsonCodec.gen[PermissionAskEvent]
    given permissionDecisionEvent: JsonCodec[PermissionDecisionEvent] =
      DeriveJsonCodec.gen[PermissionDecisionEvent]
    given externalExecutionAskEvent: JsonCodec[ExternalExecutionAskEvent] =
      DeriveJsonCodec.gen[ExternalExecutionAskEvent]
    given externalExecutionResultEvent: JsonCodec[ExternalExecutionResultEvent] =
      DeriveJsonCodec.gen[ExternalExecutionResultEvent]
    given compactionStartEvent: JsonCodec[CompactionStartEvent] =
      DeriveJsonCodec.gen[CompactionStartEvent]
    given compactionEndEvent: JsonCodec[CompactionEndEvent] =
      DeriveJsonCodec.gen[CompactionEndEvent]
    given hintBlockEvent: JsonCodec[HintBlockEvent] = DeriveJsonCodec.gen[HintBlockEvent]
    given errorEvent: JsonCodec[ErrorEvent]         = DeriveJsonCodec.gen[ErrorEvent]
    given customEvent: JsonCodec[CustomEvent]       = DeriveJsonCodec.gen[CustomEvent]

  private lazy val wireCodec: JsonCodec[AgentEvent] =
    import PayloadCodecs.given
    DeriveJsonCodec.gen[AgentEvent]

  private def eventDecoder[A](
      payload: => JsonDecoder[A],
      expected: Option[String]
  ): JsonDecoder[A] =
    summon[JsonDecoder[Json]].mapOrFail {
      case obj: Json.Obj =>
        val tags = obj.fields.collect { case ("event", value) => value }.toList
        tags match
          case Nil if expected.nonEmpty                         => payload.fromJsonAST(obj)
          case List(Json.Str(tag)) if expected.forall(_ == tag) => payload.fromJsonAST(obj)
          case _ => Left("Missing, duplicate, or invalid event discriminator")
      case _ => Left("Expected an event object")
    }

  private def taggedCodec[A <: AgentEvent](tag: String, payload: JsonCodec[A]): JsonCodec[A] =
    JsonCodec(
      JsonEncoder.suspend(wireCodec.encoder).contramap[A](identity),
      eventDecoder(payload.decoder, Some(tag))
    )

  given JsonCodec[AgentEvent] = JsonCodec(
    JsonEncoder.suspend(wireCodec.encoder),
    eventDecoder(wireCodec.decoder, None)
  )
