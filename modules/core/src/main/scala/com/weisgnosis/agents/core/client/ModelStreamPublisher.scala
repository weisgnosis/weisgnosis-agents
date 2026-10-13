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

import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.event.{AgentEvent, ModelStreamContext}
import zio.*

/** Serial publication after frame validation; only completed output closes content blocks. */
final private[client] class ModelStreamPublisher private (
    state: Ref[ModelStreamPublisher.State],
    hub: Hub[AgentEvent],
    context: ModelStreamContext
):
  def frame(raw: String, deltas: Chunk[LlmChunk]): UIO[Unit] =
    hub.publish(AgentEvent.ModelResponseChunkEvent(raw, context = Some(context))) *>
      ZIO.foreachDiscard(deltas): delta =>
        state
          .modify(current =>
            val (next, events) = current.accept(delta, context)
            (events, next)
          )
          .flatMap(events => hub.publishAll(events).unit)

  def finish(output: TurnOutput): IO[AgentError, Unit] =
    for
      current <- state.get
      events  <- ZIO.fromEither(current.endEvents(output, context))
      _       <- hub.publishAll(events)
    yield ()

private[client] object ModelStreamPublisher:
  private enum Block:
    case Text(index: Int)
    case Thinking(index: Int)
    case Tool(index: Int)

  private enum ToolProgress:
    case Pending(id: Option[String], name: Option[String], argumentFragments: List[String])
    case Started(id: String, name: String)

  final private case class State(
      text: Map[Int, List[String]] = Map.empty,
      thinking: Map[Int, List[String]] = Map.empty,
      tools: Map[Int, ToolProgress] = Map.empty,
      order: Chunk[Block] = Chunk.empty
  ):
    def accept(delta: LlmChunk, context: ModelStreamContext): (State, Chunk[AgentEvent]) =
      delta match
        case LlmChunk.TextDelta(value, index) =>
          val started = text.contains(index)
          val start =
            if started then Chunk.empty
            else Chunk(AgentEvent.TextBlockStartEvent(index, context = Some(context)))
          val next = copy(
            text = text.updated(index, value :: text.getOrElse(index, Nil)),
            order = if started then order else order :+ Block.Text(index)
          )
          (next, start :+ AgentEvent.TextBlockDeltaEvent(value, index, context = Some(context)))
        case LlmChunk.ThinkingDelta(value, index, _, _) =>
          val started = thinking.contains(index)
          val start =
            if started then Chunk.empty
            else Chunk(AgentEvent.ThinkingBlockStartEvent(index, context = Some(context)))
          val next = copy(
            thinking = thinking.updated(index, value :: thinking.getOrElse(index, Nil)),
            order = if started then order else order :+ Block.Thinking(index)
          )
          (
            next,
            start :+ AgentEvent.ThinkingBlockDeltaEvent(
              value,
              index,
              context = Some(context)
            )
          )
        case LlmChunk.ReasoningDetailsDelta(details, _) =>
          val text = details.map(_.plaintext).mkString
          if text.isEmpty then (this, Chunk.empty)
          else accept(LlmChunk.ThinkingDelta(text), context)
        case call: LlmChunk.ToolCallChunk =>
          tools.getOrElse(call.streamIndex, ToolProgress.Pending(None, None, Nil)) match
            case ToolProgress.Started(id, _) =>
              (this, toolDelta(id, call.streamIndex, call.argumentsDelta, context))
            case ToolProgress.Pending(id, name, argumentFragments) =>
              val nextId   = call.callId.orElse(id)
              val nextName = call.name.orElse(name)
              val buffered =
                if call.argumentsDelta.isEmpty then argumentFragments
                else call.argumentsDelta :: argumentFragments
              (nextId, nextName) match
                case (Some(callId), Some(callName)) =>
                  val next = copy(
                    tools = tools.updated(call.streamIndex, ToolProgress.Started(callId, callName)),
                    order = order :+ Block.Tool(call.streamIndex)
                  )
                  val start = AgentEvent.ToolCallStartEvent(
                    callId,
                    callName,
                    call.streamIndex,
                    streamIndex = Some(call.streamIndex),
                    context = Some(context)
                  )
                  (
                    next,
                    Chunk(start) ++ toolDelta(
                      callId,
                      call.streamIndex,
                      buffered.reverse.mkString,
                      context
                    )
                  )
                case _ =>
                  (
                    copy(tools =
                      tools
                        .updated(call.streamIndex, ToolProgress.Pending(nextId, nextName, buffered))
                    ),
                    Chunk.empty
                  )
        case _: LlmChunk.UsageChunk | _: LlmChunk.FinishReason =>
          (this, Chunk.empty)

    def endEvents(
        output: TurnOutput,
        context: ModelStreamContext
    ): Either[AgentError, Chunk[AgentEvent]] =
      val completedBlocks = output match
        case _: TurnOutput.Truncated => order.collect { case block: Block.Text => block }
        case _                       => order
      completedBlocks.foldLeft[Either[AgentError, Chunk[AgentEvent]]](Right(Chunk.empty))(
        (previous, block) =>
          for
            events <- previous
            event <- block match
              case Block.Text(index) =>
                Right(
                  AgentEvent.TextBlockEndEvent(
                    index,
                    text.getOrElse(index, Nil).reverse.mkString,
                    context = Some(context)
                  )
                )
              case Block.Thinking(index) =>
                Right(
                  AgentEvent.ThinkingBlockEndEvent(
                    index,
                    thinking.getOrElse(index, Nil).reverse.mkString,
                    context = Some(context)
                  )
                )
              case Block.Tool(index) =>
                tools.get(index) match
                  case Some(ToolProgress.Started(id, name)) =>
                    output.message.toolUses
                      .find(_.id == id)
                      .toRight(
                        AgentError.InvalidMessage(
                          "Completed stream is missing a published tool call"
                        )
                      )
                      .map(call =>
                        AgentEvent
                          .ToolCallEndEvent(id, name, call.input, index, context = Some(context))
                      )
                  case _ =>
                    Left(
                      AgentError.InvalidMessage("Completed stream has no published tool identity")
                    )
          yield events :+ event
      )

  private def toolDelta(
      id: String,
      index: Int,
      arguments: String,
      context: ModelStreamContext
  ): Chunk[AgentEvent] =
    if arguments.isEmpty then Chunk.empty
    else
      Chunk(
        AgentEvent.ToolCallDeltaEvent(
          id,
          arguments,
          index,
          streamIndex = Some(index),
          context = Some(context)
        )
      )

  def make(hub: Hub[AgentEvent], context: ModelStreamContext): UIO[ModelStreamPublisher] =
    Ref.make(State()).map(new ModelStreamPublisher(_, hub, context))
