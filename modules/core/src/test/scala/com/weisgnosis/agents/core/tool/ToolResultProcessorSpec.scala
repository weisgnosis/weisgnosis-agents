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
import com.weisgnosis.agents.core.error.AgentError
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

object ToolResultProcessorSpec extends ZIOSpecDefault:
  def spec: Spec[TestEnvironment & Scope, Any] = suite("ToolResultProcessorSpec")(
    test("payload codecs retain output and metadata without execution identity fields") {
      val payload =
        ToolResultPayload(Chunk(ContentBlock.Text("preview")), Map("cache" -> Json.Str("key")))
      assertTrue(payload.toJson.fromJson[ToolResultPayload] == Right(payload))
    },
    test("applying a payload preserves identity state and original runtime control keys") {
      val controls = Map(
        "serverTool"             -> Json.Bool(true),
        "agentscope_server_tool" -> Json.Bool(false),
        "agentscope_suspended"   -> Json.Bool(true)
      )
      val original = ContentBlock.ToolResult
        .text("call", "tool", "raw", ToolResultState.Error)
        .copy(metadata = controls)
      val forged = ToolResultPayload(
        Chunk(ContentBlock.Text("preview")),
        controls.view.mapValues(_ => Json.Bool(false)).toMap + ("cache" -> Json.Str("key"))
      )
      val rewritten = forged.applyTo(original)
      val plain     = forged.applyTo(original.copy(metadata = Map.empty))
      assertTrue(
        rewritten.id == original.id,
        rewritten.name == original.name,
        rewritten.state == ToolResultState.Error,
        rewritten.metadata == controls + ("cache" -> Json.Str("key")),
        rewritten.text == "preview",
        plain.metadata == Map("cache" -> Json.Str("key")),
        !plain.isServerTool,
        !plain.isSuspended
      )
    },
    test("composition is lazy ordered and passes only safely applied payloads onward") {
      val original = ContentBlock.ToolResult.text("call", "tool", "raw")
      for
        seen <- Ref.make(Chunk.empty[ContentBlock.ToolResult])
        first = ToolResultProcessor(result =>
          seen
            .update(_ :+ result)
            .as(
              ToolResultPayload(
                Chunk(ContentBlock.Text("first")),
                Map("serverTool" -> Json.Bool(true))
              )
            )
        )
        second = ToolResultProcessor(result =>
          seen.update(_ :+ result).as(ToolResultPayload.fromResult(result))
        )
        effect = first.andThen(second).process(original)
        before    <- seen.get
        payload   <- effect
        after     <- seen.get
        unchanged <- ToolResultProcessor.identity.process(original)
      yield assertTrue(
        before.isEmpty,
        after.map(_.text) == Chunk("raw", "first"),
        after.forall(!_.isServerTool),
        payload.applyTo(original).text == "first",
        unchanged.applyTo(original) == original
      )
    },
    test(
      "decisions stop only their chain while legacy calls and scoped failures retain their semantics"
    ) {
      val original = ContentBlock.ToolResult.text("call", "tool", "raw")
      val payload  = ToolResultPayload(Chunk(ContentBlock.Text("processed")))
      val failure  = AgentError.InvalidConfiguration("later processor failed")
      val defect   = new IllegalStateException("processor cleanup failed")
      for
        seen <- Ref.make(Chunk.empty[String])
        stopping = ToolResultProcessor.deciding(_ =>
          seen.update(_ :+ "stop") *>
            ZIO.addFinalizer(seen.update(_ :+ "closed")) *>
            ZIO.succeed(ToolResultDecision.Stop(payload, "host decision"))
        )
        later = ToolResultProcessor(_ => seen.update(_ :+ "later") *> ZIO.fail(failure))
        chain = stopping.andThen(later)
        decision      <- ZIO.scoped(chain.processDecision(original))
        afterDecision <- seen.get
        direct        <- ZIO.scoped(chain.process(original)).exit
        afterDirect   <- seen.get
        legacy = ToolResultProcessor(result =>
          seen.update(_ :+ "legacy").as(ToolResultPayload.fromResult(result))
        )
        continued   <- ZIO.scoped(legacy.processDecision(original))
        failed      <- ZIO.scoped(legacy.andThen(later).processDecision(original)).exit
        afterLegacy <- seen.get
        cleanup <- ZIO
          .scoped(
            ToolResultProcessor
              .deciding(_ =>
                ZIO
                  .addFinalizer(ZIO.die(defect))
                  .as(ToolResultDecision.Stop(payload, "not accepted"))
              )
              .processDecision(original)
          )
          .exit
      yield assertTrue(
        decision == ToolResultDecision.Stop(payload, "host decision"),
        afterDecision == Chunk("stop", "closed"),
        direct == Exit.fail(failure),
        afterDirect == Chunk("stop", "closed", "stop", "later", "closed"),
        continued == ToolResultDecision.Continue(ToolResultPayload.fromResult(original)),
        failed == Exit.fail(failure),
        afterLegacy == afterDirect ++ Chunk("legacy", "legacy", "later"),
        cleanup.causeOption.exists(_.defects == List(defect))
      )
    }
  )
