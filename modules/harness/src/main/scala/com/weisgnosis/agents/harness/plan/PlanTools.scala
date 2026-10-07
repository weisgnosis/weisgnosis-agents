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
package com.weisgnosis.agents.harness.plan

import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.tool.{Tool, ToolDef, Toolkit}
import zio.*
import zio.json.*
import zio.json.ast.Json

/** Opt-in model controls; execution approval remains an exact-ticket host decision. */
object PlanTools:
  val access: Map[String, PlanToolAccess] = Map(
    "plan_enter" -> PlanToolAccess.PlanControl,
    "plan_write" -> PlanToolAccess.PlanControl,
    "plan_exit"  -> PlanToolAccess.PlanControl
  )

  def make(control: PlanSessionControl, publisher: PlanReviewPublisher): Toolkit =
    val enter = tool[Unit](
      definition(
        "plan_enter",
        "Enter Plan mode to investigate and prepare a draft before host review.",
        Json.Obj()
      ),
      emptyInput
    )(_ => control.enter.as(PlanToolResult.EnteredPlan))
    val write = tool[PlanDraft](
      definition(
        "plan_write",
        "Replace the complete unapproved draft in Plan mode. This does not approve execution.",
        Json.Obj("content" -> Json.Obj("type" -> Json.Str("string"))),
        Chunk("content")
      ),
      draftInput
    )(draft => control.replaceDraft(draft).as(PlanToolResult.DraftReplaced))
    val exit = tool[Option[String]](
      definition(
        "plan_exit",
        "Request host review of the exact current draft and wait for its decision. Only that approval enters Build mode.",
        Json.Obj("summary" -> Json.Obj("type" -> Json.Str("string")))
      ),
      summaryInput
    )(summary =>
      ZIO.scoped:
        for
          review  <- control.requestReview
          _       <- publisher.publish(PlanReviewRequest(review, summary))
          outcome <- review.await
        yield PlanToolResult.ReviewSettled(outcome)
    )
    Toolkit.single(enter).updated(write).updated(exit)

  private def definition(
      name: String,
      description: String,
      properties: Json,
      required: Chunk[String] = Chunk.empty
  ): ToolDef =
    ToolDef(
      name,
      description,
      Json.Obj(
        "type"                 -> Json.Str("object"),
        "properties"           -> properties,
        "required"             -> Json.Arr(required.map(Json.Str.apply)),
        "additionalProperties" -> Json.Bool(false)
      ),
      isConcurrencySafe = false,
      isReadOnly = false
    )

  private def tool[In](definitionValue: ToolDef, decode: Json => Either[String, In])(
      run: In => IO[AgentError, PlanToolResult]
  ): Tool[Any, In, PlanToolResult] = new Tool[Any, In, PlanToolResult]:
    def definition: ToolDef                                = definitionValue
    def execute(input: In): IO[AgentError, PlanToolResult] = ZIO.suspendSucceed(run(input))
    def decodeInput(raw: Json): Either[String, In]         = decode(raw)
    def encodeOutput(output: PlanToolResult): String       = output.toJson

  private def emptyInput(input: Json): Either[String, Unit] = input match
    case Json.Obj(fields) if fields.isEmpty => Right(())
    case _                                  => Left("Expected an empty object")

  private def draftInput(input: Json): Either[String, PlanDraft] = input match
    case Json.Obj(Chunk(("content", Json.Str(content)))) =>
      PlanDraft.fromString(content).left.map(_.message)
    case _ => Left("Expected exactly one string field named content")

  private def summaryInput(input: Json): Either[String, Option[String]] = input match
    case Json.Obj(fields) if fields.isEmpty              => Right(None)
    case Json.Obj(Chunk(("summary", Json.Str(summary)))) => Right(Some(summary))
    case _ => Left("Expected an empty object or exactly one string field named summary")
