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

import com.weisgnosis.agents.core.error.AgentError
import zio.*
import zio.stream.ZPipeline

/** Opt-in presentation filtering; raw diagnostics, results and canonical history are unchanged. */
object FinalAnswerFilter:
  /**
   * Buffers correlated text until its request completes, suppressing rounds that announce a tool.
   * Limits apply across all active requests in one subscription. Characters are UTF-16 code units,
   * including both deltas and completed fullText. Unmatched events pass through unchanged.
   * This is neither secret redaction nor proof that a tool-free round is the agent's final reply.
   */
  def make(
      maxBufferedEvents: Int = 4096,
      maxBufferedCharacters: Int = 1048576,
      maxActiveRequests: Int = 64
  ): Either[
    AgentError.InvalidConfiguration,
    ZPipeline[Any, AgentError.InvalidMessage, AgentEvent, AgentEvent]
  ] =
    Either.cond(
      maxBufferedEvents > 0 && maxBufferedCharacters > 0 && maxActiveRequests > 0,
      ZPipeline
        .mapAccumZIO(State())((state: State, event: AgentEvent) =>
          ZIO.fromEither(
            state.accept(event, maxBufferedEvents, maxBufferedCharacters, maxActiveRequests)
          )
        )
        .mapChunks(_.flatten),
      AgentError.InvalidConfiguration("Final-answer filter limits must be positive")
    )

  final private case class Key(source: Option[String], context: ModelStreamContext)

  private enum Round:
    case Buffering(events: Chunk[AgentEvent], characters: Long)
    case Suppressed

  final private case class State(
      rounds: Map[Key, Round] = Map.empty,
      eventCount: Long = 0L,
      characterCount: Long = 0L
  ):
    private def remove(key: Key): State = rounds.get(key) match
      case Some(Round.Buffering(events, characters)) =>
        copy(
          rounds = rounds.removed(key),
          eventCount = eventCount - events.size.toLong,
          characterCount = characterCount - characters
        )
      case _ => copy(rounds = rounds.removed(key))

    def accept(
        event: AgentEvent,
        maxEvents: Int,
        maxCharacters: Int,
        maxRequests: Int
    ): Either[AgentError.InvalidMessage, (State, Chunk[AgentEvent])] =
      event match
        case start: AgentEvent.ModelRequestStartEvent =>
          start.context match
            case Some(context) =>
              val key = Key(start.source, context)
              if !rounds.contains(key) && rounds.size >= maxRequests then
                Left(AgentError.InvalidMessage("Final-answer filter active-request limit exceeded"))
              else
                val cleared = remove(key)
                Right(
                  (
                    cleared.copy(rounds =
                      cleared.rounds.updated(key, Round.Buffering(Chunk.empty, 0L))
                    ),
                    Chunk(event)
                  )
                )
            case None => Right((this, Chunk(event)))
        case text: AgentEvent.TextBlockStartEvent =>
          buffer(text, text.context, 0L, maxEvents, maxCharacters)
        case text: AgentEvent.TextBlockDeltaEvent =>
          buffer(text, text.context, text.delta.length.toLong, maxEvents, maxCharacters)
        case text: AgentEvent.TextBlockEndEvent =>
          buffer(text, text.context, text.fullText.length.toLong, maxEvents, maxCharacters)
        case call: AgentEvent.ToolCallStartEvent =>
          call.context match
            case Some(context) if rounds.contains(Key(call.source, context)) =>
              val key     = Key(call.source, context)
              val cleared = remove(key)
              Right(
                (cleared.copy(rounds = cleared.rounds.updated(key, Round.Suppressed)), Chunk(event))
              )
            case _ => Right((this, Chunk(event)))
        case end: AgentEvent.ModelResponseEndEvent =>
          end.context match
            case Some(context) =>
              val key = Key(end.source, context)
              val text = rounds.get(key) match
                case Some(Round.Buffering(events, _)) => events
                case _                                => Chunk.empty
              Right((remove(key), text :+ event))
            case None => Right((this, Chunk(event)))
        case end: AgentEvent.AgentEndEvent =>
          val matching = rounds.keys.filter(key =>
            key.source == end.source && key.context.replyId == end.replyId
          )
          Right((matching.foldLeft(this)((state, key) => state.remove(key)), Chunk(event)))
        case _ => Right((this, Chunk(event)))

    private def buffer(
        event: AgentEvent,
        context: Option[ModelStreamContext],
        characters: Long,
        maxEvents: Int,
        maxCharacters: Int
    ): Either[AgentError.InvalidMessage, (State, Chunk[AgentEvent])] =
      context.map(Key(event.source, _)) match
        case Some(key) =>
          rounds.get(key) match
            case Some(Round.Suppressed) => Right((this, Chunk.empty))
            case Some(Round.Buffering(events, retained)) =>
              if eventCount >= maxEvents.toLong || characters > maxCharacters.toLong - characterCount
              then Left(AgentError.InvalidMessage("Final-answer filter text buffer limit exceeded"))
              else
                Right(
                  (
                    copy(
                      rounds = rounds
                        .updated(key, Round.Buffering(events :+ event, retained + characters)),
                      eventCount = eventCount + 1L,
                      characterCount = characterCount + characters
                    ),
                    Chunk.empty
                  )
                )
            case None => Right((this, Chunk(event)))
        case None => Right((this, Chunk(event)))
