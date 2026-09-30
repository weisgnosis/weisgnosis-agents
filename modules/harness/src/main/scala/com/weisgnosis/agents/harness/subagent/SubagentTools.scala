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

/** Routed observations retain the selected membership without exposing a session capability. */
enum SubagentSessionToolResult derives JsonCodec:
  case Session(sessionId: String, result: SubagentToolResult)
  case Missing(sessionId: String)

/** Opt-in controls bound to trusted host capabilities and their configured child factories. */
object SubagentTools:
  val MaximumTextCharacters: Int = 4000

  private enum Access:
    case Observation, Control

  private enum Invocation(val name: String, val description: String):
    case Spawn
        extends Invocation(
          "agent_spawn",
          "Run the configured child. After 30 seconds, return its retained task ID without restarting it."
        )
    case Send
        extends Invocation(
          "agent_send",
          "Send a prompt to the bound child session. After 30 seconds, return its retained task ID without restarting it."
        )

  def make(registry: SubagentTaskRegistry): Toolkit =
    controls(registry, Invocation.Spawn)

  /** Reuse one host-selected session; task inspection and cancellation share its execution registry. */
  def forSession(session: SubagentSession): Toolkit =
    controls(session.executions, Invocation.Send)

  /** Route only to ready memberships in this host-selected directory; no creation authority. */
  def forDirectory(directory: SubagentSessionDirectory): Toolkit =
    val send = routedTool[String](
      directory,
      "agent_send",
      "Send a prompt to a registered child session. After 30 seconds, return its retained task ID.",
      "prompt",
      Access.Control,
      Right(_)
    )(submit)
    val inspect = routedTool[SubagentTaskId](
      directory,
      "agent_task",
      "Inspect a retained task in the selected session without making a model request.",
      "task_id",
      Access.Observation,
      value => uuid("task_id", value).map(SubagentTaskId(_))
    )(inspectTask)
    val cancel = routedTool[SubagentTaskId](
      directory,
      "agent_cancel",
      "Cancel a task in the selected session and wait for its owned cleanup.",
      "task_id",
      Access.Control,
      value => uuid("task_id", value).map(SubagentTaskId(_))
    )(cancelTask)
    Toolkit(Map("agent_send" -> send, "agent_task" -> inspect, "agent_cancel" -> cancel))

  private enum Observation[+A]:
    case Success(value: A)
    case Error(value: A)

    def map[B](f: A => B): Observation[B] = this match
      case Success(value) => Success(f(value))
      case Error(value)   => Error(f(value))

  final private case class RoutedInput[A](sessionId: SubagentSessionId, value: A)

  private def controls(registry: SubagentTaskRegistry, invocation: Invocation): Toolkit =
    val invoke = tool[String, SubagentToolResult](
      definition(invocation.name, invocation.description, Chunk("prompt"), Access.Control),
      stringField("prompt")
    )(prompt => submit(registry, prompt).flatMap(respond(invocation.name, _)))
    val inspect = tool[SubagentTaskId, SubagentToolResult](
      definition(
        "agent_task",
        "Inspect a retained task without waiting, restarting it or making a model request.",
        Chunk("task_id"),
        Access.Observation
      ),
      taskIdInput
    )(id => inspectTask(registry, id).flatMap(respond("agent_task", _)))
    val cancel = tool[SubagentTaskId, SubagentToolResult](
      definition(
        "agent_cancel",
        "Cancel the tracked task and wait for its owned cleanup. Completed results remain unchanged.",
        Chunk("task_id"),
        Access.Control
      ),
      taskIdInput
    )(id => cancelTask(registry, id).flatMap(respond("agent_cancel", _)))
    Toolkit(Map(invocation.name -> invoke, "agent_task" -> inspect, "agent_cancel" -> cancel))

  private def submit(
      registry: SubagentTaskRegistry,
      prompt: String
  ): IO[AgentError, Observation[SubagentToolResult]] =
    ZIO.scoped:
      registry
        .submit(Msg.user(prompt))
        .flatMap:
          case SubagentTaskSubmission.Accepted(id) =>
            registry
              .awaitOrPromote(id)
              .map:
                case Some(TaskWait.Promoted) =>
                  Observation.Success(task(id, SubagentToolState.Promoted))
                case Some(TaskWait.Completed(exit)) =>
                  exit match
                    case Exit.Failure(_) => Observation.Error(project(id, exit))
                    case Exit.Success(result) if result.state == AgentRunState.Interrupted =>
                      Observation.Error(project(id, exit))
                    case Exit.Success(_) => Observation.Success(project(id, exit))
                case None => Observation.Error(task(id, SubagentToolState.Missing))
          case SubagentTaskSubmission.Capacity =>
            ZIO.succeed(
              Observation.Error(SubagentToolResult.Rejected(SubagentToolRejection.Capacity))
            )
          case SubagentTaskSubmission.Closed =>
            ZIO.succeed(
              Observation.Error(SubagentToolResult.Rejected(SubagentToolRejection.Closed))
            )
          case SubagentTaskSubmission.IdentityCollision =>
            ZIO.succeed(
              Observation.Error(
                SubagentToolResult.Rejected(SubagentToolRejection.IdentityCollision)
              )
            )

  private def inspectTask(
      registry: SubagentTaskRegistry,
      id: SubagentTaskId
  ): UIO[Observation[SubagentToolResult]] =
    registry
      .inspect(id)
      .map:
        case Some(SubagentTaskSnapshot(_, SubagentTaskState.Running)) =>
          Observation.Success(task(id, SubagentToolState.Running))
        case Some(SubagentTaskSnapshot(_, SubagentTaskState.Completed(exit))) =>
          Observation.Success(project(id, exit))
        case None => Observation.Error(task(id, SubagentToolState.Missing))

  private def cancelTask(
      registry: SubagentTaskRegistry,
      id: SubagentTaskId
  ): UIO[Observation[SubagentToolResult]] =
    registry
      .cancel(id)
      .map:
        case Some(exit) => Observation.Success(project(id, exit))
        case None       => Observation.Error(task(id, SubagentToolState.Missing))

  private def routedTool[In](
      directory: SubagentSessionDirectory,
      name: String,
      description: String,
      field: String,
      access: Access,
      decodeValue: String => Either[String, In]
  )(
      run: (SubagentTaskRegistry, In) => IO[AgentError, Observation[SubagentToolResult]]
  ): Tool[Any, RoutedInput[In], SubagentSessionToolResult] =
    tool[RoutedInput[In], SubagentSessionToolResult](
      definition(name, description, Chunk("session_id", field), access),
      raw =>
        pairedStrings(field)(raw).flatMap: (session, value) =>
          for
            id    <- uuid("session_id", session).map(SubagentSessionId(_))
            input <- decodeValue(value)
          yield RoutedInput(id, input)
    )(input =>
      directory
        .lookup(input.sessionId)
        .flatMap:
          case None =>
            respond[SubagentSessionToolResult](
              name,
              Observation.Error(SubagentSessionToolResult.Missing(input.sessionId.value.toString))
            )
          case Some(session) =>
            run(session.executions, input.value).flatMap(observation =>
              respond[SubagentSessionToolResult](
                name,
                observation.map(result =>
                  SubagentSessionToolResult.Session(input.sessionId.value.toString, result)
                )
              )
            )
    )

  private def definition(
      name: String,
      description: String,
      fields: Chunk[String],
      access: Access
  ): ToolDef =
    ToolDef(
      name,
      description,
      Json.Obj(
        "type" -> Json.Str("object"),
        "properties" -> Json.Obj(
          fields.map(field => field -> Json.Obj("type" -> Json.Str("string")))
        ),
        "required"             -> Json.Arr(fields.map(Json.Str(_))),
        "additionalProperties" -> Json.Bool(false)
      ),
      isConcurrencySafe = true,
      isReadOnly = access == Access.Observation
    )

  private def tool[In, Out: JsonEncoder](
      definitionValue: ToolDef,
      decode: Json => Either[String, In]
  )(
      run: In => IO[AgentError, Out]
  ): Tool[Any, In, Out] = new Tool[Any, In, Out]:
    def definition: ToolDef                        = definitionValue
    def execute(input: In): IO[AgentError, Out]    = ZIO.suspendSucceed(run(input))
    def decodeInput(raw: Json): Either[String, In] = decode(raw)
    def encodeOutput(output: Out): String          = output.toJson

  private def respond[A: JsonEncoder](
      name: String,
      observation: Observation[A]
  ): IO[AgentError, A] =
    observation match
      case Observation.Success(value) => ZIO.succeed(value)
      case Observation.Error(value)   => ZIO.fail(AgentError.ToolExecutionError(name, value.toJson))

  private def stringField(name: String)(input: Json): Either[String, String] = input match
    case Json.Obj(Chunk((field, Json.Str(value)))) if field == name => Right(value)
    case _ => Left(s"Expected exactly one string field named $name")

  private def pairedStrings(name: String)(input: Json): Either[String, (String, String)] =
    input match
      case Json.Obj(Chunk(("session_id", Json.Str(session)), (field, Json.Str(value))))
          if field == name =>
        Right((session, value))
      case Json.Obj(Chunk((field, Json.Str(value)), ("session_id", Json.Str(session))))
          if field == name =>
        Right((session, value))
      case _ => Left(s"Expected exactly two string fields named session_id and $name")

  private def taskIdInput(input: Json): Either[String, SubagentTaskId] =
    stringField("task_id")(input).flatMap(value => uuid("task_id", value).map(SubagentTaskId(_)))

  private def uuid(name: String, value: String): Either[String, UUID] =
    if value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    then Json.Str(value).as[UUID]
    else Left(s"$name must be a canonical UUID string")

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
