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
import java.nio.charset.StandardCharsets
import zio.*
import zio.json.*
import zio.json.ast.Json

/** Untrusted remote observations; these do not authorize confirmation or local execution. */
final case class SubagentRemotePendingApproval(callId: String, toolName: String, input: Json)
    derives JsonCodec

/** Task-local monotonic remote generation supplied by the trusted transport, not inferred from IDs. */
opaque type SubagentRemoteApprovalGeneration = Long

object SubagentRemoteApprovalGeneration:
  def fromLong(
      value: Long
  ): Either[AgentError.InvalidConfiguration, SubagentRemoteApprovalGeneration] =
    Either.cond(
      value > 0L,
      value,
      AgentError.InvalidConfiguration("Remote approval generation must be positive")
    )
  extension (value: SubagentRemoteApprovalGeneration) def value: Long = value
  given JsonCodec[SubagentRemoteApprovalGeneration] = JsonCodec.long.transformOrFail(
    value => fromLong(value).left.map(_.message),
    _.value
  )

enum SubagentRemoteApproval derives JsonCodec:
  case NotAwaiting
  case Awaiting(
      generation: SubagentRemoteApprovalGeneration,
      calls: Chunk[SubagentRemotePendingApproval]
  )
  case ConfirmationClaimed(
      generation: SubagentRemoteApprovalGeneration,
      calls: Chunk[SubagentRemotePendingApproval],
      decisions: Chunk[SubagentRemoteConfirmation]
  )
  case Resolved(generation: SubagentRemoteApprovalGeneration)

object SubagentRemoteApproval:
  private[subagent] def resolved(value: SubagentRemoteApproval): SubagentRemoteApproval =
    value match
      case NotAwaiting                           => NotAwaiting
      case Awaiting(generation, _)               => Resolved(generation)
      case ConfirmationClaimed(generation, _, _) => Resolved(generation)
      case Resolved(generation)                  => Resolved(generation)

/** The terminal payload is validated before any durable transition. */
enum SubagentRemoteObservation derives JsonCodec:
  case Pending, Running
  case ApprovalResolved(generation: SubagentRemoteApprovalGeneration)
  case Awaiting(
      generation: SubagentRemoteApprovalGeneration,
      calls: Chunk[SubagentRemotePendingApproval]
  )
  case Terminal(outcome: SubagentTerminalOutcome)

object SubagentRemoteObservation:
  private[subagent] def validate(value: SubagentRemoteObservation): Either[AgentError, Unit] =
    val shape = value match
      case Awaiting(_, calls) =>
        calls.nonEmpty && calls.size <= 128 && calls.map(_.callId).distinct.size == calls.size &&
        calls.forall(call =>
          call.callId.trim.nonEmpty && call.toolName.trim.nonEmpty &&
            call.callId.getBytes(StandardCharsets.UTF_8).length <= 1024 &&
            call.toolName.getBytes(StandardCharsets.UTF_8).length <= 1024
        )
      case Terminal(SubagentTerminalOutcome.Suspended(_)) => false
      case _                                              => true
    for
      ast <- value.toJsonAST.left.map(AgentError.StorageError.apply)
      _   <- JsonBoundary.validate(ast).left.map(AgentError.StorageError.apply)
      _ <- Either.cond(
        shape && JsonBoundary.validUnicode(ast) &&
          JsonBoundary.render(ast).getBytes(StandardCharsets.UTF_8).length <= 1048576,
        (),
        AgentError.StorageError("Invalid or oversized remote task observation")
      )
    yield ()

/** Explicit trusted host decisions, never inferred from a remote approval payload. */
enum SubagentRemoteDecision derives JsonCodec:
  case Allow(input: Json)
  case Deny(reason: String)

final case class SubagentRemoteConfirmation(callId: String, decision: SubagentRemoteDecision)
    derives JsonCodec

/**
 * Borrowed host capability bound to one endpoint. No submit operation exists. Implementations own
 * bounded request resources in the supplied Scope; URI observation data does not create transport.
 * Approval generations must increase per task and never be reused. The pinned HTTP protocol does
 * not itself provide this guarantee; bindings without it cannot implement confirmation safely.
 */
trait SubagentRemoteTransport:
  def endpoint: SubagentProtocolBaseUri
  def observe(id: SubagentTaskId): ZIO[Scope, AgentError, SubagentRemoteObservation]
  def cancel(id: SubagentTaskId): ZIO[Scope, AgentError, Unit]
  def confirm(
      id: SubagentTaskId,
      generation: SubagentRemoteApprovalGeneration,
      decisions: Chunk[SubagentRemoteConfirmation]
  ): ZIO[Scope, AgentError, Unit]

enum SubagentRemoteAwaitResult:
  case Observed(record: SubagentTaskRecord)
  case TimedOut

/**
 * Explicit observation of an already admitted remote task. The host owns peer authentication and
 * execution exclusivity; identity matching is not a distributed fence. This handle owns no daemon.
 * Calls are serialized per handle only. Transport or uncertain storage errors are never retried.
 */
final class SubagentRemoteTask private (
    store: SubagentTerminalStore,
    identity: SubagentTaskAdmissionIdentity,
    transport: SubagentRemoteTransport,
    gate: Semaphore
):
  private def current: IO[AgentError, SubagentTaskRecord] =
    store
      .taskRecord(identity.id)
      .flatMap:
        case Some(record)
            if SubagentRemoteTask.matches(record, identity) &&
              identity.origin == SubagentExecutionOrigin.AgentProtocol(transport.endpoint) =>
          ZIO.succeed(record)
        case _ =>
          ZIO.fail(AgentError.StorageError("Remote task admission identity is missing or changed"))

  private def observeCurrent: IO[AgentError, SubagentTaskRecord] =
    current.flatMap: record =>
      if record.terminal.nonEmpty then ZIO.succeed(record)
      else
        ZIO
          .scoped(ZIO.suspendSucceed(transport.observe(identity.id)))
          .flatMap(value => ZIO.fromEither(SubagentRemoteObservation.validate(value)).as(value))
          .flatMap(store.applyRemoteObservation(record, _))

  def observe: IO[AgentError, SubagentTaskRecord] = gate.withPermit(observeCurrent)

  /** One budget includes admission, transport, storage and explicit polling; zero still polls once. */
  def await(timeout: SubagentRemoteTask.WaitTimeout): IO[AgentError, SubagentRemoteAwaitResult] =
    def waiting(attempt: Int): IO[AgentError, SubagentTaskRecord] =
      observeCurrent.flatMap: record =>
        record.remoteApproval match
          case SubagentRemoteApproval.Awaiting(_, _) |
              SubagentRemoteApproval.ConfirmationClaimed(_, _, _) =>
            ZIO.succeed(record)
          case _ if record.terminal.nonEmpty => ZIO.succeed(record)
          case _ =>
            ZIO.sleep(Duration.fromMillis(200L * (1L << attempt.min(4)))) *> waiting(
              (attempt + 1).min(4)
            )
    if timeout.milliseconds == 0L then observe.map(SubagentRemoteAwaitResult.Observed(_))
    else
      SubagentTaskBarrier
        .raceWithSignal(
          gate.withPermit(waiting(0)),
          ZIO.sleep(timeout.duration).as(Exit.unit)
        )
        .map:
          case Some(record) => SubagentRemoteAwaitResult.Observed(record)
          case None         => SubagentRemoteAwaitResult.TimedOut

  /** Intent precedes the remote request; only a subsequent terminal observation confirms stopping. */
  def requestCancellation: IO[AgentError, SubagentTaskRecord] = gate.withPermit:
    current.flatMap: record =>
      if record.terminal.nonEmpty then ZIO.succeed(record)
      else
        store
          .requestCancellation(identity.id, Some(identity))
          .flatMap:
            case SubagentCancellationRequestResult.AlreadyTerminal(terminal)
                if SubagentRemoteTask.matches(terminal, identity) && terminal.terminal.nonEmpty =>
              ZIO.succeed(terminal)
            case SubagentCancellationRequestResult.Requested(requested)
                if SubagentRemoteTask.requested(requested, identity) =>
              sendCancellation
            case SubagentCancellationRequestResult.AlreadyRequested(requested)
                if SubagentRemoteTask.requested(requested, identity) =>
              sendCancellation
            case _ => ZIO.fail(AgentError.StorageError("Invalid remote cancellation receipt"))

  private def sendCancellation: IO[AgentError, SubagentTaskRecord] =
    current.flatMap: record =>
      if record.terminal.nonEmpty then ZIO.succeed(record)
      else if SubagentRemoteTask.requested(record, identity) then
        ZIO.scoped(ZIO.suspendSucceed(transport.cancel(identity.id))) *> observeCurrent
      else ZIO.fail(AgentError.StorageError("Remote cancellation intent changed before transport"))

  /** Bind explicit host consent to its observed generation and complete ordered approval batch. */
  def confirm(
      expectedGeneration: SubagentRemoteApprovalGeneration,
      decisions: Chunk[SubagentRemoteConfirmation]
  ): IO[AgentError, SubagentTaskRecord] =
    gate.withPermit:
      current.flatMap: record =>
        record.remoteApproval match
          case SubagentRemoteApproval.Awaiting(generation, calls)
              if generation == expectedGeneration && record.terminal.isEmpty &&
                decisions.map(_.callId) == calls.map(_.callId) =>
            for
              ast <- ZIO.fromEither(decisions.toJsonAST.left.map(AgentError.StorageError.apply))
              _ <- ZIO.fromEither(
                JsonBoundary.validate(ast).left.map(AgentError.StorageError.apply)
              )
              _ <- ZIO
                .fail(AgentError.StorageError("Invalid remote confirmation payload"))
                .unless(
                  JsonBoundary.validUnicode(ast) &&
                    JsonBoundary.render(ast).getBytes(StandardCharsets.UTF_8).length <= 1048576
                )
              claimed <- store.claimRemoteConfirmation(record, decisions)
              _ <- ZIO
                .fail(AgentError.StorageError("Invalid remote confirmation claim receipt"))
                .unless(
                  SubagentRemoteTask.matches(claimed, identity) && claimed.terminal.isEmpty &&
                    (claimed.remoteApproval match
                      case SubagentRemoteApproval
                            .ConfirmationClaimed(claimedGeneration, batch, saved) =>
                        claimedGeneration == generation && batch == calls && saved == decisions
                      case _ => false)
                )
              latest <- current
              _ <- ZIO
                .fail(AgentError.StorageError("Remote confirmation claim changed before transport"))
                .unless(latest == claimed)
              _ <- ZIO.scoped(
                ZIO.suspendSucceed(transport.confirm(identity.id, generation, decisions))
              )
              observed <- observeCurrent
            yield observed
          case _ =>
            ZIO.fail(AgentError.InvalidConfiguration("No matching pending remote approval batch"))

object SubagentRemoteTask:
  final class WaitTimeout private (val milliseconds: Long):
    def duration: Duration = Duration.fromMillis(milliseconds)

  object WaitTimeout:
    def fromMilliseconds(
        value: Long = 60000L
    ): Either[AgentError.InvalidConfiguration, WaitTimeout] =
      Either.cond(
        value >= 0L && value <= 120000L,
        new WaitTimeout(value),
        AgentError.InvalidConfiguration(
          "Remote observation timeout must be between 0 and 120000 milliseconds"
        )
      )

  private[subagent] def matches(
      record: SubagentTaskRecord,
      identity: SubagentTaskAdmissionIdentity
  ): Boolean =
    record.id == identity.id && record.workerId == identity.workerId && record.origin == identity.origin &&
      record.metadata.exists(_.createdAt == identity.createdAt)

  private def requested(
      record: SubagentTaskRecord,
      identity: SubagentTaskAdmissionIdentity
  ): Boolean =
    matches(record, identity) && record.terminal.isEmpty && (record.cancellationIntent match
      case SubagentCancellationIntent.Requested(_) => true
      case _                                       => false)

  def attach(
      store: SubagentTerminalStore,
      identity: SubagentTaskAdmissionIdentity,
      transport: SubagentRemoteTransport
  ): IO[AgentError, SubagentRemoteTask] =
    ZIO.suspendSucceed:
      identity.origin match
        case SubagentExecutionOrigin.AgentProtocol(endpoint) if endpoint == transport.endpoint =>
          for
            gate <- Semaphore.make(1)
            task = new SubagentRemoteTask(store, identity, transport, gate)
            _ <- task.current
          yield task
        case _ =>
          ZIO.fail(
            AgentError.InvalidConfiguration(
              "Remote transport endpoint does not match AgentProtocol admission"
            )
          )
