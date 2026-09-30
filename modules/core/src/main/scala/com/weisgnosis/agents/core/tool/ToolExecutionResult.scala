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
package com.weisgnosis.agents.core.tool

import com.weisgnosis.agents.core.model.{ContentBlock, ToolResultState}
import zio.Chunk
import zio.json.*

/**
 * Typed observation and state outcome of executing a tool. String construction remains supported;
 * generated copy/unapply and the derived JSON representation now use payload rather than output.
 */
final case class ToolExecutionResult(
    payload: ToolResultPayload,
    state: ToolResultState
) derives JsonCodec:
  /** Text-only projection; typed non-text blocks remain available in payload. */
  def output: String =
    payload.output.collect { case ContentBlock.Text(text) => text }.mkString("\n")

object ToolExecutionResult:
  def apply(output: String): ToolExecutionResult = apply(output, ToolResultState.Success)

  def apply(output: String, state: ToolResultState): ToolExecutionResult =
    ToolExecutionResult(ToolResultPayload(Chunk(ContentBlock.Text(output))), state)
