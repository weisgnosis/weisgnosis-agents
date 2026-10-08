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
import com.weisgnosis.agents.harness.memory.MemoryTestSupport.{children, temporaryDirectory}
import java.nio.file.Files
import zio.*
import zio.test.*

object WorkspaceLeaseSpec extends ZIOSpecDefault:
  private enum Initialization:
    case Plan, Transcript, Memory

  private enum Construction:
    case Failed, Cancelled, Successful

  private def initializationLifecycle(
      mode: Construction
  ): ZIO[TestEnvironment & Scope, Throwable, TestResult] =
    ZIO.scoped:
      for
        root         <- temporaryDirectory
        host         <- ZIO.acquireRelease(Scope.make)(_.close(Exit.unit))
        entered      <- Promise.make[Nothing, Unit]
        cleaning     <- Promise.make[Nothing, Unit]
        release      <- Promise.make[Nothing, Unit]
        cleanupCount <- Ref.make(0)
        failure = AgentError.StorageError("Initialization failed exactly")
        defect  = new IllegalStateException("Initialization cleanup failed exactly")
        construction = WorkspaceLease.initialize:
          for
            lease <- WorkspaceLease.openNamed(
              root,
              WorkspaceOwner.VersionedJson,
              ".construction.lease"
            )
            _ <- ZIO.addFinalizer(cleanupCount.update(_ + 1) *> (mode match
              case Construction.Successful => ZIO.unit
              case Construction.Failed     => ZIO.die(defect)
              case Construction.Cancelled =>
                cleaning.succeed(()) *> release.await *> ZIO.die(defect)))
            _ <- entered.succeed(())
            _ <- mode match
              case Construction.Successful => ZIO.unit
              case Construction.Failed     => ZIO.fail(failure)
              case Construction.Cancelled  => ZIO.never
          yield lease
        worker <- ZIO.uninterruptibleMask: restore =>
          for
            fiber <- restore(host.extend(construction)).forkScoped
            _     <- ZIO.addFinalizer(release.succeed(()).unit)
          yield fiber
        controller <- ZIO.fiberId
        held <- mode match
          case Construction.Cancelled =>
            for
              _     <- entered.await
              _     <- worker.interruptAsFork(controller)
              _     <- cleaning.await
              early <- worker.poll
              competitor <- ZIO
                .scoped(
                  WorkspaceLease
                    .openNamed(root, WorkspaceOwner.VersionedJson, ".construction.lease")
                )
                .either
              _ <- release.succeed(())
            yield early.isEmpty && competitor.isLeft
          case _ => ZIO.succeed(true)
        result      <- worker.await
        beforeClose <- cleanupCount.get
        entries     <- children(root)
        validResult <- result match
          case Exit.Success(lease) => lease.protect(ZIO.unit).as(mode == Construction.Successful)
          case Exit.Failure(cause) =>
            ZIO.succeed(mode match
              case Construction.Failed =>
                cause.failures == List(failure) && cause.defects == List(
                  defect
                ) && !cause.isInterrupted
              case Construction.Cancelled =>
                cause.failures.isEmpty && cause.defects == List(defect) && cause.interruptors
                  .contains(controller)
              case Construction.Successful => false)
        reacquired <- mode match
          case Construction.Successful => ZIO.succeed(true)
          case _ =>
            ZIO
              .scoped(
                WorkspaceLease.openNamed(root, WorkspaceOwner.VersionedJson, ".construction.lease")
              )
              .as(true)
        hostClose    <- host.close(Exit.unit).exit
        afterClose   <- cleanupCount.get
        afterEntries <- children(root)
        escaped <- result match
          case Exit.Success(lease) => lease.protect(ZIO.unit).either.map(_.isLeft)
          case Exit.Failure(_)     => ZIO.succeed(true)
      yield assertTrue(
        held,
        validResult,
        reacquired,
        hostClose.isSuccess,
        afterClose == 1,
        afterEntries.isEmpty,
        escaped,
        beforeClose == (if mode == Construction.Successful then 0 else 1),
        entries == (if mode == Construction.Successful then Set(".construction.lease")
                    else Set.empty[String])
      )

  def spec: Spec[TestEnvironment & Scope, Any] = suite("WorkspaceLeaseSpec")(
    test(
      "construction scopes join failure and cancellation cleanup promptly without replaying finalizers at host closure"
    ):
      ZIO
        .foreach(Chunk(Construction.Failed, Construction.Cancelled, Construction.Successful))(
          initializationLifecycle
        )
        .map(_.foldLeft(assertTrue(true))(_ && _))
    ,
    test(
      "failed local initialization releases ownership before a live host scope catches the error"
    ):
      ZIO
        .foreach(Chunk(Initialization.Plan, Initialization.Transcript, Initialization.Memory))(
          kind =>
            ZIO.scoped:
              for
                root <- temporaryDirectory
                directory <- kind match
                  case Initialization.Memory =>
                    ZIO.attemptBlocking(Files.createDirectory(root.resolve("memory")))
                  case _ => ZIO.succeed(root)
                payload = kind match
                  case Initialization.Plan       => "PLAN.md"
                  case Initialization.Transcript => "transcript.jsonl"
                  case Initialization.Memory     => ".consolidation_journal"
                _ <- ZIO.attemptBlocking(
                  Files.writeString(
                    directory.resolve(payload),
                    " ",
                    java.nio.charset.StandardCharsets.UTF_8
                  )
                )
                acquire = kind match
                  case Initialization.Plan =>
                    com.weisgnosis.agents.harness.plan.LocalPlanDraftStore
                      .open(
                        root,
                        com.weisgnosis.agents.harness.eviction.WorkspaceTrust.HostControlled
                      )
                      .unit
                  case Initialization.Transcript =>
                    com.weisgnosis.agents.harness.transcript.LocalTranscriptStore
                      .open(
                        root,
                        com.weisgnosis.agents.harness.eviction.WorkspaceTrust.HostControlled
                      )
                      .unit
                  case Initialization.Memory =>
                    com.weisgnosis.agents.harness.memory.LocalMemoryRepository
                      .open(
                        root,
                        com.weisgnosis.agents.harness.eviction.WorkspaceTrust.HostControlled
                      )
                      .unit
                opened   <- acquire.either
                entries  <- children(directory)
                _        <- ZIO.attemptBlocking(Files.delete(directory.resolve(payload)))
                repaired <- acquire.either
              yield assertTrue(opened.isLeft, entries == Set(payload), repaired.isRight)
        )
        .map(_.foldLeft(assertTrue(true))(_ && _))
    ,
    test("plan ownership verification and release defects preserve the plan error identity") {
      for
        root   <- temporaryDirectory
        denied <- Ref.make(false)
        exit <- ZIO
          .scoped:
            for
              owner <- WorkspaceLease.open(root, WorkspaceOwner.Plan)
              _ <- ZIO.attemptBlocking(
                Files.writeString(root.resolve(".plan-writer.lease"), "changed")
              )
              rejected <- owner.protect(ZIO.unit).either
              _ <- denied.set(rejected.left.exists(_.isInstanceOf[AgentError.PlanStorageError]))
            yield ()
          .exit
        retained <- ZIO.attemptBlocking(Files.readString(root.resolve(".plan-writer.lease")))
        rejected <- denied.get
      yield assertTrue(
        rejected,
        exit.causeOption.exists(_.defects.exists(_.isInstanceOf[AgentError.PlanStorageError])),
        retained == "changed"
      )
    },
    test("plan admitted work finishes before interrupted ownership releases") {
      for
        root      <- temporaryDirectory
        entered   <- Promise.make[Nothing, Unit]
        release   <- Promise.make[Nothing, Unit]
        completed <- Ref.make(false)
        fiber <- ZIO
          .scoped:
            WorkspaceLease
              .open(root, WorkspaceOwner.Plan)
              .flatMap(
                _.protect(
                  entered.succeed(()) *> release.await *> completed.set(true)
                )
              )
          .forkScoped
        _          <- entered.await
        interrupt  <- fiber.interrupt.forkScoped
        _          <- TestClock.adjust(0.seconds)
        pending    <- interrupt.poll
        competitor <- ZIO.scoped(WorkspaceLease.open(root, WorkspaceOwner.Plan)).either
        _          <- release.succeed(())
        exit       <- interrupt.join
        done       <- completed.get
        names      <- children(root)
      yield assertTrue(pending.isEmpty, competitor.isLeft, exit.isInterrupted, done, names.isEmpty)
    },
    test(
      "failed admitted work preserves its failure and pending cancellation after ownership cleanup"
    ) {
      for
        root    <- temporaryDirectory
        entered <- Promise.make[Nothing, Unit]
        release <- Promise.make[Nothing, Unit]
        failure = AgentError.PlanStorageError("Admitted operation failed")
        fiber <- ZIO
          .scoped:
            WorkspaceLease
              .open(root, WorkspaceOwner.Plan)
              .flatMap(
                _.protect(entered.succeed(()) *> release.await *> ZIO.fail(failure))
              )
          .forkScoped
        _          <- entered.await
        interrupt  <- fiber.interrupt.forkScoped
        _          <- TestClock.adjust(0.seconds)
        pending    <- interrupt.poll
        competitor <- ZIO.scoped(WorkspaceLease.open(root, WorkspaceOwner.Plan)).either
        _          <- release.succeed(())
        exit       <- interrupt.join
        names      <- children(root)
      yield assertTrue(
        pending.isEmpty,
        competitor.isLeft,
        exit.isInterrupted,
        exit.causeOption.exists(_.failures == Chunk(failure)),
        names.isEmpty
      )
    },
    test("typed owners retain independent fixed ownership filenames") {
      for
        root <- temporaryDirectory
        active <- ZIO.scoped:
          for
            _     <- WorkspaceLease.open(root, WorkspaceOwner.Memory)
            _     <- WorkspaceLease.open(root, WorkspaceOwner.Transcript)
            _     <- WorkspaceLease.open(root, WorkspaceOwner.Plan)
            names <- children(root)
          yield names
        after <- children(root)
      yield assertTrue(
        active == Set(".daily-writer.lease", ".transcript-writer.lease", ".plan-writer.lease"),
        after.isEmpty
      )
    },
    test("transcript ownership excludes competitors and rejects handles after release") {
      for
        root <- temporaryDirectory
        closed <- ZIO.scoped:
          for
            owner      <- WorkspaceLease.open(root, WorkspaceOwner.Transcript)
            competitor <- ZIO.scoped(WorkspaceLease.open(root, WorkspaceOwner.Transcript)).either
            _ <- ZIO
              .fail(AgentError.MemoryStorageError("Competing owner admitted"))
              .unless(competitor.isLeft)
          yield owner
        rejected <- closed.protect(ZIO.unit).either
        next <- ZIO.scoped(
          WorkspaceLease
            .open(root, WorkspaceOwner.Transcript)
            .flatMap(_.protect(ZIO.succeed("reopened")))
        )
      yield assertTrue(rejected.isLeft, next == "reopened")
    },
    test("an abandoned transcript token is never cleared by another owner") {
      for
        root <- temporaryDirectory
        _ <- ZIO.attemptBlocking(
          Files.writeString(root.resolve(".transcript-writer.lease"), "stale")
        )
        failed   <- ZIO.scoped(WorkspaceLease.open(root, WorkspaceOwner.Transcript)).either
        retained <- ZIO.attemptBlocking(Files.readString(root.resolve(".transcript-writer.lease")))
      yield assertTrue(failed.isLeft, retained == "stale")
    },
    test(
      "checked named leases isolate exact targets while preserving defaults and rejecting filename aliases"
    ):
      for
        root     <- temporaryDirectory
        lifetime <- ZIO.acquireRelease(Scope.make)(_.close(Exit.unit))
        first <- lifetime.extend(
          WorkspaceLease.openNamed(
            root,
            WorkspaceOwner.VersionedJson,
            ".first.json.versioned-writer.lease"
          )
        )
        _ <- lifetime.extend(
          WorkspaceLease.openNamed(
            root,
            WorkspaceOwner.VersionedJson,
            ".second.json.versioned-writer.lease"
          )
        )
        _ <- lifetime.extend(WorkspaceLease.open(root, WorkspaceOwner.Plan))
        duplicate <- ZIO
          .scoped(
            WorkspaceLease
              .openNamed(root, WorkspaceOwner.VersionedJson, ".first.json.versioned-writer.lease")
          )
          .either
        invalid <- ZIO.foreach(
          Chunk(
            "../escape",
            "UPPER.lease",
            "short~1.lease",
            "con",
            "space ",
            "nonascii-λ",
            "x" * 256
          )
        )(name =>
          ZIO
            .scoped(WorkspaceLease.openNamed(root, WorkspaceOwner.VersionedJson, name))
            .either
            .map(_.isLeft)
        )
        active  <- children(root)
        _       <- lifetime.close(Exit.unit)
        escaped <- first.protect(ZIO.unit).either
        after   <- children(root)
      yield assertTrue(
        duplicate.isLeft,
        invalid.forall(identity),
        active == Set(
          ".first.json.versioned-writer.lease",
          ".second.json.versioned-writer.lease",
          ".plan-writer.lease"
        ),
        escaped == Left(AgentError.StorageError("Workspace storage store scope is closed")),
        after.isEmpty
      )
  )
