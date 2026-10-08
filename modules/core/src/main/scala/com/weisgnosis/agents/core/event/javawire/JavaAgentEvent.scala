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
package com.weisgnosis.agents.core.event.javawire

import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.json.JsonBoundary
import com.weisgnosis.agents.core.model.ToolResultState
import zio.Chunk
import zio.json.*
import zio.json.ast.Json

/** All pinned Java event payloads; presence is data, not evidence of an emitted runtime event. */
@jsonDiscriminator("type")
enum JavaEventPayload derives JsonCodec:
  @jsonHint("AGENT_START") case AgentStart(
      sessionId: Option[String],
      replyId: Option[String],
      name: Option[String],
      role: Option[String]
  )
  @jsonHint("AGENT_END") case AgentEnd(replyId: Option[String])
  @jsonHint("AGENT_RESULT") case AgentResult(result: Option[JavaMessage])
  @jsonHint("MODEL_CALL_START") case ModelCallStart(replyId: Option[String])
  @jsonHint("MODEL_CALL_END") case ModelCallEnd(
      replyId: Option[String],
      usage: Option[JavaChatUsage]
  )
  @jsonHint("EXCEED_MAX_ITERS") case ExceedMaxIters(
      replyId: Option[String],
      maxIters: Int = 0,
      currentIter: Int = 0
  )
  @jsonHint("REQUIRE_USER_CONFIRM") case RequireUserConfirm(
      replyId: Option[String],
      toolCalls: Chunk[JavaContentBlock.ToolUse] = Chunk.empty
  )
  @jsonHint("REQUIRE_EXTERNAL_EXECUTION") case RequireExternalExecution(
      replyId: Option[String],
      toolCalls: Chunk[JavaContentBlock.ToolUse] = Chunk.empty
  )
  @jsonHint("USER_CONFIRM_RESULT") case UserConfirmResult(
      replyId: Option[String],
      confirmResults: Chunk[JavaConfirmResult] = Chunk.empty
  )
  @jsonHint("EXTERNAL_EXECUTION_RESULT") case ExternalExecutionResult(
      replyId: Option[String],
      toolResults: Chunk[JavaContentBlock.ToolResult] = Chunk.empty
  )
  @jsonHint("REQUEST_STOP") case RequestStop(
      reason: Option[String],
      generateReason: JavaGenerateReason = JavaGenerateReason.MiddlewareStopRequested
  )
  @jsonHint("SUBAGENT_EXPOSED") case SubagentExposed(
      subagentId: Option[String],
      agentId: Option[String],
      sessionId: Option[String],
      label: Option[String]
  )
  @jsonHint("HINT_BLOCK") case HintBlock(
      replyId: Option[String],
      blockId: Option[String],
      hintSource: Option[String],
      hint: Option[String]
  )
  @jsonHint("ALL_TOOLS_DENIED") case AllToolsDenied(
      deniedToolCalls: Chunk[JavaContentBlock.ToolUse] = Chunk.empty
  )
  @jsonHint("CUSTOM") case Custom(name: Option[String], value: Map[String, Json] = Map.empty)
  @jsonHint("TEXT_BLOCK_START") case TextBlockStart(
      replyId: Option[String],
      blockId: Option[String]
  )
  @jsonHint("TEXT_BLOCK_DELTA") case TextBlockDelta(
      replyId: Option[String],
      blockId: Option[String],
      delta: Option[String]
  )
  @jsonHint("TEXT_BLOCK_END") case TextBlockEnd(replyId: Option[String], blockId: Option[String])
  @jsonHint("THINKING_BLOCK_START") case ThinkingBlockStart(
      replyId: Option[String],
      blockId: Option[String]
  )
  @jsonHint("THINKING_BLOCK_DELTA") case ThinkingBlockDelta(
      replyId: Option[String],
      blockId: Option[String],
      delta: Option[String]
  )
  @jsonHint("THINKING_BLOCK_END") case ThinkingBlockEnd(
      replyId: Option[String],
      blockId: Option[String]
  )
  @jsonHint("DATA_BLOCK_START") case DataBlockStart(
      replyId: Option[String],
      blockId: Option[String]
  )
  @jsonHint("DATA_BLOCK_DELTA") case DataBlockDelta(
      replyId: Option[String],
      blockId: Option[String],
      delta: Option[String]
  )
  @jsonHint("DATA_BLOCK_END") case DataBlockEnd(replyId: Option[String], blockId: Option[String])
  @jsonHint("TOOL_CALL_START") case ToolCallStart(
      replyId: Option[String],
      toolCallId: Option[String],
      toolCallName: Option[String]
  )
  @jsonHint("TOOL_CALL_DELTA") case ToolCallDelta(
      replyId: Option[String],
      toolCallId: Option[String],
      toolCallName: Option[String],
      delta: Option[String]
  )
  @jsonHint("TOOL_CALL_END") case ToolCallEnd(
      replyId: Option[String],
      toolCallId: Option[String],
      toolCallName: Option[String]
  )
  @jsonHint("TOOL_RESULT_START") case ToolResultStart(
      replyId: Option[String],
      toolCallId: Option[String],
      toolCallName: Option[String]
  )
  @jsonHint("TOOL_RESULT_TEXT_DELTA") case ToolResultTextDelta(
      replyId: Option[String],
      toolCallId: Option[String],
      toolCallName: Option[String],
      delta: Option[String]
  )
  @jsonHint("TOOL_RESULT_DATA_DELTA") case ToolResultDataDelta(
      replyId: Option[String],
      toolCallId: Option[String],
      toolCallName: Option[String],
      data: Option[JavaContentBlock]
  )
  @jsonHint("TOOL_RESULT_END") case ToolResultEnd(
      replyId: Option[String],
      toolCallId: Option[String],
      toolCallName: Option[String],
      state: Option[ToolResultState]
  )

/** Explicit caller-owned envelope; constructing this value never reads time or generates an ID. */
final class JavaEventEnvelope private (
    val id: String,
    val createdAt: String,
    val source: Option[String],
    val metadata: Map[String, Json]
)

object JavaEventEnvelope:
  def make(
      id: String,
      createdAt: String,
      source: Option[String] = None,
      metadata: Map[String, Json] = Map.empty
  ): Either[AgentError.InvalidConfiguration, JavaEventEnvelope] =
    val values = Json.Arr(
      Chunk(Json.Str(id), Json.Str(createdAt)) ++
        Chunk.fromIterable(source.map(Json.Str(_))) :+ Json.Obj(Chunk.fromIterable(metadata))
    )
    for
      _ <- Either.cond(
        !id.isBlank && !createdAt.isBlank && JsonBoundary.validUnicode(values),
        (),
        AgentError.InvalidConfiguration(
          "Java event identity/time must be explicit nonblank valid Unicode"
        )
      )
      _ <- JsonBoundary.validate(values).left.map(AgentError.InvalidConfiguration(_))
    yield new JavaEventEnvelope(id, createdAt, source, metadata)

/** Opt-in Java wire DTO; canonical AgentEvent codecs and runtime emission remain unchanged. */
final case class JavaAgentEvent(envelope: JavaEventEnvelope, payload: JavaEventPayload)

object JavaAgentEvent:
  final private case class WireEnvelope(
      id: String,
      createdAt: String,
      source: Option[String],
      metadata: Map[String, Json] = Map.empty
  ) derives JsonCodec

  def decode(input: String): Either[AgentError, JavaAgentEvent] =
    for
      json <- JsonBoundary.decode(input).left.map(AgentError.DeserializationError(_))
      _ <- Either.cond(
        JsonBoundary.validUnicode(json),
        (),
        AgentError.DeserializationError("Invalid Java event Unicode")
      )
      encoded = JsonBoundary.render(json)
      wire     <- encoded.fromJson[WireEnvelope].left.map(AgentError.DeserializationError(_))
      envelope <- JavaEventEnvelope.make(wire.id, wire.createdAt, wire.source, wire.metadata)
      payload  <- encoded.fromJson[JavaEventPayload].left.map(AgentError.DeserializationError(_))
    yield JavaAgentEvent(envelope, payload)

  def encode(value: JavaAgentEvent): Either[AgentError, String] =
    for
      payload <- value.payload.toJsonAST.left.map(AgentError.InvalidConfiguration(_))
      fields <- payload match
        case Json.Obj(fields) => Right(fields)
        case _ => Left(AgentError.InvalidConfiguration("Java event payload must be an object"))
      envelope = value.envelope
      combined = Json.Obj(
        fields ++ Chunk(
          "id"        -> Json.Str(envelope.id),
          "createdAt" -> Json.Str(envelope.createdAt)
        ) ++
          Chunk.fromIterable(envelope.source.map(source => "source" -> Json.Str(source))) ++
          (if envelope.metadata.isEmpty then Chunk.empty
           else Chunk("metadata" -> Json.Obj(Chunk.fromIterable(envelope.metadata))))
      )
      _ <- JsonBoundary.validate(combined).left.map(AgentError.InvalidConfiguration(_))
      _ <- Either.cond(
        JsonBoundary.validUnicode(combined),
        (),
        AgentError.InvalidConfiguration("Invalid Java event Unicode")
      )
      rendered = JsonBoundary.render(combined)
      _ <- decode(rendered).map(_ => ())
    yield rendered
