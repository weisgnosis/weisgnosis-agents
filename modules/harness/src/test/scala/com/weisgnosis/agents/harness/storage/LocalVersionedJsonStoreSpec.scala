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
package com.weisgnosis.agents.harness.storage

import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.harness.eviction.WorkspaceTrust
import com.weisgnosis.agents.harness.memory.MemoryTestSupport.{children, temporaryDirectory}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, StandardCopyOption}
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

object LocalVersionedJsonStoreSpec extends ZIOSpecDefault:
  private def address(key: String): IO[AgentError, StorageAddress] =
    ZIO.fromEither(StorageAddress.from(Chunk("owner λ", "session"), key))

  private def wire(address: StorageAddress, revision: Json, value: Json): IO[AgentError, Json] =
    ZIO
      .fromEither(address.toJsonAST.left.map(AgentError.StorageError(_)))
      .map(encoded =>
        Json.Obj(
          "formatVersion" -> Json.Num(1),
          "address"       -> encoded,
          "record"        -> Json.Obj("value" -> value, "revision" -> revision)
        )
      )

  private enum Replacement:
    case Failed, Cancelled, CleanupFailed

  private def replacementOwnership(
      mode: Replacement
  ): ZIO[TestEnvironment & Scope, Throwable, TestResult] =
    ZIO.scoped:
      for
        directory <- temporaryDirectory
        bound     <- address("replacement")
        target = directory.resolve("owned.json")
        revision <- ZIO.fromEither(StorageRevision.fromLong(1L))
        _ <- ZIO.scoped(
          LocalVersionedJsonStore
            .open(target, bound, WorkspaceTrust.HostControlled)
            .flatMap(_.compareAndSet(bound, ExpectedRevision.Absent, Json.Str("original")))
        )
        original <- ZIO.attemptBlocking(Files.readString(target, StandardCharsets.UTF_8))
        entered  <- Promise.make[Nothing, Unit]
        release  <- Promise.make[Nothing, Unit]
        failure = AgentError.StorageError("Atomic replacement failed exactly")
        lifetime <- ZIO.acquireRelease(Scope.make)(_.close(Exit.unit))
        store <- lifetime.extend(
          LocalVersionedJsonStore.openWith(
            target,
            bound,
            WorkspaceTrust.HostControlled,
            LocalVersionedJsonStore.Limits.default
          )((temporary, destination) =>
            mode match
              case Replacement.Failed => ZIO.fail(failure)
              case Replacement.Cancelled =>
                entered.succeed(()) *> release.await *>
                  ZIO
                    .attemptBlocking(
                      Files.move(
                        temporary,
                        destination,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                      )
                    )
                    .mapError(error =>
                      AgentError.StorageError(Option(error.getMessage).getOrElse(error.toString))
                    )
                    .unit
              case Replacement.CleanupFailed =>
                ZIO
                  .attemptBlocking:
                    Files.delete(temporary)
                    Files.createDirectory(temporary)
                    Files.writeString(temporary.resolve("fixture-owned"), "held cleanup")
                  .mapError(error =>
                    AgentError.StorageError(Option(error.getMessage).getOrElse(error.toString))
                  ) *> ZIO.fail(failure)
          )
        )
        worker <- ZIO.uninterruptibleMask: restore =>
          for
            fiber <- restore(
              store.compareAndSet(
                bound,
                ExpectedRevision.AtRevision(revision),
                Json.Str("replacement")
              )
            ).forkScoped
            _ <- ZIO.addFinalizer(release.succeed(()).unit)
          yield fiber
        cancellation <- mode match
          case Replacement.Cancelled =>
            for
              _           <- entered.await
              interrupter <- worker.interrupt.forkScoped
              _           <- TestClock.adjust(0.seconds)
              early       <- interrupter.poll
              competitor <- ZIO
                .scoped(LocalVersionedJsonStore.open(target, bound, WorkspaceTrust.HostControlled))
                .either
              _    <- release.succeed(())
              exit <- interrupter.join
            yield (exit, early.isEmpty && competitor.isLeft)
          case _ => worker.await.map(_ -> true)
        _        <- lifetime.close(Exit.unit)
        bytes    <- ZIO.attemptBlocking(Files.readString(target, StandardCharsets.UTF_8))
        reopened <- LocalVersionedJsonStore.open(target, bound, WorkspaceTrust.HostControlled)
        after    <- reopened.read(bound)
        names    <- children(directory)
        validOutcome = mode match
          case Replacement.Cancelled =>
            cancellation._1.isInterrupted && after.exists(record =>
              record.revision.value == 2L && record.value == Json.Str("replacement")
            )
          case Replacement.Failed =>
            cancellation._1.causeOption.exists(_.failures == List(failure)) && bytes == original
          case Replacement.CleanupFailed =>
            cancellation._1.causeOption.exists(cause =>
              cause.failures.take(1) == List(
                failure
              ) && cause.failures.size == 2 && cause.defects.isEmpty && !cause.isInterrupted
            ) && bytes == original
      yield assertTrue(
        cancellation._2,
        validOutcome,
        if mode == Replacement.CleanupFailed then
          names.count(_.startsWith(".versioned-json-pending-")) == 1
        else names == Set("owned.json", ".owned.json.versioned-writer.lease")
      )

  def spec: Spec[TestEnvironment & Scope, Any] = suite("LocalVersionedJsonStoreSpec")(
    test(
      "actual atomic file CAS retains exact Unicode and revisions across reopen; distinct targets coexist and stale writers cannot overwrite"
    ):
      for
        directory     <- temporaryDirectory
        firstAddress  <- address("first")
        secondAddress <- address("second")
        target = directory.resolve("sess-rt.json")
        other  = directory.resolve("other.json")
        owner <- ZIO.acquireRelease(Scope.make)(_.close(Exit.unit))
        store <- owner.extend(
          LocalVersionedJsonStore.open(target, firstAddress, WorkspaceTrust.HostControlled)
        )
        second <- owner.extend(
          LocalVersionedJsonStore.open(other, secondAddress, WorkspaceTrust.HostControlled)
        )
        absent       <- store.read(firstAddress)
        wrongAddress <- store.read(secondAddress).either
        competitor <- ZIO
          .scoped(LocalVersionedJsonStore.open(target, firstAddress, WorkspaceTrust.HostControlled))
          .either
        caseAlias <- ZIO
          .scoped(
            LocalVersionedJsonStore
              .open(directory.resolve("SESS-RT.json"), firstAddress, WorkspaceTrust.HostControlled)
          )
          .either
        shortAlias <- ZIO
          .scoped(
            LocalVersionedJsonStore
              .open(directory.resolve("sess-r~1.json"), firstAddress, WorkspaceTrust.HostControlled)
          )
          .either
        exact = Json.Obj(
          "output" -> Json.Str("full λ 😀\n" * 1000),
          "nested" -> Json.Arr(Json.Num(7), Json.Bool(true)),
          "integer" -> Json.Num(
            new java.math.BigDecimal("1234567890123456789012345678901234567890")
          ),
          "decimal" -> Json.Num(
            new java.math.BigDecimal("-9.2233720368547758081234567890123456789")
          )
        )
        first          <- store.compareAndSet(firstAddress, ExpectedRevision.Absent, exact)
        firstRevision  <- ZIO.fromEither(StorageRevision.fromLong(1L))
        exactRead      <- store.read(firstAddress)
        beforeConflict <- ZIO.attemptBlocking(Files.readString(target, StandardCharsets.UTF_8))
        conflict <- store.compareAndSet(
          firstAddress,
          ExpectedRevision.Absent,
          Json.Str("cannot overwrite")
        )
        afterConflict <- ZIO.attemptBlocking(Files.readString(target, StandardCharsets.UTF_8))
        parallel <- ZIO.foreachPar(Chunk("left", "right"))(text =>
          store.compareAndSet(
            firstAddress,
            ExpectedRevision.AtRevision(firstRevision),
            Json.Str(text)
          )
        )
        advanced <- store.read(firstAddress)
        _ <- second.compareAndSet(secondAddress, ExpectedRevision.Absent, Json.Str("independent"))
        foreign <- second.read(secondAddress)
        exists  <- ZIO.attemptBlocking(Files.isRegularFile(target))
        _       <- owner.close(Exit.unit)
        escaped <- store.read(firstAddress).either
        reopened <- LocalVersionedJsonStore.open(
          target,
          firstAddress,
          WorkspaceTrust.HostControlled
        )
        recovered <- reopened.read(firstAddress)
        names     <- children(directory)
        replacementChecks <- ZIO.foreach(
          Chunk(Replacement.Failed, Replacement.Cancelled, Replacement.CleanupFailed)
        )(replacementOwnership)
        applied = parallel.count:
          case ConditionalWrite.Applied(_) => true
          case _                           => false
      yield assertTrue(
        absent.isEmpty,
        wrongAddress == Left(
          AgentError.StorageError("Local JSON store is bound to a different address")
        ),
        competitor.isLeft,
        caseAlias.isLeft,
        shortAlias.isLeft,
        first == ConditionalWrite.Applied(firstRevision),
        beforeConflict.contains("full λ 😀"),
        conflict == ConditionalWrite.Conflict,
        beforeConflict == afterConflict,
        applied == 1,
        exactRead.contains(VersionedJson(exact, firstRevision)),
        parallel.count(_ == ConditionalWrite.Conflict) == 1,
        advanced.exists(record =>
          record.revision.value == 2L && Set(Json.Str("left"), Json.Str("right"))
            .contains(record.value)
        ),
        foreign.exists(record =>
          record.revision.value == 1L && record.value == Json.Str("independent")
        ),
        exists,
        escaped.isLeft,
        recovered == advanced,
        names == Set("sess-rt.json", "other.json", ".sess-rt.json.versioned-writer.lease")
      ) && replacementChecks.foldLeft(assertTrue(true))(_ && _)
    ,
    test(
      "untrusted paths, ambiguous or invalid envelopes, exhausted revisions and byte overflow fail without replacing existing bytes"
    ):
      for
        directory <- temporaryDirectory
        bound     <- address("bound")
        different <- address("different")
        target = directory.resolve("checked.json")
        valid              <- wire(bound, Json.Num(1), Json.Str("exact λ"))
        wrong              <- wire(different, Json.Num(1), Json.Str("wrong partition"))
        stringRevision     <- wire(bound, Json.Str("1"), Json.Str("wrong type"))
        zeroRevision       <- wire(bound, Json.Num(0), Json.Str("zero"))
        fractionalRevision <- wire(bound, Json.Num(BigDecimal("1.5")), Json.Str("fraction"))
        surrogate          <- wire(bound, Json.Num(1), Json.Str("unpaired"))
        unknown = valid match
          case Json.Obj(fields) => Json.Obj(fields :+ ("unknown" -> Json.Bool(true)))
          case other            => other
        invalidTexts = Chunk(
          wrong.toJson,
          stringRevision.toJson,
          zeroRevision.toJson,
          fractionalRevision.toJson,
          unknown.toJson,
          valid.toJson.replace("\"formatVersion\":1", "\"formatVersion\":1,\"formatVersion\":1"),
          surrogate.toJson.replace("unpaired", "\\ud800")
        )
        failedOpenEntries <- ZIO.scoped:
          for
            _ <- ZIO.attemptBlocking(
              Files.writeString(target, wrong.toJson, StandardCharsets.UTF_8)
            )
            rejected <- LocalVersionedJsonStore
              .open(target, bound, WorkspaceTrust.HostControlled)
              .either
            entries <- children(directory)
            _ <- ZIO.attemptBlocking(
              Files.writeString(target, valid.toJson, StandardCharsets.UTF_8)
            )
            repaired  <- LocalVersionedJsonStore.open(target, bound, WorkspaceTrust.HostControlled)
            recovered <- repaired.read(bound)
          yield (rejected.isLeft, entries, recovered.exists(_.value == Json.Str("exact λ")))
        invalid <- ZIO.foreach(invalidTexts): text =>
          for
            _ <- ZIO.attemptBlocking(Files.writeString(target, text, StandardCharsets.UTF_8))
            opened <- ZIO
              .scoped(LocalVersionedJsonStore.open(target, bound, WorkspaceTrust.HostControlled))
              .either
            retained <- ZIO.attemptBlocking(Files.readString(target, StandardCharsets.UTF_8))
          yield opened.isLeft && retained == text
        _ <- ZIO.attemptBlocking(Files.write(target, Array(0xff.toByte, 0xfe.toByte)))
        invalidUtf8 <- ZIO
          .scoped(LocalVersionedJsonStore.open(target, bound, WorkspaceTrust.HostControlled))
          .either
        utf8Bytes <- ZIO.attemptBlocking(Files.readAllBytes(target).toList)
        maximum   <- wire(bound, Json.Num(Long.MaxValue), Json.Str("exhausted"))
        _ <- ZIO.attemptBlocking(Files.writeString(target, maximum.toJson, StandardCharsets.UTF_8))
        exhausted <- ZIO.scoped:
          for
            store    <- LocalVersionedJsonStore.open(target, bound, WorkspaceTrust.HostControlled)
            revision <- ZIO.fromEither(StorageRevision.fromLong(Long.MaxValue))
            exact <- store
              .compareAndSet(bound, ExpectedRevision.AtRevision(revision), Json.Str("replacement"))
              .either
            stale <- store.compareAndSet(bound, ExpectedRevision.Absent, Json.Str("replacement"))
          yield (exact, stale)
        maximumAfter <- ZIO.attemptBlocking(Files.readString(target, StandardCharsets.UTF_8))
        _ <- ZIO.attemptBlocking(Files.writeString(target, valid.toJson, StandardCharsets.UTF_8))
        limit <- ZIO.fromEither(
          LocalVersionedJsonStore.Limits.make(valid.toJson.getBytes(StandardCharsets.UTF_8).length)
        )
        overflow <- ZIO.scoped:
          for
            store <- LocalVersionedJsonStore.open(
              target,
              bound,
              WorkspaceTrust.HostControlled,
              limit
            )
            original <- store.read(bound)
            revision <- ZIO.fromEither(StorageRevision.fromLong(1L))
            denied <- store
              .compareAndSet(bound, ExpectedRevision.AtRevision(revision), Json.Str("x" * 1000))
              .either
            maximumRevision <- ZIO.fromEither(StorageRevision.fromLong(Long.MaxValue))
            maxStale <- store
              .compareAndSet(bound, ExpectedRevision.AtRevision(maximumRevision), Json.Str("stale"))
              .either
            duplicate <- store
              .compareAndSet(
                bound,
                ExpectedRevision.AtRevision(revision),
                Json.Obj("same" -> Json.Num(1), "same" -> Json.Num(2))
              )
              .either
          yield original.nonEmpty && denied.isLeft && duplicate.isLeft && maxStale == Left(
            AgentError.StorageError("Storage revision exhausted")
          )
        unchanged <- ZIO.attemptBlocking(Files.readString(target, StandardCharsets.UTF_8))
        untrusted <- ZIO
          .scoped(
            LocalVersionedJsonStore
              .open(directory.resolve("new.json"), bound, WorkspaceTrust.Untrusted)
          )
          .either
        traversal <- ZIO
          .scoped(
            LocalVersionedJsonStore
              .open(directory.resolve("child/../new.json"), bound, WorkspaceTrust.HostControlled)
          )
          .either
        invalidNames <- ZIO.foreach(
          Chunk("con.json", "upper.JSON", "nonascii-λ.json", "..json")
        )(name =>
          ZIO
            .scoped(
              LocalVersionedJsonStore
                .open(directory.resolve(name), bound, WorkspaceTrust.HostControlled)
            )
            .either
            .map(_.isLeft)
        )
        sensitiveParent <- ZIO.attemptBlocking(Files.createDirectory(directory.resolve(".ssh")))
        sensitive <- ZIO
          .scoped(
            LocalVersionedJsonStore
              .open(sensitiveParent.resolve("state.json"), bound, WorkspaceTrust.HostControlled)
          )
          .either
        sensitiveEntries <- children(sensitiveParent)
        nonregularTarget <- ZIO.attemptBlocking(
          Files.createDirectory(directory.resolve("nonregular.json"))
        )
        _ <- ZIO.attemptBlocking(
          Files.writeString(
            nonregularTarget.resolve("keep"),
            "fixture unchanged",
            StandardCharsets.UTF_8
          )
        )
        nonregular <- ZIO
          .scoped(
            LocalVersionedJsonStore.open(nonregularTarget, bound, WorkspaceTrust.HostControlled)
          )
          .either
        nonregularEntries <- children(nonregularTarget)
        nonregularContent <- ZIO.attemptBlocking(
          Files.readString(nonregularTarget.resolve("keep"), StandardCharsets.UTF_8)
        )
        names <- children(directory)
      yield assertTrue(
        failedOpenEntries._1,
        failedOpenEntries._2 == Set("checked.json"),
        failedOpenEntries._3,
        invalid.forall(identity),
        invalidUtf8.isLeft,
        utf8Bytes == List(0xff.toByte, 0xfe.toByte),
        exhausted._1 == Left(AgentError.StorageError("Storage revision exhausted")),
        exhausted._2 == ConditionalWrite.Conflict,
        maximumAfter == maximum.toJson,
        overflow,
        unchanged == valid.toJson,
        untrusted.isLeft,
        traversal.isLeft,
        invalidNames.forall(identity),
        WorkspaceLease.validateName("bad:stream.json", 128).isLeft,
        sensitive.isLeft,
        sensitiveEntries.isEmpty,
        nonregular.isLeft,
        nonregularEntries == Set("keep"),
        nonregularContent == "fixture unchanged",
        names == Set("checked.json", ".ssh", "nonregular.json"),
        LocalVersionedJsonStore.Limits.make(0).isLeft,
        LocalVersionedJsonStore.Limits.make(Int.MaxValue).isLeft
      )
  )
