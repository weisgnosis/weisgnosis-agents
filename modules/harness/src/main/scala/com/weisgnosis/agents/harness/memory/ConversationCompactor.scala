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
package com.weisgnosis.agents.harness.memory

import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.model.{ContentBlock, Msg, Role}
import zio.*
import zio.json.*
import zio.json.ast.Json

/** Pure boundary selection and history rewriting, with explicit effectful summarization and hooks. */
final class ConversationCompactor(
    summarize: Chunk[Msg] => IO[AgentError, String],
    val config: CompactorConfig = CompactorConfig(),
    counter: TokenCounter = TokenCounter.approximate,
    flush: Chunk[Msg] => IO[AgentError, Unit] = _ => ZIO.unit,
    offload: Chunk[Msg] => IO[AgentError, Unit] = _ => ZIO.unit
):
  def compactIfNeeded(history: Chunk[Msg]): IO[AgentError, Chunk[Msg]] =
    ZIO.fromEither(expandCoalescedUsers(history)).flatMap { expanded =>
      val (systems, conversation) = expanded.partition(_.role == Role.System)
      val triggered               = counter.count(conversation) > config.triggerTokens.max(0)
      val pruned                  = if triggered then prune(conversation) else conversation
      val cutoff                  = calculateCutoffIndex(pruned, config)
      if !triggered then ZIO.succeed(history)
      else if counter.count(pruned) <= config.triggerTokens.max(0) || cutoff <= 0 then
        ZIO.succeed(systems ++ coalesceUsers(pruned))
      else
        val prefix               = pruned.take(cutoff)
        val (oldSummaries, tail) = pruned.drop(cutoff).partition(ConversationCompactor.isSummary)
        val summaryInput         = prefix ++ oldSummaries
        for
          _       <- flush(summaryInput.filterNot(ConversationCompactor.isSummary))
          _       <- offload(conversation)
          summary <- summarize(summaryInput)
          result <-
            if summary.trim.isEmpty then
              ZIO.fail(AgentError.DeserializationError("Compaction produced an empty summary"))
            else
              ZIO.succeed(
                systems ++ coalesceUsers(
                  Chunk(
                    Msg(
                      role = Role.User,
                      name = Some(ConversationCompactor.SummaryName),
                      content = Chunk(ContentBlock.Text(summary))
                    )
                  ) ++ tail
                )
              )
        yield result
    }

  def calculateCutoffIndex(history: Chunk[Msg], settings: CompactorConfig): Int =
    val tokenCutoff   = tokenBoundary(history, settings.keepTokens.max(0).toLong)
    val messageCutoff = (history.size - settings.preserveTailMessages.max(1)).max(0)
    safeCutoff(history, tokenCutoff.min(messageCutoff))

  private def tokenBoundary(history: Chunk[Msg], keep: Long): Int =
    val suffix =
      history.map(message => counter.count(message).max(0L)).toVector.scanRight(0L)(_ + _)
    @annotation.tailrec
    def search(low: Int, high: Int): Int =
      if low >= high then low
      else
        val middle = low + (high - low) / 2
        if suffix.lift(middle).getOrElse(0L) <= keep then search(low, middle)
        else search(middle + 1, high)
    search(0, history.size)

  /** Every result in the retained tail keeps its nearest preceding assistant call. */
  @annotation.tailrec
  private def safeCutoff(history: Chunk[Msg], candidate: Int): Int =
    val (_, pairs) =
      history.zipWithIndex.foldLeft((Map.empty[String, Int], Chunk.empty[(Int, Int)])) {
        case ((pending, spans), (message, index)) =>
          val calls =
            message.toolUses.foldLeft(pending)((current, call) => current.updated(call.id, index))
          val matched = message.toolResults.flatMap(result =>
            Chunk.fromIterable(calls.get(result.toolUseId).map(_ -> index))
          )
          (calls -- message.toolResults.map(_.toolUseId), spans ++ matched)
      }
    val crossing = pairs.collect {
      case (start, end) if start < candidate && end >= candidate => start
    }.minOption
    crossing match
      case Some(index) => safeCutoff(history, index)
      case None =>
        val orphanCount = history.drop(candidate).takeWhile(_.role == Role.Tool).size
        if orphanCount > 0 then safeCutoff(history, candidate + orphanCount)
        else candidate

  private def prune(history: Chunk[Msg]): Chunk[Msg] =
    val protectedStart = tokenBoundary(history, config.protectTokens.max(0).toLong)
      .min((history.size - config.preserveTailMessages.max(1)).max(0))
    val limit = config.maxPruneOutputChars.max(0)
    history.zipWithIndex.map { case (message, index) =>
      if index >= protectedStart then message
      else
        message.copy(content = message.content.map {
          case result: ContentBlock.ToolResult if result.text.length > limit =>
            result.copy(output =
              result.output.filterNot(_.isInstanceOf[ContentBlock.Text]) :+
                ContentBlock.Text(preview(result.text, limit))
            )
          case call: ContentBlock.ToolUse if call.input.toJson.length > limit =>
            call.copy(input =
              Json.Obj("_pruned_arguments" -> Json.Str(preview(call.input.toJson, limit)))
            )
          case block => block
        })
    }

  private def preview(text: String, limit: Int): String =
    val head = limit / 2
    text.take(head) + "\n... pruned ...\n" + text.takeRight(limit - head)

  private def retainedUsers(message: Msg): Either[AgentError, Chunk[Msg]] =
    message.metadata.get(ConversationCompactor.RetainedUsersKey) match
      case None => Right(Chunk.empty)
      case Some(Json.Str(encoded)) =>
        encoded
          .fromJson[Chunk[Msg]]
          .left
          .map(reason =>
            AgentError.DeserializationError(s"Invalid retained compaction users: $reason")
          )
      case Some(_) =>
        Left(AgentError.DeserializationError("Invalid retained compaction users metadata"))

  private def expandCoalescedUsers(history: Chunk[Msg]): Either[AgentError, Chunk[Msg]] =
    history.foldLeft[Either[AgentError, Chunk[Msg]]](Right(Chunk.empty)) { (accumulated, message) =>
      for
        previous <- accumulated
        retained <-
          if ConversationCompactor.isSummary(message) then retainedUsers(message)
          else Right(Chunk.empty)
        retainedContent = retained.flatMap(_.content)
        _ <- Either.cond(
          retained.forall(_.role == Role.User) && message.content.takeRight(
            retainedContent.size
          ) == retainedContent,
          (),
          AgentError.DeserializationError(
            "Retained compaction users must match the coalesced content"
          )
        )
      yield
        if retained.isEmpty then previous :+ message
        else
          val summary = message.copy(
            content = message.content.dropRight(retainedContent.size),
            metadata = message.metadata - ConversationCompactor.RetainedUsersKey
          )
          previous ++ Chunk(summary) ++ retained
    }

  private def coalesceUsers(history: Chunk[Msg]): Chunk[Msg] =
    history.foldLeft(Chunk.empty[Msg]) { (result, message) =>
      result.lastOption match
        case Some(previous) if previous.role == Role.User && message.role == Role.User =>
          result.dropRight(1) :+ previous.copy(
            content = previous.content ++ message.content,
            metadata = if ConversationCompactor.isSummary(previous) then
              val retained = retainedUsers(previous).getOrElse(Chunk.empty) :+ message
              (message.metadata ++ previous.metadata).updated(
                ConversationCompactor.RetainedUsersKey,
                Json.Str(retained.toJson)
              )
            else message.metadata ++ previous.metadata
          )
        case _ => result :+ message
    }

object ConversationCompactor:
  private[memory] val RetainedUsersKey: String = "_compaction_retained_users"
  val SummaryName: String                      = "__compaction_summary__"
  def isSummary(message: Msg): Boolean         = message.name.contains(SummaryName)
