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

import com.weisgnosis.agents.core.agent.{AgentResult, AgentRunState}
import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.model.Msg
import com.weisgnosis.agents.core.tool.{Tool, ToolDef, Toolkit}
import java.util.UUID
import zio.*
import zio.json.*
import zio.json.ast.Json

/** Bounded text projection; the registry retains the original result. */
final case class SubagentToolText(text: String, omittedCharacters: Int) derives JsonCodec

enum SubagentToolState derives JsonCodec:
  case Running, Promoted, Interrupted, Missing
  case Completed(reply: SubagentToolText)
  case Suspended(reply: SubagentToolText)
  case Failed(message: SubagentToolText)

enum SubagentToolRejection derives JsonCodec:
  case Capacity, Closed, IdentityCollision

/** Model observations contain identifiers and bounded summaries, never live task capabilities. */
enum SubagentToolResult derives JsonCodec:
  case Task(taskId: String, state: SubagentToolState)
  case Rejected(reason: SubagentToolRejection)

/** Opt-in controls bound to one trusted registry, parent lineage and configured child factory. */
object SubagentTools:
  val MaximumTextCharacters: Int = 4000

  private enum Access:
    case Observation, Control

  def make(registry: SubagentTaskRegistry): Toolkit =
    val spawn = tool[String](
      definition(
        "agent_spawn",
        "Run the configured child. After 30 seconds, return its retained task ID without restarting it.",
        "prompt",
        Access.Control
      ),
      stringField("prompt")
    )(prompt =>
      ZIO.scoped:
        registry
          .submit(Msg.user(prompt))
          .flatMap:
            case SubagentTaskSubmission.Accepted(id) =>
              registry
                .awaitOrPromote(id)
                .flatMap:
                  case Some(TaskWait.Promoted) => ZIO.succeed(task(id, SubagentToolState.Promoted))
                  case Some(TaskWait.Completed(exit)) =>
                    exit match
                      case Exit.Failure(_) => fail("agent_spawn", project(id, exit))
                      case Exit.Success(result) if result.state == AgentRunState.Interrupted =>
                        fail("agent_spawn", project(id, exit))
                      case Exit.Success(_) => ZIO.succeed(project(id, exit))
                  case None => fail("agent_spawn", task(id, SubagentToolState.Missing))
            case SubagentTaskSubmission.Capacity =>
              fail("agent_spawn", SubagentToolResult.Rejected(SubagentToolRejection.Capacity))
            case SubagentTaskSubmission.Closed =>
              fail("agent_spawn", SubagentToolResult.Rejected(SubagentToolRejection.Closed))
            case SubagentTaskSubmission.IdentityCollision =>
              fail(
                "agent_spawn",
                SubagentToolResult.Rejected(SubagentToolRejection.IdentityCollision)
              )
    )
    val inspect = tool[SubagentTaskId](
      definition(
        "agent_task",
        "Inspect a retained task without waiting, restarting it or making a model request.",
        "task_id",
        Access.Observation
      ),
      taskIdInput
    )(id =>
      registry
        .inspect(id)
        .flatMap:
          case Some(SubagentTaskSnapshot(_, SubagentTaskState.Running)) =>
            ZIO.succeed(task(id, SubagentToolState.Running))
          case Some(SubagentTaskSnapshot(_, SubagentTaskState.Completed(exit))) =>
            ZIO.succeed(project(id, exit))
          case None => fail("agent_task", task(id, SubagentToolState.Missing))
    )
    val cancel = tool[SubagentTaskId](
      definition(
        "agent_cancel",
        "Cancel the tracked task and wait for its owned cleanup. Completed results remain unchanged.",
        "task_id",
        Access.Control
      ),
      taskIdInput
    )(id =>
      registry
        .cancel(id)
        .flatMap:
          case Some(exit) => ZIO.succeed(project(id, exit))
          case None       => fail("agent_cancel", task(id, SubagentToolState.Missing))
    )
    Toolkit(Map("agent_spawn" -> spawn, "agent_task" -> inspect, "agent_cancel" -> cancel))

  private def definition(
      name: String,
      description: String,
      field: String,
      access: Access
  ): ToolDef =
    ToolDef(
      name,
      description,
      Json.Obj(
        "type"                 -> Json.Str("object"),
        "properties"           -> Json.Obj(field -> Json.Obj("type" -> Json.Str("string"))),
        "required"             -> Json.Arr(Json.Str(field)),
        "additionalProperties" -> Json.Bool(false)
      ),
      isConcurrencySafe = true,
      isReadOnly = access == Access.Observation
    )

  private def tool[In](definitionValue: ToolDef, decode: Json => Either[String, In])(
      run: In => IO[AgentError, SubagentToolResult]
  ): Tool[Any, In, SubagentToolResult] = new Tool[Any, In, SubagentToolResult]:
    def definition: ToolDef                                    = definitionValue
    def execute(input: In): IO[AgentError, SubagentToolResult] = ZIO.suspendSucceed(run(input))
    def decodeInput(raw: Json): Either[String, In]             = decode(raw)
    def encodeOutput(output: SubagentToolResult): String       = output.toJson

  private def stringField(name: String)(input: Json): Either[String, String] = input match
    case Json.Obj(Chunk((field, Json.Str(value)))) if field == name => Right(value)
    case _ => Left(s"Expected exactly one string field named $name")

  private def taskIdInput(input: Json): Either[String, SubagentTaskId] =
    stringField("task_id")(input).flatMap: value =>
      if value.matches(
          "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
        )
      then Json.Str(value).as[UUID].map(SubagentTaskId(_))
      else Left("task_id must be a canonical UUID string")

  private def task(id: SubagentTaskId, state: SubagentToolState): SubagentToolResult =
    SubagentToolResult.Task(id.value.toString, state)

  private def project(id: SubagentTaskId, exit: Exit[AgentError, AgentResult]): SubagentToolResult =
    val state = exit match
      case Exit.Success(result) =>
        result.state match
          case AgentRunState.Completed   => SubagentToolState.Completed(preview(result.reply.text))
          case AgentRunState.Suspended   => SubagentToolState.Suspended(preview(result.reply.text))
          case AgentRunState.Interrupted => SubagentToolState.Interrupted
      case Exit.Failure(cause) if cause.isInterrupted => SubagentToolState.Interrupted
      case Exit.Failure(cause) =>
        val message =
          cause.failureOption.map(_.message).getOrElse("Task failed with an internal defect")
        val suffix =
          if cause.failureOption.nonEmpty && cause.defects.nonEmpty then
            "; task also reported an internal defect"
          else ""
        SubagentToolState.Failed(preview(message + suffix))
    task(id, state)

  private def preview(text: String): SubagentToolText =
    if text.length <= MaximumTextCharacters then SubagentToolText(text, 0)
    else
      val marker     = "\n...\n"
      val headBudget = (MaximumTextCharacters - marker.length) / 2
      val rawHead    = text.take(headBudget)
      val rawTail    = text.takeRight(MaximumTextCharacters - marker.length - headBudget)
      val head =
        if rawHead.lastOption.exists(Character.isHighSurrogate) then rawHead.dropRight(1)
        else rawHead
      val tail =
        if rawTail.headOption.exists(Character.isLowSurrogate) then rawTail.drop(1) else rawTail
      SubagentToolText(head + marker + tail, text.length - head.length - tail.length)

  private def fail(name: String, result: SubagentToolResult): IO[AgentError, Nothing] =
    ZIO.fail(AgentError.ToolExecutionError(name, result.toJson))
