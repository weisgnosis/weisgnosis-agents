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
package com.weisgnosis.agents.harness.transcript

import com.weisgnosis.agents.core.error.AgentError
import com.weisgnosis.agents.core.model.Role
import com.weisgnosis.agents.harness.eviction.WorkspaceTrust
import com.weisgnosis.agents.harness.memory.MemoryTestSupport.{children, temporaryDirectory}
import com.weisgnosis.agents.harness.storage.*
import java.nio.file.Files
import zio.*
import zio.test.*

object TranscriptDiscoverySpec extends ZIOSpecDefault:
  private enum Stop:
    case Failure, Cancellation

  private def binding(id: String, address: StorageAddress): IO[AgentError, TranscriptSession] =
    ZIO.fromEither(
      TranscriptTargetId
        .fromString(id)
        .flatMap(TranscriptSession.make(_, address, TranscriptLayout.Aggregate))
    )

  private def draft(id: String, text: String): TranscriptDraft = TranscriptDraft(
    Some(TranscriptKey.Message(id)),
    TranscriptPayload.Message(Role.User, text, Chunk(TranscriptBlockType.Text))
  )

  def spec: Spec[TestEnvironment & Scope, Any] = suite("TranscriptDiscoverySpec")(
    test(
      "explicit local and catalog sources union exact bindings without opening readers or changing files"
    ) {
      for
        directory      <- temporaryDirectory
        localDirectory <- ZIO.attemptBlocking(Files.createDirectory(directory.resolve("local")))
        local          <- LocalTranscriptStore.open(localDirectory, WorkspaceTrust.HostControlled)
        localEntries   <- local.append(Chunk(draft("local-message", "local observation")))
        catalogAddress <- ZIO.fromEither(StorageAddress.from(Chunk("workspace"), "catalog"))
        localAddress   <- ZIO.fromEither(StorageAddress.from(Chunk("explicit-local"), "log"))
        remoteAddress  <- ZIO.fromEither(StorageAddress.from(Chunk("explicit-remote"), "log"))
        catalogBackend <- LocalVersionedJsonStore.open(
          directory.resolve("catalog.json"),
          catalogAddress,
          WorkspaceTrust.HostControlled
        )
        remoteBackend <- LocalVersionedJsonStore.open(
          directory.resolve("remote.json"),
          remoteAddress,
          WorkspaceTrust.HostControlled
        )
        localSession  <- binding("local", localAddress)
        remoteSession <- binding("remote", remoteAddress)
        remote        <- ZIO.fromEither(VersionedTranscriptStore.make(remoteBackend, remoteAddress))
        remoteEntries <- remote.append(Chunk(draft("remote-message", "remote observation")))
        catalog <- ZIO.fromEither(
          VersionedTranscriptSessionCatalog.make(catalogBackend, catalogAddress)
        )
        _             <- catalog.register(remoteSession) *> catalog.register(localSession)
        catalogBefore <- catalogBackend.read(catalogAddress)
        namesBefore   <- children(localDirectory)
        bytesBefore <- ZIO.attemptBlocking(
          Files.readAllBytes(localDirectory.resolve("transcript.jsonl")).toVector
        )
        modifiedBefore <- ZIO.attemptBlocking(
          Files.getLastModifiedTime(localDirectory.resolve("transcript.jsonl"))
        )
        reads <- Ref.make(Chunk.empty[String])
        localSource = TranscriptDiscovery.Source.local(
          directory,
          WorkspaceTrust.HostControlled,
          id =>
            Right(
              TranscriptDiscovery.Target(localSession)(reads.update(_ :+ "local") *> local.entries)
            ).filterOrElse(
              _.session.id == id,
              AgentError.InvalidConfiguration("Unexpected selected directory")
            )
        )
        catalogSource = TranscriptDiscovery.Source.catalog(
          catalog,
          session =>
            reads.update(
              _ :+ session.id.value
            ) *> (if session == remoteSession then remote.entries
                  else
                    ZIO.fail(
                      AgentError.InvalidConfiguration(
                        "Duplicate reader must not replace first source"
                      )
                    ))
        )
        sources = Chunk(localSource, catalogSource)
        targets     <- TranscriptDiscovery.discover(sources)
        readsBefore <- reads.get
        namesAfter  <- children(localDirectory)
        bytesAfter <- ZIO.attemptBlocking(
          Files.readAllBytes(localDirectory.resolve("transcript.jsonl")).toVector
        )
        modifiedAfter <- ZIO.attemptBlocking(
          Files.getLastModifiedTime(localDirectory.resolve("transcript.jsonl"))
        )
        catalogAfter <- catalogBackend.read(catalogAddress)
        observed     <- ZIO.foreach(targets)(target => target.entries.map(target.session -> _))
        readOrder    <- reads.get
        duplicateOverflow <- TranscriptDiscovery
          .discover(sources, TranscriptDiscovery.Limits(maxTargets = 2))
          .either
        conflictingSession <- binding("local", remoteAddress)
        physicalSession    <- binding("other", localAddress)
        conflicts <- ZIO.foreach(Chunk(conflictingSession, physicalSession)): session =>
          val source = TranscriptDiscovery.Source(
            ZIO.succeed(
              Chunk(
                TranscriptDiscovery.Target(session)(ZIO.succeed(Chunk.empty))
              )
            )
          )
          TranscriptDiscovery.discover(Chunk(localSource, source)).either
        wrongMapping <- TranscriptDiscovery
          .discover(
            Chunk(
              TranscriptDiscovery.Source.local(
                directory,
                WorkspaceTrust.HostControlled,
                _ => Right(TranscriptDiscovery.Target(remoteSession)(remote.entries))
              )
            )
          )
          .either
      yield assertTrue(
        targets.map(_.session) == Chunk(localSession, remoteSession),
        readsBefore.isEmpty,
        namesAfter == namesBefore,
        bytesAfter == bytesBefore,
        modifiedAfter == modifiedBefore,
        catalogAfter == catalogBefore,
        observed == Chunk(localSession -> localEntries, remoteSession -> remoteEntries),
        readOrder == Chunk("local", "remote"),
        duplicateOverflow.isLeft,
        conflicts.forall(_.isLeft),
        wrongMapping.isLeft
      )
    },
    test(
      "bounds reject before acquisition and failures or interruption join discovery cleanup before stopping"
    ) {
      for
        calls <- Ref.make(0)
        source = TranscriptDiscovery.Source(calls.update(_ + 1).as(Chunk.empty))
        rejected <- ZIO.foreach(
          Chunk(
            (Chunk(source), TranscriptDiscovery.Limits(0, 1)),
            (Chunk(source), TranscriptDiscovery.Limits(1, 0)),
            (Chunk(source, source), TranscriptDiscovery.Limits(1, 1))
          )
        )((sources, limits) => TranscriptDiscovery.discover(sources, limits).either)
        before <- calls.get
        empty  <- TranscriptDiscovery.discover(Chunk.empty)
        outcomes <- ZIO.foreach(Chunk(Stop.Failure, Stop.Cancellation)): mode =>
          ZIO.scoped:
            val failure = AgentError.MemoryStorageError("Named discovery failure")
            val defect  = new IllegalStateException("Named discovery release defect")
            for
              entered        <- Promise.make[Nothing, Unit]
              cleanupEntered <- Promise.make[Nothing, Unit]
              release        <- Promise.make[Nothing, Unit]
              later          <- Ref.make(0)
              first = TranscriptDiscovery.Source(
                ZIO.acquireRelease(ZIO.unit)(_ =>
                  cleanupEntered.succeed(()).unit *> release.await *> ZIO.die(defect)
                ) *>
                  entered.succeed(()).unit *> (mode match
                    case Stop.Failure      => ZIO.fail(failure)
                    case Stop.Cancellation => ZIO.never)
              )
              next = TranscriptDiscovery.Source(later.update(_ + 1).as(Chunk.empty))
              controller <- ZIO.fiberId
              running <- ZIO.uninterruptibleMask: restore =>
                for
                  fiber <- restore(TranscriptDiscovery.discover(Chunk(first, next))).forkScoped
                  _     <- ZIO.addFinalizer(release.succeed(()).unit)
                yield fiber
              _        <- entered.await
              _        <- running.interruptAsFork(controller).when(mode == Stop.Cancellation)
              _        <- cleanupEntered.await
              early    <- running.poll
              _        <- release.succeed(())
              exit     <- running.await
              attempts <- later.get
            yield assertTrue(
              early.isEmpty,
              attempts == 0,
              exit.causeOption.exists(cause =>
                cause.defects == List(defect) && (mode match
                  case Stop.Failure      => cause.failures == List(failure)
                  case Stop.Cancellation => cause.interruptors.contains(controller))
              )
            )
      yield assertTrue(before == 0, rejected.forall(_.isLeft), empty.isEmpty) &&
        outcomes.foldLeft(assertTrue(true))(_ && _)
    }
  ) @@ TestAspect.timeout(20.seconds)
