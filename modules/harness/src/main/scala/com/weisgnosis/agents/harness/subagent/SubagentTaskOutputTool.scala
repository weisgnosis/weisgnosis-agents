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
package com.weisgnosis.agents.harness.subagent

import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.json.JsonBoundary
import com.weisgnosis.agents.core.tool.{Tool, ToolDef, ToolInvocation}
import java.time.Instant
import java.util.UUID
import zio.*
import zio.json.*
import zio.json.ast.Json

/** Full terminal text, not a preview or an acknowledgement of model consumption. */
enum SubagentTaskOutputResult derives JsonCodec:
  case Running(taskId: String, createdAt: Option[Instant] = None)
  case PublicationPending(taskId: String, createdAt: Option[Instant] = None)
  case Terminal(
      taskId: String,
      workerId: Option[SubagentWorkerId],
      outcome: SubagentTerminalOutcome,
      createdAt: Option[Instant] = None
  )

/** Only the joined task/publication wait is timed; hosts bound lookup and readiness backend calls. */
object SubagentTaskOutputTool:
  final class WaitTimeout private (val milliseconds: Long):
    def duration: Duration = Duration.fromMillis(milliseconds)

  object WaitTimeout:
    def fromMilliseconds(value: Option[Long] = None): WaitTimeout =
      new WaitTimeout(value.fold(30000L)(_.max(0L).min(600000L)))

  private enum Mode:
    case Poll
    case Wait(timeout: WaitTimeout)

  @jsonNoExtraFields
  final private case class WireInput(
      @jsonField("task_id") taskId: String,
      block: Option[Boolean] = None,
      timeout: Option[Long] = None
  ) derives JsonCodec

  final private case class Input(id: SubagentTaskId, mode: Mode)

  def make(registry: SubagentTaskRegistry): Tool[Any, ?, SubagentTaskOutputResult] =
    tool(registry, None)

  private[subagent] def consuming(
      registry: SubagentTaskRegistry,
      capture: SubagentTaskConsumptionCapture
  ): Tool[Any, ?, SubagentTaskOutputResult] = tool(registry, Some(capture))

  private def tool(
      registry: SubagentTaskRegistry,
      capture: Option[SubagentTaskConsumptionCapture]
  ): Tool[Any, ?, SubagentTaskOutputResult] =
    new Tool[Any, Input, SubagentTaskOutputResult]:
      def definition: ToolDef = ToolDef(
        "task_output",
        (capture match
          case None => "Read a task's exact full terminal text without acknowledging delivery. "
          case Some(_) =>
            "Read full terminal text; unchanged persisted results are acknowledged within the middleware receipt capacity. "
        ) + "block defaults to true; timeout is milliseconds, default 30000, clamped to 0..600000. " +
          "Use block=false or timeout=0 for a nonwaiting status check. " +
          "PublicationPending means execution joined but its durable receipt is not ready. " +
          "Lookup/backend readiness are host-bounded separately from the wait timeout.",
        Json.Obj(
          "type" -> Json.Str("object"),
          "properties" -> Json.Obj(
            "task_id" -> Json.Obj("type" -> Json.Str("string")),
            "block"   -> Json.Obj("type" -> Json.Str("boolean")),
            "timeout" -> Json.Obj("type" -> Json.Str("integer"))
          ),
          "required"             -> Json.Arr(Json.Str("task_id")),
          "additionalProperties" -> Json.Bool(false)
        ),
        isConcurrencySafe = true,
        isReadOnly = true
      )
      def decodeInput(raw: Json): Either[String, Input] =
        for
          validated <- JsonBoundary.validate(raw)
          wire      <- validated.as[WireInput]
          _ <- Either.cond(
            wire.taskId.matches(
              "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
            ),
            (),
            "task_id must be a canonical UUID string"
          )
          uuid <- Json.Str(wire.taskId).as[UUID]
          mode =
            if wire.block.contains(false) then Mode.Poll
            else Mode.Wait(WaitTimeout.fromMilliseconds(wire.timeout))
        yield Input(SubagentTaskId(uuid), mode)

      def encodeOutput(output: SubagentTaskOutputResult): String = output.toJson

      def execute(input: Input): IO[AgentError, SubagentTaskOutputResult] =
        executeWithContext(input, ToolInvocation.Detached)

      override def executeWithContext(
          input: Input,
          invocation: ToolInvocation
      ): IO[AgentError, SubagentTaskOutputResult] = capture match
        case None       => read(input)
        case Some(sink) => sink.output(input.id, registry, invocation)(read(input))

      private def read(input: Input): IO[AgentError, SubagentTaskOutputResult] =
        for
          selection <- ZIO.fromEither(SubagentTaskSelection.selected(Chunk(input.id)))
          captured  <- registry.captureWait(selection)
          entry <- ZIO
            .fromOption(captured.headOption)
            .orElseFail(
              AgentError.InvalidConfiguration(
                "No task with this ID exists in the selected registry; reconcile it with task_list if installed"
              )
            )
          metadata <- entry.checkedMetadata
          createdAt = metadata.map(_.createdAt)
          ready <- entry.readiness
          result <- ready match
            case SubagentCapturedReadiness.Ready(record) => ZIO.succeed(terminal(record, createdAt))
            case pending =>
              input.mode match
                case Mode.Poll => ZIO.succeed(status(input.id, pending, createdAt))
                case Mode.Wait(timeout) if timeout.milliseconds == 0L =>
                  ZIO.succeed(status(input.id, pending, createdAt))
                case Mode.Wait(timeout) =>
                  SubagentTaskBarrier
                    .raceWithSignal(entry.await, ZIO.sleep(timeout.duration).as(Exit.unit))
                    .flatMap:
                      case Some(record) => ZIO.succeed(terminal(record, createdAt))
                      case None         => entry.readiness.map(status(input.id, _, createdAt))
        yield result

  private def terminal(
      record: SubagentTerminalRecord,
      createdAt: Option[Instant]
  ): SubagentTaskOutputResult =
    SubagentTaskOutputResult.Terminal(
      record.id.value.toString,
      record.workerId,
      record.outcome,
      createdAt
    )

  private def status(
      id: SubagentTaskId,
      readiness: SubagentCapturedReadiness,
      createdAt: Option[Instant]
  ): SubagentTaskOutputResult = readiness match
    case SubagentCapturedReadiness.Ready(record) => terminal(record, createdAt)
    case SubagentCapturedReadiness.Running =>
      SubagentTaskOutputResult.Running(id.value.toString, createdAt)
    case SubagentCapturedReadiness.PublicationPending =>
      SubagentTaskOutputResult.PublicationPending(id.value.toString, createdAt)
