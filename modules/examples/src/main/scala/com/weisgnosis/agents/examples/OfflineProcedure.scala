/* Copyright 2024-2026 WeisGnosis. Licensed under the Apache License, Version 2.0. */
package com.weisgnosis.agents.examples

import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.harness.eviction.WorkspaceTrust
import com.weisgnosis.agents.harness.procedure.*
import com.weisgnosis.agents.harness.storage.*
import java.nio.file.{Files, Path}
import zio.*
import zio.json.ast.Json

/** Reopens the real local backend between accepted stages; no model or credentials are required. */
object OfflineProcedure extends ZIOAppDefault:
  private def io[A](operation: => A): IO[AgentError, A] =
    ZIO.attemptBlocking(operation).mapError(error => AgentError.StorageError(error.toString))

  final case class Receipt(
      first: ProcedureOutcome,
      resumed: ProcedureOutcome,
      executions: Chunk[String]
  )

  def exercise: IO[AgentError, Receipt] =
    ZIO.scoped:
      for
        directory <- ZIO.acquireRelease(io(Files.createTempDirectory("weisgnosis-procedure-")))(
          directory =>
            (io(Files.deleteIfExists(directory.resolve("procedure.json"))).unit *>
              io(Files.delete(directory))).orDie
        )
        path = directory.resolve("procedure.json")
        address <- ZIO.fromEither(StorageAddress.from(Chunk("offline-procedure"), "release"))
        claims  <- Semaphore.make(1)
        authority = new ProcedureSession.Authority:
          def acquire(candidate: StorageAddress): ZIO[Scope, AgentError, Unit] =
            if candidate != address then
              ZIO.fail(AgentError.StorageError("Wrong procedure address"))
            else claims.withPermitScoped
        calls <- Ref.make(Chunk.empty[String])
        definition = ProcedureDefinition(
          "release",
          "v1",
          Chunk("draft", "package").map(id =>
            ProcedureStage(
              id,
              1,
              input => calls.update(_ :+ id).as(Json.Str(s"$id accepted after $input")),
              (_, submission) => ZIO.succeed(ProcedureVerdict.Accepted(submission))
            )
          )
        )
        first    <- reopen(path, address, definition, authority)(_.advance)
        resumed  <- reopen(path, address, definition, authority)(_.run)
        observed <- calls.get
        _ <- ZIO
          .fail(AgentError.InvalidMessage("Procedure replayed an accepted stage"))
          .unless(
            first == ProcedureOutcome.Accepted("draft") && observed == Chunk(
              "draft",
              "package"
            ) && resumed.isInstanceOf[ProcedureOutcome.Completed]
          )
      yield Receipt(first, resumed, observed)

  private def reopen[A](
      path: Path,
      address: StorageAddress,
      definition: ProcedureDefinition,
      authority: ProcedureSession.Authority
  )(use: ProcedureSession => IO[AgentError, A]): IO[AgentError, A] =
    ZIO.scoped:
      for
        backend <- LocalVersionedJsonStore.open(path, address, WorkspaceTrust.HostControlled)
        session <- ProcedureSession.open(
          definition,
          Json.Str("release input"),
          address,
          backend,
          authority
        )
        result <- use(session)
      yield result

  def run: ZIO[Any, Throwable, Unit] = exercise.flatMap(receipt =>
    Console.printLine(
      s"Accepted draft, reopened local storage, completed package: ${receipt.executions.mkString(", ")}"
    )
  )
