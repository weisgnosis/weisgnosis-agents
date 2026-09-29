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
package com.weisgnosis.agents.harness.eviction

import com.weisgnosis.agents.core.agent.HistoryPreparer
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.model.{ContentBlock, Msg}
import com.weisgnosis.agents.core.tool.{ToolResultPayload, ToolResultProcessor}
import zio.*
import zio.json.*
import zio.json.ast.Json
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Eviction is best-effort: typed storage failures leave the original observation intact. */
final class ToolResultEvictor(store: ToolResultStore, agentName: String = "assistant"):
  def evictOversizedToolResults(
      history: Chunk[Msg],
      config: EvictionConfig = EvictionConfig()
  ): UIO[Chunk[Msg]] =
    ZIO.foreach(history)(_.mapToolResultsZIO(result => evictResult(result, config)))

  /** Recognize stored previews before applying the threshold to a new raw observation. */
  def evictResult(
      result: ContentBlock.ToolResult,
      config: EvictionConfig = EvictionConfig()
  ): UIO[ContentBlock.ToolResult] =
    if result.name.exists(config.excludedTools.contains) then ZIO.succeed(result)
    else
      ZIO
        .suspendSucceed(verifiedOriginal(result))
        .flatMap:
          case Some((key, original)) => ZIO.succeed(render(result, key, original, config))
          case None if result.text.length > config.maxResultChars.max(0) => evict(result, config)
          case None                                                      => ZIO.succeed(result)
        .catchAll(_ => ZIO.succeed(result))

  /** Installs immediate local/inline-result eviction after raw completion persistence. */
  def processor(config: EvictionConfig = EvictionConfig()): ToolResultProcessor =
    ToolResultProcessor(result => evictResult(result, config).map(ToolResultPayload.fromResult))

  /** Rewrites supplied and accumulated history before model dispatch. */
  def preparer(config: EvictionConfig = EvictionConfig()): HistoryPreparer =
    HistoryPreparer(history => evictOversizedToolResults(history, config))

  private case class PreviewHint(key: String, path: String, head: Int, tail: Int)

  private def hint(result: ContentBlock.ToolResult): Option[PreviewHint] =
    val prefix = keyPrefix(result.id)
    for
      version <- result.metadata.get("eviction_preview_version").flatMap(integer)
      if version == 1
      key <- result.metadata.get("eviction_cache_key").flatMap(_.asString)
      if key.startsWith(prefix)
      digest = key.drop(prefix.length)
      if digest.length == 64 && digest.forall(c => (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))
      path <- result.metadata.get("eviction_path").flatMap(_.asString)
      head <- result.metadata.get("eviction_preview_head_chars").flatMap(integer)
      tail <- result.metadata.get("eviction_preview_tail_chars").flatMap(integer)
      if head >= 0 && tail >= 0
    yield PreviewHint(key, path, head, tail)

  private def integer(value: Json): Option[Int] = value match
    case number: Json.Num => number.as[Int].toOption
    case _                => None

  private def verifiedOriginal(
      result: ContentBlock.ToolResult
  ): IO[AgentError, Option[(String, StoredToolResult)]] =
    hint(result) match
      case None => ZIO.none
      case Some(candidate) =>
        store
          .resolve(candidate.key)
          .flatMap:
            case None => ZIO.none
            case Some(original) =>
              contentKey(result.id, original.content).map: key =>
                Option.when(
                  key == candidate.key && original.path == candidate.path &&
                    candidate.head <= original.content.length && candidate.tail <= original.content.length &&
                    result.text == preview(original, candidate.head, candidate.tail)
                )(key -> original)

  private def evict(
      result: ContentBlock.ToolResult,
      config: EvictionConfig
  ): IO[AgentError, ContentBlock.ToolResult] =
    for
      key  <- contentKey(result.id, result.text)
      path <- store.persist(key, result.text)
    yield render(result, key, StoredToolResult(result.text, path), config)

  private def render(
      result: ContentBlock.ToolResult,
      key: String,
      original: StoredToolResult,
      config: EvictionConfig
  ): ContentBlock.ToolResult =
    val head = config.previewHeadChars.max(0).min(original.content.length)
    val tail = config.previewTailChars.max(0).min(original.content.length)
    result.copy(
      output = result.output.filterNot(_.isInstanceOf[ContentBlock.Text]) :+
        ContentBlock.Text(preview(original, head, tail)),
      metadata = result.metadata ++ Map(
        "eviction_cache_key"          -> Json.Str(key),
        "eviction_path"               -> Json.Str(original.path),
        "eviction_preview_version"    -> Json.Num(1),
        "eviction_preview_head_chars" -> Json.Num(head),
        "eviction_preview_tail_chars" -> Json.Num(tail)
      )
    )

  private def preview(original: StoredToolResult, head: Int, tail: Int): String =
    s"Tool output was too large (${original.content.length} chars) and has been saved to `${original.path}`.\nUse `read_file` with path `${original.path}` to inspect the full output.\n\n${original.content.take(head)}\n\n... omitted ...\n\n${original.content.takeRight(tail)}"

  private def contentKey(id: String, text: String): IO[AgentError, String] =
    ZIO
      .attempt:
        val digest = MessageDigest
          .getInstance("SHA-256")
          .digest(text.getBytes(StandardCharsets.UTF_8))
          .iterator
          .map(byte => f"${byte & 0xff}%02x")
          .mkString
        keyPrefix(id) + digest
      .mapError(error => AgentError.ToolExecutionError("tool_result_eviction", error.toString))

  private def keyPrefix(id: String): String = safeName(agentName) + "-" + safeName(id) + "-"

  private def safeName(value: String): String =
    val sanitized =
      value.map(c => if c.isLetterOrDigit || c == '-' || c == '_' then c else '_').take(48)
    if sanitized.isEmpty then "result" else sanitized
