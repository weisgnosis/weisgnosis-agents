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
package com.weisgnosis.agents.core.model

import zio.Chunk

/** Internal shared occurrence semantics for history rewriting and compaction boundaries. */
private[agents] object ToolHistory:
  final case class Occurrence(call: ContentBlock.ToolUse, position: Int, ordinal: Int)
  final case class Span(start: Int, end: Int)
  final case class Analysis(
      pending: Chunk[Occurrence],
      spans: Chunk[Span],
      unmatched: Chunk[ContentBlock.ToolResult]
  ):
    /** Local pairing completeness; unresolved provider work belongs to subsequent model turns. */
    def isComplete: Boolean = pending.isEmpty && unmatched.isEmpty

  def inspect(history: Chunk[Msg]): Analysis =
    history.zipWithIndex.foldLeft(Analysis(Chunk.empty, Chunk.empty, Chunk.empty)) {
      case (analysis, (message, position)) =>
        message.role match
          case Role.Assistant =>
            val inline = inlineOccurrences(message, position)
            Analysis(
              analysis.pending ++ inline.pending.filterNot(_.call.isServerTool),
              analysis.spans ++ inline.spans,
              analysis.unmatched ++ inline.unmatched
            )
          case Role.Tool =>
            message.toolResults.foldLeft(analysis) { (current, result) =>
              consume(current, result, position, call => result.name.forall(_ == call.name))
            }
          case _ => analysis
    }

  /** Uses the same occurrence matching as history inspection, not an ID-only completion guess. */
  def completedInlineOrdinals(message: Msg): Set[Int] =
    message.toolUses.indices.toSet -- inlineOccurrences(message, 0).pending.map(_.ordinal).toSet

  private def inlineOccurrences(message: Msg, position: Int): Analysis =
    val calls = message.toolUses.zipWithIndex.map { case (call, ordinal) =>
      Occurrence(call, position, ordinal)
    }
    message.toolResults.foldLeft(Analysis(calls, Chunk.empty, Chunk.empty)) { (current, result) =>
      if result.state == ToolResultState.Running &&
        calls.exists(value => value.call.isServerTool && value.call.id == result.id)
      then current
      else consume(current, result, position, _.isServerTool)
    }

  private def consume(
      analysis: Analysis,
      result: ContentBlock.ToolResult,
      position: Int,
      eligible: ContentBlock.ToolUse => Boolean
  ): Analysis =
    val (before, matchingAndAfter) =
      analysis.pending.span(value => value.call.id != result.id || !eligible(value.call))
    matchingAndAfter.headOption match
      case Some(matched) =>
        analysis.copy(
          pending = before ++ matchingAndAfter.drop(1),
          spans = analysis.spans :+ Span(matched.position, position)
        )
      case None => analysis.copy(unmatched = analysis.unmatched :+ result)
