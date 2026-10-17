/* Copyright 2024-2026 WeisGnosis. Licensed under the Apache License, Version 2.0. */
package com.weisgnosis.agents.harness.procedure

import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.harness.storage.*
import java.util.concurrent.TimeUnit
import zio.*
import zio.json.*
import zio.json.ast.Json

/** Sequential accepted-prefix progress under an explicit, continuously held host execution claim. */
final class ProcedureSession private (
    definition: ProcedureDefinition,
    address: StorageAddress,
    store: VersionedJsonStore,
    owner: Scope.Closeable,
    gate: Semaphore,
    state: Ref[ProcedureSession.State]
):
  import ProcedureSession.*

  def snapshot: UIO[ProcedureSnapshot] = state.get.map(_.snapshot)

  /** One attempt or saved-submission verification; all child resources close before publication. */
  def advance: IO[AgentError, ProcedureOutcome] = owned(step)

  /** One persisted deadline covers every stage, retry, verifier, and time while the host is offline. */
  def run: IO[AgentError, ProcedureOutcome] = owned(loop)

  private def owned(work: IO[AgentError, ProcedureOutcome]): IO[AgentError, ProcedureOutcome] =
    gate.withPermit:
      ZIO.uninterruptibleMask: restore =>
        for
          _      <- live
          worker <- restore(work).forkIn(owner)
          waited <- restore(worker.await).exit
          outcome <- waited match
            case Exit.Success(result) => worker.inheritAll *> result
            case Exit.Failure(cause) =>
              worker.interrupt.flatMap(result =>
                ZIO.refailCause(cause ++ result.causeOption.getOrElse(Cause.empty))
              )
        yield outcome

  private def live: IO[AgentError, (ProcedureSnapshot, ExpectedRevision)] =
    state.get.flatMap:
      case State.Live(snapshot, revision) => ZIO.succeed((snapshot, revision))
      case _ => ZIO.fail(AgentError.StorageError("Procedure session is closed or requires reopen"))

  private def persist(next: ProcedureSnapshot): IO[AgentError, Unit] =
    ZIO.fromEither(ProcedureValidation.snapshot(next, ProcedureValidation.identity(definition))) *>
      ZIO.uninterruptibleMask: restore =>
        live.flatMap: (_, expected) =>
          ZIO
            .fromEither(next.toJsonAST.left.map(AgentError.StorageError(_)))
            .flatMap: encoded =>
              restore(store.compareAndSet(address, expected, encoded)).exit.flatMap:
                case Exit.Success(ConditionalWrite.Applied(revision)) =>
                  state.set(State.Live(next, ExpectedRevision.AtRevision(revision)))
                case Exit.Success(ConditionalWrite.Conflict) =>
                  state.update(current => State.Blocked(current.snapshot)) *>
                    ZIO.fail(
                      AgentError.StorageError("Procedure persistence conflict; reopen required")
                    )
                case Exit.Failure(cause) =>
                  state.update(current => State.Blocked(current.snapshot)) *> ZIO.refailCause(cause)

  private def loop: IO[AgentError, ProcedureOutcome] =
    step.flatMap:
      case ProcedureOutcome.Accepted(_) | ProcedureOutcome.Rejected(_) => loop
      case terminal                                                    => ZIO.succeed(terminal)

  private def step: IO[AgentError, ProcedureOutcome] =
    live.flatMap: (saved, _) =>
      definition.stages.lift(saved.accepted.size) match
        case None =>
          ZIO.succeed(
            ProcedureOutcome.Completed(saved.accepted.lastOption.fold(saved.input)(_.submission))
          )
        case Some(stage) =>
          saved.phase match
            case ProcedurePhase.Executing(_) =>
              ZIO.succeed(ProcedureOutcome.RecoveryRequired(stage.id))
            case ProcedurePhase.Exhausted(reason, _) =>
              ZIO.succeed(ProcedureOutcome.Exhausted(reason))
            case _ =>
              Clock
                .currentTime(TimeUnit.MILLISECONDS)
                .flatMap: now =>
                  val remaining = saved.deadlineEpochMillis - now
                  if remaining <= 0L then exhaust("Procedure deadline exhausted")
                  else beforeDeadline(perform(saved, stage), Duration.fromMillis(remaining))

  /** Race only the observer; retain and join the actual worker's complete cleanup cause. */
  private def beforeDeadline(
      work: IO[AgentError, ProcedureOutcome],
      remaining: Duration
  ): IO[AgentError, ProcedureOutcome] =
    val timeout = AgentError.TimeoutError(0L, "Procedure deadline exhausted")
    ZIO.scoped(ZIO.uninterruptibleMask: restore =>
      for
        worker <- restore(work).forkScoped
        waited <- restore(worker.await.raceEither(ZIO.sleep(remaining))).exit
        outcome <- waited match
          case Exit.Success(Left(result)) => worker.inheritAll *> result
          case Exit.Success(Right(_)) =>
            worker.interrupt.flatMap:
              case Exit.Failure(cause) if !cause.isInterruptedOnly =>
                ZIO.refailCause(cause ++ Cause.fail(timeout))
              case _ => ZIO.fail(timeout)
          case Exit.Failure(waitCause) =>
            worker.interrupt.flatMap:
              case Exit.Failure(cause) if !cause.isInterruptedOnly =>
                ZIO.refailCause(waitCause ++ cause)
              case _ => ZIO.refailCause(waitCause)
      yield outcome)

  private def exhaust(reason: String): IO[AgentError, ProcedureOutcome] =
    live
      .flatMap((saved, _) =>
        val attempts = saved.phase match
          case ProcedurePhase.Ready(count)        => count
          case ProcedurePhase.Executing(count)    => count
          case ProcedurePhase.Submitted(count, _) => count
          case ProcedurePhase.Exhausted(_, count) => count
        persist(saved.copy(phase = ProcedurePhase.Exhausted(reason, attempts)))
      )
      .as(ProcedureOutcome.Exhausted(reason))

  private def perform(
      saved: ProcedureSnapshot,
      stage: ProcedureStage
  ): IO[AgentError, ProcedureOutcome] =
    val input = saved.accepted.lastOption.fold(saved.input)(_.submission)
    saved.phase match
      case ProcedurePhase.Ready(attempts) =>
        if saved.executions >= definition.limits.maximumExecutions then
          exhaust("Procedure execution budget exhausted")
        else
          val admitted = saved.copy(
            phase = ProcedurePhase.Executing(attempts + 1),
            executions = saved.executions + 1,
            verdict = None
          )
          for
            _          <- persist(admitted)
            submission <- ZIO.scoped(ZIO.suspendSucceed(stage.execute(input)))
            _          <- ZIO.fromEither(ProcedureValidation.payload(submission, definition.limits))
            submitted = admitted.copy(phase = ProcedurePhase.Submitted(attempts + 1, submission))
            _      <- persist(submitted)
            result <- verify(submitted, stage, input, attempts + 1, submission)
          yield result
      case ProcedurePhase.Submitted(attempt, submission) =>
        verify(saved, stage, input, attempt, submission)
      case _ => ZIO.fail(AgentError.StorageError("Unexpected procedure transition"))

  private def verify(
      saved: ProcedureSnapshot,
      stage: ProcedureStage,
      input: Json,
      attempt: Int,
      submission: Json
  ): IO[AgentError, ProcedureOutcome] =
    if saved.verifications >= definition.limits.maximumVerifications then
      exhaust("Procedure verification budget exhausted")
    else
      val admitted = saved.copy(verifications = saved.verifications + 1)
      for
        _       <- persist(admitted)
        verdict <- ZIO.scoped(ZIO.suspendSucceed(stage.verify(input, submission)))
        result <- verdict match
          case ProcedureVerdict.Accepted(evidence) =>
            ZIO.fromEither(ProcedureValidation.payload(evidence, definition.limits)) *>
              persist(
                admitted.copy(
                  accepted = admitted.accepted :+ ProcedureAcceptance(
                    stage.id,
                    attempt,
                    submission,
                    evidence
                  ),
                  phase = ProcedurePhase.Ready(0),
                  verdict = Some(verdict)
                )
              ).as(ProcedureOutcome.Accepted(stage.id))
          case ProcedureVerdict.Rejected(feedback) =>
            ZIO.fromEither(ProcedureValidation.payload(feedback, definition.limits)) *>
              (if attempt >= stage.maximumAttempts then
                 persist(
                   admitted.copy(
                     phase = ProcedurePhase
                       .Exhausted("Procedure stage attempt budget exhausted", attempt),
                     verdict = Some(verdict)
                   )
                 )
                   .as(ProcedureOutcome.Exhausted("Procedure stage attempt budget exhausted"))
               else
                 persist(
                   admitted.copy(phase = ProcedurePhase.Ready(attempt), verdict = Some(verdict))
                 ).as(ProcedureOutcome.Rejected(stage.id)))
      yield result

object ProcedureSession:
  /** Acquire a real host-exclusive claim for this exact address until the supplied scope closes. */
  trait Authority:
    def acquire(address: StorageAddress): ZIO[Scope, AgentError, Unit]

  private enum State:
    case Live(data: ProcedureSnapshot, revision: ExpectedRevision)
    case Blocked(data: ProcedureSnapshot)
    case Closed(data: ProcedureSnapshot)
    def snapshot: ProcedureSnapshot = this match
      case Live(value, _) => value
      case Blocked(value) => value
      case Closed(value)  => value

  def open(
      definition: ProcedureDefinition,
      input: Json,
      address: StorageAddress,
      store: VersionedJsonStore,
      authority: Authority
  ): ZIO[Scope, AgentError, ProcedureSession] =
    WorkspaceLease.initialize:
      for
        _     <- ZIO.fromEither(ProcedureValidation.definition(definition))
        _     <- ZIO.fromEither(ProcedureValidation.payload(input, definition.limits))
        owner <- ZIO.acquireRelease(Scope.make)(_.close(Exit.unit))
        _ <- ZIO.suspendSucceed(authority.acquire(address)).provideEnvironment(ZEnvironment(owner))
        record <- store.read(address)
        now    <- Clock.currentTime(TimeUnit.MILLISECONDS)
        identity = ProcedureValidation.identity(definition)
        saved <- record match
          case Some(value) =>
            ZIO.fromEither(ProcedureValidation.stored(value.value, definition.limits)) *> ZIO
              .fromEither(value.value.as[ProcedureSnapshot].left.map(AgentError.StorageError(_)))
              .map(snapshot => (snapshot, ExpectedRevision.AtRevision(value.revision)))
          case None =>
            ZIO.succeed(
              (
                ProcedureSnapshot(
                  1,
                  identity,
                  input,
                  Chunk.empty,
                  ProcedurePhase.Ready(0),
                  0,
                  0,
                  now + definition.limits.timeoutMillis
                ),
                ExpectedRevision.Absent
              )
            )
        _ <- ZIO.fromEither(ProcedureValidation.snapshot(saved._1, identity))
        _ <- ZIO
          .fail(AgentError.StorageError("Procedure input changed on reopen"))
          .unless(saved._1.input == input)
        state <- Ref.make[State](State.Live(saved._1, saved._2))
        gate  <- Semaphore.make(1)
        session = new ProcedureSession(definition, address, store, owner, gate, state)
        _ <- owner.addFinalizer(state.update(current => State.Closed(current.snapshot)))
        _ <- if record.isEmpty then session.persist(saved._1) else ZIO.unit
      yield session
