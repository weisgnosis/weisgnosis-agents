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
import com.weisgnosis.agents.core.tool.{Tool, ToolDef, ToolEventPort, ToolInvocation, Toolkit}
import java.util.UUID
import zio.*
import zio.json.*
import zio.json.ast.Json

/**
 * Private trusted task-result boundary. The sink borrows resolved registry capabilities only for
 * acknowledgement; it neither owns their lifetime nor retains invocation event ports.
 */
private[subagent] trait SubagentTaskConsumptionCapture:
  def inspect[Out: JsonEncoder](
      id: SubagentTaskId,
      invocation: ToolInvocation
  )(resolve: => IO[AgentError, SubagentTaskRegistry])(
      read: SubagentTaskRegistry => IO[AgentError, (SubagentToolResult, Out)]
  ): IO[AgentError, Out]

  def output(
      id: SubagentTaskId,
      registry: SubagentTaskRegistry,
      invocation: ToolInvocation
  )(read: => IO[AgentError, SubagentTaskOutputResult]): IO[AgentError, SubagentTaskOutputResult]

  def waitFor(registry: SubagentTaskRegistry, invocation: ToolInvocation)(
      read: => IO[AgentError, SubagentWaitOutcome]
  ): IO[AgentError, SubagentWaitOutcome]

/** Bounded text projection; the registry retains the original result. */
final case class SubagentToolText(text: String, omittedCharacters: Int) derives JsonCodec

enum SubagentToolState derives JsonCodec:
  case Running, Promoted, Interrupted, Missing
  case Completed(reply: SubagentToolText)
  case Suspended(reply: SubagentToolText)
  case Stopped(reason: SubagentToolText)
  case Failed(message: SubagentToolText)

enum SubagentToolRejection derives JsonCodec:
  case Capacity, Closed, IdentityCollision

/** Model observations contain identifiers and bounded summaries, never live task capabilities. */
enum SubagentToolResult derives JsonCodec:
  case Task(taskId: String, state: SubagentToolState)
  case Rejected(reason: SubagentToolRejection)
  case TimedOut(message: SubagentToolText)

/** Typed local-barrier observations; incomplete admission does not imply an empty wait set. */
enum SubagentWaitToolResult derives JsonCodec:
  case Completed(tasks: Chunk[SubagentToolResult])
  case TimedOut(initialIds: Chunk[String], remainingIds: Chunk[String])
  case Empty, AdmissionTimedOut, InboxArrived, NoTasks, AllTerminal
  case BudgetExhausted(alternatives: Chunk[String])
  case InboxTimedOut(emptyWaits: Int, alternatives: Chunk[String])

/** Routed observations retain the selected membership without exposing a session capability. */
enum SubagentSessionToolResult derives JsonCodec:
  case Session(sessionId: String, result: SubagentToolResult)
  case Missing(sessionId: String)

/** Catalog selection is descriptive identity, never a factory or parent capability. */
enum SubagentCatalogToolResult derives JsonCodec:
  case Agent(agentId: String, result: SubagentToolResult)
  case Missing(agentId: String)
  case Failed(agentId: String, message: SubagentToolText)

/** Opt-in controls bound to trusted host capabilities and their configured child factories. */
object SubagentTools:
  val MaximumTextCharacters: Int = 4000

  private enum Access:
    case Observation, Control

  private enum Invocation(val name: String, val description: String):
    case Spawn
        extends Invocation(
          "agent_spawn",
          "Run the configured child."
        )
    case Send
        extends Invocation(
          "agent_send",
          "Send a prompt to the bound child session."
        )

  def make(registry: SubagentTaskRegistry): Toolkit =
    make(registry, SubagentExecutionPolicy.PromoteAfter30Seconds)

  def make(registry: SubagentTaskRegistry, policy: SubagentExecutionPolicy): Toolkit =
    controls(registry, Invocation.Spawn, policy)

  /** Own a session budget and borrow the explicit host inbox; no state is allocated by make. */
  def open(
      registry: SubagentTaskRegistry,
      inbox: SubagentInbox,
      externalWork: SubagentExternalWorkProbe = SubagentExternalWorkProbe.idle,
      policy: SubagentExecutionPolicy = SubagentExecutionPolicy.PromoteAfter30Seconds
  ): ZIO[Scope, Nothing, Toolkit] =
    SubagentWaitSession
      .open(inbox, Some(registry), externalWork)
      .map(session => controls(registry, Invocation.Spawn, policy, waitSession = Some(session)))

  private[subagent] def waitSessionTools(session: SubagentWaitSession): Toolkit =
    Toolkit.single(waitTool(None, None, Some(session)))

  private[subagent] def consuming(
      registry: SubagentTaskRegistry,
      policy: SubagentExecutionPolicy,
      capture: SubagentTaskConsumptionCapture,
      waitSession: Option[SubagentWaitSession]
  ): Toolkit = controls(registry, Invocation.Spawn, policy, Some(capture), waitSession)

  private[subagent] def consumingSession(
      session: SubagentSession,
      policy: SubagentExecutionPolicy,
      capture: SubagentTaskConsumptionCapture
  ): Toolkit = controls(session.executions, Invocation.Send, policy, Some(capture))

  private[subagent] def consumingCatalog(
      catalog: SubagentCatalog,
      policy: SubagentExecutionPolicy,
      capture: SubagentTaskConsumptionCapture
  ): Toolkit = namedControls(
    catalog.entries,
    name => ZIO.succeed(catalog.lookup(name)),
    Invocation.Spawn,
    policy,
    Some(capture)
  )

  private[subagent] def consumingSessionCatalog(
      catalog: SubagentSessionCatalog,
      policy: SubagentExecutionPolicy,
      capture: SubagentTaskConsumptionCapture
  ): Toolkit = namedControls(
    catalog.entries,
    name => catalog.lookup(name).map(_.map(_._2.executions)),
    Invocation.Send,
    policy,
    Some(capture)
  )

  private[subagent] def consumingDirectory(
      directory: SubagentSessionDirectory,
      policy: SubagentExecutionPolicy,
      capture: SubagentTaskConsumptionCapture
  ): Toolkit = directoryControls(directory, policy, Some(capture))

  /** Reuse one host-selected session; task inspection and cancellation share its execution registry. */
  def forSession(session: SubagentSession): Toolkit =
    forSession(session, SubagentExecutionPolicy.PromoteAfter30Seconds)

  def forSession(session: SubagentSession, policy: SubagentExecutionPolicy): Toolkit =
    controls(session.executions, Invocation.Send, policy)

  /** Select fresh children only from this fixed host catalog; task operations keep that selection. */
  def forCatalog(catalog: SubagentCatalog): Toolkit =
    forCatalog(catalog, SubagentExecutionPolicy.PromoteAfter30Seconds)

  def forCatalog(catalog: SubagentCatalog, policy: SubagentExecutionPolicy): Toolkit =
    namedControls(
      catalog.entries,
      name => ZIO.succeed(catalog.lookup(name)),
      Invocation.Spawn,
      policy
    )

  /** Send to live named sessions installed by the host; removed names are never recreated. */
  def forSessionCatalog(catalog: SubagentSessionCatalog): Toolkit =
    forSessionCatalog(catalog, SubagentExecutionPolicy.PromoteAfter30Seconds)

  def forSessionCatalog(catalog: SubagentSessionCatalog, policy: SubagentExecutionPolicy): Toolkit =
    namedControls(
      catalog.entries,
      name => catalog.lookup(name).map(_.map(_._2.executions)),
      Invocation.Send,
      policy
    )

  private def namedControls(
      entries: Chunk[SubagentEntry],
      lookup: String => UIO[Option[SubagentTaskRegistry]],
      invocation: Invocation,
      policy: SubagentExecutionPolicy,
      capture: Option[SubagentTaskConsumptionCapture] = None
  ): Toolkit =
    val description = invocation match
      case Invocation.Spawn =>
        "Run the selected configured child."
      case Invocation.Send =>
        "Send a prompt to the selected child session."
    val invoke = catalogTool[String](
      entries,
      lookup,
      invocation.name,
      description + " " + waitDescription(policy),
      "prompt",
      Access.Control,
      Right(_)
    )((registry, prompt, events) => submit(registry, prompt, events, policy))
    val inspect = catalogTool[SubagentTaskId](
      entries,
      lookup,
      "agent_task",
      "Inspect a retained task of the selected child without making a model request.",
      "task_id",
      Access.Observation,
      value => uuid("task_id", value).map(SubagentTaskId(_)),
      capture.map(sink => InspectionCapture[SubagentTaskId](sink, identity))
    )((registry, id, _) => inspectTask(registry, id))
    val cancel = catalogTool[SubagentTaskId](
      entries,
      lookup,
      "agent_cancel",
      "Cancel a task of the selected child and wait for its owned cleanup.",
      "task_id",
      Access.Control,
      value => uuid("task_id", value).map(SubagentTaskId(_))
    )((registry, id, _) => cancelTask(registry, id))
    Toolkit.single(invoke).updated(inspect).updated(cancel)

  /** Route only to ready memberships in this host-selected directory; no creation authority. */
  def forDirectory(directory: SubagentSessionDirectory): Toolkit =
    forDirectory(directory, SubagentExecutionPolicy.PromoteAfter30Seconds)

  def forDirectory(directory: SubagentSessionDirectory, policy: SubagentExecutionPolicy): Toolkit =
    directoryControls(directory, policy, None)

  private def directoryControls(
      directory: SubagentSessionDirectory,
      policy: SubagentExecutionPolicy,
      capture: Option[SubagentTaskConsumptionCapture]
  ): Toolkit =
    val send = routedTool[String](
      directory,
      "agent_send",
      "Send a prompt to a registered child session. " + waitDescription(policy),
      "prompt",
      Access.Control,
      Right(_)
    )((registry, prompt, events) => submit(registry, prompt, events, policy))
    val inspect = routedTool[SubagentTaskId](
      directory,
      "agent_task",
      "Inspect a retained task in the selected session without making a model request.",
      "task_id",
      Access.Observation,
      value => uuid("task_id", value).map(SubagentTaskId(_)),
      capture.map(sink => InspectionCapture[SubagentTaskId](sink, identity))
    )((registry, id, _) => inspectTask(registry, id))
    val cancel = routedTool[SubagentTaskId](
      directory,
      "agent_cancel",
      "Cancel a task in the selected session and wait for its owned cleanup.",
      "task_id",
      Access.Control,
      value => uuid("task_id", value).map(SubagentTaskId(_))
    )((registry, id, _) => cancelTask(registry, id))
    Toolkit.single(send).updated(inspect).updated(cancel)

  private enum Observation[+A]:
    case Success(value: A)
    case Error(value: A)

    def map[B](f: A => B): Observation[B] = this match
      case Success(value) => Success(f(value))
      case Error(value)   => Error(f(value))

  final private case class InspectionCapture[A](
      sink: SubagentTaskConsumptionCapture,
      taskId: A => SubagentTaskId
  )

  final private case class RoutedInput[A](sessionId: SubagentSessionId, value: A)
  final private case class CatalogInput[A](agentId: String, value: A)

  private def waitDescription(policy: SubagentExecutionPolicy): String = policy match
    case SubagentExecutionPolicy.PromoteAfter30Seconds =>
      "After 30 seconds, return its retained task ID without restarting it."
    case SubagentExecutionPolicy.Synchronous(timeout) =>
      s"Wait up to ${timeout.duration} without background promotion. On timeout, cancel and join cleanup before returning an error; cleanup may exceed the wait budget."

  private def controls(
      registry: SubagentTaskRegistry,
      invocation: Invocation,
      policy: SubagentExecutionPolicy,
      capture: Option[SubagentTaskConsumptionCapture] = None,
      waitSession: Option[SubagentWaitSession] = None
  ): Toolkit =
    val invoke = tool[String, SubagentToolResult](
      definition(
        invocation.name,
        invocation.description + " " + waitDescription(policy),
        Chunk("prompt"),
        Access.Control
      ),
      stringField("prompt")
    )((prompt, events) =>
      submit(registry, prompt, events, policy).flatMap(respond(invocation.name, _))
    )
    val inspect = invocationTool[SubagentTaskId, SubagentToolResult](
      definition(
        "agent_task",
        "Inspect a retained task without restarting it or making a model request.",
        Chunk("task_id"),
        Access.Observation
      ),
      taskIdInput
    )((id, invocation) =>
      val read = inspectTask(registry, id).flatMap(respond("agent_task", _))
      capture match
        case Some(sink) =>
          sink.inspect(id, invocation)(ZIO.succeed(registry))(_ =>
            read.map(result => (result, result))
          )
        case None => read
    )
    val cancel = tool[SubagentTaskId, SubagentToolResult](
      definition(
        "agent_cancel",
        "Cancel the tracked task and wait for its owned cleanup. Completed results remain unchanged.",
        Chunk("task_id"),
        Access.Control
      ),
      taskIdInput
    )((id, _) => cancelTask(registry, id).flatMap(respond("agent_cancel", _)))
    Toolkit
      .single(invoke)
      .updated(inspect)
      .updated(cancel)
      .updated(waitTool(Some(registry), capture, waitSession))

  private def submit(
      registry: SubagentTaskRegistry,
      prompt: String,
      events: Option[ToolEventPort],
      policy: SubagentExecutionPolicy
  ): IO[AgentError, Observation[SubagentToolResult]] =
    ZIO.scoped:
      policy match
        case SubagentExecutionPolicy.PromoteAfter30Seconds =>
          registry
            .submit(Msg.user(prompt), events)
            .flatMap(admission =>
              observeAdmission(
                admission,
                id =>
                  registry
                    .awaitOrPromote(id)
                    .flatMap:
                      case Some(TaskWait.Promoted) =>
                        ZIO.succeed(Observation.Success(task(id, SubagentToolState.Promoted)))
                      case Some(TaskWait.Completed(exit)) =>
                        terminalObservation(registry, id, Some(exit))
                      case None => ZIO.succeed(completed(id, None))
              )
            )
        case SubagentExecutionPolicy.Synchronous(timeout) =>
          submitSynchronous(registry, Msg.user(prompt), events, timeout)

  private def observeAdmission(
      admission: SubagentTaskSubmission,
      await: SubagentTaskId => IO[AgentError, Observation[SubagentToolResult]]
  ): IO[AgentError, Observation[SubagentToolResult]] = admission match
    case SubagentTaskSubmission.Accepted(id) => await(id)
    case SubagentTaskSubmission.Capacity =>
      ZIO.succeed(Observation.Error(SubagentToolResult.Rejected(SubagentToolRejection.Capacity)))
    case SubagentTaskSubmission.Closed =>
      ZIO.succeed(Observation.Error(SubagentToolResult.Rejected(SubagentToolRejection.Closed)))
    case SubagentTaskSubmission.IdentityCollision =>
      ZIO.succeed(
        Observation.Error(SubagentToolResult.Rejected(SubagentToolRejection.IdentityCollision))
      )

  private def completed(
      id: SubagentTaskId,
      exit: Option[Exit[AgentError, AgentResult]]
  ): Observation[SubagentToolResult] =
    exit match
      case Some(value @ Exit.Success(result))
          if result.state == AgentRunState.Completed || result.isSuspended =>
        Observation.Success(project(id, value))
      case Some(value) => Observation.Error(project(id, value))
      case None        => Observation.Error(task(id, SubagentToolState.Missing))

  private def submitSynchronous(
      registry: SubagentTaskRegistry,
      input: Msg,
      events: Option[ToolEventPort],
      timeout: SubagentSyncTimeout
  ): ZIO[Scope, AgentError, Observation[SubagentToolResult]] =
    ZIO.uninterruptibleMask: restore =>
      restore(registry.submit(input, events)).exit.flatMap:
        case Exit.Failure(cause) => ZIO.refailCause(cause)
        case Exit.Success(admission) =>
          observeAdmission(
            admission,
            id =>
              restore(registry.await(id).timeout(timeout.duration)).exit.flatMap:
                case Exit.Success(Some(exit)) =>
                  restore(registry.awaitTerminalPublication(id)).exit.flatMap:
                    case Exit.Success(_)     => ZIO.succeed(completed(id, exit))
                    case Exit.Failure(cause) => ZIO.refailCause(publicationFailure(cause, exit))
                case Exit.Success(None) =>
                  registry
                    .cancel(id)
                    .flatMap: exit =>
                      restore(registry.awaitTerminalPublication(id)).exit.flatMap:
                        case Exit.Success(_)     => ZIO.succeed(timedOut(exit))
                        case Exit.Failure(cause) => ZIO.refailCause(publicationFailure(cause, exit))
                case Exit.Failure(cause) =>
                  registry.cancel(id).flatMap(exit => ZIO.refailCause(cause ++ childFailure(exit)))
          )

  private def childFailure(exit: Option[Exit[AgentError, AgentResult]]): Cause[AgentError] =
    exit.flatMap(_.causeOption).getOrElse(Cause.empty)

  private def publicationFailure(
      cause: Cause[AgentError],
      exit: Option[Exit[AgentError, AgentResult]]
  ): Cause[AgentError] =
    // An interrupted publication wait preserves the joined child's failure in its cause.
    // A previously interrupted child cannot cancel a caller observing a publication failure.
    if cause.isInterrupted then cause ++ childFailure(exit) else cause

  private def terminalObservation(
      registry: SubagentTaskRegistry,
      id: SubagentTaskId,
      exit: Option[Exit[AgentError, AgentResult]]
  ): IO[AgentError, Observation[SubagentToolResult]] =
    ZIO.uninterruptibleMask: restore =>
      restore(registry.awaitTerminalPublication(id)).exit.flatMap:
        case Exit.Success(_)     => ZIO.succeed(completed(id, exit))
        case Exit.Failure(cause) => ZIO.refailCause(publicationFailure(cause, exit))

  private def timedOut(
      exit: Option[Exit[AgentError, AgentResult]]
  ): Observation[SubagentToolResult] =
    val detail = exit match
      case None                  => "The retained task is no longer available."
      case Some(Exit.Success(_)) => "The child completed while timeout cancellation was joining."
      case Some(Exit.Failure(cause)) if cause.isInterruptedOnly =>
        "Child cancellation and owned cleanup completed."
      case Some(Exit.Failure(cause)) =>
        val failure = cause.failureOption.fold("")(error => s" Child failure: ${error.message}")
        val defect =
          if cause.defects.nonEmpty then " Child cleanup or execution reported an internal defect."
          else ""
        "Child cleanup completed with failures." + failure + defect
    Observation.Error(SubagentToolResult.TimedOut(preview("Synchronous wait timed out. " + detail)))

  private def inspectTask(
      registry: SubagentTaskRegistry,
      id: SubagentTaskId
  ): IO[AgentError, Observation[SubagentToolResult]] =
    registry
      .observe(id)
      .map:
        case Some(SubagentTaskObservation(_, SubagentTaskObservationState.Running)) =>
          Observation.Success(task(id, SubagentToolState.Running))
        case Some(SubagentTaskObservation(_, SubagentTaskObservationState.Terminal(outcome))) =>
          Observation.Success(project(id, outcome))
        case None => Observation.Error(task(id, SubagentToolState.Missing))

  private def cancelTask(
      registry: SubagentTaskRegistry,
      id: SubagentTaskId
  ): IO[AgentError, Observation[SubagentToolResult]] =
    registry
      .cancel(id)
      .flatMap:
        case Some(exit) =>
          terminalObservation(registry, id, Some(exit)).as(Observation.Success(project(id, exit)))
        case None => ZIO.succeed(Observation.Error(task(id, SubagentToolState.Missing)))

  private def routedTool[In](
      directory: SubagentSessionDirectory,
      name: String,
      description: String,
      field: String,
      access: Access,
      decodeValue: String => Either[String, In],
      capture: Option[InspectionCapture[In]] = None
  )(
      run: (SubagentTaskRegistry, In, Option[ToolEventPort]) => IO[AgentError, Observation[
        SubagentToolResult
      ]]
  ): Tool[Any, RoutedInput[In], SubagentSessionToolResult] =
    invocationTool[RoutedInput[In], SubagentSessionToolResult](
      definition(name, description, Chunk("session_id", field), access),
      raw =>
        pairedStrings("session_id", field)(raw).flatMap: (session, value) =>
          for
            id    <- uuid("session_id", session).map(SubagentSessionId(_))
            input <- decodeValue(value)
          yield RoutedInput(id, input)
    )((input, invocation) =>
      val missing: SubagentSessionToolResult =
        SubagentSessionToolResult.Missing(input.sessionId.value.toString)
      val resolve = directory
        .lookup(input.sessionId)
        .someOrFail(AgentError.ToolExecutionError(name, missing.toJson))
        .map(_.executions)
      def read(
          target: SubagentTaskRegistry
      ): IO[AgentError, (SubagentToolResult, SubagentSessionToolResult)] =
        run(target, input.value, eventPort(invocation)).flatMap(observation =>
          observedOutput[SubagentSessionToolResult](
            name,
            observation,
            result => SubagentSessionToolResult.Session(input.sessionId.value.toString, result)
          )
        )
      capture match
        case Some(bound) => bound.sink.inspect(bound.taskId(input.value), invocation)(resolve)(read)
        case None        => resolve.flatMap(target => read(target).map(_._2))
    )

  private def catalogTool[In](
      entries: Chunk[SubagentEntry],
      lookup: String => UIO[Option[SubagentTaskRegistry]],
      name: String,
      description: String,
      field: String,
      access: Access,
      decodeValue: String => Either[String, In],
      capture: Option[InspectionCapture[In]] = None
  )(
      run: (SubagentTaskRegistry, In, Option[ToolEventPort]) => IO[AgentError, Observation[
        SubagentToolResult
      ]]
  ): Tool[Any, CatalogInput[In], SubagentCatalogToolResult] =
    val available =
      entries.map(entry => s"${entry.name}: ${entry.description}").mkString("\n")
    invocationTool[CatalogInput[In], SubagentCatalogToolResult](
      definition(
        name,
        description + "\nAvailable agents:\n" + available,
        Chunk("agent_id", field),
        access
      ),
      raw =>
        pairedStrings("agent_id", field)(raw).flatMap: (agentId, value) =>
          decodeValue(value).map(CatalogInput(agentId, _))
    )((input, invocation) =>
      val missing: SubagentCatalogToolResult = SubagentCatalogToolResult.Missing(input.agentId)
      val resolve =
        lookup(input.agentId).someOrFail(AgentError.ToolExecutionError(name, missing.toJson))
      def read(
          target: SubagentTaskRegistry
      ): IO[AgentError, (SubagentToolResult, SubagentCatalogToolResult)] =
        run(target, input.value, eventPort(invocation))
          .mapError: error =>
            val failure: SubagentCatalogToolResult =
              SubagentCatalogToolResult.Failed(input.agentId, preview(error.message))
            AgentError.ToolExecutionError(name, failure.toJson)
          .flatMap(observation =>
            observedOutput[SubagentCatalogToolResult](
              name,
              observation,
              result => SubagentCatalogToolResult.Agent(input.agentId, result)
            )
          )
      capture match
        case Some(bound) => bound.sink.inspect(bound.taskId(input.value), invocation)(resolve)(read)
        case None        => resolve.flatMap(target => read(target).map(_._2))
    )

  final private case class WaitInput(
      selection: SubagentWaitRequest,
      timeout: SubagentTaskWaitTimeout
  )

  private def waitTool(
      registry: Option[SubagentTaskRegistry],
      capture: Option[SubagentTaskConsumptionCapture],
      waitSession: Option[SubagentWaitSession]
  ): Tool[Any, WaitInput, SubagentWaitToolResult] =
    invocationTool[WaitInput, SubagentWaitToolResult](
      ToolDef(
        "wait_async_results",
        "Wait for selected task_ids or the wait_all snapshot, then return bounded terminal previews. " +
          "Default timeout 60 seconds; maximum 120. " +
          (if waitSession.nonEmpty then
             "No selection waits for any host inbox arrival; two empty waits exhaust the session budget. Nonblocking alternatives require host-installed tools."
           else "Legacy inbox-any mode requires an explicit scoped wait session."),
        Json.Obj(
          "type" -> Json.Str("object"),
          "properties" -> Json.Obj(
            "task_ids"        -> Json.Obj("type" -> Json.Str("string")),
            "wait_all"        -> Json.Obj("type" -> Json.Str("boolean")),
            "timeout_seconds" -> Json.Obj("type" -> Json.Str("integer"))
          ),
          "additionalProperties" -> Json.Bool(false)
        ),
        isConcurrencySafe = true,
        isReadOnly = true
      ),
      raw => waitInput(raw, waitSession)
    )((input, invocation) =>
      val waiting = waitSession match
        case Some(session) => session.waitFor(input.selection, input.timeout)
        case None =>
          input.selection match
            case SubagentWaitRequest.Tasks(selection) =>
              ZIO
                .fromOption(registry)
                .orElseFail(AgentError.InvalidConfiguration("Task registry is unavailable"))
                .flatMap(SubagentTaskBarrier.await(_, selection, input.timeout))
                .map(SubagentWaitOutcome.Barrier(_))
            case SubagentWaitRequest.InboxAny =>
              ZIO.fail(
                AgentError.InvalidConfiguration(
                  "Inbox-any waiting requires an explicit scoped wait session"
                )
              )
      ((capture, registry) match
        case (Some(sink), Some(target)) => sink.waitFor(target, invocation)(waiting)
        case (Some(_), None) =>
          ZIO.fail(AgentError.InvalidConfiguration("Task consumption requires a registry"))
        case (None, _) => waiting
      ).map(projectWait)
    )

  private def waitInput(
      raw: Json,
      session: Option[SubagentWaitSession]
  ): Either[String, WaitInput] = raw match
    case Json.Obj(fields)
        if fields.map(_._1).distinct.size == fields.size &&
          fields
            .forall((name, _) => Set("task_ids", "wait_all", "timeout_seconds").contains(name)) =>
      for
        selected <- fields.find(_._1 == "task_ids") match
          case None => Right(None)
          case Some((_, Json.Str(value))) =>
            val values = Chunk.fromIterable(value.split(",").toList.map(_.trim).filter(_.nonEmpty))
            if values.isEmpty then Right(None)
            else
              values
                .foldLeft[Either[String, Chunk[SubagentTaskId]]](Right(Chunk.empty))((acc, value) =>
                  for
                    ids    <- acc
                    parsed <- uuid("task_ids", value)
                  yield ids :+ SubagentTaskId(parsed)
                )
                .flatMap(ids => SubagentTaskSelection.selected(ids).left.map(_.message))
                .map(Some(_))
          case _ => Left("task_ids must be a comma-separated string")
        all <- fields.find(_._1 == "wait_all") match
          case None                        => Right(false)
          case Some((_, Json.Bool(value))) => Right(value)
          case _                           => Left("wait_all must be a boolean")
        seconds <- fields.find(_._1 == "timeout_seconds") match
          case None => Right(None)
          case Some((_, Json.Num(value))) if BigDecimal(value).isValidInt =>
            Right(Some(value.intValue))
          case _ => Left("timeout_seconds must be an integer")
        selection <- selected match
          case Some(value) => Right(SubagentWaitRequest.Tasks(value))
          case None if all => Right(SubagentWaitRequest.Tasks(SubagentTaskSelection.CurrentRunning))
          case None if session.nonEmpty => Right(SubagentWaitRequest.InboxAny)
          case None =>
            Left(
              "Inbox-any waiting requires an explicit scoped wait session; supply task_ids or wait_all=true otherwise"
            )
      yield WaitInput(selection, SubagentTaskWaitTimeout.fromSeconds(seconds))
    case _ => Left("Expected distinct task_ids, wait_all and timeout_seconds fields")

  private[subagent] def projectWait(result: SubagentWaitOutcome): SubagentWaitToolResult =
    result match
      case SubagentWaitOutcome.Barrier(value) => projectBarrier(value)
      case SubagentWaitOutcome.InboxArrived   => SubagentWaitToolResult.InboxArrived
      case SubagentWaitOutcome.NoTasks        => SubagentWaitToolResult.NoTasks
      case SubagentWaitOutcome.AllTerminal    => SubagentWaitToolResult.AllTerminal
      case SubagentWaitOutcome.BudgetExhausted =>
        SubagentWaitToolResult.BudgetExhausted(
          Chunk(
            "Inspect task_list if the host installed it",
            "Inspect agent_task if the host installed it"
          )
        )
      case SubagentWaitOutcome.InboxTimedOut(count) =>
        SubagentWaitToolResult.InboxTimedOut(
          count,
          Chunk(
            "Inspect task_list if the host installed it",
            "Inspect agent_task if the host installed it"
          )
        )

  private[subagent] def projectBarrier(result: SubagentTaskBarrierResult): SubagentWaitToolResult =
    result match
      case SubagentTaskBarrierResult.Completed(tasks) =>
        SubagentWaitToolResult.Completed(tasks.map(task => project(task.id, task.outcome)))
      case SubagentTaskBarrierResult.TimedOut(initial, remaining) =>
        SubagentWaitToolResult.TimedOut(
          initial.map(_.value.toString),
          remaining.map(_.value.toString)
        )
      case SubagentTaskBarrierResult.Empty             => SubagentWaitToolResult.Empty
      case SubagentTaskBarrierResult.AdmissionTimedOut => SubagentWaitToolResult.AdmissionTimedOut

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
      run: (In, Option[ToolEventPort]) => IO[AgentError, Out]
  ): Tool[Any, In, Out] = invocationTool(definitionValue, decode)((input, invocation) =>
    val events = invocation match
      case ToolInvocation.Detached              => None
      case ToolInvocation.Active(_, _, _, port) => Some(port)
    run(input, events)
  )

  private def invocationTool[In, Out: JsonEncoder](
      definitionValue: ToolDef,
      decode: Json => Either[String, In]
  )(
      run: (In, ToolInvocation) => IO[AgentError, Out]
  ): Tool[Any, In, Out] = new Tool[Any, In, Out]:
    def definition: ToolDef = definitionValue
    def execute(input: In): IO[AgentError, Out] =
      ZIO.suspendSucceed(run(input, ToolInvocation.Detached))
    override def executeWithContext(input: In, invocation: ToolInvocation): IO[AgentError, Out] =
      ZIO.suspendSucceed(run(input, invocation))
    def decodeInput(raw: Json): Either[String, In] = decode(raw)
    def encodeOutput(output: Out): String          = output.toJson

  private def eventPort(invocation: ToolInvocation): Option[ToolEventPort] = invocation match
    case ToolInvocation.Detached              => None
    case ToolInvocation.Active(_, _, _, port) => Some(port)

  private def observedOutput[Out: JsonEncoder](
      name: String,
      observation: Observation[SubagentToolResult],
      wrap: SubagentToolResult => Out
  ): IO[AgentError, (SubagentToolResult, Out)] = observation match
    case Observation.Success(result) => ZIO.succeed((result, wrap(result)))
    case Observation.Error(result) =>
      respond(name, Observation.Error(wrap(result))).map(output => (result, output))

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

  private def pairedStrings(identity: String, name: String)(
      input: Json
  ): Either[String, (String, String)] =
    input match
      case Json.Obj(Chunk((key, Json.Str(selected)), (field, Json.Str(value))))
          if key == identity && field == name =>
        Right((selected, value))
      case Json.Obj(Chunk((field, Json.Str(value)), (key, Json.Str(selected))))
          if key == identity && field == name =>
        Right((selected, value))
      case _ => Left(s"Expected exactly two string fields named $identity and $name")

  private def taskIdInput(input: Json): Either[String, SubagentTaskId] =
    stringField("task_id")(input).flatMap(value => uuid("task_id", value).map(SubagentTaskId(_)))

  private def uuid(name: String, value: String): Either[String, UUID] =
    if value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    then Json.Str(value).as[UUID]
    else Left(s"$name must be a canonical UUID string")

  private def task(id: SubagentTaskId, state: SubagentToolState): SubagentToolResult =
    SubagentToolResult.Task(id.value.toString, state)

  private def project(id: SubagentTaskId, exit: Exit[AgentError, AgentResult]): SubagentToolResult =
    project(id, SubagentTerminalOutcome.fromExit(exit))

  private def project(id: SubagentTaskId, outcome: SubagentTerminalOutcome): SubagentToolResult =
    val state = outcome match
      case SubagentTerminalOutcome.Completed(output) => SubagentToolState.Completed(preview(output))
      case SubagentTerminalOutcome.Suspended(output) => SubagentToolState.Suspended(preview(output))
      case SubagentTerminalOutcome.Stopped(reason)   => SubagentToolState.Stopped(preview(reason))
      case SubagentTerminalOutcome.Interrupted(_, _) => SubagentToolState.Interrupted
      case SubagentTerminalOutcome.Failed(failures, defects) =>
        val message = failures.headOption.getOrElse("Task failed with an internal defect")
        val suffix =
          if failures.nonEmpty && defects == SubagentTaskDefects.Present then
            "; task also reported an internal defect"
          else ""
        SubagentToolState.Failed(preview(message + suffix))
    task(id, state)

  private[subagent] def preview(text: String): SubagentToolText =
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
