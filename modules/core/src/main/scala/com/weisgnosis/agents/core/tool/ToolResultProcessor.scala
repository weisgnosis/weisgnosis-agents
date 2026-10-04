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

import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.model.ContentBlock
import zio.*
import zio.json.*
import zio.json.ast.Json

/** Replaceable observation content; call identity, execution state, and control metadata stay runtime-owned. */
final case class ToolResultPayload(
    output: Chunk[ContentBlock],
    metadata: Map[String, Json] = Map.empty
) derives JsonCodec:
  def applyTo(original: ContentBlock.ToolResult): ContentBlock.ToolResult =
    val controls = Set("serverTool", "agentscope_server_tool", "agentscope_suspended")
    original.copy(
      output = output,
      metadata = (metadata -- controls) ++ original.metadata.filter { case (key, _) =>
        controls.contains(key)
      }
    )

object ToolResultPayload:
  def fromResult(result: ContentBlock.ToolResult): ToolResultPayload =
    ToolResultPayload(result.output, result.metadata)

/** A host processor decision; tool payloads cannot grant stop authority. */
enum ToolResultDecision:
  case Continue(value: ToolResultPayload)
  case Stop(value: ToolResultPayload, reason: String)

  def payload: ToolResultPayload = this match
    case Continue(value) => value
    case Stop(value, _)  => value

/** Optional scoped processing of an already persisted completed local or matched inline provider observation. */
trait ToolResultProcessor:
  def process(result: ContentBlock.ToolResult): ZIO[Scope, AgentError, ToolResultPayload]

  def processDecision(result: ContentBlock.ToolResult): ZIO[Scope, AgentError, ToolResultDecision] =
    process(result).map(ToolResultDecision.Continue(_))

  final def andThen(next: ToolResultProcessor): ToolResultProcessor =
    val first = this
    new ToolResultProcessor:
      def process(result: ContentBlock.ToolResult): ZIO[Scope, AgentError, ToolResultPayload] =
        first.process(result).flatMap(payload => next.process(payload.applyTo(result)))

      override def processDecision(
          result: ContentBlock.ToolResult
      ): ZIO[Scope, AgentError, ToolResultDecision] =
        first
          .processDecision(result)
          .flatMap:
            case ToolResultDecision.Continue(payload) =>
              next.processDecision(payload.applyTo(result))
            case stop @ ToolResultDecision.Stop(_, _) => ZIO.succeed(stop)

object ToolResultProcessor:
  /** Stop ends this decision chain; direct payload-only processing still evaluates every stage. */
  def deciding(
      run: ContentBlock.ToolResult => ZIO[Scope, AgentError, ToolResultDecision]
  ): ToolResultProcessor = new ToolResultProcessor:
    def process(result: ContentBlock.ToolResult): ZIO[Scope, AgentError, ToolResultPayload] =
      processDecision(result).map(_.payload)

    override def processDecision(
        result: ContentBlock.ToolResult
    ): ZIO[Scope, AgentError, ToolResultDecision] = ZIO.suspendSucceed(run(result))

  val identity: ToolResultProcessor =
    ToolResultProcessor(result => ZIO.succeed(ToolResultPayload.fromResult(result)))

  def apply(
      run: ContentBlock.ToolResult => ZIO[Scope, AgentError, ToolResultPayload]
  ): ToolResultProcessor = new ToolResultProcessor:
    def process(result: ContentBlock.ToolResult): ZIO[Scope, AgentError, ToolResultPayload] =
      ZIO.suspendSucceed(run(result))
