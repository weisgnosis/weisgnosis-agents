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

import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.model.{ContentBlock, Msg}
import zio.*
import zio.json.ast.Json
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Eviction is best-effort: persistence failure leaves the original observation intact. */
final class ToolResultEvictor(store: ToolResultStore, agentName: String = "assistant"):
  def evictOversizedToolResults(
      history: Chunk[Msg],
      config: EvictionConfig = EvictionConfig()
  ): UIO[Chunk[Msg]] =
    ZIO.foreach(history) { message =>
      message.mapToolResultsZIO { result =>
        if result.text.length > config.maxResultChars.max(0) &&
          !result.name.exists(config.excludedTools.contains)
        then evict(result, config).catchAll(_ => ZIO.succeed(result))
        else ZIO.succeed(result)
      }
    }

  private def evict(
      result: ContentBlock.ToolResult,
      config: EvictionConfig
  ): IO[AgentError, ContentBlock.ToolResult] =
    val text = result.text
    for
      hash <- ZIO
        .attempt {
          MessageDigest
            .getInstance("SHA-256")
            .digest(text.getBytes(StandardCharsets.UTF_8))
            .iterator
            .map(byte => f"${byte & 0xff}%02x")
            .mkString
        }
        .mapError(error => AgentError.ToolExecutionError("tool_result_eviction", error.toString))
      key = safeName(agentName) + "-" + safeName(result.id) + "-" + hash
      path <- store.persist(key, text)
      head = text.take(config.previewHeadChars.max(0))
      tail = text.takeRight(config.previewTailChars.max(0))
      preview =
        s"Tool output was too large (${text.length} chars) and has been saved to `$path`.\nUse `read_file` with path `$path` to inspect the full output.\n\n$head\n\n... omitted ...\n\n$tail"
    yield result.copy(
      output =
        result.output.filterNot(_.isInstanceOf[ContentBlock.Text]) :+ ContentBlock.Text(preview),
      metadata = result.metadata ++ Map(
        "eviction_cache_key" -> Json.Str(key),
        "eviction_path"      -> Json.Str(path)
      )
    )

  private def safeName(value: String): String =
    val sanitized =
      value.map(c => if c.isLetterOrDigit || c == '-' || c == '_' then c else '_').take(48)
    if sanitized.isEmpty then "result" else sanitized
