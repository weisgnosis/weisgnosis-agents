/* Copyright 2024-2026 WeisGnosis. Licensed under the Apache License, Version 2.0. */
package com.weisgnosis.agents.harness.procedure

import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.harness.storage.*
import zio.*
import zio.json.ast.Json
import zio.test.*

object ProcedureSessionSpec extends ZIOSpecDefault:
  private enum Fault:
    case None, Conflict, LostAcknowledgement

  final private class MemoryStore(
      value: Ref[Option[VersionedJson]],
      writes: Ref[Int],
      faultAt: Int,
      fault: Fault
  ) extends VersionedJsonStore:
    def read(address: StorageAddress): IO[AgentError, Option[VersionedJson]] =
      value.get.filterOrFail(_ => address.key == "run")(AgentError.StorageError("Wrong address"))

    def compareAndSet(
        address: StorageAddress,
        expected: ExpectedRevision,
        json: Json
    ): IO[AgentError, ConditionalWrite] =
      writes
        .updateAndGet(_ + 1)
        .flatMap: count =>
          if address.key != "run" then ZIO.fail(AgentError.StorageError("Wrong address"))
          else if count == faultAt && fault == Fault.Conflict then
            ZIO.succeed(ConditionalWrite.Conflict)
          else
            val commit = value.modify: current =>
              val matches = (expected, current) match
                case (ExpectedRevision.Absent, None) => true
                case (ExpectedRevision.AtRevision(revision), Some(saved)) =>
                  revision == saved.revision
                case _ => false
              if !matches then (Right(ConditionalWrite.Conflict), current)
              else
                StorageRevision.fromLong(current.fold(1L)(_.revision.value + 1L)) match
                  case Left(error) => (Left(error), current)
                  case Right(revision) =>
                    (Right(ConditionalWrite.Applied(revision)), Some(VersionedJson(json, revision)))
            commit
              .flatMap(result => ZIO.fromEither(result))
              .flatMap(result =>
                if count == faultAt && fault == Fault.LostAcknowledgement then
                  ZIO.fail(AgentError.StorageError("Committed but acknowledgement lost"))
                else ZIO.succeed(result)
              )

  private def store(faultAt: Int = 0, fault: Fault = Fault.None): UIO[MemoryStore] =
    for
      value  <- Ref.make(Option.empty[VersionedJson])
      writes <- Ref.make(0)
    yield new MemoryStore(value, writes, faultAt, fault)

  private val authority: ProcedureSession.Authority = new ProcedureSession.Authority:
    def acquire(address: StorageAddress): ZIO[Scope, AgentError, Unit] =
      ZIO
        .fail(AgentError.StorageError("Wrong ownership partition"))
        .unless(address.key == "run")
        .unit

  private def address: IO[AgentError, StorageAddress] =
    ZIO.fromEither(StorageAddress.from(Chunk("procedure-tests"), "run"))

  private def definition(calls: Ref[Chunk[String]]): ProcedureDefinition =
    ProcedureDefinition(
      "release",
      "v1",
      Chunk("draft", "package").map(id =>
        ProcedureStage(
          id,
          2,
          input => calls.update(_ :+ id).as(Json.Str(s"$id:$input")),
          (_, _) => ZIO.succeed(ProcedureVerdict.Accepted(Json.Str("host-check-v1")))
        )
      )
    )

  def spec: Spec[TestEnvironment & Scope, Any] = suite("ProcedureSessionSpec")(
    test("failed initialization releases exclusive authority before same-scope reopen")(
      ZIO.scoped:
        for
          backend  <- store()
          reads    <- Ref.make(0)
          released <- Ref.make(false)
          claims   <- Semaphore.make(1)
          location <- address
          calls    <- Ref.make(Chunk.empty[String])
          exclusive = new ProcedureSession.Authority:
            def acquire(candidate: StorageAddress): ZIO[Scope, AgentError, Unit] =
              ZIO.fail(AgentError.StorageError("Wrong address")).unless(candidate == location) *>
                claims.withPermitScoped *> ZIO.addFinalizer(released.set(true)).unit
          failing = new VersionedJsonStore:
            def read(candidate: StorageAddress): IO[AgentError, Option[VersionedJson]] =
              reads
                .updateAndGet(_ + 1)
                .flatMap(count =>
                  if count == 1 then ZIO.fail(AgentError.StorageError("Initialization read failed"))
                  else backend.read(candidate)
                )
            def compareAndSet(
                candidate: StorageAddress,
                expected: ExpectedRevision,
                value: Json
            ): IO[AgentError, ConditionalWrite] =
              backend.compareAndSet(candidate, expected, value)
          first <- ProcedureSession
            .open(definition(calls), Json.Str("input"), location, failing, exclusive)
            .either
          releasedImmediately <- released.get
          reopened <- ProcedureSession
            .open(definition(calls), Json.Str("input"), location, failing, exclusive)
            .timeout(1.second)
        yield assertTrue(first.isLeft, releasedImmediately, reopened.nonEmpty)
    ) @@ TestAspect.withLiveClock,
    test("reopen skips accepted prefix and passes its saved submission to the successor"):
      for
        backend  <- store()
        location <- address
        calls    <- Ref.make(Chunk.empty[String])
        plan = definition(calls)
        first <- ZIO.scoped(
          ProcedureSession
            .open(plan, Json.Str("input"), location, backend, authority)
            .flatMap(_.advance)
        )
        result <- ZIO.scoped(
          ProcedureSession
            .open(plan, Json.Str("input"), location, backend, authority)
            .flatMap(_.run)
        )
        observed <- calls.get
      yield assertTrue(
        first == ProcedureOutcome.Accepted("draft"),
        result.isInstanceOf[ProcedureOutcome.Completed],
        observed == Chunk("draft", "package")
      )
    ,
    test(
      "committed submission with lost acknowledgement reopens into verifier without executor replay"
    ):
      for
        backend       <- store(3, Fault.LostAcknowledgement)
        location      <- address
        calls         <- Ref.make(Chunk.empty[String])
        verifications <- Ref.make(0)
        base = definition(calls)
        plan = base.copy(stages =
          base.stages
            .take(1)
            .map(
              _.copy(verify =
                (_, _) =>
                  verifications.update(_ + 1).as(ProcedureVerdict.Accepted(Json.Str("checked")))
              )
            )
        )
        failed <- ZIO.scoped:
          for
            session <- ProcedureSession.open(plan, Json.Str("input"), location, backend, authority)
            first   <- session.advance.either
            blocked <- session.advance.either
          yield first.isLeft && blocked.isLeft
        before <- verifications.get
        reopened <- ZIO.scoped(
          ProcedureSession
            .open(plan, Json.Str("input"), location, backend, authority)
            .flatMap(_.run)
        )
        executed <- calls.get
        verified <- verifications.get
      yield assertTrue(
        failed,
        before == 0,
        reopened.isInstanceOf[ProcedureOutcome.Completed],
        executed == Chunk("draft"),
        verified == 1
      )
    ,
    test(
      "committed execution admission with lost acknowledgement never starts or repeats the executor"
    ):
      for
        backend  <- store(2, Fault.LostAcknowledgement)
        location <- address
        calls    <- Ref.make(Chunk.empty[String])
        plan = definition(calls)
        failed <- ZIO.scoped(
          ProcedureSession
            .open(plan, Json.Str("input"), location, backend, authority)
            .flatMap(_.advance.either)
        )
        reopened <- ZIO.scoped(
          ProcedureSession
            .open(plan, Json.Str("input"), location, backend, authority)
            .flatMap(_.run)
        )
        observed <- calls.get
      yield assertTrue(
        failed.isLeft,
        reopened == ProcedureOutcome.RecoveryRequired("draft"),
        observed.isEmpty
      )
    ,
    test("acceptance lost acknowledgement blocks successor until explicit reopen"):
      for
        backend  <- store(5, Fault.LostAcknowledgement)
        location <- address
        calls    <- Ref.make(Chunk.empty[String])
        plan = definition(calls)
        failed <- ZIO.scoped(
          ProcedureSession
            .open(plan, Json.Str("input"), location, backend, authority)
            .flatMap(_.run.either)
        )
        before <- calls.get
        reopened <- ZIO.scoped(
          ProcedureSession
            .open(plan, Json.Str("input"), location, backend, authority)
            .flatMap(_.run)
        )
        after <- calls.get
      yield assertTrue(
        failed.isLeft,
        before == Chunk("draft"),
        after == Chunk("draft", "package"),
        reopened.isInstanceOf[ProcedureOutcome.Completed]
      )
    ,
    test(
      "confirmed conflict stops and poisons the instance without executing the unadmitted stage"
    ):
      for
        backend  <- store(2, Fault.Conflict)
        location <- address
        calls    <- Ref.make(Chunk.empty[String])
        plan = definition(calls)
        stopped <- ZIO.scoped:
          for
            session  <- ProcedureSession.open(plan, Json.Str("input"), location, backend, authority)
            first    <- session.advance.either
            again    <- session.advance.either
            observed <- calls.get
          yield first.isLeft && again.isLeft && observed.isEmpty
        result <- ZIO.scoped(
          ProcedureSession
            .open(plan, Json.Str("input"), location, backend, authority)
            .flatMap(_.run)
        )
      yield assertTrue(stopped, result.isInstanceOf[ProcedureOutcome.Completed])
    ,
    test("attempt and aggregate verifier budgets survive reopen"):
      for
        backend  <- store()
        location <- address
        calls    <- Ref.make(Chunk.empty[String])
        base = definition(calls)
        plan = base.copy(stages =
          base.stages
            .take(1)
            .map(
              _.copy(verify = (_, _) => ZIO.succeed(ProcedureVerdict.Rejected(Json.Str("not yet"))))
            )
        )
        first <- ZIO.scoped(
          ProcedureSession
            .open(plan, Json.Str("input"), location, backend, authority)
            .flatMap(_.advance)
        )
        last <- ZIO.scoped(
          ProcedureSession
            .open(plan, Json.Str("input"), location, backend, authority)
            .flatMap(_.run)
        )
        repeated <- ZIO.scoped(
          ProcedureSession
            .open(plan, Json.Str("input"), location, backend, authority)
            .flatMap(_.run)
        )
        observed <- calls.get
      yield assertTrue(
        first == ProcedureOutcome.Rejected("draft"),
        last == repeated,
        last.isInstanceOf[ProcedureOutcome.Exhausted],
        observed == Chunk("draft", "draft")
      )
    ,
    test("changed order, revision, input and malformed stored counters fail on open"):
      for
        backend  <- store()
        location <- address
        calls    <- Ref.make(Chunk.empty[String])
        plan = definition(calls)
        saved <- ZIO.scoped(
          ProcedureSession
            .open(plan, Json.Str("input"), location, backend, authority)
            .flatMap(_.snapshot)
        )
        reordered <- ZIO.scoped(
          ProcedureSession
            .open(
              plan.copy(stages = plan.stages.reverse),
              Json.Str("input"),
              location,
              backend,
              authority
            )
            .either
        )
        revision <- ZIO.scoped(
          ProcedureSession
            .open(plan.copy(revision = "v2"), Json.Str("input"), location, backend, authority)
            .either
        )
        changedInput <- ZIO.scoped(
          ProcedureSession.open(plan, Json.Str("changed"), location, backend, authority).either
        )
        invalid = saved.copy(executions = 1)
      yield assertTrue(
        reordered.isLeft,
        revision.isLeft,
        changedInput.isLeft,
        ProcedureValidation.snapshot(invalid, saved.definition).isLeft
      )
    ,
    test("interruption joins executor cleanup and leaves a recovery-required admission"):
      ZIO.scoped:
        for
          backend    <- store()
          location   <- address
          entered    <- Promise.make[Nothing, Unit]
          cleaning   <- Promise.make[Nothing, Unit]
          release    <- Promise.make[Nothing, Unit]
          controller <- ZIO.fiberId
          stage = ProcedureStage(
            "held",
            1,
            _ =>
              ZIO.acquireRelease(entered.succeed(()))(_ =>
                cleaning.succeed(()) *> release.await
              ) *> ZIO.never,
            (_, _) => ZIO.succeed(ProcedureVerdict.Accepted(Json.Str("unreachable")))
          )
          plan = ProcedureDefinition("release", "v1", Chunk(stage))
          session <- ProcedureSession.open(plan, Json.Str("input"), location, backend, authority)
          running <- ZIO.uninterruptibleMask: restore =>
            for
              fiber <- restore(session.run).forkScoped
              _     <- ZIO.addFinalizer(release.succeed(()).unit)
            yield fiber
          _         <- entered.await
          _         <- running.interruptAsFork(controller)
          _         <- cleaning.await
          held      <- running.poll
          _         <- release.succeed(())
          cancelled <- running.await
          recovery  <- session.advance
        yield assertTrue(
          held.isEmpty,
          cancelled.causeOption.exists(_.isInterrupted),
          recovery == ProcedureOutcome.RecoveryRequired("held")
        )
    ,
    test("one persisted deadline includes time spent before reopen"):
      ZIO.scoped:
        for
          backend       <- store()
          location      <- address
          enteredFirst  <- Promise.make[Nothing, Unit]
          enteredSecond <- Promise.make[Nothing, Unit]
          stages = Chunk(("first", enteredFirst), ("second", enteredSecond)).map((id, entered) =>
            ProcedureStage(
              id,
              1,
              input => entered.succeed(()) *> ZIO.sleep(3.seconds).as(input),
              (_, _) => ZIO.succeed(ProcedureVerdict.Accepted(Json.Str("check")))
            )
          )
          plan = ProcedureDefinition(
            "deadline",
            "v1",
            stages,
            ProcedureLimits(timeoutMillis = 5000L)
          )
          first <- ZIO
            .scoped(
              ProcedureSession
                .open(plan, Json.Str("input"), location, backend, authority)
                .flatMap(_.advance)
            )
            .forkScoped
          _        <- enteredFirst.await
          _        <- TestClock.adjust(3.seconds)
          accepted <- first.join
          second <- ZIO
            .scoped(
              ProcedureSession
                .open(plan, Json.Str("input"), location, backend, authority)
                .flatMap(_.run)
            )
            .either
            .forkScoped
          _        <- enteredSecond.await
          _        <- TestClock.adjust(2.seconds)
          timedOut <- second.join
          recovery <- ZIO.scoped(
            ProcedureSession
              .open(plan, Json.Str("input"), location, backend, authority)
              .flatMap(_.advance)
          )
        yield assertTrue(
          accepted == ProcedureOutcome.Accepted("first"),
          timedOut.left.exists(_.isInstanceOf[AgentError.TimeoutError]),
          recovery == ProcedureOutcome.RecoveryRequired("second")
        )
    ,
    test("verifier cancellation retains submission and preserves joined cleanup defects"):
      ZIO.scoped:
        for
          backend  <- store()
          location <- address
          entered  <- Promise.make[Nothing, Unit]
          cleaning <- Promise.make[Nothing, Unit]
          release  <- Promise.make[Nothing, Unit]
          stage = ProcedureStage(
            "verify",
            1,
            input => ZIO.succeed(input),
            (_, _) =>
              ZIO.acquireRelease(entered.succeed(()))(_ =>
                cleaning.succeed(()) *> release.await *> ZIO.dieMessage("verifier cleanup failed")
              ) *> ZIO.never
          )
          plan = ProcedureDefinition("release", "v1", Chunk(stage))
          session <- ProcedureSession.open(plan, Json.Str("input"), location, backend, authority)
          running <- ZIO.uninterruptibleMask: restore =>
            for
              fiber <- restore(session.run).forkScoped
              _     <- ZIO.addFinalizer(release.succeed(()).unit)
            yield fiber
          controller <- ZIO.fiberId
          _          <- entered.await
          _          <- running.interruptAsFork(controller)
          _          <- cleaning.await
          held       <- running.poll
          _          <- release.succeed(())
          exit       <- running.await
          saved      <- session.snapshot
        yield assertTrue(
          held.isEmpty,
          exit.causeOption.exists(_.isInterrupted),
          exit.causeOption.exists(_.defects.exists(_.getMessage == "verifier cleanup failed")),
          saved.phase == ProcedurePhase.Submitted(1, Json.Str("input")),
          saved.verifications == 1
        )
    ,
    test("admission is visible before execution and oversized output cannot become a submission"):
      for
        backend  <- store()
        location <- address
        admitted <- Ref.make(false)
        stage = ProcedureStage(
          "bounded",
          1,
          _ =>
            backend
              .read(location)
              .flatMap(record =>
                admitted.set(
                  record.exists(
                    _.value
                      .as[ProcedureSnapshot]
                      .exists(snapshot =>
                        snapshot.phase == ProcedurePhase.Executing(1) && snapshot.executions == 1
                      )
                  )
                )
              )
              .as(Json.Str("output larger than eight bytes")),
          (_, _) => ZIO.succeed(ProcedureVerdict.Accepted(Json.Str("check")))
        )
        plan = ProcedureDefinition(
          "release",
          "v1",
          Chunk(stage),
          ProcedureLimits(maximumPayloadBytes = 8)
        )
        result <- ZIO.scoped:
          for
            session <- ProcedureSession.open(plan, Json.Str("input"), location, backend, authority)
            failed  <- session.advance.either
            saved   <- session.snapshot
          yield (failed, saved)
        observed <- admitted.get
      yield assertTrue(
        observed,
        result._1.isLeft,
        result._2.phase == ProcedurePhase.Executing(1),
        result._2.accepted.isEmpty
      )
    ,
    test("verification reservations survive failure and exhaust before a repeated verifier"):
      for
        backend       <- store()
        location      <- address
        executions    <- Ref.make(0)
        verifications <- Ref.make(0)
        stage = ProcedureStage(
          "check",
          2,
          input => executions.update(_ + 1).as(input),
          (_, _) =>
            verifications.update(_ + 1) *> ZIO.fail(
              AgentError.InvalidMessage("Verifier unavailable")
            )
        )
        plan = ProcedureDefinition(
          "release",
          "v1",
          Chunk(stage),
          ProcedureLimits(maximumVerifications = 1)
        )
        failed <- ZIO.scoped(
          ProcedureSession
            .open(plan, Json.Str("input"), location, backend, authority)
            .flatMap(_.advance.either)
        )
        resumed <- ZIO.scoped(
          ProcedureSession
            .open(plan, Json.Str("input"), location, backend, authority)
            .flatMap(_.run)
        )
        executed <- executions.get
        verified <- verifications.get
      yield assertTrue(
        failed.isLeft,
        resumed == ProcedureOutcome.Exhausted("Procedure verification budget exhausted"),
        executed == 1,
        verified == 1
      )
    ,
    test("deadline expiry during verification retains submission and does not reset on reopen"):
      ZIO.scoped:
        for
          backend       <- store()
          location      <- address
          entered       <- Promise.make[Nothing, Unit]
          verifications <- Ref.make(0)
          stage = ProcedureStage(
            "verify",
            1,
            input => ZIO.succeed(input),
            (_, _) =>
              verifications.update(_ + 1) *> entered.succeed(()) *>
                ZIO.sleep(6.seconds).as(ProcedureVerdict.Accepted(Json.Str("late")))
          )
          plan = ProcedureDefinition(
            "deadline",
            "v1",
            Chunk(stage),
            ProcedureLimits(timeoutMillis = 5000L)
          )
          running <- ZIO
            .scoped(
              ProcedureSession
                .open(plan, Json.Str("input"), location, backend, authority)
                .flatMap(_.run)
            )
            .either
            .forkScoped
          _        <- entered.await
          _        <- TestClock.adjust(5.seconds)
          timedOut <- running.join
          resumed <- ZIO.scoped(
            ProcedureSession
              .open(plan, Json.Str("input"), location, backend, authority)
              .flatMap(_.run)
          )
          verified <- verifications.get
        yield assertTrue(
          timedOut.left.exists(_.isInstanceOf[AgentError.TimeoutError]),
          resumed == ProcedureOutcome.Exhausted("Procedure deadline exhausted"),
          verified == 1
        )
    ,
    test("exact decimal byte limits and bounded JSON depth apply before initial publication"):
      val text    = "-0.9223372036854775808"
      val decimal = Json.Num(new java.math.BigDecimal(text))
      val deep    = (1 to 66).foldLeft[Json](Json.Str("leaf"))((value, _) => Json.Arr(value))
      for
        backend  <- store()
        location <- address
        calls    <- Ref.make(Chunk.empty[String])
        base  = definition(calls)
        exact = base.copy(limits = ProcedureLimits(maximumPayloadBytes = text.length))
        stored <- ZIO.scoped(
          ProcedureSession.open(exact, decimal, location, backend, authority).flatMap(_.snapshot)
        )
        tooSmall <- ZIO.scoped(
          ProcedureSession
            .open(
              exact.copy(limits = exact.limits.copy(maximumPayloadBytes = text.length - 1)),
              decimal,
              location,
              backend,
              authority
            )
            .either
        )
        tooDeep <- ZIO.scoped(
          ProcedureSession.open(base, deep, location, backend, authority).either
        )
      yield assertTrue(stored.input == decimal, tooSmall.isLeft, tooDeep.isLeft)
    ,
    test("deadline joins verifier cleanup and preserves its defect alongside the timeout"):
      ZIO.scoped:
        for
          backend  <- store()
          location <- address
          entered  <- Promise.make[Nothing, Unit]
          stage = ProcedureStage(
            "verify",
            1,
            input => ZIO.succeed(input),
            (_, _) =>
              ZIO.acquireRelease(entered.succeed(()))(_ =>
                ZIO.dieMessage("deadline cleanup failed")
              ) *> ZIO.never
          )
          plan = ProcedureDefinition(
            "deadline",
            "v1",
            Chunk(stage),
            ProcedureLimits(timeoutMillis = 1000L)
          )
          session <- ProcedureSession.open(plan, Json.Str("input"), location, backend, authority)
          running <- session.run.exit.forkScoped
          _       <- entered.await
          _       <- TestClock.adjust(1.second)
          exit    <- running.join
          saved   <- session.snapshot
        yield assertTrue(
          exit.causeOption.exists(_.defects.exists(_.getMessage == "deadline cleanup failed")),
          exit.causeOption.exists(_.failures.exists(_.isInstanceOf[AgentError.TimeoutError])),
          saved.phase == ProcedurePhase.Submitted(1, Json.Str("input")),
          saved.accepted.isEmpty
        )
    ,
    test("malformed Unicode is rejected before procedure execution"):
      ZIO.scoped:
        for
          backend  <- store()
          location <- address
          calls    <- Ref.make(Chunk.empty[String])
          result <- ProcedureSession
            .open(definition(calls), Json.Str("\uD800"), location, backend, authority)
            .either
          observed <- calls.get
        yield assertTrue(result.isLeft, observed.isEmpty)
  )
