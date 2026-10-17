/* Copyright 2024-2026 WeisGnosis. Licensed under the Apache License, Version 2.0. */
package com.weisgnosis.agents.harness.procedure

import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.json.JsonBoundary
import java.nio.charset.StandardCharsets
import zio.*
import zio.json.*
import zio.json.ast.Json

enum ProcedureVerdict derives JsonCodec:
  case Accepted(evidence: Json)
  case Rejected(feedback: Json)

/** Verifiers may run again after reopen and must be safe to repeat. Executors never auto-replay. */
final case class ProcedureStage(
    id: String,
    maximumAttempts: Int,
    execute: Json => ZIO[Scope, AgentError, Json],
    verify: (Json, Json) => ZIO[Scope, AgentError, ProcedureVerdict]
)

final case class ProcedureLimits(
    maximumExecutions: Int = 256,
    maximumVerifications: Int = 256,
    maximumPayloadBytes: Int = 65536,
    maximumStateBytes: Int = 1048576,
    timeoutMillis: Long = 300000L
) derives JsonCodec

final case class ProcedureDefinition(
    id: String,
    revision: String,
    stages: Chunk[ProcedureStage],
    limits: ProcedureLimits = ProcedureLimits()
)

final case class ProcedureStageIdentity(id: String, maximumAttempts: Int) derives JsonCodec
final case class ProcedureIdentity(
    id: String,
    revision: String,
    stages: Chunk[ProcedureStageIdentity],
    limits: ProcedureLimits
) derives JsonCodec

final case class ProcedureAcceptance(
    stageId: String,
    attempt: Int,
    submission: Json,
    evidence: Json
) derives JsonCodec

enum ProcedurePhase derives JsonCodec:
  case Ready(attempts: Int)
  case Executing(attempt: Int)
  case Submitted(attempt: Int, submission: Json)
  case Exhausted(reason: String, attempts: Int)

/** One bounded aggregate. The accepted prefix, never a model response, determines the next stage. */
final case class ProcedureSnapshot(
    formatVersion: Int,
    definition: ProcedureIdentity,
    input: Json,
    accepted: Chunk[ProcedureAcceptance],
    phase: ProcedurePhase,
    executions: Int,
    verifications: Int,
    deadlineEpochMillis: Long,
    verdict: Option[ProcedureVerdict] = None
) derives JsonCodec

enum ProcedureOutcome:
  case Accepted(stageId: String)
  case Rejected(stageId: String)
  case Completed(output: Json)
  case Exhausted(reason: String)
  case RecoveryRequired(stageId: String)

private[procedure] object ProcedureValidation:
  def identity(definition: ProcedureDefinition): ProcedureIdentity =
    ProcedureIdentity(
      definition.id,
      definition.revision,
      definition.stages.map(stage => ProcedureStageIdentity(stage.id, stage.maximumAttempts)),
      definition.limits
    )

  private def validId(value: String): Boolean = value.matches("[A-Za-z0-9_-]{1,64}")

  def definition(value: ProcedureDefinition): Either[AgentError, Unit] =
    val limits = value.limits
    val valid = validId(value.id) && validId(value.revision) && value.stages.nonEmpty &&
      value.stages.size <= 64 && value.stages.map(_.id).distinct.size == value.stages.size &&
      value.stages.forall(stage =>
        validId(stage.id) && stage.maximumAttempts > 0 && stage.maximumAttempts <= 64
      ) &&
      limits.maximumExecutions > 0 && limits.maximumExecutions <= 4096 &&
      limits.maximumVerifications > 0 && limits.maximumVerifications <= 4096 &&
      limits.maximumPayloadBytes > 0 && limits.maximumPayloadBytes <= 1048576 &&
      limits.maximumStateBytes > 0 && limits.maximumStateBytes <= 16777216 &&
      limits.timeoutMillis > 0L && limits.timeoutMillis <= 86400000L
    Either.cond(valid, (), AgentError.InvalidConfiguration("Invalid bounded procedure definition"))

  def payload(value: Json, limits: ProcedureLimits): Either[AgentError, Unit] =
    for
      _ <- Either.cond(
        shape(value, 64, 65536) && JsonBoundary.validUnicode(value),
        (),
        AgentError.InvalidMessage(
          "Procedure payload exceeds JSON shape limits or contains malformed Unicode"
        )
      )
      _ <- JsonBoundary.validate(value).left.map(AgentError.InvalidMessage(_))
      _ <- Either.cond(
        bytes(JsonBoundary.render(value)) <= limits.maximumPayloadBytes,
        (),
        AgentError.InvalidMessage("Procedure payload exceeds UTF-8 byte limit")
      )
    yield ()

  def stored(value: Json, limits: ProcedureLimits): Either[AgentError, Unit] =
    for
      _ <- Either.cond(
        shape(value, 80, 4194304) && JsonBoundary.validUnicode(value),
        (),
        AgentError.StorageError(
          "Stored procedure exceeds JSON shape limits or contains malformed Unicode"
        )
      )
      _ <- JsonBoundary.validate(value).left.map(AgentError.StorageError(_))
      _ <- Either.cond(
        bytes(JsonBoundary.render(value)) <= limits.maximumStateBytes,
        (),
        AgentError.StorageError("Stored procedure aggregate exceeds UTF-8 byte limit")
      )
    yield ()

  private def shape(value: Json, maximumDepth: Int, maximumNodes: Int): Boolean =
    @annotation.tailrec
    def inspect(pending: List[(Json, Int)], visited: Int): Boolean = pending match
      case Nil                                                                => true
      case (_, depth) :: _ if depth > maximumDepth || visited >= maximumNodes => false
      case (Json.Obj(fields), depth) :: rest =>
        inspect(fields.map((_, child) => (child, depth + 1)).toList ::: rest, visited + 1)
      case (Json.Arr(values), depth) :: rest =>
        inspect(values.map(child => (child, depth + 1)).toList ::: rest, visited + 1)
      case _ :: rest => inspect(rest, visited + 1)
    inspect(List((value, 0)), 0)

  def snapshot(value: ProcedureSnapshot, expected: ProcedureIdentity): Either[AgentError, Unit] =
    val prefixValid =
      value.accepted.map(_.stageId) == expected.stages.take(value.accepted.size).map(_.id)
    val current = expected.stages.lift(value.accepted.size)
    val phaseValid = value.phase match
      case ProcedurePhase.Ready(attempts) =>
        attempts >= 0 && current.fold(attempts == 0)(attempts < _.maximumAttempts)
      case ProcedurePhase.Executing(attempt) =>
        current.exists(stage => attempt > 0 && attempt <= stage.maximumAttempts)
      case ProcedurePhase.Submitted(attempt, _) =>
        current.exists(stage => attempt > 0 && attempt <= stage.maximumAttempts)
      case ProcedurePhase.Exhausted(reason, attempts) =>
        reason.nonEmpty && reason.length <= 256 &&
        current.exists(stage => attempts >= 0 && attempts <= stage.maximumAttempts)
    val phaseAttempts = value.phase match
      case ProcedurePhase.Ready(attempts)        => attempts
      case ProcedurePhase.Executing(attempt)     => attempt
      case ProcedurePhase.Submitted(attempt, _)  => attempt
      case ProcedurePhase.Exhausted(_, attempts) => attempts
    val acceptedAttemptsValid = value.accepted
      .zip(expected.stages)
      .forall((accepted, stage) =>
        accepted.attempt > 0 && accepted.attempt <= stage.maximumAttempts
      )
    val acceptedExecutions = value.accepted.map(_.attempt).sum
    val minimumVerifications = acceptedExecutions + (value.phase match
      case ProcedurePhase.Ready(attempts)        => attempts
      case ProcedurePhase.Executing(attempt)     => attempt - 1
      case ProcedurePhase.Submitted(attempt, _)  => attempt - 1
      case ProcedurePhase.Exhausted(_, attempts) => (attempts - 1).max(0))
    val valid = value.formatVersion == 1 && value.definition == expected &&
      value.accepted.size <= expected.stages.size && prefixValid && phaseValid && acceptedAttemptsValid &&
      value.executions == acceptedExecutions + phaseAttempts &&
      value.executions <= expected.limits.maximumExecutions && value.verifications >= minimumVerifications &&
      value.verifications <= expected.limits.maximumVerifications && value.deadlineEpochMillis > 0L
    val payloads =
      Chunk(value.input) ++ value.accepted.flatMap(item => Chunk(item.submission, item.evidence)) ++
        (value.phase match
          case ProcedurePhase.Submitted(_, submission) => Chunk(submission)
          case _                                       => Chunk.empty) ++ value.verdict.toList.map:
          case ProcedureVerdict.Accepted(evidence) => evidence
          case ProcedureVerdict.Rejected(feedback) => feedback
    if !valid then
      Left(AgentError.StorageError("Stored procedure state is invalid or definition changed"))
    else
      payloads
        .foldLeft[Either[AgentError, Unit]](Right(()))((checked, json) =>
          checked.flatMap(_ => payload(json, expected.limits))
        )
        .flatMap(_ => value.toJsonAST.left.map(AgentError.StorageError(_)))
        .flatMap(stored(_, expected.limits))

  def bytes(value: String): Int = value.getBytes(StandardCharsets.UTF_8).length
