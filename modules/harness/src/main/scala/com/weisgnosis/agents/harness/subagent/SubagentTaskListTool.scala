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
import com.weisgnosis.agents.core.tool.{Tool, ToolDef}
import zio.*
import zio.json.*
import zio.json.ast.Json

enum ListedTaskStatus(val wireName: String):
  case Pending   extends ListedTaskStatus("pending")
  case Running   extends ListedTaskStatus("running")
  case Completed extends ListedTaskStatus("completed")
  case Failed    extends ListedTaskStatus("failed")
  case Cancelled extends ListedTaskStatus("cancelled")
  case Suspended extends ListedTaskStatus("suspended")
  case Stopped   extends ListedTaskStatus("stopped")

object ListedTaskStatus:
  // Lowercase names preserve the model-facing task_list status-filter protocol.
  given JsonCodec[ListedTaskStatus] = JsonCodec.string.transformOrFail(
    value => ListedTaskStatus.values.find(_.wireName == value).toRight("Unknown task status"),
    _.wireName
  )

final case class ListedTask(taskId: String, status: ListedTaskStatus) derives JsonCodec

/** A bounded observation, never an exclusive claim or an execution capability. */
final case class TaskListPage(tasks: Chunk[ListedTask], totalMatching: Int, nextOffset: Option[Int])
    derives JsonCodec

final class TaskListLimits private (val maximumTasks: Int)

object TaskListLimits:
  val default: TaskListLimits = new TaskListLimits(64)

  def make(maximumTasks: Int): Either[AgentError.InvalidConfiguration, TaskListLimits] =
    Either.cond(
      maximumTasks > 0 && maximumTasks <= 256,
      new TaskListLimits(maximumTasks),
      AgentError.InvalidConfiguration("Task-list page size must be between 1 and 256")
    )

/** Opt-in read-only enumeration of one host-selected owned registry. */
object SubagentTaskListTool:
  private enum Filter:
    case All
    case Status(value: ListedTaskStatus)

    def matches(status: ListedTaskStatus): Boolean = this match
      case All           => true
      case Status(value) => value == status

  private object Filter:
    given JsonCodec[Filter] = JsonCodec.string.transformOrFail(
      value =>
        if value == "all" then Right(Filter.All)
        else
          ListedTaskStatus.values
            .find(_.wireName == value)
            .map(Filter.Status(_))
            .toRight("Unknown task status filter")
      ,
      {
        case Filter.All           => "all"
        case Filter.Status(value) => value.wireName
      }
    )

  @jsonNoExtraFields
  final private case class Input(
      @jsonField("status_filter") statusFilter: Option[Filter] = None,
      offset: Int = 0
  ) derives JsonCodec

  def make(
      registry: SubagentTaskRegistry,
      limits: TaskListLimits = TaskListLimits.default
  ): Tool[Any, ?, TaskListPage] =
    val schema = Json.Obj(
      "type" -> Json.Str("object"),
      "properties" -> Json.Obj(
        "status_filter" -> Json.Obj(
          "type" -> Json.Str("string"),
          "enum" -> Json.Arr(
            Chunk(Json.Str("all")) ++ Chunk
              .fromIterable(ListedTaskStatus.values)
              .map(value => Json.Str(value.wireName))
          )
        ),
        "offset" -> Json.Obj("type" -> Json.Str("integer"), "minimum" -> Json.Num(0))
      ),
      "additionalProperties" -> Json.Bool(false)
    )
    new Tool[Any, Input, TaskListPage]:
      def definition: ToolDef = ToolDef(
        "task_list",
        "List tasks in the host-selected registry without executing work. " +
          "Optionally filter by status and continue from nextOffset. Concurrent changes can shift pages.",
        schema,
        isConcurrencySafe = true,
        isReadOnly = true
      )
      def decodeInput(raw: Json): Either[String, Input] =
        JsonBoundary
          .validate(raw)
          .flatMap(_.as[Input])
          .flatMap: input =>
            Either.cond(input.offset >= 0, input, "Task-list offset must be nonnegative")
      def encodeOutput(output: TaskListPage): String = output.toJson
      def execute(input: Input): IO[AgentError, TaskListPage] =
        ZIO.suspendSucceed:
          if input.offset < 0 then
            ZIO.fail(AgentError.InvalidMessage("Task-list offset must be nonnegative"))
          else
            registry.observations.map: snapshots =>
              val matching =
                snapshots
                  .map(project)
                  .filter(task => input.statusFilter.forall(_.matches(task.status)))
              val page = matching.drop(input.offset).take(limits.maximumTasks)
              val next =
                if page.nonEmpty && page.size < matching.size - input.offset then
                  Some(input.offset + page.size)
                else None
              TaskListPage(page, matching.size, next)

  private def project(snapshot: SubagentTaskObservation): ListedTask =
    val status = snapshot.state match
      case SubagentTaskObservationState.Running => ListedTaskStatus.Running
      case SubagentTaskObservationState.Stored(SubagentStoredTaskState.Pending) =>
        ListedTaskStatus.Pending
      case SubagentTaskObservationState.Stored(SubagentStoredTaskState.Running) =>
        ListedTaskStatus.Running
      case SubagentTaskObservationState.Terminal(outcome) =>
        outcome match
          case SubagentTerminalOutcome.Completed(_) => ListedTaskStatus.Completed
          case SubagentTerminalOutcome.Suspended(_) => ListedTaskStatus.Suspended
          case SubagentTerminalOutcome.Stopped(_)   => ListedTaskStatus.Stopped
          case SubagentTerminalOutcome.Failed(_, _) => ListedTaskStatus.Failed
          case SubagentTerminalOutcome.Interrupted(failures, defects) =>
            if failures.isEmpty && defects == SubagentTaskDefects.Absent then
              ListedTaskStatus.Cancelled
            else ListedTaskStatus.Failed
    ListedTask(snapshot.id.value.toString, status)
